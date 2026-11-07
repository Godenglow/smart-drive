"""Manage only journaled Step22 demo accounts/orders; never adopt legacy accounts."""
import argparse
import json
import secrets
import uuid

from step19_support import (Fixture, ROOT, CONFIG_PATH, api, gateway, load_env, number,
                           require, save, stamp)
import hashlib

POINTER = ROOT / 'logs/step22/demo.json'
CREDENTIALS = ROOT / 'logs/step22/demo-accounts.local.json'


class DemoFixture(Fixture):
    @property
    def marker(self):
        return 'Step22-' + self.run_id

    def profile_delete_sql(self, users):
        # Dedicated accounts may edit their display names through the UI.
        # Base cleanup verifies exact account identities before calling this.
        return f"DELETE FROM driver_db.driver_profile WHERE user_id IN ({users})"

    def seed_demo(self):
        require(self.data is None, 'Demo already exists')
        self.data = {'schemaVersion': 1, 'purpose': 'step22-demo', 'runId': self.run_id, 'createdAt': stamp(),
                     'gateway': gateway(), 'fixtureSha256': hashlib.sha256(CONFIG_PATH.read_bytes()).hexdigest(),
                     'status': 'seeding', 'accounts': [], 'syntheticOrders': [], 'apiRequests': []}
        self.save()
        for role in ('PASSENGER', 'DRIVER', 'DRIVER'):
            while True:
                phone = '199' + f'{secrets.randbelow(100000000):08d}'
                if number(f"SELECT COUNT(*) FROM account_db.account WHERE phone='{phone}'") == 0:
                    break
            account = {'phone': phone, 'password': 'Demo22!' + secrets.token_hex(12), 'role': role}
            self.data['accounts'].append(account)
            self.save()  # Recover registration response loss with password proof.
            account['id'] = api('POST', '/api/auth/register', body=account)['userId']
            self.save()
            if role == 'DRIVER':
                api('PUT', '/api/drivers/me/profile', self.token(account), {'displayName': self.marker})
        self.data['status'] = 'ready'
        self.save()
        self.export_credentials()

    def owned_orders(self):
        # UI orders may use arbitrary route names. Their passenger must belong to
        # this newly registered, dedicated fixture; never infer ownership by prefix.
        passengers = [int(a['id']) for a in self.passengers if a.get('id')]
        if not passengers:
            return []
        from step19_support import sql
        rows = sql(f"SELECT id FROM order_db.ride_order WHERE passenger_id IN ({','.join(map(str, passengers))})")
        return [int(row) for row in rows.splitlines()] if rows else []

    def cleanup(self):
        # UI orders have arbitrary route names, unlike the load fixture. Identify
        # them by dedicated passenger IDs and retain a snapshot before teardown.
        ids = self.owned_orders() if self.data and self.data['status'] != 'cleaned' else []
        if ids:
            from step19_support import sql
            self.validate_accounts()
            self.settle(ids)
            users = ','.join(str(int(a['id'])) for a in self.data['accounts'] if a.get('id'))
            joined = ','.join(map(str, ids))
            require(number(f"SELECT COUNT(*) FROM order_db.ride_order WHERE id IN ({joined}) "
                           f"AND driver_id IS NOT NULL AND driver_id NOT IN ({users})") == 0,
                    'Demo order assigned to an external driver; preserve data')
            # Keep original order fields in a local audit file before teardown.
            rows = sql(f"SELECT JSON_OBJECT('id',id,'pickupName',pickup_name,'status',status,'driverId',driver_id) "
                       f"FROM order_db.ride_order WHERE id IN ({joined})")
            save(self.directory / 'demo-orders-before-cleanup.json', [json.loads(r) for r in rows.splitlines()])
            self._cleanup_demo(ids)
        else:
            if self.data and self.data['status'] != 'cleaned':
                self.validate_accounts()
                users = ','.join(str(int(a['id'])) for a in self.data['accounts'] if a.get('id')) or '0'
                require(number(f"SELECT COUNT(*) FROM driver_db.order_notice WHERE driver_id IN ({users})") == 0,
                        'External notices reference empty demo; preserve accounts')
            super().cleanup()
            if self.data.get('demoCleanedOrderIds'):
                self.data['cleanedOrderIds'] = self.data['demoCleanedOrderIds']
                self.save()

    def _cleanup_demo(self, ids):
        from step19_support import sql
        self.validate_accounts()
        users = ','.join(str(int(a['id'])) for a in self.data['accounts'] if a.get('id'))
        joined = ','.join(map(str, ids))
        require(number(f"SELECT COUNT(*) FROM order_db.ride_order WHERE driver_id IN ({users}) "
                       f"AND id NOT IN ({joined})") == 0, 'External orders reference demo drivers')
        require(number(f"SELECT COUNT(*) FROM order_db.driver_active_order WHERE "
                       f"(driver_id IN ({users}) AND order_id NOT IN ({joined})) OR "
                       f"(order_id IN ({joined}) AND driver_id NOT IN ({users}))") == 0,
                'External active-order association')
        require(number(f"SELECT COUNT(*) FROM driver_db.order_notice WHERE driver_id IN ({users}) "
                       f"AND order_id NOT IN ({joined})") == 0, 'External notices reference demo drivers')
        for driver in self.drivers:
            token = self.token(driver)
            api('PUT', '/api/drivers/me/offline', token)
            require(api('GET', '/api/drivers/me/status', token)['online'] is False, 'Demo driver still online')
        # Accounts/profiles remain until the proven base cleanup, so failures
        # retain enough identity and credentials for recovery.
        self.data['demoCleanedOrderIds'] = sorted(set(self.data.get('demoCleanedOrderIds', []) + ids))
        self.save()
        sql(f"START TRANSACTION; DELETE FROM driver_db.order_notice WHERE order_id IN ({joined});"
            f"DELETE FROM driver_db.processed_order_event WHERE order_id IN ({joined});"
            f"DELETE FROM order_db.driver_active_order WHERE order_id IN ({joined});"
            f"DELETE FROM order_db.outbox_event WHERE aggregate_id IN ({joined});"
            f"DELETE FROM order_db.ride_order WHERE id IN ({joined}) AND passenger_id IN ({users}); COMMIT;")
        require(number(f"SELECT (SELECT COUNT(*) FROM order_db.ride_order WHERE id IN ({joined})) + "
                       f"(SELECT COUNT(*) FROM order_db.outbox_event WHERE aggregate_id IN ({joined})) + "
                       f"(SELECT COUNT(*) FROM driver_db.order_notice WHERE order_id IN ({joined})) + "
                       f"(SELECT COUNT(*) FROM driver_db.processed_order_event WHERE order_id IN ({joined})) + "
                       f"(SELECT COUNT(*) FROM order_db.driver_active_order WHERE order_id IN ({joined}))") == 0,
                'Demo order cleanup incomplete')
        super().cleanup()
        self.data['cleanedOrderIds'] = self.data['demoCleanedOrderIds']
        self.save()

    def export_credentials(self):
        require(self.data['status'] == 'ready', 'Demo not ready')
        save(CREDENTIALS, {'runId': self.run_id, 'accounts': self.data['accounts'],
                           'url': 'http://127.0.0.1:5173', 'preset': '天津站 → 南开大学'})


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=('seed', 'inspect', 'reset', 'cleanup'))
    args = parser.parse_args()
    load_env()
    # Lock a stable coordinator as well as each batch, including pointer updates.
    with Fixture('00000000-0000-0000-0000-000000000022').locked():
        pointer = json.loads(POINTER.read_text(encoding='utf-8')) if POINTER.exists() else None
        fixture = DemoFixture(pointer['runId']) if pointer else None
        if args.action == 'inspect':
            print(json.dumps({'runId': fixture.run_id if fixture else None,
                              'status': fixture.data['status'] if fixture and fixture.data else 'absent',
                              'accounts': [{k: a[k] for k in ('id', 'phone', 'role') if k in a}
                                           for a in fixture.data['accounts']] if fixture and fixture.data else [],
                              'credentialsFile': str(CREDENTIALS)}, ensure_ascii=False))
            return
        if args.action in ('reset', 'cleanup') and fixture and fixture.data:
            with fixture.locked():
                fixture.cleanup()
            CREDENTIALS.unlink(missing_ok=True)
        if args.action == 'cleanup':
            print(json.dumps({'cleaned': True, 'runId': fixture.run_id if fixture else None}))
            return
        if args.action == 'seed' and fixture and fixture.data and fixture.data['status'] != 'cleaned':
            require(fixture.data['status'] == 'ready', 'Partial seed; reset to recover')
            fixture.validate_accounts()
            fixture.export_credentials()
        else:
            fixture = DemoFixture(str(uuid.uuid4()))
            save(POINTER, {'runId': fixture.run_id, 'createdAt': stamp()})
            with fixture.locked():
                fixture.seed_demo()
        print(json.dumps({'ready': True, 'runId': fixture.run_id, 'credentialsFile': str(CREDENTIALS)}))


if __name__ == '__main__':
    main()
