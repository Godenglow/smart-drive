# Day 3 服务注册与网关路由记录（2026-10-09）

> **结论**：Day 3 交付物已达成 —— 三个业务服务注册 Nacos（实例 `healthy=true`）；Gateway 按服务名（`lb://`）转发，经 8080 可访问三个健康检查；`requestId` 的生成、透传、日志落 MDC 全链路可用；order-service 停止后注销、重启后自动重新注册，经网关复查仍 `200`。**可进入 Day 4**。
>
> 检查方式：命令行实测（Maven 构建输出、Nacos Open API、curl 响应头、服务日志）。

## 一、完成内容

| 项 | 内容 |
| --- | --- |
| 依赖 | 三个业务服务 + gateway 增加 `spring-cloud-starter-alibaba-nacos-discovery`；gateway 增加 `spring-cloud-starter-loadbalancer`（`lb://` 路由必需）；四个模块增加内部依赖 `common-api` |
| 注册配置 | 四个服务 `application.yml` 统一 `spring.cloud.nacos.discovery.server-addr: ${NACOS_ADDR:localhost:8848}`；gateway 额外 `register-enabled: false`（只做服务发现，不注册自己） |
| 路由 | Gateway 4.3.0 用新属性前缀 `spring.cloud.gateway.server.webflux.routes`（旧前缀 `spring.cloud.gateway.*` 已是弃用别名）；`/api/auth/**` + `/api/users/**` → `lb://account-service`；`/api/drivers/**` → `lb://driver-service`；`/api/orders/**` → `lb://order-service`；健康检查 `/actuator/{account\|driver\|order}/health` → 对应服务 + `SetPath=/actuator/health` |
| requestId | `common-api` 新增 `RequestIds`（头名 `X-Request-Id`、MDC 键 `requestId`、8~64 位安全字符校验）；gateway 加 `RequestIdGlobalFilter`（最高优先级）生成/透传并回写响应头；三个业务服务各加 `RequestIdFilter` 写 MDC、回写响应头、每请求一行日志 |
| 日志格式 | 三个业务服务 `logging.pattern.correlation: "[${spring.application.name:-},%X{requestId:-}]"`，用 Boot 3.4+ 自带关联 ID 机制 |
| 启动顺序 | 不变：account-service → driver-service → order-service → gateway；Nacos 先于四者 |

改动文件（10 个）：

- `common-api/src/main/java/com/smartdrive/common/api/RequestIds.java`（新增）
- `gateway/pom.xml`、`gateway/src/main/resources/application.yml`、`gateway/src/main/java/com/smartdrive/gateway/filter/RequestIdGlobalFilter.java`
- `account-service`、`driver-service`、`order-service` 各：`pom.xml`、`src/main/resources/application.yml`、`src/main/java/com/smartdrive/<模块>/filter/RequestIdFilter.java`（新增）

## 二、验证结果

### 1. 三个业务服务注册 Nacos —— 通过

启动日志（各服务启动约 17 秒）：

```text
nacos registry, DEFAULT_GROUP account-service 192.168.234.1:8081 register finished
nacos registry, DEFAULT_GROUP driver-service  192.168.234.1:8082 register finished
nacos registry, DEFAULT_GROUP order-service   192.168.234.1:8083 register finished
```

Nacos 服务端实查（`GET /nacos/v1/ns/instance/list?serviceName=order-service`）：

```json
"hosts":[{"instanceId":"192.168.234.1#8083##DEFAULT_GROUP@@order-service","ip":"192.168.234.1","port":8083,
          "healthy":true,"enabled":true,"ephemeral":true,"metadata":{"preserved.register.source":"SPRING_CLOUD"}}]
```

三个服务各 1 个健康实例；`GET /nacos/v1/ns/service/list` 返回 `{"count":3,"doms":["account-service","driver-service","order-service"]}`——正好是三个业务服务，gateway 不在其中。

> Nacos 3.0.3 的 v3 admin API 需要鉴权（`403 User not found`），本次核实用 v1 Open API；控制台仍可在 `http://localhost:8090/` 用界面查看。

### 2. 经 Gateway 访问三个健康检查 —— 通过

```text
GET http://localhost:8080/actuator/account/health  -> account=200
GET http://localhost:8080/actuator/driver/health   -> driver=200
GET http://localhost:8080/actuator/order/health    -> order=200
```

响应体为 `{"status":"UP","components":{"discoveryComposite":{...}}}`，其中 `discoveryClient` 显示的服务列表与 Nacos 一致，说明网关确实通过服务发现（`lb://`）找到实例，而不是写死地址。

业务路由同样生效：`GET /api/orders` 返回 `404`（服务内尚无控制器），且 order-service 日志出现 `--> GET /api/orders requestId=...`，证明 `/api/**` 路由已转发到目标服务；Day 4 补上控制器后即为可用接口。

### 3. 服务重启后自动重新注册 —— 通过

1. 停止 order-service：8083 端口释放，Nacos 中该服务 `hosts` 变为 `[]`（实例注销）；
2. 重新启动：日志再次出现 `nacos registry, DEFAULT_GROUP order-service ... register finished`，实例恢复 `healthy=true`；
3. 经网关复查 `/actuator/order/health` 恢复 `200`，无需任何手工操作。

