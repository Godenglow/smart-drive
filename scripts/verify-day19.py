"""Live Gateway/MySQL/MQ regression and two repeatability smoke rounds per race shape."""
from concurrent.futures import ThreadPoolExecutor
import importlib.util
import json
import os
import traceback
from urllib.parse import quote
import uuid

from day19_support import (CONFIG, ROOT, Fixture, api, gateway, http, load_env, mq, number, queue, require,
                           save, snapshot, sql, stamp, wait_until)


def live(fixture):
    passenger, outsider = fixture.passengers
    driver, second, _ = fixture.drivers
    p, other, d, d2 = [fixture.token(a) for a in (passenger, outsider, driver, second)]
    for a in fixture.drivers:
        api("PUT", "/api/drivers/me/online", fixture.token(a), CONFIG["pickup"])
    checks = []

    def check(label, method, path, token=None, body=None, expected=200, code="0"):
        result = api(method, path, token, body, expected, code)
        checks.append({"check": label, "httpStatus": expected, "code": code})
        return result

    check("missing authentication", "GET", "/api/orders/mine", expected=401, code="UNAUTHORIZED")
    check("invalid token", "GET", "/api/orders/mine", "invalid", expected=401, code="UNAUTHORIZED")
    check("passenger cannot go online", "PUT", "/api/drivers/me/online", p, CONFIG["pickup"], 403, "FORBIDDEN")
    check("driver cannot create order", "POST", "/api/orders", d, {}, 403, "FORBIDDEN")
    for base in (gateway(), 'http://127.0.0.1:8082'):
        for path in ('/api/%64rivers/me/status', '/api/drivers;x=1/me/status'):
            status, response, _ = http('GET', base + path, headers={'Authorization': 'Bearer ' + p})
            require(status == 400 and response.get('code') == 'INVALID_PARAM', 'Alternate path bypassed authorization')
            checks.append({'check': 'reject alternate driver path', 'base': base, 'path': path, 'httpStatus': status})
    for base in (gateway(), 'http://127.0.0.1:8081'):
        status, response, _ = http('POST', base + '/api/auth/login', b'x', {'Content-Type': 'text/plain'})
        require(status == 415 and response.get('code') == 'UNSUPPORTED_MEDIA_TYPE', 'Unsupported login content type returned wrong status')
        checks.append({'check': 'unsupported login content type', 'base': base, 'httpStatus': status})
    for action in ('register', 'login'):
        check('reject oversized UTF-8 password on ' + action, 'POST', '/api/auth/' + action,
              body={'phone': passenger['phone'], 'password': '中' * 25, 'role': 'PASSENGER'},
              expected=400, code='INVALID_PARAM')
    request_id = str(uuid.uuid5(uuid.UUID(fixture.run_id), "http-idempotency"))
    fixture.data["apiRequests"].append(request_id)
    fixture.save()
    body = {"requestId": request_id,
            "pickup": dict(CONFIG["pickup"], name=fixture.marker),
            "dropoff": dict(CONFIG["dropoff"], name=fixture.marker)}
    with ThreadPoolExecutor(max_workers=8) as pool:
        created = list(pool.map(lambda _: api("POST", "/api/orders", p, body, 201), range(8)))
    order_id = created[0]["id"]
    require(all(o["id"] == order_id for o in created), "Concurrent create duplicated an order")
    require(number(f"SELECT COUNT(*) FROM order_db.ride_order WHERE passenger_id={passenger['id']} "
                   f"AND request_id='{request_id}'") == 1, "Idempotency key duplicated in database")
    require(number(f"SELECT COUNT(*) FROM order_db.outbox_event WHERE aggregate_id={order_id}") == 1,
            "Idempotency produced duplicate outbox events")
    checks.append({"check": "8 concurrent creates", "singleOrderId": order_id, "outboxEvents": 1})
    fixture.settle([order_id])
    notices = number(f"SELECT COUNT(*) FROM driver_db.order_notice WHERE order_id={order_id}")
    require(notices == len(fixture.drivers), "Real matching should notify all three fixture drivers")
    event = json.loads(sql(f"SELECT JSON_OBJECT('eventId',event_id,'payload',payload,'status',status) "
                           f"FROM order_db.outbox_event WHERE aggregate_id={order_id}"))
    require(event["status"] == "SENT", "Outbox not sent")
    # Wait for each injected delivery to be ACKed, rather than assuming publish=routed means consumed.
    wait_until(lambda: queue().get("message_stats", {}).get("ack", 0) >= 1, "Original ACK not visible")
    baseline = queue().get("message_stats", {}).get("ack", 0)
    vhost = quote(os.environ.get("RABBITMQ_VHOST", "/"), safe="")
    payload = event["payload"] if isinstance(event["payload"], str) else json.dumps(event["payload"])
    for _ in range(2):
        result = mq("POST", f"/api/exchanges/{vhost}/ride.events/publish", {
            "routing_key": "order.created", "payload": payload, "payload_encoding": "string",
            "properties": {"content_type": "application/json", "delivery_mode": 2, "message_id": event["eventId"]}})
        require(result.get("routed"), "Duplicate event was not routed")
    wait_until(lambda: queue().get("message_stats", {}).get("ack", 0) >= baseline + 2,
               "Duplicate deliveries were not ACKed")
    fixture.settle([order_id])
    require(number(f"SELECT COUNT(*) FROM driver_db.order_notice WHERE order_id={order_id}") == notices,
            "Duplicate deliveries created extra notices")
    require(number(f"SELECT COUNT(*) FROM driver_db.processed_order_event WHERE order_id={order_id} "
                   f"AND event_id='{event['eventId']}'") == 1, "Duplicate event changed dedup identity")
    checks.append({"check": "two real duplicate deliveries ACKed", "notices": notices, "processedEvents": 1})
    path = f"/api/orders/{order_id}"
    check("other passenger detail forbidden", "GET", path, other, expected=403, code="FORBIDDEN")
    candidate = check("candidate detail", "GET", path, d2)
    require(all(candidate.get(k) is None for k in ("passengerId", "requestId", "driverId")), "Candidate privacy leak")
    check("cannot pay WAITING", "POST", path + "/pay-demo", p, expected=409, code="ORDER_STATUS_INVALID")
    accepted = check("accept", "POST", path + "/accept", d)
    require(accepted["status"] == "ACCEPTED" and accepted["driverId"] == driver["id"], "Acceptance owner mismatch")
    check("loser cannot accept", "POST", path + "/accept", d2, expected=409, code="ORDER_ALREADY_TAKEN")
    check("loser cannot start", "POST", path + "/start", d2, expected=403, code="FORBIDDEN")
    check("cannot finish ACCEPTED", "POST", path + "/finish", d, expected=409, code="ORDER_STATUS_INVALID")
    for action, token, state in (("start", d, "IN_SERVICE"), ("finish", d, "FINISHED")):
        result = check(action, "POST", path + "/" + action, token)
        require(result["status"] == state, "State transition mismatch")
        check("duplicate " + action, "POST", path + "/" + action, token, expected=409, code="ORDER_STATUS_INVALID")
    require(number(f"SELECT COUNT(*) FROM order_db.driver_active_order WHERE driver_id={driver['id']}") == 0,
            "Finish failed to release driver")
    check("other passenger cannot pay", "POST", path + "/pay-demo", other, expected=403, code="FORBIDDEN")
    result = check("demo pay", "POST", path + "/pay-demo", p)
    require(result["status"] == "PAID", "Final state is not PAID")
    check("duplicate pay", "POST", path + "/pay-demo", p, expected=409, code="ORDER_STATUS_INVALID")
    check("cannot cancel PAID", "POST", path + "/cancel", p, expected=409, code="ORDER_NOT_WAITING")
    require(api("GET", "/api/orders/mine", other)["total"] == 0, "Passenger history isolation failed")
    row = json.loads(sql(f"SELECT JSON_OBJECT('status',status,'driverId',driver_id,'amount',amount) "
                         f"FROM order_db.ride_order WHERE id={order_id}"))
    require(row["status"] == "PAID" and row["driverId"] == driver["id"] and float(row["amount"]) == 50,
            "Final database state mismatch")
    return {"checks": checks, "orderId": order_id, "database": row, "duplicateDeliveries": 2}


