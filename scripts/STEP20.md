# Step20：10 / 100 / 500 并发抢单验收

在项目根目录执行，使用 Step19 的 Python 标准库工具及本机私有配置。先按根 README 启动 MySQL、Redis、RabbitMQ、Nacos、三个业务服务和 Gateway。

正式测量使用完整的 Spring Boot 可执行 JAR。运行副本放在独立目录可避免后续 Maven 构建覆盖已打开的 target 文件；本次 Gateway 已从 `logs/step20-runtime/gateway.jar` 恢复运行，产物指纹记录于同目录 `runtime.json`。需要重新打包正在运行的文件时，先停止对应服务，再 package 并恢复服务；确认四个健康检查均 UP 后开始矩阵。

```powershell
python -m unittest discover -s scripts -p 'test_step*_tools.py' -v
python scripts/verify-step20.py
if ($LASTEXITCODE -ne 0) { throw 'Step20 failed; inspect logs/step20-result.json and batch evidence' }
```

一键入口生成新 UUID，注册 2 位乘客、500 位司机，保存司机资料，生成 500 笔固定 WAITING 订单及 999 条候选通知。账号密码随机且 BCrypt 入库；清单先记账再创建，可恢复异常中断。归属检查用一条批量 SQL 核对全部账号的 ID、手机号、角色和 BCrypt，不减少检查字段。使用 SQL 夹具扩大候选集合，业务 MQ 匹配的最多 5 人规则保持原值。这组测试测量抢单路径，不测量正常匹配或持续消息吞吐。

顺序执行下表，每格 3 轮，合计 18 轮、3,660 个抢单请求：

| 场景 | 并发 | 每轮 HTTP 结果 | 每轮最终数据库 |
| --- | --- | --- | --- |
| 多司机抢同单 `same-order` | 10 / 100 / 500 | 1 个 200，其余为 409 ORDER_ALREADY_TAKEN | 1 个 ACCEPTED、1 个活动占用，均属于 HTTP 获胜司机 |
| 同司机抢多单 `same-driver` | 10 / 100 / 500 | 1 个 200，其余为 409 DRIVER_BUSY | 同司机只有 1 个活动占用，其余订单保持 WAITING 且 driver_id 为空 |

每轮重新核对夹具归属、reset 固定订单、登录并刷新上线 TTL。barrier 集齐所有客户端线程后同时放行，准备时间不计入请求时间。每请求独立连接，无自动重试，超时为 30 秒。TLS 证书上下文在导入时初始化，urllib opener 按线程复用，不在每个 HTTP 请求中重新加载 Windows 证书库。超时、5xx、身份错误均判失败。请求结束先写 JSONL，再查询全部 500 笔订单及本批相关占用，防止只检查 HTTP 而忽略最终数据。

汇总器再读取原始 JSONL，检查每轮请求数量、编号、独立请求 ID、司机与订单基数，重新计算正确性结论；六格、每格三轮缺一不可。报告保存 SHA256 便于核对证据。失败立即退出矩阵并保留已产生的结果，不自动重试掩盖失败。

无论矩阵是否通过，入口均尝试使用 Step19 的归属检查清理本批临时数据，逐位司机离线并检查状态，SQL 删除后复查相关行数为零。清理成功后移除清单中的临时密码。只有完整矩阵和清理同时通过，才返回退出码 0。清理失败保留清单，可在恢复服务后执行：

```powershell
python scripts/step19-data.py cleanup --run-id '<logs/step20-result.json 中的 runId>'
```

## 证据

- `logs/step20-result.json`：最近一批的完整汇总，含各轮请求数、HTTP 分布、延迟、数据库获胜归属、路径和 SHA256。
- `logs/step19/runs/<runId>/step20-result.json`：该批永久独立汇总；再次执行不会覆盖旧批次。
- 同批 `environment.json`、各 `load-*/environment.json`：环境版本、健康、源码/配置指纹、参数。
- 同批 `load-*/round-*.jsonl`：逐请求开始偏移、耗时、响应码、身份及关联 requestId，无 Authorization。
- 同批 `load-*/round-*.json`：每轮全部订单/占用的最终数据库状态与统计。
- 同批 `manifest.json`：清理状态、审计 ID；应为 `status=cleaned, cleanupVerified=true`。

可用 PowerShell 将控制台输出重定向到 `logs/step20-run.log`。日志目录由 Git 忽略，不将临时凭据加入源码库。

`completedRequestsPerSecond` 包含正常 409；它表示本批并发请求完成率，不能解释为成功抢单 QPS 或持续吞吐。p50/p95/p99 为每轮请求延迟的 nearest-rank 分位数，受 Windows 调度、Python 客户端、本机连接与服务热身影响。正式持续创建、MQ 消费与监控在 Step21 执行。
