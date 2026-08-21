# 2026-08-21 — OCR 截图按 HEADER_POS / FOOTER_POS 裁剪（PDF 层 cropBox 方案）

## 任务背景

延续 2026-08-20 两次 (`have_stream_table` 和 `have_formula`) 截图任务：现在每次截图都是整页 PNG，浪费 OCR token 且把页眉页脚（噪声）也送进去了。需要让 `JsonWriter#writeOcrDetectionJson` 在写 stream-table / formula entry 时，按该页 JSON 里的 `header_pos` / `footer_pos` 裁剪掉页眉页脚带。

整次任务分 **七个迭代回合**，每回合用户主动提出新约束或纠正方向。前六回合确立 PDF 层 cropBox 方案；**第七回合** 用户提出三个新要求：①Pass 2（图片命中）跳过 Pass 1/1.5 已截图的页面，②Pass 2 也按 HEADER_POS/FOOTER_POS 截图（与 Pass 1 一致），③Pass 1/1.5 命中时也要标 `is_ocr=true`。最终方案与最初设想完全不同：原本是"先 `renderImageWithDPI` 整页 → `getSubimage` 裁子图"，第六回合用户提出"为什么不在 PDF 层面裁剪再转图片"，定位后改为**临时修改 `PDPage.cropBox` 让 PDFRenderer 直接渲染指定 PDF 区域**，从根本上消除了像素层裁剪的累积误差。

## 整体定位过程（按迭代顺序）

### 回合 1：用户给出原始 14 行代码让我重构

```java
Double[] headerPos = new Double[4];
if (pageContents.size() > 0 && pageContents.get(0) instanceof SemanticHeaderOrFooter) {
    BoundingBox headerBox = ((SemanticHeaderOrFooter) pageContents.get(0)).getBoundingBox();
    headerPos[0] = headerBox.getLeftX();
    headerPos[1] = height - headerBox.getTopY();
    headerPos[2] = headerBox.getRightX();
    headerPos[3] = height - headerBox.getBottomY();
}
Double[] footerPos = new Double[4];
if (pageContents.size() > 1 && pageContents.get(pageContents.size() - 1) instanceof SemanticHeaderOrFooter) {
    // 同上的 footer 提取
}
```

我做的是：抽 `headerFooterPos(IObject, double): Double[]` helper，调用点从 14 行压到 4 行；顺手把 `pageContents.size() > 0` 改成 `isEmpty()`。

但 `mvn compile` 报 `JsonWriter.java:[551,22] ??writeNumberField(java.lang.String,java.lang.Double[])` —— **Jackson 的 `writeNumberField` 没有任何 `Double[]` 重载**（只有 `short / int / long / float / double / BigInteger / BigDecimal`）。编译错误指向下面两行：

```java
pageGenerator.writeNumberField(JsonName.HEADER_POS, headerPos);  // 编译失败
pageGenerator.writeNumberField(JsonName.FOOTER_POS, footerPos);  // 编译失败
```

**这是用户原话"可能有语法错误，请一并修改"指向的真正根因** —— 我之前误以为是上面 14 行有问题，其实下面那两行才是编译失败的源头。**修复**：改用项目里 `ContextUtils` / `TextSerializer` 已经在用的 `writeArrayFieldStart + writeNumber` 模式，并加 `writePosArray(JsonGenerator, String, Double[])` helper 处理 null 元素（`Double[]` 数组里可能含 null，需要 `writeNull()`）。

### 回合 2：`new Double[4]` 默认值是什么？

用户问。我答：包装类型数组元素默认是 `null`（不是 `0.0`）。

然后我问用户要不要把"无 header/footer 时数组全 null"改成"不输出此字段"。用户同意，于是：

- `headerFooterPos` 在 content 不是 SemanticHeaderOrFooter 时返回 `null`（而不是 `new Double[4]`）
- 调用点 `pageContents.isEmpty() ? null : headerFooterPos(...)`
- `writePosArray` 在 pos==null 时直接 return 不写字段

### 回合 3：OCR 截图按 HEADER_POS/FOOTER_POS 裁剪

用户在回合 2 之后给出正式任务。我用 `AskUserQuestion` 问了 4 个澄清：

| 问题 | 用户回答 |
|------|---------|
| HEADER_POS+FOOTER_POS 都在时，y 区间？ | `[HEADER_POS.bottomY, FOOTER_POS.topY]`（去掉两条带） |
| 只有一个时怎么办？ | 只有 HEADER：截 `[HEADER_POS.bottomY, pageHeight]`；只有 FOOTER：截 `[0, FOOTER_POS.topY]`；都没有：保持截整页 |
| 实现方式？ | 先渲染整页再 `getSubimage`（改动最小） |
| entry 里 `image_width/height` 字段？ | 改为裁剪后图片的实际宽高 |

