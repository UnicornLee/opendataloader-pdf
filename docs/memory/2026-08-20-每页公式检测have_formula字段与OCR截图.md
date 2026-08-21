# 每页公式检测 `have_formula` 字段 + OCR JSON 截图透传

- 时间：2026-08-20
- 任务：基于上一轮已落地的 `have_stream_table` 流水线，新增 `have_formula` 字段。每页"是否包含公式候选区域"的检测挂到 `DocumentProcessor#processDocument` 的 **Loop 4**，**替换**原本对 `LineArtProcessor#processLineArtGroups` 的调用（重写页面内容、跑 Paddle OCR）—— 改为轻量级 `LineArtProcessor#haveFormulas`（仅返回 boolean，不重写 `pageContents`），把结果以 `pageHaveFormulas: boolean[]`（按 0-based 页码索引）通过 `ExtractionResult` → `processFileWithResult` → `generateCustomOutputs` → `JsonWriter` 整条链路透传，并在两层 JSON 中落地：
  1. 主 JSON 每页追加 `have_formula` 字段（true/false）；
  2. `_ocr.json` 对所有 `have_formula=true` 的页面渲染 PNG 截图，按 `ossEnabled` 走本地保存或上传到 OSS **temp 桶**，entry 中带 `have_formula=true`；
  3. **与 `have_stream_table` 互斥**：同一页若已被 `have_stream_table` 标记，则由 `have_stream_table` 占用 entry 槽位，`have_formula` 跳过；下游消费者看到的是"每页最多一条 entry"。
- 状态：已实现 + `mvn package` 在 `opendataloader-pdf-core / opendataloader-pdf-cli / opendataloader-pdf-server` 三模块均 BUILD SUCCESS（exit 0）。
- 附带修复：上一轮 stream-table 任务遗留的 `StreamTableProcessor.existStreamTableexistStreamTable(...)` 方法名重复 typo，导致上轮代码也无法编译；本轮顺手改为 `existStreamTable` 后才使整库 BUILD SUCCESS。
- **设计决策记录**：初版计划把 `haveFormulas` 挂在 Loop 2（与 `haveStreamTables` 并行），但用户最终决定改为挂 Loop 4 并替换 `processLineArtGroups`，理由是 Loop 4 已经是 sequential 的公式处理点，让"轻量检测"直接接在原本的"重量重写"位置更连贯；Loop 2 不动 `haveStreamTables`。

## 目标（Goal）

- 把昂贵的 Paddle 公式 OCR 从"Loop 4 无差别全跑"降级为"Loop 4 只标记、由下游按需触发"，把 OCR 真正要做的页面 rewrite 留给外部消费者；与 stream-table 的检测/重写解耦思路一致，但**挂点不同**（stream-table 在 Loop 2，formula 在 Loop 4 替换原 `processLineArtGroups`）。
- 在不破坏现有 `_ocr.json` 结构的前提下，新增"公式页面截图"作为第二种 entry 类型，下游 OCR 工具可凭 `have_formula` 字段区分。
- 用户约束："有不清楚的地方需要向我澄清；先提供方案，我同意后才能修改代码"（计划阶段已通过 4 项澄清问答）。
- 用户**后续**指示（实施过程中变更）：将原 Loop 2 的 `haveFormulas` 块**还原**到调用 `haveStreamTables` 的形态；将公式检测**移到 Loop 4**，**替换**原来的 `LineArtProcessor.processLineArtGroups(...)` 调用，让 Loop 4 不再触发任何 Paddle OCR。

## 用户偏好与约束（Constraints & Preferences）

