"""Run the complete Step20 acceptance matrix and retain each request and SQL snapshot."""
import hashlib
import importlib.util
import json
from pathlib import Path
import sys
import uuid

from step19_support import Fixture, ROOT, load_env, require, save, snapshot, stamp

spec = importlib.util.spec_from_file_location("load_step19", Path(__file__).with_name("load-step19.py"))
load = importlib.util.module_from_spec(spec)
spec.loader.exec_module(load)

LEVELS = (10, 100, 500)
SCENARIOS = ("same-order", "same-driver")
ROUNDS = 3


def audit_cell(directory, scenario, concurrency, run_id):
    """Recompute verdicts from raw requests; missing rounds cannot pass the matrix."""
    directory = Path(directory)
    reports = sorted(directory.glob("round-*.json"))
    require(len(reports) == ROUNDS, "Missing or extra round reports")
    rounds = []
    for index, path in enumerate(reports, 1):
        report = json.loads(path.read_text(encoding="utf-8"))
        require((report["runId"], report["scenario"], report["concurrency"], report["round"])
                == (run_id, scenario, concurrency, index), "Round identity mismatch")
        raw = directory / f"round-{index:02d}.jsonl"
        rows = [json.loads(line) for line in raw.read_text(encoding="utf-8").splitlines()]
        require(len(rows) == report["requests"] == concurrency, "Request count mismatch")
        require({r["index"] for r in rows} == set(range(concurrency)), "Missing/duplicate request index")
        require(len({r["requestId"] for r in rows}) == concurrency, "Duplicate request ID")
        require(len({r["driverId"] for r in rows}) == (concurrency if scenario == "same-order" else 1),
                "Unexpected driver cardinality")
        require(len({r["orderId"] for r in rows}) == (1 if scenario == "same-order" else concurrency),
                "Unexpected order cardinality")
        verdict = load.judge(scenario, rows, report["orders"], report["activeOrders"])
        require(verdict["passed"] and report["verdict"] == verdict, "Raw evidence failed race invariants")
        rounds.append({"round": index, "requests": len(rows), "verdict": verdict,
                       "outcomes": report["outcomes"], "latencyMs": report["latencyMs"],
                       "elapsedSeconds": report["elapsedSeconds"],
                       "completedRequestsPerSecond": report["completedRequestsPerSecond"],
                       "winner": report["activeOrders"][0],
                       "report": str(path.relative_to(ROOT)), "rawResults": str(raw.relative_to(ROOT)),
                       "reportSha256": hashlib.sha256(path.read_bytes()).hexdigest(),
                       "rawSha256": hashlib.sha256(raw.read_bytes()).hexdigest()})
    return {"scenario": scenario, "concurrency": concurrency, "passed": True, "rounds": rounds}


def matrix_complete(cells):
    expected = {(s, c) for c in LEVELS for s in SCENARIOS}
    return (len(cells) == len(expected)
            and {(c["scenario"], c["concurrency"]) for c in cells} == expected
            and all(c["passed"] and len(c["rounds"]) == ROUNDS for c in cells))


def main():
    load_env()
    fixture = Fixture(str(uuid.uuid4()))
    output = fixture.directory / "step20-result.json"
    result = {"step": 20, "runId": fixture.run_id, "startedAt": stamp(), "passed": False,
              "cleanupPassed": False, "levels": LEVELS, "roundsPerCell": ROUNDS, "cells": [],
              "fixture": {"drivers": 500, "orders": 500, "passengers": 2},
              "scope": "Gateway/JWT/eligibility/Redis/MySQL acceptance; SQL-seeded notices; no MQ load"}
    print(f"runId={fixture.run_id} result={output}", flush=True)
    with fixture.locked():
        save(output, result)
        try:
            save(fixture.directory / "environment.json", snapshot())
            print("Seeding 502 isolated accounts and 500 orders...", flush=True)
            fixture.seed(500, 500)
            print("Fixture ready", flush=True)
            for concurrency in LEVELS:
                for scenario in SCENARIOS:
                    print(f"Running {scenario}: concurrency={concurrency}, rounds={ROUNDS}", flush=True)
                    cell = load.run(fixture, scenario, concurrency, ROUNDS)
                    result["cells"].append(audit_cell(cell["directory"], scenario, concurrency, fixture.run_id))
                    save(output, result)
                    print(f"Passed {scenario}/{concurrency}", flush=True)
            result["matrixPassed"] = matrix_complete(result["cells"])
            require(result["matrixPassed"], "Incomplete Step20 matrix")
        except Exception as error:
            # Never print exception text from network/configuration libraries containing credentials.
            result["errorType"] = type(error).__name__
            print(f"Verification failed: {type(error).__name__}; inspect batch evidence", flush=True)
        finally:
            save(output, result)
            if fixture.data:
                try:
                    print("Cleaning this batch and checking remaining rows...", flush=True)
                    fixture.cleanup()
                    result["cleanupPassed"] = fixture.data.get("cleanupVerified") is True
                except Exception as error:
                    result["cleanupErrorType"] = type(error).__name__
                    print("Cleanup failed; retain manifest and use step19-data.py cleanup --run-id "
                          + fixture.run_id, flush=True)
            result["finishedAt"] = stamp()
            result["passed"] = result.get("matrixPassed", False) and result["cleanupPassed"]
            save(output, result)
            save(ROOT / "logs/step20-result.json", result)
    print(json.dumps({"runId": fixture.run_id, "passed": result["passed"],
                      "completedCells": len(result["cells"]), "cleanupPassed": result["cleanupPassed"],
                      "result": str(output)}, indent=2), flush=True)
    return 0 if result["passed"] else 1


if __name__ == "__main__":
    sys.exit(main())
