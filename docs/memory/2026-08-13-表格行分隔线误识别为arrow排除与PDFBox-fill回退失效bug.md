# opendataloader-pdf 任务记忆 — 2026-08-13（表格行分隔线误识别为 arrow 排除 + 顺手发现的 PDFBox fill 回退失效 bug）

## 目标（Goal）
- 修复 `202304291682681121000817-7.pdf` 第 1 页有 19 个 `shapeType=arrow` 的 `ShapeChunk`，但它们实际是**表格内部的横向行分隔线**而非流程图箭头。
- 在 `-83`/`-84` 流程图 PDF 上做 E2E 回归，确保真流程图箭头不被新逻辑误杀。

## 完整定位过程（从现象到根因）

### 第 1 步：复现 — 写临时 debug test 抽取 arrow
- 用户提供 PDF：`D:\Code\JavaCode\opendataloader-pdf-parse\opendataloader-pdf\docs\pdf\202304291682681121000817-7.pdf`
- 跑 `DebugSample` 受限于 Paddle server，改为仿 `ArrowE2ETest` 写临时测试 `ArrowDebugTempTest.java`（**调试专用，定位后已删除**），只跑 `DocumentProcessor.preprocessing`（不依赖 Paddle），直接从 `StaticContainers.getDocument().getArtifacts(page)` 抽 `ShapeChunk`。
- 第 1 页共识别 28 个 shape：5 个 `rectangle`（表格填充）+ 4 个 `polyline`（表格边框）+ **19 个 `arrow`**。
- 关键观察：所有 19 个 arrow 的 bbox 都是 `[51.18, y, 533.37, y+0.24]`——左右 x 完全等于 polyline 边框的左右 x，高度仅 0.24 pt，明显不是流程图箭头，是横线。

### 第 2 步：定位根因 — 看懂 `isConnectorLine` 的判定逻辑
- 翻代码：`java\opendataloader-pdf-core\src\main\java\org\opendataloader\pdf\processors\ShapeRecognizer.java:617` 的 `isConnectorLine(LineChunk line, List<ShapeChunk> existingShapes)`：
  ```java
  ShapeChunk startShape = findShapeNearPoint(line.getStartX(), line.getStartY(), existingShapes);
  ShapeChunk endShape   = findShapeNearPoint(line.getEndX(),   line.getEndY(),   existingShapes);
  return startShape != null && endShape != null && startShape != endShape;
  ```
- `findShapeNearPoint` 的判定：以线段端点为中心，命中任何 bbox 在 8 pt（`CONNECTOR_MARGIN`）内且**距离该 bbox 中心最近**的 shape。
- 拿 arrow `[51.18, 667.36, 533.37, 667.6]` 算：起点 `(51.18, 667.48)` 命中 rect1 `[51.3, 476.7, 171.8, 683.7]`（左边界 51.3 仅差 0.12 pt）；终点 `(533.37, 667.48)` 命中 poly1 `[51.18, 476.61, 533.37, 683.85]`（右边界 533.37 正好重合）。
- 两个端点命中**不同**的 shape → 判定为 connector → 输出 `arrow`。**但这条横线其实是 rect1 + poly1 这张表的内部行分隔线**，不是连接两 shape 的 connector。

### 第 3 步：归纳 — 真 connector 与表格内分隔线的区别
| 特征 | 真流程图 connector | 表格行分隔线 |
|---|---|---|
| 两端"近端不同 shape" | ✓ | ✓ |
| 杆长 vs shape 尺寸 | 远小于 | ≈ 与表格宽同量级 |
| 杆是否完全落在某 shape 内 | ✗ | ✓ |
| 杆所在 shape 是否就是表格边框 polyline | ✗ | ✓ |

最稳健的判别是**杆 bbox 是否完全落在某个已有 shape 内**——如果是，它就是该 shape 的内部结构线（行分隔线、坐标轴刻度、网格线），不是 connector。

## 已实现方案（方案 A，已验证）：isConnectorLine 增加包含检查

### 改动：`java\opendataloader-pdf-core\src\main\java\org\opendataloader\pdf\processors\ShapeRecognizer.java`
- `isConnectorLine(LineChunk line, List<ShapeChunk> existingShapes)`：
  - 在原"两端不同 shape"判定通过后，**额外**检查：线段 bbox 完全落在某个已有 shape 的 bbox 内（闭区间，`>=`/`<=`）→ 返回 `false`（不是 connector）。
