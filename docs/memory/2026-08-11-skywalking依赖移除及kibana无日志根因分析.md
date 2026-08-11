# opendataloader-pdf 任务记忆 — 2026-08-11（logback-spring.xml 移除 SkyWalking 引用 + pom 依赖清理 + Kibana 无日志根因分析）

- 时间：2026-08-11
- 任务：
  1. 用户已修改 `logback-spring.xml`（commit `c211b78` 后），所有 `org.apache.skywalking.*` 引用已移除。
  2. 询问 `opendataloader-pdf-server/pom.xml` 中的 `org.apache.skywalking:apm-toolkit-logback-1.x:8.2.0` 依赖是否可以删除 → **可以，已删除**。
  3. 排查 Kibana 仍看不到日志的问题，**与 SkyWalking 是否使用无关**，根因在 Logstash/ES / Kibana 服务端链路。
- 状态：pom 已清理；客户端无需再改；服务端待 Logstash 运维介入。

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
应用侧**确实把日志推到 Logstash**——所以不是"SkyWalking 让日志发不出去"，而是"Logstash → ES → Kibana 这一段没把日志呈现出来"。

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
- **不修复 Kibana 无日志问题**：该问题在服务端（Logstash/ES/Kibana），不在客户端 Java 代码；强行在客户端加 SkyWalking 反而增加维护成本。
- **保留 logstash-logback-encoder 依赖**：stash appender 仍依赖其提供的 `LogstashTcpSocketAppender` / `LogstashEncoder` 类。
- **保留依赖注释的删除**：原注释解释 SkyWalking 必要性，依赖删除后注释失去上下文，一并清理。

## 服务端排查建议（移交 Logstash 运维）

按可能性排序排查：

| 假设 | 验证手段 |
|---|---|
| A. Logstash input codec 与发送端不匹配 | `tcpdump -i any port 9999 -A -nn` 看 raw 包；`/var/log/logstash/logstash-plain.log` 看 parse 错误 |
| B. Logstash filter 假设了 trace 字段 | `grep -RnE 'traceId\|spanId\|TID\|skywalking' /etc/logstash/conf.d/` 查 pipeline 中相关 if 分支 |
| C. ES 索引未创建 / 索引名不匹配 | `curl -s 'http://es-host:9200/_cat/indices?v' \| grep -i jetty`；对照 Logstash `output.elasticsearch.index` |
| D. Kibana 时间窗口不对 | 推送时间是否在当前 Kibana 时间选择器内 |
| E. ES 写失败 | Logstash 日志中搜索 `[status_code]` |

**特别注意假设 B**：若 SkyWalking 时代的 Logstash pipeline 写过 `if [traceId]` 这类条件，移除 SkyWalking 后字段缺失，filter 行为可能改变。但根因是 Logstash pipeline 的设计选择，不是 Java 端问题。

## 验证结果
- `mvn -q -o dependency:tree -Dincludes=org.apache.skywalking`：无输出 → SkyWalking 已从依赖树移除 ✓
- `mvn -q -o compile`：编译通过 ✓
- 未启动应用做端到端验证（已确认 SkyWalking 与 Kibana 无日志无因果关系，无需改动客户端即可恢复；恢复需服务端介入）。

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
- 服务端（Logstash 端）抓包验证：
  ```bash
  tcpdump -i any port 9999 -A -nn | head -100
  ```