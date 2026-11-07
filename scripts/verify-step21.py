"""Paced sustained real order creation, outbox delivery and matching, with raw evidence."""
from concurrent.futures import ThreadPoolExecutor
import argparse
from collections import Counter
import hashlib
import json
import math
import os
from pathlib import Path
import threading
import time
import uuid
from urllib.parse import quote

from step19_support import (CONFIG, ROOT, Fixture, api, gateway, http, load_env, mq,
                           queue, require, save, snapshot, sql, stamp, wait_until)

METRICS = ('jvm.memory.used', 'jvm.memory.max', 'jvm.gc.pause', 'jvm.threads.live',
           'process.cpu.usage', 'process.uptime', 'system.cpu.usage')
POOL_METRICS = ('hikaricp.connections.active', 'hikaricp.connections.idle',
                'hikaricp.connections.pending', 'hikaricp.connections.max',
                'hikaricp.connections.timeout', 'hikaricp.connections.acquire')


def percentiles(values):
    ordered = sorted(values)
    return {name: ordered[max(0, math.ceil(len(ordered) * q) - 1)] if ordered else None
            for name, q in (('p50', .5), ('p95', .95), ('p99', .99), ('max', 1))}


def where(fixture, phase):
    require(phase in ('baseline', 'tuned', 'smoke', 'warmup'), 'Invalid phase')
    return (f"r.passenger_id={int(fixture.passengers[0]['id'])} "
            f"AND r.pickup_name='{fixture.marker}' AND r.dropoff_name='{phase}'")


def database_rows(fixture, phase):
    statement = ("SELECT JSON_OBJECT('id',r.id,'requestId',r.request_id,'status',r.status,"
                 "'driverId',r.driver_id,'eventId',e.event_id,'eventStatus',e.status,"
                 "'attempts',e.attempts,'processedEventId',p.event_id,"
                 "'notificationDelayMs',TIMESTAMPDIFF(MICROSECOND,r.created_at,p.processed_at)/1000,"
                 "'noticeDrivers',(SELECT GROUP_CONCAT(n.driver_id ORDER BY n.driver_id) "
                 "FROM driver_db.order_notice n WHERE n.order_id=r.id)) "
                 "FROM order_db.ride_order r LEFT JOIN order_db.outbox_event e ON e.aggregate_id=r.id "
                 "LEFT JOIN driver_db.processed_order_event p ON p.order_id=r.id WHERE "
                 + where(fixture, phase) + " ORDER BY r.id")
    return [json.loads(line) for line in sql(statement).splitlines()]


def judge(rows, db_rows, expected, driver_ids):
    errors = []
    good = [r for r in rows if r['httpStatus'] == 201 and r['code'] == '0'
            and r['state'] == 'WAITING' and r['responseOrderId'] is not None
            and r['responsePassengerId'] == r['passengerId']
            and r['responseRequestId'] == r['requestId']]
    if len(rows) != expected or len(good) != expected:
        errors.append('Missing or unsuccessful HTTP requests')
    requests = {r['requestId'] for r in rows}
    if (len(requests) != expected or len({r['responseOrderId'] for r in good}) != expected
            or {r['index'] for r in rows} != set(range(expected))):
        errors.append('Duplicate request or order identities')
    if len(db_rows) != expected or {r['requestId'] for r in db_rows} != requests:
        errors.append('HTTP and SQL cardinality differ')
    by_request = {r['requestId']: r for r in good}
    candidates = ','.join(map(str, sorted(driver_ids)))
    for row in db_rows:
        if (row['status'] != 'WAITING' or row['driverId'] is not None
                or row['eventStatus'] != 'SENT' or not row['eventId']
                or row['processedEventId'] != row['eventId'] or row['attempts'] != 1
                or row['noticeDrivers'] != candidates or row['notificationDelayMs'] is None
                or row['notificationDelayMs'] < 0
                or by_request.get(row['requestId'], {}).get('responseOrderId') != row['id']):
            errors.append('Order/outbox/processed-event/notices mismatch')
            break
    return {'passed': not errors, 'errors': errors, 'requests': len(rows),
            'successfulCreates': len(good), 'databaseOrders': len(db_rows)}


def metric(port, name, token):
    status, body, _ = http('GET', f'http://127.0.0.1:{port}/actuator/metrics/{name}',
                           headers={'Authorization': 'Bearer ' + token}, timeout=5)
    require(status == 200 and body.get('name') == name, f'Metric unavailable: {port}/{name}')
    return {m['statistic']: m['value'] for m in body['measurements']}


