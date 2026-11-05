"""Reject destructive demo teardown when ownership cannot be established."""
import importlib.util
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import uuid

import day19_support as support

spec = importlib.util.spec_from_file_location('demo_data', Path(__file__).with_name('demo-data.py'))
demo = importlib.util.module_from_spec(spec)
spec.loader.exec_module(demo)


class DemoOwnershipTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(dir=support.ROOT / 'logs')
        self.addCleanup(self.temp.cleanup)
        self.runs = patch.object(support, 'RUNS', Path(self.temp.name))
        self.runs.start()
        self.addCleanup(self.runs.stop)
        self.fixture = demo.DemoFixture(str(uuid.uuid4()))
        self.fixture.data = {'status': 'ready', 'accounts': [
            {'id': 51, 'phone': '19912345678', 'role': 'PASSENGER'},
            {'id': 52, 'phone': '19912345679', 'role': 'DRIVER'}]}

    def test_ownership_uses_dedicated_passenger_id(self):
        with patch.object(support, 'sql', return_value='61\n62') as sql:
            self.assertEqual(self.fixture.owned_orders(), [61, 62])
            self.assertEqual(sql.call_args.args[0],
                             'SELECT id FROM order_db.ride_order WHERE passenger_id IN (51)')

    def test_no_registered_passenger_has_no_order_scope(self):
        self.fixture.data['accounts'][0].pop('id')
        with patch.object(support, 'sql') as sql:
            self.assertEqual(self.fixture.owned_orders(), [])
            sql.assert_not_called()

    def test_external_assigned_driver_prevents_any_delete(self):
        with patch.object(self.fixture, 'owned_orders', return_value=[61]), \
             patch.object(self.fixture, 'validate_accounts'), patch.object(self.fixture, 'settle'), \
             patch.object(demo, 'number', return_value=1), patch.object(support, 'sql') as sql:
            with self.assertRaisesRegex(RuntimeError, 'external driver'):
                self.fixture.cleanup()
            sql.assert_not_called()

    def test_unsettled_message_prevents_any_delete(self):
        with patch.object(self.fixture, 'owned_orders', return_value=[61]), \
             patch.object(self.fixture, 'validate_accounts'), \
             patch.object(self.fixture, 'settle', side_effect=RuntimeError('pending MQ')), \
             patch.object(support, 'sql') as sql:
            with self.assertRaisesRegex(RuntimeError, 'pending MQ'):
                self.fixture.cleanup()
            sql.assert_not_called()

    def test_external_orders_active_association_or_notices_prevent_delete(self):
        for counts in ([1], [0, 1], [0, 0, 1]):
            with self.subTest(counts=counts), patch.object(self.fixture, 'validate_accounts'), \
                 patch.object(demo, 'number', side_effect=counts), patch.object(support, 'sql') as sql:
                with self.assertRaisesRegex(RuntimeError, 'External'):
                    self.fixture._cleanup_demo([61])
                sql.assert_not_called()

    def test_wrong_account_identity_prevents_teardown(self):
        with patch.object(self.fixture, 'owned_orders', return_value=[61]), \
             patch.object(self.fixture, 'validate_accounts', side_effect=RuntimeError('identity mismatch')), \
             patch.object(support, 'sql') as sql:
            with self.assertRaisesRegex(RuntimeError, 'identity mismatch'):
                self.fixture.cleanup()
            sql.assert_not_called()

    def test_journaled_deleted_ids_survive_interrupted_cleanup(self):
        self.fixture.data.update(status='cleaned', demoCleanedOrderIds=[61, 62], cleanedOrderIds=[])
        with patch.object(self.fixture, 'save'):
            self.fixture.cleanup()
        self.assertEqual(self.fixture.data['cleanedOrderIds'], [61, 62])

    def test_empty_demo_with_external_notice_keeps_accounts(self):
        with patch.object(self.fixture, 'owned_orders', return_value=[]), \
             patch.object(self.fixture, 'validate_accounts'), patch.object(demo, 'number', return_value=1), \
             patch.object(support, 'sql') as sql:
            with self.assertRaisesRegex(RuntimeError, 'External notices'):
                self.fixture.cleanup()
            sql.assert_not_called()


if __name__ == '__main__':
    unittest.main()
