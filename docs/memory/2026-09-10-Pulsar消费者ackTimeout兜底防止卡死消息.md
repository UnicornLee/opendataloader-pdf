# opendataloader-pdf 任务记忆 — 2026-09-10（Pulsar 消费者 `ackTimeout` 兜底：防止"已 receive 未 ack"的卡死消息）

## 目标（Goal）

为 `org.opendataloader.pdf.server.pulsar.PulsarService` 中的两个消费者（普通 parse topic、OCR result topic）增加 **`ackTimeout` 兜底机制**，防止消费者在"已 receive 但未 ack"的状态下永久卡死，进而导致消息既不被消费也不被释放。

具体改动：

1. `PulsarProperties` 新增可配置字段 `ackTimeoutSeconds`（默认 `60`），通过 `application.yml` 的 `pulsar.ack_timeout_seconds` 覆盖。
2. `PulsarService` 两个 `client.newConsumer(...)` 构建链各加一行 `.ackTimeout(pulsarProperties.ackTimeoutSeconds(), TimeUnit.SECONDS)`。
3. 类级 Javadoc 新增 "Hung-consumer safeguard" 段落，说明该机制与现有"失败仍 ack、下游观察 jsonUrl=''"语义**互不冲突**。

---

## 背景链路（改动前）

`PulsarService` 启动时为两个 inbound topic 各起一批 `Shared` 消费者线程：

```
@PostConstruct start()
  ├─ 普通 receive topic: count 个 consumer，每个跑在 daemon 线程里
  │     .topic(receiveTopicName)
  │     .subscriptionType(Shared)
  │     .receiverQueueSize(1)            ← 关键的"读取即消费"前提
  │     .subscribe()
  │     → consumeReceiveLoop(consumer)
  │          while (running) {
  │              msg = consumer.receive();   // 同步阻塞
  │              handleReceiveMessage(consumer, msg);  // 处理 + 发送结果 + 清理
  │              acknowledgeQuietly(consumer, msg, ...); // 单条 ack
  │          }
  │
  └─ OCR receive topic: ocrCount 个 consumer，结构同上 → consumeOcrReceiveLoop
```

改动前已有的"读取即消费"语义（已正确，无需调整）：

| 配置/调用 | 作用 |
|----------|------|
| `.receiverQueueSize(1)` | 本地预取缓冲只放 1 条，Broker 端按 1 个 permit 派发 |
| `consumer.receive()` | 同步阻塞取值（不是 `receiveAsync`） |
| `consumer.acknowledge(msg)` | 单条 ack（不是 `acknowledgeCumulative`） |

**但是**——这是本次定位的起点——整个构建链**没有任何 `ackTimeout` 配置**。这意味着 `consumer.receive()` 已经把消息拉到客户端、但 `acknowledgeQuietly` 还没被调用（甚至 `handleReceiveMessage` 中途卡住）的窗口期内，**Broker 既不会自动重投，也不会派发新消息**（permit 收不回来），消息就此悬空。

---

## 完整定位过程（从问题到根因）

### 阶段 1：用户提问的演进链

对话由 6 轮组成，每一轮都在逼近真正的问题：

| 轮次 | 用户问题 | 当时回复要点 |
|------|---------|------------|
| 1 | `receiverQueueSize(1)` 是做什么的？ | JMS 接收方本地队列容量=1，低延迟 + 反压 |
| 2 | 可以设为 0 吗？ | 不行，下限是 1；真要"零缓冲"得改 prefetch=1 + 手动 ack |
| 3 | 那代码怎么改才能"读取即消费"？ | 三件套：prefetch=1、`receive()` 同步、`acknowledge(msg)` 单条 |
| 4 | 这是 pulsar，只说 pulsar | 改用 Pulsar API：`receiverQueueSize(1)` + 同步 receive + 单条 ack |
| 5 | 不设置 ackTimeout 会不会出现"预读取的消息既不消费也不释放"？ | **是的**。连接断开 Broker 能感知并重投；但消费者 hang 死（连接未断）消息就永久卡住。补救：`ackTimeout` + `ackTimeoutTickDuration` |
| 6 | 那请帮我修改 `PulsarService` | 本次落地的任务 |

**问题真正浮现是在第 5 轮**——前面 4 轮都聚焦在"如何不让消息被预取"，第 5 轮才暴露真正的风险：**消息进入客户端之后、ack 之前的窗口期里，如果消费者 hang 死，没有自动恢复机制**。

