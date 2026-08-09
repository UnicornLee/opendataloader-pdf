# OCR 页面识别（is_ocr 标记 + `<pdfname>_ocr.json` 生成）

- 时间：2026-08-09
- 任务：在 `JsonWriter.writeToCustomJson` "重新读取 json 文件内容"之后、目录书签处理之前，新增 OCR 页面扫描：对每页判定"items ≤ 4 / 无表格 / 含图片 / 存在图片 height/page.height > 0.8"，命中页原地写 `is_ocr=true`、并把命中页汇总写入独立的紧凑 JSON 文件 `<pdfname>_ocr.json`（结构与已有样例 `tmp_output\202604231785283947722051256_ocr.json` 对齐）。
- 状态：已实现 + 已用 `DebugSample` 跑通 `docs/pdf\202303181679059838994480.pdf` 验证。

## 目标（Goal）
- 自动识别"以单张大图为主、几乎无文字和表格"的页面（典型为扫描件页），输出结构化清单，方便后续 OCR / 文字识别管线直接消费。
- 同时在主 JSON 上留下 `is_ocr=true` 标记，让下游无需重新扫一遍页面。

## 用户偏好与约束（Constraints & Preferences）
1. 改动点：仅 `org.opendataloader.pdf.json.JsonWriter#writeToCustomJson`，插入位置在"重新读取json文件内容"之后（~ 第 666 行）。
2. 触发条件四则：页面元素（items）数量 ≤ 4；不包含 `lattice_table` / `stream_table`；包含至少一张 `item_type == "image"`；存在某张图片 `height / page.height > 0.8`（多个满足时取比例最大的那张）。
3. 文件名：`<pdfname>_ocr.json`，写入位置同主 JSON（`outputFolder`）。
4. 主 JSON 上对命中页只改 `is_ocr=true`，不追加额外字段。
5. 不清楚处先澄清；改码前先给思路获批后再改（已满足）。

### 澄清后口径
- **`image_url`**：复用现有 `imageFile` 格式（直接读取图片项 `content[0]` 的绝对路径），不重新拼接样例里的 `<pdfname>_<page>_<idx>.png`。
- **`image_height` / `image_width`**：取**页面**的 `height` / `width`，不是图片自身的尺寸（与样例 `202604231785283947722051256_ocr.json` 字段含义一致）。
- **图片索引**：样例字段在用户口径下被重新解释为页面级数据，OCR JSON 里**不再需要单独的"图片索引"字段**，只用 `image_url`（从 `content[0]` 来的绝对路径）即可。
- **`is_ocr` 持久化**：允许在内存 map 中标记 `is_ocr=true`；若 `config != null`，由后续 bookmarks 写回主 JSON 时一起持久化（无需额外写回）；若 `config == null`，主 JSON 不被写回——保持现有调用约定，最小侵入。

## 前置事实（已核实）
- `writeToCustomJson` 主流程：
  - 主体用 `JsonGenerator` 写出主 JSON，每页字段 `page_index`(1-based)/`width`/`height`/`is_ocr`(默认 false)/`items`。
  - 之后 `mapper.readValue(...)` 反序列化为 `Map<String, Object>`，再做目录书签处理；处理完调 `mapper.writerWithDefaultPrettyPrinter().writeValue(jsonFileName, map)` 写回（仅 `if (config != null)` 时执行）。
- `JsonName` 常量：`DATA / ITEMS / PAGE_INDEX / WIDTH / HEIGHT / IS_OCR / ITEM_TYPE / HEIGHT / CONTENT` 全部已存在，无需新增常量。
- 图片项 `content`：`jsonGenerator.writeObject(imageMap)` 时 `content = Arrays.asList(absolutePath)`，`absolutePath = String.format(MarkdownSyntax.IMAGE_FILE_NAME_FORMAT, imagesDirectory, File.separator, imageChunk.getIndex(), imageFormat)` → 取 `content[0].toString()` 即为绝对路径。
- 表格项 `item_type` 取值：`lattice_table` / `stream_table`（分别由 `TableBorder` / `PageItem("stream_table")` 分支生成）。
- `JsonWriter.java` 已 import：`java.util.HashMap / List / ArrayList / Map`；`com.fasterxml.jackson.databind.ObjectMapper`；`java.io.File`。

