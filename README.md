# SmartDrive 代驾平台

基于 Spring Cloud 和 Vue 3 的代驾练手项目，提供乘客和司机双角色的完整业务流程。

## 主要功能

- 账号注册、登录与角色权限控制
- 司机资料、上下线、位置更新与附近司机查询
- 乘客下单、取消订单，司机接单与服务履约
- 异步订单通知、并发抢单、模拟支付与历史订单查询

## 技术栈

后端：Java 21、Spring Boot、Spring Cloud、Nacos、MyBatis、MySQL、Redis、RabbitMQ、JWT。

前端：Vue 3、TypeScript、Vite、Vue Router。

## 项目结构

| 模块 | 说明 | 端口 |
| --- | --- | --- |
| `gateway` | API 网关 | 8080 |
| `account-service` | 账号与认证 | 8081 |
| `driver-service` | 司机资料、位置与通知 | 8082 |
| `order-service` | 订单、抢单与履约 | 8083 |
| `common-api` | 通用响应、错误码与事件定义 | — |
| `web-app` | 前端页面 | 5173 |

## 本地运行

需要 Java 21、Maven、Node.js 22.12+ 和 PowerShell 7。

1. 启动 MySQL、Redis、RabbitMQ 和 Nacos，执行 `db/` 下的三个 SQL 脚本，为应用数据库账号授予对应库的读写权限。
2. 将 [.env.example](.env.example) 复制为 `.env.local`，填写数据库连接信息、JWT 密钥及内部服务令牌。JWT 密钥至少 32 个 UTF-8 字节。
3. 在项目根目录构建并启动后端：

```powershell
pwsh -NoProfile -File scripts/start-local.ps1 -Build
```

另开终端启动前端：

```powershell
cd web-app
npm.cmd ci
npm.cmd run dev
```

访问 [本地页面](http://127.0.0.1:5173)，注册乘客和司机账号即可体验。下单前，上车点 5 km 内需要有在线司机。

更多前端配置见 [前端说明](web-app/README.md)。
