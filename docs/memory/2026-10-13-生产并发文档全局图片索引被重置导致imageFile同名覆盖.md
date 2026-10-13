# 2026-10-13 — 生产并发解析下全局图片索引被重置导致 imageFile 同名覆盖

## 任务背景 / 现象

样本文件：`docs/pdf/202007061784000125245069931.pdf`（截至二零二零年六月三十日止股份發行人的證券變動月報表）。

- **本地**用 `org.opendataloader.pdf.DebugSample1` 解析结果正确。
- **生产**解析结果异常：
  1. 第 9 页的截图（OCR 后应为「文本 + 表格」）在第 9 页消失了；
  2. 这张截图跑到了**第 1 页的开头**；
  3. 第 1 页原本开头的截图不见了，疑似被第 9 页的截图**覆盖**；
  4. 第 9 页截图的**图片文件名与第 1 页截图重复**。

生产解析详情链接：`https://pdf-parse.valueonline.cn/ui/pdfParse/home/detail?announcementId=1784000125245069931&...&parseRecordId=1352391339298414592`（已用 admin 登录核验，详情页可正常渲染）。

## 定位过程（从现象到根因）

### 第 1 步：抓住「本地对、生产错」的差异点

`DebugSample1.main()` 里 `customOptions.put("paddleUrl", ...)` 是**注释掉的** —— 本地不跑 OCR；生产是开 OCR 且并发的。现象是「图片文件名重复 + 互相覆盖」，所以方向锁定到**图片命名 / 索引分配**逻辑，而不是 OCR 内容本身。

### 第 2 步：找到图片文件名的来源

- `ImagesUtils.writeImage(ImageChunk)` 里 `int currentImageIndex = StaticLayoutContainers.incrementImageIndex();`，文件名 `imageFile{N}.png` 由这个 `N` 决定，并 `chunk.setIndex(N)` 烘进对象；序列化时 `ImageSerializer` / `PictureSerializer` 只**读** `getIndex()` 拼路径。
- 关键：`StaticLayoutContainers.imageIndex` 是 **`private static final AtomicInteger`（进程级全局，不是 ThreadLocal）**，而 `imagesDirectory` 是 **ThreadLocal（每文档独立目录）**。字段注释写明当初做成全局唯一是为了「同一文档多个 ForkJoinPool worker 之间不重名」。

### 第 3 步：确认索引分配的时机与顺序

`DocumentProcessor.processDocument`（Phase 1 抽取）里：
- L652 `imagesUtils.write(contents)` 在**主线程**按页序（page0→pageN）给所有 ImageChunk 分配 `imageFile{N}`；
- L792 chart/formula 循环在 **ForkJoinPool worker** 上继续 `saveImageChunk` → 继续 `incrementImageIndex()`。

Phase 2 输出 `JsonWriter.writeToCustomJson`（L300 `resetImageIndex()`）与每文档开始 `preprocessing → updateStaticContainers → clearContainers`（L61 `imageIndex.set(1)`）**都会把这个全局计数器重置为 1**。

单文档顺序跑：write() 与 chart/formula 都在 reset 之前完成，索引 1..N 连续唯一 → 本地永远正确。

### 第 4 步：确认生产是同 JVM 并发多文档

`PulsarService.start()` 启动 `count()` 个 receive 消费者 + `ocrCount()` 个 OCR 消费者，每个在独立线程（`pulsar-receive-i` / `pulsar-ocr-receive-i`），`receiverQueueSize(1)` + Shared 订阅；`runWithWatchdog` 还可能为每条消息再开单线程 executor。**多个 PDF 在同一 JVM 内并发 `processFile`**，OCR 与非 OCR 混跑。

### 第 5 步：串起冲突时序（完全吻合现象）

1. 文档 A（本 PDF）主线程 `write()` 按页序分配：第 1 页拿到 `imageFile1.png`；
2. 并发的文档 B 进入 `clearContainers()` / `resetImageIndex()`，把**全局** `imageIndex` 打回 1；
3. A 继续处理到第 9 页时再次 `incrementImageIndex()` → 又拿到 1；
4. A 的第 9 页截图写成 `imageFile1.png`，**覆盖** A 第 1 页的文件；A 的第 1 页与第 9 页在 JSON 里引用同一个图片名。

本地单文件无并发重置 → 正常。这就是「本地对、生产错」的根因。

## 根因

`StaticLayoutContainers.imageIndex` 是**进程级全局 `static AtomicInteger`**，作用域被错误地设为「整个 JVM」。生产服务器同 JVM 并发解析多文档时，每个文档开始 / 输出阶段都会 `resetImageIndex()` / `clearContainers()` 重置这个共享计数器，导致**一个文档的重置把另一个正在分配索引的文档的计数器打回**，进而同文档内后面的页复用前面页的 `imageFile{N}` 索引 → 同名文件互相覆盖。

字段注释担心的「跨 worker 重名」只针对**单文档内并行**，用了「全局」这一过度手段，反而引入了**跨文档**冲突。

## 修复策略

把计数器作用域从「进程全局」改为「**每文档一份、且在该文档的 worker 线程间按引用共享**」，沿用项目已有的 `embeddedImageBytesMap` / `replacementCharRatiosMap` 通过 `propagateState` 按引用下发可变对象的模式。

