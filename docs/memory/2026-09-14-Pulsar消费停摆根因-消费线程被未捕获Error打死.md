# opendataloader-pdf 任务记忆 — 2026-09-14（Pulsar `announcement_parse` 消费停摆：消费线程被"未捕获的 Error"打死）

> 现象：Pulsar 消费暂停，"总会有几个不消费"，当前 **6 个**。
> 结论：**6 个消费线程已被 `java.lang.Error` 打死（线程终止），它们各自扣住 1 条消息永不 ack → 消费者槽位永久丢失。**
> 已实施最小止血：`PulsarService` 中 11 处 `catch (Exception)` → `catch (Throwable)`。

---

## 目标（Goal）

用户报告 Pulsar 消费停摆，并给出三套观测入口与账号，要求**先查出真正原因（重在查因，而不是改代码）**，Chrome 已打开可被直接操控：

1. Kibana 日志：`https://kibana-huawei.valueonline.cn/app/r/s/S5rwj`（elastic / elastic@123）
2. Nightingale 服务器 JMX 看板：`https://monitor.valueonline.cn/dashboards/201?...instance=app-0-88-prod-beijing1-hwcloud&port=8091&services=opendataloader-pdf-parser`（xiaobo.li / Jzzx@123）
3. Nightingale Pulsar 看板：`https://monitor.valueonline.cn/dashboards/32?...cluster=pulsar-cluster-hwcloud-bj4&namespace=capital/pdf_parse&topic=persistent://capital/pdf_parse/announcement_parse`

## 结论速览（TL;DR）

- 卡住的是订阅 **`announcement_parse`**（topic `persistent://capital/pdf_parse/announcement_parse`）：
  `back_log=6`、`unacked_messages=6`、`blocked_on_unacked=0`、`consumers_count=7`、`msg_rate_out=0`、`msg_rate_redeliver=0`。
- **根因**：`PulsarService.consumeReceiveLoop` 的 `while` 循环**只 `catch (Exception)`**。当处理某条消息抛出 **`Error`（非 `Exception` 子类）** 时，两层 catch 都拦不住 → **线程终止**；
  线程虽死，它持有的 `Consumer` 仍注册在 broker（`consumers_count` 看起来正常），但**再没有任何线程对它调 `receive()` / `acknowledge()`** → 那条消息**永久 unacked**，该槽位**永久报废**。
- 所以 **未确认数 == 死掉的线程数** → 当前 6。`ackTimeout=60s` 对此**无效**（它只是把消息重新放回**同一个**客户端队列，而那个 consumer 已无线程取）。
- **jstack 铁证**：`../debuglogs/jstack-app088-20260914_163816.txt`（78 个线程）中，全部 `pulsar-receive-*` **只剩 `pulsar-receive-1`（#33，空闲阻塞在 `consumer.receive()`）**，其余 6 个 **不在 dump 中 = 已死亡**。
- 症状"**总会有几个不消费**"= 每发生一次 `Error` 事件就永久吃掉一个消费槽位，随时间**只增不减**，涨到 7 就彻底停摆。

---

## 背景（Context）

### 代码结构（消费模型）

`java/opendataloader-pdf-server/.../pulsar/PulsarService.java`：

```java
while (running) {
    try {
        Message<byte[]> msg = consumer.receive();
        handleReceiveMessage(consumer, msg);   // download → processForPulsar → send → 清理 → ack
    } catch (Exception e) {                     // ★ 原实现：只 catch Exception
        if (!running) break;
        log.error("pulsar receive loop error: {}", e.getMessage(), e);
        sleepBriefly();
    }
}
```

- 每个 consumer 一个 daemon 线程（`pulsar-receive-<i>`），**同步**处理、`.receiverQueueSize(1)`、`SubscriptionType.Shared`；
- `handleReceiveMessage` 内部也**只 `catch (Exception)`**，其后**必定**执行 `sendResultMessage(...)` 与 `acknowledgeQuietly(...)`；
- 失败语义（有意设计）：**失败也 ack**，把 `jsonUrl=""` 抛给下游，下游可（用 `retry_pdf_parse`）自行重试。

