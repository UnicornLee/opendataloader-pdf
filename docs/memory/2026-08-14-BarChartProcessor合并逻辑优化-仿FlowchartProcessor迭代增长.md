# opendataloader-pdf 任务记忆 — 2026-08-14（BarChartProcessor 合并逻辑优化，仿 FlowchartProcessor 迭代增长）

## 目标（Goal）

优化 `org.opendataloader.pdf.processors.BarChartProcessor#processBarChartGroups` 方法，使其逻辑与 `org.opendataloader.pdf.processors.FlowchartProcessor#processFlowchartGroups` 类似：

- 每次合并别的 shape group 或 pageContents 中的内容，都要重新计算边界，再去合并，直至没有新内容落在范围内为止。
- 不清楚的地方需向用户澄清。
- 给出修改方案，用户同意后才能改代码。

## 背景（Context）

### 1. 当前 `BarChartProcessor#processBarChartGroups` 实现（修改前）

```java
public static void processBarChartGroups(List<IObject> pageContents,
                                          List<List<IObject>> groupedShapeChunks,
                                          ImagesUtils imagesUtils,
                                          int pageNumber) {
    if (pageContents == null || imagesUtils == null || groupedShapeChunks == null) return;
    for (List<IObject> group : groupedShapeChunks) {
        if (group == null || group.isEmpty() || !BoundingBoxGroupUtils.containsBarChart(group)) continue;
        BoundingBox groupBox = BoundingBoxGroupUtils.unionBoundingBoxes(group, pageNumber);
        if (groupBox == null || groupBox.isEmpty()) continue;
        pageContents.removeIf(content -> BoundingBoxGroupUtils.isVerticallyMostlyInside(groupBox, content.getBoundingBox()));
        ImageChunk imageChunk = new ImageChunk(groupBox);
        imagesUtils.saveImageChunk(imageChunk);
        pageContents.add(imageChunk);
    }
}
```

特点：一次性遍历、单独处理每个柱状图 group，不做合并/吸收。

### 2. 对比 `FlowchartProcessor#processFlowchartGroups`（参考实现）

核心模式：
1. `boolean[] skipped` 标记已吸收的 group；
2. 外层 `for i` 遍历当前 group；
3. 对每个 group：先 `collectCluster` 收集一个初始 cluster（含一次 growth step）；
4. 然后 `do-while` 循环吸收后续 group（j > i），每次 `screenshotBox.union(laterCluster.boundingBox)` 重算边界；
5. 循环结束条件：本轮无新增吸收；
6. 截图框使用「初始 bbox + 水平 5pt / 垂直 1pt margin」，吸收后续 group 时 `screenshotBox.union` 但**不再重新加 margin**（这是 `2026-08-13-FlowchartProcessor流程图合并逻辑优化.md` 的关键经验：重算 margin 会让现有断言漂移）。

## 定位过程（Investigation）

### 第 1 步：阅读源码，理清两个方法的输入/输出契约

- 入参：`pageContents`、`groupedShapeChunks`、`imagesUtils`、`pageNumber`，签名完全相同；
- 调用方：`DocumentProcessor.preprocessing`，BarChart 在 Flowchart 之前调用；
- `groupedShapeChunks` 由 `ShapeRecognizer.groupShapes(shapeChunks)` 产出，元素是 `List<IObject>`（实际只含 `ShapeChunk`）。

### 第 2 步：阅读已有工具 `BoundingBoxGroupUtils`

可复用工具：`unionBoundingBoxes`、`unionShapeBoundingBoxes`、`containsBarChart`、`containsArrow`、`isVerticallyMostlyInside`、`hasSignificantOverlap`。新代码应优先用 `unionShapeBoundingBoxes`（与 FlowchartProcessor 风格一致）。

### 第 3 步：阅读 `2026-08-13-FlowchartProcessor流程图合并逻辑优化.md`

明确两点关键经验：
- 截图框在合并后**不要再扩 margin**，否则测试断言漂移；
- 合并逻辑应放在 `isFlowchartCluster` 判定**之前**，让合并后的整体 cluster 参与判定。

### 第 4 步：识别设计选择分歧，提炼 4 个关键问题向用户确认

BarChartProcessor 与 FlowchartProcessor 语义不同（柱状图无需"是否流程图"判定门），所以有几个分叉点必须澄清：

