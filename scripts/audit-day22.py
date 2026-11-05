"""Check delivery evidence, clean test batches and unchanged historical SQL data."""
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import uuid

import day19_support as s


def read(path):
    return json.loads(Path(path).read_text(encoding='utf-8-sig'))


def module(name, filename):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).with_name(filename))
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result


def demo_scope():
    users, orders = set(), set()
    for path in s.RUNS.glob('*/manifest.json'):
        data = read(path)
        if data.get('purpose') == 'day22-demo':
            users.update(int(a['id']) for a in data['accounts'] if a.get('id'))
            orders.update(data.get('cleanedOrderIds', []))
            orders.update(data.get('demoCleanedOrderIds', []))
    user_sql = ','.join(map(str, sorted(users))) or '0'
    current = s.sql(f'SELECT id FROM order_db.ride_order WHERE passenger_id IN ({user_sql})')
    orders.update(int(row) for row in current.splitlines() if row)
    return user_sql, ','.join(map(str, sorted(orders))) or '0'


def historical_fingerprints():
    users, orders = demo_scope()
    scopes = {
        'account_db.account': f'id NOT IN ({users})',
        'driver_db.driver_profile': f'user_id NOT IN ({users})',
        'driver_db.order_notice': f'driver_id NOT IN ({users}) AND order_id NOT IN ({orders})',
        'driver_db.processed_order_event': f'order_id NOT IN ({orders})',
        'order_db.ride_order': f'id NOT IN ({orders})',
        'order_db.outbox_event': f'aggregate_id NOT IN ({orders})',
        'order_db.driver_active_order': f'driver_id NOT IN ({users}) AND order_id NOT IN ({orders})'}
    result = {}
    for table, condition in scopes.items():
        rows = sorted(s.sql(f'SELECT * FROM {table} WHERE {condition}').splitlines())
        result[table] = {'rows': len(rows), 'sha256': hashlib.sha256('\n'.join(rows).encode()).hexdigest()}
    return result


def exercise_demo(directory):
    demo = module('demo_data_audit', 'demo-data.py')
    fixture = demo.DemoFixture(read(demo.POINTER)['runId'])
    old_users = [int(a['id']) for a in fixture.data['accounts']]
    with fixture.locked():
        passenger = fixture.token(fixture.passengers[0])
        s.api('PUT', '/api/drivers/me/profile', fixture.token(fixture.drivers[0]),
              {'displayName': 'Day22 页面编辑后的司机称呼'})
        for driver in fixture.drivers:
            s.api('PUT', '/api/drivers/me/online', fixture.token(driver), {'lng': 118.8, 'lat': 36.5})
        request_id = str(uuid.uuid4())
        order = s.api('POST', '/api/orders', passenger,
                      {'requestId': request_id, 'pickup': {'name': 'Day22 自定义上车点', 'lng': 118.8, 'lat': 36.5},
                       'dropoff': {'name': 'Day22 自定义终点', 'lng': 118.82, 'lat': 36.51}}, expected=201)
        s.api('POST', f"/api/orders/{order['id']}/cancel", passenger)
        fixture.settle([order['id']])
    # Exercise the public CLI, including pointer replacement and credential export.
    subprocess.run([sys.executable, str(s.ROOT/'scripts/demo-data.py'), 'reset'], check=True)
    cleaned = read(fixture.path)
    s.require(cleaned['status'] == 'cleaned' and cleaned['cleanupVerified']
              and order['id'] in cleaned['cleanedOrderIds'], 'Demo reset did not clean real API order')
    s.require(all('password' not in a for a in cleaned['accounts']), 'Cleaned demo retains passwords')
    users = ','.join(map(str, old_users))
    s.require(s.number(f'SELECT COUNT(*) FROM account_db.account WHERE id IN ({users})') == 0,
              'Old demo accounts remain')
    second_id = read(demo.POINTER)['runId']
    subprocess.run([sys.executable, str(s.ROOT/'scripts/demo-data.py'), 'reset'], check=True)
    s.require(read(s.RUNS/second_id/'manifest.json')['cleanupVerified'], 'Empty demo reset failed')
    current = demo.DemoFixture(read(demo.POINTER)['runId'])
    current.validate_accounts()
    s.require(len(current.data['accounts']) == 3 and not current.owned_orders(), 'Demo not reset to empty')
    for driver in current.drivers:
        s.require(not s.api('GET', '/api/drivers/me/status', current.token(driver))['online'], 'Demo driver online')
    result = {'passed': True, 'checkedAt': s.stamp(), 'oldRunId': fixture.run_id, 'oldAccountIds': old_users,
              'cleanedOrderId': order['id'], 'emptyResetRunId': second_id, 'readyRunId': current.run_id,
              'readyAccounts': 3, 'readyOrders': 0, 'driversOffline': True, 'editedProfileReset': True}
    s.save(directory/'demo-reset-audit.json', result)
    return result


