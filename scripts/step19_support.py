"""Local-only, standard-library helpers for repeatable Step19 fixtures and HTTP tests."""
from __future__ import annotations

import base64
from contextlib import contextmanager
import hashlib
import json
import os
from pathlib import Path
import platform
import re
import secrets
import shutil
import ssl
import subprocess
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parents[1]
CONFIG_PATH = ROOT / "scripts/fixtures/step19.json"
CONFIG = json.loads(CONFIG_PATH.read_text(encoding="utf-8"))
RUNS = ROOT / "logs/step19/runs"
# Loading the Windows certificate store for every HTTP request serializes the load
# generator even for plain HTTP. Share only the immutable TLS context; handlers
# remain local to each thread, and urllib still closes each TCP connection.
HTTP_SSL_CONTEXT = ssl.create_default_context()
HTTP_CLIENT = threading.local()


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def load_env():
    """Explicit process environment wins over the private local fallback."""
    path = ROOT / ".env.local"
    if path.exists():
        for line in path.read_text(encoding="utf-8-sig").splitlines():
            match = re.match(r"^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=(.*)$", line)
            if match:
                os.environ.setdefault(match[1], match[2].strip().strip('"').strip("'"))
    require(os.environ.get("SMARTDRIVE_DB_PASSWORD"), "SMARTDRIVE_DB_PASSWORD is required")
    # account-service is fixed to localhost:3306/account_db. Never audit/clean another server.
    for key, database in (("MYSQL_ORDER_URL", "order_db"), ("MYSQL_DRIVER_URL", "driver_db")):
        value = os.environ.get(key)
        if value:
            parsed = urllib.parse.urlsplit(value.removeprefix("jdbc:"))
            require(parsed.hostname in ("localhost", "127.0.0.1") and
                    (parsed.port or 3306) == 3306 and parsed.path == f"/{database}",
                    f"{key} must point to the fixed local Step19 database")
    for key in ("SMARTDRIVE_GATEWAY_URL", "RABBITMQ_MANAGEMENT_URL"):
        value = os.environ.get(key)
        if value:
            parsed = urllib.parse.urlsplit(value)
            require(parsed.hostname in ("localhost", "127.0.0.1") and not parsed.username,
                    f"{key} must be a local URL without credentials")


def stamp():
    return time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())


def save(path, value):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    temporary.replace(path)


def sql(statement):
    env = os.environ.copy()
    env["MYSQL_PWD"] = env["SMARTDRIVE_DB_PASSWORD"]
    command = [shutil.which("mysql") or "mysql.exe", "--host=127.0.0.1", "--port=3306",
               "--user=" + env.get("SMARTDRIVE_DB_USER", "smartdrive"),
               "--default-character-set=utf8mb4", "--batch", "--raw", "--skip-column-names"]
    result = subprocess.run(command, input=statement, env=env, encoding="utf-8",
                            capture_output=True, timeout=60)
    # Do not echo SQL, credentials, or raw client diagnostics.
    require(result.returncode == 0, "MySQL command failed; check local connection/schema/permissions")
    return result.stdout.strip()


def number(statement):
    return int(sql(statement))


def http(method, url, body=None, headers=None, timeout=None):
    request = urllib.request.Request(url, method=method, headers=headers or {},
                                     data=body if isinstance(body, bytes) else None if body is None else json.dumps(body).encode())
    if body is not None and not isinstance(body, bytes):
        request.add_header("Content-Type", "application/json")
    # Local requests must not be routed through workstation proxy settings.
    opener = getattr(HTTP_CLIENT, "opener", None)
    if opener is None:
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}),
                                             urllib.request.HTTPSHandler(context=HTTP_SSL_CONTEXT))
        HTTP_CLIENT.opener = opener
    try:
        response = opener.open(request, timeout=timeout or CONFIG["requestTimeoutSeconds"])
    except urllib.error.HTTPError as error:
        response = error
    with response:
        return response.status, json.loads(response.read()), dict(response.headers)


def gateway():
    return os.environ.get("SMARTDRIVE_GATEWAY_URL", "http://127.0.0.1:8080").rstrip("/")


