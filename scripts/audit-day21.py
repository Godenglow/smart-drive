"""Recompute the Day21 comparison from persisted requests, SQL snapshots and metrics."""
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import re

from day19_support import ROOT, require, save, stamp

spec = importlib.util.spec_from_file_location('verify_day21', Path(__file__).with_name('verify-day21.py'))
load = importlib.util.module_from_spec(spec)
spec.loader.exec_module(load)


def read(path):
    return json.loads(path.read_text(encoding='utf-8-sig'))


def committed_notifications(directory, phase, database):
    expected = {r['eventId']: r['id'] for r in database}
    rows, sources = [], {}
    pattern = r'Matching committed eventId=([0-9a-f-]+) orderId=(\d+) notificationDelayMs=(\d+) redelivered=(true|false)'
    for path in (ROOT/'logs/day21-runtime').glob('driver-service-*.out.log'):
        raw = path.read_bytes()
        matches = [{'eventId':e, 'orderId':int(o), 'notificationDelayMs':int(d), 'redelivered':r == 'true'}
                   for e,o,d,r in re.findall(pattern, raw.decode('utf-8',errors='replace')) if e in expected]
        if matches:
            rows.extend(matches)
            sources[str(path.relative_to(ROOT))] = hashlib.sha256(raw).hexdigest()
    require(len(rows) == len(expected) and {r['eventId'] for r in rows} == set(expected)
            and all(expected[r['eventId']] == r['orderId'] and not r['redelivered'] for r in rows),
            'Missing/duplicate/invalid committed notification logs')
    output = directory/f'day21-{phase}-committed-notifications.json'
    save(output, sorted(rows,key=lambda r:r['orderId']))
    return {'latencyMs':load.percentiles([r['notificationDelayMs'] for r in rows]),
            'records':len(rows), 'rawResults':str(output.relative_to(ROOT)),
            'rawSha256':hashlib.sha256(output.read_bytes()).hexdigest(), 'sourceLogSha256':sources,
            'scope':'Java event.occurredAt to post-commit/basicAck log; excludes browser polling'}


