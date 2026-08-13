# opendataloader-pdf 任务记忆 — 2026-08-13（PaddleOcrProcessor 调用加重试机制 — StreamTableProcessor + LineArtProcessor）

- 时间：2026-08-13
- 任务：为 `StreamTableProcessor.processStreamTables` 与 `LineArtProcessor.processLineArtGroups` 中两处 `PaddleOcrProcessor.getPaddleResponse` 调用加重试机制（失败睡 3 秒、最多重试 3 次、睡秒数与重试次数为常量、最终失败打 ERROR 日志不抛异常）。
- 状态：已实现并编译通过（`mvn -pl opendataloader-pdf-core -am compile`：BUILD SUCCESS，150 源文件）。

## 目标（Goal）
- `PaddleOcrProcessor.getPaddleResponse` 是远程 HTTP 调用（`OkHttpClient`，连接/读/写超时 60 秒，定义在 `PaddleOcrProcessor.java:51-58`），受网络抖动、Paddle 服务端瞬时不可用影响会抛 `IOException`。
- 原代码两处调用点直接抛异常，会让整页解析失败。
- 期望：失败后透明重试，最终失败也要降级（不中断整条解析管线），同时保持 contents 不被破坏。

## 根因 / 设计依据（Root Cause / Design Rationale）
- **为什么加重试**：HTTP 调用天然有瞬时失败（连接超时、5xx、网络丢包）。一次失败不应当让整页 OCR 退化为异常退出。
- **为什么固定 3 次 + 3 秒**：与用户约定（3 次 / 3 秒）足够覆盖大部分瞬时故障，单页最坏延迟增加 ≈ 6 秒（首失败 → 睡 3 秒 → 二失败 → 睡 3 秒 → 三失败 → 放弃），是可接受的代价。
- **为什么不抛异常**：管线是 PageProcessor 串行处理，一页 OCR 失败不能让 PDF 解析整体失败；按业务侧约定"contents 保持不变"降级即可。
- **为什么 ERROR 级而非 WARNING**：3 次重试用尽说明 Paddle 服务侧或网络存在持续问题，需要运维感知；同时只打一条 ERROR，不刷屏。
- **为什么常量本地化（方案 A）而非放在 `PaddleOcrProcessor`（方案 B）**：两个调用点的重试策略属于调用方策略，不应污染 PaddleOcrProcessor 的公共 API；3/3 是局部配置而非全局契约。
- **为什么 LineArtProcessor 在 `formulaChunk==null` 时撤销合并用 `ImageChunk` 之外的路径**：合并本身依赖 OCR 来决定是否换公式；OCR 没识别到公式 → 合并没价值 → 恢复原状最自然；保留原始 LineArtChunk 还能让后续阶段（如 SemanticHeaderOrFooter 之外的 shape/text 链路）继续处理。
- **为什么撤销合并后还要按 `topY` 降序排**：函数开头已 `pageContents.sort(Comparator.comparingDouble(IObject::getTopY).reversed())`，`group` 里的 backward neighbors 是从 `result` 末尾逆序拉出来的，相对顺序与 topY 降序不再严格一致；addAll 之前局部排一下，保持与入口处一致的不变量。

## 需求澄清过程（Clarifications）
用户首次提需求时有两处含糊，先向用户确认后才动手：

1. **重试常量放哪里**
   - 方案 A（采纳）：两个类各放一份 `private static final`
   - 方案 B：在 `PaddleOcrProcessor` 中暴露 `public static final`，让两个调用方复用
   - 选择 A 的理由：避免给 `PaddleOcrProcessor` 加语义外的公共 API，调用方策略应在调用方定义。

2. **LineArtProcessor 中"OCR 成功但 formulaChunk 为空"时"使用原始数据，不使用 ImageChunk"具体指什么**
   - 方案 1（采纳）：把 `group` 里的所有原始元素（含 backward neighbors + current + forward neighbors）按原顺序全部 `result.addAll(group)`，相当于撤销这次合并。
   - 方案 2：只把 `current`（LineArtChunk）放回 `result`，丢弃 backward neighbors（会丢失从 result 拉出来的邻居元素，破坏数据完整性，不合理）。
   - 选择 1 的理由：合并前 backward neighbors 已被从 `result` 拉出，如果不放回就丢失；forward neighbors 还没处理，需要 addAll 才能保留。`group` 的内部顺序 = `[向后邻居(原序)] + [current] + [向前邻居]`，addAll 后顺序与原 pageContents / result 中的相对位置一致。
   - **后续追加（用户第二轮要求）**：addAll 之前按 `topY` 降序排 group，对齐入口处排序不变量。

