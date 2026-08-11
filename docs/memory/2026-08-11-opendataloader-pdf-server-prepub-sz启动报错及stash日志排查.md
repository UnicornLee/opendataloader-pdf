# opendataloader-pdf 任务记忆 — 2026-08-11（opendataloader-pdf-server prepub-sz 启动报错及 stash 日志排查）

## 目标（Goal）
- 修复 `opendataloader-pdf-server` 以 `prepub-sz` profile 启动时直接退出：
  ```
  java.lang.ClassNotFoundException: net.logstash.logback.appender.LogstashTcpSocketAppender
  Logging system failed to initialize using configuration from 'null'
  ```
- 排查应用启动后，Logstash（stash）端看不到日志的问题。
- 让 `prepub-sz` 环境的日志通过 `LogstashTcpSocketAppender` 正常发送到 `node01.public.logstash.test:9999,node02.public.logstash.test:9999`。

## 根因（Root Cause）
1. **启动直接退出**：`src/main/resources/logback-spring.xml` 在 `prepub-sz,prepub,prod` profile 下引用了 `net.logstash.logback.appender.LogstashTcpSocketAppender`，但 `pom.xml` 中对应的 `logstash-logback-encoder` 依赖被注释掉了，导致 fat jar 打包后缺少该类，Logback 初始化失败，Spring Boot 在 `LoggingApplicationListener` 阶段就抛出 `IllegalStateException` 并退出。
2. **stash 看不到日志**：用户之前实际启动时激活的是 `dev` profile（日志中显示 `The following 1 profile is active: "dev"`），`dev` profile 在 `logback-spring.xml` 中对应的是 `file` appender，根本不会启用 `stash` appender，因此日志只写到本地文件，不会发往 Logstash。

## 修复策略
- **不改动 logback-spring.xml**：现有配置在 `prepub-sz` 下是正确的，只需把缺失的运行时依赖补回来。
- **取消 `logstash-logback-encoder` 依赖注释**：使用原配置中已有的 `7.4` 版本，保持与项目历史一致。
- **重新打包 fat jar**：通过 `mvn clean package -DskipTests` 生成包含 `BOOT-INF/lib/logstash-logback-encoder-7.4.jar` 的新 jar。
- **以正确 profile 启动**：使用 `--spring.profiles.active=prepub-sz` 显式激活 profile，确保加载 `application-prepub-sz.yml` 中的 `log.stash.addresses` 等配置。
- **stash 端问题留给服务端排查**：应用侧验证 `LogstashTcpSocketAppender` 已连接、网络端口通；如果 stash 上仍看不到日志，需检查 Logstash 的 input/output 配置、ES 索引、Kibana 查询条件等。

## 实现（Implementation）
- `java/opendataloader-pdf-server/pom.xml`：
  - 取消以下依赖的注释：
    ```xml
    <dependency>
        <groupId>net.logstash.logback</groupId>
        <artifactId>logstash-logback-encoder</artifactId>
        <version>7.4</version>
    </dependency>
    ```
- `java/opendataloader-pdf-server/src/main/resources/application-prepub-sz.yml`：
  - 本次未改动，确认其中已配置：
    ```yaml
    log:
      level: DEBUG
      appender: stash
      stash:
        addresses: node01.public.logstash.test:9999,node02.public.logstash.test:9999
    ```
- 重新打包：
  ```bash
  cd D:\Code\JavaCode\opendataloader-pdf\java\opendataloader-pdf-server
  mvn clean package -DskipTests
  ```

## 关键决策（Key Decisions）
- **使用已有的 `logstash-logback-encoder 7.4` 版本**：原 pom 中该依赖被注释，版本号已写好，直接取消注释即可，避免引入版本兼容风险。
- **保留 `apm-toolkit-logback-1.x` 依赖**：logback-spring.xml 同时使用了 SkyWalking 的 `TraceIdMDCPatternLogbackLayout` 和 `TraceIdJsonProvider`，该依赖已在 pom 中声明，无需改动。
- **后台启动采用 `start /min cmd /c ...`**：在 Windows PowerShell 环境下，`nohup` 和 `&` 后台语法不可用；使用 `start /min cmd /c "java -jar ... > output.log 2>&1"` 可让应用在独立窗口后台运行并把日志重定向到文件。
- **不修改 logback-spring.xml 的 profile 结构**：`prepub-sz` 与 `prepub`、`prod` 共用同一套 stash appender 配置，符合项目原有设计。
- **不深入改动 Logstash 服务端**：应用侧已确认发送成功，但用户反馈即使以 `prepub-sz` 启动，Kibana 上仍看不到日志；因此 stash 看不到日志属于服务端配置/查询问题，超出当前代码修复范围，仅提供排查清单。

