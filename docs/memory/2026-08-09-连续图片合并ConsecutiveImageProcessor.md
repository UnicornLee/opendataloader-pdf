# 连续图片合并 ConsecutiveImageProcessor（一页内相邻图片合并为一张截图）

- 时间：2026-08-09
- 任务：在 `DocumentProcessor.processDocument` 每页循环内、`LineArtProcessor.processLineArtGroups(...)` 之后，新增处理步骤：收集**连续**的 `ImageChunk`，重算合并包围盒，重新截图一次，用单张合并图替换原图序列。
- 状态：已实现 + 单元测试通过；合并语义阈值（50% / 替换原图 / 并排合并）**待用户拍板**。

## 目标（Goal）
- PDF 引擎把同一图片切分成多块相邻图片时（如分片存储的图、tile 序列），目前会输出"一列碎图"；期望合并为一张完整截图。
- 复用既有模式：分组 → `unionBoundingBoxes` → `imagesUtils.saveImageChunk` 截图 → 用新 `ImageChunk` 替换原内容（同 BarChart / LineArt / Flowchart）。

## 前置事实（已核实）
- `processDocument` 每页循环前（第 442-445 行，注释 `// 1. 连续`）：`pageContents.sort(Comparator.comparingDouble(item -> item.getTopY()))` 后 `Collections.reverse(pageContents)` → **topY 降序（页面上方在前）**。因此 list 相邻 ≈ 纵向相邻，"连续"判定建立在 list 相邻之上。
- 每页循环（第 498 行起）：`BarChartProcessor`(:507) → `FlowchartProcessor`(:508) → `LineArtProcessor`(:510) → **新增 `ConsecutiveImageProcessor`(:511)** → 循环结束。
- grep `ConsecutiveImage|mergeConsecutive|consecutiveImage|MergeImages|combineImages`：仓库中**不存在**现成的连续图片合并实现。
- `BoundingBox` API：`getTopY()` / `getBottomY()` / `getHeight()`（= topY − bottomY，正值）/ `isEmpty()` 均可用；`BoundingBoxGroupUtils.unionBoundingBoxes(List<IObject>, int pageNumber)` 返回并集 bbox。
- `ImageChunk` / `BoundingBox` / `IObject` / `ImagesUtils` 在 `DocumentProcessor` 中已 import（同一 `processors` 包内调用，无新 import）。

## 已实现方案
### ConsecutiveImageProcessor（新文件，`processors` 包，final + private ctor + 静态方法）
- `processConsecutiveImages(List<IObject> pageContents, int pageNumber, ImagesUtils imagesUtils)`：单次遍历重建 `result` 列表：
  1. 非 `ImageChunk` 元素直接保留（同时**打断运行**——文字/图形等夹在中间则前后不合并）。
  2. 从当前 `ImageChunk` 起贪婪收集连续运行：相邻下一个也是 `ImageChunk` 且 `areClose(prev, next)` 为真则入组。
  3. 运行长度 ≥ 2 且 `unionBoundingBoxes` 非空 → `new ImageChunk(union)` + `saveImageChunk` 截图一次，**整个运行替换为合并图**（`i = j` 跳转）。
  4. 运行长度 < 2 或 union 为空 → 保留当前元素，`i++`。
  5. 结束 `pageContents.clear(); pageContents.addAll(result);`。
- `areClose(upper, lower)`：`gap = upper.getBottomY() − lower.getTopY()`；`maxGap = min(高度差小者) × MAX_VERTICAL_GAP_RATIO`；`gap ≤ maxGap` 判连续。垂直重叠/并排（gap ≤ 0）天然满足 → 合并。
- 常量 `MAX_VERTICAL_GAP_RATIO = 0.5`（垂直间隙 ≤ 较小图片高度一半）。
- null / 空列表 / null imagesUtils → no-op。

### DocumentProcessor（改）
- 第 510 行 `LineArtProcessor.processLineArtGroups(...)` 之后新增一行调用 `ConsecutiveImageProcessor.processConsecutiveImages(pageContents, pageNumber, imagesUtils);`。

