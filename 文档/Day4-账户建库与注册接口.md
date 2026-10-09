# Day 4 账户建库与注册接口记录（2026-10-09）

> **结论**：Day 4 交付物已达成 —— `account_db` 与 `account` 表按规格 §5 建好，项目专用账号可用；`POST /api/auth/register` 经网关与直连均可用（200 返回 `userId`）；密码只存 BCrypt 哈希（`$2a$10$`、60 位，无明文）；非法参数 400、重复手机号 409 均有明确错误响应；springdoc 接入，`/v3/api-docs` 与 `/swagger-ui.html` 可用。账户查询的**数据访问层已就绪，`GET /api/users/me` 不开放**（未接入 JWT 前一律 401），其授权验收留 Day 5。**可进入 Day 5**。
>
> 检查方式：命令行实测（MySQL 客户端、curl 请求与响应、服务日志、OpenAPI JSON）。

## 一、完成内容

| 项 | 内容 |
| --- | --- |
| 建库 | 执行 `db/account_db.sql`：`account_db`（utf8mb4）+ `account` 表（id / phone 唯一 / password_hash / role / created_at(3)），与规格 §5 DDL 一致 |
| 数据库账号 | 新建本机专用账号 `smartdrive@localhost`，只授权 `account_db.*`；密码只写本机（用户环境变量 `SMARTDRIVE_DB_PASSWORD`，`setx` 持久化），不进仓库与文档 |
| 数据访问 | MyBatis（annotation Mapper）：`selectByPhone` / `selectById` / `insert(useGeneratedKeys)`；`map-underscore-to-camel-case` |
| 注册接口 | `POST /api/auth/register`（公开）：`phone` / `password` / `role`；`role` 只允许 PASSENGER、DRIVER；重复手机号先查后插 + 唯一键兜底 |
| 密码存储 | `spring-security-crypto` 的 `BCryptPasswordEncoder`（未引入完整 Spring Security Web） |
| 统一错误响应 | `common-api` 新增 `ErrorCode` / `ApiError` / `ApiException` / `GlobalExceptionHandler`：失败响应 `{code, message, requestId}`（规格 §7.1），500 只记日志不返回堆栈 |
| 账户查询 | `AccountService.findById` + `AccountMapper.selectById` 已实现；`GET /api/users/me` 接口代码就位但固定 401，**不临时开放** |
| springdoc | 三个业务服务统一在父 POM 锁定 `springdoc-openapi-starter-webmvc-ui 2.8.14`（本次先落 account-service）；`/v3/api-docs`、`/swagger-ui.html` 实测可用 |
| 依赖锁定 | 父 POM 新增 `springdoc.version=2.8.14`、`mybatis-spring-boot.version=3.0.5`；mysql-connector-j 9.2.0 由 Boot BOM 管理 |

改动文件：

- 新增：`db/account_db.sql`；`common-api` 的 `ErrorCode.java`、`ApiError.java`、`ApiException.java`、`web/GlobalExceptionHandler.java`；account-service 的 `entity/Account.java`、`entity/Role.java`、`mapper/AccountMapper.java`、`dto/RegisterRequest.java`、`dto/RegisterResponse.java`、`dto/AccountView.java`、`service/AccountService.java`、`controller/AuthController.java`、`controller/UserController.java`、`config/PasswordEncoderConfig.java`、`config/OpenApiConfig.java`
- 修改：父 `pom.xml`（版本属性与 dependencyManagement）；`common-api/pom.xml`（spring-web / spring-context / jakarta.servlet-api(provided) / slf4j-api）、`common-api/.../RequestIds.java`（新增 `current()` 供错误响应回填 requestId）；`account-service/pom.xml`（validation、mybatis、mysql、spring-security-crypto、springdoc）、`application.yml`（datasource / mybatis / springdoc）、`AccountApplication.java`（扫描 `com.smartdrive.common.api`）

## 二、验证结果

### 1. 建库建表与专用账号 —— 通过

实际执行的 SQL（密码以本机私有值为准，此处不记录）：

