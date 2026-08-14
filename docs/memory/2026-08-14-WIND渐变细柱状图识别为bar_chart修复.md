# opendataloader-pdf 任务记忆 — 2026-08-14（WIND 渐变细柱状图未被识别为 bar_chart 修复）

## 目标（Goal）

修复 `docs/pdf/202304271682510470028924-15.pdf`（中矿资源 2022 年报第 15 页）：

- 该页「中矿资源集团股份有限公司 2022 年年度报告全文」与「数据来源：WIND」之间的柱状图完全没被识别出来；
- 期望：柱状图中的柱子成为 `shapeType = bar_chart` 的 `ShapeChunk`（与已有 BarChartProcessor 截图链路衔接）；
- 约束：先用 `org.opendataloader.pdf.DebugSample1` 重新解析/调试；先定位原因、给出方案，**用户同意后才能改代码**。

## 问题复现

### 1. 查看既有解析结果（修复前）

`tmp_output/202304271682510470028924-15.json` 中该页 items 只有 `text` 和 1 个 `lattice_table`，**没有任何 picture/image/shape 覆盖柱状图区域**。Y 轴刻度（4500.00/4000.00/…）与「数据来源：WIND」都存在，唯独柱子消失。

### 2. PDFBox 渲染确认柱子物理存在

用 PDFBox `renderImageWithDPI` 渲染页面 → 蓝色细柱清晰可见；按 y-up 坐标换算（110 DPI，比例 110/72）进行像素扫描，柱子在图像行 149~310、列 292~718 区域，柱宽仅 ~3px，符合「比较细的渐变蓝柱」。

## 定位过程

### 第 1 步：dump 内容流操作符，发现柱子是 Pattern 渐变填充

写临时工具 `DumpContent`（PDFBox 直接解码页面内容流），统计页 0 绘制操作符：

```
re : 182   f* : 109   W* : 72   n : 72   gs : 37   cs/scn : 37
```

**没有 `sh`（无渐变 shading 操作）**。查看柱子附近内容（x=165.81 起、每根之间 8.52pt、宽 1.87pt、高 45~105pt、基线全部 y=639.34）：

```
q
/Pattern cs /P267 scn      ← Pattern（渐变图案）颜色空间 + 图案名
/GS292 gs                  ← 透明度图形状态
165.81 639.34 1.872 52.517 re
f*
Q
```

结论 1：**每根柱子是一个「/Pattern 渐变图案填充的矩形」**，这是万得 WIND 等金融图表的典型导出方式。

### 第 2 步：检查 veraPDF artifacts 层 —— 柱子根本不在输入里

写 `DebugArtifacts`（复用 `DocumentProcessor.preprocessing` + `ShapeRecognizer` 完全相同的解析路径）dump 页 0 artifacts：

```
summary: LineChunk=114 LineArtChunk=22 ShapeChunk=15
```

按坐标核对，**没有**任何 LineChunk/LineArtChunk 覆盖柱子区域（x≈165~470, y≈639~745）。轴带、图框 polyline 都有，唯独柱子缺失。而图框、轴带恰好是 `mcid == null`（BMC/BDC 标记重复去重后按未标记处理）或显式 `rg` 上色的内容。

### 第 3 步：读 veraPDF 定制层源码（../veraPDF-validation），定位两层丢弃

本仓库依赖 veraPDF 定制版 1.31.99/1.31.33（源码在 `opendataloader-pdf-parse/veraPDF-validation`、`veraPDF-wcag-algs`，是团队 fork）。`ChunkParser.java`（wcag-validation 模块）关键点：

- `isProcessColorSpace(colorSpace)` 白名单只有 DeviceRGB/DeviceGray/DeviceCMYK/ICCBased/CalRGB/CalGray/DeviceN/Separation/Indexed/Lab —— **不含 Pattern**；
- `SCN_FILL` 分支：`isProcessColorSpace(Pattern)==false` → `graphicsState.setFillColor(null)`；
- `processf()` 中 `Rectangle.getLine(0, fillColor=null)` 仍会产出 LineChunk（**几何保留、颜色为 null**）；
- `putChunk(mcid, chunk)`：`mcid != null` 时进 `StaticStorages.getChunks()`（结构树），只有 `mcid == null` 才进 `artifacts`；
- `DocumentProcessor.preprocessing` 设置 `StaticStorages.setIsIgnoreMCIDs(!isUseStructTree())`；本页是带结构树（/Lang、全页 MCID）的 tagged PDF → `isIgnoreMCIDs = false` → **MCID 内的内容全部进结构树，不进 `getArtifacts()`**；而 `ShapeRecognizer.recognize` 只遍历 `document.getArtifacts(pageNumber)`。