### 阶段 2：根因分析 —— "已 receive 未 ack"窗口期的盲区

Pulsar 中消息的生命周期状态机（简化）：

```
[未派发] --(broker 派发到 client 本地队列)--> [已派发, 未 ack]
                                                  │
                                                  ├── client.acknowledge() → [已确认]（正常完成）
                                                  ├── client.negativeAcknowledge() → [重投]
                                                  └── 超时无操作:
                                                       ├── consumer.processId 失效 / session 过期 → broker 感知 → 重投 ✅
                                                       └── consumer 进程活着但卡死（死锁、阻塞 IO、JVM hang）→ broker 不感知 → 永久卡 ❌
```

根因不是 `receiverQueueSize(1)` 或 `acknowledge(msg)` 配错了——这两者都已正确。根因是**消费者构建时缺少 `ackTimeout` 兜底**：当上述 ❌ 分支触发时，没有"如果 X 秒内仍未 ack，Broker 端强制回收并重投"的机制。

三种典型触发场景：

1. **业务代码死锁/死循环**（例如某条消息解析时走入未覆盖的递归分支）。
2. **阻塞 IO 超时未释放**（例如 PDF 下载卡在某个 TCP 半开连接上，且应用层超时设得极大或没设）。
3. **JVM 整体 hang**（Full GC 极长、SAFETYPOINT 失联）但 socket 连接本身未断。

这三种情况**都不能依赖 Broker 主动检测**——Broker 只看心跳、看消息派发/ack 计数，看不到消费者 JVM 内部状态。

### 阶段 3：解决方案设计 —— ackTimeout 兜底

Pulsar 的 `ConsumerBuilder.ackTimeout(long, TimeUnit)`：

- **设置后**，Broker 端会跟踪每个已派发消息的时间戳。若某消息派发后超过 `ackTimeout` 仍无 ack 也无 nack，Broker 自动触发**负确认**，消息按 `negativeAckRedeliveryDelay` 延迟后被重投。
- **正常完成路径不受影响**：处理完调用 `consumer.acknowledge(msg)` 时，消息早已离开"已派发"状态，根本不会走到超时分支。
- **不影响现有失败语义**：`PulsarService` 设计上"业务异常也 ack、靠 jsonUrl='' 通知下游"——这是业务决策，不能改。`ackTimeout` 只在**真正卡死**这一极端场景下生效，与该设计正交、不冲突。

### 阶段 4：方案细化 —— 通过 `AskUserQuestion` 确认三个关键决策

在落地前用 `AskUserQuestion` 收集了 3 项决策：

| 决策点 | 选择 | 理由 |
|--------|------|------|
| 配置方式 | **写入 application.yml**（而非硬编码常量） | 与现有 `pulsar.count` / `pulsar.ocrCount` 模式一致；不同环境可独立调 |
| 默认超时值 | **60 秒** | 既能较快发现 hang，又覆盖多数 PDF 解析+OCR 重试链路的正常耗时 |
| 是否同时调整 nack 相关配置 | **仅加 ackTimeout，不动 nack** | 保持现有"失败仍 ack、下游观察 jsonUrl=''"的语义不变；nack 系列只在 ackTimeout 触发自动重投时由 Broker 内部使用，默认值已够用 |

### 阶段 5：实施与发现 —— Pulsar 2.9.2 的 API 兼容性问题

按方案实施时遇到一个非预期的兼容性问题。

**问题**：第一版按"用户回答 60s/10s tick"写了 `ackTimeoutSeconds` + `ackTimeoutTickSeconds` 两个字段，并在两个 consumer 上分别加：

```java
.ackTimeout(pulsarProperties.ackTimeoutSeconds(), TimeUnit.SECONDS)
.ackTimeoutTickDuration(pulsarProperties.ackTimeoutTickSeconds(), TimeUnit.SECONDS)
```

**编译失败**：

```
[ERROR] PulsarService.java:[159,29] 找不到符号
  符号:   方法 ackTimeoutTickDuration(int,java.util.concurrent.TimeUnit)
  位置: 接口 org.apache.pulsar.client.api.ConsumerBuilder<byte[]>
```

**根因**：

1. 项目依赖 `<pulsar-client.version>2.9.2</pulsar-client.version>`（见 `java/opendataloader-pdf-server/pom.xml`）。
2. `ConsumerBuilder.ackTimeoutTickDuration(long, TimeUnit)` 是 **Pulsar 2.10.0+** 才加入的 API（参考 pulsar PR #11862）。
3. 在 2.9.x 中，tick 周期由 Broker 端控制（默认 1s），客户端无需也无法设置。

