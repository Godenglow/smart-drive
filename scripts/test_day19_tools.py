"""Guard against false-positive race results and cleanup beyond the owned batch."""
import importlib.util
import hashlib
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import uuid

import day19_support as support

spec = importlib.util.spec_from_file_location("load_day19", Path(__file__).with_name("load-day19.py"))
load = importlib.util.module_from_spec(spec)
spec.loader.exec_module(load)


class RaceVerdictTest(unittest.TestCase):
    def setUp(self):
        self.rows = [dict(httpStatus=200, code="0", state="ACCEPTED", orderId=7, driverId=11,
                          responseOrderId=7, responseDriverId=11),
                     dict(httpStatus=409, code="ORDER_ALREADY_TAKEN", state=None, orderId=7, driverId=12)]
        self.orders = [dict(id=7, status="ACCEPTED", driverId=11)]
        self.active = [dict(orderId=7, driverId=11)]

    def judge(self):
        return load.judge("same-order", self.rows, self.orders, self.active)["passed"]

    def test_one_http_winner_and_matching_committed_owner_pass(self):
        self.assertTrue(self.judge())

    def test_timeout_is_not_counted_as_expected_conflict(self):
        self.rows[1].update(httpStatus=0, code="TRANSPORT_ERROR")
        self.assertFalse(self.judge())

    def test_http_success_cannot_hide_wrong_database_owner(self):
        self.orders[0]["driverId"] = 12
        self.assertFalse(self.judge())

    def test_second_occupation_fails_even_with_single_http_success(self):
        self.active.append(dict(orderId=8, driverId=12))
        self.assertFalse(self.judge())

    def test_wrong_success_response_identity_fails(self):
        self.rows[0]["responseOrderId"] = 8
        self.assertFalse(self.judge())

    def test_same_driver_requires_busy_and_unchanged_losing_orders(self):
        self.rows[1].update(code="DRIVER_BUSY", orderId=8, driverId=11)
        self.orders.append(dict(id=8, status="WAITING", driverId=None))
        self.assertTrue(load.judge("same-driver", self.rows, self.orders, self.active)["passed"])
        self.orders[1]["driverId"] = 11
        self.assertFalse(load.judge("same-driver", self.rows, self.orders, self.active)["passed"])


class FixtureSafetyTest(unittest.TestCase):
    def setUp(self):
        self.temp_root = support.ROOT / "logs/day19/tool-tests"
        self.temp_root.mkdir(parents=True, exist_ok=True)

    def test_non_uuid_cannot_select_an_arbitrary_cleanup_directory(self):
        with self.assertRaises(ValueError):
            support.Fixture("../../another-directory")

    def test_unsettled_events_preserve_journal_and_prevent_deletes(self):
        with tempfile.TemporaryDirectory(dir=self.temp_root) as directory, patch.object(support, "RUNS", Path(directory)):
            fixture = support.Fixture(str(uuid.uuid4()))
            fixture.data = dict(status="ready", accounts=[])
            fixture.save()
            with patch.object(fixture, "validate_accounts"), patch.object(fixture, "owned_orders", return_value=[7]), \
                    patch.object(fixture, "settle", side_effect=RuntimeError("pending")), patch.object(support, "sql") as sql:
                with self.assertRaisesRegex(RuntimeError, "pending"):
                    fixture.cleanup()
                sql.assert_not_called()
                self.assertEqual("ready", fixture.data["status"])
                self.assertTrue(fixture.path.exists())

    def test_reset_refuses_external_active_order_before_mutating_sql(self):
        with tempfile.TemporaryDirectory(dir=self.temp_root) as directory, patch.object(support, "RUNS", Path(directory)):
            fixture = support.Fixture(str(uuid.uuid4()))
            fixture.data = dict(status="ready", fixtureSha256=hashlib.sha256(support.CONFIG_PATH.read_bytes()).hexdigest(),
                                syntheticOrders=[dict(id=7)], accounts=[dict(id=11, role="DRIVER")])
            with patch.object(fixture, "validate_accounts"), patch.object(fixture, "owned_orders", return_value=[7]), \
                    patch.object(support, "number", return_value=1), patch.object(support, "sql") as sql:
                with self.assertRaisesRegex(RuntimeError, "unrelated active order"):
                    fixture.reset()
                sql.assert_not_called()


if __name__ == "__main__":
    unittest.main()