## 已实现方案
### 改动 1：插入调用（~ 第 665 行，紧跟 `mapper.readValue(...)` 之后）
```java
// 扫描每一页，识别符合 OCR 条件的页面，写入 <pdfname>_ocr.json，
// 并在命中页上将 is_ocr 置为 true（由后续 bookmarks 写回主 JSON 时一起持久化）。
try {
    writeOcrDetectionJson(mapper, map, outputFolder, inputPDF.getName());
} catch (Exception ocrEx) {
    LOGGER.log(Level.WARNING, "Unable to create OCR detection JSON: " + ocrEx.getMessage());
}
```
外层 `try/catch` 保证 OCR 流程异常不影响主流程。

### 改动 2：新增私有方法 `writeOcrDetectionJson`（紧跟 `isParagraphOrHeadingItem` 之后）
签名：
```java
private static void writeOcrDetectionJson(ObjectMapper mapper,
                                          Map<String, Object> map,
                                          String outputFolder,
                                          String pdfFileName) throws IOException
```
算法（单次遍历 `map["data"]` 每页）：
1. `items == null` 或 `items.size() > 4` → 跳过。
2. 任一 item `item_type` ∈ {`lattice_table`, `stream_table`} → 跳过。
3. `page["height"]` 不是 `Number` 或 `≤ 0` → 跳过。
4. 遍历 items，挑选 `item_type == "image"` 且 `height / page.height` 比例最大的；记录 `bestImage / bestRatio`。
5. `bestImage == null || bestRatio <= 0.8` → 跳过。
6. 命中：`page["is_ocr"] = true`；取 `bestImage["content"][0]` 作为 `image_url`；`image_height = page.height`、`image_width = page.width`；构造 entry 追加到 `ocrEntries`。
7. 构造 OCR JSON：
   ```json
   {
     "business_id": "None",
     "extend": {},
     "url": map["url"],
     "data": [ { "page_index": N, "image_url": "...", "image_height": H, "image_width": W } ]
   }
   ```
8. 文件名：
   ```java
   String ocrBaseName = pdfFileName.substring(0, pdfFileName.length() - 3);
   if (ocrBaseName.endsWith(".")) {
       ocrBaseName = ocrBaseName.substring(0, ocrBaseName.length() - 1);
   }
   String ocrFileName = outputFolder + File.separator + ocrBaseName + "_ocr.json";
   ```
   - 必须显式去掉 `length()-3` 残留的尾部 `.`，否则 `.pdf` 会得到 `xxx._ocr.json`（实测遇到）。
9. 紧凑 JSON（**不带** `writerWithDefaultPrettyPrinter`），匹配样例单行格式：
   ```java
   mapper.writeValue(new File(ocrFileName), ocrResult);
   LOGGER.log(Level.INFO, "Created {0}", ocrFileName);
   ```

### 改动 3：新增 import
```java
import java.util.LinkedHashMap;   // 紧跟 java.util.HashMap 之后
```
保持 entry 键序，便于人眼调试。

## 验证结果
- `mvn -pl opendataloader-pdf-core compile -DskipTests` BUILD SUCCESS，无新增 warning/error。
- 跑 `DebugSample`（`docs/pdf\202303181679059838994480.pdf`）：
  - 生成 `tmp_output\202303181679059838994480_ocr.json`：
    ```json
    {"business_id":"None","extend":{},"url":"...\\202303181679059838994480.pdf","data":[{"page_index":196,"image_url":"...\\202303181679059838994480_images\\imageFile85.png","image_height":841.92,"image_width":595.32}]}
    ```
  - 主 `tmp_output\202303181679059838994480.json` 中 page_index=196 出现 `"is_ocr" : true`（其余页保持 `false`），证明：
    - 命中判定正确（page196 仅 1 张图，图片高度 808 / 页高 841.92 ≈ 0.96 > 0.8）。
    - `is_ocr` 写回主 JSON 链路通（`config != null` 分支写回时生效）。
  - 文件名严格匹配 `<pdfname>_ocr.json`，无 `.` 前缀（已剥除）。

