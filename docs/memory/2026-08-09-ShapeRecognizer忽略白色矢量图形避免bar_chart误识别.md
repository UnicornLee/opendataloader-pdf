# opendataloader-pdf 任务记忆 — 2026-08-09（ShapeRecognizer 忽略白色矢量图形，避免误识别为 bar_chart）

## 目标（Goal）
- 修复 `202303181679059838994480-197.pdf` 页顶部 12 个白色填充矩形被误判为 `bar_chart` 的问题。
- 在 `ShapeRecognizer` 中忽略白色/近白色矢量图形（默认页面背景为白色时这些图形不可见），避免把表格行背景、装饰条纹等误识别为图表/矩形形状。

## 问题原因（Root Cause）
- PDF **没有标准的页面背景色字段**，主流阅读器默认页面背景为白色。
- 197.pdf 顶部存在 12 个白色填充矩形（`color=[1.0, 1.0, 1.0]`），宽度完全相同（418.27 pt）、上下边完全贴合（无间隙）、横跨整个内容区。这些是不可见的装饰/表格背景。
- `ShapeRecognizer` 原先不区分颜色，将这 12 个白条当成水平柱状图，识别为 `shape type=bar_chart, components=12`；随后 `BarChartProcessor` 把该区域截图替换为 `ImageChunk`，导致页顶部被输出成一张图片。
- 即便此前的 `BAR_VALUE_VARIATION` / `hasBarGaps` 校验能把它降级为 `rectangle`，白色背景条本身仍不应被提取为形状。

## 已实现方案

### ShapeRecognizer 白色过滤
- 新增常量 `WHITE_EPSILON = 0.005`：颜色通道与 1.0 的偏差在此范围内视为白色（覆盖 PDF 中常见的 `1.0` 及浮点误差 `0.9999`）。
- 新增 `isWhite(double[] color)` 方法（带 Javadoc）。
- 在 `recognizePage()` 收集原始 artifacts 时：
  - 直接遇到的 `LineChunk` 若为白色，跳过。
  - `LineArtChunk` 内部的子 `LineChunk` 若为白色，也跳过；
  - bbox-only 的 `LineArtChunk`（无颜色信息，通常用于箭头头回退）仍保留。
- 注释说明：PDF 无标准页面背景色，这里按默认白背景处理，因此白底白色图形不可见、且几乎总是装饰/表格背景，不应作为图表元素提取。

### 柱状图误判加固（已在本任务过程中同时落地）
- 新增 `BAR_VALUE_VARIATION = 0.15`。
- 新增 `isValidBarGroup()` / `hasBarGaps()` / `hasVaryingBarLengths()`：
  - 条与条之间必须有正间隙，排除上下/左右完全贴合的表格行；
  - 条在“数值维度”上必须有差异（竖条看高度、横条看宽度），排除等长装饰条纹。
- 在 `detectBarGroups()` 和 `guessFilledShapeType()` 两条路径都应用该校验，不合法降级为 `rectangle`。

### 测试补充
- `ShapeRecognizerTest` 新增/调整：
  - `whiteShapesAreIgnored`：白色形状被忽略，不产生任何 `ShapeChunk`。
  - `stackedEqualWidthHorizontalBarsAreNotBarChart`：颜色改为非白色，继续验证等宽水平条不会误判为柱状图。
  - `equalHeightVerticalBarsAreNotBarChart`：等高度竖条不会误判为柱状图。

## 关键决策（Key Decisions）
- **按默认白背景处理**：因为 PDF 没有标准页面背景色字段，渲染采样每个角落会引入额外开销；默认白背景是 PDF 事实标准，直接忽略白色形状简单且覆盖绝大多数场景。
- 仅忽略**颜色**，不忽略几何：非白色装饰条仍走 `isValidBarGroup` 校验，不会误识别为图表。
- 不过滤 bbox-only 的 `LineArtChunk`：它没有颜色信息，且多为箭头头回退候选，保留不影响。
- 阈值选择：`WHITE_EPSILON = 0.005` 足够覆盖浮点误差，又不会把浅灰色（如 0.95）误判为白色。

## 验证结果
- `ShapeRecognizerTest`：**20/20 通过**。
- `BarChartProcessorTest`：**3/3 通过**。
- 重新跑 `202303181679059838994480-197.pdf`：
  - 日志中不再出现 `Page 1: recognized N shape(s)`，12 个白色矩形被完全忽略；
  - 输出 JSON 顶部不再被截图成 `ImageChunk`，恢复为正常文本段落。

## 构建 / 运行
- 目录：`java\`。
- 定向测试：
  ```powershell
  mvn -pl opendataloader-pdf-core test -Dtest=ShapeRecognizerTest -DfailIfNoTests=false
  mvn -pl opendataloader-pdf-core test -Dtest=BarChartProcessorTest -DfailIfNoTests=false
  ```
- 真实 PDF 验证：用临时 `Debug197.java` 调用 `OpenDataLoaderPDF.processFile(...-197.pdf, ...)`，验证后已删除临时文件。

## 相关文件（Relevant Files）
- `java\opendataloader-pdf-core\src\main\java\org\opendataloader\pdf\processors\ShapeRecognizer.java`：
  - 新增 `WHITE_EPSILON`、`isWhite()`；
  - `recognizePage()` 中过滤白色 `LineChunk` / `LineArtChunk` 子节点；
  - 保留此前新增的 `BAR_VALUE_VARIATION`、`isValidBarGroup()`、`hasBarGaps()`、`hasVaryingBarLengths()` 校验。
- `java\opendataloader-pdf-core\src\test\java\org\opendataloader\pdf\processors\ShapeRecognizerTest.java`：
  - 新增 `whiteShapesAreIgnored`；
  - 新增/调整 `stackedEqualWidthHorizontalBarsAreNotBarChart`、`equalHeightVerticalBarsAreNotBarChart`。
- `docs\pdf\202303181679059838994480-197.pdf`：验证样例文件。
