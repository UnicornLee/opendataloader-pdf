# opendataloader-pdf 任务记忆 — 2026-09-17（JSON 序列化遇 NaN 导致整页 JSON 丢失：根因定位与修复）

> 样本：`docs/pdf/202303251679660111823147.pdf`（招股说明书，201 页；第 3 页为异常图片页）
> 分支：`ocr-unification-20260820`
> 触发方式：用户跑 `org.opendataloader.pdf.DebugSample` 时看到 JUL 警告
> 本轮 = **定位 → 修复 → 验证**（用户明确要求"计算 MARGIN_TOP 时遇到 NaN 就设置为默认值 5"）

## 现象（用户贴出的完整告警）

```
警告: ...\202303251679660111823147.pdf - Error when generating JSON data of page 3:
      fallback to empty page: JsonMappingException: Character N is neither a decimal digit number,
      decimal point, nor "e" notation exponential mark. (through reference chain: java.util.HashMap["margin_top"])
Caused by: java.lang.NumberFormatException: Character N is neither a decimal digit number, ...
	at java.base/java.math.BigDecimal.<init>(BigDecimal.java:582)
	at org.opendataloader.pdf.json.serializers.DoubleSerializer.round(DoubleSerializer.java:54)
	at org.opendataloader.pdf.json.JsonWriter.generateJsonPageContentData(JsonWriter.java:917)
```

即：第 3 页 JSON 生成失败 → 该页被兜底成"空页"，页面内容**静默丢失**。

## 结论速览（TL;DR）

存在两条独立的缺陷链，叠加才造成"整页丢失"这个后果：

1. **数据侧**：第 3 页唯一的 item 是一个**纵向坐标为 NaN 的 `ImageChunk`**
   （实测 `box=[-711.456543, NaN, 761.8010177000001, NaN]`，横向正常、纵向 NaN）。
2. **健壮性侧**：
   - `DoubleSerializer.round()` 用 `new BigDecimal(Double.toString(value))`，而 `Double.toString(NaN)` = `"NaN"`，
     `BigDecimal(String)` **不接受 NaN / Infinity**（只有 `BigDecimal(double)` 有专门分支）→ 抛异常。**`Character N` 就是 `NaN` 的首字母**（若是 Infinity 会报 `Character I`）。
   - `BoundingBox.isEmpty()` 用比较运算判定（`leftX > rightX + EPS`、`bottomY > topY + EPS`），
     **NaN 参与比较恒为 false** → 这个坏盒子被判为"非空合法盒"（日志 `emptyBox=false`），躲过了所有下游校验。
   - `JsonWriter.writeToCustomJson()` 逐页 try/catch，异常时写 `placeholderPageJson()` → **内容丢失且只有一行 WARNING**。

修复后：告警消失，第 3 页从 `items: []` 恢复为 1 个 image item。

## 定位过程（逐步实测）

### 第 1 步：从异常信息反推"值是非有限数"

`DoubleSerializer.round()` 的唯一可能抛点数就是那行 `BigDecimal` 构造。

```java
// 修复前（DoubleSerializer.java:54）
BigDecimal bigDecimalValue = new BigDecimal(Double.toString(value));
```

`Double.toString` 对 NaN/±Infinity 分别产出 `"NaN"` / `"Infinity"` / `"-Infinity"`，字符串版构造器均不接受。
→ 结论：被序列化的某个 double 字段是 NaN 或 Infinity，且**异常链指名 key 是 `margin_top`**。

### 第 2 步：确认"兜底吞掉了整页"

```java
// JsonWriter.writeToCustomJson() —— 逐页生成，异常则写占位页
} catch (Exception pageEx) {
    LOGGER.log(Level.WARNING, inputPdfName + " - Error when generating JSON data of page "
        + (pageNumber + 1) + ": fallback to empty page: ...", pageEx);
    pageJson = placeholderPageJson(pageNumber + 1);   // items: []
}
```

用 python 读已产出的 JSON 核对：
```
pages 201
page_index 1 items 1
page_index 2 items 1
page_index 3 items 0     ← 空页，与警告一致
page_index 4 items 25
```

