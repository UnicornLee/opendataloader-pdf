# 每页无线表格检测 `have_stream_table` 字段 + OCR JSON 截图透传

- 时间：2026-08-20
- 任务：将 `DocumentProcessor#processDocument` 内对每页的"是否包含无线表格"检测从原来的 `StreamTableProcessor#processStreamTables`（重写页面内容）改为轻量级 `StreamTableProcessor#haveStreamTables`（仅返回 boolean），把结果以 `pageHaveStreamTables: boolean[]`（按 0-based 页码索引）通过 `ExtractionResult` → `processFileWithResult` → `generateCustomOutputs` → `JsonWriter` 整条链路透传，并在两层 JSON 中落地：
  1. 主 JSON 每页追加 `have_stream_table` 字段（true/false）；
  2. `_ocr.json` 对所有 `have_stream_table=true` 的页面渲染 PNG 截图，按 `ossEnabled` 走本地保存或上传到 OSS **temp 桶**（与主 JSON 同桶），entry 中带 `have_stream_table=true`。
- 状态：已实现 + `mvn package` 在 `opendataloader-pdf-core / opendataloader-pdf-cli / opendataloader-pdf-server` 三模块均 BUILD SUCCESS（exit 0）。

## 目标（Goal）

- 把昂贵的 Paddle OCR 表格识别从"无差别全跑"降级为"只标记、由下游按需触发"，降低默认流水线耗时与 OSS 流量。
- 在不破坏现有 `_ocr.json`（图片命中条目）结构的前提下，新增"无线表格页面截图"作为新的 entry 类型，下游 OCR 工具可凭 `have_stream_table` 字段区分两类。
- 用户约束："有不清楚的地方需要向我澄清；先提供方案，我同意后才能修改代码"（计划阶段已满足）。

## 用户偏好与约束（Constraints & Preferences）

1. 检测与重写解耦：保留 `haveStreamTables` 的轻量检测，**移除** `processStreamTables`（重写 `pageContents`）。
2. `have_stream_table` 字段同时出现在主 JSON（每页）和 `_ocr.json`（entry 级）。
3. 截图存放：复用现有 `outputFolder/{baseName}_images/` 目录；本地文件名 `{baseName}_streamtable-{n}.png`（前缀 `_` 对齐 `cleanupLocalFiles` 的 `name.startsWith(pdfBaseName + "_")` 白名单，OSS 上传成功后本地文件会被自动删除）。
4. OSS 桶选择：与主 JSON 一致 → **temp 桶**（`ossTempBucketName` + `getTempDomainName()`），**不是**永久桶。
5. 截图源 PDF 用 `inputPdfName`（绝对路径），不要读 `map.get("url")` —— `url` 可能是云端 URL，会渲染失败。
6. `writeOcrDetectionJson` 中 `pdfFileName` 参数可以从 `inputPdfName` 派生，不需要单独传入。
7. Java 源版本是 11，`record` 关键字不可用 → `ProcessDocumentResult` 改写为普通 `private static final class` + getter。
8. ForkJoinPool 并发安全：与 `pageWidths/pageHeights` 同模式，`pageHaveStreamTables` 在主线程 `new`、各 worker 写自己索引，无需 `propagateState`。
9. Backward compatibility：`TaggedDocumentProcessor` / `HybridDocumentProcessor` 路径传 `null`，下游遇到 `null` 视作"无信息"，每页 `have_stream_table` 写 `false`。

## 前置事实（已核实）

- `DocumentProcessor#processDocument` 原本在每页调用 `StreamTableProcessor.processStreamTables(inputPdfName, pageContents, pageNumber, width, height, paddleUrl)` —— 该方法除检测外还会做 OCR + 重写 `pageContents`。
- `StreamTableProcessor.haveStreamTables(...)` 签名同 `processStreamTables` 但返回 `boolean`，不做 OCR、不重写。
- `ExtractionResult` 现有 3 个构造函数，新增 `pageHaveStreamTables` 字段作为可空扩展，老构造器全部委托到新构造器传 `null`，旧调用方零改动。
- `JsonWriter#writePageToGenerator` 序列化顺序：`page_index / width / height / is_ocr(false) / margins / items` —— `have_stream_table` 紧跟 `is_ocr` 之后写最自然。
- `JsonWriter#placeholderPageJson` 当前只写 `{page_index, is_ocr, items}`，异常页 placeholder 也要补 `have_stream_table`。
- `JsonWriter.OssUploadConfig.getTempBucketName()` / `getTempDomainName()` 与 `uploadFile` 已存在；主 JSON 上传格式 `public/{basicEnv}/{topic}_{businessId}.json` 已被 `buildJsonObjectKey` 复用。
- `cleanupLocalFiles` 中 `isRelatedToCurrentPdf` 白名单 `name.startsWith(pdfBaseName + "_")`，所以新文件名 **必须** 以 `{baseName}_` 开头才能被自动清理；采用 `{baseName}_streamtable-{n}.png`。
- `org.verapdf.pd.PDDocument` 与 `org.apache.pdfbox.pdmodel.PDDocument` 同名冲突，新代码需要写 `org.apache.pdfbox.pdmodel.PDDocument`，**不能**简单 `import PDDocument`。
- Maven 源目标：`maven.compiler.source=11`（pom.xml 已确认），Java 17 运行时但编译目标是 Java 11，因此 `record` 不可用。