### 部署配置（`application-prod-hjs.yml`）

| 配置 | 值 |
|---|---|
| `receive_topic_name` | `capital/pdf_parse/announcement_parse` |
| `send_topic_name` | `capital/pdf_parse/announcement_parse_result` |
| `ocr_receive_topic_name` | `capital/pdf_parse/announcement_ocr_image_analysis_result` |
| `count` / `ocrCount` | **7** / 4 |
| `ack_timeout_seconds` | 60 |
| `is_ocr` / `is_immediate_ocr` | true / **false**（全文 OCR 走旁路） |
| `parse_stream_table` / `formula_recognize` | true / false |

两个 pod 跑同一个 jar（Kibana `program` 都是 `opendataloader-pdf-server-hjs`，靠 `host.ip` 区分）：
- **`192.168.0.88` = `app-0-88` = profile `prod-hjs`（消费 `announcement_parse`）← 故障方**
- `192.168.0.11` = profile `prod-hjsinc`（消费 `announcement_parse_increment`）← 正常

### 观测入口与手法（可复用）

- **Nightingale Pulsar 看板**底层是 Prometheus 代理：`/api/n9e/proxy/11/api/v1/query?query=<promql>`、`/query_range?...&step=`，在页面内同源 `fetch` 即可（已登录态）。
  关键指标：`pulsar_subscription_back_log`、`pulsar_subscription_unacked_messages`、`pulsar_subscription_blocked_on_unacked_messages`、`pulsar_subscription_consumers_count`、`pulsar_subscription_msg_rate_out`、`pulsar_subscription_msg_rate_redeliver`、`pulsar_subscription_last_consumed_timestamp`。
- **Kibana**：用 ES 代理直接查比点 UI 快——
  `POST /api/console/proxy?path=<index>%2F_search&method=POST`，header 加 `kbn-xsrf: true`，body 为普通 ES 查询。
- ⚠️ 踩坑：
  1. `program` / `host.ip` / `service_host` 等字段**不可聚合**（mapping 未建 keyword，terms agg 返回空），只能在 JS 里客户端统计；
  2. `match_phrase` 对 `1789116433707014605_1344679311993899` 这种**下划线长数字 token** 不匹配，要用 `wildcard: *<biz>*`；
  3. `host.name`/`host.ip` 是**区分两个 pod 的唯一办法**（thread 名会重名）。
- **Nightingale JMX 看板 201**：看 Heap / Old Gen / CPU System Load / Uptime，用来判定"是烧 CPU 还是阻塞"。

---

## 定位过程（从问题到根本原因）

### 第 1 步：先量化"6 个不消费"落在哪个订阅

查 `announcement_parse`：`back_log=6`、`unacked=6`、`blocked_on_unacked=0`、`consumers=7`、`msg_rate_out=0`、`msg_rate_redeliver=0`、`last_consumed≈07:57:56Z`。

→ **6 条消息"已投递但永不 ack"**；`blocked=0` 排除 broker 端限流/暂停；`redeliver=0` 说明**重投机制根本没触发**。

### 第 2 步：看时间趋势，确认是"冻结"不是"慢"

近 2h：`06:39Z` 前 backlog=0 → `06:39Z` 一次性涌入 **2712** 条 → 一路消费到 **剩 6 条**（约 07:59）→ **30+ 分钟完全不变**（backlog/unacked 恒为 6，out/redeliver 恒为 0，consumers 恒为 7）。

### 第 3 步：定位到具体服务与 socket 实例

`program=opendataloader-pdf-server-hjs`，按 `host.ip` 分出两个 pod；`192.168.0.88`（app-0-88）正是 prod-hjs。对比：
**pod .11 的 7 个 `pulsar-receive-*` 线程 08:26 全部活跃**，而 **pod .88 的 7 个线程全部静默**（最后日志 06:39~07:55）。

