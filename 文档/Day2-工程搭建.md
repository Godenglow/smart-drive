# Day 2 工程搭建记录（2026-10-05）

> **结论**：Day 2 交付物已达成 —— Git 仓库建立并推送 GitHub（commit `2b7af24`）；Maven 父工程与 5 个模块编译通过（`BUILD SUCCESS`）；四个服务健康检查接口实测返回 `UP`；README 已记录启动顺序。**可进入 Day 3**。
>
> 检查方式：命令行实测（构建输出、端口监听、HTTP 健康检查）。

## 一、完成内容

| 项 | 内容 |
| --- | --- |
| Git 仓库 | `main` 分支；远端 `https://github.com/Godenglow/smart-drive.git`；提交 `2b7af24` |
| Maven 父工程 | `com.smartdrive:smart-drive 1.0.0-SNAPSHOT`，父 POM = `spring-boot-starter-parent 3.5.0` |
| 锁定 BOM | `dependencyManagement` 导入 Spring Cloud `2025.0.0` + Spring Cloud Alibaba `2025.0.0.0`；Java 21 |
| 模块 | `common-api`（通用 `Result` 包装，不启动）、`gateway`(8080)、`account-service`(8081)、`driver-service`(8082)、`order-service`(8083) |
| 工程卫生 | `.gitignore`（target/、IDE 文件、`application-local.yml` 等）、`.gitattributes`、README（含启动顺序与端口表） |

## 二、验证结果

- `mvn.cmd clean compile`：**BUILD SUCCESS**，reactor 6 模块全部 SUCCESS，用时 3:38（首次含依赖下载）。
- `mvn.cmd -DskipTests package`：**BUILD SUCCESS**，四个服务均产出可执行 jar。
- 四个服务 `java -jar` 启动后，`/actuator/health` 全部返回 `{"status":"UP"}`（8080 / 8081 / 8082 / 8083 实测）。
- 停止四个服务后复查 `netstat`，8080–8083 全部释放干净。

## 三、当日发现并处理的问题

### Nacos 3.x 控制台占用 8080，与规格中 Gateway 端口冲突 — 已解决

- **冲突点**：规格文档约定 Gateway 用 8080（Vue `/api` 也代理到 8080）；而 Day 1 安装的 Nacos 3.0.3 控制台默认占 8080。Day 3 起两者必然同时运行。
- **处理**：`D:\devtools\nacos\nacos\conf\application.properties` 中 `nacos.console.port` 由 8080 改为 **8090**，重启 Nacos。
- **验证**：控制台 `http://localhost:8090` 返回 HTTP 200；8848 / 9848 正常监听；8080 已空出（连接被拒绝）。
- **连带修订**：Day 1 检查表中"控制台 8080"及开工自查命令里的 `:8080` 自此作废，统一改为 `:8090`（README 已同步更新，Day 1 文档已加修订注记）。

### 重启 Nacos 时把 derby.log / logs\ / work\ 吐进了项目根目录 — 已清理

- **现象**：第一次重启 Nacos 直接在项目根目录执行了 `startup.cmd`，Nacos 的相对路径产物（`derby.log`、`logs\`、`work\`）落进了 `SmartDrive\`。
- **处理**：停掉 Nacos 后删除三个产物，改在 `D:\devtools\nacos\nacos\bin` 目录下重新启动。
- **验证**：8848 / 9848 / 8090 正常监听，控制台 HTTP 200，项目根目录保持干净。**今后启动 Nacos 一律先 `cd /d D:\devtools\nacos\nacos\bin`。**（这些产物名已被 `.gitignore` 的 `*.log`、`logs/` 覆盖，`work\` 是空目录，均未进过提交。）

## 四、每日进度记录（按开发计划模板）

```text
日期 / Day：2026-10-05 / Day 2
完成的交付物（附提交或截图）：Git 仓库 + Maven 父工程 + 5 模块骨架 + BOM 锁定 + README；
    提交 2b7af24 已推送 GitHub
验证方式与结果：mvn.cmd clean compile BUILD SUCCESS；四服务 /actuator/health 实测 UP；
    Nacos 控制台 8090 HTTP 200、8080 已让给 Gateway
阻塞项及处理人：无阻塞
下一工作日的第一件事：Day 3 —— 三个业务服务注册 Nacos；Gateway 按服务名转发；
    每个请求生成/透传 requestId
```