## 已实现方案

### 改动 1：`ExtractionResult.java`
新增字段 + 新构造器，老构造器全部委托：

```java
private final boolean[] pageHaveStreamTables;
public ExtractionResult(List<List<IObject>> contents, long extractionNs, JsonNode hybridTimings,
                         Map<Long, ElementMetadata> elementMetadata,
                         boolean[] pageHaveStreamTables) { ... }
public boolean[] getPageHaveStreamTables() { return pageHaveStreamTables; }
```

- 老 4 参构造器：`this(..., null)`
- 老 3 参构造器：`this(..., Collections.emptyMap(), null)`

### 改动 2：`DocumentProcessor.java`
- ForkJoinPool 之前 `final boolean[] pageHaveStreamTables = new boolean[totalPages];`（与 `pageWidths/pageHeights` 同模式）。
- 原 `processStreamTables` 调用替换为：
  ```java
  if (basicParseStreamTable && paddleUrl != null && !"".equals(paddleUrl) && !ocrFallbackPages.contains(pageNumber)) {
      try {
          pageHaveStreamTables[pageNumber] = StreamTableProcessor.haveStreamTables(
              inputPdfName, pageContents, pageNumber,
              pageWidths[pageNumber], pageHeights[pageNumber], paddleUrl);
      } catch (Exception e) {
          LOGGER.log(Level.WARNING, "haveStreamTables failed for page " + pageNumber + ": " + e.getMessage(), e);
          pageHaveStreamTables[pageNumber] = false;
      }
  } else {
      pageHaveStreamTables[pageNumber] = false;
  }
  ```
- `processDocument` 返回类型改为私有类 `ProcessDocumentResult`（Java 11 不支持 `record`）：
  ```java
  private static final class ProcessDocumentResult {
      private final List<List<IObject>> contents;
      private final boolean[] pageHaveStreamTables;
      ProcessDocumentResult(List<List<IObject>> contents, boolean[] pageHaveStreamTables) { ... }
      List<List<IObject>> getContents() { return contents; }
      boolean[] getPageHaveStreamTables() { return pageHaveStreamTables; }
  }
  ```
- `extractContents` 中只 `processDocument` 分支使用新类：`contents = result.getContents(); pageHaveStreamTables = result.getPageHaveStreamTables();`；`Tagged/HybridDocumentProcessor` 分支维持原样，`pageHaveStreamTables` 默认 null。
- `processFileWithResult` 调用 `generateCustomOutputs` 时新增第 5 参数 `extraction.getPageHaveStreamTables()`。
- `generateCustomOutputs` 签名同步加 `boolean[] pageHaveStreamTables`，转发给 `JsonWriter.writeToCustomJson`。

### 改动 3：`JsonName.java`
新增 `public static final String HAVE_STREAM_TABLE = "have_stream_table";`（紧跟 `IS_OCR` 之后）。

### 改动 4：`JsonWriter.java`
- `writeToCustomJson` 增加 8 参重载，老 6 参 / 7 参重载全部委托为 `null`：
  ```java
  public static CustomOutputResult writeToCustomJson(String inputPdfName, String outputFolder, List<List<IObject>> contents,
                                            Map<Long, ElementMetadata> elementMetadata,
                                            Map<String, Object> hybridInfo,
                                            boolean includeHeaderFooter,
                                            Config config,
                                            boolean[] pageHaveStreamTables) throws IOException
  ```
- `writePageToGenerator` 签名加 `boolean[] pageHaveStreamTables`，在 `IS_OCR` 之后写：
  ```java
  pageGenerator.writeBooleanField(JsonName.HAVE_STREAM_TABLE,
      pageHaveStreamTables != null && pageHaveStreamTables[pageNumber]);
  ```
