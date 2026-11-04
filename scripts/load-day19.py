"""Repeatable Gateway acceptance races; setup/SQL assertions are outside the timed interval."""
from concurrent.futures import ThreadPoolExecutor
import argparse
from collections import Counter
import json
import math
import threading
import time
import uuid

from day19_support import (CONFIG, Fixture, api, gateway, http, load_env, require, save,
                           snapshot, sql, stamp)


def percentile(values, fraction):
    return sorted(values)[max(0, math.ceil(len(values) * fraction) - 1)]


def judge(scenario, rows, orders, active):
    """Require HTTP outcomes AND their exact committed database ownership to agree."""
    success = [r for r in rows if r["httpStatus"] == 200 and r["code"] == "0" and r["state"] == "ACCEPTED"
               and r["responseOrderId"] == r["orderId"] and r["responseDriverId"] == r["driverId"]]
    conflict = "ORDER_ALREADY_TAKEN" if scenario == "same-order" else "DRIVER_BUSY"
    bad = [r for r in rows if r not in success and not (r["httpStatus"] == 409 and r["code"] == conflict)]
    errors = []
    if len(success) != 1:
        errors.append(f"Expected exactly one successful request, got {len(success)}")
    if bad:
        errors.append(f"Unexpected/transport outcomes: {len(bad)}")
    accepted = [o for o in orders if o["status"] == "ACCEPTED"]
    if not {r["orderId"] for r in rows}.issubset({o["id"] for o in orders}):
        errors.append("An attempted order is missing from the database")
    if len(accepted) != 1 or len(active) != 1:
        errors.append("Expected exactly one ACCEPTED order and one active occupation")
    if len(success) == len(accepted) == len(active) == 1:
        winner = success[0]
        pair = (winner["orderId"], winner["driverId"])
        if (accepted[0]["id"], accepted[0]["driverId"]) != pair or (active[0]["orderId"], active[0]["driverId"]) != pair:
            errors.append("HTTP winner and committed ownership differ")
    for order in orders:
        if order not in accepted and (order["status"] != "WAITING" or order["driverId"] is not None):
            errors.append("A losing order changed state or ownership")
    return {"passed": not errors, "errors": errors, "successes": len(success),
            "expectedConflicts": len(rows) - len(success) - len(bad), "unexpected": len(bad)}


