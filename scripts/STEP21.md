# Step21 五分钟持续创建与 MQ 消费

测试链路为 Gateway → JWT → 附近司机 Redis GEO 预检查 → MySQL 订单/outbox 同事务 → RabbitMQ confirm → 真实司机消费者 → 通知及消费幂等记录。测量阶段不使用 SQL 预造订单或通知。复用 Step19 的隔离账号及精确清理机制。

## 运行

四个服务、MySQL、Redis、RabbitMQ 和 Nacos 先按 README 启动。配置可通过进程环境或本机私有 `.env.local` 提供，显式进程环境优先；脚本不输出密码或 JWT。需要 Python 标准库、PowerShell 7、MySQL CLI、PATH 中可用的 JDK 21，Maven 用于修改后的回归和打包。首次运行前执行 `mvn.cmd -o -DskipTests package`，确保四个模块的 target JAR 均存在。

```powershell
python -m unittest discover -s scripts -p 'test_step*_tools.py' -v

# 复制运行 JAR，重启四服务。保留五秒轮询作为基线，与源码新默认值无关。
pwsh -NoProfile -File scripts/step21-runtime.ps1 -PollIntervalMs 5000
python scripts/verify-step21.py --phase baseline
if ($LASTEXITCODE -ne 0) { throw 'Baseline failed; inspect batch evidence' }

# 基线完成、MQ 收敛、清理后，仅改变 order-service 的轮询间隔。
pwsh -NoProfile -File scripts/step21-runtime.ps1 -Service order-service -PollIntervalMs 1000
python scripts/verify-step21.py --phase tuned
if ($LASTEXITCODE -ne 0) { throw 'Tuned run failed; inspect batch evidence' }

python scripts/audit-step21.py

# 完成后恢复常规 health 路由，保留一秒轮询；打包新代码后可加 -RefreshJar。
pwsh -NoProfile -File scripts/step21-runtime.ps1 -Restore -PollIntervalMs 1000

# 共享 order_db 的集成测试需临时隔离运行中的发布器；结束后自动恢复并跑真实 HTTP。
pwsh -NoProfile -File scripts/verify-step19.ps1 -IsolatePublisher
```

若在自动化终端中将 `pwsh -File ...` 的输出重定向，Windows 子进程句柄可能使外层命令迟迟不返回。服务日志已显示 UP 时，应核实进程后结束卡住的外层启动器，并单独执行下一条命令，避免在同一条外部进程管道后串联压测。当前 PowerShell 会话也可直接调用 `& ./scripts/step21-runtime.ps1 ...`。

`step21-runtime.ps1` 只停止已核实的本项目业务端口监听 Java 进程，从 `logs/step21-runtime/*.jar` 的独立副本启动，记录 JAR SHA256、进程、时间和非敏感配置。所有模块（含网关）的初始副本均从当前 target 复制，无需历史 logs 或 Step20 JAR；已有副本保留，`-Restore -RefreshJar` 可在测量之外刷新。两次测量必须保持相同 JAR、负载、批大小和其他环境。

独立管理端口为 **127.0.0.1:18080–18083**，只暴露 health/info/metrics。业务端口仍为 8080–8083；在此性能运行模式下，健康检查迁移到管理端口，旧业务端口及网关健康转发暂不可用。验收脚本显式使用管理端口；测试结束应使用 `-Restore` 恢复常规健康端点（见脚本参数）。这不是业务接口变更。

## 参数和统计口径

正式两组各 **300 秒、12 次/秒、16 个工作线程**，每组 3,600 个独立 UUID，单乘客、两位同位置司机。两司机每 20 秒心跳。每组先用同速率预热完整链路 10 秒，再等待预热消息全部消费；预热数据不混入正式统计。脚本额外生成一笔 Step19 抢单夹具，仅用于复用隔离数据工具，不计入持续下单成绩。

`--duration` 只有 smoke 可缩短；正式 baseline/tuned 强制为 300 秒。可调整 `--rps` 和 `--workers`，但前后必须一致，且每请求只有一次尝试。请求 timeout 为 30 秒；发送排程滞后超过 1 秒或实际开始超出五分钟窗口，整组失败，避免客户端饱和时误称稳定负载。