1. **公式检测挂 Loop 4，替换 `processLineArtGroups`**：原 Loop 4 调用 `LineArtProcessor.processLineArtGroups(...)` 会真正重写 `pageContents`（合并 LineArtChunk 写截图、走 Paddle、识别公式后替换为 TextChunk）。本轮改为调用 `LineArtProcessor.haveFormulas(...)`，仅返回 boolean，不重写 `pageContents`，让 Loop 4 不再做任何公式 OCR。
2. **Loop 2 无线表格保持不动**：`haveStreamTables` 块（Loop 2 内）原样保留，不在 Loop 2 旁边加并行的 `haveFormulas` 块。
3. `have_formula` 字段同时出现在主 JSON（每页）和 `_ocr.json`（entry 级）。
4. 截图存放：复用现有 `outputFolder/{baseName}_images/` 目录；本地文件名 `{baseName}_formula-{n}.png`（前缀 `_` 对齐 `cleanupLocalFiles` 的 `name.startsWith(pdfBaseName + "_")` 白名单）。
5. OSS 桶选择：与主 JSON / stream-table 一致 → **temp 桶**（`ossTempBucketName` + `getTempDomainName()`），**不是**永久桶。
6. 截图源 PDF 用 `inputPdfName`（绝对路径），不要读 `map.get("url")` —— `url` 可能是云端 URL，会渲染失败。
7. `writeOcrDetectionJson` 中 `pdfFileName` 由 `inputPdfName` 派生，不需要单独传入。
8. Java 源版本是 11，`record` 关键字不可用 → `ProcessDocumentResult` 维持普通 `private static final class` + getter。
9. Loop 4 是 sequential `for` 循环（不在 ForkJoinPool 内），所以 `pageHaveFormulas[pageNumber] = ...` 直接写即可，无需并发保护；`propagateState` 也无需扩展。
10. Backward compatibility：`TaggedDocumentProcessor` / `HybridDocumentProcessor` 路径不跑 Loop 4，传 `null`，下游遇到 `null` 视作"无信息"，每页 `have_formula` 写 `false`。

## 前置事实（已核实）

- `LineArtProcessor#haveFormulas(...)` 早就存在（L224-329），但没有 JavaDoc，且分支结构混乱：`if (basicFormulaRecognize) { return true; }` 后紧跟一行 unreachable 的注释，else 分支又先 `restoreAllGroups` 再 log 再 return false，不平行于 `processLineArtGroups` 的结构。本轮按 plan 重新整理了 body（JavaDoc + 整理分支）。
- `haveFormulas` 内部已调用 `scanAndMerge`，会通过 `imagesUtils.saveImageChunk(imageChunk)` 写临时 PNG，但 `restoreAllGroups` 不会清；这是已知 trade-off（同 `processLineArtGroups` 在 basicFormulaRecognize=false 时也写不清理）。
- `DocumentProcessor#processDocument` Loop 4 原代码（L702-703）调用 `LineArtProcessor.processLineArtGroups(...)`，会**实际跑 Paddle OCR 并改写 `pageContents`**。本轮改为 `haveFormulas` 后，这一行不再触发任何 OCR。
- `ExtractionResult` 现有 3 个构造函数（4/5/6 参），上一轮把 5 参扩展为含 `pageHaveStreamTables`，本轮把 6 参扩展为同时含 `pageHaveStreamTables + pageHaveFormulas`，老 5 参和 4 参继续向后兼容（`null` 委托）。
- `JsonWriter#writePageToGenerator` 序列化顺序：`page_index / width / height / is_ocr(false) / have_stream_table / have_formula / margins / items`。
- `JsonWriter#placeholderPageJson` 之前只写 `{page_index, is_ocr, have_stream_table, items}`，本轮追加 `have_formula: false`。
- `JsonWriter.writeOcrDetectionJson` 上一轮已新增 Pass 1（stream-table 截图），本轮在同一函数内紧跟其后加 Pass 1.5（公式截图），互斥规则由 Pass 1.5 自身跳过 stream-table 已占用页实现。
- `JsonWriter.OssUploadConfig.getTempBucketName()` / `getTempDomainName()` 与 `uploadFile` 已存在；复用即可。
- `cleanupLocalFiles` 中 `isRelatedToCurrentPdf` 白名单 `name.startsWith(pdfBaseName + "_")`，所以新文件名 **必须** 以 `{baseName}_` 开头才能被自动清理；采用 `{baseName}_formula-{n}.png`。
- `org.verapdf.pd.PDDocument` 与 `org.apache.pdfbox.pdmodel.PDDocument` 同名冲突，新代码用 fully-qualified `org.apache.pdfbox.pdmodel.PDDocument`（与 stream-table 一致）。
- Maven 源目标：`maven.compiler.source=11`（pom.xml 已确认），Java 17 运行时但编译目标是 Java 11。

## 已实现方案

### 改动 1：`LineArtProcessor.java`（Step 1）
- 给 `haveFormulas` 加完整 JavaDoc：明确说明它是 `processLineArtGroups` 的"轻量检测孪生"，不跑 OCR；返回值的语义；参数作用；返回值何时为 `true`。
- 重排 body 让 `restoreAllGroups(pageContents, candidates)` 无条件在分支前执行；`if (basicFormulaRecognize)` 走 `return true`；else 分支只 log + return false，移除原先 `return true` 之后那行 unreachable 注释。
- 不改签名，不加参数。

