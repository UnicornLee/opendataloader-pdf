# opendataloader-pdf 任务记忆 — 2026-09-11（柱状图截图区域补全：坐标轴 + 刻度/类别标签 + 图例；附带修复 FlowchartProcessor 重复裁剪）

## 目标（Goal）

用户反馈 3 个样本的"图表截图不完整"，要求**先定位真正原因、给出解决方案，不要改代码**，不清楚的地方向用户澄清：

1. `docs/pdf/354cb7d4-8f79-4429-8768-e3b0e3fcc4e3-18.pdf`：中间有个饼图，截图不完整。
2. `docs/pdf/354cb7d4-8f79-4429-8768-e3b0e3fcc4e3-28.pdf`：两个柱状图，**纵坐标轴及其数字、横坐标每个柱子对应的文字**都没在图片里。
3. `docs/pdf/354cb7d4-8f79-4429-8768-e3b0e3fcc4e3-149.pdf`：一个柱状图，**纵坐标轴及数字、横坐标文字、图例**都没在图片里。
4. 授权：可用 `org.opendataloader.pdf.DebugSample1` 解析/调试（里面的文件可自行替换）。

用户对澄清问题的最终口径（本次实施的约束）：

| # | 澄清点 | 用户答复 |
|---|---|---|
| 1 | 截图完整边界定义到哪一层 | 只到 **plot + 坐标轴 + 刻度/类别标签 + 图例**（不含标题、`单位：xxx`、`数据来源：xxx`） |
| 2 | 被图片覆盖的文字如何处理 | **从文本流移除**（保持现有 `removeAll` 行为） |
| 3 | 饼图是否本轮一起修 | **本轮只修柱状图** |
| 4 | 吸收策略激进/保守 | 接受"**只吸收短数字标签 + 细线**"的保守策略 |
| 5 | 是否允许改现有常量与 ShapeRecognizer 分组规则 | **允许** |

## 背景（Context）

三个样本都是整册 `docs/pdf/354cb7d4-8f79-4429-8768-e3b0e3fcc4e3.pdf`（431 页）的拆分单页（对应整册第 18 / 28 / 149 页）。

### 1. 图表截图的两个产出方（改动前）

```java
// DocumentProcessor（第 4 轮循环，逐页）
List<IObject> shapeChunks = pageContents.stream().filter(ShapeChunk.class::isInstance).collect(...);
List<List<IObject>> groupedShapeChunks = ShapeRecognizer.groupShapes(shapeChunks);
BarChartProcessor.processBarChartGroups(pageContents, groupedShapeChunks, imagesUtils, pageNumber);
FlowchartProcessor.processFlowchartGroups(pageContents, groupedShapeChunks, imagesUtils, pageNumber);
```

- **柱状图** → `BarChartProcessor`（`containsBarChart(group)` 为真的组）。
- **饼图** → 没有任何 shape 类型，实际是 `FlowchartProcessor` 的启发式"顺带"产出的截图。

### 2. `BarChartProcessor`（改动前）的截图框口径

```java
private static final double COLLECTION_MARGIN = 1.0;                 // 吸收相邻内容的容差
private static final double SCREENSHOT_HORIZONTAL_MARGIN = 5.0;      // 截图框水平外扩
private static final double SCREENSHOT_VERTICAL_TOLERANCE = 1.0;     // 截图框垂直外扩
private static final int MAX_GROWTH_ITERATIONS = 30;
```

`groupBox = unionShapeBoundingBoxes(group)`（**只含柱子矩形**）→ `screenshotBox = groupBox ± (5,1)` → `do-while` 循环里：

```java
// 1) 吸收后续 shape group：要求 bbox 与 screenshotBox 真实相交（无容差）
if (laterBox == null || !screenshotBox.overlaps(laterBox)) continue;
// 2) 吸收 pageContents：要求 bbox 与 screenshotBox 在 COLLECTION_MARGIN(1pt) 内相交
if (contentBox.overlaps(screenshotBox, COLLECTION_MARGIN)) { ... }
```

### 3. `FlowchartProcessor`（改动前）的关键门限