## 验证结果
- `mvn clean package -DskipTests`：✅ **BUILD SUCCESS**，新 jar 已包含 `BOOT-INF/lib/logstash-logback-encoder-7.4.jar`。
- 以 `prepub-sz` 启动后日志显示：
  ```text
  value "stash" substituted for "${log.appender}"
  value "node01.public.logstash.test:9999,node02.public.logstash.test:9999" substituted for "${log.stash.addresses}"
  Attaching appender named [stash] to Logger[ROOT]
  The following 1 profile is active: "prepub-sz"
  Log destination node01.public.logstash.test/<unresolved>:9999: connection established.
  Started ServerApplication in 6.292 seconds
  ```
- 网络连通性验证：
  ```powershell
  Test-NetConnection -ComputerName node01.public.logstash.test -Port 9999  # TcpTestSucceeded: True
  Test-NetConnection -ComputerName node02.public.logstash.test -Port 9999  # TcpTestSucceeded: True
  ```
- 应用进程持续运行（PID 15236，内存约 268MB），未再出现 `ClassNotFoundException`。
- 用户反馈：即使显式以 `--spring.profiles.active=prepub-sz` 启动，Kibana 上仍未看到日志；应用侧发送通道已建立，问题需进一步在 Logstash → Elasticsearch → Kibana 链路排查。

## 构建 / 运行
- 打包（项目 server 目录）：
  ```bash
  cd D:\Code\JavaCode\opendataloader-pdf\java\opendataloader-pdf-server
  mvn clean package -DskipTests
  ```
- 前台启动（看实时日志）：
  ```bash
  java -jar target/opendataloader-pdf-server-0.0.0.jar --spring.profiles.active=prepub-sz
  ```
- 后台启动并把日志写入 `opendataloader_pdf_server_log/output.log`：
  ```cmd
  cd /d D:\Code\JavaCode\opendataloader-pdf\java\opendataloader-pdf-server
  start /min cmd /c "java -jar target/opendataloader-pdf-server-0.0.0.jar --spring.profiles.active=prepub-sz > opendataloader_pdf_server_log/output.log 2>&1"
  ```
- 查看日志：
  ```powershell
  Get-Content opendataloader_pdf_server_log/output.log -Tail 50
  ```
- 若 Kibana 仍看不到日志，继续排查：
  1. 登录 Logstash 服务器抓包：`tcpdump -i any port 9999`
  2. 查看 Logstash 自身日志：`/var/log/logstash/logstash-plain.log`
  3. 确认 Logstash input codec 与发送端 JSON 格式匹配（常用 `codec => json_lines`）
  4. 检查 Elasticsearch 索引名称、索引模式（Index Pattern）及 Kibana 时间范围
  5. 检查 output 中是否有 `if` 条件把 `type:"jetty_log"` / `program:"opendataloader-pdf-server"` 过滤掉

## 相关文件（Relevant Files）
- `java/opendataloader-pdf-server/pom.xml`：取消 `logstash-logback-encoder` 依赖注释。
- `java/opendataloader-pdf-server/src/main/resources/logback-spring.xml`：本次未改，但它是启用 `LogstashTcpSocketAppender` 和 `LogstashEncoder` 的关键配置。
- `java/opendataloader-pdf-server/src/main/resources/application-prepub-sz.yml`：配置 `log.level`、`log.appender`、`log.stash.addresses`。
- `java/opendataloader-pdf-server/target/opendataloader-pdf-server-0.0.0.jar`：重新打包后的可运行 fat jar。
- `java/opendataloader-pdf-server/opendataloader_pdf_server_log/output.log`：启动日志输出文件。
- Logstash / Elasticsearch / Kibana 服务端配置：待用户补充，用于定位 Kibana 无日志的根本原因。