实现：
- 新增 `computeClipY(Map<String,Object>): double[]` —— 从 page map 读 `HEADER_POS` / `FOOTER_POS` 数组，按规则算出 `[top, bottom]`
- 新增 `readPosArray(Object): Double[]` —— 把 Jackson 反序列化回来的 `List<Number>` 还原成 4 元素 `Double[]`，缺字段/长度不够/含非数字都返回 null
- 新增 `renderPage(File, int, double, double[]): BufferedImage` —— 渲染 + `getSubimage` 裁剪
- `renderStreamTablePageScreenshot` / `renderFormulaPageScreenshot` 签名加 `double[] clipYPdf, double pageHeight`，返回类型从 `String` 改成新增的内部类 `RenderedImage(String url, int width, int height)`，entry 用 `image.width/height`（裁剪后实际像素）
- 三个 stream-table / formula 分支都接 `computeClipY(page)` 传入

实现要点（裁剪逻辑）：
```java
double pixelPerPdfUnit = pageImage.getHeight() / pageHeight;
int yPx = (int) Math.round(clipYPdf[0] * pixelPerPdfUnit);
int hPx = (int) Math.round((clipYPdf[1] - clipYPdf[0]) * pixelPerPdfUnit);
yPx = Math.max(0, Math.min(yPx, pageImage.getHeight() - 1));
hPx = Math.max(1, Math.min(hPx, pageImage.getHeight() - yPx));
return pageImage.getSubimage(0, yPx, pageImage.getWidth(), hPx);
```

### 回合 4：用户纠正 entry 字段语义

用户原话：**"entry 字段：image_width / image_height 是对应pdf中的宽和高，不是图片正式的宽和高。"**

我误解成"图片像素尺寸"，正确语义是 **PDF 坐标下的区域尺寸**。于是：

- `entry.image_width = pageWidth`
- `entry.image_height = pageHeight`（此时我以为是整页）

同时把 `renderStreamTablePageScreenshot` / `renderFormulaPageScreenshot` 改回返回 `String`（不再需要 url+width+height 组合），并删除 `RenderedImage` 内部类。

### 回合 5：用户进一步澄清 image_height 不是整页

用户原话：**"不是整个高，是减去页眉和页脚后的高。"**

所以 `image_height` 应该是裁剪区间长度（PDF 单位），不是整页。改：

```java
double capturedHeight = clipY != null ? (clipY[1] - clipY[0]) : pageHeight;
entry.put("image_height", capturedHeight);
entry.put("image_width", pageWidth);
```

| 情况 | image_width | image_height |
|------|------------|--------------|
| 有 clipY | pageWidth（横坐标截满） | `clipY[1] - clipY[0]`（去掉 header/footer 后的 PDF 高） |
| 无 clipY（无 header/footer） | pageWidth | pageHeight（整页） |

### 回合 6：用户的关键质疑——为什么不先截 PDF 再转图片

用户原话：**"现在的截图逻辑会不会截得不准？毕竟坐标都是原始pdf的，为什么不先截pdf，然后再转成图片，是不是会更准确？"**

**根因分析**：当前实现是"先 `renderImageWithDPI` 整页到 `BufferedImage`，再用 `getSubimage` 按 y 像素裁剪"。这个流程有两个潜在不准确：

1. **像素 ↔ PDF 单位换算误差**：`pixelPerPdfUnit = pageImage.getHeight() / pageHeight` 取决于实际渲染图的高度。如果 PDF 的 mediabox / cropbox 与 `pageHeight`（来自 `DocumentProcessor.getPageBoundingBox`）不完全一致（比如 PDF 设了 trim box），换算比例会有偏差。
2. **`Math.round` 累积误差**：y 起点和高度各做一次 round，两个向上/向下取整方向叠加可能多 1-2 像素偏差或越界（参考 `2026-08-17-ImagesUtils.getPageSubImage越界RasterFormatException防御性clamp修复.md` 的同类问题）。

**新方案**：在 PDF 坐标层面完成裁剪，然后让 PDFBox 渲染器只渲染这块区域。

PDFBox 的 `PDFRenderer.renderImageWithDPI(pageIndex, dpi)` 内部用 `PDPage.getCropBox()` 决定渲染区域。如果我们**临时修改 cropBox 为我们想要的 PDF 区域**，渲染器就直接输出那块区域的 PNG，像素尺寸精确等于 `cropBox × DPI / 72`，零换算误差。

**关键约束**：

- PDFBox 用**左下原点**（bottom-left origin），但 `clipYPdf` 是**左上原点**（与 HEADER_POS/FOOTER_POS 一致），所以 y 必须翻转：`pdfY = cropBox.height - clipYPdf[1]`
- 横坐标截满：`pdfX = 0`，`pdfW = originalCropBox.getWidth()`（用 cropBox 宽度而不是传入的 pageWidth —— 三处收敛：getPageBoundingBox、entry.image_width、renderPage 都基于 cropBox）
- 必须 **try-finally 恢复 cropBox**，否则后续若有人再读这个 page 会拿到被改的值
- `pdfH <= 0` 时返回 null（header 和 footer 重叠或夹掉整页的情况）