```java
private static final double COLLECTION_MARGIN = 2.0;
private static final double MIN_WIDTH = 80.0;
private static final double MIN_HEIGHT = 40.0;
private static final int MIN_SHAPE_COUNT = 2;
private static final int MIN_TOTAL_COMPONENTS = 5;
// 命中分支之一：labelsWithConnectors = textCount >= 3 && (polylineCount + arrowCount) >= 2
```

### 4. `ShapeRecognizer.groupShapes` 的分组条件（关键）

```java
private static boolean overlapsWithYTolerance(BoundingBox a, BoundingBox b) {
    if (a.getLeftX() > b.getRightX() || b.getLeftX() > a.getRightX()) {
        return false;                       // ← 必须先有 x 方向相交
    }
    return a.getBottomY() <= b.getTopY() + SHAPE_GROUP_Y_TOLERANCE
            && b.getBottomY() <= a.getTopY() + SHAPE_GROUP_Y_TOLERANCE;   // SHAPE_GROUP_Y_TOLERANCE = 2.0
}
```

## 定位过程（Investigation）

### 第 1 步：建立可观测手段（工具脚本）

在 `tmp_output/` 下新建：

| 脚本 | 用途 |
|---|---|
| `probe_pdf.py` | 用 PyMuPDF 导出页面的**矢量图形**（type/bbox/fill/stroke/kinds：`re`=矩形、`cl`=贝塞尔曲线、`l`=直线）与**全部文本 span**，写 UTF-8 报告 |
| `compare_streams.py` | 逐字节比对两页的 **content stream**、字体、XObject、Image 资源 |
| `make_subset.py` / `make_tests.py` | 从整册切出任意页子集（`p18_only`、`p1_18`、`p17_18`、`p18_20`、`p18_blank`、`blank_18`、`p18_18`…） |
| `inspect_chart.py` | 打印 JSON 每页 item 的 `item_type/id/bbox/文字/图片路径` |
| `grep_log2.py` | **支持 UTF-16 与 UTF-8 自动识别**的日志过滤（PowerShell `*>` 重定向出来的日志是 UTF-16，用 utf-8 读会全部失配，早期因此得出过错误结论） |
| `filter_report.py` | 按区域过滤 probe 报告 |

### 第 2 步：先排除"文件不同"这一可能

对整册第 18 页与 `-18.pdf`：

```
full pages: 431  split pages: 1
full p18 rect == split rect (595.32 x 841.92)
text equal: True (982 chars)
full p18 drawings: (85, '505e20c16aa09390eb556e1e95769457')
split   drawings: (85, '505e20c16aa09093...')  → md5 完全相同
content stream: len full 27122 == len split 27122, equal True
fonts: 仅对象号/子集前缀不同（ABCDEE+宋体、Times New Roman 相同）
xobjects/images: 均为空
```

→ **文件内容逐字节一致**，差异只能来自流水线本身。

### 第 3 步：发现反常现象 —— 同一页"单页文件无图，多页文档有图"

对第 18 页做子集实验（每次 `DebugSample1` 运行都看 `FlowchartProcessor` 的 INFO 日志与 JSON）：

| 输入 | 页数 | 是否产出图表图片 | 截图 bbox |
|---|---|---|---|
| `-18.pdf` / `p18_only.pdf` | 1 | **否**（24 个纯文本 item） | — |
| `p18_blank.pdf`（18 + 空白页） | 2 | 否 | — |
| `blank_18.pdf`（空白页 + 18） | 2 | 否 | — |
| `p17_18.pdf`（17 + 18） | 2 | 是 | `314.12–443.56 × 203.98–245.58` |
| `p18_20.pdf`（18 + 19 + 20） | 3 | 是 | 同上 |
| `p18_18.pdf`（18 + 18，内容重复） | 2 | 是，但**巨图** | `83.38–512.04 × 42.87–275.39`（把标题 + 两段正文吞进图片） |
| 整册 431 页 | 431 | 是 | 同小图（`imageFile32.png`） |

四次运行的 `ShapeRecognizer` 日志完全一致（`Page N: recognized 22 shape(s)`，11 个 `rectangle`(图例色块) + 11 个 `polyline`），说明**输入给判定逻辑的图形集合没变**；变的是**非图形内容列表**（跨页 `HeaderFooterProcessor` 会把重复的页眉/页脚内容包成 `SemanticHeaderOrFooter`，同一页在整册里标题会被当页眉吃掉；`p18_18` 因两页内容完全相同而触发页眉/页脚判定，截图框随之暴涨）。