def collect(fixture, phase, token, epoch):
    began = time.perf_counter()
    q = queue()
    vhost = quote(os.environ.get('RABBITMQ_VHOST', '/'), safe='')
    dead = mq('GET', f'/api/queues/{vhost}/driver.matching.dlq')
    counts = json.loads(sql(
        "SELECT JSON_OBJECT('orders',COUNT(*),'sent',COALESCE(SUM(e.status='SENT'),0),"
        "'processed',COUNT(p.order_id),'pending',COALESCE(SUM(e.status='NEW'),0),"
        "'oldestPendingMs',COALESCE(MAX(IF(e.status='NEW',"
        "TIMESTAMPDIFF(MICROSECOND,e.created_at,UTC_TIMESTAMP(3))/1000,0)),0)) "
        "FROM order_db.ride_order r LEFT JOIN order_db.outbox_event e ON e.aggregate_id=r.id "
        "LEFT JOIN driver_db.processed_order_event p ON p.order_id=r.id WHERE " + where(fixture, phase)))
    db_status = dict(line.split('\t') for line in sql(
        "SHOW GLOBAL STATUS WHERE Variable_name IN "
        "('Slow_queries','Threads_connected','Threads_running','Max_used_connections')").splitlines())
    database_offset = time.perf_counter() - epoch
    services = {}
    calls = []
    for name, port in (('gateway',18080), ('account-service',18081),
                       ('driver-service',18082), ('order-service',18083)):
        names = METRICS + (POOL_METRICS if name != 'gateway' else ())
        services[name] = {}
        calls.extend((name, port, key) for key in names)
    with ThreadPoolExecutor(max_workers=8) as pool:
        pending = [(name, key, pool.submit(metric, port, key, token)) for name, port, key in calls]
        for name, key, future in pending:
            services[name][key] = future.result()
    return {'capturedAt': stamp(), 'offsetSeconds': began - epoch,
            'databaseOffsetSeconds': database_offset,
            'collectionSeconds': time.perf_counter() - began, 'database': counts,
            'mysqlGlobal': {k: int(v) for k, v in db_status.items()},
            'queue': {k: q.get(k, 0) for k in ('messages_ready','messages_unacknowledged','consumers')},
            'deadLetters': dead.get('messages', 0), 'services': services}