def final_audit(directory):
    baseline = read(directory/'historical-baseline.json')
    current = historical_fingerprints()
    s.require(baseline['tables'] == current, 'Historical rows changed or temporary SQL data remains')
    backend = read(directory/'day19-verification.json')
    live = read(directory/'day19-http-result.json')
    s.require(backend['passed'] and backend['tests'] >= 99 and backend['failures'] == backend['errors'] == backend['skipped'] == 0,
              'Incomplete backend regression')
    s.require(live['passed'] and live['cleanupPassed'] and backend['liveRunId'] == live['runId'], 'Stale HTTP evidence')
    s.require(read(s.RUNS/live['runId']/'manifest.json')['cleanupVerified'], 'HTTP fixture not cleaned')
    example = read(directory/'demo-api-result.json')
    s.require(example['passed'] and example['states'] == ['WAITING','ACCEPTED','IN_SERVICE','FINISHED','PAID']
              and example['orderId'] == example['idempotentOrderId'] and example['realMqCandidates'] == 2,
              'Executable API example failed')
    s.require(read(s.RUNS/example['runId']/'manifest.json')['cleanupVerified'], 'API demo not cleaned')
    browsers = {}
    for day in (15, 16, 17, 18):
        verification = read(directory/f'day{day}-verification.json')
        stats = read(directory/f'day{day}-browser-results.json')['stats']
        s.require(verification['live'] and verification['testPassed'] and verification['cleanupPassed']
                  and stats['unexpected'] == stats['flaky'] == stats['skipped'] == 0 and stats['expected'] > 0,
                  f'Day{day} browser/cleanup failed')
        for path in s.ROOT.glob(f'logs/day{day}-created-accounts.jsonl'):
            s.require(not path.exists(), 'Pending browser cleanup journal')
        browsers[str(day)] = stats
    timeline = read(directory/'day18-timeline.json')
    s.require(not timeline['videoErrors'] and timeline['timeline'][-1]['finalStatus'] == 'PAID', 'Journey incomplete')
    for name in ('passenger', 'driver-a', 'driver-b'):
        s.require((directory/f'{name}.webm').stat().st_size > 10000, 'Recording missing')
    if not (directory/'video-qa.json').exists():
        subprocess.run(['node', str(s.ROOT/'scripts/verify-demo-videos.cjs'), str(directory)], check=True)
    videos = read(directory/'video-qa.json')
    s.require(videos['passed'] and {v['name'] for v in videos['videos']} == {'passenger','driver-a','driver-b'},
              'Recording decode/preview incomplete')
    matrix = read(s.ROOT/'logs/day20-result.json')
    day20 = module('day20_delivery', 'verify-day20.py')
    cells = []
    for cell in matrix['cells']:
        folder = (s.ROOT/cell['rounds'][0]['report']).parent
        audited = day20.audit_cell(folder, cell['scenario'], cell['concurrency'], matrix['runId'])
        s.require(audited == cell, 'Day20 raw evidence or hashes changed')
        cells.append(audited)
    s.require(matrix['passed'] and matrix['cleanupPassed'] and day20.matrix_complete(cells), 'Day20 incomplete')
    s.require(read(s.RUNS/matrix['runId']/'manifest.json')['cleanupVerified'], 'Day20 fixture not cleaned')
    performance = read(directory/'day21-comparison.json')
    s.require(performance['passed'], 'Day21 audit incomplete')
    health = s.snapshot()
    routes = {}
    for name in ('account', 'driver', 'order'):
        status, body, _ = s.http('GET', s.gateway()+f'/actuator/{name}/health')
        s.require(status == 200 and body['status'] == 'UP', 'Gateway health route unavailable')
        routes[name] = body['status']
    queue = health['queue']
    s.require(queue['messages_ready'] == queue['messages_unacknowledged'] == 0 and queue['consumers'] > 0,
              'MQ has outstanding deliveries')
    for name, port in (('account',8081),('driver',8082),('order',8083)):
        status, body, _ = s.http('GET', f'http://127.0.0.1:{port}/v3/api-docs')
        s.require(status == 200 and body.get('paths'), 'OpenAPI export failed')
        s.save(directory/f'openapi-{name}.json', body)
    result = {'day': 22, 'passed': True, 'auditedAt': s.stamp(), 'historicalTablesUnchanged': current,
              'backendTests': backend['tests'], 'httpRunId': live['runId'], 'browsers': browsers,
              'apiExample': {'passed': True, 'orderId': example['orderId'], 'states': example['states']},
              'day20': {'runId': matrix['runId'], 'rounds': sum(len(c['rounds']) for c in cells), 'rawEvidencePassed': True},
              'day21': {'baselineRunId': performance['baseline']['runId'], 'tunedRunId': performance['tuned']['runId'],
                        'rawEvidencePassed': True}, 'demo': read(directory/'demo-reset-audit.json'),
              'environment': health, 'gatewayHealthRoutes': routes}
    result['evidenceSha256'] = {str(p.relative_to(s.ROOT)): hashlib.sha256(p.read_bytes()).hexdigest()
                                for p in sorted(directory.iterdir()) if p.is_file()
                                and p.name not in ('audit.json','result.json','delivery-audit.log')}
    s.save(directory/'audit.json', result)
    print(json.dumps({'passed': True, 'backendTests': backend['tests'],
                      'browserExecutions': sum(v['expected'] for v in browsers.values()),
                      'directory': str(directory)}))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--directory', required=True)
    parser.add_argument('--baseline', action='store_true')
    parser.add_argument('--exercise-demo', action='store_true')
    args = parser.parse_args()
    s.load_env()
    directory = (s.ROOT/args.directory).resolve()
    s.require(directory.is_relative_to((s.ROOT/'logs/day22').resolve()), 'Audit directory outside Day22 logs')
    directory.mkdir(parents=True, exist_ok=True)
    if args.baseline:
        s.require(not (directory/'historical-baseline.json').exists(), 'Do not overwrite historical baseline')
        s.save(directory/'historical-baseline.json', {'capturedAt': s.stamp(), 'tables': historical_fingerprints()})
        print('Historical row hashes saved (credentials excluded from output)')
    elif args.exercise_demo:
        print(json.dumps(exercise_demo(directory)))
    else:
        final_audit(directory)


if __name__ == '__main__':
    main()