最终 renderPage：

```java
private static BufferedImage renderPage(File pdfFile, int pageNumber, double[] clipYPdf) throws IOException {
    try (org.apache.pdfbox.pdmodel.PDDocument sourceDoc = Loader.loadPDF(pdfFile)) {
        PDFRenderer renderer = new PDFRenderer(sourceDoc);
        org.apache.pdfbox.pdmodel.PDPage page = sourceDoc.getPage(pageNumber);

        if (clipYPdf == null) {
            return renderer.renderImageWithDPI(pageNumber, 200.0f);
        }

        PDRectangle originalCropBox = page.getCropBox();
        float pdfY = (float) (originalCropBox.getHeight() - clipYPdf[1]);
        float pdfH = (float) (clipYPdf[1] - clipYPdf[0]);
        if (pdfH <= 0) {
            return null;
        }
        page.setCropBox(new PDRectangle(0.0f, pdfY, originalCropBox.getWidth(), pdfH));
        try {
            return renderer.renderImageWithDPI(pageNumber, 200.0f);
        } finally {
            page.setCropBox(originalCropBox);
        }
    }
}
```

`renderStreamTablePageScreenshot` / `renderFormulaPageScreenshot` 签名去掉了 `pageHeight` 参数（renderPage 自己从 cropBox 拿）。

### 回合 7：用户的三条后续要求

**当前 `writeOcrDetectionJson` 结构**：Pass 1（stream-table 命中）→ Pass 1.5（formula 命中）→ Pass 2（图片命中：≤4 items、无表格、有大图 `image_height/page_height > 0.8`）。Pass 2 原本直接用 `bestImage.get("content")[0]` 的 url 写 entry，没有重新渲染。

用户提出三条要求：

1. **Pass 2 跳过 Pass 1/1.5 已截图的页面**：避免同一页出现多个 entry（消费者"每页至多一张图"）。
2. **Pass 2 也按 HEADER_POS/FOOTER_POS 截图**：截图逻辑与 Pass 1 一致——重新渲染整页（裁剪掉页眉页脚），而不是用嵌入图片的 url。
3. **Pass 1/1.5 命中时也要设 `page.put(JsonName.IS_OCR, true)`**：跟 Pass 2 行为对齐，被截图的页面在主 JSON 里都标 `is_ocr=true`。

**实现细节**：

- **Pass 2 跳过逻辑**：循环改成 `for (int i = 0; i < data.size(); i++)`（之前是 `for (Map<...> page : data)`，拿不到 index）。在条件 1-4 之前判断：
  ```java
  if (pageHaveStreamTables != null && i < pageHaveStreamTables.length && pageHaveStreamTables[i]) continue;
  if (pageHaveFormulas != null && i < pageHaveFormulas.length && pageHaveFormulas[i]) continue;
  ```
- **Pass 2 改截图**：删掉 `bestImage` / `bestRatio` 逻辑（不再用嵌入图片 url）；改为调新的 `renderOcrPageScreenshot` 重新渲染。entry 字段同样用 `image_width=pageWidth` / `image_height=capturedHeight`（裁剪后尺寸）。
- **Pass 1/1.5 加 `IS_OCR=true`**：在 `imageUrl != null` 之后、`ocrEntries.add(entry)` 之前加 `page.put(JsonName.IS_OCR, true)`。

**关键问题：page 修改是否需要 put 回 map？**

不会要。原因：`page` 是 `data.get(i)` 返回的 in-memory `LinkedHashMap`（Jackson 反序列化产物），`page.put(...)` 直接修改这个对象本身；`data` 是 `map.get(JsonName.DATA)` 返回的同一个 List 引用，`map` 持有同一个 List。后续 `mapper.writerWithDefaultPrettyPrinter().writeValue(jsonFileName, map)` 序列化整个对象图，自然带上 `is_ocr=true`。

**但是有一个持久化边界条件**：writeValue 只在 `if (config != null)` 分支内执行。如果调用方传 `config=null`，主 JSON 不会被回写，`is_ocr` 修改就丢了（主 JSON 文件里仍然是 `writePageToGenerator` 最初写的 `false`）。当前所有正常调用路径都用 `new Config()`（非 null），所以生产路径 OK；但写单元测试或绕过 `writeToCustomJson` 的代码要意识到这个限制。

**新增 `renderOcrPageScreenshot`**：与 `renderStreamTablePageScreenshot` / `renderFormulaPageScreenshot` 结构完全相同，差异仅在文件名 `{pdfBaseName}_ocr-{pageNumber}.png` 和 OSS objectKey `public/{basicEnv}/{topic}_{businessId}_ocr_{pageNumber+1}.png`。`_ocr-` 前缀命中 `cleanupLocalFiles` 的 `name.startsWith(pdfBaseName + "_")` 白名单，无需改白名单逻辑。