## 实现（Implementation）

### `StreamTableProcessor.java`
- 加常量（`:37-41`）：
  ```java
  /** Maximum number of attempts (initial + retries) when calling Paddle OCR. */
  private static final int PADDLE_MAX_RETRIES = 3;
  /** Sleep duration between failed Paddle OCR attempts, in seconds. */
  private static final long PADDLE_RETRY_SLEEP_SECONDS = 3L;
  ```
- 加辅助方法 `callPaddleWithRetry`（`:79-105`）：
  - 循环 `for (attempt=1; attempt <= PADDLE_MAX_RETRIES; attempt++)`，最多 3 次
  - 失败时 `LOGGER.log(Level.WARNING, "Paddle OCR call failed (attempt X/3) ...")` + 记录 `lastException`
  - 第 1、2 次失败后 `Thread.sleep(3000)`（秒 × 1000）
  - sleep 被中断时 `Thread.currentThread().interrupt()` + WARNING + `break`（不再继续后续重试）
  - 3 次全失败：`LOGGER.log(Level.SEVERE, "Paddle OCR call failed after 3 attempts ...")` + 返回 `null`
  - **关键**：所有路径都不抛异常给上层
- 替换调用点（`:55-66`）：
  ```java
  File singlePageImageFile = extractSinglePageImage(pdfPath, pageNumber);
  TextInOcrAnalysisResultDto textInOcrAnalysisResultDto = callPaddleWithRetry(
      singlePageImageFile, 1, paddleUrl, pageNumber, pdfPath);
  if (textInOcrAnalysisResultDto == null) {
      // 重试全部失败：删除临时文件并返回原始 contents，不抛异常
      singlePageImageFile.delete();
      return new ArrayList<>(contents);
  }
  PageItemResultDto pageItemResultDto = PaddleOcrResultUtils.generateJsonResultByTextInOcrAnalysisResultDto(
      singlePageImageFile, textInOcrAnalysisResultDto, width, height, pageNumber);
  singlePageImageFile.delete();
  return replaceStreamTables(pageItemResultDto, contents);
  ```
- 加 `import java.util.logging.Level;`

### `LineArtProcessor.java`
- 加常量（`:52-56`）：同 StreamTableProcessor
- 加辅助方法 `callPaddleWithRetry`（`:187-209`）：结构与 StreamTableProcessor 同型，差异在日志文案（"for image chunk: ..." + "falling back to ImageChunk"）
- 替换调用点（`:139-171`）三分支：
  ```java
  if (paddleUrl != null && !"".equals(paddleUrl)) {
      String imageFileName = String.format(MarkdownSyntax.IMAGE_FILE_NAME_FORMAT, ...);
      TextInOcrAnalysisResultDto textInOcrAnalysisResultDto =
          callPaddleWithRetry(new File(imageFileName), 1, paddleUrl);
      if (textInOcrAnalysisResultDto != null) {
          LOGGER.log(Level.INFO, "Text in ocr analysis result: {}", textInOcrAnalysisResultDto);
          TextChunk formulaChunk = tryCreateFormulaTextChunk(textInOcrAnalysisResultDto, union);
          if (formulaChunk != null) {
              // ...原有 fontSize 逻辑...
              replacement = formulaChunk;
          } else {
              // OCR 成功但未识别到公式：撤销合并，按 topY 降序排后放回 result
              group.sort(Comparator.comparingDouble(IObject::getTopY).reversed());
              result.addAll(group);
              continue;
          }
      }
      // null = 重试全部失败：ERROR 日志已在 callPaddleWithRetry 中打印，replacement 保持为 imageChunk
  }
  result.add(replacement);
  continue;
  ```
- 三分支语义：
  - **OCR 重试全失败** → `replacement` 保持初始值 `imageChunk`，按原逻辑 `result.add(replacement)`（**降级保留截图**，符合用户要求"使用 ImageChunk"）
  - **OCR 成功 + formulaChunk 非空** → `replacement = formulaChunk`（公式替换，保持原行为）
  - **OCR 成功 + formulaChunk 为空** → `result.addAll(group); continue;`（**撤销合并，使用原始数据**）
- 必要导入已就位：`IObject`、`Comparator`、`Level` 都在原文件里已 import；`IOException` 因辅助方法内仍需捕获而保留。