```sql
-- db/account_db.sql
CREATE DATABASE IF NOT EXISTS account_db CHARACTER SET utf8mb4;
CREATE TABLE account_db.account (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  phone VARCHAR(32) NOT NULL UNIQUE,
  password_hash VARCHAR(100) NOT NULL,
  role VARCHAR(16) NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
);
-- 本机执行（一次）：创建项目专用账号并授权
CREATE USER IF NOT EXISTS 'smartdrive'@'localhost' IDENTIFIED BY '<本机私有>';
GRANT ALL PRIVILEGES ON account_db.* TO 'smartdrive'@'localhost';
FLUSH PRIVILEGES;
```

结构核对（`SHOW CREATE TABLE`）：`id` bigint 自增主键、`phone` 唯一键、`created_at datetime(3)` 默认 `CURRENT_TIMESTAMP(3)`、`CHARSET=utf8mb4`，与规格一致；以 `smartdrive` 账号实测 `SELECT` 通过。

### 2. 注册与错误响应 —— 通过（13 个用例，全部符合预期）

经网关（`http://localhost:8080`）为主，直连（`:8081`）抽测；请求/响应原文留档 `logs/day4-http-tests.txt`（logs 未入库）。关键用例：

| # | 用例 | 请求要点 | 结果 |
| --- | --- | --- | --- |
| A | 乘客注册（经网关） | `13800138000 / Passw0rd!23 / PASSENGER`，带 `X-Request-Id: day4-reg-0001` | `200` `{"code":0,"message":"ok","data":{"userId":1}}`，响应头回带 `X-Request-Id: day4-reg-0001` |
| B | 重复手机号 | 再次注册 `13800138000` | `409` `{"code":"PHONE_ALREADY_EXISTS","message":"手机号已注册","requestId":"day4-reg-0002"}` |
| C | 手机号格式非法 | `phone=12345` | `400` `INVALID_PARAM`，`phone: 手机号需为 1[3-9] 开头的 11 位数字` |
| D | 缺密码 | 无 `password` 字段 | `400` `password: 密码不能为空` |
| E | 非法角色 | `role=ADMIN` | `400` `role 只能是 PASSENGER 或 DRIVER` |
| F | 密码过短 | `password=123` | `400` `password: 密码长度需为 8~64 位` |
| G | 请求体非 JSON | `{phone:oops` | `400` `请求体缺失或 JSON 格式错误` |
| H | 司机注册（经网关） | `13900139000 / DRIVER` | `200`，`userId=2` |
| I/J | `GET /api/users/me` | 直连与经网关各一次 | 均 `401` `{"code":"UNAUTHORIZED","message":"未登录或令牌无效",...}` |
| K | 直连注册（绕过网关） | `13600136000`，`http://localhost:8081` | `200`，`userId=3`（服务不依赖网关可用） |
| L | 未知路径 | `GET /api/users/unknown-path` | `404` `NOT_FOUND`（统一错误结构） |
| M | 方法不支持 | `GET /api/auth/register` | `405` `METHOD_NOT_ALLOWED` |

### 3. 数据库落库检查 —— 通过

```text
id  phone         role       hash_prefix  hash_len  created_at               now_utc
1   13800138000   PASSENGER  $2a$10$      60        2026-10-09 14:05:35.191  2026-10-09 14:05:42.255
2   13900139000   DRIVER     $2a$10$      60        2026-10-09 14:05:35.891  2026-10-09 14:05:42.255
3   13600136000   PASSENGER  $2a$10$      60        2026-10-09 14:05:36.187  2026-10-09 14:05:42.255
```

- 三条记录均为 BCrypt 哈希（前缀 `$2a$10$`、长度 60）；`password_hash LIKE '%Passw0rd%'` 命中 0 行，**库中无明文**。
- `created_at` 与 `UTC_TIMESTAMP(3)` 一致（比本机北京时间早 8 小时）：连接串 `connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true` 生效，会话时区为 UTC，符合"时间统一保存 UTC"。