### 第 4 步：对照实验（关键证据）

写 `DebugArtifacts2`：不经过 DocumentProcessor，手动构造 `GFSAPDFDocument` 并**强制 `StaticStorages.setIsIgnoreMCIDs(true)`** 后 parseChunks → 36 根柱子**立刻以 `color=null` 的 LineChunk 全部浮现**：

```
BAR? bbox=(165.81,691.86)-(167.68,639.34) w=1.87 h=52.52 width=1.87 color=null
BAR? bbox=(191.36,744.63)-(193.23,639.34) w=1.87 h=105.29 width=1.87 color=null
...
```

> 期间还验证了 veraPDF BoundingBox 是 y-up（bottom-left 原点）坐标，与 PDFBox rect 一致——后续兜底无需翻转；还排除了「chunk 在结构树里其实可见」的可能（getArtifacts 只线性返回 null-mcid 内容）。

### 第 5 步：识别器自身还有一道门槛

即便柱子以 LineChunk 进入识别器，`ShapeRecognizer.isFilledRectangle()`：

```java
double minDim = Math.min(bbox.getWidth(), bbox.getHeight());  // = 1.87
if (minDim <= 2) return false;                                 // ← 细柱直接判死
```

1.87pt 宽 → 会被当作细线走 polyline/connector 通道，永远到不了 `detectBarGroups`。

### 第 6 步：确认可用兜底通道 —— PDFBox fill 已能拿到全部柱子

项目里 `DocumentProcessor.extractPageFillBoxes()`（PDFBox `GetDrawings` 采集全部闭合 fill）**早已把这些柱子的矩形 bbox 采到**（实测 `total FILL/FILL_STROKE paths=109`，柱子区域 36 根全在，`closePath=true`），但此前**只把 fillBoxes 用作箭头头候选**（`recognizeConnectorLines`→`findArrowBBox`），从未喂给柱状图识别。

### 第 7 步：先用真实生产逻辑验证「识别会通过」（改码前可行性证明）

用反射调用 `ShapeRecognizer` 私有的 `groupByColor`/`detectBarGroups`/`isValidBarGroup`/`hasBarGaps`/`hasVaryingBarLengths`，把 PDFBox 兜底盒转成合成矩形（统一兜底色）喂入：

```
color bucket 25,35,48 entries=81 barGroups=1
  group size=36 valid=true gaps=true vary=true
totalBarGroups=1 validBarGroups=1
```

证明：**只要柱子以填充矩形身份进入 `recognizeFilledShapes`，现有校验全部放行，正好生成 1 个含 36 部件的 bar_chart**。

## 根本原因（Root Cause，三层阻断叠加）

| # | 层 | 机制 |
|---|----|----|
| 1 | veraPDF 颜色解析（`ChunkParser.SCN_FILL`/`SC_FILL`） | `isProcessColorSpace()` 白名单不含 `Pattern` 颜色空间 → `/Pattern cs …scn` 的填充色被置为 `null` |
| 2 | veraPDF 结构树（tagged PDF） | 本页所有内容位于 `/P MCID` 标记内容内，`isIgnoreMCIDs=false` → MCID 内容进结构树、不进 `getArtifacts()`；`ShapeRecognizer` 只读 `getArtifacts()` → 柱子对识别器完全不可见 |
| 3 | 识别器 `isFilledRectangle` | `minDim <= 2` 门槛把 1.87pt 细柱判为细线（thinLines），走不到 `detectBarGroups` |

即：**WIND 式「细渐变 Pattern 填充柱」被 veraPDF 色值层和结构树层双重吞掉，现有 PDFBox fill 兜底通道又没接入柱状图识别**。

## 已实现方案（用户批准：方案 A，只改本仓库）

### `ShapeRecognizer.recognizeBarChartsFromFillBoxes(pageNumber, fillBoxes, existingShapes)`

- 复用已有 PDFBox `fillBoxes` 兜底（`extractPageFillBoxes` → `GetDrawings`，与箭头头回退同一数据源）；
- 先用现有 `filterShapeCoincidentFills` 剔除与已识别 rectangle/bar_chart 形状重合的兜底盒（避免轴带等 veraPDF 已识别几何被重复采集）；
- 剩余盒按「厚线穿过矩形」表示转成合成填充矩形（与 veraPDF `Rectangle.getLine` 的几何一致，用 `LineChunk.createLineChunk(…, PROJECTING_SQUARE_CAP_STYLE, FALLBACK_FILL_COLOR)`，统一兜底色 `[0.5, 0.7, 0.95]` 使同页 Pattern 填充归于同一色桶）；
- 喂入**现有** `detectBarGroups` + `isValidBarGroup` 做与 artifact 层完全相同的柱状图校验，通过即 `createShape(TYPE_BAR_CHART)`；
- `recognizePage` 中在 `recognizeFilledShapes` 之后、polyline 之前调用（这样 `existingShapes` 已含 veraPDF 矩形，去重正确）。

