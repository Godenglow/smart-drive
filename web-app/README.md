# SmartDrive Web

基于 Vue 3、TypeScript 和 Vite 的乘客端与司机端页面。

## 本地启动

需要 Node.js 22.12+，并先按[项目说明](../README.md)启动后端。

```powershell
npm.cmd ci
npm.cmd run dev
```

访问 [本地页面](http://127.0.0.1:5173)。`/api` 默认代理到网关 `http://127.0.0.1:8080`。
如需修改网关地址，将 `.env.example` 复制为 `.env.local`，设置 `SMARTDRIVE_GATEWAY_URL`。

## 生产构建

执行 `npm.cmd run build`，产物位于 `dist/`。部署时为 `/api` 配置网关代理，并将前端路由回退到 `index.html`。