- 创建 QPS = 正式成功创建数 / 实际负载窗口秒数。它不代表最大系统容量；按固定 12/s 发送，两组创建 QPS 应接近 12。
- HTTP p50/p95/p99 为逐请求 `perf_counter` 延迟的 nearest-rank 分位数，包含本机 TCP/网关/服务端处理；不包含账号准备或预热。
- 实际消费提交延迟：从消费者 `Matching committed ... notificationDelayMs` 日志提取。计时起点为事件 occurredAt，终点为匹配事务提交、basicAck 调用后的日志时刻；仍不包含司机页面轮询。
- 数据库认领延迟 = MySQL `processed_at - ride_order.created_at`，精度毫秒。processed_at 在消费事务最初 claim 时写入，后面才查询 GEO、写通知和提交；保留该辅助指标，但不将它当作最终提交时间。
- 五分钟结束立即保存一组指标，记录 SQL 实际采集偏移，随后继续采样、心跳并等所有事件收敛，最多 600 秒。收敛耗时与负载窗口分别记录。

## 监控

约每 10 秒采样，初次及五分钟结束另采。记录采集开始/SQL 采集偏移和采集耗时；跨 HTTP、MySQL 的数据不是同一瞬间的原子快照。

| 类别 | 数据 |
| --- | --- |
| Outbox | 本批订单、SENT、NEW、processed 数量、最老 NEW 年龄 |
| RabbitMQ | 主队列 ready/unacked/consumer、DLQ 消息数 |
| JVM/进程 | memory used/max、GC pause COUNT/TOTAL_TIME/MAX、线程数、进程/系统 CPU 使用率、uptime |
| Hikari | active/idle/pending/max、timeout COUNT、acquire COUNT/TOTAL_TIME/MAX |
| MySQL | 全局 Slow_queries、Threads_connected/Threads_running、Max_used_connections |

运行脚本通过 Hikari connection-init-sql 为本项目应用连接设置会话级 **long_query_time=0.1 秒**，不修改 MySQL 全局配置。服务器已开启慢查询日志。`Slow_queries` 是共享服务器计数，可能包含其他应用；当前项目账号没有 performance_schema 查询权限，Windows 文件 ACL 也禁止当前用户读取 MySQL 慢日志，不声称得到逐语句慢查询归因。采样最大值可能遗漏短峰值；`jvm.memory.used` 是 heap 与 nonheap 合计，不是堆专用值。GC 与连接池累计指标均取窗口首末差。

## 判定、证据和恢复

每组逐请求 JSONL、逐单数据库 JSON、指标 JSONL、环境指纹、预热报告保存在 `logs/step19/runs/<runId>/`。`logs/step21-baseline-result.json` / `step21-tuned-result.json` 指向最近两组汇总。原始目录按 UUID 隔离，失败记录保留。

通过条件：3,600 个 201/0，响应中的 requestId/乘客/订单 ID 精确匹配；SQL 恰好一单一事件，全部 WAITING/无司机、SENT/attempts=1、processed_event_id 一致，通知司机集合恰好为本批两位司机。无传输失败、消费者缺失、死信或采样失败。数据清理成功后才将整组标为 passed。

`audit-step21.py` 重新读取原始文件，校验 SHA256、逐笔业务不变量、HTTP/数据库认领分位数、发送窗口、指标采样数、清单清理状态、两组 JAR 和参数。另按已核实的 eventId/orderId 从原始司机日志逐笔提取消费提交延迟，要求每笔恰好一条日志且无 redelivery，保存提取行及原日志 SHA256。输出 `logs/step21-comparison.json`，供检验报告引用。

清理仍按乘客 ID + 预登记 UUID + 批次地点标记定位，等待消息 ACK 后删除本批关联行、司机离线并复查，不清空共享队列或重置其他数据。异常时保留清单，先解决服务/MQ 故障，再执行：

```powershell
python scripts/step19-data.py cleanup --run-id <runId>
```

五分钟测试应在本项目空闲的本机环境运行。不要同时执行其他写库集成测试或另一组压测；本机生成器、服务和数据库共用资源，结果不能外推生产环境。
