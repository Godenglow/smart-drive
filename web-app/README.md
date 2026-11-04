# SmartDrive Web

Day15 前端：Vue 3 + TypeScript + Vite + Vue Router。已接通账户注册、登录、当前账户查询、双角色首页和权限提示。乘客订单页面与司机业务操作分别在 Day16、Day17 接入。

## 本地启动

需要 Node.js 22.12+、npm 和已经启动的 Gateway（8080）、account-service（8081）、Nacos、MySQL。后端启动方法见项目根 README。

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
| `/driver` | 司机工作台 |
| `/forbidden` | 角色不匹配的权限提示 |
| `/session-error` | 无法确认会话时重试连接或切换账号 |

JWT 与到期时间存入当前标签页 `sessionStorage`，刷新时重新请求当前账户，不使用浏览器缓存中的角色授权。密码不持久化。普通新标签页和独立浏览器会话可分别登录不同角色；浏览器“复制标签页”可能复制初始 sessionStorage，双角色演示请使用独立窗口/会话。到期或鉴权接口返回 401 时清空会话；网络/503 故障保留会话并显示重试入口。退出只清理浏览器会话，后端 JWT 在原有效期内仍然有效，未实现服务端撤销。

## 验证

```powershell
npm.cmd run build              # TypeScript 检查 + 生产构建
npm.cmd test                   # 18 项模拟/移动端测试；2 项真实测试默认跳过
cd ..
pwsh -NoProfile -File scripts/verify-day15.ps1 -Live
```

完整脚本读取项目根 `.env.local`，执行构建、20 项 Chrome 浏览器测试，通过真实 Gateway/MySQL 注册两个临时账号，核对 BCrypt 存储并按 ID + 手机号精确清理。需要 `mysql.exe` 在 PATH、`SMARTDRIVE_DB_PASSWORD` 可用以及本机 Chrome。可通过 `PLAYWRIGHT_CHANNEL=msedge` 使用已安装 Edge；截图与 JSON 报告位于根目录 `logs/day15-*`。移动测试使用 Chromium 的设备模拟，尚未在真实 iOS Safari 上验收。

生产构建在 `dist/`。正式静态托管需要将 `/api` 反向代理到 Gateway，并为前端路由配置 `index.html` 回退。Vite 开发服务器只用于本机开发。