def audit(result):
    require(result['passed'] and result['cleanupPassed'] and result['formalFiveMinutes'], 'Incomplete formal run')
    directory = ROOT / 'logs/day19/runs' / result['runId']
    phase = result['phase']
    for name, digest in result['evidenceSha256'].items():
        require(Path(name).name == name, 'Invalid evidence path')
        require(hashlib.sha256((directory/name).read_bytes()).hexdigest() == digest, 'Evidence hash mismatch')
    report = result['load']
    rows = [json.loads(line) for line in (directory/f'day21-{phase}-requests.jsonl').read_text(encoding='utf-8').splitlines()]
    database = read(directory/f'day21-{phase}-database.json')
    samples = [json.loads(line) for line in (directory/f'day21-{phase}-metrics.jsonl').read_text(encoding='utf-8').splitlines()]
    samples.sort(key=lambda s: s['offsetSeconds'])
    manifest = read(directory/'manifest.json')
    drivers = [a['id'] for a in manifest['accounts'] if a['role'] == 'DRIVER']
    expected = report['durationSeconds']*report['targetRps']
    verdict = load.judge(rows, database, expected, drivers)
    require(verdict['passed'] and verdict == report['verdict'], 'Raw data failed invariants')
    require(report['durationSeconds'] == 300 and report['actualLoadSeconds'] >= 300, 'Five minute duration missing')
    require(all(0 <= r['startOffsetSeconds'] < 300 and r['scheduleLagMs'] < 1000 for r in rows), 'Pacing failed')
    require(report['httpLatencyMs'] == load.percentiles([r['durationMs'] for r in rows]), 'HTTP percentile mismatch')
    require(report['notificationDelayMs'] == load.percentiles([r['notificationDelayMs'] for r in database]), 'MQ percentile mismatch')
    require(len(samples) == report['sampleCount'] and len(samples) >= 20 and not report['sampleErrors'], 'Metric evidence missing')
    require(all(s['queue']['consumers'] > 0 and s['deadLetters'] == 0 for s in samples), 'MQ consumer or DLQ failed')
    require(manifest['status'] == 'cleaned' and manifest['cleanupVerified']
            and all('password' not in a for a in manifest['accounts']), 'Cleanup not verified')
    environment = read(directory/'environment.json')
    committed = committed_notifications(directory, phase, database)
    # The endpoint counters are sampled across several calls. Preserve capture offsets;
    # do not treat one collection as an atomic snapshot or claim instantaneous maxima.
    active_samples = [s for s in samples if s['offsetSeconds'] <= 301]
    metrics = {}
    for name in samples[0]['services']:
        service_samples = [s['services'][name] for s in active_samples]
        first, last = service_samples[0], service_samples[-1]
        summary = {
            'sampledMemoryUsedMaxBytes': max(s['jvm.memory.used']['VALUE'] for s in service_samples),
            'sampledThreadsMax': max(s['jvm.threads.live']['VALUE'] for s in service_samples),
            'sampledProcessCpuMax': max(s['process.cpu.usage']['VALUE'] for s in service_samples),
            'gcPauseCountDelta': last['jvm.gc.pause']['COUNT']-first['jvm.gc.pause']['COUNT'],
            'gcPauseTotalSecondsDelta': last['jvm.gc.pause']['TOTAL_TIME']-first['jvm.gc.pause']['TOTAL_TIME']}
        if name != 'gateway':
            summary.update(poolMax=last['hikaricp.connections.max']['VALUE'],
                sampledPoolActiveMax=max(s['hikaricp.connections.active']['VALUE'] for s in service_samples),
                sampledPoolPendingMax=max(s['hikaricp.connections.pending']['VALUE'] for s in service_samples),
                poolTimeoutDelta=last['hikaricp.connections.timeout']['COUNT']-first['hikaricp.connections.timeout']['COUNT'],
                poolAcquireCountDelta=last['hikaricp.connections.acquire']['COUNT']-first['hikaricp.connections.acquire']['COUNT'],
                poolAcquireTotalSecondsDelta=last['hikaricp.connections.acquire']['TOTAL_TIME']-first['hikaricp.connections.acquire']['TOTAL_TIME'])
        metrics[name] = summary
    return {'phase':phase, 'runId':result['runId'], 'startedAt':result['startedAt'], 'finishedAt':result['finishedAt'],
            'passed':True, 'cleanupPassed':True, 'requestCount':len(rows),
            'actualLoadSeconds':report['actualLoadSeconds'], 'successfulCreateQps':report['successfulCreateQps'],
            'httpLatencyMs':report['httpLatencyMs'], 'notificationDelayMs':report['notificationDelayMs'],
            'committedNotifications':committed,
            'scheduleLagMs':report['scheduleLagMs'], 'endOfLoadDatabase':report['endOfLoad']['database'],
            'endOfLoadDatabaseCaptureOffsetSeconds':report['endOfLoad']['databaseOffsetSeconds'],
            'drainSeconds':report['drainSeconds'], 'metrics':metrics,
            'sampledOutboxPendingMax':max(s['database']['pending'] for s in active_samples),
            'sampledMqReadyMax':max(s['queue']['messages_ready'] for s in active_samples),
            'sampledMqUnackedMax':max(s['queue']['messages_unacknowledged'] for s in active_samples),
            'mysqlGlobalSlowQueryDelta':samples[-1]['mysqlGlobal']['Slow_queries']-samples[0]['mysqlGlobal']['Slow_queries'],
            'mysqlSlowQueryThresholdSeconds':report['slowQueryThresholdSeconds'],
            'sampledMysqlConnectionsMax':max(s['mysqlGlobal']['Threads_connected'] for s in active_samples),
            'sampledMysqlRunningMax':max(s['mysqlGlobal']['Threads_running'] for s in active_samples),
            'metricSamples':len(samples), 'environment':environment,
            'cleanedAccounts':len(manifest['accounts']), 'cleanedOrders':len(manifest['cleanedOrderIds']),
            'evidenceSha256':result['evidenceSha256']}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', default='logs/day21-comparison.json')
    args = parser.parse_args()
    before = audit(read(ROOT/'logs/day21-baseline-result.json'))
    after = audit(read(ROOT/'logs/day21-tuned-result.json'))
    b, a = before['environment'], after['environment']
    require(all(b['load'][k] == a['load'][k] for k in ('duration','rps','workers')), 'Different load parameters')
    require(all(b['runtime'][s]['jarSha256'] == a['runtime'][s]['jarSha256'] for s in b['runtime']), 'Different application JARs')
    require(b['runtime']['order-service']['pollIntervalMs'] == 5000
            and a['runtime']['order-service']['pollIntervalMs'] == 1000, 'Wrong comparison configuration')
    require(b['runtime']['order-service']['batchSize'] == a['runtime']['order-service']['batchSize'] == 50, 'Batch changed')
    result = {'day':21, 'passed':True, 'auditedAt':stamp(), 'baseline':before, 'tuned':after,
              'change':{'outboxPollIntervalMs':{'before':5000,'after':1000}, 'batchSize':50},
              'scope':'Two sequential local five-minute paced runs at identical load; not a maximum capacity benchmark',
              'limitations':['Same host client and services; one run per configuration; JVM/OS warmness differ',
                'JVM, Hikari and MQ are sampled every ~10s, so maxima may miss short spikes',
                'Slow_queries is a shared server counter; 100ms threshold applies to these application connections only',
                'No performance_schema grants and Windows slow-log file ACL denies read: no per-statement slow-query attribution',
                'DB notificationDelayMs measures order insert to processed-event claim before matching commits; committedNotifications uses post-commit/basicAck application logs instead']}
    save(ROOT/args.output, result)
    print(json.dumps({'passed':True,'baselineQps':before['successfulCreateQps'],
                      'tunedQps':after['successfulCreateQps'], 'output':args.output}))


if __name__ == '__main__':
    main()