**抽取 `readPageDimensions(Map)` helper**：Pass 1 / 1.5 / 2 三处都重复 `page.get(HEIGHT)` + `page.get(WIDTH)` 的提取代码（虽然 Pass 2 原本只读 HEIGHT，但加 pageWidth 后三处都用得上）。抽到 helper 里：

```java
private static double[] readPageDimensions(Map<String, Object> page) {
    double pageWidth = 0.0;
    double pageHeight = 0.0;
    Object pageHeightObj = page.get(JsonName.HEIGHT);
    if (pageHeightObj instanceof Number) pageHeight = ((Number) pageHeightObj).doubleValue();
    Object pageWidthObj = page.get(JsonName.WIDTH);
    if (pageWidthObj instanceof Number) pageWidth = ((Number) pageWidthObj).doubleValue();
    return new double[]{pageWidth, pageHeight};
}
```

## 前置事实（已核实）

- `DocumentProcessor#getPageBoundingBox` 内部就是用 `page.getCropBox()` 构造 BoundingBox，所以 pageBoundingBox 与 cropBox 等价；任何"PDF 页面尺寸"在项目里都是指 cropBox。
- `JsonName.HEADER_POS = "header_pos"`，`JsonName.FOOTER_POS = "footer_pos"`，由本任务之前的工作流添加（在 `git diff` 里显示已 staged，JsonWriter 引用前已存在）。
- Jackson `writeNumberField` 重载：`short / int / long / float / double / BigInteger / BigDecimal`，**没有数组重载**；写数组字段必须 `writeArrayFieldStart + 多次 writeNumber` 或 `writeFieldName + writePOJO`。
- 项目内已有多个 `BoundingBox → JSON array` 的标准模式参考：`ContextUtils.rect`、`AnnotationNodeSerializer.boundingBox`、`TextSerializer.boundingBox`、`LineArtSerializer.boundingBox` 都是 `writeArrayFieldStart + 4 × writeNumber`。
- `org.apache.pdfbox.pdmodel.PDPage.setCropBox(PDRectangle)` 接受 null 表示清除（恢复为 mediaBox），传非 null 即覆盖；`getCropBox()` 在未设置时内部 fallback 到 mediaBox，返回非 null 的 PDRectangle。
- Java 源目标 11：`record`、switch pattern matching 等不可用（参考 `2026-08-20-每页无线表格检测have_stream_table字段与OCR截图.md` 已记录）。
- `org.apache.pdfbox.pdmodel.PDDocument` 与 `org.verapdf.pd.PDDocument` 同名冲突，必须 fully-qualified 写 `org.apache.pdfbox.pdmodel.PDDocument`。

## 已实现方案（最终态）

### 改动 1：JsonWriter 顶部 headerPos/footerPos 提取重构

```java
List<IObject> pageContents = contents.get(pageNumber);
Double[] headerPos = null;
Double[] footerPos = pageContents.size() > 1
        ? headerFooterPos(pageContents.get(pageContents.size() - 1), height)
        : null;
if (isHk) {
    pageContents = flattenHeaderFooterContents(pageContents);
} else {
    headerPos = pageContents.isEmpty() ? null : headerFooterPos(pageContents.get(0), height);
}
```

注意：isHk 分支把 headerPos 计算放到 `else` 里 —— 这是因为 `flattenHeaderFooterContents` 会把第一个 SemanticHeaderOrFooter 展开成它的 contents（不再是 SemanticHeaderOrFooter），isHk 时再调 `headerFooterPos(pageContents.get(0), ...)` 会拿到错误的对象。footer 不受影响（flatten 只处理"第一个" header/footer，footer 是最后一个，类型保留）。

### 改动 2：helper 方法

```java
private static Double[] headerFooterPos(IObject content, double height) {
    if (!(content instanceof SemanticHeaderOrFooter)) {
        return null;
    }
    BoundingBox box = ((SemanticHeaderOrFooter) content).getBoundingBox();
    return new Double[]{
            box.getLeftX(),
            height - box.getTopY(),
            box.getRightX(),
            height - box.getBottomY()
    };
}

private static void writePosArray(JsonGenerator gen, String fieldName, Double[] pos) throws IOException {
    if (pos == null) {
        return;
    }
    gen.writeArrayFieldStart(fieldName);
    for (Double value : pos) {
        if (value == null) gen.writeNull();
        else gen.writeNumber(value);
    }
    gen.writeEndArray();
}
```

### 改动 3：writeOcrDetectionJson 中加 computeClipY + readPosArray + 调用点改造

