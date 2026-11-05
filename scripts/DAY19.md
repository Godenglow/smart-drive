# Day19 集成验收与固定压测数据

在项目根执行。需要 JDK 21、Maven 3.8.6、本机 MySQL 客户端、PowerShell 7、Python 3.10+（仅标准库，无 pip 依赖）。沿用父 POM、各模块 POM、前端 lockfile 锁定的版本。先按根 README 启动 MySQL、Redis、RabbitMQ、Nacos、三个业务服务和 Gateway，8080–8083 健康检查都应为 UP。

## 一键验收

```powershell
pwsh -NoProfile -File scripts/verify-day19.ps1
```

入口默认隔离运行中的 Outbox 发布器（兼容显式 `-IsolatePublisher`）：确认 outbox/MQ 已收敛并核实本项目订单服务身份后，临时停止 order-service，避免测试事件被运行中的发布器发送到真实司机消费者。Maven 结束或失败时自动恢复服务，再进入真实 HTTP 验收。入口会启用并最终恢复三个 `SMARTDRIVE_*_INTEGRATION` 开关，执行工具自检、`mvn.cmd -o test`、真实 HTTP/MQ 验收与两类抢单的 3 并发各 2 轮冒烟。仅删除旧 Surefire XML，不运行 `clean`，避免 Windows 中运行的 JAR 被锁定。检查报告新鲜度、必需的 8 个集成套件和全仓零失败/错误/跳过。离线依赖未缓存时先按 README 完成 Maven 构建。

真实 HTTP 验收创建 2 位乘客、3 位司机及 3 笔固定抢单订单、1 笔真实 MQ 订单。8 个相同 requestId 请求必须落一单、一事件；经 Gateway 覆盖鉴权、越权、候选隐私、状态机、抢单冲突、重复履约与支付；真实事件重复发布两次，等待 ACK 后核对通知与消费记录不增加。验收结束清理本批数据并复查。

| 覆盖 | 自动化入口 | 实际依赖 / 替身边界 |
| --- | --- | --- |
| 注册唯一键、BCrypt、登录、本人身份与角色、过期/伪造令牌 | `AccountMySqlIntegrationTest` | MySQL + MyBatis + BCrypt + ServletAuthFilter；HTTP 为 MockMvc |
| 网关与业务接口角色/参数/归属规则 | `JwtGlobalFilterTest`、各 `*ControllerTest`；`verify-day19.py` | 单元层使用替身；HTTP 层为运行中的真实服务 |
| 幂等创建、取消、非法状态不变 | `OrderMySqlIntegrationTest` | 真实 MySQL 事务；附近司机调用替身 |
| 两种抢单竞争、取消竞争、失败回滚 | `OrderAcceptanceMySqlIntegrationTest` | 真实 MySQL + Spring 事务代理；资格调用替身，通知写真实司机库 |
| 开始/完成/支付并发、占用释放、历史隔离、回滚 | `OrderFulfillmentMySqlIntegrationTest` | 真实 MySQL + Spring 事务代理；远程资格替身 |
| 订单/outbox 原子性、重发身份、SKIP LOCKED | `OutboxMySqlIntegrationTest` | 真实 MySQL；broker 发送替身 |
| confirm、return、持久化路由 | `OutboxRabbitIntegrationTest` | 真实 RabbitMQ；UUID 独立 vhost，测试后删除 |
| GEO、TTL、过滤与限额 | `DriverRedisIntegrationTest` | 真实 Redis，本批独立用户 ID |
| 并发重复消息、通知事务回滚、重试、DLQ、修复重放 | `MatchingIntegrationTest` | 真实 MySQL/Redis；broker 用例使用独立 vhost |
| 完整 Gateway、真实通知与重复投递 | `verify-day19.py` | MySQL/Redis/RabbitMQ/Nacos/四服务全链路 |

## 环境与配置

密码由环境变量或忽略文件 `.env.local` 提供；**显式进程环境变量优先**。工具不打印 token、数据库密码或测试密码。关键变量沿用 README 的 `SMARTDRIVE_DB_USER/PASSWORD`、`REDIS_HOST/PORT/PASSWORD`、`RABBITMQ_*`、`NACOS_ADDR`；服务还需已配置一致的 JWT_SECRET 和 SMARTDRIVE_INTERNAL_TOKEN。管理 API 默认 `http://localhost:15672`，可用 `RABBITMQ_MANAGEMENT_URL` 覆盖；账户服务固定使用本机 3306，因此本组工具只支持 `account_db/driver_db/order_db` 位于本机同一 MySQL 实例，发现指向其他位置的 JDBC 配置会拒绝执行。网关可用本机 `SMARTDRIVE_GATEWAY_URL` 覆盖。

RabbitMQ 账号需有管理 API 与创建/删除测试 vhost 的权限。数据工具只向已有 `ride.events` 发布本批原事件，不停止 broker、不 purge 队列、不清空库或 Redis。验收期间请暂停其他压测和本项目写入，以便固定 GEO 候选、队列及测量环境。

固定业务参数见 `fixtures/day19.json`：起点 (118.8,36.5)、终点 (118.82,36.51)、金额 50.00、HTTP 超时 30 秒、收敛等待 60 秒。两个乘客 + N 个司机、N 笔 WAITING 订单默认 N=10，最多 500。每批 UUID 隔离账号与订单；手机号/密码随机，requestId 用批次 UUID 派生，重置复用原账号/订单/通知。

每批保存 `environment.json`：OS/CPU/Python/JDK/Maven/MySQL/Redis/RabbitMQ 版本、MySQL 隔离级别与表引擎、服务健康、MQ 积压、固定参数、Git HEAD 及脚本/POM/DDL/服务配置 SHA256。每次 load 单独保存当时的环境快照。结果仅适用于所附环境。

