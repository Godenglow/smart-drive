# SmartDrive 简化版代驾平台

微服务练手项目：乘客 / 司机双角色，覆盖注册登录、司机上线、附近司机查询、下单、异步待抢通知、并发抢单、履约与模拟支付，最终给出并发正确性测试与吞吐压测报告。

- 规格文档：[代驾项目开发文档.md](./代驾项目开发文档.md)
- 开发计划：[文档/每日开发计划.md](./文档/每日开发计划.md)
- 当前进度：**Day 4 —— account_db 建库、注册接口（BCrypt / 参数校验 / 重复手机号 409）与 springdoc 接入完成**（登录与 JWT 鉴权 Day 5，前端 Day 15）

## 技术栈（已锁定基线）

| 组件 | 版本 | 说明 |
| --- | --- | --- |
| JDK | 21 | 本机 Oracle JDK 21.0.2 |
| Maven | 3.8.6 | 运行在 JDK 21 上 |
| Spring Boot | 3.5.0 | 父 POM = `spring-boot-starter-parent` |
| Spring Cloud | 2025.0.0 | BOM 统一管理 |
| Spring Cloud Alibaba | 2025.0.0.0 | BOM 统一管理（nacos-client 3.x） |
| MyBatis | mybatis-spring-boot-starter 3.0.5 | 父 POM 统一锁定 |
| springdoc-openapi | 2.8.14 | WebMVC UI Starter；2.8.15+ 在 spring-web 6.2 上启动报错，见 [Day 4 文档](./文档/Day4-账户建库与注册接口.md) |
| MySQL / Redis / RabbitMQ / Nacos | 8.3 / 7.2.5 / 4.3.5 / 3.0.3 | 连接方式见 [Day 1 环境检查表](./文档/Day1-环境检查表.md) |

> 版本对应关系依据 [Spring Cloud Alibaba 官方版本说明](https://sca.aliyun.com/en/docs/2025.x/overview/version-explain/)：SCA 2025.0.0.0 ↔ Spring Cloud 2025.0.0 ↔ Spring Boot 3.5.0，不混用 Boot 4 / Cloud 2025.1 / SCA 2025.1。

## 模块与端口

| 模块 | 端口 | 说明 |
| --- | --- | --- |
| `gateway` | 8080 | 统一入口，对外只暴露 Gateway |
| `account-service` | 8081 | 注册、登录、JWT |
| `driver-service` | 8082 | 司机资料、上下线、位置（Redis GEO） |
| `order-service` | 8083 | 订单、抢单、履约、模拟支付、MQ 通知 |
| `common-api` | — | 错误码、事件 DTO、通用响应（jar，不启动） |
| `web-app` | Vite 自动分配 | Vue 3 + TS 前端，Day 15 再创建 |

## 构建与验证

```bat
mvn.cmd clean compile                :: 编译全部模块
mvn.cmd -DskipTests package          :: 打出可执行 jar（各模块 target/ 下）
mvn.cmd clean install -DskipTests    :: 同时安装到本地仓库（IDE 或单模块构建前需要）
```

## 启动顺序（每天开工按此执行）

1. **基础设施**
   - MySQL83、RabbitMQ：开机自启，无需操作。
   - CentOS VM（Redis 6379）：需手动开机，命令见 Day 1 检查表第二节。
   - Nacos：`D:\devtools\nacos\nacos\bin\startup.cmd -m standalone`（约 20 秒就绪）。
   - 开工自查一行命令：`netstat -ano | findstr "LISTENING" | findstr ":3306 :5672 :6379 :8848 :9848 :8090"`，六个端口齐了即可开工。
2. **数据库账号**：account-service 连接 `account_db` 用的是项目专用账号 `smartdrive`（建库脚本 `db/account_db.sql`），密码从本机环境变量 `SMARTDRIVE_DB_PASSWORD` 读取（用户名可用 `SMARTDRIVE_DB_USER` 覆盖，默认 `smartdrive`）。缺少该变量时服务启动会因数据库认证失败而退出。
3. **业务服务**按 `account-service → driver-service → order-service → gateway` 顺序启动（`java -jar 各模块/target/*.jar` 或 IDE）。
4. **健康检查**
   - http://localhost:8081/actuator/health （account-service）
   - http://localhost:8082/actuator/health （driver-service）
   - http://localhost:8083/actuator/health （order-service）
   - http://localhost:8080/actuator/health （gateway）
   - 经网关访问业务服务（验证注册与路由）：http://localhost:8080/actuator/account/health 、/actuator/driver/health 、/actuator/order/health
   - Nacos 控制台：http://localhost:8090/ （服务列表应恰好是三个业务服务，gateway 只做发现不注册）
5. **接口文档（springdoc）**：account-service 直连 http://localhost:8081/swagger-ui.html ，OpenAPI JSON 在 http://localhost:8081/v3/api-docs ；driver-service、order-service 在 Day 13 前接入。仅本机开发用，不经网关对外暴露。

## 演示账号（本机 account_db，虚构号码）

| 手机号 | 密码 | 角色 | 说明 |
| --- | --- | --- | --- |
| 13800138000 | Passw0rd!23 | PASSENGER | Day 4 注册演示乘客 |
| 13900139000 | Dr1ver!pass | DRIVER | Day 4 注册演示司机（司机资料 Day 6 创建） |
| 13600136000 | Passw0rd!23 | PASSENGER | Day 4 直连注册验证用例 |

> 以上只是本地演示数据，不是真实账号；Day 22 会按需重置。

> ⚠️ Nacos 3.x 控制台默认占 8080，与规格中 Gateway 的 8080 冲突。本机已在 `D:\devtools\nacos\nacos\conf\application.properties` 将 `nacos.console.port` 改为 **8090**（2026-10-05，验证记录见 [Day 2 工程搭建](./文档/Day2-工程搭建.md)）。Day 1 检查表里写的“控制台 8080”自此作废。

## 配置与安全约定

- 密码 / 密钥只通过环境变量或本地忽略文件提供（`application-local.yml` 已在 `.gitignore`），不提交 Git。
- 服务注册地址用 `NACOS_ADDR`（默认 `localhost:8848`）；数据库连接用 `SMARTDRIVE_DB_USER` / `SMARTDRIVE_DB_PASSWORD`；业务服务经网关访问的路径保留 `/api` 前缀（网关不做 `StripPrefix`）。
- 每个请求带 `X-Request-Id`：网关生成或透传并回写响应头，业务服务写入日志 MDC（日志格式含 `requestId`）。
- 接口约定：成功响应为 `Result` 包装（`code=0`）；失败响应统一 `{"code":"错误码","message":"文案","requestId":"..."}`，错误码为 `common-api` 的 `ErrorCode` 枚举名（如 `INVALID_PARAM`、`PHONE_ALREADY_EXISTS`）。
- 数据库 DDL（`db/` 目录，按 Day 增量维护）不含任何账号密码；项目专用数据库账号由本机私有配置创建。
- Redis Key 一律使用 `smartdrive:` 前缀。
- 数据库、MQ、Nacos 和内部服务端口不对公网开放，外部请求只从 Gateway 进入。