def main():
    load_env()
    fixture = Fixture(str(uuid.uuid4()))
    report = {"runId": fixture.run_id, "startedAt": stamp(), "passed": False, "cleanupPassed": False}
    failed = False
    with fixture.locked():
        try:
            save(fixture.directory / "environment.json", snapshot())
            fixture.seed(3, 3)
            report["live"] = live(fixture)
            spec = importlib.util.spec_from_file_location("day19_load", ROOT / "scripts/load-day19.py")
            module = importlib.util.module_from_spec(spec)
            spec.loader.exec_module(module)
            report["smoke"] = [module.run(fixture, scenario, 3, 2) for scenario in ("same-order", "same-driver")]
            report["passed"] = True
        except Exception as error:
            failed = True
            report["errorType"] = type(error).__name__
            traceback.print_exc()
        finally:
            if fixture.data:
                try:
                    fixture.cleanup()
                    report["cleanupPassed"] = True
                    report["cleanedAccounts"] = len(fixture.data["accounts"])
                    report["cleanedOrders"] = len(fixture.data.get("cleanedOrderIds", []))
                except Exception as error:
                    failed = True
                    report["cleanupErrorType"] = type(error).__name__
                    traceback.print_exc()
            report["finishedAt"] = stamp()
            save(fixture.directory / "verification.json", report)
            save(ROOT / "logs/day19-http-result.json", report)
    print(json.dumps(report, ensure_ascii=False, indent=2))
    require(not failed and report["passed"] and report["cleanupPassed"], "Day19 live verification failed; see logs/day19-http-result.json")


if __name__ == "__main__":
    main()