改动：`ShapeRecognizer.java` +79 行（1 个常量 + 1 个私有方法 + 3 行调用 + 注释）。`groupShapes`/`BarChartProcessor` 截图链路零改动。

### 关键决策（Key Decisions）

- **修在本仓库而非 veraPDF fork**：veraPDF 层改动（给 Pattern 填充一个兜底色）会让所有下游看到 pattern 填充矩形，影响面大且需重建定制依赖；本仓库已有「virt 层丢内容 → PDFBox fill 兜底」的既定模式（箭头头），在此模式内扩展最一致、风险最低。
- **兜底盒只做 bar 组检测、不做通用矩形聚类**：避免把 fillBoxes 合并进 `filledRects` 主干后对全页表格/背景等产生连锁影响。
- **兜底色统一**：Pattern 填充无法解析具体 RGB，统一色仅作分组键与元数据，最终 BarChartProcessor 会以截图替换该区域。
- **不改 `isFilledRectangle` 的 minDim≤2 门槛**：该门槛保护表格线段误判；兜底通道直接用矩形几何（不经此门槛），互不影响。
- `filterShapeCoincidentFills` 只对比 rectangle/bar_chart（既有行为），polyline 不参与——兜底 bar 不与其去重。

## 验证结果

- **目标文件 `202304271682510470028924-15.pdf`**（DebugArtifacts 同管线复跑）：
  ```
  SHAPE type=bar_chart bbox=(165.81,744.63)-(465.80,639.34) components=36 color=[0.5, 0.7, 0.95]
  ```
  其余 15 个 shape 与修复前完全一致（无回归变化）。
- **端到端**：`DebugSample1` 完整管线跑通，新 JSON 目标区域出现 `image` 项（`tmp_output/...-15_images/imageFile1.png`，BarChartProcessor 对 bar_chart 组截图，44KB），修复前该区域为空。
- **回归（全页扫描，重点关注）**：
  - `202304191681815910199312-318(表格单元格为色块儿).pdf`：25 个 ShapeChunk 全是 polyline/rectangle，**无 bar_chart 误报**（最易误报场景）；
  - 116 页大年报 `202304281682603453761936.pdf`：全部页面仅 polyline/rectangle，无新增 bar_chart；
  - `202304191681815910199312-95(图表).pdf`：2 个 bar_chart（components=5，真实色），与原识别完全一致；
  - 流程图、`202304281682608479323398.pdf` 等：无 bar_chart 新增、无 crash。

## 遗留 / 说明

- 修复后最终截图覆盖的是「bar_chart 组与重叠图例外框」的并集（`groupShapes` 既有按重叠分组行为）。若需截图仅含柱子区域，需再调整分组策略（未做，待用户确认）。
- 临时诊断类 `DebugArtifacts`（`DebugArtifacts2`，手动 GFSAPDFDocument + 强制 isIgnoreMCIDs 的对照实验）按用户要求**保留**在源码中，供后续调试。
- 恢复 backslash 路径注意：Git Bash 下 `java -cp` 分号拼接的 classpath 会被 MSYS2 路径转换破坏，需 `export MSYS2_ARG_CONV_EXCL='*'`。

## 相关文件（Relevant Files）

- `java/opendataloader-pdf-core/src/main/java/org/opendataloader/pdf/processors/ShapeRecognizer.java`：新增 `FALLBACK_FILL_COLOR`、`recognizeBarChartsFromFillBoxes()`，`recognizePage()` 增加兜底 bar 检测调用。
- `java/opendataloader-pdf-core/src/main/java/org/opendataloader/pdf/DebugSample1.java`：调试入口 PDF 路径改为 `202304271682510470028924-15.pdf`。
- 新增（保留）：`org/opendataloader/pdf/DebugArtifacts.java`、`DebugArtifacts2.java`。
- 样例：`docs/pdf/202304271682510470028924-15.pdf`。
- 只读参考（未改动）：`../veraPDF-validation/wcag-validation/.../chunks/ChunkParser.java`（`isProcessColorSpace`、`SCN_FILL`、`processf`、`processLineArts`/`parseLineArts` 逻辑）。