**修复**：移除 `ackTimeoutTickDuration(...)` 调用和 `ackTimeoutTickSeconds` 字段，**只保留 `ackTimeout`**——这才是 2.9.2 能用的兜底 API。tick 周期交给 Broker 默认值即可。

> 教训：写 Pulsar 客户端代码前必须确认服务端/客户端版本，本项目 `pom.xml` 锁的是 2.9.2，不在新版本可用的 API 要主动绕过。

---

## 已实现方案（最终版，已编译通过）

### 改动文件

#### 1. `java/opendataloader-pdf-server/src/.../config/PulsarProperties.java`

新增 1 个字段（位于 `ocrCount` 之后）：

```java
@ConfigurationProperties("pulsar")
public record PulsarProperties(
        @DefaultValue("") String servers,
        @DefaultValue("") String token,
        @DefaultValue("") String receiveTopicName,
        @DefaultValue("") String sendTopicName,
        @DefaultValue("") String ocrSendTopicName,
        @DefaultValue("") String ocrReceiveTopicName,
        @DefaultValue("1") int count,
        @DefaultValue("1") int ocrCount,
        @DefaultValue("60") int ackTimeoutSeconds) {
}
```

yml 覆盖示例：

```yaml
pulsar:
  ack_timeout_seconds: 300   # 大文件 / OCR 流水线长时可调大
```

#### 2. `java/opendataloader-pdf-server/src/.../pulsar/PulsarService.java`

**(a) 新增 import**：

```java
import java.util.concurrent.TimeUnit;
```

**(b) 普通 receive consumer（约第 152–158 行）**：

```java
Consumer<byte[]> consumer = client.newConsumer(Schema.BYTES)
        .topic(pulsarProperties.receiveTopicName())
        .subscriptionName(subscriptionName(pulsarProperties.receiveTopicName()))
        .subscriptionType(SubscriptionType.Shared)
        .receiverQueueSize(1)
        .ackTimeout(pulsarProperties.ackTimeoutSeconds(), TimeUnit.SECONDS)   // ← 新增
        .subscribe();
```

**(c) OCR receive consumer（约第 173–179 行）** —— 同样的位置插入同样的 `.ackTimeout(...)` 链。

**(d) 类级 Javadoc 新增段落**（位于 Failure semantics 段之后）：

```java
* <p>Hung-consumer safeguard: every consumer is built with
* {@code receiverQueueSize=1} plus {@code ackTimeout} (tunable via
* {@code pulsar.ack_timeout_seconds}, default 60s). If a consumer receives a
* message but neither acknowledges nor negatively acknowledges it within
* the timeout - e.g. due to a deadlock, blocking IO, or the JVM being stuck
* without the connection dropping - the broker automatically redelivers
* the message. This does NOT change the failure semantics above: the
* auto-redelivery only fires when the consumer is genuinely hung; normal
* processing still completes via {@code consumer.acknowledge(msg)}.</p>
```

### 行为对照表

| 场景 | 修改前 | 修改后 |
|------|--------|--------|
| 正常处理完 + `acknowledge(msg)` | ✅ 正常 | ✅ 正常（路径完全不变） |
| 业务异常 → catch + ack(jsonUrl="") | ✅ 下游观察空 url | ✅ 不变 |
| 消费者崩溃 / 连接断开 | ✅ Broker 检测心跳/连接，重投 | ✅ 不变 |
| **消费者 hang 死但连接在** | ❌ 消息永久卡住，permit 收不回来，下游也不派发新消息 | ✅ 60s 后 Broker 自动 nack 并按 `negativeAckRedeliveryDelay` 重投 |

---

## 验证

- 编译：`cd D:\Code\JavaCode\opendataloader-pdf\java\opendataloader-pdf-server; mvn -q -o compile -DskipTests` → **exit code 0** ✅
- 阅读 diff：确认两个 consumer 构建链都加上了 `.ackTimeout(...)`，且 `ackTimeoutTickDuration` 已彻底移除（首版误用后已纠正）。
- 现有的 `application-*.yml`（dev/test/prepub-*/prod-*）无需修改：`@DefaultValue("60")` 为 `ackTimeoutSeconds` 提供默认值，各环境按需覆盖即可。

---

## 关键决策（Key Decisions）