```java
private static double[] computeClipY(Map<String, Object> page) {
    Object pageHeightObj = page.get(JsonName.HEIGHT);
    if (!(pageHeightObj instanceof Number)) return null;
    double pageHeight = ((Number) pageHeightObj).doubleValue();
    if (pageHeight <= 0) return null;
    Double[] headerPos = readPosArray(page.get(JsonName.HEADER_POS));
    Double[] footerPos = readPosArray(page.get(JsonName.FOOTER_POS));
    if (headerPos != null && footerPos != null) {
        return new double[]{headerPos[3], footerPos[1]};
    }
    if (headerPos != null) return new double[]{headerPos[3], pageHeight};
    if (footerPos != null) return new double[]{0.0, footerPos[1]};
    return null;
}

private static Double[] readPosArray(Object value) {
    if (!(value instanceof List)) return null;
    List<?> list = (List<?>) value;
    if (list.size() < 4) return null;
    Double[] result = new Double[4];
    for (int i = 0; i < 4; i++) {
        Object element = list.get(i);
        if (!(element instanceof Number)) return null;
        result[i] = ((Number) element).doubleValue();
    }
    return result;
}
```

stream-table / formula 两个分支的 entry 写入：

```java
double[] clipY = computeClipY(page);
String imageUrl = renderStreamTablePageScreenshot(pdfFile, outputFolder, pdfBaseName, i,
    ossEnabled, ossConfig, obsClient, clipY);
if (imageUrl == null) continue;
Map<String, Object> entry = new LinkedHashMap<>();
entry.put(JsonName.PAGE_INDEX, page.get(JsonName.PAGE_INDEX));
entry.put("image_url", imageUrl);
double capturedHeight = clipY != null ? (clipY[1] - clipY[0]) : pageHeight;
entry.put("image_height", capturedHeight);
entry.put("image_width", pageWidth);
ocrEntries.add(entry);
```

### 改动 4：renderPage 基于 cropBox

详见回合 6 的代码片段。

### 改动 5：render 函数签名清理

- `renderStreamTablePageScreenshot` / `renderFormulaPageScreenshot` 签名加 `double[] clipYPdf`，去掉 `pageHeight` 参数（renderPage 自己从 cropBox 拿）。
- 返回类型保持 `String`（仅返回 url/路径，entry 字段在调用方写入）。
- import 新增 `org.apache.pdfbox.pdmodel.common.PDRectangle`。

### 改动 6（回合 7）：新增 `renderOcrPageScreenshot`

Pass 2（图片命中）的截图函数。结构与 `renderStreamTablePageScreenshot` / `renderFormulaPageScreenshot` 完全一样，差异仅：

- 本地文件名：`{pdfBaseName}_ocr-{pageNumber}.png`（替代 Pass 2 原本直接用嵌入图 url）
- OSS objectKey：`public/{basicEnv}/{topic}_{businessId}_ocr_{pageNumber+1}.png`
- 全部走 cropBox 方案 + temp 桶 + 与前两个 render 函数相同的清理/回退逻辑

`_ocr-` 前缀命中 `cleanupLocalFiles` 的 `name.startsWith(pdfBaseName + "_")` 白名单（任何 `_xxx-` 形式都中），无需改 `isRelatedToCurrentPdf`。

### 改动 7（回合 7）：抽取 `readPageDimensions(Map)` helper

Pass 1 / 1.5 / 2 三处都重复 `page.get(HEIGHT)` + `page.get(WIDTH)` 的提取代码（之前 Pass 2 原本只读 HEIGHT，但加了 pageWidth 进 entry 后三处都用得上）。抽到 helper 统一：

```java
double[] dims = readPageDimensions(page);
double pageWidth = dims[0];
double pageHeight = dims[1];
```

返回 `double[2] = {width, height}`，缺失字段或非 Number 元素都返回 0.0。Pass 1/1.5 用 `pageHeight` 还需自己判 `<= 0` 走 fallback（沿用之前的逻辑），helper 只负责"读字段 + 转 double"。

### 改动 8（回合 7）：Pass 1/1.5 加 `page.put(IS_OCR, true)`

在 `imageUrl != null` 之后、`ocrEntries.add(entry)` 之前加：

```java
page.put(JsonName.IS_OCR, true);
```

**持久化路径**：`writeOcrDetectionJson` 修改的是 in-memory `map`（`map.put(DATA, data)` 不需要，page 是 in-memory 引用，put 直接修改对象）；后续 `mapper.writerWithDefaultPrettyPrinter().writeValue(jsonFileName, map)` 序列化整个对象图，`is_ocr=true` 会被写回主 JSON 文件。

**边界条件**：writeValue 只在 `if (config != null)` 分支内执行。如果调用方传 `config=null`，主 JSON 不会被回写，`is_ocr` 修改就丢了。当前所有正常调用都用 `new Config()`，所以生产路径 OK；但绕过 `writeToCustomJson` 的代码（如直接读主 JSON 的脚本）会看不到 `is_ocr`。

### 改动 9（回合 7）：Pass 2 跳过已截图页 + 改为新截图

