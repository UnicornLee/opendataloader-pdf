# opendataloader-pdf 任务记忆 — 2026-08-13（FlowchartProcessor 流程图合并逻辑优化）

## 目标（Goal）

优化 `org.opendataloader.pdf.processors.FlowchartProcessor#processFlowchartGroups` 方法：
- 将吸收后续相交 shape group 的 `do-while` 循环从 `isFlowchartCluster(cluster)` 判断**内部**移到判断**之前**。
- 使合并后的整体 cluster 参与流程图判定，避免单个小 group 因自身不满足条件而被漏检。

## 优化前的问题

原代码结构：

```java
Cluster cluster = collectCluster(...);
if (cluster == null) continue;

if (isFlowchartCluster(cluster)) {
    // do-while: 吸收后续相交 group
    ...
    // 截图保存
}
```

问题：只有当前 group 自己先被判定为流程图时，才会去吸收后续相交的 group。如果当前 group 单独不满足 `isFlowchartCluster`（例如 shape 数量、长宽比、组件数刚好差一点点），但它与相邻 group 合并后明显是一个完整流程图，则会被漏掉。

## 定位与重构思路

### 第 1 步：理解现有吸收逻辑

原 `do-while` 循环：
- 以当前 cluster 的 bbox 加水平/垂直 margin 得到 `screenshotBox`
- 遍历后续 group，若其 bbox 与 `screenshotBox` 重叠，则吸收
- 吸收时把后续 group 的 shape 和 collected contents 加入当前 cluster，并标记 `skipped[j] = true`
- 循环直到没有新的 group 被吸入

### 第 2 步：明确前移后的语义

将循环前移到 `isFlowchartCluster` 之前：
- 先收集当前 group 的 cluster
- 无条件地吸收所有后续相交 group，形成 `mergedCluster`
- 对 `mergedCluster` 调用 `isFlowchartCluster`
- 若判定为流程图，则用已经计算好的 `screenshotBox` 截图保存

### 第 3 步：保持截图框行为一致

第一次重构时，我在合并后重新用 `expandHorizontally(mergedBox, ...)` 计算 `screenshotBox`，导致测试 `mergesSubsequentGroupIntersectingScreenshotBox` 失败：

```
expected: <250.0> but was: <255.0>
```

原因是原逻辑中 `screenshotBox` 初始带 margin，吸收 group2 后只是 `union` group2 的真实 bbox，不会再额外扩一次 margin。如果合并后重新计算 margin，会在右侧再增加 5pt（`SCREENSHOT_HORIZONTAL_MARGIN=5`）。

修正：保留原截图框计算方式——
- `screenshotBox` 初始 `expandHorizontally(cluster.boundingBox, ...)`
- 吸收后续 group 时，`screenshotBox.union(laterCluster.boundingBox)`
- 判断流程图时使用不带额外 margin 的 `mergedBox`

这样既能对合并后的真实 bbox 做流程图判断，又能保持原有截图框大小不变。

## 已实现方案

### 改动

**文件：** `java/opendataloader-pdf-core/src/main/java/org/opendataloader/pdf/processors/FlowchartProcessor.java`

优化后 `processFlowchartGroups` 核心逻辑：

```java
Cluster cluster = collectCluster(pageContents, group, pageNumber);
if (cluster == null) {
    continue;
}

// 1. 先吸收后续相交 groups
List<IObject> mergedShapes = new ArrayList<>(group);
List<IObject> mergedContents = new ArrayList<>(cluster.collectedContents);
BoundingBox mergedBox = new BoundingBox(cluster.boundingBox);
BoundingBox screenshotBox = expandHorizontally(mergedBox, SCREENSHOT_HORIZONTAL_MARGIN,
        SCREENSHOT_VERTICAL_TOLERANCE);
boolean expanded;
do {
    expanded = false;
    for (int j = i + 1; j < groupedShapeChunks.size(); j++) {
        if (skipped[j]) continue;
        List<IObject> laterGroup = groupedShapeChunks.get(j);
        if (laterGroup == null || laterGroup.isEmpty()) continue;
        BoundingBox laterBox = BoundingBoxGroupUtils.unionShapeBoundingBoxes(laterGroup, pageNumber);
        if (laterBox == null || !screenshotBox.overlaps(laterBox)) continue;
        Cluster laterCluster = collectCluster(pageContents, laterGroup, pageNumber);
        if (laterCluster != null) {
            screenshotBox.union(laterCluster.boundingBox);
            mergedBox.union(laterCluster.boundingBox);
            mergedContents.addAll(laterCluster.collectedContents);
        }
        mergedShapes.addAll(laterGroup);
        skipped[j] = true;
        expanded = true;
    }
} while (expanded);

// 2. 用合并后的 cluster 判断是否是流程图
Cluster mergedCluster = new Cluster(mergedShapes, mergedContents, mergedBox);
if (isFlowchartCluster(mergedCluster)) {
    LOGGER.log(Level.INFO, "Page {0}: detected flowchart cluster with screenshot bbox {1}",
            new Object[]{pageNumber + 1, screenshotBox});
    pageContents.removeAll(mergedContents);
    pageContents.removeAll(mergedShapes);
    ImageChunk imageChunk = new ImageChunk(screenshotBox);
    imagesUtils.saveImageChunk(imageChunk);
    pageContents.add(imageChunk);
}
```

## 验证结果

| 测试 | 结果 |
|---|---|
| `FlowchartProcessorTest` | ✅ 9/9 通过 |
| `ArrowE2ETest` | ✅ 2/2 通过 |
| `ShapeRecognizerTest` | ✅ 22/22 通过 |

## 关键决策

- 吸收循环无条件前置：即使当前 group 单独不是流程图，也会尝试与相邻 group 合并后再判断。
- `mergedBox` 用于流程图判定，`screenshotBox` 用于截图保存，两者职责分离。
- 截图框保持原行为（初始带 margin，union 后续 bbox 后不再重复扩 margin），避免现有测试断言漂移。

## 潜在影响

- 行为变化：原先不会触发吸收的 group 现在会被吸收。如果当前 group 与后续 group 在 margin 内重叠，但合并后仍不是流程图，则后续 group 会被 `skipped` 且不再单独处理。
- 这一般符合预期：margin 内重叠的 shape groups 通常属于同一图表，不应被拆成独立流程图。
- 若未来出现"两个独立流程图刚好在 margin 内相邻"的误合并，可考虑在合并前加入更严格的语义判断（例如要求有 connector/arrow 跨 group 连接）。

## 相关文件

- `java/opendataloader-pdf-core/src/main/java/org/opendataloader/pdf/processors/FlowchartProcessor.java`：`processFlowchartGroups`、`isFlowchartCluster`、`collectCluster`。
- `java/opendataloader-pdf-core/src/test/java/org/opendataloader/pdf/processors/FlowchartProcessorTest.java`：流程图合并与吸收相关单元测试。