### 4. 注册链路与日志 —— 通过

- account-service 启动约 23 秒，注册 Nacos 实例 `healthy=true`；`/actuator/health` 直连与经网关均 `200`。
- 服务日志与网关同一 requestId（MDC 关联）：

```text
[account-service,day4-reg-0001] --> POST /api/auth/register requestId=day4-reg-0001
[account-service,day4-me-0001]  --> GET /api/users/me requestId=day4-me-0001
```

### 5. springdoc 接口文档 —— 通过（注册与账户查询接口说明）

- `GET /v3/api-docs` → `200`；文档含 `POST /api/auth/register`（响应 200 / 400 / 409 与字段校验规则 `pattern=1[3-9]\d{9}`、`minLength=8`）与 `GET /api/users/me`（响应 200 / 401），失败响应 schema 为 `ApiError{code,message,requestId}`。留档 `logs/day4-api-docs.json`。
- `GET /swagger-ui.html` → `302` 跳转 `/swagger-ui/index.html` → `200`。
- 按规格 §9.6 安排：登录接口说明与 JWT Bearer 的 Swagger 调试（Authorize 按钮）**不在本日验收**，随 Day 5 的 JWT 一起做。

### 6. 演示数据（本机 account_db，虚构号码）

| userId | 手机号 | 密码 | 角色 |
| --- | --- | --- | --- |
| 1 | 13800138000 | Passw0rd!23 | PASSENGER |
| 2 | 13900139000 | Dr1ver!pass | DRIVER |
| 3 | 13600136000 | Passw0rd!23 | PASSENGER |

### 7. 构建与收尾

- `mvn.cmd clean install -DskipTests`：**BUILD SUCCESS**（6 模块）。
- 服务停止后 `netstat` 复查 8080 / 8081 已释放；Nacos（8848/9848/8090）保持运行，MySQL、RabbitMQ 不受影响。
- 运行日志与请求留档在 `logs/`（已忽略，未入库）。

## 三、当日发现并处理的问题

### 1. springdoc 2.8.15+ 与 spring-web 6.2 不兼容 —— 锁定 2.8.14

- **现象**：按"最新补丁"选用 2.8.17 时，account-service 启动即失败：
  `Invalid mapping pattern detected: /swagger-ui/**/*swagger-initializer.js → No more pattern data allowed after {*...} or ** pattern element`（`resourceHandlerMapping` 创建失败）。
- **定位**：反编译/源码核对 2.8.17 的 `AbstractSwaggerConfigurer`：它用 `PathPattern.combine` 把 `/swagger-ui/**` 与 `/*swagger-initializer.js` 拼成上述非法 pattern；在本机 spring-web 6.2.7 上该 `combine` 调用直接抛异常（本地用最小 Java 程序复现）。上游同问题见 springdoc issue #3210（2.8.15 引入、2.8.14 正常）。2.9.x 源码中同样是该拼法。
- **处理**：父 POM 锁定 `springdoc-openapi-starter-webmvc-ui 2.8.14`（CHANGELOG 中面向 Spring Boot 3.5.7 的最后一个 2.8.x 补丁，仍属规格要求的 2.8.x 线），启动与文档页面均正常。
- **注意**：后续 Day 8/13 给 driver-service、order-service 接入 springdoc 时沿用 2.8.14；升级 springdoc 前先核对上游修复（勿用 2.8.15+ / 2.9.x 直接替换）。

### 2. common-api 增加统一异常处理后缺编译依赖 —— 已补

- **现象**：`GlobalExceptionHandler` 编译报错：`org.springframework.validation.BindException`（来自 `MethodArgumentNotValidException` 父类）与 `jakarta.servlet.ServletException` 不在 common-api 类路径。
- **处理**：common-api 补 `spring-context`（compile）；`jakarta.servlet-api` 用 **provided** 作用域，只用于编译，不向 WebFlux 的 gateway 传递（避免 WebFlux 应用被误判为 Servlet 应用）。