### 单测 ConsecutiveImageProcessorTest（新，6 例全过）
复用既有 `CapturingImagesUtils`（记录 saved 不落盘）。坐标语义：`new BoundingBox(page, leftX, bottomY, rightX, topY)`。
1. `mergesCloseStackedImagesIntoOneScreenshot`：垂直 gap=10 ≤ 50 合并为 1 张，bbox=并集(leftX/bottomY/rightX/topY 极值)。
2. `mergesSideBySideImages`：垂直重叠（负 gap）→ 合并，width 取两图并集。
3. `keepsFarAwayImagesSeparate`：gap=80 > 50 → 不合并，原图保留。
4. `nonImageElementBreaksTheRun`：中间夹 `ShapeChunk` → 不合并。
5. `singleImageIsLeftUntouched`：运行长度 1 → 原样保留（`assertSame`）。
6. `nullOrEmptyInputsAreNoOp`：null / 空 / imagesUtils=null 不抛异常。

## 关键决策（待确认）
| 决策 | 当前取值 | 待确认 |
|---|---|---|
| "连续"判定 | list 相邻 + 垂直间隙 ≤ 较小高度×50%（`MAX_VERTICAL_GAP_RATIO=0.5`） | 阈值是否合适？ |
| 并排（垂直重叠，gap≤0）图片 | 合并 | 是否应合并？ |
| 合并后 | **替换**原图（不保留） | 替换 or 保留原图？ |
| 合并图放置位置 | 替换在运行起始处 | — |

## 验证结果
- `mvn -q -pl opendataloader-pdf-core -am test "-Dtest=ConsecutiveImageProcessorTest" "-Dcheckstyle.skip=true" "-Dlicense.skip=true"` → **通过**（无输出=成功）。
- 尚未跑真实 PDF 端到端验证（可用 `DebugSample` + `docs/pdf` 样例 + `tmp_output` 观察合并截图）。

## 构建 / 运行
- 目录：`java\`。
- 定向测试：`mvn -q -pl opendataloader-pdf-core -am test "-Dtest=ConsecutiveImageProcessorTest" "-Dcheckstyle.skip=true" "-Dlicense.skip=true"`（PowerShell 下 `-D` 参数必须加引号，否则被当生命周期阶段解析）。

## 相关文件（Relevant Files）
- `java\opendataloader-pdf-core\src\main\java\org\opendataloader\pdf\processors\ConsecutiveImageProcessor.java`（新）：`processConsecutiveImages` + `areClose` + `MAX_VERTICAL_GAP_RATIO`。
- `java\opendataloader-pdf-core\src\main\java\org\opendataloader\pdf\processors\DocumentProcessor.java`：:442-445 topY 降序排序（注释 `// 1. 连续`）；:511 新增调用（原 :510 `LineArtProcessor` 之后）。
- `java\opendataloader-pdf-core\src\test\java\org\opendataloader\pdf\processors\ConsecutiveImageProcessorTest.java`（新）：6 用例。
- `java\opendataloader-pdf-core\src\test\java\org\opendataloader\pdf\processors\CapturingImagesUtils.java`：测试截图记录 fixture。
- `java\opendataloader-pdf-core\src\main\java\org\opendataloader\pdf\processors\BoundingBoxGroupUtils.java`：`unionBoundingBoxes`。
- `java\opendataloader-pdf-core\src\main\java\org\opendataloader\pdf\utils\ImagesUtils.java`：`saveImageChunk`。

## 备忘
- 排序在**每页并行循环**内完成，处理器在**串行每页循环**内执行；`pageContents` 到处理器链时基本保持 topY 降序，但中间处理器可能改动内容，`areClose` 以"list 相邻"为唯一依据。
- 合并只针对 `ImageChunk`；BarChart/LineArt 截图产物（也是 ImageChunk）同样参与合并。
- 阈值若后续被证明误合并（如相邻无关插图被吞），可下调 `MAX_VERTICAL_GAP_RATIO` 或对水平重叠比例加约束。
