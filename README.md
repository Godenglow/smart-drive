# SmartDrive 简化版代驾平台

微服务练手项目：乘客 / 司机双角色，覆盖注册登录、司机上线、附近司机查询、下单、异步待抢通知、并发抢单、履约与模拟支付，最终给出并发正确性测试与吞吐压测报告。

- 开发计划与每日记录位于 `项目文档/每日文档/`；完整规格文档 `项目文档/代驾项目开发文档.md` 仍由本机保存。
- 源码学习见 `项目文档/程序解读/`，最新为 [Day18 双角色联调与冲突同步](项目文档/程序解读/Day18-双角色联调与冲突同步.md)，说明抢单冲突、操作结果待确认、旧轮询隔离与真实会话录制。
- 当前进度：**Day 18 已完成独立乘客/司机会话全流程联调、双司机真实抢单冲突、重复点击、刷新同步和录制**。生产构建及 71/71 浏览器测试通过；3 个临时账号、2 笔订单及关联数据核对清理通过。计划累计完成 83%，下一步 Day 19 整理集成测试、固定测试数据与压测脚本。

## 技术栈（已锁定基线）

| 组件 | 版本 | 说明 |
| --- | --- | --- |
| JDK | 21 | 本机 Oracle JDK 21.0.2 |
| Maven | 3.8.6 | 运行在 JDK 21 上 |
| Spring Boot | 3.5.0 | 父 POM = `spring-boot-starter-parent` |
| Spring Cloud | 2025.0.0 | BOM 统一管理 |
| Spring Cloud Alibaba | 2025.0.0.0 | BOM 统一管理（nacos-client 3.x） |
| MyBatis | mybatis-spring-boot-starter 3.0.5 | 父 POM 统一锁定 |
| springdoc-openapi | 2.8.14 | WebMVC UI Starter；2.8.15+ 在 spring-web 6.2 上启动报错（定位记录见本地 `文档/Day4-账户建库与注册接口.md`） |
| MySQL / Redis / RabbitMQ / Nacos | 8.3 / 7.2.5 / 4.3.5 / 3.0.3 | 连接方式见本地 `文档/Day1-环境检查表.md`（不入库） |
| Vue / TypeScript / Vite | 3.5.44 / 5.9.3 / 7.3.7 | Vue Router 4.6.4；Node.js 22.12+；依赖由 `web-app/package-lock.json` 锁定 |