def run_round(fixture, scenario, concurrency, round_number, output):
    fixture.reset()
    candidates = fixture.drivers[:concurrency] if scenario == "same-order" else fixture.drivers[:1]
    with ThreadPoolExecutor(max_workers=min(16, len(candidates))) as setup_pool:
        tokens = list(setup_pool.map(fixture.token, candidates))
        # Renew online TTL immediately before the barrier, outside request timing.
        list(setup_pool.map(lambda pair: api("PUT", "/api/drivers/me/online", pair[1], CONFIG["pickup"]),
                            zip(candidates, tokens)))
    orders = fixture.data["syntheticOrders"]
    attempts = [(candidates[i]["id"], orders[0]["id"], tokens[i]) if scenario == "same-order"
                else (candidates[0]["id"], orders[i]["id"], tokens[0]) for i in range(concurrency)]
    epoch = [0.0]
    gate = threading.Barrier(concurrency + 1, timeout=60,
                             action=lambda: epoch.__setitem__(0, time.perf_counter()))

    def request(index, attempt):
        driver, order_id, token = attempt
        request_id = str(uuid.uuid4())
        gate.wait()
        start = time.perf_counter()
        row = {"index": index, "driverId": driver, "orderId": order_id, "requestId": request_id,
               "startOffsetMs": round((start - epoch[0]) * 1000, 3), "httpStatus": 0, "code": "TRANSPORT_ERROR",
               "state": None, "responseOrderId": None, "responseDriverId": None, "responseRequestId": None}
        try:
            status, body, headers = http("POST", f"{gateway()}/api/orders/{order_id}/accept", headers={
                "Authorization": "Bearer " + token, "X-Request-Id": request_id})
            data = body.get("data") or {}
            row.update(httpStatus=status, code=str(body.get("code")), state=data.get("status"),
                       responseOrderId=data.get("id"), responseDriverId=data.get("driverId"),
                       responseRequestId=next((v for k, v in headers.items() if k.lower() == "x-request-id"), None))
        except Exception as error:
            # Persist transport failures, without exception messages that might contain credentials.
            row["transportError"] = type(error).__name__
        row["durationMs"] = round((time.perf_counter() - start) * 1000, 3)
        return row

    started = stamp()
    with ThreadPoolExecutor(max_workers=concurrency) as pool:
        futures = [pool.submit(request, i, attempt) for i, attempt in enumerate(attempts)]
        gate.wait()
        rows = [f.result(timeout=CONFIG["requestTimeoutSeconds"] + 10) for f in futures]
        elapsed = time.perf_counter() - epoch[0]
    # Preserve the raw records before performing SQL so assertion/DB failures still leave evidence.
    raw_path = output.with_suffix(".jsonl")
    raw_path.write_text("".join(json.dumps(row) + "\n" for row in rows), encoding="utf-8")
    ids = ",".join(str(int(o["id"])) for o in orders)
    drivers = ",".join(str(a["id"]) for a in fixture.drivers)
    final_orders = [json.loads(line) for line in sql(
        f"SELECT JSON_OBJECT('id',id,'status',status,'driverId',driver_id) FROM order_db.ride_order WHERE id IN ({ids}) ORDER BY id").splitlines()]
    active = [json.loads(line) for line in sql(
        f"SELECT JSON_OBJECT('orderId',order_id,'driverId',driver_id) FROM order_db.driver_active_order "
        f"WHERE driver_id IN ({drivers}) OR order_id IN ({ids}) ORDER BY order_id").splitlines()]
    verdict = judge(scenario, rows, final_orders, active)
    latencies = [r["durationMs"] for r in rows]
    report = {"runId": fixture.run_id, "startedAt": started, "scenario": scenario,
              "round": round_number, "concurrency": concurrency, "requests": len(rows),
              "elapsedSeconds": elapsed, "completedRequestsPerSecond": len(rows) / elapsed,
              "latencyMs": {p: percentile(latencies, q) for p, q in (("p50", .5), ("p95", .95), ("p99", .99))},
              "outcomes": dict(Counter(f"{r['httpStatus']}/{r['code']}" for r in rows)),
              "verdict": verdict, "orders": final_orders, "activeOrders": active,
              "rawResults": raw_path.name, "client": "Python urllib, one TCP connection per request; no retries",
              "scope": "Synthetic WAITING orders and explicit candidate notices; real HTTP/JWT/Redis/MySQL acceptance"}
    save(output, report)
    require(verdict["passed"], f"Race invariants failed; see {output}")
    return report


def run(fixture, scenario, concurrency, rounds):
    require(fixture.data and fixture.data["status"] == "ready", "Fixture is not ready")
    require(2 <= concurrency <= 500 and 1 <= rounds <= 100, "concurrency: 2..500; rounds: 1..100")
    require(len(fixture.drivers) >= (concurrency if scenario == "same-order" else 1), "Insufficient fixture drivers")
    require(len(fixture.data["syntheticOrders"]) >= (concurrency if scenario == "same-driver" else 1),
            "Insufficient fixture orders")
    output_dir = fixture.directory / ("load-" + str(uuid.uuid4()))
    output_dir.mkdir()
    save(output_dir / "environment.json", snapshot())
    reports = [run_round(fixture, scenario, concurrency, i + 1, output_dir / f"round-{i+1:02d}.json")
               for i in range(rounds)]
    result = {"passed": True, "scenario": scenario, "concurrency": concurrency, "rounds": len(reports),
              "directory": str(output_dir), "runId": fixture.run_id}
    save(output_dir / "summary.json", result)
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--run-id", required=True)
    parser.add_argument("--scenario", choices=("same-order", "same-driver"), required=True)
    parser.add_argument("--concurrency", type=int, default=10)
    parser.add_argument("--rounds", type=int, default=3)
    args = parser.parse_args()
    load_env()
    fixture = Fixture(args.run_id)
    with fixture.locked():
        print(json.dumps(run(fixture, args.scenario, args.concurrency, args.rounds), indent=2))


if __name__ == "__main__":
    main()