### 4. requestId 生成 / 透传 / 日志 —— 通过

| 场景 | 请求 | 响应头观察 |
| --- | --- | --- |
| 客户端未带 | `curl -i /actuator/account/health` | `X-Request-Id: b03b235f-...`（网关生成，单值） |
| 客户端带合法值 | `-H "X-Request-Id: day3-trace-0002"` | `X-Request-Id: day3-trace-0002`（原样透传） |
| 客户端带非法值 | `-H "X-Request-Id: bad id!"` | 换成新 UUID（8~64 位安全字符校验生效） |

同一次调用在网关与业务服务日志中是同一个 requestId，且业务服务日志的关联段带 MDC：

```text
[gateway]         --> GET /actuator/account/health requestId=b03b235f-c8d9-46e8-b2ee-7c7379ecae6b
[account-service] [account-service,b03b235f-c8d9-46e8-b2ee-7c7379ecae6b] --> GET /actuator/health requestId=b03b235f-...
```

### 5. 构建与收尾

- `mvn.cmd clean install -DskipTests`：**BUILD SUCCESS**（6 模块，common-api 已进入本地仓库）。
- 四个服务停止后，`netstat` 复查 8080–8083 全部释放，无残留 java 进程；Nacos（8848/9848/8090）保持运行。
- 运行日志留在 `logs/`（已被 `.gitignore` 覆盖，未入库）。

## 三、当日发现并处理的问题

### 1. Gateway 4.3.0 配置属性前缀已迁移 —— 已按新前缀编写

- **现象**：Spring Cloud 2025.0.0 解析到 `spring-cloud-gateway-server 4.3.0`；其配置元数据中 `spring.cloud.gateway.*` 与 `spring.cloud.gateway.server.webflux.*` 两套前缀并存，后者为规范写法，且 starter 已带上 `spring-boot-properties-migrator`（用于提示属性迁移）。
- **处理**：路由统一写在 `spring.cloud.gateway.server.webflux.routes` 下，避免启动期的弃用告警和后续版本移除风险。

### 2. 响应头 `X-Request-Id` 重复出现两行 —— 已修复

- **现象**：网关写入响应头后，下游服务也回写了同名头，两者在网关合并响应时叠加，返回两个 `X-Request-Id`（同值）。前端若按 `res.headers['x-request-id']` 取值会得到 `"a, a"` 这类合并字符串。
- **处理**：网关改为 `response.beforeCommit(...)` 中 `set` 覆盖，等下游响应头合并完成后再写；复测只剩一行。
- **保留**：业务服务继续回写该头，保证绕过网关直连服务时也能看到 requestId。

### 3. Gateway 自己也注册进了 Nacos —— 已关闭

- **现象**：首轮启动时 Nacos 服务列表出现 `gateway`。架构上网关只负责发现服务。
- **处理**：gateway 增加 `spring.cloud.nacos.discovery.register-enabled: false`，重启后日志无 `register finished`，Nacos 列表只剩三个业务服务。

### 4. Nacos 会短暂保留"空服务壳" —— 判断依据改看实例列表

- **现象**：服务停止后，`/v1/ns/service/list` 仍列出该服务名，但其 `hosts` 已为空；约 1 分钟后服务名的空壳也被清理（order-service 重启后复查，列表回到 3 个）。
- **结论**：判断"服务是否在册"应看 `/v1/ns/instance/list?serviceName=xxx` 的 `hosts`，而不是服务名列表；这是 Nacos 的正常机制，不是注册失败。

## 四、本日确立的约定（后续 Day 直接沿用）

1. **`/api` 前缀保留到服务内部**：网关不做 `StripPrefix`，业务服务控制器直接映射 `/api/...`，内外路径一致，便于按日志排查。
2. **requestId 约定**：请求头 `X-Request-Id`；日志 MDC 键 `requestId`；网关与业务服务都用 `common-api` 的 `RequestIds.resolve(...)`，不要各自硬编码字符串。
3. **健康检查经网关的路径**：`/actuator/{account|driver|order}/health`，只映射 `health` 一条，避免把其他 actuator 端点暴露出去。
4. **依赖管理**：内部依赖用 `${project.version}` 引用 `common-api`；IDE 或单模块构建前先执行一次 `mvn.cmd clean install -DskipTests`。

## 五、每日进度记录（按开发计划模板）

```text
日期 / Day：2026-10-09 / Day 3
完成的交付物（附提交或截图）：三服务 Nacos 注册、Gateway 服务名路由（/api 三类 + 健康检查）、
    requestId 生成/透传/MDC 日志；common-api 新增 RequestIds
验证方式与结果：mvn.cmd clean install BUILD SUCCESS；Nacos 实例 healthy=true（3 个服务各 1 个实例）；
    经 8080 访问三个健康检查均 200；order-service 停→注销、重启→自动重注册→网关 200；
    requestId 生成/透传/非法值替换 3 个场景 + 网关与服务日志同一 ID
阻塞项及处理人：无阻塞
下一工作日的第一件事：Day 4 —— 执行 account_db DDL；实现注册、账户查询、BCrypt 密码存储；
    参数校验与重复手机号错误响应
```