def api(method, path, token=None, body=None, expected=200, code="0"):
    headers = {"X-Request-Id": str(uuid.uuid4())}
    if token:
        headers["Authorization"] = "Bearer " + token
    status, data, _ = http(method, gateway() + path, body, headers)
    require(status == expected and str(data.get("code")) == code,
            f"{method} {path}: expected {expected}/{code}, got {status}/{data.get('code')}")
    return data.get("data")


def mq(method, path, body=None):
    user = os.environ.get("RABBITMQ_USER", "guest")
    password = os.environ.get("RABBITMQ_PASSWORD", "guest")
    auth = base64.b64encode(f"{user}:{password}".encode()).decode()
    status, result, _ = http(method, os.environ.get("RABBITMQ_MANAGEMENT_URL", "http://localhost:15672") + path,
                             body, {"Authorization": "Basic " + auth})
    require(status in (200, 201, 204), "RabbitMQ management request failed")
    return result


def queue():
    vhost = urllib.parse.quote(os.environ.get("RABBITMQ_VHOST", "/"), safe="")
    return mq("GET", f"/api/queues/{vhost}/driver.matching.q")


def wait_until(predicate, message, seconds=None):
    deadline = time.monotonic() + (seconds or CONFIG["settleTimeoutSeconds"])
    while True:
        if predicate():
            return
        require(time.monotonic() < deadline, message)
        time.sleep(0.25)


def snapshot(health_ports=None):
    health = {}
    for port in (8080, 8081, 8082, 8083):
        health_port = (health_ports or {}).get(port, port)
        status, data, _ = http("GET", f"http://127.0.0.1:{health_port}/actuator/health")
        require(status == 200 and data["status"] == "UP", f"Service {port} is not UP")
        health[str(port)] = {"status": data["status"]}
        if port == 8082:
            health[str(port)]["redisVersion"] = data.get("components", {}).get("redis", {}).get("details", {}).get("version")
    overview = mq("GET", "/api/overview")
    q = queue()
    require(q.get("consumers", 0) > 0, "Matching consumer is not running")
    versions = {}
    for name, command in (("java", ["java", "-version"]), ("maven", ["mvn.cmd", "-version"]),
                          ("mysqlClient", ["mysql.exe", "--version"])):
        result = subprocess.run(command, capture_output=True, text=True, timeout=20)
        require(result.returncode == 0, f"{name} is unavailable")
        versions[name] = (result.stdout + result.stderr).strip()
    hashes = {str(p.relative_to(ROOT)).replace("\\", "/"): hashlib.sha256(p.read_bytes()).hexdigest()
              for pattern in ("pom.xml", "*/pom.xml", "db/*.sql", "scripts/*.py", "scripts/fixtures/*.json",
                              "scripts/verify-step19.ps1", "*/src/main/resources/application.yml")
              for p in ROOT.glob(pattern)}
    head = subprocess.run(["git", "rev-parse", "HEAD"], cwd=ROOT, capture_output=True, text=True)
    return {"capturedAt": stamp(), "os": platform.platform(), "cpu": platform.processor(),
            "logicalCpus": os.cpu_count(), "python": platform.python_version(), "versions": versions,
            "gitHead": head.stdout.strip(), "sourceSha256": hashes, "health": health,
            "mysql": json.loads(sql("SELECT JSON_OBJECT('version',VERSION(),'isolation',@@transaction_isolation,"
                                     "'maxConnections',@@max_connections,'timeZone',@@session.time_zone)")),
            "tables": sql("SELECT TABLE_SCHEMA,TABLE_NAME,ENGINE FROM information_schema.TABLES "
                          "WHERE TABLE_SCHEMA IN ('account_db','driver_db','order_db') ORDER BY 1,2"),
            "rabbitmqVersion": overview["rabbitmq_version"], "erlangVersion": overview["erlang_version"],
            "queue": {k: q.get(k) for k in ("messages_ready", "messages_unacknowledged", "consumers")},
            "endpoints": {"gateway": gateway(), "mysql": "127.0.0.1:3306",
                          "redisHost": os.environ.get("REDIS_HOST", "127.0.0.1"),
                          "redisPort": os.environ.get("REDIS_PORT", "6379"),
                          "nacos": os.environ.get("NACOS_ADDR", "localhost:8848")},
            "fixture": CONFIG, "timing": "perf_counter; nearest-rank percentiles; setup excluded"}