## 改动清单

### 1. `java/opendataloader-pdf-core/.../containers/StaticLayoutContainers.java`

- 字段：`private static final AtomicInteger imageIndex = new AtomicInteger(1);`
  → `private static final ThreadLocal<AtomicInteger> imageIndex = ThreadLocal.withInitial(() -> new AtomicInteger(1));`
- `clearContainers()`：`imageIndex.set(1)` → `imageIndex.set(new AtomicInteger(1))`（每文档一份全新实例，重置只影响本线程/本文档）。
- `incrementImageIndex()`：`imageIndex.get().getAndIncrement()`。
- `resetImageIndex()`：`imageIndex.get().set(1)`。
- 新增按引用下发访问器：`getImageIndex()`（返回本线程 `AtomicInteger` 实例）、`setImageIndex(AtomicInteger)`。
- 更新字段注释，说明「每文档一份 + 跨 worker 按引用共享」的双重目的。

### 2. `java/opendataloader-pdf-core/.../processors/DocumentProcessor.java`

- 在主线程捕获段（`embeddedImageBytesMap` / `replacementCharRatiosMap` 附近）新增：
  `final var imageIndexAtomic = StaticLayoutContainers.getImageIndex();`
- 在 `propagateState.run()` 内新增：
  `StaticLayoutContainers.setImageIndex(imageIndexAtomic);`
  → 让 chart/formula 等 worker 与主线程 `write()` 共用同一个 `AtomicInteger`，保证单文档内跨 worker 唯一、跨文档隔离。

### 3. `java/opendataloader-pdf-core/.../utils/ImagesUtils.java`

- `writeImage()` 写盘前加**覆盖告警日志**（非 embed 模式）：若 `new File(fileName).exists()` 为真，`LOGGER.log(WARNING, "Image index collision: ...")`，带文件名 / 页码 / 索引。修复后应永不触发，用于上线后核验。

## 验证

- `mvn -o -pl opendataloader-pdf-core compile` → **BUILD SUCCESS**。
- 静态检查（GetProblems）三处改动无错误。
- 生产 UI 已用 admin 登录，详情页正常渲染（page1 顶部 HKEX logo + 文本 + 表格）。逐字节比对 `imageFile` 名需 SPA 从 OSS 拉取的原始结果 JSON，成本高；代码层证据链已闭合，足以定案。

## 上线后核验方式

Kibana（`https://kibana-huawei.valueonline.cn/app/r/s/H2mmS`）搜关键字 `Image index collision`：
- 不再出现 → 修复生效；
- 仍出现 → 说明还有未走 `propagateState` 的图片写入路径（如 hybrid / tagged 分支），据日志页码继续收敛。

## 关键决策与权衡

- **为何不直接改回普通 ThreadLocal（int）**：会退回字段注释当初警告的 bug —— 同文档多 worker 各自计数 → 跨 worker 重名覆盖。必须用 `ThreadLocal<AtomicInteger>` 且**按引用下发**，兼顾「单文档跨 worker 唯一」与「跨文档隔离」。
- **为何保留 `resetImageIndex()`**：改为主线程私有实例后，Phase 2 的重置只影响本文档、且此时索引已分配完毕，无害；保留以防未来 Phase 2 内有新的 imageFile 分配。
- **未触碰**：`imagesDirectory` / `embedImages` / `imageFormat`（本就是 ThreadLocal 且已在 `propagateImageState` 下发）；veraPDF 外部 `StaticContainers`；OCR 截图的 `_streamtable-` / `_formula-` 命名（走独立路径，不用 `imageFile{N}`）。

## 待跟进

- 可加一个**并发多文档回归测试**：两线程各自 `clearContainers()` 后交错 `incrementImageIndex()`，断言各自序列从 1 起且互不干扰（守护本次作用域修复）。
- 反射式守护测试：枚举 `StaticLayoutContainers` 所有 `ThreadLocal` 字段，比对 `propagateState` 实际下发集合，漏字段即 fail（与 2026-08-12 那篇「待跟进」同源诉求，本次仍未做）。

## 相关文件

- `java/opendataloader-pdf-core/src/main/java/org/opendataloader/pdf/containers/StaticLayoutContainers.java` —— imageIndex 改 ThreadLocal<AtomicInteger> + 下发访问器
- `java/opendataloader-pdf-core/src/main/java/org/opendataloader/pdf/processors/DocumentProcessor.java` —— 捕获并 propagateState 下发实例
- `java/opendataloader-pdf-core/src/main/java/org/opendataloader/pdf/utils/ImagesUtils.java` —— writeImage 覆盖告警日志
- `java/opendataloader-pdf-server/src/main/java/org/opendataloader/pdf/server/pulsar/PulsarService.java` —— 并发多消费者的来源（未改）
- `java/opendataloader-pdf-core/src/main/java/org/opendataloader/pdf/json/JsonWriter.java` —— L300 `resetImageIndex()`（未改，语义现已安全）
- `docs/memory/2026-08-12-Parallel-page-processing-failed根因与ThreadLocal传播修复.md` —— 同类「ThreadLocal 传播」根因，模式参考