- 新增私有方法 `isContainedInAnyShape(BoundingBox shaft, List<ShapeChunk> shapes)`：
  ```java
  for (ShapeChunk shape : shapes) {
      BoundingBox box = shape.getBoundingBox();
      if (box != null && !box.isEmpty()
              && box.getLeftX()   <= shaft.getLeftX()
              && box.getRightX()  >= shaft.getRightX()
              && box.getBottomY() <= shaft.getBottomY()
              && box.getTopY()    >= shaft.getTopY()) {
          return true;
      }
  }
  return false;
  ```
- 严格闭区间（含重合边）：保证 `51.18 == 51.18`、`<=` 的 0.12 pt 偏出等也算"包含"。

### 单测覆盖：`java\opendataloader-pdf-core\src\test\java\org\opendataloader\pdf\processors\ShapeRecognizerTest.java`
- 新增 `horizontalLineInsideSpanningShapeIsNotAnArrow`：
  - 合成场景：1 个宽填充矩形（表格左侧块）+ 4 段边框 polyline + 2 条行分隔线，全部 bbox 在 polyline 内部。
  - 断言：`arrowCount == 0`，同时 polyline 仍被识别。
- 新增 `horizontalLineCoincidentEdgesWithSpanningShapeIsNotAnArrow`：
  - 边界 case：线段 bbox 与 polyline 边框完全重合（与表格顶部线重合），严格包含仍应剔除。
- 已存在测试 `recognizesSingleSegmentConnectorBetweenShapes` 等**不破坏**（短竖线连两个独立小盒子，线段不在任一 shape 内部）。

### 附带路径修复：`java\opendataloader-pdf-core\src\test\java\org\opendataloader\pdf\processors\ArrowE2ETest.java`
- 原因：`ArrowE2ETest` 写在 `D:\Code\JavaCode\opendataloader-pdf\docs\pdf\...`（仓库早先路径，少了 `-parse`）；当前仓库已迁至 `D:\Code\JavaCode\opendataloader-pdf-parse\opendataloader-pdf\docs\pdf\...`，测试跑时 `NoSuchFileException`，从未真正执行。
- 修复：两处路径都加 `-parse` 后缀。

## 验证结果

| 测试 | 结果 |
|---|---|
| `ShapeRecognizerTest`（含 2 个新增） | ✅ 22/22 |
| `ArrowE2ETest.arrowOn83StillRecognized` | ✅ 1/1（无回归，artifact 路径仍正确识别箭头 `[296.48, 158.52, 303.48, 175.32]`）|
| 真实 PDF `202304291682681121000817-7.pdf` 页 1 | ✅ 19 个误识别 arrow 全部消除；保留 5 个 `rectangle` + 4 个 `polyline` |

## 关键决策（Key Decisions）
- **方案 A**（包含检查）而非方案 B（长宽比）：bbox 严格落在某 shape 内是最干净的"内内部结构 vs 跨 shape 连接"判别，长宽比阈值难调、易误杀长连接器。
- **方案 A** 而非方案 C（要求 arrowhead 存在）：方案 C 需要重新梳理 `findArrowBBox` 的语义，改动面大、回归风险高；方案 A 与 2026-08-07 的 PDFBox fill 回退路径完全正交、不相互影响。
- 闭区间包含（含重合边）：适配表格分隔线左/右 x 与 polyline 完全重合的真实场景。