### 改动 2：`DocumentProcessor.java`（Step 2 / 3）
- 主线程新增 `final boolean[] pageHaveFormulas = new boolean[totalPages];`（紧跟 `pageHaveStreamTables`）。
- **Loop 2 不动**：`haveStreamTables` 块保持原样，没有并行加 `haveFormulas` 块（这是用户后续指示的修正）。
- **Loop 4 替换 `processLineArtGroups` 为 `haveFormulas`**：
  ```java
  if (paddleEnabled) {
      long count = pageContents.stream()
          .filter(c -> c instanceof LineArtChunk && c.getHeight() <= 3 && c.getWidth() <= 300)
          .count();
      LOGGER.log(Level.INFO, "Page {0} - LineArtChunk count with height <= 3 and width <= 300 in pageContents: {1}.",
          new Object[]{pageNumber + 1, count});
      // Per-page lightweight formula detection (no OCR rewrite). The boolean result
      // is exposed via the main JSON's `have_formula` field and surfaces as an entry in
      // `_ocr.json`; this replaces the previous `processLineArtGroups` call here so
      // pageContents is no longer rewritten by this stage.
      try {
          pageHaveFormulas[pageNumber] = LineArtProcessor.haveFormulas(
              pageContents, pageNumber, imagesUtils, paddleUrl,
              inputPdfName, pageWidths[pageNumber], pageHeights[pageNumber], basicFormulaRecognize);
      } catch (Exception e) {
          LOGGER.log(Level.WARNING,
              "haveFormulas failed for page " + pageNumber + " of " + inputPdfName
                  + ": " + e.getClass().getSimpleName() + ": " + e.getMessage(), e);
          pageHaveFormulas[pageNumber] = false;
      }
  } else {
      pageHaveFormulas[pageNumber] = false;
  }
  ```
  - 替换的是 L702-703 原来那行 `LineArtProcessor.processLineArtGroups(...)`；同段保留 `paddleEnabled` gate、`LineArtChunk count` 日志、`imagesUtils` 实例、`ConsecutiveImageProcessor.processConsecutiveImages(...)`（在替换块外）。
  - 不再调 `processLineArtGroups`，所以 `pageContents` 在 Loop 4 不再被公式 OCR 改写；这意味着未来若需要真正替换公式 chunk，需要在别处显式调 `processLineArtGroups`（当前没有这个调用方）。
- `ProcessDocumentResult`（Java 11 不支持 record）扩展为 3 字段、3 参构造：`contents / pageHaveStreamTables / pageHaveFormulas`，对应 3 个 getter。
- `processDocument` 末尾 `return new ProcessDocumentResult(contents, pageHaveStreamTables, pageHaveFormulas);`。
- `extractContents` 同步添加 `boolean[] pageHaveFormulas = null;` 默认值；只在默认 `processDocument` 分支读 `result.getPageHaveFormulas()`；Tagged / Hybrid 分支维持 `null`。
- `extractContents` 末尾构造 `ExtractionResult` 改为 6 参版本：`new ExtractionResult(contents, extractionNs, HybridTimings, remappedMetadata, pageHaveStreamTables, pageHaveFormulas);`。
- `processFileWithResult`（L184）调用 `generateCustomOutputs` 时新增第 6 参数 `extraction.getPageHaveFormulas()`。
- `generateCustomOutputs` 签名同步加 `boolean[] pageHaveFormulas`，转发给 `JsonWriter.writeToCustomJson` 9 参重载。

### 改动 3：`ExtractionResult.java`
- 新增字段 `private final boolean[] pageHaveFormulas;` + getter `getPageHaveFormulas()`。
- 把已有 5 参构造器扩展为 6 参（含 `pageHaveFormulas`）；老 5 参、4 参、3 参构造器继续委托，新构造器把 `pageHaveFormulas` 设为 `null`。

### 改动 4：`JsonName.java`
- 新增 `public static final String HAVE_FORMULA = "have_formula";`（紧跟 `HAVE_STREAM_TABLE`）。