### 第 4 步：确认第 18 页的图是 `FlowchartProcessor` 产物

日志（`p17_18` / `p18_20` 运行）：

```
信息: Page 2: detected flowchart cluster with screenshot bbox [314.12, 596.336, 443.56, 637.937]
```

换算成 top-down：`(314.12, 203.98) - (443.56, 245.58)`，与 JSON 里 image 的 bbox **完全一致** → 该图由 `FlowchartProcessor` 产出（`BarChartProcessor` 无 `bar_chart` 形状可用）。

### 第 5 步：量化第 18 页的"边缘判定"（0.4pt 之差）

- 截图 bbox（veraPDF，bottom-up）`[314.12, 596.336, 443.56, 637.937]` → 宽 `129.44`、高 `41.601`。
- `FlowchartProcessor` 会先算 `mergedBox` 再加 `(5,1)` margin，因此 `mergedBox` 高约 `41.601 - 2 = 39.601`，而 `MIN_HEIGHT = 40`。
- 即：**该簇仅靠亚 1pt 级别的差异（吸收到的相邻文本/线的细微不同）才越过了 40pt 高度门限**；这也解释了为什么同一页在多页文档里有图、单页文件里无图。

### 第 6 步：定位 -28 的截图框（柱状图）

`ShapeRecognizer` 日志（`-28` 只有 4 个 shape）：

```
信息: Page 1: shape type=bar_chart, components=6, bbox=[180.72, 316.5,  454.56, 435.6 ]   ← 下方图柱形
信息: Page 1: shape type=bar_chart, components=9, bbox=[179.64, 595.86, 448.74, 724.98]  ← 上方图柱形
信息: Page 1: shape type=polyline, components=3, bbox=[168.30, 595.62, 171.18, 739.56]   ← 上方图纵轴（含刻度线）
信息: Page 1: shape type=polyline, components=3, bbox=[160.08, 316.26, 163.26, 458.52]   ← 下方图纵轴
```

改动前的图片（与"柱形并集 ±(5,1)"**完全相等**，说明增长循环一次都没吸收到东西）：

| 图 | 图片 bbox | 反推的柱形并集 |
|---|---|---|
| 上方图 | `(174.6, 115.9, 453.7, 247.1)` | `(179.64,116.94)-(448.74,246.06)` |
| 下方图 | `(175.7, 405.3, 459.6, 526.4)` | `(180.72,406.32)-(454.56,525.42)` |

`probe_pdf.py` 给出的实际间隙（都能被 1pt 容差卡住）：

| 元素 | 元素 bbox（top-down） | 到截图框的距离 | 结论 |
|---|---|---|---|
| 纵轴刻度数字 `30/25/.../0` | x 151.9–160.9 | 距框左边 174.6 → **13.7pt** | > 1pt，不吸收 |
| 横轴类别文字 `2021 … 2029E` | y 253.2–261.4 | 距框下边 247.1 → **6.1pt** | > 1pt，不吸收 |
| 纵轴线本体 `polyline` | x 168.3–171.18 | 距框左边 174.6 → **3.5pt** 且**无 x 相交** | 既不吸收也不入组 |
| 下方图纵轴线 | x 160.08–163.26 | 距（下方图框）左边 175.7 → **12.4pt** | 同上 |

### 第 7 步：定位 -149 的截图框（柱状图）

```
信息: Page 1: shape type=bar_chart, components=3, bbox=[160.5, 285.42, 414.78, 418.8]
信息: Page 1: shape type=bar_chart, components=3, bbox=[202.86, 285.42, 457.14, 391.14]
... 4 个 rectangle（图例色块，4.26 x 4.2pt，x=140.4 / 232.32 / 306.18 / 371.04）
信息: Page 1: shape type=polyline, components=3, bbox=[144.36, 285.18, 147.24, 444.84]   ← 纵轴
```

改动前图片 `(155.5, 422.1, 483.3, 557.5)`，仍只是"柱形并集 ±(5,1)"（右侧多并进了一个色块组）：