- **循环改成 `for (int i = 0; i < data.size(); i++)`**：拿 index 才能查 `pageHaveStreamTables[i]` / `pageHaveFormulas[i]`。
- **跳过已截图页**：
  ```java
  if (pageHaveStreamTables != null && i < pageHaveStreamTables.length && pageHaveStreamTables[i]) continue;
  if (pageHaveFormulas != null && i < pageHaveFormulas.length && pageHaveFormulas[i]) continue;
  ```
- **删掉 `bestImage` / `bestRatio` 逻辑**：原本是"找高度最高的 image item，把它的 `content[0]` url 写到 entry"。现在统一改为调 `renderOcrPageScreenshot` 重新渲染。
- **保留大图判定**：仍然用 `image_height / page_height > 0.8` 判定命中（这是 Pass 2 的触发条件），但命中后不再用嵌入图 url，而是调 renderOcrPageScreenshot。
- **entry 字段对齐 Pass 1/1.5**：`image_width=pageWidth`、`image_height=capturedHeight`（裁剪后尺寸），`image_url` 是新截图 url。

## 验证结果

- `mvn compile` BUILD SUCCESS（多次验证均通过）。
- `git stash` 对照验证 `IncludeHeaderFooterJsonIntegrationTest.jsonOutputRespectsIncludeHeaderFooterFlag` 失败是**预先存在的**（fixture PDF `PDFUA-Ref-2-04_Presentation.pdf` 检测不到 header/footer，断言信息明确说"got 0. If detection regressed, pick a different fixture PDF"），与本次改动无关。
- 未补单元测试（用户未要求）。
- 未做端到端 PDF 验证（需用户手动跑含 header/footer 的真实 PDF，确认 `_ocr.json` 中截图实际只截了正文区）。

## 关键决策（Key Decisions）

1. **PDF 层面裁剪而非像素层**：用户主动提出"为什么不先截 pdf 再转图片"。`getSubimage` 的累积误差（round + 比例换算）不可控，cropBox 方案零误差。

2. **用 cropBox 而非 mediabox**：`DocumentProcessor#getPageBoundingBox` 用 cropBox，三处（getPageBoundingBox / entry.image_width / renderPage 渲染基准）都收敛到 cropBox，不会出现 mediabox 与 cropbox 不一致导致的错位。

3. **entry 字段语义 = 裁剪后的 PDF 区域尺寸（PDF 单位），不是图片像素尺寸**：横坐标截满用 pageWidth，纵坐标是 `clipY[1] - clipY[0]` 或 fallback 到 pageHeight。下游 OCR 拿到这两个值就能直接做坐标映射。

4. **`new Double[4]` 改成 null**：避免输出无意义的 `[null, null, null, null]`，用户语义明确。

5. **HEADER_POS/FOOTER_POS 在 writePosArray 里逐元素判 null**：`Double[]` 里残留 null 元素（防御兜底），用 `writeNull()` 写出，而不是 `writeNumber(null)` 抛 NPE。

6. **`renderPage` 不再需要 pageHeight 参数**：从 `page.getCropBox()` 拿，与 entry 写入用的 pageHeight/pageWidth 在不同地方但语义对齐（都是 cropBox 尺寸）。

7. **isHk 时 headerPos 放在 else 分支**：因为 `flattenHeaderFooterContents` 会把第一个 SemanticHeaderOrFooter 展开成它的 contents，原地再调 `headerFooterPos(pageContents.get(0), ...)` 会拿到非 SemanticHeaderOrFooter 的对象、返回 null，是合理的"isHk 时不知道 header bbox"语义。
8. **三处截图 Pass 共享同一个裁剪+上传 pattern**：Pass 1 / 1.5 / 2 的 render 函数除了文件名（`_streamtable-` / `_formula-` / `_ocr-`）和 OSS objectKey 后缀（`_streamtable_` / `_formula_` / `_ocr_`）外完全一样。本可以再抽一层 `renderPageScreenshotWithSuffix(suffix)` 进一步 DRY，但三个函数各自有独立的 WARN 日志消息（"PDF not found for stream-table screenshot" 等），抽公共函数会让日志失去"是哪类截图失败"的信息；当前三份重复比"模糊日志"更可接受。
9. **in-memory mutation 通过引用传递，不需要 `map.put(DATA, data)`**：`page` 是 Jackson 反序列化产物 `LinkedHashMap`，`page.put(...)` 直接修改对象本身；`map` 持有同一个 page 引用；后续 `mapper.writeValue` 序列化整个对象图自动带上修改。这是 Java 引用语义的隐性约定，**不要**为了让代码"看起来防御性"加冗余 `map.put(DATA, data)`——会误导后续维护者以为这行在做重要的事。
10. **Pass 1/1.5 也要标 `is_ocr=true`，保持三 Pass 行为对称**：之前只有 Pass 2 标 `is_ocr`，意味着 stream-table / formula 命中的页面在主 JSON 里仍然 `is_ocr=false`，下游 OCR 消费方可能会错过这些页面。统一后"被截图的页面 == is_ocr=true 的页面"是显式约定。
11. **Pass 2 触发条件保留大图判定**：即使改成新截图，仍然只在"items 少 + 无表格 + 有大图（>80% 页高）"时才触发，**不是**给所有 pages 都截图。这样过滤掉的"空白页 / 文本密集页 / 多 items 页"等本来就不该走 OCR，OCR 资源用在该用的地方。