## 关键决策（Key Decisions）
- 失败 ERROR 日志用 `Level.SEVERE`（ERROR 等价于 SEVERE 在 JUL 中）。告警 WARNING 在每次失败时打一次。
- `Thread.sleep` 受 `InterruptedException` 时按标准做法恢复中断标志 + break，不再继续后续重试（避免无意义的等待）。
- `result.addAll(group)` 之前按 `topY` 降序排序 group（仅 group，不动 result 已有元素），最小代价保持排序不变量。
- StreamTableProcessor 中临时 PNG 文件 `singlePageImageFile.delete()` 的位置：必须在 `null` 分支提前删（避免泄漏），成功路径放在 `generateJsonResultByTextInOcrAnalysisResultDto` 调用之后（避免函数拿到已删除文件引用）。
- **本次实现中发现并修复的一个 bug**：第一次编辑 StreamTableProcessor 时，不小心把 `singlePageImageFile.delete()` 提前到了 null 检查之前，导致成功路径下 `generateJsonResultByTextInOcrAnalysisResultDto` 拿到已删除文件引用；二次编辑修正——`delete()` 改回放在 `generateJsonResultByTextInOcrAnalysisResultDto` 之后（成功路径），`null` 分支内单独 `delete()`。

## 验证结果
- `mvn -pl opendataloader-pdf-core -am compile -DskipTests`：✅ BUILD SUCCESS（150 源文件，含本次修改）。
- 编译告警（deprecation、unchecked）来自其他文件（`PaddleOcrResultUtils.java`、`JsonWriter.java`），与本次改动无关。
- 行为矩阵（用户确认的方案 A+1）：
  - StreamTableProcessor OCR 3 次全失败 → 删临时文件 → `return new ArrayList<>(contents)`（contents 原样）
  - StreamTableProcessor OCR 成功 → 走原 replaceStreamTables 流程
  - LineArtProcessor OCR 3 次全失败 → `replacement` 保持 `imageChunk`，加入 result（ImageChunk 兜底）
  - LineArtProcessor OCR 成功 + formulaChunk 非空 → formulaChunk 替换
  - LineArtProcessor OCR 成功 + formulaChunk 为空 → 撤销合并，`group` 按 topY 降序后 `result.addAll(group)`

## 构建 / 运行
- 编译：
  ```bash
  cd D:\Code\JavaCode\opendataloader-pdf-parse\opendataloader-pdf\java
  mvn -pl opendataloader-pdf-core -am clean compile -DskipTests
  ```
- 暂未跑单测（本次改动不引入新单测场景，可后续补：模拟 Paddle 调用失败的 mock 测试）。

## 相关文件（Relevant Files）
- `java/opendataloader-pdf-core/src/main/java/org/opendataloader/pdf/processors/StreamTableProcessor.java`
  - `:29` 新增 `import java.util.logging.Level;`
  - `:37-41` 新增 `PADDLE_MAX_RETRIES`、`PADDLE_RETRY_SLEEP_SECONDS` 常量
  - `:55-66` `processStreamTables` 调用点替换
  - `:72-105` 新增 `callPaddleWithRetry` 辅助方法
- `java/opendataloader-pdf-core/src/main/java/org/opendataloader/pdf/processors/LineArtProcessor.java`
  - `:52-56` 新增常量
  - `:139-171` `processLineArtGroups` 调用点替换为三分支
  - `:180-209` 新增 `callPaddleWithRetry` 辅助方法
- `java/opendataloader-pdf-core/src/main/java/org/opendataloader/pdf/processors/PaddleOcrProcessor.java`
  - 调用方未修改；`getPaddleResponse(File, Integer, String)` 签名与 `throws IOException` 不变，仍向外抛 IOException，由两个新增的 `callPaddleWithRetry` 包裹。

## 备忘
- 两份重试常量数值相同但分别定义：后续若需统一调整策略，可提到 `PaddleOcrProcessor` 或新建 `PaddleRetryPolicy` 工具类。当前不做重构，保持改动局部化。
- StreamTableProcessor 与 LineArtProcessor 各自一份 `callPaddleWithRetry` 复制（仅日志文案差异）：若后续第三个调用点也加重试，建议提取为公共工具类。当前不做。
- `Thread.sleep` 在 `ForkJoinPool` worker 线程上的影响：根据 CLAUDE.md 提示，处理使用 `ForkJoinPool(availableProcessors)` 并行；`Thread.sleep` 会阻塞该 worker 线程，可能拖累并发度。当前每页最坏多 6 秒，若成为瓶颈可改为基于 `ScheduledExecutorService` 的异步重试。当前不做。
- 本次改动未同步 `npm run sync`（按 CLAUDE.md 提示，CLI option 改动才需要 sync；本次只改 Java 内部行为，CLI 选项未动，故无需 sync）。