### 第 4 步：逐线程看"最后一条日志"（初判卡点）

| 线程 | 最后日志(UTC) | 最后动作 |
|---|---|---|
| recv-0 / recv-6 | 06:39:39 | OBS 操作后 `client closing/closed` |
| recv-2 | 06:39:40 | `Uploaded OBS object: ...imageFile2.png` 后 |
| recv-5 | 06:39:54 | `Uploaded OBS object: ..._streamtable_80.png` 后 |
| recv-3 | 06:45:56 | 一批 `Page 58~75 text extraction is mostly garbled... falling back to full-page OCR` |
| recv-4 | 07:23:06 | 一批 `Page 593~618 ... falling back to full-page OCR` |
| recv-1 | 07:55:56 | 一条失败消息（`XRef loop`）被 ack 之后 |

→ 都在**处理大扫描件（60~600+ 页）**的中途断掉；**卡住时段 0 条 ERROR/WARN**（静默）。

### 第 5 步：排除"CPU 死循环"（与 2026-08-12 那次的关键差异）

JMX 看板 201（app-0-88）：**CPU Core 8、CPU System Load ≈ 3~5%**，Heap Used 峰值 3.86G / Max 4.29G。
→ 线程**不是在烧 CPU**，而是在**等待**。这与 `2026-08-12-...解析卡住-ShapeRecognizer-buildChains性能修复.md`（当时 CPU 满载、纯性能问题）**性质完全不同**。

### 第 6 步：一个"看似卡住其实是失败"的陷阱（排除法）

用"有 `download pdf success` 但没有 `process pdf success`"筛卡住消息，只筛出 1 条 `businessId=1789116433707014605_1344679311990693889`。
追它的全量日志后发现：它其实**已被处理并 ack**，只是每次都抛
`ERROR handleReceiveMessage failed ...: XRef loop(offset = 84584)`（PDFBox 对循环 xref 的报错）——**失败路径本来就不会打 `process pdf success`**。
→ 教训：**"缺 success 日志" ≠ "卡住"**，必须用"有开始日志但既无成功也无失败日志"来判定。

### 第 7 步：jstack —— 决定性一击

拿到的 jstack（`2026-09-14 16:38:16`，共 78 个线程）显示：

```
"pulsar-receive-1" #33 daemon ... waiting on condition
   java.lang.Thread.State: WAITING (parking)
     at GrowableArrayBlockingQueue.take
     at ConsumerImpl.internalReceive(ConsumerImpl.java:412)
     at ConsumerBase.receive(ConsumerBase.java:151)
     at PulsarService.consumeReceiveLoop(PulsarService.java:225)   ← 空闲在等消息，手上没有消息
```

而 **`pulsar-receive-0 / -2 / -3 / -4 / -5 / -6` 一个都不在 dump 里**（线程 ID `#31`、`#34–#38` 共 **6 个 ID 缺失**）；
`pulsar-ocr-receive-0..3`（#39/40/42/44）都在（OCR 侧正常）；**没有 "Found one Java-level deadlock"** 段。

→ **6 条未确认消息，恰好对应 6 个"已终止"的消费线程。** 谜底闭合。

### 第 8 步：解释为什么"线程死"= "永远不消费"、且 `ackTimeout` 无效

1. `Error` 穿透两层 `catch (Exception)` → 冒泡出 `while` → **线程结束**；
2. `handleReceiveMessage` 末尾的 `acknowledgeQuietly(...)` **执行不到** → 该消息**永久 unacked**；
3. `Consumer` 对象/连接**仍注册在 broker**（所以 `consumers_count` 恒为 7、看起来"消费者都在"），但**没有任何线程再调它的 `receive()`/`acknowledge()`**；
4. broker 侧 `blocked_on_unacked=0`（远未到 unacked 上限）→ 不会限流；也不会有"连接断开重投"（连接没断）；
5. **客户端 ackTimeout 只是把消息重新放回"同一个"客户端的收件队列**——而那个 consumer 已无线程消费，故 `msg_rate_redeliver=0`，消息**永远卡住**。