## 风险与已知限制

- **临时修改 cropBox 的副作用**：必须 try-finally 恢复，否则后续代码再读这个 page 会拿到被改的值。当前代码是 try-with-resources `PDDocument`，且渲染完就出 try 块，副作用窗口很短；理论上其他线程同时渲染同 PDF 同 page 才可能受影响，项目内没有这种并发。
- **`renderPage` 仍传入 pageHeight 是历史残留**：当前实现 renderPage 自己从 cropBox 拿，pageHeight 参数已去掉；如果未来要保留传参入口（比如传入 mediabox 高度），需要重新加。
- **entry 字段语义未文档化**：当前 `image_width` / `image_height` 是 PDF 单位（不是像素），调用方如果误以为是像素会导致 OCR 坐标错误。**建议在 entry 加注释字段**，或在 JsonName 里加 `WIDTH/HEIGHT` 文档字符串说明。
- **OCR 调用方期望像素而非 PDF 单位**：如果下游用 `image_width/height` 算像素坐标（与图片实际像素做比例），就会出错。当前需要用户去跟下游 OCR 消费方确认字段语义。
- **未做端到端验证**：未在真实 PDF 上跑过确认截图区域符合预期；建议用户手动跑一遍带 header/footer 的 PDF，对比 `_ocr.json` 截图与整页图的差异。
- **`cropBox` 越界**：理论上 `originalCropBox.getHeight() - clipYPdf[1]` 应该是非负数，但 `HEADER_POS` / `FOOTER_POS` 是 detection 阶段的结果，可能因边缘情况给出超出 pageHeight 的值；目前 renderPage 没做 clamp，依赖 detection 上游保证。若担心可在 `pdfY < 0` 时 clamp 到 0、`pdfH > originalHeight` 时 clamp 到 originalHeight。
- **`getPageBoundingBox` 在 `StaticResources.getDocument()` 为 null 时返回 null**：但 `writeOcrDetectionJson` 是 `JsonWriter` 内部调用，此时 `pageHeight = 0`，`computeClipY` 返回 null，回退到截整页。行为正确但不会触发裁剪。
- **`is_ocr=true` 的持久化依赖 `if (config != null)` 分支**：见改动 8 的"边界条件"。生产路径都传 `new Config()`，所以 OK；但任何绕过 `writeToCustomJson` 直接读主 JSON 的脚本可能看不到 `is_ocr=true`。
- **三个 render 函数重复代码**：Pass 1/1.5/2 的 render 函数除了文件名/objectKey 后缀外结构完全一样（约 60 行 × 3 = 180 行）。当前不抽公共函数，理由见决策 8。如果将来加第四种截图类型（如"页脚 OCR"），建议先抽 `renderPageScreenshot(pdfFile, outputFolder, pdfBaseName, pageNumber, ossEnabled, ossConfig, obsClient, clipYPdf, fileSuffix, logLabel)`，把 WARN 日志也参数化（"PDF not found for {logLabel} screenshot"），再各自加薄薄的"拼文件名/objectKey"层。
- **`renderOcrPageScreenshot` 与 stream-table/formula 互斥未保证**：Pass 2 用 `pageHaveStreamTables[i] / pageHaveFormulas[i]` 跳过，但没有相反方向的 mutex——也就是说如果 Pass 1 因 OSS 失败 `imageUrl == null` 跳过，但 Pass 2 命中，仍会出一张 OCR entry。这是正确的（OSS 失败不该剥夺 OCR），但消费者需要注意：Pass 1 entry 缺失可能是因为 OSS 上传失败，而不是该页没有 stream-table。

## 构建 / 运行

- 编译：`cd java/opendataloader-pdf-core && mvn compile -q`
- 跑测试（已确认与本次无关的失败）：`cd java/opendataloader-pdf-core && mvn test -Dtest=IncludeHeaderFooterJsonIntegrationTest` 会失败（fixture 问题），其余 stream-table / formula 相关路径无专门单测。

## 相关文件（Relevant Files）