| 元素 | 元素 bbox（top-down） | 到截图框距离 | 结论 |
|---|---|---|---|
| 纵轴刻度数字 `25,000.00 …` | x 98.7–134.7 | 距框左边 155.5 → **20.8pt** | 不吸收 |
| 横轴类别文字 `2022年度 …` | y 563.3–572.9 | 距框下边 557.5 → **5.8pt** | 不吸收 |
| 图例（色块 + `工程塑料改性助剂 …`） | y 582.7–592.5 | 距框下边 557.5 → **25.2pt** | 不吸收 |
| 纵轴线本体 | x 144.36–147.24 | 距框左边 155.5 → **8.3pt**，无 x 相交 | 不入组 |

### 第 8 步：确认纵轴线为何永不入组

`groupShapes` 用 `overlapsWithYTolerance` 做连通分量，**先要求 x 方向相交**；纵轴永远贴在柱形左侧外侧（无 x 相交），因此纵轴永远是"独立单 shape 组"。即使 `BarChartProcessor` 的增长循环去看后续组，也因 `screenshotBox.overlaps(laterBox)` 要求**真实相交**而拿不到它。

### 第 9 步：输出根因 + 方案，等用户确认口径

见"根本原因"与"关键决策"。用户答复后进入实施。

### 第 10 步：实施过程中被实测暴露的 3 个"实现坑"

| # | 现象 | 原因 | 处理 |
|---|---|---|---|
| 1 | `-28` 下方图仍不完整、且标签已被移除（内容丢失） | 我第一版把吸收到的标签 `union` 到了局部 `labelBox`，**没有 union 回 `chartBox`** | 增加 `chartBox` 参数，标签同时 union 回截图框 |
| 2 | `-28` 上方图的横轴类别文字没被吸收（49 字） | `MAX_LABEL_CHARS` 初值 48，"2021 2022 2023 2024E 2025E 2026E 2027E 2028E 2029E" 被截断判定 = **49 字符** | 放宽到 64，并在注释里说明原因 |
| 3 | `-28` 下方图的刻度/类别/图例最终由 `FlowchartProcessor` 重拍（bbox 被覆盖、多出孤儿 PNG） | 纵轴线宽 **3.18pt > `AXIS_MAX_THICKNESS`=3.0** 被判为非轴 → 该图完全没吃到标签；随后 `FlowchartProcessor` 用"残留的纵轴组 + 仍在页面上的标签"重新命中 | ① 阈值放宽到 6pt（并注释"轴含刻度线实测 2.9–3.2pt"）；② 给 `FlowchartProcessor` 加"组已被消费"守卫 |
| 4 | `-28`/`-149` 各出现 1 个**未被 JSON 引用**的 PNG（重复裁剪） | `BarChartProcessor` 已把坐标轴 shape 从 `pageContents` 移除，但 `groupedShapeChunks` 里那两组的**引用仍在**，`FlowchartProcessor` 拿着旧组 + 新产出的 ImageChunk 又命中一次 | `FlowchartProcessor.isStillOnPage()`：组的 shape 已不在 `pageContents` 就跳过（这也是改动前就存在的隐患） |

## 根本原因（Root Cause）

### A. 柱状图（-28 / -149）——截图框等于"柱子并集 + 固定小 margin"

`BarChartProcessor` 的截图框只由 `bar_chart` 形状（=柱子矩形）的并集决定，外加 `(5pt, 1pt)` 固定外扩；增长循环的吸收半径固定为 `COLLECTION_MARGIN = 1pt`。而图表的其它组成部分与柱形之间存在 **3–40pt 的固有间隙**（轴数字右对齐在轴左侧、类别文字在 x 轴下方一行、图例在图表下方、`单位：` 在标题行），全部大于 1pt → **永远不会被吸收**。所以截图必然只剩柱子。

### B. 纵轴"线本体"也进不去

纵轴线是独立 `ShapeChunk(TYPE_POLYLINE)`，与柱形**没有 x 方向相交**，而 `groupShapes` 先按 x 相交做连通 → 纵轴永远不在 `bar_chart` 组里；增长循环又要求与截图框真实相交 → 也吸收不到。因此即便放大 margin，光靠现有逻辑也拿不到轴线。