- `placeholderPageJson` 增加 `placeholder.put(JsonName.HAVE_STREAM_TABLE, false);`。
- `writeOcrDetectionJson` 签名扩展，新增 `pageHaveStreamTables / ossEnabled / ossConfig / obsClient / inputPdfName`，**删除冗余的 `pdfFileName`**（在方法体内用 `new File(inputPdfName).getName()` 派生）：
  ```java
  private static void writeOcrDetectionJson(ObjectMapper mapper,
                                            Map<String, Object> map,
                                            String outputFolder,
                                            Config config,
                                            boolean[] pageHaveStreamTables,
                                            boolean ossEnabled,
                                            OssUploadConfig ossConfig,
                                            HuaweiObsClient obsClient,
                                            String inputPdfName) throws IOException
  ```
- 在原 `ocrEntries` 收集之前新增 **Pass 1：stream-table 页面渲染与上传**：
  - 遍历 `pageHaveStreamTables`，命中页读 `page.get(WIDTH/HEIGHT)`，调 `renderStreamTablePageScreenshot` 拿 URL/本地路径；
  - entry 结构 `{ page_index, image_url, image_height, image_width, have_stream_table: true }`，加入 `ocrEntries`；
  - 渲染失败 `imageUrl == null` 时跳过该页（记 WARN）。
- 新增辅助方法 `renderStreamTablePageScreenshot(pdfFile, outputFolder, pdfBaseName, pageNumber, ossEnabled, ossConfig, obsClient)`：
  - 输出目录 `{outputFolder}/{baseName}_images/`，文件名 `{baseName}_streamtable-{pageNumber}.png`（前缀 `_` 对齐 cleanup 白名单）。
  - 渲染：`Loader.loadPDF(pdfFile)` + `PDFRenderer.renderImageWithDPI(pageNumber, 200f)` + `ImageIO.write`。
  - **必须用 fully-qualified `org.apache.pdfbox.pdmodel.PDDocument`**，因 `org.verapdf.pd.PDDocument` 已 import。
  - OSS 开启 → objectKey `public/{basicEnv}/{topic}_{businessId}_streamtable_{pageNumber+1}.png`，上传到 temp 桶，成功后 `Files.delete` 本地；上传失败回退到本地绝对路径（记 WARN）。
  - OSS 关闭 → 返回本地绝对路径。
- `writeToCustomJson` 中调用方：
  ```java
  writeOcrDetectionJson(mapper, map, outputFolder, config,
      pageHaveStreamTables, ossEnabled, ossConfig, obsClient, inputPdfName);
  ```
- import 新增：`java.awt.image.BufferedImage`、`javax.imageio.ImageIO`、`org.apache.pdfbox.Loader`、`org.apache.pdfbox.rendering.PDFRenderer`。

### 改动 5（事后错误修正）
- 用户反馈 `map.get("url")` 可能是云端 URL，导致 PDFBox 加载失败 → 改为直接传入 `inputPdfName`（PDF 下载到本机的绝对路径）。
- 用户反馈 `writeOcrDetectionJson` 中 `pdfFileName` 参数冗余，可由 `inputPdfName` 派生 → 删掉该参数。

## 验证结果

- `mvn -pl opendataloader-pdf-core -am compile -q` BUILD SUCCESS（修完 Java 11 record 问题后）。
- `mvn -pl opendataloader-pdf-core,opendataloader-pdf-cli,opendataloader-pdf-server -am package -DskipTests -q` BUILD SUCCESS（exit 0，三个模块全过）。
- 用户实际操作反馈两轮：
  1. `map.get("url")` 可能拿云端 URL，建议改用 `inputPdfName` —— 已采纳并替换。
  2. `pdfFileName` 参数冗余，建议去掉 —— 已采纳并在方法体内派生。
- 未补单元测试（用户未要求；code-review 风格风险 MEDIUM）。

## 关键决策（Key Decisions）

- **`ProcessDocumentResult` 用普通类而非 record**：Java 11 源目标不允许 record，避免扩大变更面（升级 maven.compiler.source 会牵涉所有模块）。
- **Pass 1 在 OCR 原始循环之前**：让 stream-table 截图条目排在前面，调试时更直观；二者 entry 类型相同，都进同一个 `ocrEntries`。
- **OSS temp 桶而不是 permanent 桶**：与主 JSON 一致；用户原话"如果 ossEnabled 为 true 保存到临时 OSS 桶对象存储"。permanent 桶是给长期保留的图片用的，截图是临时派生品。
- **本地文件命名 `{baseName}_streamtable-{n}.png` 而不是 `{baseName}-streamtable-{n}.png`**：后者 `-` 前缀不命中 `cleanupLocalFiles` 的 `_` 前缀白名单。改为下划线后无需额外白名单条目。
- **截图失败回退到本地路径**：与"上传失败保留本地"的现有策略一致（参考 `uploadImageIfNeeded` 的 `missingOrSkipped` 逻辑）。
- **`pageHaveStreamTables` 沿用 `pageWidths/pageHeights` 模式**：主线程 new、`pool.submit` 后 `pool.get`，worker 各写各索引，零额外同步原语。
- **OCR 与 stream-table 同进 `_ocr.json`**：单一消费方入口，下游按 `have_stream_table` 字段区分；不另开新文件。

