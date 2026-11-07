"""Runnable API example using the locally journaled Step22 demo credentials."""
import importlib.util
import json
from pathlib import Path
import uuid

import step19_support as s

spec = importlib.util.spec_from_file_location('demo_data_api', Path(__file__).with_name('demo-data.py'))
demo = importlib.util.module_from_spec(spec)
spec.loader.exec_module(demo)


def main():
    s.load_env()
    s.require(demo.POINTER.exists(), 'Run python scripts/demo-data.py seed first')
    fixture = demo.DemoFixture(json.loads(demo.POINTER.read_text(encoding='utf-8'))['runId'])
    with fixture.locked():
        s.require(fixture.data and fixture.data['status'] == 'ready', 'Demo not ready')
        fixture.validate_accounts()
        p = fixture.token(fixture.passengers[0])
        drivers = []
        try:
            for driver in fixture.drivers:
                token = fixture.token(driver)
                s.api('PUT', '/api/drivers/me/online', token, {'lng': 117.215, 'lat': 39.136})
                drivers.append(token)
            body = {'requestId': str(uuid.uuid4()),
                    'pickup': {'name': '天津站', 'lng': 117.215, 'lat': 39.136},
                    'dropoff': {'name': '南开大学', 'lng': 117.174, 'lat': 39.104}}
            order = s.api('POST', '/api/orders', p, body, expected=201)
            same = s.api('POST', '/api/orders', p, body, expected=201)
            s.require(order['id'] == same['id'], 'Idempotency mismatch')
            order_id = order['id']
            s.wait_until(lambda: all(order_id in s.api('GET', '/api/drivers/me/notices', token)
                                    for token in drivers), 'Real MQ notices missing')
            path = f'/api/orders/{order_id}'
            states = [order['status']]
            for action, expected in (('accept','ACCEPTED'),('start','IN_SERVICE'),('finish','FINISHED')):
                state = s.api('POST', path+'/'+action, drivers[0])
                s.require(state['status'] == expected, 'Unexpected fulfillment state')
                states.append(state['status'])
            paid = s.api('POST', path+'/pay-demo', p)
            s.require(paid['status'] == 'PAID', 'Demo payment incomplete')
            states.append(paid['status'])
            history = s.api('GET', '/api/orders/mine', p)
            s.require(history['total'] >= 1, 'Order history missing')
            result = {'passed': True, 'at': s.stamp(), 'runId': fixture.run_id, 'orderId': order_id,
                      'states': states, 'idempotentOrderId': same['id'], 'realMqCandidates': len(drivers),
                      'finalOrder': s.api('GET', path, p)}
            s.save(s.ROOT/'logs/step22/demo-api-result.json', result)
            print(json.dumps(result, ensure_ascii=False))
        finally:
            for token in drivers:
                s.api('PUT', '/api/drivers/me/offline', token)


if __name__ == '__main__':
    main()