## 关键决策（Key Decisions）
- **`is_ocr` 改在内存 map 上、由现有 bookmarks 写回顺带持久化**，避免在 OCR 流程里多写一次主 JSON；前提是 `config != null`（实际调用方 `DocumentProcessor.processDocument` 总是非 null）。
- **多个满足条件图片取比例最大**：`bestRatio = max(image.height / page.height)`，与样例每页只出一条记录的行为对齐。
- **OCR JSON 用紧凑格式（无 pretty printer）**：与样例 `202604231785283947722051256_ocr.json` 单行形态一致，便于直接 push 到流式管道。
- **新方法 `throws IOException` + 调用点 `try/catch`**：让 OCR 失败只记 WARN、不抛到上游，保持主流程鲁棒。
- **`length()-3` 残留 `.` 问题显式 trim**：保留项目既有 `substring(0, len-3)` 风格的同时纠正产物文件名，不去改主流程的命名（避免扩大变更面）。

## 风险与已知限制
- **未配单测**：用户未要求；后续若需要可在 `opendataloader-pdf-core\src\test` 加 `JsonWriterOcrDetectionTest`（用 `lorem.json` 风格 fixture 覆盖 4 个跳过分支 + 1 个命中分支）。
- **极端边界**：`page.height` 缺/非数值/`≤ 0` 直接跳过；图片项 `height` 缺/非数值则跳过该图（其它图仍可命中）；`content[0]` 缺失则 `image_url=""`（entry 仍写出，便于人工诊断）。
- **`config == null` 时 `is_ocr` 不持久化**：与现有调用约定一致；OCR JSON 本身已记录命中页，下游仍可识别。

## 构建 / 运行
- 编译：`mvn -pl opendataloader-pdf-core compile -DskipTests`（项目根在 `java\`）。
- 运行：`DebugSample` 入口 `org.opendataloader.pdf.DebugSample`（已指 `docs/pdf\202303181679059838994480.pdf`）；classpath 用 `opendataloader-pdf-core\cp.txt` + `target/classes`。
- PowerShell 下 Maven `-D` 参数必须加引号（避免被解析成 lifecycle phase）。

## 相关文件（Relevant Files）
- `java\opendataloader-pdf-core\src\main\java\org\opendataloader\pdf\json\JsonWriter.java`
  - ~661 行：新增 `writeOcrDetectionJson` 调用（夹在 `mapper.readValue` 与目录书签处理之间）。
  - 紧跟 `isParagraphOrHeadingItem` 之后：新增 `writeOcrDetectionJson` 方法 + Javadoc。
  - import 区：新增 `java.util.LinkedHashMap`。
- `java\opendataloader-pdf-core\src\main\java\org\opendataloader\pdf\json\JsonName.java`：`DATA / ITEMS / PAGE_INDEX / WIDTH / HEIGHT / IS_OCR / ITEM_TYPE / CONTENT` 常量（直接复用，未改）。
- `java\opendataloader-pdf-core\src\main\java\org\opendataloader\pdf\DebugSample.java`：验证入口（已激活 `202303181679059838994480.pdf`）。
- `tmp_output\202303181679059838994480_ocr.json`（生成产物，命中 page 196）。
- `tmp_output\202604231785283947722051256_ocr.json`（既有样例，对照结构）。
- `tmp_output\202303181679059838994480.json`（主 JSON 验证 `is_ocr:true`）。

## 备忘
- 主流程的 `length()-3` 命名怪癖：`.pdf` 截断后会留一个 `.`（实测 `xxx..json`），新代码用 `if (ocrBaseName.endsWith("."))` 剥掉；**未同步修正主 JSON 的命名**——若想统一去掉主 JSON 的多余 `.`，可单开一个 PR 改 `writeToCustomJson` 内的 `jsonFileName / jsFileName / htmlFileName` 拼接（影响面更大，本次不动）。
- `JsonName.HEIGHT` 同时用于页面和图片项，类型都是 `Number`，靠上下文（页面对象 vs item 对象）区分；OCR 方法内已分别取 `page.get(HEIGHT)` 与 `item.get(HEIGHT)`，互不混淆。
- OCR 流程不依赖 `Config`，因此即便 `config == null` 也会写出 `_ocr.json`；这对 CLI 默认调用路径无副作用。