# 2026-09-16 — 418 页扫描件解析慢全链路定位：chart/formula 循环与 `writeOcrDetectionJson` 并行化、线程上限（`pdf.threads`）配置

## 任务背景（Goal）

用户问：`docs/pdf/202504281785145532722080227.pdf`（约 8MB）为什么解析这么慢、这么耗时？要求先查根因、不改代码、可跑 `DebugSample1` 调试。

后续在同一轮工作中延伸出三个任务：
1. 重构 chart/formula 循环里公式检测的日志与魔数（20 提取为常量、日志带文件名）；
2. 把 `DocumentProcessor` 的单线程逐页后处理循环（chart/formula 循环）并行化；
3. 排查"主 JSON 写出后 24 分钟才写出 `_ocr.json`"的时间去向，并把 `JsonWriter.writeOcrDetectionJson` 并行化；
4. 发现 `Config.getThreads()` 默认 1 导致所有"并行"循环在生产实际串行，最终落地"server 配置 cap + core 按页数微调"方案。

---

## 文件特征（一切的起点）

用 pypdf 探查该文件：

- **418 页**，每页 `extract_text()` 为空（纯扫描件外观）；
- 全文件 **418 个图片引用但只有 4 张不同的图**（414 个重复引用）——"图片翻拍"型扫描件；
- mediabox 统一 595.276 × 807.874（A4）。

**关键陷阱**：pypdf 提不出文本 ≠ 没有文本层。veraPDF 侧 `StaticContainers.setIsIgnoreCharactersWithoutUnicode(false)`（DocumentProcessor.preprocessing）保留了无 Unicode 映射的字符，每页实际有约 37 个 text item（垃圾字形）。这一点是后面"416 页被判为无线表格页"的根源。

## 与既有记忆的关系

`.codebuddy/memory/2026-09-15.md` 已记录这份文件是生产 Pulsar 的"毒消息"（businessId `1785145532722080227_...`）：在 `DocumentProcessor.java:685-724` 的单线程逐页后处理循环里某些页 **~11~12 分钟/页**，叠加 `ack_timeout_seconds=60` 被 21 个 consumer 重复下载，占满订阅槽位导致消费停摆。当天已落地看门狗（`consum_timeout_min=40`、`ack_timeout_seconds=0`、各阶段检查点），但**逐页循环慢的根因未解决**。本轮就是把这条尾巴彻底解决。

---

## 定位过程（第一轮：extraction 阶段为什么慢）

### 第一步：带页数过滤的基线测量

临时给 `DebugSample1` 加 `config.setPages("1")` 与计时，跑 shaded CLI jar：

- 全程 **43.7s**，其中 `extraction cost 43.1s`；但看日志时间戳，43s 几乎全是 `preprocessing()`（5:35:20 开始 → 5:35:51 才出第一页形状识别结果）。
- 对 processDocument 内部加阶段性埋点（临时 DIAG 日志）：pageArtifacts 拉取 0ms、Loop1 ContentFilter 26ms、ocr-fallback 13ms（size=0）、imagesUtils.write 3ms、Loop2/3 各几十 ms、**processDocument 全程仅 185ms**。

结论：extraction 阶段的大头不是逐页处理，而是 preprocessing 的 veraPDF 全文档解析（418 页 ~30s），且逐页循环在页数过滤下根本没事干。真正问题在生产全量跑时的逐页循环（见 09-15 记忆）。

### 第二步：逐页循环慢的根因（结合 09-15 生产证据）

`DocumentProcessor.java:685-724` 循环（串行、无 `shouldProcessPage` 检查）：ShapeRecognizer.groupShapes → Bar/PieChart/Flowchart 截图 → `LineArtProcessor.haveFormulas` → ConsecutiveImageProcessor。生产日志量化：同一 host+thread 相邻两页日志间隔 11.2~13.1 分钟；对照机器 192.168.0.136 同循环可 1 秒 52~136 页 → 慢在该文档的页面内容把 `haveFormulas` 内的几何/合并逻辑打进最坏情况，不是循环天生慢。

### 第三步：并行化前置条件审计（决定能不能改）

逐项核实共享状态（这是本轮最关键的方法论部分）：

