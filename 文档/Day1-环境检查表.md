# Day 1 环境检查表（2026-10-05）

> **结论**：Day 1 交付物已达成 —— JDK / Maven / MySQL 检查通过；RabbitMQ 修复并启动；CentOS 虚拟机开机、Redis 恢复可达；Nacos 由 2.3.2 升级为官方 3.0.3 并运行。四组件连通性全部实测通过，**可进入 Day 2**。
>
> 检查方式：命令行实测（版本号、服务状态、端口监听、带认证的连通性测试）。
> 口径：**密码不记录在本文件**，统一见本机私有配置《D:\Projects\我的配置.md》。

## 一、检查结果总览

| 组件 | 计划要求 | 最终状态 | 端口 | 连通性验证 | 结论 |
| --- | --- | --- | --- | --- | --- |
| JDK | 21 | **21.0.2**（Oracle，`D:\java`） | — | `java -version` | ✅ 通过 |
| Maven | 3.8.6 | **3.8.6**（运行在 JDK 21 上） | — | `mvn -version` | ✅ 通过 |
| MySQL | 8.3 | **8.3.0**，服务 `MySQL83` Running | 3306 | `SELECT VERSION()` → 8.3.0 | ✅ 通过 |
| RabbitMQ | 4.3.5 | **4.3.5**，服务 Running（当日修复后启动） | 5672 / 15672 / 25672 | 带认证 `GET /api/health/checks/alarms` → `{"status":"ok"}` | ✅ 通过 |
| Redis（VM） | 7.2.5 @ 192.168.220.128 | **7.2.5**，CentOS VM 当日开机 | 6379 | `AUTH` → `+OK`，`PING` → `+PONG` | ✅ 通过 |
| Nacos | 文档基线 3.0.3 | **官方 3.0.3**（当日由源码构建版 2.3.2 替换，见第三节） | 8848 / 9848 / 8090 | 服务 API 与控制台 HTTP 200，gRPC 监听 | ✅ 通过 |

## 二、每日开工启动命令（Day 2 起每天开工先核对）

| 组件 | 自启动 | 每日操作 |
| --- | --- | --- |
| MySQL83 | ✅ 开机自启 | 无需操作 |
| RabbitMQ | ✅ 开机自启 | 无需操作；若未起：**管理员**运行 `net start RabbitMQ` |
| CentOS VM | ❌ 不自启 | `"D:\VMware\vmrun.exe" -T ws start "D:\VMware_LINUX_CentOS\CentOS 7\My_CentOS 7.vmx" nogui` |
| Nacos | ❌ 无服务 | `D:\devtools\nacos\nacos\bin\startup.cmd -m standalone`（约 20 秒就绪） |

> 开工自查一行命令：`netstat -ano | findstr "LISTENING" | findstr ":3306 :5672 :6379 :8848 :9848 :8090"`，六个端口齐了即可开工。

## 三、当日发现并处理的问题

### 1. RabbitMQ 服务无法启动（服务退出码 1067）— 已修复

