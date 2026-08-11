# opendataloader-pdf 任务记忆 — 2026-08-11（logback-spring.xml 移除 SkyWalking 引用 + pom 依赖清理 + Kibana 无日志根因分析）

- 时间：2026-08-11
- 任务：
  1. 用户已修改 `logback-spring.xml`（commit `c211b78` 后），所有 `org.apache.skywalking.*` 引用已移除。
  2. 询问 `opendataloader-pdf-server/pom.xml` 中的 `org.apache.skywalking:apm-toolkit-logback-1.x:8.2.0` 依赖是否可以删除 → **可以，已删除**。
  3. 排查 Kibana 仍看不到日志的问题 → **与 SkyWalking 无关，与 Logstash/ES 也无关；真正根因是 Kibana 端数据视图选错**（应选 `logs-jetty-default` 而不是 `logs-tomcat-default`）。
- 状态：pom 已清理；客户端无需再改；服务端无需排查；Kibana 切数据视图即可恢复。

## 目标（Goal）
- 让 `opendataloader-pdf-server` fat jar 不再依赖 `apm-toolkit-logback-1.x`，避免无用的运行时类。
- 回答用户疑问："Kibana 仍看不到日志，是不是原来 logback-spring.xml 用了 SkyWalking 导致的？"

## 根因分析（Root Cause）

### 1) 删除依赖的判断依据
- `grep -rE 'skywalking|SkyWalking|apm-toolkit|apm-trace'` 全仓库只命中两处：
  - `pom.xml`（依赖与解释用注释）
  - `docs/memory/2026-08-11-...-stash日志排查.md`（历史记忆）
- 没有任何 Java 源码或 `logback-spring.xml` 仍然引用 SkyWalking 类。
- → 依赖可安全移除。

### 2) Kibana 无日志 ≠ SkyWalking 导致
原 logback-spring.xml 中 SkyWalking 只做了三件事，全部是**日志内容增强**：

| 引用 | 作用位置 | 实际效果 |
|---|---|---|
| `TraceIdMDCPatternLogbackLayout` | console / file appender 的 `<layout>` | 在文本里追加 `%X{tid}` trace ID，**只影响文本格式** |
| `TraceIdJsonProvider` | stash appender 的 `LogstashEncoder` 子节点 | 在 JSON 中加 `traceId` / `spanId` 字段 |
| `<includeMdcKeyName>TID</includeMdcKeyName>` | `LogstashEncoder` | 把 MDC 中 `TID` 键写进 JSON |

**SkyWalking 不会做的事**：
- 不会阻断 TCP 连接
- 不会过滤 / 丢弃日志
- 不会改变 `LogstashTcpSocketAppender` 发送行为
- 不会影响 `LogstashEncoder` 是否输出合法 JSON
- 不会影响 Logstash input 是否能 parse、ES 是否能索引、Kibana 是否能查

### 3) 移除 SkyWalking 前后，**送出去的 JSON 实际差异**

有 SkyWalking（原始）：
```json
{"@timestamp":"...","message":"...","logger_name":"...","level":"DEBUG",
 "thread_name":"...","type":"jetty_log","program":"opendataloader-pdf-server",
 "traceId":"abc123...","spanId":"def456...","TID":"abc123..."}
```

无 SkyWalking（当前）：
```json
{"@timestamp":"...","message":"...","logger_name":"...","level":"DEBUG",
 "thread_name":"...","type":"jetty_log","program":"opendataloader-pdf-server"}
```

两者都是合法 JSON Lines，Logstash input 都能 parse。差别只是 trace 字段缺失。

### 4) 已确认的应用侧链路（来自历史记忆）
```
LogstashTcpSocketAppender → TCP 连接 node01.public.logstash.test:9999 → connection established ✓
Started ServerApplication in 6.292 seconds ✓
```
应用侧**确实把日志推到 Logstash**，所以"日志送没送到 Logstash"这层没问题。

### 5) Kibana 无日志的最终定位（**真正根因**）
- **根因不在服务端任何配置**：Logstash → ES → Kibana 链路正常，应用发出的 JSON（带 `type:"jetty_log"`、`program:"opendataloader-pdf-server"` 等字段）已被正常索引到 `logs-jetty-default` 索引。
- **真正根因在 Kibana UI 的数据视图（Data View）选择错误**：
  - 用户之前选的是 `logs-tomcat-default`；
  - 而本服务通过 `LogstashTcpSocketAppender` 发出的日志 JSON 带 `type:"jetty_log"`，落到的是 `logs-jetty-default` 索引；
  - `logs-tomcat-default` 与 `logs-jetty-default` 是两个完全不同的 Elasticsearch 索引模式，查询前者永远查不到本服务的日志。