### 改动 5：`JsonWriter.java`（Step 4 / 5 / 6）
- `writeToCustomJson` 加 9 参重载（含 `pageHaveFormulas`）；原 8 参重载（只含 `pageHaveStreamTables`）改为委托到 9 参，传 `null`；原 7 参重载（无两个数组）继续委托 8 参，再委托 9 参。
- `writePageToGenerator` 签名加 `boolean[] pageHaveFormulas`，在 `HAVE_STREAM_TABLE` 之后写 `HAVE_FORMULA`：
  ```java
  pageGenerator.writeBooleanField(JsonName.HAVE_STREAM_TABLE,
      pageHaveStreamTables != null && pageHaveStreamTables[pageNumber]);
  pageGenerator.writeBooleanField(JsonName.HAVE_FORMULA,
      pageHaveFormulas != null && pageHaveFormulas[pageNumber]);
  ```
- `placeholderPageJson` 追加 `placeholder.put(JsonName.HAVE_FORMULA, false);`。
- `writeOcrDetectionJson` 签名扩展：在 `pageHaveStreamTables` 之后加 `pageHaveFormulas`，**插在 ossEnabled 之前**保持"业务参数 + OSS plumbing"的分组关系不变。
- 新增 **Pass 1.5**：紧跟 Pass 1（stream-table）之后，写入 `have_formula=true` 的 entry。**互斥规则**：遍历 `pageHaveFormulas` 时，若 `pageHaveStreamTables != null && i < pageHaveStreamTables.length && pageHaveStreamTables[i]`，continue 跳过该页（stream-table 已占用）。其余渲染 + 上传与 Pass 1 同流程，只是调 `renderFormulaPageScreenshot` 而不是 `renderStreamTablePageScreenshot`。
- Pass 2（image-triggered entries）`HAVE_STREAM_TABLE: false` 之后追加 `HAVE_FORMULA: false`，保持每条 entry 字段一致。
- 新增辅助方法 `renderFormulaPageScreenshot(pdfFile, outputFolder, pdfBaseName, pageNumber, ossEnabled, ossConfig, obsClient)`：
  - 输出目录 `{outputFolder}/{baseName}_images/`，文件名 `{baseName}_formula-{pageNumber}.png`（前缀 `_` 对齐 cleanup 白名单）。
  - 渲染：`Loader.loadPDF(pdfFile)` + `PDFRenderer.renderImageWithDPI(pageNumber, 200f)` + `ImageIO.write`。
  - **必须用 fully-qualified `org.apache.pdfbox.pdmodel.PDDocument`**（与 stream-table 同源）。
  - OSS 开启 → objectKey `public/{basicEnv}/{topic}_{businessId}_formula_{pageNumber+1}.png`，上传到 temp 桶，成功后 `Files.delete` 本地；上传失败回退本地绝对路径（记 WARN）。
  - OSS 关闭 → 返回本地绝对路径。
- `writeToCustomJson` 调用 `writeOcrDetectionJson` 时同步多传一个 `pageHaveFormulas` 参数。
- 不需要新增 import（`ImageIO` / `Loader` / `PDFRenderer` / `BufferedImage` 在 stream-table 那轮已经 import）。

### 改动 6（附带修复）
- `StreamTableProcessor.java` L306 方法名 typo `existStreamTableexistStreamTable` → `existStreamTable`。上一轮 stream-table 任务提交时把方法名拼错了，导致上一轮代码本身就编不过；本轮编译时才暴露，顺手修。

## 验证结果

- `mvn -pl opendataloader-pdf-core,opendataloader-pdf-cli,opendataloader-pdf-server -am package -DskipTests -q` BUILD SUCCESS（exit 0，三个模块全过）。
- 编译输出无 warning。
- 修复 stream-table typo 后整库才进入可编译状态；本轮之前所有 `git diff` 显示的 stream-table 改动其实都没真正编译过，提醒："BUILD SUCCESS" 必须在 `git status` 干净或显式确认后才有意义，不要仅凭"上一轮汇报 BUILD SUCCESS"就跳过本次编译。
- 未补单元测试（用户未要求；code-review 风格风险 MEDIUM）。

## 关键决策（Key Decisions）