1. BarChart/PieChart/Flowchart/ConsecutiveImage 四个处理器：**grep 无任何 `StaticLayoutContainers.`/`StaticContainers.` 引用**，只改传入的页内 `pageContents` 列表 → 页间独立 ✅
2. `ShapeRecognizer.groupShapes`：union-find 纯函数，只作用于入参 ✅
3. veraPDF `StaticContainers.imagesUtils`：javap 字节码确认是 `ThreadLocal<ImagesUtils>` 且 **`getImagesUtils()` 惰性自建**（`new ImagesUtils(true)` → 每线程 load 自己的 PDDocument）→ worker 天然自持实例，**无需传播** ✅
4. ⚠️ `StaticLayoutContainers.imageIndex` 是 `ThreadLocal<Integer>` 且只在主线程 `clearContainers()` 初始化：worker 上 `incrementImageIndex()` 会 NPE；就算初始化，每 worker 从 1 计数会产生**重复 imageFileN.png 互相覆盖** → 必须改成全局 `AtomicInteger`（见改动 1）
5. ⚠️ `imagesDirectory`/`imageFormat`/`embedImages` 三个 ThreadLocal 不在 `propagateState` 覆盖内（propagateState 只传播 document/bookmarks/headings 等）→ 需新增 `propagateImageState`（见改动 2）
6. deadline：worker 上看不到主线程的 ThreadLocal deadline → 用 `ProcessingDeadline.snapshot()` 捕获 + `checkUnchecked(deadline, ...)` 显式传（与 Loop2/3 同模式）

---

## 定位过程（第二轮：主 JSON 写出后 24 分钟去哪了）

用户贴日志：`Created ...202504281785145532722080227.json`（10:20:31）→ `Created ..._ocr.json`（10:44:45），问 24 分钟耗在哪。

### 第一步：时间线还原（文件系统证据）

images 目录里 416 个 `..._streamtable-N.png`：第一个 10:20:34（主 JSON 写完 3 秒后），最后一个 10:44:45（与 `_ocr.json` 日志同一秒）。

**416 页 × 3.49s/页 = 24.18 分钟，与日志窗口精确吻合。** 24 分钟全部花在 `JsonWriter.writeOcrDetectionJson` 的 Pass 1（stream-table 截图）循环。

### 第二步：为什么 416/418 页都触发截图

主 JSON 显示每页 ~37 个 text item + 1 个整页 image（即前述"垃圾文本层"）。`StreamTableProcessor.haveStreamTables` 启发式在 Loop 2 把这 416 页判为无线表格页（`pageHaveStreamTables=true`）→ Pass 1 逐页整页截图。

### 第三步：3.49s/页 的微基准分解

写 RenderBench（javac -cp shaded.jar）实测 3 页：

| 步骤 | 耗时 | 占比 |
|---|---|---|
| `renderImageWithDPI(page, 300)` | 2.8~3.8s（输出 2480×3366） | ~90% |
| `ImageIO.write` PNG | ~0.3s | ~8% |
| `Loader.loadPDF`（整个 8MB/418 页文档） | 冷 792ms / 热 10~40ms | ~2% |

两个附带发现：
- `JsonWriter.renderPage` 的 Javadoc 写 "at 200 DPI"，代码实际是 **300**（文档与实现不一致，未改，OCR 质量相关属业务决策）；
- **该渲染循环没有任何 `ProcessingDeadline` 检查点**（JsonWriter 里检查点只在 rebuild-bookmarks 和主 JSON 逐页序列化）→ 生产看门狗在此阶段无法中断 worker。

---

## 根本原因汇总

1. **逐页循环串行 + 特定页面内容触发 haveFormulas 最坏情况**（11~12 min/页，09-15 已定性）；
2. **`writeOcrDetectionJson` 三个 Pass 串行渲染 + 每页 `Loader.loadPDF` 重载整个 PDF + 300 DPI 整页渲染**，且垃圾文本层导致 416 页命中 Pass 1 → 24 分钟；
3. **`Config.getThreads()` 默认 1**（`private int threads = 1`；CLI `--threads` 默认 "1"；server 从不调用 `setThreads`）→ 此前所有 ForkJoinPool "并行"循环在生产实际**全部串行**——任何并行化改造在不解决此点前收益为零。

---

## 改动清单

### 1. `StaticLayoutContainers.imageIndex`：ThreadLocal → AtomicInteger（并行化前置）

`incrementImageIndex()` → `getAndIncrement()`；`resetImageIndex()`/`clearContainers()` 语义不变。所有调用方（`ImagesUtils.writeImage`、JsonWriter 输出阶段 renumber）均在主线程，行为一致；并行后文件名跨线程唯一。

### 2. `DocumentProcessor` chart/formula 循环并行化