### 3. `GET /api/users/me` 的开放节奏 —— 本日不开放

- 按当日口径：数据访问层（`selectById` + `findById`）已完成、接口代码与 Swagger 说明就位，但**不临时放开** `/me`：当前实现固定返回 `401 UNAUTHORIZED`，不返回任何账户数据。
- **因此**：`selectById` 的实际读取尚未有授权入口验证，其正确性随 Day 5 的 `/me` 授权测试一并验收；本日实测覆盖的查询路径是 `selectByPhone`（重复手机号检查走的就是它）。

### 4. 数据库账号与密码不落盘仓库 —— 按约定执行

- 建库脚本只含 DDL；专用账号 `smartdrive` 的密码用 `setx` 写入本机用户环境变量 `SMARTDRIVE_DB_PASSWORD`（新开的终端生效），本文件与仓库均不记录。本机变更已列入"供《我的配置.md》更新参考"（见第五节）。

## 四、本日确立的约定（后续 Day 直接沿用）

1. **失败响应统一结构**：`{"code":"<ErrorCode 枚举名>","message":"<中文文案>","requestId":"..."}`；HTTP 状态码由错误码携带（400/401/403/404/405/409/500）。成功继续用 `common-api` 的 `Result` 包装（`code=0`）。`Result.fail` 不再使用，新代码抛 `ApiException`。
2. **错误码演进规则**：新增错误码只往 `common-api` 的 `ErrorCode` 枚举加，不在各服务自定义字符串；文案可由 `ApiException` 覆盖，编码不变。
3. **数据库访问**：MyBatis annotation Mapper + `map-underscore-to-camel-case`；实体列名映射依赖该配置，不写重复的 XML。
4. **密码**：业务密码一律 `PasswordEncoder`（BCrypt）单向哈希后入库；演示数据也用真实哈希，不因"演示"存明文。
5. **接口文档**：业务服务各自接入 springdoc（8081/8082/8083 直连访问），不做网关聚合；给前端看的接口都要在 Swagger 里写清错误码与示例。
6. **时间**：数据库连接会话时区固定 UTC（连接串已带参数），新增表沿用 `DATETIME(3)` + `CURRENT_TIMESTAMP(3)` 默认值。

## 五、本机变更记录（供《我的配置.md》下次更新参考）

| 位置 | 变更 |
| --- | --- |
| 环境变量（用户级） | 新增 `SMARTDRIVE_DB_PASSWORD`（本项目 account_db 专用账号密码，`setx` 持久化） |
| MySQL 8.3 | 新增库 `account_db` 与表 `account_db.account`；新增账号 `smartdrive@localhost`（仅 `account_db.*` 授权） |
| 项目目录 | 新增 `db/account_db.sql`；`logs/` 下新增 Day 4 运行日志与请求留档（未入库） |

## 六、每日进度记录（按开发计划模板）

```text
日期 / Day：2026-10-09 / Day 4
完成的交付物（附提交或截图）：account_db 建库建表（db/account_db.sql）与专用账号；
    POST /api/auth/register（BCrypt、参数校验、重复手机号 409）；
    common-api 统一错误响应与全局异常处理；account-service 接入 springdoc（注册与账户查询接口说明）；
    账户查询数据访问层（selectById/findById）+ /api/users/me 接口代码（未开放，固定 401）
验证方式与结果：mvn.cmd clean install BUILD SUCCESS；13 个接口用例全部符合预期
    （200/400×5/409/401×2/404/405，直连与经网关均验）；库中密码为 $2a$10$ BCrypt 且无明文；
    created_at 与 UTC_TIMESTAMP 一致；/v3/api-docs 200 且含注册/查询路径、/swagger-ui.html 302→200
阻塞项及处理人：无阻塞；springdoc 版本问题已定位并锁定 2.8.14（详见第三节）
下一工作日的第一件事：Day 5 —— 实现登录发 JWT；Gateway 与业务服务验签、角色授权；
    补账户接口测试；/me 开放并验收授权；Swagger 加 Bearer 调试
```