- **`haveFormulas` 挂 Loop 4（替换 `processLineArtGroups`）而非 Loop 2**：用户实施过程中明确指示——"无线表格保持不动，公式不在Loop2里改了，替换[processLineArtGroups 调用这段代码]"。原 Loop 4 的 `processLineArtGroups` 既重写 `pageContents` 又跑 Paddle OCR，改为 `haveFormulas` 后 Loop 4 不再做实际 OCR，paddle 调用次数归零；下游按 `have_formula=true` 自行决定要不要触发外部 OCR。
- **`pageHaveFormulas` 数组声明在 main thread，Loop 4 sequential 写入**：与 `pageHaveStreamTables` 模式不同（后者是 ForkJoinPool 并行写各索引）；Loop 4 是 sequential `for` 循环，**直接写各索引**即可，无并发竞争，`propagateState` 无需扩展。
- **Loop 2 无线表格保持不动**：用户在 Loop 2 `haveStreamTables` 块之外明确说"无线表格保持不动"，避免对先前 stream-table 任务造成回归。
- **互斥规则放在 Pass 1.5（公式）侧而非 Pass 1（stream-table）侧**：stream-table 是先到的、语义更确定；让"后到的"自查是否被占用更对称；新增第三种 entry 类型时只要再加一条互斥检查即可。
- **新增 `renderFormulaPageScreenshot` 而非把 `renderStreamTablePageScreenshot` 抽象成通用方法**：plan 推荐"inline a second helper"以保持两侧可读 side-by-side；未来如果出现第三种 entry 类型再统一抽象不迟。
- **不重命名 `basicFormulaRecognize` / `haveFormulas`**：方法名 / 字段名严格遵循用户给出的 `have_formula`（snake_case JSON）+ `haveFormulas`（camelCase Java）双轨约定。
- **`HAVE_FORMULA` 写在 `HAVE_STREAM_TABLE` 之后**：与 `JsonName.java` 常量声明顺序一致；便于阅读"按声明顺序对齐"的习惯。
- **image-triggered entry 同步写 `HAVE_FORMULA: false`**：与 Pass 2 原来写 `HAVE_STREAM_TABLE: false` 对称；不破坏消费者按字段判别 entry 类型的逻辑（消费者只关心是不是 `true`）。
- **`haveFormulas` 替换 `processLineArtGroups` 后的副效应**：Loop 4 不再有任何公式 OCR 发生，`pageContents` 在 Loop 4 末保持原样；若用户后续真的需要让 Java 内部识别公式、输出 `$$...$$` TextChunk，必须重新引入 `processLineArtGroups` 调用（可作为开关）。

## 风险与已知限制

- **未做端到端 PDF 验证**：依赖用户运行验证。需用户在测试 PDF（含公式候选区域）上跑一次确认：
  - 主 JSON 每页有 `have_formula`；
  - `_ocr.json` 中"只标记公式 / 只标记 stream-table / 同时标记二者 / 都不标记"四类页面互斥行为正确；
  - OSS 开启 → `image_url` 是 temp 桶 URL；OSS 关闭 → `image_url` 是本地路径。
  - **Loop 4 不再产生 Paddle 请求**（grep 日志或 paddle server 计数器应显示 paddle 调用次数减少）。
- **未补单测**：entry 字段 `have_formula`、截图辅助方法的 mock/单测都缺；按 code-review 标准属 MEDIUM。
- **`pageHaveFormulas` 越界**：`data.size()` 与数组长度取小，越界不会发生；显式 `i < data.size()` 与 `i < pageHaveFormulas.length` 双限制。
- **OSS objectKey 不带 PDF 名**：所有同 topic+businessId 的公式截图都进同一前缀；不同 PDF 的同名会互相覆盖（与 stream-table、`image` 项同 pattern，本次未改）。
- **200 DPI 是手挑值**：与 stream-table 保持一致（`StreamTableProcessor.SINGLE_PAGE_IMAGE_DPI=300f`，本轮选 200 平衡清晰度与文件大小）；如有 OCR 准确度诉求可改 300 或暴露参数。
- **`tagged/hybrid` 路径 `have_formula` 永远 false**：符合"这两个路径不跑 Loop 4"的设计，但消费者需注意"false ≠ 确认无公式"。
- **`haveFormulas` 不删除中间 PNG**：`scanAndMerge` 会写临时 PNG，`restoreAllGroups` 不清；后续没有 `processLineArtGroups` 来覆写或清理（因为 Loop 4 不再调它），所以扫描过程中产生的临时 PNG 会留在 images 目录直到被 `cleanupLocalFiles` 按 `_` 前缀清掉（前提是文件名以 `{baseName}_` 开头）。
- **Loop 4 取消 OCR 的语义变化**：本轮前 Loop 4 会真正把识别到的公式写回 `pageContents`（PDF / Markdown / HTML 输出会得到 `$$...$$` 公式文本）；本轮后 Loop 4 不再写回，`have_formula=true` 仅是给 JSON 消费者的标记信号。如需恢复写回，必须**单独**调用 `LineArtProcessor.processLineArtGroups(...)`。
- **重复修复了上一轮 stream-table 的 typo**：方法名 `existStreamTableexistStreamTable` 被写重；本轮编译时才暴露，必须修复才能让整库 BUILD SUCCESS。本应在上轮编译时就发现 —— 提醒："BUILD SUCCESS" 不能仅依赖历史汇报，必须每次改动后实测。