- 循环体原样包进 `pool.submit(() -> IntStream.range(0, totalPages).parallel().forEach(...)).get()`；
- 循环开头 `propagateState.run()` + 新增 `propagateImageState.run()`（在主线程 `setImagesDirectory` 之后捕获 `imagesDirectory`/`imageFormat`/`embedImages` 三个 final 再重放到 worker）；
- deadline 改 `checkUnchecked(deadline, ...)`；
- 公式检测块重构：`FORMULA_SCAN_LINE_ART_CHUNK_LIMIT = 20L` 常量（带 why-Javadoc）、`countFormulaScanCandidates()` 私有方法、三条日志全部带 `displayName(inputPdfName)` 文件名、INFO 改 Supplier 惰性求值；
- 保持遍历全页（原循环本无 shouldProcessPage，过滤页是空列表 no-op，行为等价）。

### 3. `JsonWriter.writeOcrDetectionJson` 三个 Pass 并行化（+290/−153）

- `pdfFile`/`pdfBaseName` 推导从三遍合一；预创建 images 目录（消 worker 竞态 mkdirs 误警告）；
- `ProcessingDeadline.snapshot()` 捕获 deadline（看门狗 ThreadLocal 在 worker 不可见）；
- 新增 `runScreenshotPass(pool, deadline, from, to, stage, IntConsumer)`：统一 pool 提交 + 每页 `checkUnchecked` + 异常解包（`UncheckedIOException` 剥壳；`ProcessingTimeoutException` **原样上抛**保看门狗分类；`IOException` 还原上抛）；
- 三个 Pass 的循环体抽成页级 helper：`buildStreamTableOcrEntry` / `buildFormulaOcrEntry` / `buildImageDominantOcrEntry`（判定条件、Pass 间互斥、is_ocr 标记时机逐字保留；**Pass 2 仍是"命中即标 is_ocr、渲染失败仅丢 entry"**）；
- entry 收集：每 Pass 用按页索引的 slot 数组（页间不相交无锁），Pass 后按页序合并 → `_ocr.json` entry 顺序与串行版完全一致；
- `try { ... } finally { screenshotPool.shutdown(); }`；
- 渲染仍每页 `Loader.loadPDF`（热缓存 10~40ms 不值得做共享文档——`PDFRenderer` 非线程安全，共享 PDDocument 并行渲染不可行）。

### 4. 线程上限配置（方案 B：cap + 页数微调）

- `PdfProperties` 新增 `threads`（`pdf.threads`，`@DefaultValue("4")`，Javadoc 写明 worst-case ≈ `pulsar.count × pdf.threads` 及 33MB/线程光栅的取值依据）；
- `PdfProcessService.process()` 与 `processForPulsar()` 两处 `Config` 构建点 `config.setThreads(pdfProperties.threads())`（rebuildBookmarks 链路不走并行池，不设）；
- 8 个 `application*.yml` 的 `pdf:` 块统一加 `threads: 4`（prod / prod-hjs / prod-hjsinc / prod-inc / prepub-hk / prepub-sz / test / dev）；
- core 微调：`DocumentProcessor` 并行度 = `Math.max(1, Math.min(config.getThreads(), totalPages))`；`JsonWriter` 截图池 = `Math.max(1, Math.min(threads, data.size()))`——3 页文档不再开 4 线程的池。

---

## 验证

- 单测：并行化后 processors 系 **59/59**（BarChart 6、ConsecutiveImage 6、DocumentProcessorPropagation 3、Flowchart 9、LineArt 6、ShapeRecognizer 22、ProcessingDeadline 7）；OCR JSON 系 **20/20**（JsonWriterTableCellGrouping、CatalogBookmark、Propagation、LineArt、Deadline）；线程配置后合并跑 **42/42** 全绿。
- E2E（`DebugSample1` 临时 `setThreads(8)`，跑完已还原）：
  - 饼图样本 `354cb7d4-...-18.pdf`：worker 正常写出 `imageFile1.png`（36KB）且 JSON 引用正确——验证图片写入在 worker 上链路完整；
  - **418 页毒消息文件：全程 6m23s（extraction 2m09s + outputs 4m14s）；OCR 截图阶段 416 页 3m56s vs 原 24m11s ≈ 6.2×**；`_ocr.json` 416 entries、416 张 PNG、主 JSON 13.8MB——产物与串行版逐面一致。
- 构建：core `mvn install`；server 编译需 JDK 17（本机为 `D:\Applications\Java\jdk-17.0.5`；旧记忆写的 jdk-17.0.0.1 已不存在）。

---

## 关键决策（Key Decisions）

