"""Prevent partial or inconsistent evidence from passing the formal matrix."""
import importlib.util
import json
from pathlib import Path
import tempfile
import threading
import unittest
from unittest.mock import MagicMock, patch

import day19_support as support
from day19_support import ROOT, save

spec = importlib.util.spec_from_file_location("verify_day20", Path(__file__).with_name("verify-day20.py"))
verify = importlib.util.module_from_spec(spec)
spec.loader.exec_module(verify)


class MatrixEvidenceTest(unittest.TestCase):
    def test_complete_matrix_requires_all_six_cells(self):
        cells = [dict(scenario=s, concurrency=c, passed=True, rounds=[{}, {}, {}])
                 for c in verify.LEVELS for s in verify.SCENARIOS]
        self.assertTrue(verify.matrix_complete(cells))
        self.assertFalse(verify.matrix_complete(cells[:-1]))
        self.assertFalse(verify.matrix_complete(cells[:-1] + [cells[0]]))
        cells[-1]["rounds"].pop()
        self.assertFalse(verify.matrix_complete(cells))

    def make_evidence(self, directory):
        for index in range(1, 4):
            rows = [dict(index=i, requestId=f"request-{i}", driverId=11+i, orderId=7,
                         httpStatus=200 if i == 0 else 409,
                         code="0" if i == 0 else "ORDER_ALREADY_TAKEN",
                         state="ACCEPTED" if i == 0 else None,
                         responseOrderId=7 if i == 0 else None,
                         responseDriverId=11 if i == 0 else None) for i in range(10)]
            orders = [dict(id=7, status="ACCEPTED", driverId=11)]
            active = [dict(orderId=7, driverId=11)]
            save(directory / f"round-{index:02d}.json", dict(
                runId="run", scenario="same-order", concurrency=10, round=index, requests=10,
                orders=orders, activeOrders=active, verdict=verify.load.judge("same-order", rows, orders, active),
                outcomes={"200/0": 1, "409/ORDER_ALREADY_TAKEN": 9}, latencyMs={},
                elapsedSeconds=1, completedRequestsPerSecond=10))
            (directory / f"round-{index:02d}.jsonl").write_text(
                "".join(json.dumps(r) + "\n" for r in rows), encoding="utf-8")

    def audit_modified(self, modify=None):
        parent = ROOT / "logs/day20/tool-tests"
        parent.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(dir=parent) as name:
            directory = Path(name)
            self.make_evidence(directory)
            if modify:
                modify(directory)
            return verify.audit_cell(directory, "same-order", 10, "run")

    def test_valid_raw_evidence_passes_and_records_hashes(self):
        cell = self.audit_modified()
        self.assertTrue(cell["passed"])
        self.assertEqual(3, len(cell["rounds"]))
        self.assertEqual(64, len(cell["rounds"][0]["rawSha256"]))

    def test_missing_round_fails(self):
        with self.assertRaisesRegex(RuntimeError, "round reports"):
            self.audit_modified(lambda d: (d / "round-03.json").unlink())

    def test_truncated_raw_results_fail(self):
        with self.assertRaisesRegex(RuntimeError, "count mismatch"):
            self.audit_modified(lambda d: (d / "round-01.jsonl").write_text("", encoding="utf-8"))

    def test_recorded_pass_cannot_hide_changed_database_owner(self):
        def modify(directory):
            path = directory / "round-01.json"
            report = json.loads(path.read_text(encoding="utf-8"))
            report["orders"][0]["driverId"] = 12
            save(path, report)
        with self.assertRaisesRegex(RuntimeError, "race invariants"):
            self.audit_modified(modify)


class MatrixLifecycleTest(unittest.TestCase):
    def run_main(self, run_error=None, cleanup_error=None):
        fixture = MagicMock()
        fixture.run_id = "test-run"
        fixture.directory = ROOT / "logs/day20/mock-run"
        fixture.data = {"cleanupVerified": cleanup_error is None}
        if cleanup_error:
            fixture.cleanup.side_effect = cleanup_error
        with patch.object(verify, "Fixture", return_value=fixture), patch.object(verify, "load_env"), \
                patch.object(verify, "snapshot", return_value={}), patch.object(verify, "save") as save_mock, \
                patch.object(verify.load, "run", return_value={"directory": "unused"}, side_effect=run_error), \
                patch.object(verify, "audit_cell", side_effect=lambda d, s, c, r: dict(
                    scenario=s, concurrency=c, passed=True, rounds=[{}, {}, {}])), patch("builtins.print"):
            code = verify.main()
            result = save_mock.call_args.args[1]
        fixture.cleanup.assert_called_once()
        return code, result

    def test_all_rounds_and_cleanup_required_for_success(self):
        code, result = self.run_main()
        self.assertEqual(0, code)
        self.assertTrue(result["passed"])
        self.assertEqual(6, len(result["cells"]))

    def test_failed_matrix_still_cleans_and_exits_nonzero(self):
        code, result = self.run_main(run_error=RuntimeError("failed round"))
        self.assertEqual(1, code)
        self.assertFalse(result["passed"])
        self.assertTrue(result["cleanupPassed"])

    def test_cleanup_failure_cannot_pass_complete_matrix(self):
        code, result = self.run_main(cleanup_error=RuntimeError("unsettled"))
        self.assertEqual(1, code)
        self.assertTrue(result["matrixPassed"])
        self.assertFalse(result["cleanupPassed"])
        self.assertFalse(result["passed"])


class PreparationTest(unittest.TestCase):
    def test_http_context_and_opener_are_reused_without_sharing_request_headers(self):
        opener = MagicMock()
        response = opener.open.return_value
        response.status = 200
        response.read.return_value = b'{"code":"0"}'
        response.headers = {}
        with patch.object(support, "HTTP_CLIENT", threading.local()), \
                patch.object(support.urllib.request, "build_opener", return_value=opener) as build:
            support.http("GET", "http://localhost/a", headers={"Authorization": "Bearer first"})
            support.http("GET", "http://localhost/b", headers={"Authorization": "Bearer second"})
        build.assert_called_once()
        self.assertIs(support.HTTP_SSL_CONTEXT, build.call_args.args[1]._context)
        self.assertEqual("Bearer first", opener.open.call_args_list[0].args[0].get_header("Authorization"))
        self.assertEqual("Bearer second", opener.open.call_args_list[1].args[0].get_header("Authorization"))

    def test_bulk_account_validation_checks_every_identity_and_bcrypt(self):
        fixture = object.__new__(support.Fixture)
        fixture.data = {"accounts": [dict(id=11, phone="19900000011", role="DRIVER"),
                                      dict(id=12, phone="19900000012", role="PASSENGER")]}
        with patch.object(support, "number", return_value=2) as number:
            fixture.validate_accounts()
        number.assert_called_once()
        statement = number.call_args.args[0]
        for value in ("id=11", "id=12", "19900000011", "19900000012", "DRIVER", "PASSENGER", "password_hash REGEXP"):
            self.assertIn(value, statement)
        with patch.object(support, "number", return_value=1):
            with self.assertRaisesRegex(RuntimeError, "BCrypt mismatch"):
                fixture.validate_accounts()


if __name__ == "__main__":
    unittest.main()