## 构建 / 运行

- 编译：`cd java && mvn -pl opendataloader-pdf-core,opendataloader-pdf-cli,opendataloader-pdf-server -am package -DskipTests -q`
- 跑样例：`cd java && mvn -pl opendataloader-pdf-core exec:java -Dexec.mainClass=org.opendataloader.pdf.DebugSample`（需 `DebugSample` 配置好含公式区域的 PDF 与可选 OBS 配置）。
- PowerShell 下 `tail` / `grep` 不可用，调试编译产物需走 `Select-String` 或读 `target/...` 路径。

## 相关文件（Relevant Files）

- `java/opendataloader-pdf-core/src/main/java/org/opendataloader/pdf/processors/LineArtProcessor.java`（给 `haveFormulas` 加 JavaDoc + 整理分支顺序）
- `java/opendataloader-pdf-core/src/main/java/org/opendataloader/pdf/processors/StreamTableProcessor.java`（顺手修方法名 typo `existStreamTableexistStreamTable` → `existStreamTable`）
- `java/opendataloader-pdf-core/src/main/java/org/opendataloader/pdf/processors/ExtractionResult.java`（新增 `pageHaveFormulas` 字段 + getter + 新 6 参构造器）
- `java/opendataloader-pdf-core/src/main/java/org/opendataloader/pdf/processors/DocumentProcessor.java`
  - 主线程新增 `pageHaveFormulas` 数组（注释说明由 Loop 4 sequential 写入）；
  - **Loop 2 不动**（`haveStreamTables` 块保持原样）；
  - **Loop 4 替换 `processLineArtGroups` 为 `haveFormulas`**，把结果写入 `pageHaveFormulas[pageNumber]`；paddleEnabled=false 时显式置 false；
  - `ProcessDocumentResult` 内部类扩展为 3 字段 + getter；
  - `extractContents`/`processFileWithResult`/`generateCustomOutputs` 透传新参数。
- `java/opendataloader-pdf-core/src/main/java/org/opendataloader/pdf/json/JsonName.java`（新增 `HAVE_FORMULA` 常量）
- `java/opendataloader-pdf-core/src/main/java/org/opendataloader/pdf/json/JsonWriter.java`
  - `writeToCustomJson` 新增 9 参重载；`writePageToGenerator` 与 `placeholderPageJson` 写 `have_formula`；
  - `writeOcrDetectionJson` 签名扩展 + 新增 Pass 1.5 + 新增 `renderFormulaPageScreenshot`；Pass 2 entry 同步写 `HAVE_FORMULA: false`。

## 备忘

- `org.verapdf.pd.PDDocument` 与 `org.apache.pdfbox.pdmodel.PDDocument` 同名冲突：本次用 fully-qualified 写 `org.apache.pdfbox.pdmodel.PDDocument`，未 import 简单类名；后续若再加 PDFBox 用法应继续用 fully-qualified 或用 import 起别名。
- Java 11 源目标限制：`record`、switch pattern matching 等不可用，未来升级到 17 前需注意。
- `cleanupLocalFiles` 白名单行为要记住：`name.startsWith(pdfBaseName + "_")` 捕获所有下划线前缀文件，**包括未来以 `_` 开头的辅助文件**；命名约定务必保持一致。
- 历史任务汇报的"BUILD SUCCESS"必须实测复核：本次因上一轮 typo 编译失败，提示不要仅依赖过去的构建报告。
- **`haveFormulas` 替换 `processLineArtGroups` 的语义**：这是用户**实施中**明确指示的设计变更，不是初版计划；初版计划是 Loop 2 并行加 `haveFormulas`（已被取消）。Loop 4 现在只做"轻量标记"，OCR 不再在 Java 内部发生，靠下游 JSON 消费者触发。
- `haveFormulas` 与 `processLineArtGroups` 共享 `scanAndMerge`，但前者不重写 `pageContents`、后者重写；本轮后 `processLineArtGroups` 在 `DocumentProcessor` 中没有任何调用方，若未来需要重新启用 OCR 内识别，必须**显式**调回。