- `java/opendataloader-pdf-core/src/main/java/org/opendataloader/pdf/json/JsonWriter.java`
  - 顶部 headerPos/footerPos 提取重构 + isHk 分支调整（约 518-525）
  - 写 HEADER_POS/FOOTER_POS 字段（约 539-540）
  - `headerFooterPos` helper（约 582-591）
  - `writePosArray` helper（约 599-611）
  - `computeClipY` / `readPosArray` helpers（约 1179-1223）
  - `readPageDimensions` helper（约 1234 起）
  - writeOcrDetectionJson Pass 1 加 `IS_OCR=true` + 用 readPageDimensions（约 1306-1320）
  - writeOcrDetectionJson Pass 1.5 加 `IS_OCR=true` + 用 readPageDimensions（约 1352-1370）
  - writeOcrDetectionJson Pass 2 跳过已截图页 + 改为 renderOcrPageScreenshot（约 1383-1470）
  - `renderStreamTablePageScreenshot` / `renderFormulaPageScreenshot` 签名加 clipYPdf 参数（约 1504-1522, 1597-1615）
  - 新增 `renderOcrPageScreenshot`（紧跟 `renderFormulaPageScreenshot` 之后，约 1686-1750）
  - `renderPage` 改用 cropBox 方案（约 1756-1790）
  - import 新增 `org.apache.pdfbox.pdmodel.common.PDRectangle`
  - **行号仅大致参考**，文件总行数会随其他任务改动而漂移；按方法名 / 注释定位更稳。

## 备忘

- **PDFBox cropBox 是裁剪黄金入口**：渲染任意 PDF 区域时，不要"先渲染再 getSubimage"——`renderImageWithDPI` 内部读 cropBox，临时改 cropBox 就能拿到任意区域的精确图片。这是 PDFBox 与 ImageIO 配合的常规 trick，记住。
- **PDF 原点方向**：PDFBox 用左下原点 (`y` 从下往上增长)，但 BoundingBox 和 HEADER_POS/FOOTER_POS 都用左上原点 (`y` 从上往下增长)。任何 PDF 坐标翻转：`pdfY = pageHeight - bboxTopY`。这个翻转会反复出现，已经形成项目内的隐性约定。
- **`DocumentProcessor#getPageBoundingBox` 用 cropBox 而非 mediabox**：所有需要"页面尺寸"的地方都应该用 `getPageBoundingBox`，不要用 mediabox。这点跟前面 2026-08-20 那两次 stream-table / formula 任务的 memory 文件保持一致。
- **Jackson 数组字段写法**：项目内标准是 `writeArrayFieldStart + 多次 writeNumber`，已经见过 `ContextUtils`、`TextSerializer`、`LineArtSerializer`、`AnnotationNodeSerializer` 等都这么写。新增 array 字段照搬这个模式即可，不要用 `writeNumberField(Double[])`（不存在这个重载）。
- **Java 11 限制**：`record` 不可用，临时内部类继续用 `private static final class`。Project 升级到 Java 17 前不要在 `JsonWriter` 里引入 record。
- **用户迭代风格**：本次任务用户主动改了 4 次方向（重构+修编译错 → null 语义 → OCR 裁剪 → entry 字段语义 → cropBox 方案）。每次都是"先给方案→我反馈问题→再改"，**用户主导方向**，实现方不要"过度设计"——把当前轮做对就行，不要预判下一轮。
- **entry 字段语义文档化**：如果 `image_width` / `image_height` 在 `_ocr.json` 下游被消费，建议在 `JsonName` 加常量注释或加 schema 文档，说明"PDF 单位、对应裁剪后区域"。这是当前隐性约定，未来容易踩坑。
- **in-memory mutation 通过引用传递是 Java 默认行为**：修改 `page.put(...)` 不需要 `map.put(DATA, data)`。但**不要把这种"无需 put 回 map"当成反例去做防御性 put**——会误导维护者以为这行代码在做重要工作。如果非要 put，应该加注释说明原因。
- **`is_ocr` 字段的写回依赖 `writeToCustomJson` 的完整流程**：主流程是 in-memory 修改 → `mapper.writeValue` 写回。任何想"独立读主 JSON 看 is_ocr"的脚本，必须走完 `writeToCustomJson` 才能看到正确值；中途退出或绕过此函数会读到 stale `is_ocr=false`。
- **三 Pass 互斥逻辑要对称**：当前是 Pass 1 → Pass 1.5 检查 Pass 1 → Pass 2 检查 Pass 1+1.5。如果未来加 Pass 0.5（假设），需要把 Pass 1.5 / 2 都加上对它的 mutex 检查。建议把这套 mutex 集中到一个 `boolean isPageAlreadyHandled(int i)` helper，否则三个 Pass 各自判断容易漏。
- **回合 7 三个新要求的根因**：用户把 OCR 截图从"单一入口"扩展到"三种触发场景的完整覆盖"，且要求对称（都标 is_ocr、都按 cropBox 裁剪、都跳过已截图页）。这是一个**完整化设计**的延伸——之前 Pass 1/1.5 是后来加的，没考虑与 Pass 2 的对称性；这次补齐。如果未来再加 Pass 0.5 / 1.7 等，记得沿用"对称 + 互斥 + 统一标 is_ocr"三原则。