def run_load(fixture, phase, token, driver_tokens, duration, rps, workers):
    expected = int(duration * rps)
    requests = [str(uuid.uuid5(uuid.UUID(fixture.run_id), f'{phase}-{i}')) for i in range(expected)]
    # Persist ALL intended IDs before the first send, including responses lost after commit.
    fixture.data['apiRequests'].extend(requests)
    fixture.save()
    raw_path = fixture.directory / f'step21-{phase}-requests.jsonl'
    metric_path = fixture.directory / f'step21-{phase}-metrics.jsonl'
    rows, samples, sample_errors = [], [], []
    stop = threading.Event()
    epoch = time.perf_counter() + 1
    started = stamp()

    def monitor():
        while not stop.is_set():
            began_sample = time.perf_counter()
            try:
                sample = collect(fixture, phase, token, epoch)
                samples.append(sample)
                with metric_path.open('a', encoding='utf-8') as handle:
                    handle.write(json.dumps(sample) + '\n')
                print(f"{phase} t={sample['offsetSeconds']:.0f}s orders={sample['database']['orders']} "
                      f"sent={sample['database']['sent']} consumed={sample['database']['processed']} "
                      f"outboxNEW={sample['database']['pending']}", flush=True)
            except Exception as error:
                sample_errors.append(type(error).__name__)
            stop.wait(max(0, 10 - (time.perf_counter() - began_sample)))

    def request(index):
        due = epoch + index / rps
        if due > time.perf_counter():
            time.sleep(due - time.perf_counter())
        began = time.perf_counter()
        row = {'index': index, 'requestId': requests[index],
               'passengerId': fixture.passengers[0]['id'], 'startOffsetSeconds': began - epoch,
               'scheduleLagMs': max(0, (began - due)*1000), 'httpStatus': 0, 'code': 'TRANSPORT_ERROR',
               'state': None, 'responseOrderId': None, 'responsePassengerId': None, 'responseRequestId': None}
        try:
            status, body, _ = http('POST', gateway() + '/api/orders',
                {'requestId': requests[index], 'pickup': dict(CONFIG['pickup'], name=fixture.marker),
                 'dropoff': dict(CONFIG['dropoff'], name=phase)},
                {'Authorization': 'Bearer ' + token, 'X-Request-Id': requests[index]})
            data = body.get('data') or {}
            row.update(httpStatus=status, code=str(body.get('code')), state=data.get('status'),
                       responseOrderId=data.get('id'), responsePassengerId=data.get('passengerId'),
                       responseRequestId=data.get('requestId'))
        except Exception as error:
            row['transportError'] = type(error).__name__
        row['durationMs'] = (time.perf_counter() - began)*1000
        row['endOffsetSeconds'] = time.perf_counter() - epoch
        with write_lock:
            rows.append(row)
            with raw_path.open('a', encoding='utf-8') as handle:
                handle.write(json.dumps(row) + '\n')

    def heartbeat():
        while not stop.wait(20):
            for driver_token in driver_tokens:
                try:
                    api('PUT', '/api/drivers/me/location', driver_token, CONFIG['pickup'])
                except Exception as error:
                    sample_errors.append('heartbeat:' + type(error).__name__)

    write_lock = threading.Lock()
    monitor_thread = threading.Thread(target=monitor)
    heartbeat_thread = threading.Thread(target=heartbeat)
    monitor_thread.start()
    heartbeat_thread.start()
    try:
        with ThreadPoolExecutor(max_workers=workers) as pool:
            # Pace submissions at due time instead of enqueuing the full run up front.
            futures = []
            for index in range(expected):
                due = epoch + index / rps
                if due > time.perf_counter():
                    time.sleep(due - time.perf_counter())
                futures.append(pool.submit(request, index))
            for future in futures:
                future.result()
        remaining = epoch + duration - time.perf_counter()
        if remaining > 0:
            time.sleep(remaining)
        load_end = time.perf_counter()
        end_sample = collect(fixture, phase, token, epoch)
        samples.append(end_sample)
        with metric_path.open('a', encoding='utf-8') as handle:
            handle.write(json.dumps(end_sample) + '\n')
        # Keep heartbeat and observation alive while the baseline outbox drains.
        def settled():
            values = json.loads(sql("SELECT JSON_OBJECT('sent',COALESCE(SUM(e.status='SENT'),0),"
                "'processed',COUNT(p.order_id)) FROM order_db.ride_order r "
                "LEFT JOIN order_db.outbox_event e ON e.aggregate_id=r.id "
                "LEFT JOIN driver_db.processed_order_event p ON p.order_id=r.id WHERE " + where(fixture, phase)))
            return values['sent'] == expected and values['processed'] == expected
        wait_until(settled, 'Sustained batch did not drain', seconds=600)
        drain_seconds = time.perf_counter() - load_end
    finally:
        stop.set()
        monitor_thread.join()
        heartbeat_thread.join()
    final = database_rows(fixture, phase)
    save(fixture.directory / f'step21-{phase}-database.json', final)
    # Re-read requests from disk so truncation cannot become a passing in-memory result.
    rows = [json.loads(line) for line in raw_path.read_text(encoding='utf-8').splitlines()]
    verdict = judge(rows, final, expected, [a['id'] for a in fixture.drivers])
    measured = load_end - epoch
    timing_passed = (measured >= duration and len(rows) == expected
                     and max((r['scheduleLagMs'] for r in rows), default=1e9) < 1000
                     and all(0 <= r['startOffsetSeconds'] < duration for r in rows))
    monitoring_passed = (not sample_errors and len(samples) >= duration/15
                         and all(s['queue']['consumers'] > 0 and s['deadLetters'] == 0 for s in samples))
    return {'phase': phase, 'startedAt': started, 'durationSeconds': duration,
            'actualLoadSeconds': measured, 'targetRps': rps, 'workers': workers,
            'requests': len(rows), 'successfulCreateQps': verdict['successfulCreates']/measured,
            'httpLatencyMs': percentiles([r['durationMs'] for r in rows]),
            'scheduleLagMs': percentiles([r['scheduleLagMs'] for r in rows]),
            'notificationDelayMs': percentiles([r['notificationDelayMs'] for r in final
                                              if r['notificationDelayMs'] is not None]),
            'outcomes': dict(Counter(f"{r['httpStatus']}/{r['code']}" for r in rows)),
            'endOfLoad': end_sample, 'drainSeconds': drain_seconds,
            'sampleCount': len(samples), 'sampleErrors': sample_errors,
            'timingPassed': timing_passed, 'verdict': verdict,
            'monitoringPassed': monitoring_passed,
            'passed': verdict['passed'] and timing_passed and monitoring_passed,
            'client': 'Python urllib; paced open loop; independent TCP; no retries; HTTP timing excludes setup',
            'metricScope': '10s samples plus end-of-load; JVM/Hikari sampled, not per-request maxima; MySQL global slow counter',
            'slowQueryThresholdSeconds': .1}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--phase', choices=('baseline','tuned','smoke'), required=True)
    parser.add_argument('--duration', type=int, default=300)
    parser.add_argument('--rps', type=int, default=12)
    parser.add_argument('--workers', type=int, default=16)
    args = parser.parse_args()
    require(10 <= args.duration <= 600 and 1 <= args.rps <= 50 and 1 <= args.workers <= 64, 'Invalid load parameters')
    require(args.phase == 'smoke' or args.duration == 300, 'Formal runs must last five minutes')
    load_env()
    os.environ.setdefault('RABBITMQ_MANAGEMENT_URL', 'http://127.0.0.1:15672')
    fixture = Fixture(str(uuid.uuid4()))
    result = {'step':21, 'runId':fixture.run_id, 'phase':args.phase, 'startedAt':stamp(),
              'passed':False, 'cleanupPassed':False}
    output = fixture.directory / 'step21-result.json'
    print(f'runId={fixture.run_id} evidence={fixture.directory}', flush=True)
    with fixture.locked():
        try:
            environment = snapshot(health_ports={p:p+10000 for p in (8080,8081,8082,8083)})
            environment['runtime'] = {name: json.loads((ROOT / f'logs/step21-runtime/{name}.json').read_text(encoding='utf-8-sig'))
                for name in ('gateway','account-service','driver-service','order-service')}
            environment['load'] = vars(args)
            save(fixture.directory / 'environment.json', environment)
            fixture.seed(2, 1)
            passenger_token = fixture.token(fixture.passengers[0])
            driver_tokens = [fixture.token(a) for a in fixture.drivers]
            for token in driver_tokens:
                api('PUT', '/api/drivers/me/online', token, CONFIG['pickup'])
            # Require the real GEO candidate set to be exactly this batch, never SQL-seed measured notices.
            status, nearby, _ = http('GET', 'http://127.0.0.1:8082/internal/drivers/nearby?lng='
                + str(CONFIG['pickup']['lng']) + '&lat=' + str(CONFIG['pickup']['lat']),
                headers={'X-Internal-Token':os.environ['SMARTDRIVE_INTERNAL_TOKEN']})
            require(status == 200 and set(nearby['data']) == {a['id'] for a in fixture.drivers}, 'Foreign or missing GEO candidates')
            # Warm the complete Gateway/order/outbox/MQ path and metrics before timed measurement.
            warmup = run_load(fixture, 'warmup', passenger_token, driver_tokens, 10, args.rps, args.workers)
            save(fixture.directory / 'warmup.json', warmup)
            require(warmup['passed'], 'Warmup failed')
            fixture.settle(fixture.owned_orders())
            result['load'] = run_load(fixture, args.phase, passenger_token, driver_tokens,
                                      args.duration, args.rps, args.workers)
            result['loadPassed'] = result['load']['passed']
            result['formalFiveMinutes'] = args.phase != 'smoke' and args.duration == 300
        except Exception as error:
            result['errorType'] = type(error).__name__
            # Script-defined invariant messages only, never transport/config/credential diagnostics.
            if type(error) is RuntimeError:
                result['invariant'] = str(error)[:200]
            print(f'Failed: {type(error).__name__}; inspect batch evidence', flush=True)
        finally:
            if fixture.data:
                try:
                    fixture.cleanup()
                    result['cleanupPassed'] = fixture.data.get('cleanupVerified') is True
                except Exception as error:
                    result['cleanupErrorType'] = type(error).__name__
            result['passed'] = result.get('loadPassed',False) and result['cleanupPassed']
            result['finishedAt'] = stamp()
            result['evidenceSha256'] = {p.name: hashlib.sha256(p.read_bytes()).hexdigest()
                for p in fixture.directory.glob('*') if p.suffix in ('.json','.jsonl') and p != output and p.name != 'manifest.json'}
            save(output, result)
            save(ROOT / f'logs/step21-{args.phase}-result.json', result)
    print(json.dumps({'runId':fixture.run_id, 'passed':result['passed'], 'cleanupPassed':result['cleanupPassed'],
                      'result':str(output)}, indent=2), flush=True)
    return 0 if result['passed'] else 1


if __name__ == '__main__':
    raise SystemExit(main())