## 生成、查看、重置与清理

```powershell
python scripts/day19-data.py seed --drivers 10 --orders 10
# 复制上一步输出的 runId。也可以 seed 时用 --run-id 指定一个新的 UUID。
$runId = '<上一步 runId>'
python scripts/day19-data.py inspect --run-id $runId
python scripts/day19-data.py reset --run-id $runId
python scripts/day19-data.py cleanup --run-id $runId
```

seed 通过真实注册/登录接口生成账号，密码由 BCrypt 入库；通过司机接口保存资料。为隔离抢单事务的竞争，固定订单由 SQL 生成，**不生成 outbox、不触发自动匹配**；第一单给全部司机显式插入候选通知，其余订单只给第一位司机通知，总通知数 `drivers + orders - 1`。正常业务仍然保持最多 5 个匹配候选。seed 后司机离线，load 才刷新在线 TTL。

清单位于 `logs/day19/runs/<runId>/manifest.json`，先记下待注册账号/待建订单再写入；异常时可以用同一个 runId 清理，注册响应丢失时需先以该临时密码证明账号归属。每批持有操作系统文件锁，同批 load/reset/cleanup 不能同时执行；进程结束会自动释放锁。

reset 只将本批固定订单恢复 WAITING、清空归属并释放相应占用，不重建账号、通知，不重置真实 HTTP/MQ 订单。固定参数文件的 SHA256 必须与 seed 时一致；发现参数改变、本批司机占用了其他订单或订单归属外部司机，会保留现场并失败。

cleanup 先等待本批 outbox SENT、消费记录匹配，且共享主队列 ready/unacknowledged 都为 0；逐位司机离线并确认，再在 MySQL 事务中删除本批通知、消费记录、占用、事件、订单、资料和账户，最后查询复查。账号按 ID+手机号+角色+BCrypt 校验，订单按乘客+已登记 UUID+批次地点标记定位。存在清单外的关联订单/占用或 MQ 未收敛时退出非零并保留清单，不扩大删除范围。成功清理后清单移除密码，保留审计 ID；重复 cleanup 可安全返回。

如果中途失败，恢复服务后执行同批 cleanup。不要删除未完成批次的 manifest，也不要并行对同批数据人工写库。

## 抢单脚本

```powershell
python scripts/day19-data.py seed --drivers 10 --orders 10
$runId = '<上一步 runId>'
try {
    python scripts/load-day19.py --run-id $runId --scenario same-order --concurrency 10 --rounds 3
    if ($LASTEXITCODE -ne 0) { throw 'same-order failed; inspect raw results' }
    python scripts/load-day19.py --run-id $runId --scenario same-driver --concurrency 10 --rounds 3
    if ($LASTEXITCODE -ne 0) { throw 'same-driver failed; inspect raw results' }
} finally {
    python scripts/day19-data.py cleanup --run-id $runId
}
```

Day20 的 100/500 档使用相同入口，seed 的司机数、订单数均至少等于该档并发数。`--rounds` 默认 3；每轮自动 reset、重新登录、上线续期、创建对应数量的客户端线程，然后用 barrier 一起释放请求。设置阶段不计入测量。每个请求单独 TCP 连接、无自动重试；默认连接/读取超时为 30 秒。这是 Python 客户端的并发 HTTP 测量，操作系统调度与本机客户端开销都会影响结果。

| 场景 | 期望 HTTP | 必须满足的数据库结果 |
| --- | --- | --- |
| `same-order` | 恰好 1 个 200，其余 409 ORDER_ALREADY_TAKEN | 仅一笔 ACCEPTED、一个活动占用；归属等于 HTTP 成功司机 |
| `same-driver` | 恰好 1 个 200，其余 409 DRIVER_BUSY | 司机仅占一单；其他订单维持 WAITING 且 driver_id 为空 |

超时、5xx、错误码不符、HTTP 成功身份不符、HTTP/数据库归属不符都判失败，不计作正常冲突。每轮先保存逐请求 JSONL（请求编号、相对开始时间、耗时、HTTP/业务错误码及归属，不含 Authorization），再核对数据库；JSON 汇总保存所有订单和占用状态、结果分布、完成请求数/秒及 nearest-rank p50/p95/p99。吞吐包含正常 409，不能当成成功抢单 QPS。输出目录每次使用新 UUID，重复执行不覆盖旧轮次。

## 证据位置

| 文件 | 内容 |
| --- | --- |
| `logs/day19-verification.json` | 全仓计数、逐套件摘要、真实验收 runId 与整体结果 |
| `logs/day19-maven.log` | 全仓原始测试日志 |
| `logs/day19-tools.log` | 工具误判/清理范围回归 |
| `logs/day19-http-result.json` | 最新真实链路、冒烟目录与清理结果 |
| `logs/day19/runs/<runId>/verification.json` | 该批真实验收结果，后续运行不会覆盖 |
| `logs/day19/runs/<runId>/environment.json` | seed 时环境与源码指纹 |
| `logs/day19/runs/<runId>/load-<UUID>/round-*.jsonl` | 逐请求原始结果 |
| 同目录 `round-*.json`、`summary.json`、`environment.json` | 每轮最终数据库、计数、耗时、该次运行参数与环境 |

Day19 只验收脚本正确性及重复运行能力；Day20 再完成 10/100/500 每档各 3 轮，Day21 再实现并运行 5 分钟持续创建/MQ 消费测量与监控，不提前宣称这些性能结果。
