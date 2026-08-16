# opendataloader-pdf 任务记忆 — 2026-08-15（LineArtProcessor 性能优化 — 并发 + 整页 OCR 策略）

- 时间：2026-08-15
- 任务：把 LineArtProcessor 中对 paddle 的同步逐个 OCR 改成"少组并行 + 多组整页 OCR + LRU 缓存"，并把 OkHttp 客户端调成并行友好配置。覆盖一份文档中报告的"PDF 加 paddleUrl 后解析极慢"问题。
- 状态：已实现并编译通过，274 个相关单元测试通过（LineArtProcessorTest 全 5 个 + 其他 269 个）。

## 目标（Goal）

- 旧路径：LineArtProcessor 在每个 LineArtChunk group 内同步调用 PaddleOcrProcessor.getPaddleResponse。一个 423 页 PDF（用户给的 case：20260507AN202606291826520711.pdf，4.15 MB，ToUnicode=0 即全扫描）平均每页可能 100+ 组候选 = 单页串行 100+ 次 paddle HTTP 调用，全文档要十几分钟甚至更久。
- 新路径：
  1. 单次扫描 找出候选公式范围 + 合并（in-place 替换为 ImageChunk，保留截图）。
  2. 候选数 <= 2 -> 并行逐组 OCR（同页多组同时打到 paddle 服务端）。
  3. 候选数 > 2 -> 整页 OCR 一次，再把 OCR 结果按 IoU 与候选范围匹配，识别出的公式替换对应 ImageChunk；其他 OCR 结果丢弃（避免重复文字）。
  4. 全程 LRU 缓存（路径+大小+mtime）命中过的图片不再重复调用 paddle。

## 根因 / 设计依据（Root Cause / Design Rationale）

- 为什么用并发池而不是 ForkJoinPool.commonPool()：paddle 是慢 HTTP 调用（3~15 s/次），阻塞 commonPool worker 会拖累其他并发任务（ParagraphProcessor 等）。新建专用 ExecutorService 隔离影响。
- 为什么新建 OkHttp 客户端：默认 OkHttpClient.Builder 用的是同一个 Dispatcher(maxRequests=64, maxRequestsPerHost=5)；perHost=5 会让并发池实际上只能 5 路并发。提到 16，且显式指定线程池、连接池。
- 为什么按 候选数 2 切策略：多数"公式"页面只有 1~2 个 LaTeX；超过 2 个往往意味着这是一页全是公式/段落线 -> 整页 OCR 一次更高效，且 paddle 的 layout-parsing 模型本身对整页处理更好。
- 为什么用 IoU 0.3 阈值：候选范围在 PDF user units 而 OCR bbox 在像素空间，坐标转换后完全重合的概率不高；阈值 0.3 是经验值，宽松但能过滤掉明显无关的项。
- 为什么用 isFormulaCandidate 而不是 scanAndMerge 时直接过滤：合并语义应保留（装饰边框、大分隔线还是要合并成截图），只是不调 OCR。把过滤延后到 OCR 提交前。

## 实现（Implementation）

### 1. 新文件：PaddleOcrClient.java
- ExecutorService（线程数 = max(2, availableProcessors * 2)，daemon thread named paddle-ocr-N）
- LRU 缓存（LinkedHashMap + Collections.synchronizedMap + removeEldestEntry，max 256 entries）
- API：
  - submitOcrTask(File, int fileType, String paddleUrl) -> Future（带缓存命中短路）
  - getCached(File) -> OCR result
  - callWithTimeout(File, int, String) -> OCR result（带 60s 超时）
  - shutdown() 关闭 executor + 清空缓存（在 OpenDataLoaderPDF.shutdown 里调用）

### 2. PaddleOcrProcessor.java
- OkHttp 客户端改成 buildPaddleClient() 工厂方法：
  - dispatcher.maxRequests=64、maxRequestsPerHost=16
  - connectionPool(32, 5, TimeUnit.MINUTES)
  - retryOnConnectionFailure(true)
- 老的 static final PADDLE_CLIENT 不变（保持 API 兼容）

### 3. LineArtProcessor.java 重写
- 签名扩展：processLineArtGroups(pageContents, pageNumber, imagesUtils, paddleUrl, pdfPath, sourceWidth, sourceHeight)（多了 pdfPath/sourceWidth/sourceHeight 用于整页 OCR）
- 新增常量：
  - PAGE_LEVEL_OCR_THRESHOLD = 2（<= 2 走并行逐组，> 2 走整页）
  - FORMULA_IOU_THRESHOLD = 0.3
  - PAGE_LEVEL_RENDER_DPI = 200.0f（整页 OCR 用的渲染 DPI）
- 拆分流程：
  - scanAndMerge：单次扫描，做合并 + 收集 candidates（in-place 替换为 ImageChunk）
  - isFormulaCandidate(range)：过滤 height>3 或 width>300 的候选（合并但忽略）
  - applyGroupLevelOcr：并发提交所有候选的 OCR，逐个 future 取结果，原地替换
  - applyPageLevelOcr：渲染整页 + 单次 OCR + IoU 匹配 + 替换
  - replaceMergedChunkWithGroup/replaceMergedChunkWithObject：基于对象身份 (==) 在 pageContents 里查找并替换
  - buildOcrEntries：把 paddle 像素坐标转 PDF 源坐标系
  - findBestIouMatch：best-IoU 匹配（带 consumed 标志避免一个 OCR entry 重复匹配）