| # | 决策点 | 选项 | 用户选择 |
|---|--------|------|----------|
| 1 | pageContents 吸收判定 | (a) 保持 `isVerticallyMostlyInside`；(b) 改 `overlaps(COLLECTION_MARGIN)`；(c) 更严格完全垂直包含 | (b) 与 FlowchartProcessor 一致 |
| 2 | 初始 bbox 是否加 margin | (a) 不加；(b) 加水平 5pt / 垂直 1pt | (b) 与 FlowchartProcessor 一致 |
| 3 | 吸收其他 group 的类型限制 | (a) 任意 shape group；(b) 仅柱状图 group | (a) 与 FlowchartProcessor 一致 |
| 4 | do-while 是否需最大迭代次数保护 | (a) 需要；(b) 不需要 | (a) 推荐：硬上限 |

> 用户选择整体倾向"完全对齐 FlowchartProcessor"风格，仅在"加 margin"一项会改变现有截图 bbox（水平+5pt、垂直+1pt），需更新现有测试断言。

### 第 5 步：预演现有测试，确认 bbox 断言需更新

`BarChartProcessorTest.detectsAndReplacesBarChartGroup` 中：

```java
ShapeChunk frame = new ShapeChunk(new BoundingBox(0, 100, 100, 310, 300), ShapeChunk.TYPE_RECTANGLE, GRAY, 1);
```

构造器签名 `BoundingBox(pageNumber, leftX, bottomY, rightX, topY)`，得 `frame` 的 bbox 为 `(100,100)-(310,300)`。

注意：`bar1` / `bar2` 写的是 `BoundingBox(0, 100, 100, 100, 300)` 和 `BoundingBox(0, 210, 100, 100, 300)`——按构造器语义 `leftX>=rightX`，verapdf 的 `isEmpty()` 会判为 `true`，实际只有 `frame` 进入 union。

因此旧断言期望 bbox = (100,100)-(310,300)，加 5pt / 1pt margin 后变成 (95,99)-(315,301)，现有 4 个断言全部需要更新。其他断言（`saved.size==1`、`pageContents.size==2`、`contains logo`、`contains imageChunk`）保持不变。

### 第 6 步：设计新算法骨架（伪代码）

```java
public static void processBarChartGroups(...) {
    if (pageContents == null || imagesUtils == null || groupedShapeChunks == null) return;
    boolean[] skipped = new boolean[groupedShapeChunks.size()];
    for (int i = 0; i < groupedShapeChunks.size(); i++) {
        List<IObject> group = groupedShapeChunks.get(i);
        if (skipped[i] || group == null || group.isEmpty()
                || !BoundingBoxGroupUtils.containsBarChart(group)) continue;
        BoundingBox groupBox = BoundingBoxGroupUtils.unionShapeBoundingBoxes(group, pageNumber);
        if (groupBox == null || groupBox.isEmpty()) continue;

        BoundingBox screenshotBox = expandWithMargin(groupBox,
                SCREENSHOT_HORIZONTAL_MARGIN, SCREENSHOT_VERTICAL_TOLERANCE);

        List<IObject> absorbedShapes = new ArrayList<>(group);
        List<IObject> absorbedContents = new ArrayList<>();
        boolean expanded;
        int iterations = 0;
        do {
            expanded = false;
            iterations++;

            // 1. 吸收后续 shape group
            for (int j = i + 1; j < groupedShapeChunks.size(); j++) {
                if (skipped[j]) continue;
                List<IObject> laterGroup = groupedShapeChunks.get(j);
                if (laterGroup == null || laterGroup.isEmpty()) continue;
                BoundingBox laterBox = BoundingBoxGroupUtils.unionShapeBoundingBoxes(laterGroup, pageNumber);
                if (laterBox == null || !screenshotBox.overlaps(laterBox)) continue;
                absorbedShapes.addAll(laterGroup);
                screenshotBox.union(laterBox);
                skipped[j] = true;
                expanded = true;
            }

            // 2. 吸收 pageContents（带 COLLECTION_MARGIN 容差）
            List<IObject> snapshot = new ArrayList<>(pageContents);
            for (IObject content : snapshot) {
                if (absorbedContents.contains(content)) continue;
                BoundingBox contentBox = content.getBoundingBox();
                if (contentBox == null || contentBox.isEmpty()) continue;
                if (contentBox.overlaps(screenshotBox, COLLECTION_MARGIN)) {
                    absorbedContents.add(content);
                    screenshotBox.union(contentBox);
                    expanded = true;
                }
            }
        } while (expanded && iterations < MAX_GROWTH_ITERATIONS);

        pageContents.removeAll(absorbedContents);
        pageContents.removeAll(absorbedShapes);
        ImageChunk imageChunk = new ImageChunk(screenshotBox);
        imagesUtils.saveImageChunk(imageChunk);
        pageContents.add(imageChunk);
    }
}
```