---

## 根本原因（Root Cause）

**`PulsarService.consumeReceiveLoop` 只捕获 `Exception`。当某条消息在处理过程中抛出 `java.lang.Error`（如 `OutOfMemoryError` / `StackOverflowError`）时，该 Error 逃逸出消费循环，导致消费线程永久终止。死线程持有的 `Consumer` 仍留在 broker 上，它那条未 ack 的消息再也不会被 ack 或重投 —— 消费者槽位与消息双双"永久卡死"。每个这样的 Error 事件永久损失一个槽位，因此表现为"总有 N 条不消费"且 N 只增不减。**

### 是什么 Error？→ 应用日志里看不到

`Exception in thread "pulsar-receive-N" ...` 是 **JVM 打到 stderr** 的，**没有进 Kibana**（Kibana 只采应用日志文件）。
在 Kibana 全量检索 `Exception in thread` / `OutOfMemoryError` / `NoClassDefFoundError` 均 **0 命中**。
但 JMX 显示 **Heap Used 峰值 3.86G / Max 4.29G（≈90%）、Old Gen 多次贴顶、GC 锯齿剧烈** → **`OutOfMemoryError` 嫌疑最大**（其次 `StackOverflowError`：深递归、超大 base64 整页图、长文档 JSON）。

### 已排除项

- 不是 broker 限流/暂停（`blocked_on_unacked=0`）；
- 不是 CPU 死循环（System Load 3~5%，与 2026-08-12 的性能型"卡住"不同）；
- 不是 OCR 服务整体挂（同 pod 的 `pulsar-ocr-receive-*` 正常收发）；
- 不是 `fallbackOcrPage` 内联 OCR（prod `is_immediate_ocr=false`）；
- `parse_stream_table=true` 走的 `haveStreamTables` **只做纯文本判定、不发 OCR**（OCR 在未被调用的 `processStreamTables` 中）；
- `PaddleOcrProcessor`（okhttp connect/read/write 各 60s）与 `PaddleOcrClient.callWithTimeout`（60s）均有超时，OCR 侧有界。

---

## 已实施改动（最小止血）

用户指令："修改代码，`catch (Exception)` 改为 `catch (Throwable)`"。

**文件**：`java/opendataloader-pdf-server/src/main/java/org/opendataloader/pdf/server/pulsar/PulsarService.java`

**11 处** `} catch (Exception e) {` → `} catch (Throwable e) {`：

| 方法 | 作用 |
|---|---|
| `start()` | 启动异常不再打死容器 |
| **`consumeReceiveLoop`** | **主消费循环（关键：线程不再退出）** |
| **`handleReceiveMessage`** | **单条消息处理（关键：仍走到末尾 ack）** |
| **`consumeOcrReceiveLoop`** / `handleOcrReceiveMessage` | OCR 侧同款保护 |
| `sendResultMessage` / `sendOcrPayload` | 发送失败不中断 |
| `toRelativeObsUrl` / `extractPdfBaseName` | 工具方法 |
| **`acknowledgeQuietly`** / `closeQuietly` | **保证 ack 不被 Error 打断** |

该类（模块内唯一含 `catch (Exception)` 的文件）另有 **类 Javadoc 新增 “Thread-survival safeguard”** 段，写清"catch Exception 会让 Error 打死线程 → 消息永久 unacked → 槽位永久丢失（`ackTimeout` 无效）"的现象，**防止后人改回**。

### 验证

| 检查项 | 结果 |
|---|---|
| `read_lints` | 0 错误 |
| `cd java/opendataloader-pdf-server; mvn -o -q compile -DskipTests` | **退出码 0** |
| `target/classes/.../PulsarService.class`(16:57:47) vs 源码(16:57:30) | 类文件更新 → **确实重编译**（非缓存） |
| `findstr` 复核 | `catch (Throwable` **11 处**、`catch (Exception` **0 处** |