### C. 饼图（页 18）——没有饼图识别，截图是流程图启发式的"副产品"

- 饼图由**贝塞尔扇形填充路径**绘制，`ShapeRecognizer` 没有曲线/饼图类型，整页只识别出 11 个图例色块（`rectangle`）+ 4 条引导线（`polyline`），饼图本体（含 `84.18%` 那块）**没有任何 shape 表达**。
- 那张"不完整的饼图截图"实际由 `FlowchartProcessor` 命中 `labelsWithConnectors`（≥3 文本 + ≥2 连接线）产生，取的是"引导线组 + 1–2pt 内相邻文本"的并集 → 只截到饼图右上角 + 前两条图例。
- 判定极不稳定：`MIN_HEIGHT=40` 而该簇去掉 margin 后仅约 `39.6pt`（**0.4pt 之差**）；且 `p18_18` 的巨型截图证明该启发式还会把标题与正文一起吞掉。

### D. 连带缺陷：`BarChartProcessor` 消费过的组仍会被 `FlowchartProcessor` 处理

`groupedShapeChunks` 由 `DocumentProcessor` 一次性算好后传给两个 processor；`BarChartProcessor` 只标记自己局部的 `skipped[]`，`FlowchartProcessor` 拿到的是同一份数组，于是**已并入柱状图截图的组**（尤其是坐标轴 polyline）会被当成候选流程图簇再处理一次，导致"用同一区域重拍一张图 + 产生孤儿 PNG + 最终 bbox 被流程图版本覆盖"。

> 本轮按用户口径**只修柱状图**，A/B/D 已修；C（饼图）保持原状，需要单独一轮"饼图识别 + 消除单页/多页差异"。

## 已实现方案

### 1. `BarChartProcessor.java`（主修改，+421 行）

**文件：** `java/opendataloader-pdf-core/src/main/java/org/opendataloader/pdf/processors/BarChartProcessor.java`

在原有 `(5,1)` margin + `1pt` 增长循环**之后**，新增第 3 步 `expandToChartRegion()`：

```java
// 3. 把"从不接触柱形 bbox"的图表部件一起拉进来：坐标轴、刻度/类别标签、图例
expandToChartRegion(pageContents, screenshotBox, absorbedShapes, absorbedContents,
        groupedShapeChunks, skipped);

pageContents.removeAll(absorbedContents);   // 被图片覆盖的文字/图例 → 从文本流移除
pageContents.removeAll(absorbedShapes);
ImageChunk imageChunk = new ImageChunk(screenshotBox);
imagesUtils.saveImageChunk(imageChunk);
pageContents.add(imageChunk);
```

新增常量（保守策略）：

| 常量 | 值 | 含义 / 依据 |
|---|---|---|
| `AXIS_MAX_THICKNESS` | **6.0** | 细长轴判定：厚度上限。必须 > 3.2pt（-28 下方图纵轴含刻度线实测 3.18pt），**初值 3.0 会漏** |
| `AXIS_MIN_LENGTH` | 20.0 | 轴的最短长度 |
| `AXIS_ATTACH_GAP` | 20.0 | 轴与 plot 边的最大间距（实测 3.5 / 8.3 / 12.4 / 17.5pt） |
| `AXIS_OVERLAP_RATIO` | 0.5 | 轴长与 plot 的纵向重叠率下限 |
| `TICK_LABEL_BAND` | 55.0 | 纵轴左侧刻度标签带宽度（实测 45.7 / 20.6 / 16.4pt） |
| `CATEGORY_LABEL_BAND` | 30.0 | 横轴下方类别标签带高度（实测 6.1 / 5.8pt） |
| `MAX_LABEL_HEIGHT` | 18.0 | 视作"标签"的单行文本高度上限 |
| `MAX_LABEL_CHARS` | **64** | 视作"标签"的字符上限；初值 48 会漏掉一整行合并的类别标签（实测 49 字符） |
| `SENTENCE_PUNCTUATION` | `[。，、；：！？;!?]` | 含句读 → 判为正文/来源说明。**刻意不含 ASCII 逗号与句点**，否则 `25,000.00` 会被误杀 |
| `MAX_SWATCH_SIZE` / `MIN_SWATCH_SIZE` | 12.0 / 1.5 | 图例色块边长范围（实测 4.2pt） |
| `MAX_SWATCH_ASPECT` | 2.5 | 色块长宽比上限 |
| `LEGEND_ATTACH_GAP` | 40.0 | 图例run 与 plot 的最大距离（实测 25.2 / 28.1pt） |
| `LEGEND_ALIGN_TOLERANCE` | 3.0 | 判定两个色块"同一行/列"的中心误差 |
| `MIN_LEGEND_SWATCHES` | 2 | 构成一个图例 run 的最少色块数 |