并新增 `expandWithMargin(box, xMargin, yMargin)` 私有方法（与 FlowchartProcessor 的 `expandHorizontally` 同语义，独立保留避免跨类耦合）。

## 根本原因（Why the Change）

原 `processBarChartGroups` 是「一次性扫描」：每个含柱状图的 group 独立算 bbox、独立 remove/add ImageChunk，**完全不考虑与相邻 shape group 或 pageContents 内容的关系**。这导致：

1. 若页面存在与柱状图相邻/重叠的其他 shape group（边框、坐标轴、连接线），它们不会被吸收，截图无法完整覆盖柱状图 + 邻接元素；
2. 若 pageContents 中存在与 bbox 重叠但未在初始计算时被吸收的内容（如柱状图上方的标题、下方的图例文本），不会被替换为同一张截图，可能出现内容重复或截图边界缺失。

而 `FlowchartProcessor` 已通过迭代增长解决了同类问题（参见 `2026-08-13-FlowchartProcessor流程图合并逻辑优化.md`），BarChartProcessor 应复用同一模式。

## 已实现方案

### 1. `BarChartProcessor.java` 重写

**文件：** `java/opendataloader-pdf-core/src/main/java/org/opendataloader/pdf/processors/BarChartProcessor.java`

改动：
- 新增常量 `COLLECTION_MARGIN=1.0`、`SCREENSHOT_HORIZONTAL_MARGIN=5.0`、`SCREENSHOT_VERTICAL_TOLERANCE=1.0`、`MAX_GROWTH_ITERATIONS=30`；
- 重写 `processBarChartGroups`：使用 `boolean[] skipped` + 外层 `for i` + 内层 `do-while`；
- 循环内依次：① 吸收后续 shape group（任意类型，bbox 与 `screenshotBox` 重叠即吸收）→ ② 吸收 pageContents（bbox 与 `screenshotBox` 以 `COLLECTION_MARGIN` 重叠）→ 每次 `screenshotBox.union(...)` 重算边界；
- 循环退出条件：本轮无新增 **或** 达到最大迭代次数；
- 退出循环后：`pageContents.removeAll(absorbedContents)` → `removeAll(absorbedShapes)` → `saveImageChunk(new ImageChunk(screenshotBox))` → `pageContents.add(imageChunk)`；
- 新增私有 `expandWithMargin(box, xMargin, yMargin)` 辅助方法。

### 2. `BarChartProcessorTest.java` 更新

**文件：** `java/opendataloader-pdf-core/src/test/java/org/opendataloader/pdf/processors/BarChartProcessorTest.java`

改动：
- **更新** `detectsAndReplacesBarChartGroup`：bbox 断言改为 (95,99)-(315,301)（应用新 margin），其余断言（1 张截图、pageContents.size=2、含 logo、含 imageChunk）保持不变；
- **新增** `absorbsAdjacentOverlappingShapeGroup`：模拟 FlowchartProcessorTest 的 `mergesSubsequentGroupIntersectingScreenshotBox` 场景——group1 含柱状图、group2 是与之相邻的矩形框组，期望合并为 1 张截图且 bbox 覆盖两者；
- **新增** `absorbsOverlappingPageContents`：在柱状图 group 内放置 1 个 `TextChunk`（与 group 重叠但稍突出左边界）+ 1 个远处 `TextChunk`，验证前者被吸收并扩大 bbox、后者被保留；
- **新增** `keepsDistantBarChartGroupsSeparate`：两个不相交的柱状图 group → 2 张截图，pageContents 中保留 2 个 ImageChunk。

### 3. 暂不动 `BoundingBoxGroupUtils`

`isVerticallyMostlyInside` 在改后将不再被任何 `BarChartProcessor` 代码引用，但属 package-private 工具方法，可能被未来代码或外部依赖使用，本次 PR 不动。若需清理另开后续工作。