- **修复**：在 Kibana 顶栏把数据视图从 `logs-tomcat-default` 切到 `logs-jetty-default`，即可立即看到 `opendataloader-pdf-server` 的日志。
- 与 SkyWalking 是否启用、Logstash pipeline 配置、ES 索引模板、Kibana 时间窗口都**无关**。

## 实现（Implementation）

`java/opendataloader-pdf-server/pom.xml`：
- 删除以下依赖块及上方解释用注释：
  ```xml
  <!--
    logback-spring.xml references SkyWalking and Logstash appenders /
    layouts that must be on the runtime classpath, otherwise logback
    aborts startup. apm-toolkit-logback-1.x 8.2.0 ships the
    org.apache.skywalking...toolkit.log.logback.v1.x.* package
    referenced by the existing configuration.
  -->
  <dependency>
      <groupId>org.apache.skywalking</groupId>
      <artifactId>apm-toolkit-logback-1.x</artifactId>
      <version>8.2.0</version>
  </dependency>
  ```
- 保留 `net.logstash.logback:logstash-logback-encoder:7.4`（stash appender 仍需此依赖）。

`java/opendataloader-pdf-server/src/main/resources/logback-spring.xml`：
- 用户已自行修改，本次未再改动。
- 现状：所有 SkyWalking 类引用、`includeMdcKeyName>TID</includeMdcKeyName>` 均已移除；stash appender 仍用 `LogstashTcpSocketAppender` + `LogstashEncoder`。

## 关键决策（Key Decisions）
- **删除 SkyWalking 依赖**：当前配置不再需要，保留只会增加 fat jar 体积且误导后续维护者。
- **不修复 Kibana 无日志问题**：该问题不在 Java 端，客户端无须任何改动；服务端也无需改动；只要在 Kibana UI 上把数据视图从 `logs-tomcat-default` 切到 `logs-jetty-default` 即可。
- **保留 logstash-logback-encoder 依赖**：stash appender 仍依赖其提供的 `LogstashTcpSocketAppender` / `LogstashEncoder` 类。
- **删除解释用注释**：原注释解释 SkyWalking 必要性，依赖删除后注释失去上下文，一并清理。

## Kibana 端修复（不是服务端排查）

只需一步：
1. 打开 Kibana，顶部"Data View"下拉框中，把当前的 `logs-tomcat-default` 切换为 **`logs-jetty-default`**。
2. 切完之后搜索条件无需改（默认 `*`），时间窗口保持"最近 15 分钟"或更大范围，即可看到 `opendataloader-pdf-server` 的日志条目（每条都有 `type:"jetty_log"` 和 `program:"opendataloader-pdf-server"`）。

无需联系 Logstash/ES 运维，无需改动任何服务端配置，无需回滚 SkyWalking。

## 验证结果
- `mvn -q -o dependency:tree -Dincludes=org.apache.skywalking`：无输出 → SkyWalking 已从依赖树移除 ✓
- `mvn -q -o compile`：编译通过 ✓
- 真实环境验证（用户反馈）：Kibana 数据视图从 `logs-tomcat-default` 切到 `logs-jetty-default` 后即可看到日志 ✓，无需改动服务端或客户端 Java 代码。

## 相关文件
- `java/opendataloader-pdf-server/pom.xml`：删除 `org.apache.skywalking:apm-toolkit-logback-1.x` 依赖及解释注释。
- `java/opendataloader-pdf-server/src/main/resources/logback-spring.xml`：用户已自行移除 SkyWalking 类引用，本次未改动。
- `docs/memory/2026-08-11-opendataloader-pdf-server-prepub-sz启动报错及stash日志排查.md`：历史记忆，记录了 SkyWalking 时代日志链路现状。

## 构建 / 运行
- 编译：
  ```bash
  cd D:\Code\JavaCode\opendataloader-pdf\java\opendataloader-pdf-server
  mvn -q -o compile
  ```
- 重新打包：
  ```bash
  cd D:\Code\JavaCode\opendataloader-pdf\java\opendataloader-pdf-server
  mvn clean package -DskipTests
  ```
- Kibana 端确认日志（无需服务端抓包）：
  1. Kibana 顶栏 Data View 选 `logs-jetty-default`。
  2. 时间窗口设"Last 15 minutes"。
  3. 搜索 `program:"opendataloader-pdf-server"` 即可看到日志。