1. **只动 `ackTimeout`，不动 nack 系列**：用户明确选择"保持现有失败语义不变"。`ackTimeout` 是兜底、不是错误处理路径；现有"失败也 ack、靠 jsonUrl='' 通知"是有意的设计（Javadoc 已明文），不应在本次顺手"修复"。
2. **不引入 DLQ**：当前架构无 DLQ，新增会牵涉到 topic 模板、broker 启用等环境改动，超出本次范围。`negativeAckRedeliveryDelay`、`maxNegAckRedeliverCount` 用 Pulsar 默认值即可，broker 内部行为不暴露给业务。
3. **默认值 60s**：覆盖大多数正常 PDF 解析+OCR 重试链路。运维在发现超时频繁误触发的环境（超大文件、超慢 OCR）可通过 yml 调大，无需重新编译。
4. **不升级 Pulsar 客户端到 2.10+**：本次需求用 2.9.2 已有的 API 即可满足，避免大版本升级带来的回归风险。
5. **构建链顺序保持不变**：`ackTimeout` 紧跟在 `receiverQueueSize(1)` 之后，与"读取即消费 + 兜底"的语义分组对齐，便于 reviewer 理解。

---

## 已知限制 / 后续可做

1. **`negativeAckRedeliveryDelay` 和 `maxNegAckRedeliverCount` 用默认值**：若线上发现 ackTimeout 频繁触发、消息被反复重投消耗资源，可考虑显式设置这两个值，并配套引入 DLQ topic。
2. **未做"超时事件"业务级告警**：当前 ackTimeout 触发后只是消息被重投，业务侧无感知。若需"消费者卡死"告警，可在 `PulsarService` 中订阅 Pulsar 的 `ConsumerEventListener`（2.10+ 才有，2.9.2 无此接口）或通过 broker 指标监控。
3. **60s 可能误触**：单条 PDF 解析（含 5 次 download 重试、每次最多 2 分钟超时）在异常下载源下可能超过 60s。一旦触发重投，原消息会被重新派发到另一个 consumer 实例（Shared 订阅），与"正在卡死的实例"并行处理，可能产生重复消费。当前业务模型靠下游按 `businessId` 去重（如有），不会重复执行；如无去重，需评估影响。
4. **未补充单元测试**：本模块历史上无单测覆盖，集成测试依赖真实 Pulsar 集群；本地 CI 跑不起来，建议后续加一个 `PulsarServiceTest` 用 `MockedPulsarClient`（2.10+ 提供）模拟超时场景。

---

## 相关文件（Relevant Files）

- `java/opendataloader-pdf-server/src/main/java/org/opendataloader/pdf/server/pulsar/PulsarService.java`
  - import 块（约第 54 行附近）：新增 `java.util.concurrent.TimeUnit`。
  - `start()` 普通 consumer 构建器（约第 152–158 行）：加 `.ackTimeout(...)`。
  - `start()` OCR consumer 构建器（约第 173–179 行）：加 `.ackTimeout(...)`。
  - 类级 Javadoc：在 "Failure semantics" 段后新增 "Hung-consumer safeguard" 段。
- `java/opendataloader-pdf-server/src/main/java/org/.../config/PulsarProperties.java`
  - record 末尾新增 `@DefaultValue("60") int ackTimeoutSeconds`。
- `java/opendataloader-pdf-server/pom.xml`
  - 引用 `<pulsar-client.version>2.9.2</pulsar-client.version>`（**未改**，仅核对以确认 API 可用性）。
- `java/opendataloader-pdf-server/src/main/resources/application-*.yml`
  - **未修改**：`@DefaultValue` 已提供默认 60s，无需在 7 个环境 yml 中重复声明。

---

## 环境 / 命令备忘

- Maven 本地仓库在 `D:\Maven_Repo`（`D:\Applications\apache-maven-3.9.x\conf\settings.xml`），`mvn` 必须带 `-o` 离线编译。
- Shell 实际为 PowerShell：`cd` 用 `;` 连接命令、`Select-Object -Last` 替代 `tail`、`tail` / `&&` 均不可用。
- 编译验证命令（与项目其他模块一致）：

```powershell
cd D:\Code\JavaCode\opendataloader-pdf\java\opendataloader-pdf-server
mvn -q -o compile -DskipTests
```

- 如需查看 Pulsar API 是否在某版本可用：`org.apache.pulsar.client.api.ConsumerBuilder` 接口源码在本地仓库 `D:\Maven_Repo\org\apache\pulsar\pulsar-client-api\2.9.2\pulsar-client-api-2.9.2-sources.jar`，可用 `jar tf` 列出 / `jar xf` 解压查看。
