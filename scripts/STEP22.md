# Step22 最终交付与演示

在项目根目录使用 PowerShell 7。首次安装和配置见 [启动指南](../项目文档/交付/启动指南.md)。

## 启动与验收

```powershell
pwsh -NoProfile -File scripts/start-local.ps1 -Build
python scripts/demo-data.py seed
# 另开终端
cd web-app
npm.cmd ci
npm.cmd run dev
```

四个后端按顺序从 `logs/local-runtime` 的稳定 JAR 副本启动，避免 Windows 中构建覆盖运行 JAR。启动脚本读取根 `.env.local`，显式进程变量优先；先探测 MySQL、Redis、RabbitMQ、Nacos TCP，再检查四服务和三个 Gateway 健康路由。已运行服务只有 JAR SHA256 与构建一致才复用；不一致时核实身份并重启，`-Restart` 强制重启。端口固定为 8080–8083，元数据记录实际监听 PID、JAR 路径和哈希。`mvn clean test` 删除 target JAR 后，可从稳定副本恢复；未知占端口进程明确报错。最终验收在打包后强制重启，确保运行新构建。

```powershell
pwsh -NoProfile -File scripts/verify-step22.ps1
```

入口串行执行：55 项工具测试、后端打包、启动自检、历史 SQL 指纹、专用演示批次重置、可执行 API 示例、真实订单及空批次重置验证、104 项真实中间件后端测试与 29 项 HTTP 检查及两种并发冒烟、Step15–18 四组真实浏览器回归（20/45/69/75 次）、三会话录制及解码预览、独立 MQ vhost 诊断、Step20/21 原始证据复算、最终 SQL 与健康检查。HTTP 包含网关和直连服务的编码/矩阵路径拒绝、415 与多字节密码校验。每个子步骤退出码、浏览器零跳过/失败/flaky、清理与原始证据都必须通过，才标记整体成功。

验收会短暂停止订单服务，隔离集成测试自建 Outbox 事件与运行中的发布器；停止前复制实际运行 JAR 到 `logs/step19-recovery`，`finally` 从该副本恢复，不依赖 target JAR。避免同时操作页面或运行其他压测。Step20 的 10/100/500 并发矩阵与 Step21 两组五分钟性能测量使用已保存的正式原始证据重新核对，最终验收不重复测量这些历史性能成绩。新机器没有历史 `logs/` 时，先按 [STEP20](STEP20.md) 与 [STEP21](STEP21.md) 生成证据；缺文件会明确失败。

阶段编号已统一为 `Step`，脚本、测试模块、报告字段和新增日志路径同步改名。旧命名的本机日志与演示批次保留原样，不自动迁移，也不当作新路径下的验收证据。首次使用新命名运行最终验收前，先按 [STEP20](STEP20.md) 和 [STEP21](STEP21.md) 重新生成性能证据，再执行 `python scripts/demo-data.py seed` 建立独立演示批次；旧演示账号仍保留。

## 专用演示数据

```powershell
python scripts/demo-data.py inspect
python scripts/demo-data.py seed
python scripts/demo-api.py
python scripts/demo-data.py reset
python scripts/demo-data.py cleanup
```

`seed` 注册一个全新乘客、两位全新司机，创建司机资料，初始无订单且离线；已有 ready 批次时只核对、重新导出凭据。账号和随机密码保存在 **`logs/step22/demo-accounts.local.json`**，仅本机查看，整个 `logs/` 被 Git 忽略。凭据不打印到日志、报告或示例中。

`reset` 清理当前登记批次后生成新批次、新账号和密码；清理后须退出旧页面并用新凭据登录。`cleanup` 只清理不重建，可重复执行。重置仅管理新建的 Step22 专用账号及该乘客通过页面/API 创建的订单，不接管 README 中的旧演示账号。

清理使用批次 UUID、账号 ID+手机号+角色+BCrypt 核对归属，等待本批 Outbox SENT、消费记录匹配、共享主队列无 ready/unacked 后，逐位司机离线。发现外部司机接走本批订单、演示司机关联外部订单/活动占用/通知时拒绝扩大范围，保留现场。精确删除订单关联表并复查，再删除账号/资料；不清空库、Redis 或共享队列。每个阶段有本机清单与订单快照，异常时恢复服务后重跑同一 `reset/cleanup`，不要手动删除清单。协调锁阻止多个管理 CLI 同时改指针；API 示例持有批次锁。浏览器无法持有 CLI 文件锁，因此重置期间应停止演示写入。

旧批次 `manifest.json` 记录 cleaned/cleanupVerified 与已清理 ID，移除密码。最终 SQL 审计对非 Step22 演示批次的七张表逐行排序计算 SHA256，核对测试前后行数和指纹完全一致；不会导出密码哈希原文。

## 证据和录屏

每次验收使用新 UUID，目录 `logs/step22/<runId>/`，总入口摘要 `logs/step22-result.json`。

| 文件 | 内容 |
| --- | --- |
| `result.json` | 步骤、时间、整体通过状态 |
| `audit.json` | 后端/浏览器/压测证据、健康、历史 SQL 指纹与文件 SHA256 |
| `historical-baseline.json` | 执行前七张非演示业务表的行数/指纹 |
| `demo-api-result.json` | 可执行 API 示例状态链、幂等 ID、真实通知 |
| `demo-reset-audit.json` | 真实 MQ 订单清理、再次空重置、新账号离线状态 |
| `step19-*.json` | 后端及真实 HTTP 结果、批次 ID |
| `step15/16/17/18-*.json` | 四组浏览器执行及清理结果；有重复回归用例，不能累加为唯一用例数 |
| `passenger.webm`、`driver-a.webm`、`driver-b.webm` | 本次独立乘客/双司机页面录制 |
| `step18-timeline.json` | 取消、MQ 通知、竞争、履约、支付时间线 |
| `openapi-account/driver/order.json` | 本次运行服务导出的接口定义 |
| `step21-comparison.json` | 逐请求/SQL/提交日志/哈希重新核对的性能对照 |
| `*.log` | 各步骤原始输出 |

完整演示步骤见 [演示与已知边界](../项目文档/交付/演示与已知边界.md)，API 见 [API 示例](../项目文档/交付/API示例.md)，架构见 [架构说明](../项目文档/交付/架构说明.md)。录屏含虚构账号与订单，仅分享确认后的演示媒体，不分享私有凭据文件或全部 logs。
