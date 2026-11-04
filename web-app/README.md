# SmartDrive Web

Vue 3 + TypeScript + Vite + Vue Router。已完成 Day15 注册登录、Day16 乘客订单、Day17 司机页面及 Day18 双角色全流程、冲突同步与录制。

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
| `/driver/profile` | 司机称呼资料 |
| `/driver/orders` | 本人已接订单与历史分页 |
| `/driver/orders/:id` | 候选/本人订单详情、抢单、开始/完成服务 |
| `/forbidden` | 角色不匹配的权限提示 |
| `/session-error` | 无法确认会话时重试连接或切换账号 |

JWT 与到期时间存入当前标签页 `sessionStorage`，刷新时重新请求当前账户，不使用浏览器缓存中的角色授权。密码不持久化。普通新标签页和独立浏览器会话可分别登录不同角色；浏览器“复制标签页”可能复制初始 sessionStorage，双角色演示请使用独立窗口/会话。到期或鉴权接口返回 401 时清空会话；网络/503 故障保留会话并显示重试入口。退出只清理浏览器会话，后端 JWT 在原有效期内仍然有效，未实现服务端撤销。

## 验证

```powershell
npm.cmd run build              # TypeScript 检查 + 生产构建
npm.cmd test                   # 70 项模拟/移动端测试；真实测试默认跳过
npm.cmd exec playwright install ffmpeg  # 首次录制前安装
cd ..
pwsh -NoProfile -File scripts/verify-day18.ps1 -Live
```

Day17 脚本读取项目根 `.env.local`，执行构建和 65 项测试（42 项 Day15/16 回归、22 项司机模拟/移动测试、1 项真实司机链路）。司机业务操作全部通过浏览器完成，乘客下单/模拟支付通过 API 夹具完成；两个临时账号 BCrypt、一笔 PAID 订单、司机归属与占用释放及事件消费核对后，仅清理本次数据。需要四个服务、MySQL、Redis、RabbitMQ、Nacos、`mysql.exe`、`SMARTDRIVE_DB_PASSWORD`、RabbitMQ 管理 API 和本机 Chrome。可设置 `PLAYWRIGHT_CHANNEL=msedge`；截图与 JSON 报告位于根目录 `logs/day17-*`。移动测试使用 Chromium 设备模拟，未测真实 iOS Safari。Day15/16 验收脚本保留，可单独重跑此前的真实乘客链路。

Day18 脚本沿用上述环境，执行生产构建与 71 项测试（64 项既有回归、6 项同步竞态测试、1 项真实三会话链路）。使用独立乘客和两位司机会话，业务操作全部通过页面：取消、MQ 卡片、同时抢单、开始/完成、模拟支付、历史和离线，包含重复点击及每阶段刷新。临时账号由 API 夹具创建；不拦截真实 API，不预设竞争赢家。核对 3 账号、2 订单、候选通知、占用释放与事件消费后精确清理。录屏保存为 `logs/day18-passenger.webm`、`day18-driver-a.webm`、`day18-driver-b.webm`，其余证据见 `logs/day18-*`。未加 `-Live` 时真实测试明确跳过；未清理的账号清单会阻止下一次真实验收。

订单写失败后立即重新读取服务端状态；同步未成功时显示结果待确认并禁止后续动作，可通过“刷新状态”恢复。旧轮询的返回值与网络错误都不能覆盖新操作。`npm.cmd test -- --project=journey` 可单独复现这些模拟时序。

司机工作台每 5 秒检查最近 20 条通知，只显示 WAITING 卡片；在线时每 30 秒续期已确认的演示坐标。编辑经纬度后需点击更新位置，未提交的编辑不参与心跳。状态由 `/api/drivers/me/status` 恢复，位置缓存只用于本标签页续期，不作为上线凭据。隐藏或离开工作台即暂停心跳和通知轮询，在线标记最长 120 秒后过期，可主动离线立即移除。详情页、资料页和历史页不维持位置心跳，返回工作台会重新读取真实状态；没有缓存坐标时需主动确认位置。

下单网络失败时保留草稿和 requestId，并锁定路线；“重试确认原订单”复用同一幂等请求。确认成功后才删除草稿。无附近司机等明确业务拒绝允许修改路线。草稿按账号隔离，关闭标签页后不保证保留；浏览器禁用存储时只保留当前页面内存。列表/详情隐藏时暂停轮询，离开中止请求；详情已取消/已支付后停止定时读取。

生产构建在 `dist/`。正式静态托管需要将 `/api` 反向代理到 Gateway，并为前端路由配置 `index.html` 回退。Vite 开发服务器只用于本机开发。