三个子步骤：

1. `isVerticalAxis` / `isHorizontalAxis`：细长 + 贴 plot 边（≤20pt）+ 与 plot 重叠率 ≥0.5 → 吸收并 `union`；同时把轴并进 `labelBox`（用于刻度标签的纵向范围）并记录 `axisLeftX`。
2. `expandToLegend`：收集边长 ≤12pt、宽高比 ≤2.5 的"色块"候选，要求贴 plot（下方 ≤40pt 或右侧 ≤40pt）→ 按 `centerY`（横排）或 `centerX`（竖排）聚类成 run（≥2 个）→ 吸收整个 run，并吸收**同一行/列**（中心 Y 差 ≤ runHeight/2+4）且起点在 run 右侧 3pt 内的短文本。
3. `expandToAxisLabels`：刻度标签 = 右边界落在 `[axisLeftX-55, axisLeftX+2]` 且纵向在 `labelBox` 内的短文本；类别标签 = 底边落在 plot 下方 `[bottom-30, bottom+2]` 且横向覆盖 plot 的短文本。

**关键实现约定**：标签带必须锚定在"**增长循环后的 plot box**"，不能锚定在被图例扩张后的框 —— 否则图例会把类别标签带往下推，`-149` 的 `2022年度/2023年度/2024年度` 会被漏掉（这是实测踩到的坑）。

### 2. `FlowchartProcessor.java`（连带必要修复，+22 行）

```java
if (skipped[i] || group == null || group.isEmpty()
        || BoundingBoxGroupUtils.containsBarChart(group)
        || !isStillOnPage(pageContents, group)) {          // ← 新增
    continue;
}
```

```java
/**
 * 组的 shape 已不在 pageContents → 说明它已被 BarChartProcessor 并进柱状图截图，
 * 不能再按"流程图"重拍一遍（否则截图被覆盖、产生孤儿 PNG）。
 */
private static boolean isStillOnPage(List<IObject> pageContents, List<IObject> shapeGroup) {
    for (IObject shape : shapeGroup) {
        for (IObject content : pageContents) {
            if (content == shape) {
                return true;
            }
        }
    }
    return false;
}
```

### 3. 未改动

- `ShapeRecognizer`（第 5 条虽授权可改，但实测在 `BarChartProcessor` 内吸附坐标轴即可解决，不动通用分组规则，风险更小）。
- 饼图相关路径（`FlowchartProcessor` 的判定门限、`MIN_HEIGHT=40`）按用户口径不动。
- `DebugSample1.java` 调试后**已还原成用户原样**（仍指向 `-18.pdf`）。

## 验证结果

### 三个样本（改动前 → 改动后）

`-28`（`final28.pdf`）：

| | 改动前 | 改动后 |
|---|---|---|
| 上方图 bbox | `(174.6, 115.9, 453.7, 247.1)` 只有柱子 | `(151.9, 99.2, 455.5, 261.4)`：纵轴 + `0…30` 刻度 + `2021…2029E` |
| 下方图 bbox | `(175.7, 405.3, 459.6, 526.4)` 只有柱子 | `(139.5, 379.9, 459.6, 542.5)`：纵轴 + `0…250` 刻度 + `2018…2023` |
| 保留为文本 | — | `单位：万吨`、`单位：亿元`、`数据来源：前瞻产业研究院`、`数据来源：共研产业咨询`、正文、页码 |

`-149`（`final149.pdf`）：