### 4. 常量后续调整（用户提交后）

用户事后调整了 4 个常量中的 2 个，最终值为：

| 常量 | 初版（计划阶段） | 最终（当前代码） | 调整理由（推测） |
|---|---|---|---|
| `COLLECTION_MARGIN` | 2.0 | **1.0** | 收窄吸收范围，避免误吞与柱状图远端但仍在 2pt 容差内的细小内容 |
| `SCREENSHOT_HORIZONTAL_MARGIN` | 5.0 | 5.0（未变） | — |
| `SCREENSHOT_VERTICAL_TOLERANCE` | 1.0 | 1.0（未变） | — |
| `MAX_GROWTH_ITERATIONS` | 10 | **30** | 放宽上限，允许更复杂的页面（多柱状图层叠、嵌套图例）充分收敛 |

> 调整后 `absorbsOverlappingPageContents` 测试用例中 `label.bbox=(80,150)-(95,250)` 与 `screenshotBox=(95,99)-(175,301)` 仍满足 1pt 容差下的重叠条件（effective x 94≤96、y 98≤251），测试无需调整；测试注释「overlaps within 2pt collection margin」现已过时，下次维护时可一并刷新。

## 验证结果

| 测试 | 结果 |
|---|---|
| `BarChartProcessorTest` | ✅ 6/6 通过（3 旧 + 3 新） |
| `FlowchartProcessorTest` | ✅ 9/9 通过（无回归） |
| `ShapeRecognizerTest` | ✅ 22/22 通过（无回归） |

> ⚠️ `LineArtProcessorTest`（3 个用例：`emptyPaddleUrlSkipsOcrBranch`、`multipleAdjacentLineArtChunksMergeIntoImage`、`nullPaddleUrlStopsBeforeOcr`）与 `ArrowE2ETest`（2 个 NoSuchFile 错误）在原代码（git stash 后）就已失败，与本次修改无关，详见末尾「注意事项」。

### Maven 构建