### 第 3 步：插桩抓出"哪个对象、哪个字段是 NaN"

手段：临时给 `JsonWriter.generateJsonPageContentData()` 加开关 `-DdbgNaN=1` 的打印，
并新建不依赖 PaddleOCR 的临时入口 `DebugNaNTmp`（当时 OCR 服务 192.168.1.97:8088 不可达，否则每页要等 60s 超时）。

```
[NANDBG] pg=2 item org.verapdf.wcag.algorithms.entities.content.ImageChunk
         box=[-711.456543, NaN, 761.8010177000001, NaN]
         marginTop=NaN prevBottomY=807.87 height=807.87 emptyBox=false
```

- `pg=2` 是 0-based → 第 3 页 ✓
- 该页 `contents=1`，**只有这一个对象**，所以它的 NaN 直接决定整页成败
- 横向 `[-711.46, 761.80]` 本身也异常（页宽仅 595.28）
- `emptyBox=false` 证实 `isEmpty()` 被 NaN 骗过

### 第 4 步：dump 页面结构，定位到"绘制这张图的矩阵"

手段：单文件 `tmp_output/DumpPage.java`（pdfbox 3.0.4；
注意 pdfbox 3 里 `PDPage.getContents()` 返回 `InputStream`，要 `IOUtils.toByteArray(...)`）。

```
mediaBox=[0.0,0.0,595.28,807.87]
xobj Im0 PDImageXObject  w=3840 h=2160 bpc=8
--- content len=15835997 ---        ← 内容流 15.8 MB
### Im0 Do @15812189 ctx: ... q|1473.2575607 0 0 828.7073779 -711.456543 -9.1160693 cm|/Im0 Do|
token nan -> -1 ; token NaN -> -1 ; token inf -> -1 ; token Inf -> -1
```

关键：绘制 `Im0` 的矩阵是 `1473.2575607 0 0 828.7073779 -711.456543 -9.1160693 cm`，
与解析出的包围盒逐位对应 —— **x 分量完全吻合**（`x1 = e = -711.456543`、`x2 = e + a = 761.801`），
**只有 y 是 NaN**（而 `f = -9.116` 明明是有限值）。
并且内容流里**不存在** `nan/NaN/inf` 字面量（索引全是 -1）→ 不是 PDF 显式写入的。

### 第 5 步：定位到上游构造点

`veraPDF-validation/wcag-validation/.../chunks/ChunkParser.java`（`Do` 与 `BI` 内联图两个分支都调用它）：

```java
private BoundingBox parseImageBoundingBox() {
    double x1 = graphicsState.getCTM().getTranslateX();
    ...                                   // 用 CTM 的 scale/shear 推出 x2/y1/y2
    double y1 = graphicsState.getCTM().getTranslateY();
    ...
    return new BoundingBox(pageNumber, x1, y1, x2, y2);
}
```

它**直接取当前 CTM 的分量**，不做任何有限性校验。该页有大量嵌套 `q/Q` + 透明组 Form，
CTM 的纵向分量在累积中变成了 NaN（横向仍正常），于是产生了一个"半 NaN"的包围盒。

### 第 6 步：确认与本次其它改动无关

读改动前基线语料 `tmp_output/corpus_before/202303251679660111823147.digest`：

```
==== corpus_before   pages with items 200
    p1 9   p2 1   p3 0   p4 25   p5 6   p6 8
==== corpus_after5   pages with items 200
    p1 9   p2 1   p3 0   p4 25   p5 6   p6 8
```

**改动前 p3 同样是 0 items** → 该缺陷早已存在，不是回归。

## 根因（一句话）

第 3 页的 `Im0` 是用一个**纵向分量已退化为 NaN 的 CTM** 绘制的（veraPDF `parseImageBoundingBox` 原样取用），
产出的 `ImageChunk` 纵向坐标为 NaN；`BoundingBox.isEmpty()` 因 NaN 比较恒假而放行；
最终 `margin_top = prevBottomY - topY = NaN`，`DoubleSerializer.round()` 对 NaN 无策略、直接抛异常，整页被兜底成空页。

## 修复

