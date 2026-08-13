# opendataloader-pdf 任务记忆 — 2026-08-13（PDFBox fill 回退坐标转换方向修复）

## 目标（Goal）

修复 `-84` 流程图 PDF 中箭头 1 的 bbox 再次丢失填充箭头头尖的问题：
- 期望：`arrow1.bbox.bottomY ≈ 244.87`（含三角箭头尖）
- 实际（修复前）：`arrow1.bbox.bottomY = 249.87`（仅杆，丢失箭头尖）

## 问题复现

运行现有回归测试 `ArrowE2ETest.arrowOneHeadRecoveredFromPdfBoxFill`：

```
Arrow 1 bbox must extend onto its filled arrowhead tip
expected: <244.87> but was: <249.87>
```

箭头 1 被识别为 `ShapeChunk.TYPE_ARROW`，但 bbox 仅为杆：`[298.5, 249.87, 299.5, 267.97]`。

## 定位过程

### 第 1 步：确认 ShapeRecognizer 回退逻辑是否被覆盖

`ShapeRecognizer` 中 `recognize(IDocument, Map<Integer, List<BoundingBox>>)` 和 `findArrowBBox` 均存在，且 `DocumentProcessor.preprocessing` 仍调用：

```java
ShapeRecognizer.recognize(document, extractPageFillBoxes(pdfName, pdDocument.getNumberOfPages()));
```

回退逻辑没有被删除或覆盖。继续排查回退数据源。

### 第 2 步：观察 artifact 层当前状态

在 `-84` 第 0 页 artifact 层中发现：

- 杆：`LineChunk bbox=[298.5, 249.87, 299.5, 267.97]`
- 填充箭头头被合并后的 MCID 大容器：`bbox-only LineArtChunk [133.575, 148.57, 462.975, 284.97]`
- 箭头 2 的头幸运保留：`bbox-only LineArtChunk [295.5, 184.62, 302.5, 208.22]`

`pickArrowhead(shaft, filledArtBoxes)` 会先看到大容器，但大容器两端都外伸（`extendsBefore == extendsAfter`），被丢弃；随后没有别的 artifact 候选，于是进入 PDFBox fill 回退。

### 第 3 步：dump PDFBox fill 回退候选

用 `GetDrawings` 直接提取 `-84` 第 0 页 x≈296 的 fill：

```
pageHeight=841.92 rotation=0
fill FILL raw=[x0=296.0, y0=244.8699951171875, x1=302.0, y1=267.9700012207031]
```

PDFBox 输出的是 **y-up 坐标**（`y0` 为 bottom，`y1` 为 top）。

### 第 4 步：核对现有转换公式

当前 `DocumentProcessor.extractPageFillBoxes` 的转换：

```java
pageBoxes.add(new BoundingBox(pageNumber,
        drawing.rect.x0, pageHeight - drawing.rect.y1,
        drawing.rect.x1, pageHeight - drawing.rect.y0));
```

代入 arrow1 的 raw rect：

- `bottom = 841.92 - 267.97 = 573.95`
- `top = 841.92 - 244.87 = 597.05`

得到转换后 bbox：`[296.0, 573.95, 302.0, 597.05]`。

该 bbox 与 shaft y 范围 `[249.87, 267.97]` 完全不重叠，因此 `pickArrowhead` 的 `overlaps` 检查失败，回退候选被丢弃。

### 第 5 步：验证正确转换

将转换改为直接使用 y-up 坐标：

```java
pageBoxes.add(new BoundingBox(pageNumber,
        drawing.rect.x0, drawing.rect.y0,
        drawing.rect.x1, drawing.rect.y1));
```

得到 bbox：`[296.0, 244.87, 302.0, 267.97]`，与 shaft 重叠并单端外伸，`pickArrowhead` 成功选中。临时调用 `ShapeRecognizer.recognizePage(0, artifacts, correctedFills)` 输出：

```
CORRECTED arrow [296.0, 244.8699951171875, 302.0, 267.9700012207031]
```

与期望一致。

## 根本原因（Root Cause）