```
[INFO] Tests run: 37, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

## 关键决策（Key Decisions）

- **完全对齐 FlowchartProcessor 风格**：用户 4 项设计选择整体倾向"和 FlowchartProcessor 一致"——仅"加 margin"一项改变截图 bbox 大小。这让两个 processor 的代码阅读与维护对称性更好。
- **截图框 margin 在初始计算一次，吸收后续 group 时不再重复加 margin**：与 FlowchartProcessor 的关键经验一致（避免现有断言漂移）。
- **`absorbedContents` 使用 `List<IObject>` 而非 `Set`**：与 FlowchartProcessor 风格一致；通过 `contains` 检查避免重复添加；`removeAll` 时 IObject 默认按引用比较。
- **pageContents 遍历用 snapshot**：`pageContents` 在迭代中未被修改（仅在循环结束后 `removeAll` + `add`），但 snapshot 保证未来若需 inline 移除时也安全。
- **吸收其他 group 限定为后续（j > i）**：与 FlowchartProcessor 一致；`skipped[]` 防止被吸收的 group 被重复处理。
- **`MAX_GROWTH_ITERATIONS=30` 硬上限**：在 pageContents 有限且 union 单调递增的前提下，循环本应自然收敛；上限只是防止异常数据下意外死循环的安全网。FlowchartProcessor 后续也加上同样常量，对称且防御。
- **`unionShapeBoundingBoxes` 取代 `unionBoundingBoxes`**：与 FlowchartProcessor 风格一致；两组 groups 仅含 `ShapeChunk`，结果等价但风格统一便于后续维护。
- **不动 LineArtProcessor / FlowchartProcessor / DocumentProcessor**：BarChartProcessor 仍在 FlowchartProcessor 之前调用，先于流程图处理消化柱状图 region，避免重复处理；对外签名不变，调用方零改动。

## 潜在影响 / 风险

- **行为变化**：现有 PDF 的柱状图截图 bbox 会扩大 5pt 水平 / 1pt 垂直；并可能吞并相邻 shape group 与重叠文本/图像。属预期优化，但建议用 1-2 个真实 PDF 回归确认无视觉退化。
- **性能**：`MAX_GROWTH_ITERATIONS=30` 提供硬上限，最坏情况下比旧版多 ~30 次线性扫描（pageContents 大小为 N 时 ~30N 次比较），可接受。FlowchartProcessor 同样使用 30 作为上限。
- **API 兼容性**：仅修改方法内部实现，对外签名 `processBarChartGroups(pageContents, groupedShapeChunks, imagesUtils, pageNumber)` 不变。

## 注意事项

### 1. 构建系统为 Maven，不是 Gradle

- 项目根目录的 `java/` 下使用 `pom.xml` 构建，仓库根的 `scripts/test-java.sh` 即 `mvn test "$@"`；
- 不存在 `gradlew.bat`（早期搜索时误以为有）。

### 2. 已有 `LineArtProcessorTest` 与 `ArrowE2ETest` 失败属历史遗留

本次通过 `git stash` 验证（stash 后跑 `mvn test -Dtest="LineArtProcessorTest,ArrowE2ETest"` 仍然报同样失败）：

| 测试 | 失败原因（与本次修改无关） |
|---|---|
| `LineArtProcessorTest.emptyPaddleUrlSkipsOcrBranch` | 与 Paddle OCR mock/状态相关 |
| `LineArtProcessorTest.multipleAdjacentLineArtChunksMergeIntoImage` | LineArt 合并行为 |
| `LineArtProcessorTest.nullPaddleUrlStopsBeforeOcr` | 与 Paddle OCR mock/状态相关 |
| `ArrowE2ETest.arrowOneHeadRecoveredFromPdfBoxFill` | `NoSuchFile D:\Code\JavaCode\opendataloader-pdf-parse\docs\pdf\202302281677505819604328-84(流程图).pdf`（环境差异：脚本运行目录） |
| `ArrowE2ETest.arrowOn83StillRecognized` | `NoSuchFile D:\Code\JavaCode\opendataloader-pdf-parse\docs\pdf\202302281677505819604328-83(流程图).pdf`（同上） |

未来再跑这两组测试时请先确认这些失败是否被本次改动引入——通过 `git stash` 快速验证。

### 3. PowerShell 执行 Maven 时的 INFO 日志误判

执行 `mvn test ... 2>&1 | Select-Object -Last N` 时，Java `Logger` 输出的中文 INFO 行被 PowerShell 当作 stderr 错误处理，导致最终 `Exit code: 1`，但 Maven 实际 `BUILD SUCCESS`。判定结果以 `[INFO] BUILD SUCCESS` 为准，不要被 exit code 误导。

### 4. 仓库根目录已有 `DebugSample1.java` 被修改（非本次改动）

`git status` 显示该文件也在 modified 列表中，但属于先前调试任务的遗留修改（与柱状图识别 `2026-08-14-WIND渐变细柱状图识别为bar_chart修复.md` 相关），不在本次提交范围。

## 相关文件（Relevant Files）

- `java/opendataloader-pdf-core/src/main/java/org/opendataloader/pdf/processors/BarChartProcessor.java`：本次主修改（重写 + 新常量 + 私有 helper）。
- `java/opendataloader-pdf-core/src/test/java/org/opendataloader/pdf/processors/BarChartProcessorTest.java`：测试更新与新增。
- `java/opendataloader-pdf-core/src/main/java/org/opendataloader/pdf/processors/FlowchartProcessor.java`：本次实现参考的范式。
- `java/opendataloader-pdf-core/src/test/java/org/opendataloader/pdf/processors/FlowchartProcessorTest.java`：参考测试风格（`mergesSubsequentGroupIntersectingScreenshotBox`、`skipsAbsorbedGroupAndStillProcessesRemainingGroups` 等）。
- `java/opendataloader-pdf-core/src/main/java/org/opendataloader/pdf/processors/BoundingBoxGroupUtils.java`：复用 `unionShapeBoundingBoxes`、`containsBarChart`；本次未修改（`isVerticallyMostlyInside` 现已 unused，保留以便后续清理）。
- `java/opendataloader-pdf-core/src/test/java/org/opendataloader/pdf/processors/CapturingImagesUtils.java`：测试 fixtures，记录 `saveImageChunk` 调用。
- `java/opendataloader-pdf-core/src/main/java/org/opendataloader/pdf/processors/DocumentProcessor.java`：调用方，零改动（BarChartProcessor 仍在 FlowchartProcessor 之前调用）。
- `docs/memory/2026-08-13-FlowchartProcessor流程图合并逻辑优化.md`：关键经验来源（截图框不在合并后重复加 margin）。