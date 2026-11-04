# SmartDrive Web

Vue 3 + TypeScript + Vite + Vue Router。已完成 Day15 注册登录和角色路由，以及 Day16 乘客下单、订单列表/详情、轮询、取消与模拟支付。司机业务页面在 Day17 接入。

## 本地启动

需要 Node.js 22.12+、npm 和已经启动的 Gateway（8080）、account-service（8081）、Nacos、MySQL。订单业务还需要 driver-service（8082）、order-service（8083）、Redis 和 RabbitMQ；下单前上车点 5 km 内需有在线司机。后端启动方法见项目根 README。

```powershell
cd web-app
npm.cmd ci
npm.cmd run dev
```

打开 http://127.0.0.1:5173 。端口固定，已占用时明确报错。`/api` 原样代理到 `http://127.0.0.1:8080`，不删除前缀。若网关不在默认地址，复制 `.env.example` 为 `.env.local` 并设置 `SMARTDRIVE_GATEWAY_URL`，重启 Vite。根目录 `.env.local` 的数据库密码与 JWT 密钥不应复制到前端目录；浏览器不持有这些配置。

## 页面与会话

| 地址 | 用途 |
| --- | --- |
| `/login` | 手机号和密码登录，以 `/api/users/me` 的真实角色跳转 |
| `/register` | 选择乘客/司机注册；成功回登录页，不再次自动注册 |
| `/passenger` | 乘客首页 |
| `/passenger/orders/new` | 乘客下单，天津/上海坐标预设与本标签页草稿 |
| `/passenger/orders` | 本人订单分页列表，每 5 秒更新 |
| `/passenger/orders/:id` | 详情、状态同步、取消/模拟支付 |
| `/driver` | 司机工作台 |
| `/forbidden` | 角色不匹配的权限提示 |
| `/session-error` | 无法确认会话时重试连接或切换账号 |

JWT 与到期时间存入当前标签页 `sessionStorage`，刷新时重新请求当前账户，不使用浏览器缓存中的角色授权。密码不持久化。普通新标签页和独立浏览器会话可分别登录不同角色；浏览器“复制标签页”可能复制初始 sessionStorage，双角色演示请使用独立窗口/会话。到期或鉴权接口返回 401 时清空会话；网络/503 故障保留会话并显示重试入口。退出只清理浏览器会话，后端 JWT 在原有效期内仍然有效，未实现服务端撤销。

## 验证

```powershell
npm.cmd run build              # TypeScript 检查 + 生产构建
npm.cmd test                   # 42 项模拟/移动端测试；3 项真实测试默认跳过
cd ..
pwsh -NoProfile -File scripts/verify-day16.ps1 -Live
```

Day16 脚本读取项目根 `.env.local`，执行构建和 43 项测试（18 项 Day15 回归、24 项订单模拟/移动测试、1 项真实订单链路）。通过浏览器创建/取消订单及模拟支付，通过司机 API 夹具验证 MQ 通知和履约；两个临时账号 BCrypt 与两笔订单核对后，仅清理本次数据。需要 `mysql.exe`、`SMARTDRIVE_DB_PASSWORD`、RabbitMQ 管理 API 和本机 Chrome。可设置 `PLAYWRIGHT_CHANNEL=msedge`；截图与 JSON 报告位于根目录 `logs/day16-*`。移动测试使用 Chromium 设备模拟，未测真实 iOS Safari。原有 `verify-day15.ps1 -Live` 仍单独运行 Day15 的 20 项测试。

下单网络失败时保留草稿和 requestId，并锁定路线；“重试确认原订单”复用同一幂等请求。确认成功后才删除草稿。无附近司机等明确业务拒绝允许修改路线。草稿按账号隔离，关闭标签页后不保证保留；浏览器禁用存储时只保留当前页面内存。列表/详情隐藏时暂停轮询，离开中止请求；详情已取消/已支付后停止定时读取。

生产构建在 `dist/`。正式静态托管需要将 `/api` 反向代理到 Gateway，并为前端路由配置 `index.html` 回退。Vite 开发服务器只用于本机开发。