- **现象**：`RabbitMQ` 服务 STOPPED，提权启动后立即退出；手动运行 `rabbitmq-server.bat` 报 `corrupt atom table`，BEAM 引导阶段核心模块加载失败。
- **根因**：2026-09-25 16:46 安装 Erlang/OTP 29（erts-17.0.3）时**装进了已有的 OTP 27 目录** `D:\Apps\ErlangOTP`，两套版本的 `lib` 库并存，且 `bin\start.boot` 等 4 个启动脚本被 OTP 29 版本覆盖。OTP 27 运行时（RabbitMQ 依赖）引导时加载到 OTP 29 的库文件即崩溃。
- **修复动作**（可回滚，未删除任何文件）：
  1. 把全部 2026-09-25 混入的 OTP 29 目录（lib 下 36 个 + 根目录 `erts-17.0.3` + `releases\29`）**移入隔离目录** `D:\Apps\ErlangOTP_otp29_quarantine\`；
  2. 用 `releases\27\` 里的 `start.boot` / `start_clean.boot` / `start_sasl.boot` / `no_dot_erlang.boot` 覆盖恢复 `bin\` 下被覆盖的同名文件；
  3. `net start RabbitMQ` 启动成功，5672/15672/25672 全部监听，管理 API 健康检查通过。
- **遗留注意**：`D:\Apps\ErlangOTP\bin\erl.exe` 目前是 OTP 29 的包装器（`erl.ini` 指向 `erts-15.2.7.13`，工作正常）。**今后若需要 OTP 29，必须安装到独立目录**，不要与 OTP 27 混装；确认不需要 OTP 29 后可整目录删除隔离文件夹。

### 2. CentOS 虚拟机未开机导致 Redis 不可达 — 已恢复

- 用 vmrun 以无界面模式启动虚拟机（命令见第二节），`192.168.220.128:6379` 恢复可达，认证与 PING 通过。
- VM 内 Redis 库中仍有秒杀项目的约 78 个 key；本项目按计划只使用 `smartdrive:` 前缀的 Key，**不清库**。

### 3. Nacos 服务端由 2.3.2 升级为官方 3.0.3 — 已完成

- **原状态**：`D:\devtools\nacos\nacos` 为源码构建的 2.3.2（`target\nacos-server.jar` MANIFEST 实测），与 Spring Cloud Alibaba 2025.0.0.0 管理的 nacos-client 3.x 不兼容（新客户端连老服务端不受官方支持，通常注册失败）。
- **替换过程**：
  1. 备份旧版：`D:\devtools\nacos\nacos` 重命名为 `nacos-2.3.2-bak`（未删除，改回目录名即可回滚）；
  2. 下载官方发行包 `nacos-server-3.0.3.zip`（195,994,435 字节，与 GitHub Release 一致；阿里云 Maven 镜像与 Maven Central 无此发行包，经 gh-proxy 加速下载），解压到 `D:\devtools\nacos\nacos`；
  3. 首次启动报 `errCode 50002: Empty identity` —— **Nacos 3.x 即使关闭 auth 也必须设置 `nacos.core.auth.server.identity.key/value`**；在 `conf\application.properties` 补上这两项与 token secret（本地开发占位值），`nacos.core.auth.enabled` 保持 false，启动成功。
- **验证**：日志 `Starting NacosBootstrap v3.0.3 using Java 21.0.2` + `Nacos Server API started successfully in 4255 ms`；8848（服务端）、9848（gRPC）、控制台全部监听，HTTP 200。
- **3.x 架构变化（Day 3 要用到）**：控制台从 8848 挪到**独立端口**（首次打开需初始化管理员密码）；v1 API 已废弃（访问返回 410），服务注册走 9848 gRPC；auth 关闭，业务服务注册**无需配用户名密码**。
- **当日后续调整（11:22，另一会话操作）**：控制台端口由默认 8080 改为 **8090**（`conf\application.properties` 的 `nacos.console.port`），旧实例被替换重启，8080 现已空闲。副作用：从项目目录启动导致项目根出现 `derby.log` 与 `logs\`（Nacos 相对路径产物，可删；启动时建议先 `cd /d D:\devtools\nacos\nacos\bin`）。

## 四、连接方式速查（密码见私有配置）

| 组件 | 连接方式 |
| --- | --- |
| MySQL | 宿主机 `localhost:3306`，服务名 `MySQL83`（项目专用库 Day 4 才建，当前无 smartdrive 库） |
| RabbitMQ | AMQP `localhost:5672`；管理台 `http://localhost:15672`（guest/guest 仅限本机） |
| Redis | `192.168.220.128:6379`，requirepass 认证；Key 一律 `smartdrive:` 前缀 |
| Nacos | 服务端 `localhost:8848`（服务注册走 9848 gRPC，auth 关闭无需凭证）；控制台 `http://localhost:8090` |

## 五、本机变更记录（供《我的配置.md》下次更新参考）

| 位置 | 变更 |
| --- | --- |
| `D:\Apps\ErlangOTP` | 混装的 OTP 29 文件先移入隔离目录、复核后已删除；`bin\` 下 4 个 boot 文件恢复为 OTP 27 版；现仅保留 OTP 27 |
| `D:\devtools\nacos\` | 旧目录（2.3.2 源码构建）已删除；现仅存官方 3.0.3（`conf\application.properties` 含两处本地开发占位配置）；安装包与旧版备份已于当日确认稳定后删除 |
| `D:\Projects\java-projects\SmartDrive\` | 新增本文档 `Day1-环境检查表.md` |

## 六、遗留事项（当日 11:00 已全部处理）

- [x] 崩溃转储 `C:\Windows\System32\erl_crash.dump`：执行删除时已不存在（疑似被系统清理），复检确认无残留
- [x] 已删除安装包 `D:\devtools\nacos\nacos-server-3.0.3.zip` 与旧版备份 `D:\devtools\nacos\nacos-2.3.2-bak`（删除前复核 Nacos 3.0.3 三端口正常，删除后仍运行正常）
- [x] 已删除 `D:\Apps\ErlangOTP_otp29_quarantine`（删除前确认无进程使用 OTP 29；RabbitMQ 运行于 OTP 27，不受影响）

## 七、每日进度记录（按开发计划模板）

```text
日期 / Day：2026-10-05 / Day 1
完成的交付物（附提交或截图）：本文档（环境检查表）；RabbitMQ 修复并运行；
    CentOS VM 启动、Redis 6379 可达；Nacos 官方 3.0.3 替换 2.3.2 并运行
验证方式与结果：版本号 / 服务状态 / 端口监听 / 带认证连通性全部实测通过（见第一节总览）
阻塞项及处理人：无阻塞；小遗留见第六节
下一工作日的第一件事：Day 2 建 Git 仓库与 Maven 父工程
    （gateway / account-service / driver-service / order-service / common-api），
    锁定 Spring Cloud Alibaba 2025.0.0.0 兼容 BOM
```