## 构建 / 运行
- 目录：`java\`。
- 定向测试：
  ```bash
  mvn -pl opendataloader-pdf-core -am test "-Dtest=ShapeRecognizerTest,ArrowE2ETest#arrowOn83StillRecognized" "-DfailIfNoTests=false" "-Dmaven.javadoc.skip=true"
  ```

## 相关文件（Relevant Files）
- `java\opendataloader-pdf-core\src\main\java\org\opendataloader\pdf\processors\ShapeRecognizer.java`：
  `isConnectorLine` + 新增 `isContainedInAnyShape`（**核心修复**）。
- `java\opendataloader-pdf-core\src\test\java\org\opendataloader\pdf\processors\ShapeRecognizerTest.java`：
  新增 `horizontalLineInsideSpanningShapeIsNotAnArrow`、`horizontalLineCoincidentEdgesWithSpanningShapeIsNotAnArrow`。
- `java\opendataloader-pdf-core\src\test\java\org\opendataloader\pdf\processors\ArrowE2ETest.java`：
  路径修复 `-parse`。
- `docs\pdf\202304291682681121000817-7.pdf`：本次主修复验证样例。
- `docs\pdf\202302281677505819604328-83(流程图).pdf`、`...-84(流程图).pdf`：E2E 回归样例。

---

# 顺手发现的 Bug（未修，独立任务）

## Bug：`extractPageFillBoxes` PDFBox fill 回退在 -84 流程图上失效

### 现象
`ArrowE2ETest.arrowOneHeadRecoveredFromPdfBoxFill`（验证 -84 页 0 箭头1）期望 bbox bottomY = 244.87（含填充箭头头尖），实测 249.87（仅含杆，head 丢失）。

### 定位过程
1. 在 `findArrowBBox` 加临时打印：对 -84 箭头1 shaft `[298.5, 249.87, 299.5, 267.97]`：
   - `pickArrowhead(shaft, filledArtBoxes)` 返回 null（merged 容器吃掉，正常）
   - `pickArrowhead(shaft, filterShapeCoincidentFills(fillBoxes, existingShapes))` 返回 **null**
   - `filledArtBoxes=14, fillBoxes=61`，**回退**没找到箭头头
2. 临时 dump `fillBoxes` 中 x≈296 的 fill：
   - `[296.0, 573.949951171875, 302.0, 597.0499877929688]`——y 范围 [573.95, 597.05]，与 shaft y [249.87, 267.97] 完全错位 328 pt
   - 巧合：`841.92 - 267.97 = 573.95`，`841.92 - 244.87 = 597.05`——这是把 shaft 沿页面水平中线**镜面翻转**后的位置
3. 在 `extractPageFillBoxes` 加临时打印（**已撤回**），直接观察 PDFBox 返回：
   - `rect=(88.464, 785.04, 507.004, 785.75995) pageHeight=841.92`
   - 转换为 y-up 后 `bottom=56.16, top=56.88`——**PDFBox 返回 y-down 是事实**，现有转换公式对 y-down 输入**数学上正确**
   - 那么问题不在 y 转换公式本身

### 推断（本次未实证到底）
- 61 根 fill 中**没有任何一根**与 shaft y 范围 `[249.87, 267.97]` 相交——意味着 -84 流程图的箭头1 填充路径**根本没被 PDFBox 当作闭合填充提取出来**（可能 PDF 内容流中此路径的 `closePath`/`FILL`/`FILL_STROKE` 标记不符合 `GetDrawings` 提取条件，或 CTM 变换下 `getBounds2D()` 给出非预期值）。
- 后续验证方向：在 `extractPageFillBoxes` 的 `for` 循环里**对每个 fill box 打印原始 PDFBox rect + 转换后值**，**直接**观察 PDFBox 给 arrow1 的 fill 输出长什么样；如果 PDFBox 没产出此 fill，问题在 PDFBox 内容流提取；如果产出了但 y 错位，问题在 `filterShapeCoincidentFills` / `pickArrowhead` 的过滤逻辑（注意 -84 所有节点 box 在 ShapeRecognizer 里被识别为 `TYPE_POLYLINE` 而非 `TYPE_RECTANGLE`，所以 `filterShapeCoincidentFills` 不会剔除任何 fill——这不是问题原因）。

### 当前影响范围
- -84 PDF 流程图箭头识别框丢失填充头尖端（视觉上箭头 bbox 略短），不影响 connector 判定本身、不影响 -83（artifact 路径已识别头）、不影响本次表格分隔线修复。

### 建议下一步行动
1. 写一个针对 -84 的临时 dump 测试，`extractPageFillBoxes` 内逐个打印 fill rect + 转换后 BoundingBox，找出 arrow1 期望 fill 是否被提取、为何没匹配上 `pickArrowhead` 的所有约束（`overlaps`、`extendsBefore XOR extendsAfter`、`perpDim<=15`、`alongDim<=3×shaftAlong`）。
2. 若 PDFBox 根本没产此 fill：扩展 `GetDrawings` 提取条件，或考虑 2026-08-07 记忆中"备选方案1"——在 veraPDF `LineArtContainer.add` 层不让闭合填充被合并。
3. 若 PDFBox 产了但被 `pickArrowhead` 拒绝：松绑对应约束或调整候选筛选顺序。

### 风险
- 单独任务，不要并入本次"表格行分隔线"修复 commit。

## 相关文件（Bug 涉及但未修）
- `java\opendataloader-pdf-core\src\main\java\org\opendataloader\pdf\processors\DocumentProcessor.java`：行 854-856 的 `extractPageFillBoxes` y 转换公式（公式本身正确，疑点不在此）。
- `java\opendataloader-pdf-core\src\main\java\org\opendataloader\pdfbox\GetDrawings.java`：内容流 path 收集与 bbox-only LineArtChunk 提取（可能漏掉某些 FILL/FILL_STROKE+closePath）。
- `java\opendataloader-pdf-core\src\main\java\org\opendataloader\pdf\processors\ShapeRecognizer.java`：`findArrowBBox` / `pickArrowhead` / `filterShapeCoincidentFills`（候选筛选）。
- `docs\pdf\202302281677505819604328-84(流程图).pdf`：bug 复现样例。