### 行为对照

| 场景 | 改前 | 改后 |
|---|---|---|
| 业务 `Exception` | 记日志 → ack（`jsonUrl=""`） | 不变 |
| **`Error`（OOM/SOE…）** | **线程死 → 消息永久 unacked → 槽位永久丢失 → 逐步停摆** | **记日志（含栈）→ ack（`jsonUrl=""`）→ 线程继续工作** |
| `ackTimeout` | 对上述无效 | 仍无效，但已不需要它兜这个底 |

即：故障由「**永久停摆（可用性事故）**」降级为「**个别大文件解析失败并被正常上报**」（下游可用 `retry_pdf_parse` 重试）。

### 后续两项"是否有必要"的决策

| 项 | 判断 | 理由 |
|---|---|---|
| Error 后 `negativeAcknowledge` / `close` 交还槽位 | **不需要，且不建议** | 改 `Throwable` 后 Error 在 `handleReceiveMessage` 内被吞掉，**仍会走到末尾 `acknowledgeQuietly`** → 消息照常 ack，**不会漏槽位**；改 nack 会让"确定性失败的大文件"陷入 `nack→重投→再 Error` **毒消息循环**，`close()` 更糟 |
| 消费者线程存活看护（死了自动重建 consumer） | **非必需，可延后** | 原触发条件（Error 逃逸）已堵住；仅剩极端情形（catch 块自身在 OOM 下再抛 Error）会退出线程，属纵深防御 |

---

## 遗留 / 后续

1. **Error 本身未消除**（第 4 项）：大扫描件/长文档**仍会解析失败**（只是不再拖垮链路）。**好消息**：改动后 Error **第一次会被写进日志**（`handleReceiveMessage failed ... + 栈`），此前线程直死、日志无痕。
   → 建议上线后**先不改代码**，到 Kibana 抓 `handleReceiveMessage failed` 里的 Error 实栈，再决定治本方向（加堆 / 流式处理长文档 JSON / 别把整页图 base64 全塞内存 / 限制大文档并发）。
2. **已卡住的 6 条消息不会因本次改动自动恢复**（被死消费者扣着，broker 不重投）→ 需**滚动重启 `app-0-88` 的 Pod** 释放（与代码改动无关的现场消缺）。
3. 建议加 `-XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/tmp` 以捕获现场。
4. 同族现象待复核：`hk_announcement_ocr_image_analysis`（unacked 877）、`announcement_ocr_image_analysis`（unacked 177）等也在积压，若同样是"线程被 Error 打死"，会一起复发。

## 相关文件与产物

- 代码：`java/opendataloader-pdf-server/src/main/java/org/opendataloader/pdf/server/pulsar/PulsarService.java`（本轮唯一改动文件）
- 配置：`java/opendataloader-pdf-server/src/main/resources/application-prod-hjs.yml`（`receive_topic_name=announcement_parse`、`count=7`、`ack_timeout_seconds=60`、`is_immediate_ocr=false`）
- 现场证据：`../debuglogs/jstack-app088-20260914_163816.txt`（jstack，2026-09-14 16:38:16）
- 关联记忆：
  - `docs/memory/2026-09-10-Pulsar消费者ackTimeout兜底防止卡死消息.md`（本故障的"预案"，其"ackTimeout 可兜底"的结论在本场景**不成立**）
  - `docs/memory/2026-08-12-202303251679660111823147.pdf解析卡住-ShapeRecognizer-buildChains性能修复.md`（同类"卡住"但性质为 **CPU 满载**，与本轮"线程死亡"需区分）
  - `docs/memory/2026-08-15-LineArtProcessor性能优化-并发-整页OCR.md`（OCR 并发/超时设计背景）