## 风险与已知限制

- **未做端到端 PDF 验证**：当前用 `DebugSample` 跑过类似流程的可行性已确认（依赖用户运行验证）；需用户在测试 PDF（含无线表格页）上跑一次确认：
  - 主 JSON 每页有 `have_stream_table`；
  - `_ocr.json` 对应页 entry 含 `have_stream_table: true` 且 `image_url` 指向本地路径或 OSS temp URL。
- **未补单测**：entry 字段 `have_stream_table`、截图辅助方法的 mock/单测都缺；按 code-review 标准属 MEDIUM。
- **`pageHaveStreamTables` 越界**：`data.size()` 与数组长度取小，越界不会发生；显式 `i < data.size()` 与 `i < pageHaveStreamTables.length` 双限制。
- **OSS objectKey 不带 PDF 名**：所有同 topic+businessId 的 stream-table 截图都进同一前缀；不同 PDF 的同名会互相覆盖（与现有 `image` 项同 pattern，本次未改）。
- **200 DPI 是手挑值**：`StreamTableProcessor.SINGLE_PAGE_IMAGE_DPI=300f`，本次选 200 是平衡清晰度与文件大小；如有 OCR 准确度诉求可改 300 或暴露参数。
- **`tagged/hybrid` 路径 `have_stream_table` 永远 false**：符合用户口径"这两个路径不跑 stream-table 检测"，但消费者需注意"false ≠ 确认无表格"。

## 构建 / 运行

- 编译：`cd java && mvn -pl opendataloader-pdf-core,opendataloader-pdf-cli,opendataloader-pdf-server -am package -DskipTests -q`
- 跑样例：`cd java && mvn -pl opendataloader-pdf-core exec:java -Dexec.mainClass=org.opendataloader.pdf.DebugSample`（需 `DebugSample` 配置好含无线表格的 PDF 与可选 OBS 配置）。
- PowerShell 下 `tail` / `grep` 不可用，调试编译产物需走 `Select-String` 或读 `target/...` 路径。

## 相关文件（Relevant Files）

- `java/opendataloader-pdf-core/src/main/java/org/opendataloader/pdf/processors/ExtractionResult.java`（新增 `pageHaveStreamTables` 字段 + getter + 新构造器）
- `java/opendataloader-pdf-core/src/main/java/org/opendataloader/pdf/processors/DocumentProcessor.java`
  - ForkJoinPool 上方新增 `pageHaveStreamTables`；替换 `processStreamTables` 调用；新增 `ProcessDocumentResult` 内部类（普通 class 而非 record）；`extractContents`/`processFileWithResult`/`generateCustomOutputs` 透传新参数。
- `java/opendataloader-pdf-core/src/main/java/org/opendataloader/pdf/processors/StreamTableProcessor.java`（未改；`haveStreamTables` 复用既有方法）
- `java/opendataloader-pdf-core/src/main/java/org/opendataloader/pdf/json/JsonName.java`（新增 `HAVE_STREAM_TABLE` 常量）
- `java/opendataloader-pdf-core/src/main/java/org/opendataloader/pdf/json/JsonWriter.java`
  - `writeToCustomJson` 新增 `boolean[] pageHaveStreamTables` 重载；`writePageToGenerator` 与 `placeholderPageJson` 写 `have_stream_table`；`writeOcrDetectionJson` 签名扩展 + 新增 Pass 1 + 新增 `renderStreamTablePageScreenshot`；新增 4 个 import。

## 备忘

- `org.verapdf.pd.PDDocument` 与 `org.apache.pdfbox.pdmodel.PDDocument` 同名冲突：本次用 fully-qualified 写 `org.apache.pdfbox.pdmodel.PDDocument`，未 import 简单类名；后续若再加 PDFBox 用法应继续用 fully-qualified 或用 import 起别名。
- Java 11 源目标限制：`record`、switch pattern matching 等不可用，未来升级到 17 前需注意。
- `cleanupLocalFiles` 白名单行为要记住：`name.startsWith(pdfBaseName + "_")` 捕获所有下划线前缀文件，**包括未来以 `_` 开头的辅助文件**；命名约定务必保持一致。
- 用户在迭代过程中两次主动反馈（url 改 inputPdfName、pdfFileName 冗余），属"已澄清后口径"型反馈，要作为约束记入设计；后续类似任务应先思考"参数是否可由既有参数派生"，避免冗余。