| | 改动前 | 改动后 |
|---|---|---|
| 图 bbox | `(155.5, 422.1, 483.3, 557.5)` 只有柱子 | `(98.7, 393.9, 483.3, 592.5)`：纵轴 + `25,000.00…` 刻度 + `2022/2023/2024年度` **+ 图例色块与图例文字** |
| 保留为文本 | — | 图表标题 `主要细分类型产品销售额`、`单位：万元`（两处）、表格、页码 |

`-18`（饼图，未修，确认无回归）：单页仍为 24 个纯文本 item、无图片；`p18_20` 第 1 页图片 bbox 仍为 `(314.1, 204.0, 443.6, 245.6)`，与改动前一致。

### 其它检查

- 图片文件与引用一一对应，**无孤儿 PNG**：`final28_images` 恰 2 张、`final149_images` 恰 1 张（+1 张 streamtable 检测图）、`p18_20_images` 2 张。
- `detected flowchart cluster` 日志只在饼图页出现（`p18_20` page 1），柱状图页不再被流程图重拍。
- 单元测试：`BarChartProcessorTest(6) + CaptionProcessorTest(1) + ConsecutiveImageProcessorTest(6) + FlowchartProcessorTest(9) + LineArtProcessorTest(6)` = **28 通过 / 0 失败**。

## 关键决策（Key Decisions）

- **截图边界严格按用户口径**：只到 plot + 轴 + 刻度/类别标签 + 图例；标题、`单位：`、`数据来源：` 一律保留为文本（靠"位置带 + 长度 + 句读标点"过滤实现，而不是靠"看起来像不像标签"的模糊判断）。
- **保守策略用几何带 + 文本形态双重约束**：位置带限定 x/y 范围，`isShortSingleLineText` 限定"单行、≤18pt、≤64 字、无句读"，避免把正文段落吞进图片。
- **把坐标轴吸附放在 `BarChartProcessor` 内做，而不是改 `ShapeRecognizer.groupShapes`**：通用分组规则影响面大（flowchart / lineart / table 都依赖），在柱状图处理器内部按"细长 + 贴近"识别轴，风险可控。
- **`AXIS_MAX_THICKNESS` 取 6pt 而非 3pt**：轴 shape 的实际 bbox 包含刻度短线，实测 2.88–3.18pt；6pt 仍远小于任何块状图形，是"实测驱动"的取值。
- **标点过滤不含 ASCII 逗号/句点**：否则 `25,000.00` 这类千分位刻度会被当正文丢弃。
- **`FlowchartProcessor` 加 `isStillOnPage` 守卫**：属于让柱状图修复"真正生效"的必要条件（否则截图被覆盖 + 孤儿文件），同时顺带修掉一个改动前就存在的隐患；改动仅 22 行且不影响既有 9 个 flowcult 测试。
- **不引入额外 padding**：初版曾加 `FINAL_PADDING=1pt`，导致 3 个既有断言（`95.0 vs 94.0`、`80.0 vs 79.0`）失败；为保持既有契约与最小 diff，去掉 padding，改为把吸收到的元素 bbox 直接 `union` 进截图框。
- **`pageContents` 的元素按"被覆盖即移除"处理**（用户口径 2）：刻度/类别/图例文字不再重复出现在文本流里。

## 潜在影响 / 风险

- **行为变化**：柱状图截图 bbox 会明显扩大（含坐标轴、刻度、类别标签、图例），且这些文字从文本流中消失（用户明确要求）。下游若依赖"刻度数字作为文本"的消费方需要知悉。
- **误吸收风险（保守策略已缓解但未消除）**：若图表正下方 30pt 内存在"短、单行、无句读"的正文行，或图表左侧 55pt 内存在短文本列，可能被吸收进图片。`-28` 的 `数据来源：…`（含 `：`）与正文段落（高 >18pt）均被正确排除。
- **图例识别依赖"≥2 个等距小方块"**：单色块图例、或色块尺寸差异很大的图例不会被吸收（保守取舍）。
- **饼图仍未解决**：页 18 的行为与"文档是单页还是多页"强相关（`MIN_HEIGHT=40` 边缘 + 跨页页眉页脚改写页面内容），需要单独一轮处理。
- **性能**：`expandToChartRegion` 对每个柱状图组多做 O(3×N) 次线性扫描（N = 页内容数），可忽略。

## 注意事项