`extractPageFillBoxes` 错误地假设 PDFBox `GetDrawings` 输出的是 y-down 坐标，使用了 `pageHeight - y` 翻转。实际上 PDFBox `PDFGraphicsStreamEngine` 在标准页面（rotation=0）下输出的是 PDF 用户空间坐标，即 **y-up**：`rect.y0` 是 bottom，`rect.y1` 是 top。翻转后 fill bbox 被镜像到页面另一侧，与 shaft 不重叠，导致 PDFBox 回退路径对合并后的箭头头失效。

## 已实现方案

### 改动

**文件：** `java/opendataloader-pdf-core/src/main/java/org/opendataloader/pdf/processors/DocumentProcessor.java`

将 `extractPageFillBoxes` 中的 y 轴翻转去掉，直接使用 PDFBox 输出的 y-up 坐标：

```java
// 修改前
float pageHeight = page.getMediaBox().getHeight();
...
pageBoxes.add(new BoundingBox(pageNumber,
        drawing.rect.x0, pageHeight - drawing.rect.y1,
        drawing.rect.x1, pageHeight - drawing.rect.y0));

// 修改后
pageBoxes.add(new BoundingBox(pageNumber,
        drawing.rect.x0, drawing.rect.y0,
        drawing.rect.x1, drawing.rect.y1));
```

同时移除不再使用的 `pageHeight` 局部变量，并添加注释说明 PDFBox 坐标系语义。

### 为什么之前的记忆会误判方向

`2026-08-13-表格行分隔线误识别为arrow排除与PDFBox-fill回退失效bug.md` 中记录了同一现象，但当时推断"PDFBox 返回 y-down、现有转换公式正确"。该推断基于 `-84` 文件中另一个 fill 的例子：

```
rect=(88.464, 785.04, 507.004, 785.75995)
bottom=841.92-785.76=56.16, top=841.92-785.04=56.88
```

当时误以为 56 附近是正确位置，因此认为翻转有效。实际上该 fill 也位于页面顶部附近（y≈785），翻转后同样被错置到页面底部。本次直接对照 `-84` arrow1 的已知 shaft 位置后，确认了 PDFBox 输出为 y-up，翻转是错误方向。

## 验证结果

| 测试 | 结果 |
|---|---|
| `ArrowE2ETest` | ✅ 2/2 通过 |
| `ShapeRecognizerTest` | ✅ 22/22 通过 |
| `FlowchartProcessorTest` | ✅ 9/9 通过 |
| `202304291682681121000817-7.pdf` 第 1 页表格分隔线回归 | ✅ arrow 误识别数为 0 |

`-84` 箭头 1 bbox 恢复为 `[296.0, 244.87, 302.0, 267.97]`，包含填充箭头头尖。

## 关键决策

- 直接使用 PDFBox 输出的 y-up 坐标，不再做 `pageHeight - y` 翻转。
- 保留 `GetDrawings` 的 `closePath` 与 `FILL/FILL_STROKE` 过滤条件不变。
- 该修复与 `ShapeRecognizer` 的 `pickArrowhead`、`filterShapeCoincidentFills` 逻辑正交，只修正数据源坐标。

## 相关文件

- `java/opendataloader-pdf-core/src/main/java/org/opendataloader/pdf/processors/DocumentProcessor.java`：`extractPageFillBoxes` 坐标转换。
- `java/opendataloader-pdf-core/src/main/java/org/opendataloader/pdfbox/GetDrawings.java`：PDFBox path 提取（输出 y-up 用户空间坐标）。
- `java/opendataloader-pdf-core/src/main/java/org/opendataloader/pdf/processors/ShapeRecognizer.java`：`findArrowBBox` / `pickArrowhead` 回退筛选逻辑。
- `java/opendataloader-pdf-core/src/test/java/org/opendataloader/pdf/processors/ArrowE2ETest.java`：`-84`/`-83` 箭头回归测试。
- `docs/pdf/202302281677505819604328-84(流程图).pdf`：修复验证样例。
- `docs/pdf/202302281677505819604328-83(流程图).pdf`：artifact 路径未受回退干扰的对照样例。