class Fixture:
    def __init__(self, run_id):
        self.run_id = str(uuid.UUID(run_id))
        self.directory = RUNS / self.run_id
        self.directory.mkdir(parents=True, exist_ok=True)
        self.path = self.directory / "manifest.json"
        self.data = json.loads(self.path.read_text(encoding="utf-8")) if self.path.exists() else None
        if self.data:
            require(self.data["runId"] == self.run_id and self.data["gateway"] == gateway(),
                    "Fixture identity/environment mismatch")

    @contextmanager
    def locked(self):
        # OS lock is released even after a killed process; do not manually remove the lock file.
        with (self.directory / "fixture.lock").open("a+b") as handle:
            handle.seek(0)
            if os.name == "nt":
                import msvcrt
                if handle.read(1) == b"":
                    handle.write(b"0")
                    handle.flush()
                handle.seek(0)
                msvcrt.locking(handle.fileno(), msvcrt.LK_NBLCK, 1)
            else:
                import fcntl
                fcntl.flock(handle, fcntl.LOCK_EX | fcntl.LOCK_NB)
            try:
                yield self
            finally:
                if os.name == "nt":
                    handle.seek(0)
                    msvcrt.locking(handle.fileno(), msvcrt.LK_UNLCK, 1)

    def save(self):
        save(self.path, self.data)

    @property
    def marker(self):
        return "Step19-" + self.run_id

    @property
    def passengers(self):
        return [a for a in self.data["accounts"] if a["role"] == "PASSENGER"]

    @property
    def drivers(self):
        return [a for a in self.data["accounts"] if a["role"] == "DRIVER"]

    def token(self, account):
        login = api("POST", "/api/auth/login", body={"phone": account["phone"], "password": account["password"]})
        me = api("GET", "/api/users/me", login["accessToken"])
        require(me["role"] == account["role"], "Fixture account role mismatch")
        if account.get("id"):
            require(me["userId"] == account["id"], "Fixture account identity mismatch")
        else:
            account["id"] = me["userId"]
            self.save()
        return login["accessToken"]

    def seed(self, drivers, orders):
        require(self.data is None, "Run already exists; use reset or a new UUID")
        require(2 <= drivers <= 500 and 1 <= orders <= 500, "drivers: 2..500; orders: 1..500")
        self.data = {"schemaVersion": 1, "runId": self.run_id, "createdAt": stamp(), "gateway": gateway(),
                     "fixtureSha256": hashlib.sha256(CONFIG_PATH.read_bytes()).hexdigest(),
                     "status": "seeding", "accounts": [], "syntheticOrders": [], "apiRequests": []}
        self.save()
        for index, role in enumerate(["PASSENGER"] * 2 + ["DRIVER"] * drivers):
            while True:
                phone = "199" + f"{secrets.randbelow(100000000):08d}"
                if number(f"SELECT COUNT(*) FROM account_db.account WHERE phone='{phone}'") == 0:
                    break
            account = {"phone": phone, "password": secrets.token_hex(16), "role": role}
            self.data["accounts"].append(account)
            self.save()  # Journal before registering, including response-loss recovery credentials.
            result = api("POST", "/api/auth/register", body=account)
            account["id"] = result["userId"]
            self.save()
            if role == "DRIVER":
                api("PUT", "/api/drivers/me/profile", self.token(account), {"displayName": self.marker})
        passenger = self.passengers[0]["id"]
        for index in range(orders):
            request = str(uuid.uuid5(uuid.UUID(self.run_id), f"synthetic-{index}"))
            self.data["syntheticOrders"].append({"requestId": request})
            self.save()
            sql(f"INSERT INTO order_db.ride_order(request_id,passenger_id,pickup_name,pickup_lng,pickup_lat,"
                f"dropoff_name,dropoff_lng,dropoff_lat,amount,status) VALUES('{request}',{passenger},"
                f"'{self.marker}',{CONFIG['pickup']['lng']},{CONFIG['pickup']['lat']},'{self.marker}',"
                f"{CONFIG['dropoff']['lng']},{CONFIG['dropoff']['lat']},{CONFIG['amount']},'WAITING')")
            order_id = number(f"SELECT id FROM order_db.ride_order WHERE passenger_id={passenger} AND request_id='{request}'")
            self.data["syntheticOrders"][-1]["id"] = order_id
            self.save()
            candidates = self.drivers if index == 0 else self.drivers[:1]
            values = ",".join(f"({order_id},{a['id']})" for a in candidates)
            sql("INSERT INTO driver_db.order_notice(order_id,driver_id) VALUES " + values)
        self.data["status"] = "ready"
        self.save()

    def owned_orders(self):
        if not self.passengers:
            return []
        passenger = self.passengers[0].get("id")
        if not passenger:
            return []
        requests = [o["requestId"] for o in self.data["syntheticOrders"]] + self.data["apiRequests"]
        require(all(str(uuid.UUID(r)) == r for r in requests), "Invalid request journal")
        if not requests:
            return []
        quoted = ",".join(f"'{r}'" for r in requests)
        rows = sql(f"SELECT id FROM order_db.ride_order WHERE passenger_id={int(passenger)} "
                   f"AND request_id IN ({quoted}) AND pickup_name='{self.marker}'")
        return [int(x) for x in rows.splitlines()] if rows else []

    def settle(self, ids):
        if not ids:
            return
        joined = ",".join(map(str, ids))
        def done():
            pending = number(f"SELECT COUNT(*) FROM order_db.outbox_event e LEFT JOIN "
                             f"driver_db.processed_order_event p ON p.order_id=e.aggregate_id AND p.event_id=e.event_id "
                             f"WHERE e.aggregate_id IN ({joined}) AND (e.status<>'SENT' OR p.order_id IS NULL)")
            # Empty shared queue also excludes unacknowledged/replayed deliveries before deleting dedup records.
            q = queue()
            return pending == 0 and q.get("messages_ready", 0) == 0 and q.get("messages_unacknowledged", 0) == 0
        wait_until(done, "MQ not settled; fixture journal retained for later cleanup")

    def reset(self):
        require(self.data and self.data["status"] == "ready", "Fixture must be ready")
        require(self.data["fixtureSha256"] == hashlib.sha256(CONFIG_PATH.read_bytes()).hexdigest(),
                "Fixture configuration changed; clean this batch and seed a new one")
        self.validate_accounts()
        ids = [int(o["id"]) for o in self.data["syntheticOrders"]]
        require(set(ids).issubset(self.owned_orders()), "Synthetic fixture rows are missing")
        joined = ",".join(map(str, ids))
        drivers = ",".join(str(a["id"]) for a in self.drivers)
        require(number(f"SELECT COUNT(*) FROM order_db.driver_active_order WHERE driver_id IN ({drivers}) "
                       f"AND order_id NOT IN ({joined})") == 0, "Fixture driver has an unrelated active order")
        require(number(f"SELECT COUNT(*) FROM order_db.ride_order WHERE id IN ({joined}) "
                       f"AND driver_id IS NOT NULL AND driver_id NOT IN ({drivers})") == 0,
                "Fixture order has an unrelated assigned driver")
        sql(f"START TRANSACTION; DELETE FROM order_db.driver_active_order WHERE order_id IN ({joined}) "
            f"AND driver_id IN ({drivers}); UPDATE order_db.ride_order SET status='WAITING',driver_id=NULL,"
            f"updated_at=CURRENT_TIMESTAMP(3) WHERE id IN ({joined}) AND pickup_name='{self.marker}'; COMMIT;")
        require(number(f"SELECT COUNT(*) FROM order_db.ride_order WHERE id IN ({joined}) "
                       "AND status='WAITING' AND driver_id IS NULL") == len(ids), "Reset failed")

    def validate_accounts(self):
        known = []
        for account in self.data["accounts"]:
            if not account.get("id"):
                # A registration may have committed while its HTTP response was lost.
                if number(f"SELECT COUNT(*) FROM account_db.account WHERE phone='{account['phone']}'") == 0:
                    continue
                self.token(account)  # Password proof is required before adopting a pending registration.
            require(re.fullmatch(r"199\d{8}", account["phone"]) and account["role"] in ("PASSENGER", "DRIVER"),
                    "Invalid fixture account")
            known.append(account)
        if known:
            require(len({int(a["id"]) for a in known}) == len(known)
                    and len({a["phone"] for a in known}) == len(known), "Duplicate fixture account")
            identities = " OR ".join(f"(id={int(a['id'])} AND phone='{a['phone']}' AND role='{a['role']}')"
                                     for a in known)
            require(number("SELECT COUNT(*) FROM account_db.account WHERE (" + identities + ") "
                           "AND password_hash REGEXP '^[$]2[aby][$]'") == len(known),
                    "Fixture account/BCrypt mismatch")

    def profile_delete_sql(self, users):
        return (f"DELETE FROM driver_db.driver_profile WHERE user_id IN ({users}) "
                f"AND display_name='{self.marker}'")

    def cleanup(self):
        require(self.data, "No fixture manifest")
        if self.data["status"] == "cleaned":
            return
        self.validate_accounts()
        ids = self.owned_orders()
        self.settle(ids)
        accounts = [a for a in self.data["accounts"] if a.get("id")]
        drivers = [a for a in accounts if a["role"] == "DRIVER"]
        for account in drivers:
            token = self.token(account)
            api("PUT", "/api/drivers/me/offline", token)
            require(api("GET", "/api/drivers/me/status", token)["online"] is False, "Driver still online")
        if accounts:
            users = ",".join(str(int(a["id"])) for a in accounts)
            order_ids = ",".join(map(str, ids)) or "0"
            # Refuse external associations; preserve the journal rather than expanding deletion scope.
            require(number(f"SELECT COUNT(*) FROM order_db.ride_order WHERE (passenger_id IN ({users}) "
                           f"OR driver_id IN ({users})) AND id NOT IN ({order_ids})") == 0,
                    "Unjournaled orders reference fixture accounts")
            require(number(f"SELECT COUNT(*) FROM order_db.driver_active_order WHERE "
                           f"(driver_id IN ({users}) AND order_id NOT IN ({order_ids})) OR "
                           f"(order_id IN ({order_ids}) AND driver_id NOT IN ({users}))") == 0,
                    "Unexpected active-order association")
            statements = ["START TRANSACTION",
                          f"DELETE FROM driver_db.order_notice WHERE order_id IN ({order_ids})",
                          f"DELETE FROM driver_db.processed_order_event WHERE order_id IN ({order_ids})",
                          f"DELETE FROM order_db.driver_active_order WHERE order_id IN ({order_ids}) AND driver_id IN ({users})",
                          f"DELETE FROM order_db.outbox_event WHERE aggregate_id IN ({order_ids})",
                          f"DELETE FROM order_db.ride_order WHERE id IN ({order_ids}) AND pickup_name='{self.marker}'",
                          self.profile_delete_sql(users)]
            for a in accounts:
                statements.append(f"DELETE FROM account_db.account WHERE id={int(a['id'])} AND phone='{a['phone']}' AND role='{a['role']}'")
            statements.append("COMMIT")
            sql(";".join(statements) + ";")
            remaining = number(f"SELECT (SELECT COUNT(*) FROM account_db.account WHERE id IN ({users})) + "
                               f"(SELECT COUNT(*) FROM driver_db.driver_profile WHERE user_id IN ({users})) + "
                               f"(SELECT COUNT(*) FROM order_db.ride_order WHERE id IN ({order_ids})) + "
                               f"(SELECT COUNT(*) FROM order_db.outbox_event WHERE aggregate_id IN ({order_ids})) + "
                               f"(SELECT COUNT(*) FROM driver_db.order_notice WHERE order_id IN ({order_ids})) + "
                               f"(SELECT COUNT(*) FROM driver_db.processed_order_event WHERE order_id IN ({order_ids})) + "
                               f"(SELECT COUNT(*) FROM order_db.driver_active_order WHERE driver_id IN ({users}) OR order_id IN ({order_ids}))")
            require(remaining == 0, "Fixture cleanup did not remove every owned row")
        self.data.update(status="cleaned", cleanedAt=stamp(), cleanupVerified=True, cleanedOrderIds=ids)
        for a in self.data["accounts"]:
            a.pop("password", None)
        self.save()