### 1. `mvn ... surefire:test` 不会触发编译

`mvn -o -Dtest=BarChartProcessorTest surefire:test` 直接跑的是 `target/classes` 里的**旧字节码**，会给出与源码不符的"失败"（我第一次就被 `95.0 vs 94.0` 误导，以为是新代码问题）。必须先：

```
mvn -o -q -DskipTests compile test-compile
mvn -o -Dtest=BarChartProcessorTest -DfailIfNoTests=false surefire:test
```

`-Dtest=A,B,C` 用**逗号**分隔，`+` 在本项目 surefire 版本会被判为"无匹配测试"。

### 2. PowerShell 重定向的日志是 UTF-16

`mvn ... *> run.log` 产出的日志用 `utf-8` 读取会全部失配（早期因此得出了"没有 LineArtChunk 日志"的错误结论）。用 `grep_log2.py`（自动识别 BOM/编码）或直接 `read_file`。

### 3. `-18.pdf` 单页跑不出饼图截图是"预期"的

若用 `DebugSample1` 只跑 `-18.pdf`，会看到**没有图片**，这是改动前就存在的行为（`FlowchartProcessor` 的门限在单页场景下不达标），不是本次改动引入的。要复现那张"不完整的饼图"，需把该页放进 ≥2 页的文档（例如 `tmp_output/p18_20.pdf` 或整册）。

### 4. 调试脚本与产物

`tmp_output/` 下：`probe_pdf.py`、`compare_streams.py`、`make_subset.py`、`make_tests.py`、`inspect_chart.py`、`grep_log2.py`、`filter_report.py`；子集 PDF：`p18_only.pdf`、`p1_18.pdf`、`p17_18.pdf`、`p18_20.pdf`、`p18_blank.pdf`、`blank_18.pdf`、`p18_18.pdf`；最终验证样本：`final28.pdf`、`final149.pdf` 及对应 `*_images/`；日志：`run18.log`、`run_multi.log`、`run_fix*.log`、`run_final.log`、`run_dbg*.log`。

### 5. 复现命令

```
cd java\opendataloader-pdf-core
mvn -o -q -DskipTests compile exec:java "-Dexec.mainClass=org.opendataloader.pdf.DebugSample1" "-Dexec.classpathScope=runtime" "-Dfile.encoding=UTF-8"
```

（`DebugSample1` 内的路径需临时替换成目标 PDF；本次调试完成后已还原。）

## 相关文件（Relevant Files）

- `java/opendataloader-pdf-core/src/main/java/org/opendataloader/pdf/processors/BarChartProcessor.java`：本次主修改。
- `java/opendataloader-pdf-core/src/main/java/org/opendataloader/pdf/processors/FlowchartProcessor.java`：新增 `isStillOnPage` 守卫。
- `java/opendataloader-pdf-core/src/main/java/org/opendataloader/pdf/processors/ShapeRecognizer.java`：`groupShapes` / `overlapsWithYTolerance`（x 相交前提）是根因 B 的来源；本轮未改。
- `java/opendataloader-pdf-core/src/main/java/org/opendataloader/pdf/processors/BoundingBoxGroupUtils.java`：`unionShapeBoundingBoxes` / `containsBarChart` 复用。
- `java/opendataloader-pdf-core/src/main/java/org/opendataloader/pdf/processors/DocumentProcessor.java`：调用方（BarChart 在 Flowchart 之前），本轮零改动。
- `java/opendataloader-pdf-core/src/test/java/org/opendataloader/pdf/processors/BarChartProcessorTest.java`：既有 6 个用例（含 bbox/margin 契约）本轮未改，全部通过。
- `docs/memory/2026-08-14-BarChartProcessor合并逻辑优化-仿FlowchartProcessor迭代增长.md`：本次改动建立在其增长循环之上（`COLLECTION_MARGIN=1`、`(5,1)` margin 等常量出处）。
- `docs/memory/2026-08-13-FlowchartProcessor流程图合并逻辑优化.md`：`FlowchartProcessor` 判定与 margin 约定的来源。
- 样本：`docs/pdf/354cb7d4-8f79-4429-8768-e3b0e3fcc4e3-{18,28,149}.pdf` 与整册 `...-8f79-...pdf`。