1. **imageIndex 改 AtomicInteger 而非"每 worker 分配号段"**：所有调用方要的本来就是全局唯一序号，ThreadLocal 是历史误会；AtomicInteger 是最小正确解。
2. **veraPDF ImagesUtils 不做传播**：字节码证实惰性自建，每 worker 自持独立 PDDocument 正是该 ThreadLocal 的设计意图（veraPDF 自己并行页处理也靠它）；worker 实例随线程死亡由 GC 回收（文件句柄经 finalizer 释放），接受这个与 veraPDF 一致的取舍。
3. **`_ocr.json` entry 保序**：slot 数组 + 按页序合并，避免并行后 entry 乱序影响下游。
4. **超时原样上抛**：`runScreenshotPass` 剥壳时 `ProcessingTimeoutException` 不包进 generic IOException，保证 Pulsar 看门狗的"超时 vs 失败"分类在 OCR 阶段不被破坏。
5. **不改 DPI（300）与 haveStreamTables 误报判定**：前者是 OCR 质量业务决策，后者是识别策略调整，均需回归集，留给后续。
6. **并行度 cap 放 server yml 默认 4**：pod 8 核 × 7 consumer 下 4 是吞吐/内存的平衡点；`min(cap, 页数)` 微调避免小文档浪费线程。
7. **DebugSample1 的所有临时改动（setPages/setThreads/切换目标 PDF）均已还原**。

## 已知遗留 / 后续建议

- `renderPage` Javadoc "200 DPI" 与代码 300 不一致；如需降 DPI（如 200，渲染面积约 44%，24min→~11min 级别收益）需 OCR 侧确认质量。
- 垃圾文本层导致的 `have_stream_table` 误报（416/418 页）值得在 `StreamTableProcessor` 侧加保护，但要回归验证。
- `writeToCustomJson` 里 OCR 阶段之后的书签处理阶段仍无 deadline 检查点（既有行为，本轮未扩）。
- 生产生效前提：8 个 yml 已配 `threads: 4`，**发版后需确认镜像里的 yml 生效**，并观察 pod CPU/堆（截图渲染每线程 ~33MB 光栅）。

## 环境/工具备忘（本轮踩坑）

- **EditFile 对同一文件的并行批量编辑会丢补丁**（两个 edit 同块调用时后写覆盖先写）：`JsonWriter` 的 finally 块、`PdfProcessService` 的第二处 setThreads 都因此丢过一次——同一文件多处修改必须串行，改完用 `git diff` 复核。
- EditFile 长 original 匹配失败（疑似不可见字符）：改用"小锚点替换 + PowerShell 按行号删多余块"组合完成 Pass 2 搬迁；`[System.IO.File]::ReadAllLines/WriteAllLines` 按行操作稳定。
- PowerShell：`&&` 不可用；`Select-String -Path <dir>` 拒绝目录（用 `Get-ChildItem -Recurse | Select-String`）；mvn 的 `-Dtest=A,B` 逗号会被 PS 拆数组（写 .bat 绕过）；测试日志走 `.bat > log` 避免 NativeCommandError 噪音。
- shaded CLI jar：`java/opendataloader-pdf-cli/target/opendataloader-pdf-cli-0.0.0.jar` 含全部依赖与 DebugSample1，改完 core 需 `mvn -pl opendataloader-pdf-cli -am package -DskipTests -DskipITs`。
- 微基准技巧：把 RenderBench.java 放 tmp_output，`javac -cp shaded.jar` + `java -cp shaded.jar;.` 即可直接调用 PDFBox，无需搭工程。

## 相关文件

- `java/opendataloader-pdf-core/src/main/java/org/opendataloader/pdf/processors/DocumentProcessor.java`（chart/formula 循环并行化 + 页数微调 + FORMULA_SCAN_LINE_ART_CHUNK_LIMIT）
- `java/opendataloader-pdf-core/src/main/java/org/opendataloader/pdf/containers/StaticLayoutContainers.java`（imageIndex → AtomicInteger）
- `java/opendataloader-pdf-core/src/main/java/org/opendataloader/pdf/json/JsonWriter.java`（writeOcrDetectionJson 并行化 + runScreenshotPass + 三个 entry builder）
- `java/opendataloader-pdf-server/src/main/java/org/opendataloader/pdf/server/PdfProcessService.java`（两处 setThreads）
- `java/opendataloader-pdf-server/src/main/java/org/opendataloader/pdf/server/config/PdfProperties.java`（threads 字段）
- `java/opendataloader-pdf-server/src/main/resources/application*.yml` ×8（pdf.threads: 4）
- 证据产物：`tmp_output/e2e_parallel.log`（并行 E2E）、`tmp_output/debug_p1.log`、`tmp_output/pie_e2e.log`、`tmp_output/test_parallel.log`、`tmp_output/test_threads.log`
- 关联记忆：`docs/memory/2026-09-15-Pulsar消费停摆根因-单条超大扫描件占满消费槽位与超时看门狗实现.md`（毒消息原始定性 + 看门狗）