> 版本对应关系依据 [Spring Cloud Alibaba 官方版本说明](https://sca.aliyun.com/en/docs/2025.x/overview/version-explain/)：SCA 2025.0.0.0 ↔ Spring Cloud 2025.0.0 ↔ Spring Boot 3.5.0，不混用 Boot 4 / Cloud 2025.1 / SCA 2025.1。

## 模块与端口

| 模块 | 端口 | 说明 |
| --- | --- | --- |
| `gateway` | 8080 | 统一入口，对外只暴露 Gateway |
| `account-service` | 8081 | 注册、登录、JWT |
| `driver-service` | 8082 | 司机资料、上下线、位置（Redis GEO） |
| `order-service` | 8083 | 订单、抢单、履约、模拟支付、MQ 通知 |
| `common-api` | — | 错误码、事件 DTO、通用响应（jar，不启动） |
| `web-app` | 5173（固定） | Vue 3 + TS 前端，`/api` 代理 Gateway 8080 |

## 构建与验证

```bat
mvn.cmd clean compile                :: 编译全部模块
mvn.cmd -DskipTests package          :: 打出可执行 jar（各模块 target/ 下）
mvn.cmd clean install -DskipTests    :: 同时安装到本地仓库（IDE 或单模块构建前需要）
```

前端在 `web-app/` 执行 `npm.cmd ci`、`npm.cmd run dev`，打开 http://127.0.0.1:5173 。`npm.cmd run build` 执行 TypeScript 检查和生产构建；详细说明见 [前端 README](web-app/README.md)。

## 启动顺序（每天开工按此执行）

1. **基础设施**
   - MySQL83、RabbitMQ：开机自启，无需操作。
   - CentOS VM（Redis 6379）：需手动开机，命令见 Day 1 检查表第二节。
   - Nacos：`D:\devtools\nacos\nacos\bin\startup.cmd -m standalone`（约 20 秒就绪）。
   - 开工自查一行命令：`netstat -ano | findstr "LISTENING" | findstr ":3306 :5672 :6379 :8848 :9848 :8090"`，六个端口齐了即可开工。
2. **数据库与 JWT**：三个业务库的脚本分别是 `db/account_db.sql`、`db/driver_db.sql`、`db/order_db.sql`。服务连接数据库使用项目专用账号 `smartdrive`，密码从本机环境变量 `SMARTDRIVE_DB_PASSWORD` 读取（用户名可用 `SMARTDRIVE_DB_USER` 覆盖）。四个服务还需相同的 `JWT_SECRET`，至少 32 UTF-8 字节；缺失或过短时启动失败。可在本机 PowerShell 生成临时密钥并启动同一终端中的服务：`$env:JWT_SECRET=[Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(48))`。不要将密钥写入仓库、日志或请求示例。
3. **业务服务**按 `account-service → driver-service → order-service → gateway` 顺序启动（`java -jar 各模块/target/*.jar` 或 IDE）。
4. **健康检查**
   - http://localhost:8081/actuator/health （account-service）
   - http://localhost:8082/actuator/health （driver-service）
   - http://localhost:8083/actuator/health （order-service）
   - http://localhost:8080/actuator/health （gateway）
   - 经网关访问业务服务（验证注册与路由）：http://localhost:8080/actuator/account/health 、/actuator/driver/health 、/actuator/order/health
   - Nacos 控制台：http://localhost:8090/ （服务列表应恰好是三个业务服务，gateway 只做发现不注册）
5. **接口文档（springdoc）**：账户、司机、订单服务直连各自端口 `8081/8082/8083` 的 `/swagger-ui.html`，OpenAPI JSON 均为各服务的 `/v3/api-docs`；登录后把 `accessToken` 填入 Authorize 的 Bearer 输入框。司机文档包含上线、位置、待抢提示，隐藏内部接口。仅本机开发用，不经网关对外暴露。

## Day 5 鉴权接口

- `POST /api/auth/login`：`{"phone":"13800138000","password":"Passw0rd!23"}`，成功返回 `Result.data.accessToken / expiresAt / role`，JWT 有效期两小时。
- `GET /api/users/me`：请求头 `Authorization: Bearer <accessToken>`，返回当前账户的 `userId / phone / role`。
- 缺失、过期或签名错误的令牌返回 `401 UNAUTHORIZED`；乘客访问司机接口或司机执行乘客专属操作返回 `403 FORBIDDEN`。网关与直连业务服务都执行相同的验签和路径角色规则；订单归属等业务级权限在订单接口实现时校验。
- 自动化验证：`mvn.cmd clean test`，覆盖账户查询、错误密码、伪造和过期令牌、网关与业务服务的角色拦截。

## Day 6 司机接口

先执行 `db/driver_db.sql`，为项目数据库账号授予 `driver_db` 权限，再设置 `SMARTDRIVE_DB_PASSWORD`、`JWT_SECRET` 和 `REDIS_HOST`（默认 `192.168.220.128`）；Redis 若需要密码，设置 `REDIS_PASSWORD`。`driver-service` 使用 `driver_db`，不会清理 Redis 其他项目的数据。

以下接口均需司机 JWT；经 Gateway 的地址为 `http://localhost:8080`：

| 方法与路径 | 请求体 | 结果 |
| --- | --- | --- |
| `PUT /api/drivers/me/profile` | `{"displayName":"演示司机"}` | 创建或更新资料 |
| `PUT /api/drivers/me/online` | `{"lng":121.4737,"lat":31.2304}` | 上线并写入 GEO 和两分钟在线标记 |
| `PUT /api/drivers/me/location` | 同上 | 仅在线时更新坐标与 TTL；过期返回 `409 DRIVER_OFFLINE` |
| `PUT /api/drivers/me/offline` | 无 | 删除在线标记与 GEO 成员，可重复调用 |

每 30 秒调用位置接口可保持在线。Redis key 为 `smartdrive:driver:geo:online` 和 `smartdrive:driver:online:{userId}`。GEO 成员本身不自动过期；Day 7 附近司机查询必须复查在线标记。

## Day 7 附近司机查询

司机服务内部接口 `GET /internal/drivers/nearby?lng=121.4737&lat=31.2304` 返回 `Result.data` 中的司机用户 ID 列表，按球面距离从近到远排列，范围 5 km、最多 5 人。查询在 Redis 脚本中复查两分钟在线标记，过滤并清理已过期的 GEO 成员；距离不等于道路行驶距离。

该接口只由业务服务直连 `driver-service:8082`，Gateway 不转发 `/internal/**`。调用时设置 `X-Internal-Token`，其值由服务环境变量 `SMARTDRIVE_INTERNAL_TOKEN` 提供；未配置或令牌不匹配均返回 403。请为调用方配置相同的高熵令牌，不要写入仓库。`PUT /api/drivers/me/location` 是位置心跳，每次更新坐标并续期在线标记 120 秒；标记过期后需重新上线。

## Day 8 订单创建与查询

订单服务使用 `MYSQL_ORDER_URL`（默认本机 `order_db`）、`SMARTDRIVE_DB_USER`、`SMARTDRIVE_DB_PASSWORD`。先由有建库权限的本机 MySQL 账号执行 `db/order_db.sql`。司机服务与订单服务均需配置同一个 `SMARTDRIVE_INTERNAL_TOKEN`；未配置时订单创建返回 `503 SERVICE_UNAVAILABLE`。

乘客使用 JWT 调用 `POST /api/orders`，请求体例如：

```json
{"requestId":"fb6a9d1f-8db0-47d0-9f5d-59315be44fb5","pickup":{"name":"天津站","lng":117.215,"lat":39.136},"dropoff":{"name":"南开大学","lng":117.174,"lat":39.104}}
```

首次创建前会查询起点 5 km 内在线司机；没有司机时返回 `409 NO_DRIVER_NEARBY`。成功返回 `201`，订单状态 `WAITING`、演示金额 `50.00`。`GET /api/orders/{id}` 只允许本单乘客和已接单司机查看；`GET /api/orders/mine?page=1&size=10` 返回本人订单，页码从 1 开始，单页最多 100 条。

## Day 9 幂等创建与取消

- `POST /api/orders` 以 **当前乘客 ID + 请求体 requestId（UUID）** 为幂等键。并发重试只产生一单，均返回 `201` 和原订单 ID；已存在时直接返回订单当前状态，不再查询附近司机。相同键使用首次成功落库的起终点和金额，即使重试修改了请求内容也不会覆盖；新订单必须使用新 UUID。它与请求头 `X-Request-Id` 的链路追踪用途不同。
- `POST /api/orders/{id}/cancel`：仅本单乘客可取消 `WAITING` 订单，成功返回 `200` 和 `CANCELLED`。不存在返回 `404 NOT_FOUND`，其他乘客或司机返回 `403 FORBIDDEN`；重复取消、已接单及其他非等待状态返回 `409 ORDER_NOT_WAITING`。
- 状态机定义为 `WAITING → ACCEPTED → IN_SERVICE → FINISHED → PAID`，另允许 `WAITING → CANCELLED`，两个终态不再跳转。取消使用带乘客归属与 `status='WAITING'` 条件的原子 SQL，状态冲突不会写库。抢单与履约接口已分别在 Day 10、Day 11 实现。
- `mvn.cmd test` 执行单元和 MockMvc 测试。已配置数据库环境变量后，设置 `$env:SMARTDRIVE_MYSQL_INTEGRATION='true'` 再执行，启用真实 MySQL 的 12 并发创建/取消验证；仅清理本次随机标识的测试订单。详细结果与复现命令见 `项目文档/每日文档/Day9-订单幂等与取消.md`。

## Day 10 司机抢单

- 用有建表权限的本机账号重新执行 `db/driver_db.sql`，新增 `driver_db.order_notice`；订单库复用 `db/order_db.sql` 中的 `driver_active_order`。应用账号只需现有项目库的读写权限。
- `POST /api/orders/{id}/accept`：仅司机 JWT 可调用。订单服务通过带 `X-Internal-Token` 的内部接口 `GET /internal/drivers/{driverId}/eligibility?orderId=...`，检查该司机的通知记录和 Redis 在线标记；随后在同一 MySQL 事务内插入司机占用，再条件更新 `WAITING → ACCEPTED`。成功返回 `200` 与订单详情。
- 未获该单通知返回 `403 FORBIDDEN`；已通知但离线/TTL 过期返回 `409 DRIVER_OFFLINE`；司机已有占用返回 `409 DRIVER_BUSY`；订单被接走返回 `409 ORDER_ALREADY_TAKEN`；取消等非等待状态返回 `409 ORDER_NOT_WAITING`；资格服务不可用返回 `503 SERVICE_UNAVAILABLE`。失败不会留下占用。
- Day 10 验收使用测试通知；Day 13 起由 MQ 自动分发，提供候选详情和待抢提示接口。没有公共写通知接口。正常接单后的占用在完成服务时同事务释放。
- 真实 MySQL 测试：设置 `SMARTDRIVE_MYSQL_INTEGRATION=true`，执行 `mvn.cmd -o -pl order-service -am test`。涵盖两类 10 并发各 3 轮、取消竞争和回滚，使用真实 Spring 事务代理，远程资格调用使用测试替身并核对已插入的通知记录。
- 四服务启动后，使用 PowerShell 7 执行 `pwsh -NoProfile -File scripts/verify-day10.ps1`，通过 Gateway/Nacos/司机服务/Redis/MySQL 验证真实 HTTP。脚本读取本机 `.env.local` 或环境变量，连接本机 MySQL，创建并清理本次临时账号、司机资料、订单、通知及占用，报告写入忽略目录 `logs/day10-http-result.json`。详情见 `项目文档/每日文档/Day10-司机抢单事务.md`。

## Day 11 履约、模拟支付与订单历史

以下接口均无请求体，身份来自 JWT，成功返回 `200` 和最新订单详情：

| 方法与路径 | 权限 | 状态变更 |
| --- | --- | --- |
| `POST /api/orders/{id}/start` | 本单已接单司机 | `ACCEPTED → IN_SERVICE` |
| `POST /api/orders/{id}/finish` | 本单已接单司机 | `IN_SERVICE → FINISHED`，同事务释放本单司机占用 |
| `POST /api/orders/{id}/pay-demo` | 本单乘客 | `FINISHED → PAID`，固定演示金额 `50.00`，无资金流转 |

- 角色或归属不符返回 `403 FORBIDDEN`，订单不存在返回 `404 NOT_FOUND`。跳过状态、重复提交、终态操作及并发条件更新失败均为 `409 ORDER_STATUS_INVALID`；失败不改变订单或占用。
- 完成时仅删除司机 ID 与本单 ID 同时匹配的占用。占用缺失或不匹配返回 `500 INTERNAL_ERROR`，订单状态回滚，保留其他订单的占用；需检查本机数据一致性。正常完成后即可接下一单，无需等待上一单模拟支付。
- 历史查询复用 `GET /api/orders/mine?page=1&size=10`，包含本人进行中、已完成、已支付及已取消订单，按 `created_at DESC, id DESC` 分页；司机历史按已分配司机过滤。原有 `GET /api/orders/{id}` 可查看最终状态。
- 开启 MySQL/Redis 集成开关并执行 `mvn.cmd -o test`：本次全仓 **61/61** 通过；开始/完成/支付各 10 并发、各 3 轮均为 1 成功和 9 冲突，并验证完成事务回滚、占用复用和历史隔离。
- 四服务启动后执行 `pwsh -NoProfile -File scripts/verify-day11.ps1`：通过网关验证完整链路、各 5 并发的重复状态操作、角色/归属和历史分页；创建并清理本次临时数据，报告位于 `logs/day11-http-result.json`。详细说明见 `项目文档/每日文档/Day11-履约与模拟支付.md`。

## Day 12 Outbox 与 RabbitMQ 发布

- `POST /api/orders` 在同一 MySQL 事务中写入 `ride_order` 与 `outbox_event`；任一步失败均回滚。乘客 ID + requestId 的并发幂等继续生效，同一订单只生成一个 `order.created` 事件。已有 `db/order_db.sql` 已包含 outbox 表，无需重建数据。
- 自动声明 durable topic exchange `ride.events`、绑定 `order.created` 的 durable queue `driver.matching.q`，以及 `ride.events.dlx` → `driver.matching.dlq` 死信链路。消息为持久化 JSON，携带 `eventId/type/orderId/pickupLng/pickupLat/occurredAt`，`message_id=eventId`。
- 发布器默认每批最多 50 条、每轮结束后等待 5 秒；逐条用 MySQL `FOR UPDATE SKIP LOCKED` 持锁，等待 correlated broker confirm，且必须没有 mandatory return 才将事件置为 `SENT`。连接失败、NACK、无路由、超时均保留 `NEW`，累计 `attempts`，下轮重试；确认等待默认上限 5 秒，连接超时 3 秒。
- RabbitMQ 连接由 `RABBITMQ_HOST/PORT/USER/PASSWORD/VHOST` 配置，默认本机 `localhost:5672`、`guest/guest`、`/`，仅用于本地演示。发布参数可用 `SMARTDRIVE_OUTBOX_ENABLED`、`SMARTDRIVE_OUTBOX_POLL_INTERVAL_MS`、`SMARTDRIVE_OUTBOX_BATCH_SIZE`、`SMARTDRIVE_OUTBOX_CONFIRM_TIMEOUT_MS` 覆盖。关闭发布器仍会保存 outbox 事件。
- 消息确认后、数据库提交前崩溃可能重发同一 eventId；这属于至少一次发送，由 Day 13 消费者事务去重。Day 12 阶段积压的消息会在司机消费者启动后处理。MQ 不可用不阻断订单写库；Rabbit 健康检查会显示 `DOWN`，不能把健康状态直接等同于创建接口不可用。
- 配置本机凭据后，开启 `SMARTDRIVE_MYSQL_INTEGRATION=true`、`SMARTDRIVE_REDIS_INTEGRATION=true`、`SMARTDRIVE_RABBITMQ_INTEGRATION=true`，执行 `mvn.cmd -o test`：本次 **78/78** 通过。Rabbit 集成测试通过本机管理 API（默认 `http://localhost:15672`，可用 `RABBITMQ_MANAGEMENT_URL` 覆盖）创建/删除 UUID 独立 vhost，需要管理权限；不会操作共享队列中的消息。
- 四服务启动后，用 PowerShell 7 执行 `pwsh -NoProfile -File scripts/verify-day12.ps1`。脚本使用 README 的演示乘客（可传 `PassengerPhone/PassengerPassword`），创建临时司机和独立捕获队列，通过 Gateway 验证真正的定时发送。结束时清理临时资源，**保留已取消的演示订单及 SENT 事件**，便于共享队列中的消息关联原单；报告在 `logs/day12-http-result.json`。详细说明和验收边界见 [Day12 文档](项目文档/每日文档/Day12-Outbox与RabbitMQ发布.md)。

## Day 13 司机消费、通知去重与待抢提示

- 先使用具有 DDL 权限的本机账号执行 `db/driver_db.sql`，新增 `processed_order_event`；应用账号沿用读写权限。司机服务与订单服务配置相同的 `RABBITMQ_*`。默认启动 `driver.matching.q` 消费者，可用 `SMARTDRIVE_MATCHING_ENABLED=false` 暂停。
- 消费 `order.created` 后重新查询起点 5 km 内在线司机，最多 5 人，过滤过期标记。消费记录与全部 `order_notice` 在同一 MySQL 事务提交后才手动 ACK；任意写入失败整体回滚。同一 eventId/orderId 重投不重复写通知，也不重新匹配后来上线的司机；首次无候选也记为已处理。
- `GET /api/drivers/me/notices?status=NEW&limit=100`：仅司机 JWT，返回本人最近通知的订单 ID 数组，按通知 ID 倒序。`status` 只支持 `NEW`，`limit` 为 1–100。通知表示曾获得候选资格，保留记录，不自动代表订单仍可接；详情返回当前状态，抢单仍执行资格和原子状态校验。
- 收到通知的候选司机可调用 `GET /api/orders/{id}`，只得到地点、金额、当前状态等接单字段；`passengerId/requestId/driverId` 为 `null`。未通知司机返回 403；本单乘客与已接单司机沿用完整详情。候选查看不要求持续在线，抢单仍要求在线。
- 非法 JSON/事件直接拒收进入既有 DLQ；数据库或 Redis 处理失败现已由 Day 14 升级为有退避的有限重试，耗尽进入 DLQ，见下节。
- 全仓真实中间件测试 **91/91** 通过；执行 `pwsh -NoProfile -File scripts/verify-day13.ps1` 可复现 Gateway 验收，结果在 `logs/day13-http-result.json`。详见 [Day13 实现说明](项目文档/每日文档/Day13-司机消费与待抢提示.md)、[检验报告](项目文档/检验报告/Day13-检验报告.md)及[程序解读](项目文档/程序解读/Day13-司机消息消费与通知去重.md)。

## Day 14 消费重试、死信与恢复演练

- 合法事件默认总共尝试 4 次，三次等待为 1、2、4 秒；耗尽后 `Reject/requeue=false` 进入 `driver.matching.dlq`。JSON/事件非法直接拒收。每次匹配独立事务，成功提交后才 ACK；ACK 不参与本地重试，连接失败后的重投仍由消费记录去重。
- 使用 `SMARTDRIVE_MATCHING_MAX_ATTEMPTS`、`SMARTDRIVE_MATCHING_INITIAL_INTERVAL_MS`、`SMARTDRIVE_MATCHING_MAX_INTERVAL_MS` 配置重试。每 30 秒记录 outbox NEW 数量/最老年龄与主队列、DLQ 的 ready/consumer；消费成功记录 `eventId/orderId/notificationDelayMs`。AMQP ready 不包含 unacknowledged，管理诊断工具另提供该计数。
- `pwsh -NoProfile -File scripts/mq-diagnostics.ps1` 查看积压及最多 20 条死信的身份、SHA256、`x-death`（`-Count` 最大 100）。读完重新入队，保留来源；会影响队列顺序/redelivered。修复原因后加 `-ReplayEventId '<原 UUID>'` 定向补发完整原消息，始终保留 DLQ 原件。`routed=true` 仅表示路由成功，需另外核对消费提交，工具不自动删死信。`scripts/verify-mq-diagnostics.ps1` 在独立 vhost 验证工具。
- `pwsh -NoProfile -File scripts/verify-day14.ps1` 会实际执行本机 RabbitMQ `stop_app/start_app`，影响该节点全部 vhost，适合本地演练；`finally` 中尝试恢复 broker。本次停止/恢复约 42 秒，Gateway 下单与幂等重试正常，事件由 NEW 自动补发至 SENT，两司机通知、重复投递、唯一抢单及 PAID 通过，临时数据已清理。RabbitCtl 路径不同可用 `-RabbitCtl` 覆盖。
- 全仓真实中间件回归 **95/95** 通过，日志 `logs/day14-verify.log`；演练 `logs/day14-http-result.json`；工具验证 `logs/day14-diagnostics-result.json`。本地退避会占用消费线程，次数按单次 delivery 计算，重启/断线重投会重新计数；既有 classic DLX 未升级为集群下的至少一次死信保证。详见 [每日记录](项目文档/每日文档/Day14-消费重试与故障恢复.md)、[检验报告](项目文档/检验报告/Day14-检验报告.md)、[程序解读](项目文档/程序解读/Day14-消费重试与故障恢复.md)。

## Day 15 Vue 前端、登录注册与角色路由

- `web-app` 已接入 `POST /api/auth/register`、`POST /api/auth/login`、`GET /api/users/me`；注册时选择乘客/司机，成功后返回登录页。登录后按服务端角色进入 `/passenger` 或 `/driver`，两种首页展示本人账户，后续业务功能明确标注准备中。
- Vite 固定监听本机 5173，`/api` 前缀原样代理到 8080。可在 `web-app/.env.local` 设置 `SMARTDRIVE_GATEWAY_URL`。前端不使用数据库密码或 JWT 签名密钥，根目录私有配置不应复制到前端。
- 请求层统一解包 Result、添加 Bearer 和 `X-Request-Id`、10 秒超时及错误提示。当前标签页 sessionStorage 只存 token/到期时间，刷新后通过 `/users/me` 恢复账户。过期/401 清空会话，网络故障保留会话并提供重试；未登录跳登录页，角色不匹配显示 403。
- 在根目录执行 `pwsh -NoProfile -File scripts/verify-day15.ps1 -Live`：生产构建及 **20/20 Chrome 浏览器测试**通过，包括双角色真实 Gateway/MySQL 注册、错误密码、登录、刷新、前后端越权、退出和重复注册。两个临时账号 BCrypt 核对及清理通过。该脚本固定运行 Day15 三个测试项目；需要本机 Chrome 或设置 `PLAYWRIGHT_CHANNEL=msedge`。
- 本机证据：`logs/day15-browser-results.json`、`logs/day15-verification.json` 及 `logs/day15-*.png`。移动端为 Chrome 设备模拟；退出清理浏览器会话，不撤销已签发 JWT。详见 [每日记录](项目文档/每日文档/Day15-Vue前端与登录注册.md)、[检验报告](项目文档/检验报告/Day15-检验报告.md)、[程序解读](项目文档/程序解读/Day15-前端请求与角色路由.md)。

## Day 16 乘客下单与订单页面

- 乘客首页提供“预约代驾”“我的订单”。`/passenger/orders/new` 支持天津/上海演示路线和手动地点坐标，校验名称、经纬度；`/passenger/orders` 展示本人分页历史；`/passenger/orders/{id}` 展示行程、状态、费用和司机编号。司机访问以上页面显示权限提示。
- 草稿按账号保存在当前标签页。断网、超时或 5xx 后保留路线及 requestId，锁定路线并允许“重试确认原订单”，防止响应丢失造成重复下单。明确的业务拒绝（如无附近司机）保留并解锁表单；成功后清理草稿。
- 列表和非终态详情每 5 秒轮询，慢请求不重叠；隐藏页面暂停、恢复时刷新，离开页面中止读取，已支付/已取消停止详情轮询。等待接单时可二次确认取消，服务完成后可二次确认模拟支付；按钮提交中禁用，409 后重新读取服务端状态。
- 完整验收：`pwsh -NoProfile -File scripts/verify-day16.ps1 -Live`。本次构建与 **43/43** 浏览器测试通过：Day15 回归 18 项、Day16 模拟/移动 24 项、真实业务链路 1 项。真实乘客在浏览器下单/取消/支付；司机测试夹具通过 API 上线、接收 MQ 通知并履约，司机页面在 Day17 实现。
- `logs/day16-browser-results.json` 保存测试明细，`logs/day16-verification.json` 记录两个账号 BCrypt 核对、两笔订单及关联事件清理通过。详见 [每日记录](项目文档/每日文档/Day16-乘客下单与订单页面.md)、[检验报告](项目文档/检验报告/Day16-检验报告.md)、[程序解读](项目文档/程序解读/Day16-乘客订单与状态同步.md)。

## Day 17 司机工作台与履约页面

- `/driver/profile` 保存 1–64 字司机称呼；`/driver` 提供演示坐标、上线/离线、位置更新和待抢卡片；`/driver/orders` 与 `/driver/orders/{id}` 提供本人分页历史及抢单、开始/完成服务，操作二次确认并防止重复点击。
- 新增 `GET /api/drivers/me/status`，返回 `{profile, online}`；无资料时 profile 为 null，在线状态读取 Redis 有效标记，不续期、不信任前端缓存。该接口仅司机本人可读，无需修改数据库结构。
- 工作台每 5 秒读取状态和最近 20 条通知，仅展示当前 WAITING 订单，详情局部读取失败提供重试提示。在线时每 30 秒续期已确认坐标，输入框未提交的编辑不会自动上报。隐藏/离开工作台停止心跳和轮询；标记最长 120 秒过期。新标签页没有已确认坐标时，需主动更新位置以恢复心跳。
- 详情根据归属和状态提供抢单/开始/完成，司机不能模拟支付；409 后重新读取。状态变化会撤销旧确认，防止“确认抢单”意外变成“确认开始服务”；旧 GET 不能覆盖写入结果。真实权限、在线资格、占用与状态机仍由后端验证。
- 执行 `pwsh -NoProfile -File scripts/verify-day17.ps1 -Live`：构建及 **65/65** 浏览器测试通过，包括 42 项 Day15/16 回归、22 项司机模拟/移动测试和 1 项真实链路。真实司机在浏览器保存资料、上线、心跳、收到 MQ 卡片、抢单、开始/完成、查看历史并离线；乘客下单和模拟支付使用 API 夹具。两个临时账号及一笔 PAID 订单、归属/占用释放、事件消费核对与清理通过。
- 开启三个中间件集成开关执行 `mvn.cmd -o test`：**97/97** 通过。证据见 `logs/day17-*`；详见 [每日记录](项目文档/每日文档/Day17-司机工作台与履约页面.md)、[检验报告](项目文档/检验报告/Day17-检验报告.md)及[程序解读](项目文档/程序解读/Day17-司机工作台与心跳管理.md)。

## Day 18 双角色全流程联调与录制

- 独立乘客、司机 A、司机 B 三个浏览器会话，通过页面完成资料、上线、下单、取消、MQ 待抢、同时抢单、开始/完成、模拟支付、历史和离线。真实竞争恰好一个 `200`、一个 `409 ORDER_ALREADY_TAKEN`，失败司机看到请求编号且无法履约。
- 写操作失败后立即取代旧轮询读取最新订单；结果未确认或同步失败时暂停后续操作，支持手动刷新恢复。旧读取的错误不能禁用刚成功后的下一步操作。连续触发确认只发出一次写请求，各状态变化先验证另一角色自动同步，再验证双方刷新恢复。
- 首次录制前在 `web-app/` 执行 `npm.cmd exec playwright install ffmpeg`。完整验收：`pwsh -NoProfile -File scripts/verify-day18.ps1 -Live`。本次生产构建、**71/71** 浏览器测试通过，零失败/跳过/flaky；其中 64 项既有模拟/移动回归、6 项同步竞态测试、1 项真实三会话链路。此次未修改后端，未重跑 Maven 全仓测试。
- 三份录屏为 `logs/day18-passenger.webm`、`day18-driver-a.webm`、`day18-driver-b.webm`，均为 1440×1000、约 28 秒，已用 Chrome 实际播放并抽帧检查。原始测试、时间线、清理报告与截图位于 `logs/day18-*`；3 个临时账号及一笔 PAID、一笔 CANCELLED 订单已核对清理。详见 [每日记录](项目文档/每日文档/Day18-双角色全流程联调与录制.md)、[检验报告](项目文档/检验报告/Day18-检验报告.md)及[程序解读](项目文档/程序解读/Day18-双角色联调与冲突同步.md)。

## 演示账号（本机 account_db，虚构号码）

| 手机号 | 密码 | 角色 | 说明 |
| --- | --- | --- | --- |
| 13800138000 | Passw0rd!23 | PASSENGER | Day 4 注册演示乘客 |
| 13900139000 | Dr1ver!pass | DRIVER | Day 4 注册演示司机（司机资料 Day 6 创建） |
| 13600136000 | Passw0rd!23 | PASSENGER | Day 4 直连注册验证用例 |

> 以上只是本地演示数据，不是真实账号；Day 22 会按需重置。

> ⚠️ Nacos 3.x 控制台默认占 8080，与规格中 Gateway 的 8080 冲突。本机已在 `D:\devtools\nacos\nacos\conf\application.properties` 将 `nacos.console.port` 改为 **8090**（2026-10-05，验证记录见本地 `文档/Day2-工程搭建.md`）。Day 1 检查表里写的“控制台 8080”自此作废。

## 配置与安全约定

- 密码 / 密钥只通过环境变量或本地忽略文件提供（`application-local.yml` 已在 `.gitignore`），不提交 Git。
- 服务注册地址用 `NACOS_ADDR`（默认 `localhost:8848`）；数据库连接用 `SMARTDRIVE_DB_USER` / `SMARTDRIVE_DB_PASSWORD`；业务服务经网关访问的路径保留 `/api` 前缀（网关不做 `StripPrefix`）。
- 每个请求带 `X-Request-Id`：网关生成或透传并回写响应头，业务服务写入日志 MDC（日志格式含 `requestId`）。
- 接口约定：成功响应为 `Result` 包装（`code=0`）；失败响应统一 `{"code":"错误码","message":"文案","requestId":"..."}`，错误码为 `common-api` 的 `ErrorCode` 枚举名（如 `INVALID_PARAM`、`PHONE_ALREADY_EXISTS`）。
- 数据库 DDL（`db/` 目录，按 Day 增量维护）不含任何账号密码；项目专用数据库账号由本机私有配置创建。
- Redis Key 一律使用 `smartdrive:` 前缀。
- 数据库、MQ、Nacos 和内部服务端口不对公网开放，外部请求只从 Gateway 进入。