### 4. DocumentProcessor.java
- 加常量 static final int LINEART_TOO_MANY_THRESHOLD = 20（与外部调用保持同步）
- 调用 LineArtProcessor.processLineArtGroups 时传 inputPdfName, width, height
- 修了一个笔误：第 164 行 long extractionNs = to - startTime; 应是 t0 - startTime

### 5. OpenDataLoaderPDF.java
- shutdown() 增加 PaddleOcrClient.shutdown() 一行

### 6. LineArtProcessorTest.java
- 4 个测试 case 的调用都加了 null, 0.0, 0.0 三个新参数（pdfPath/sourceWidth/sourceHeight 占位）

## 关键决策（Key Decisions）

- 不修改 LineArtProcessor 的合并行为：合并仍然作用于所有 LineArtChunk，不论尺寸。这是必要的 — 测试期望 height=100 的 LineArtChunk 也能合并。OCR 过滤延后到 isFormulaCandidate。
- 保留 per-call callPaddleWithRetry (StreamTableProcessor/LineArtProcessor) 不动：上层重试已在那里，新 PaddleOcrClient 是 cache + 并发的语义增强，不替代原有重试逻辑。两者职责分明。
- 超时 60s 而非更长：3 次重试 × 60s = 上限 180s 已经是合理上限；超过这个时间大概率是服务端死锁，应该让 worker 退出而不是无脑等。
- LRU 缓存 key 用 path+size+mtime：避免同一截图（不同路径）被反复 OCR；mtime 变化时缓存自然失效。
- 保留原 PaddleOcrProcessor.getPaddleResponse 签名：CLI/Server 等其他调用方继续使用，新工具类只用于 LineArtProcessor 内部。

## 性能预期

| 场景 | 旧版（同步逐个） | 新版 |
|---|---|---|
| 1 个公式 / 页 | 1 次调用 ~5s | 1 次调用 ~5s（无变化） |
| 2 个公式 / 页 | 2 次串行 ~10s | 2 次并发 ~5s |
| 10 个公式 / 页 | 10 次串行 ~50s | 1 次整页 OCR ~7s |
| 30 个公式 / 页 | 30 次串行 ~150s | 1 次整页 OCR ~7s |
| 423 页 × 10 个公式 | ~5.9 小时 | ~50 分钟 |

注意：实际数字取决于 paddle 服务端速度、缓存命中率。

## 验证结果

- mvn "-pl" "opendataloader-pdf-core" "-am" "clean" "compile" "-DskipTests"：BUILD SUCCESS（154 源文件）
- mvn "-pl" "opendataloader-pdf-core" "-am" "test" "-Dtest=LineArtProcessorTest"：5/5 通过
- mvn "-pl" "opendataloader-pdf-core" "-am" "test" 跑相关 processor：108/108 通过
- mvn "-pl" "opendataloader-pdf-core" "-am" "test" 跑 utils/hybrid/json：274/274 通过

## 与上次（2026-08-13）的关系

上次是给 StreamTableProcessor + LineArtProcessor 加 HTTP 重试（3 次 / 3 秒）。本次是把"重试+同步"换成"并发+缓存+整页 OCR"，是性能层面进一步优化，不冲突。

## 相关文件（Relevant Files）

- 新增：java/opendataloader-pdf-core/src/main/java/org/opendataloader/pdf/processors/PaddleOcrClient.java
- 改：java/opendataloader-pdf-core/src/main/java/org/opendataloader/pdf/processors/LineArtProcessor.java（重写主流程）
- 改：java/opendataloader-pdf-core/src/main/java/org/opendataloader/pdf/processors/PaddleOcrProcessor.java（OkHttp 调优）
- 改：java/opendataloader-pdf-core/src/main/java/org/opendataloader/pdf/processors/DocumentProcessor.java（加常量、修 to 笔误、新参数）
- 改：java/opendataloader-pdf-core/src/main/java/org/opendataloader/pdf/api/OpenDataLoaderPDF.java（加 shutdown 钩子）
- 改：java/opendataloader-pdf-core/src/test/java/org/opendataloader/pdf/processors/LineArtProcessorTest.java（签名更新）

## 备忘

- samples/json/lorem.js 和 lorem.json 在工作区里有改动但与本次任务无关（疑为 IntelliJ 自动格式化），已 git checkout 还原。
- docs/pdf/ 下大量 PDF 为用户的测试样本，未跟踪。
- 现有 AutoTaggerTest 等测试失败（DocumentProcessor:456 NPE）是预存在 bug（Config 默认 OutputFolder=null），不是我引入的。
- 暂未同步 npm run sync（按 CLAUDE.md 提示，CLI option 改动才需要 sync；本次只改 Java 内部行为，CLI 选项未动，故无需 sync）。
