"""A successful HTTP batch must prove exact durable order/event/notice correspondence."""
import copy
import importlib.util
from pathlib import Path
import unittest
import tempfile
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('step21', Path(__file__).with_name('verify-step21.py'))
step21 = importlib.util.module_from_spec(spec)
spec.loader.exec_module(step21)
audit_spec = importlib.util.spec_from_file_location('audit_step21', Path(__file__).with_name('audit-step21.py'))
audit = importlib.util.module_from_spec(audit_spec)
audit_spec.loader.exec_module(audit)


class SustainedEvidenceTest(unittest.TestCase):
    def setUp(self):
        self.rows = [dict(index=i, requestId=f'request-{i}', httpStatus=201, code='0', state='WAITING',
                          responseOrderId=10+i, passengerId=7, responsePassengerId=7,
                          responseRequestId=f'request-{i}') for i in range(2)]
        self.db = [dict(id=10+i, requestId=f'request-{i}', status='WAITING', driverId=None,
                        eventId=f'event-{i}', eventStatus='SENT', processedEventId=f'event-{i}',
                        attempts=1, noticeDrivers='8,9', notificationDelayMs=20) for i in range(2)]

    def passed(self):
        return step21.judge(self.rows, self.db, 2, [9,8])['passed']

    def test_exact_evidence_passes(self):
        self.assertTrue(self.passed())

    def test_timeout_cannot_count_as_success(self):
        self.rows[1].update(httpStatus=0, code='TRANSPORT_ERROR')
        self.assertFalse(self.passed())

    def test_truncated_raw_requests_fail(self):
        self.rows.pop()
        self.assertFalse(self.passed())

    def test_duplicate_response_order_fails(self):
        self.rows[1]['responseOrderId'] = 10
        self.assertFalse(self.passed())

    def test_wrong_passenger_fails(self):
        self.rows[1]['responsePassengerId'] = 99
        self.assertFalse(self.passed())

    def test_sent_without_matching_consumption_fails(self):
        self.db[1]['processedEventId'] = 'other'
        self.assertFalse(self.passed())

    def test_duplicate_outbox_join_fails(self):
        self.db.append(copy.deepcopy(self.db[0]))
        self.assertFalse(self.passed())

    def test_foreign_or_missing_notice_fails(self):
        for candidates in ('8', '8,9,99', None):
            with self.subTest(candidates=candidates):
                self.db[1]['noticeDrivers'] = candidates
                self.assertFalse(self.passed())

    def test_unpublished_or_retried_event_fails(self):
        for patch in (dict(eventStatus='NEW'), dict(attempts=2), dict(notificationDelayMs=-1)):
            with self.subTest(patch=patch):
                original = self.db[1].copy()
                self.db[1].update(patch)
                self.assertFalse(self.passed())
                self.db[1] = original

    def test_response_request_mismatch_fails(self):
        self.rows[1]['responseRequestId'] = 'other'
        self.assertFalse(self.passed())

    def test_duplicate_index_fails(self):
        self.rows[1]['index'] = 0
        self.assertFalse(self.passed())

    def test_percentiles_use_nearest_rank(self):
        self.assertEqual({'p50':50,'p95':95,'p99':99,'max':100}, step21.percentiles(list(range(1,101))))
        self.assertIsNone(step21.percentiles([])['p99'])


class CommittedLogEvidenceTest(unittest.TestCase):
    event = '00000000-0000-0000-0000-000000000001'

    def check(self, lines):
        base = step21.ROOT/'logs/step21/tool-tests'
        base.mkdir(parents=True,exist_ok=True)
        with tempfile.TemporaryDirectory(dir=base) as directory, patch.object(audit,'ROOT',Path(directory)):
            root = Path(directory)
            (root/'logs/step21-runtime').mkdir(parents=True)
            (root/'logs/step21-runtime/driver-service-test.out.log').write_text('\n'.join(lines))
            return audit.committed_notifications(root,'baseline',[dict(eventId=self.event,id=7)])

    def line(self, order=7, redelivered='false'):
        return f'Matching committed eventId={self.event} orderId={order} notificationDelayMs=42 redelivered={redelivered}'

    def test_exact_committed_log_passes(self):
        self.assertEqual(42,self.check([self.line()])['latencyMs']['p95'])

    def test_missing_committed_log_fails(self):
        with self.assertRaises(RuntimeError):
            self.check([])

    def test_duplicate_committed_log_fails(self):
        with self.assertRaises(RuntimeError):
            self.check([self.line(),self.line()])

    def test_wrong_order_in_committed_log_fails(self):
        with self.assertRaises(RuntimeError):
            self.check([self.line(order=99)])

    def test_redelivery_is_not_silently_counted_as_clean_run(self):
        with self.assertRaises(RuntimeError):
            self.check([self.line(redelivered='true')])


if __name__ == '__main__':
    unittest.main()