### 1. `JsonWriter`：新增 `marginTop(...)`，16 处 `MARGIN_TOP` 赋值全部走它

```java
private static final double DEFAULT_MARGIN_TOP = 5.0;

private static double marginTop(double previousBottomY, double currentTopY) {
    return marginTop(previousBottomY - currentTopY);
}

private static double marginTop(double margin) {
    return Double.isFinite(margin) ? margin : DEFAULT_MARGIN_TOP;
}
```

覆盖：15 个 `put(JsonName.MARGIN_TOP, …)` + 1 个页面级 `writeNumberField(JsonName.MARGIN_TOP, …)`
（批量替换脚本 `tmp_output/apply_margin.py`，正则 `(\.put\(JsonName\.MARGIN_TOP, )(.*?)(\);)` → `\1marginTop(\2)\3`；
页面级那处是单参数形态，故保留一个单参重载）。

### 2. `DoubleSerializer`：非有限值返回 0（**必要配套**）

只改 MARGIN_TOP 是**不够的**：同一个坏 `ImageChunk` 的
`y0 = height - topY`、`y1 = height - bottomY`、`height`、`font_underline_size` 同样是 NaN，
异常只会换个 key（`HashMap["y0"]`）继续炸。而且 JSON 规范本身不允许 NaN 字面量。

```java
private static double round(double value, int decimalPlaces) {
    if (decimalPlaces < 0) {
        throw new IllegalArgumentException();
    }
    if (!Double.isFinite(value)) {
        // NaN / ±Infinity 不是合法 JSON 数字，且 BigDecimal(String) 无对应表示，
        // 不兜底会让整页序列化失败。
        return 0.0;
    }
    BigDecimal bigDecimalValue = new BigDecimal(Double.toString(value));
    ...
}
```

## 验证

| 项 | 结果 |
|---|---|
| 编译 | 通过 |
| 重跑该 PDF 日志 | `fallback to empty page` / `JsonMappingException` / `NumberFormatException` **全部消失** |
| 第 3 页 | `items` 由 0 → 1：`image {x0:-711.457, y0:0.0, x1:761.801, y1:0.0, width:1473.258, height:0.0, margin_top:5.0}` |
| 其它页 | p1=9、p2=1、p4=25、p5=6 与改动前一致，无副作用 |
| 单测 | 57/57 绿（`JsonWriterTableCellGroupingTest`、`ElementMetadataSerializerTest`、`FlowchartProcessorTest`、`ShapeRecognizerTest`、`ArrowE2ETest`、`BarChartProcessorTest`、`CatalogBookmarkProcessorTest`） |

## 观测手法（可复用）

1. **无 OCR 的临时入口**：复制 `DebugSample` 去掉 `paddleUrl`，避免外部 OCR 不可达时每页 60s 超时。
2. **插桩开关**：`-DdbgNaN=1` 只打印非有限坐标的对象（类名 + box + 各分量），避免日志爆炸。
3. **单文件 dump 页面**：`tmp_output/DumpPage.java`（pdfbox 3.0.4）；用 `content.indexOf("/Im0 Do")` 回溯矩阵上下文，
   并用 `content.indexOf("nan")` 证明"PDF 里没有显式 NaN"。
4. **基线佐证**：用改动前的语料 digest 证明"现象早已存在"，避免误判为回归。

## 遗留（未做）

1. **上游治本**：`ChunkParser.parseImageBoundingBox()` 加 `Double.isFinite` 校验（非有限时回退到图像实际尺寸或跳过该 chunk）。
2. `BoundingBox.isEmpty()` / `isStillOnPage()` 等判定补 `isFinite`，让坏盒子在下游被自然过滤。
3. `JsonWriter` 兜底策略可从"丢整页"改为"剔除坏 item 重试"。
4. 现状下第 3 页会输出一个**退化尺寸的 image item**（`height=0`）；如需彻底排除非法 chunk，可再加一道过滤。

## 相关产物

`tmp_output/nan_run.log`（插桩输出）、`nan_verify.log`（修复后）、`DumpPage.java`、`apply_margin.py`、
`nan_scratch/202303251679660111823147.json`（修复后产物）。
