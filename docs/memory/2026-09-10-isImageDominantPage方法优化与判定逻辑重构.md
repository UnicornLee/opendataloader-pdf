# opendataloader-pdf 任务记忆 — 2026-09-10（`DocumentProcessor#isImageDominantPage`：图片主导页判定逻辑重构）

## 目标（Goal）

优化 `org.opendataloader.pdf.processors.DocumentProcessor#isImageDominantPage` 的判定逻辑，使其更准确地识别"图片主导页"（如整页扫描图），并在非图片主导页时清理掉超大背景图。

用户给出的 4 步需求：

1. 若存在单个 `ImageChunk` 的面积超过页面 80%，将其**单独收集**起来（后续用于判定与移除）。
2. 计算**剩余元素**占页面的比例，以及**剩余图片**的面积占页面的比例。
3. 返回 `true` 的条件：
   - 剩余元素占比 **低于 40%** 且 **步骤 1 收集到了大图**；**或**
   - 剩余图片的面积占比 **超过 80%**；
   否则返回 `false`。
4. 若返回 `false`，把面积超过页面 **95%** 的 `ImageChunk` 从 `pageContents` 中**移除**。

后续用户两次修正了"剩余元素占比"的度量口径：

- 修正 A：剩余元素占比**不按面积**，改为**高度占比**。
- 修正 B：剩余元素高度**不是各元素高度求和**，而是取所有剩余元素 `BoundingBox` 的**最大 `topY` 减去最小 `bottomY`**（垂直跨度 / 整体覆盖高度）。

---

## 背景链路（改动前）

`isImageDominantPage` 是"是否对整页走 OCR 兜底"的判据之一，调用链：

```
DocumentProcessor.processDocument(...)
  └─ 主循环（per page）：
       List<IObject> pageContents = contents.get(pageNumber);
       if (shouldUseOcrFallback(pageContents, pageWidth, pageHeight) || replacementRatio >= 0.1)
            → fallbackOcrPage(...) 做整页 OCR，返回 TextChunk 列表替换原 contents
  └─ shouldUseOcrFallback(...)
       ├─ isImageDominantPage(pageContents, pageWidth, pageHeight)   ← 本次改动点
       └─ countTextAndGarbage(...) 计算乱码字符比例（cid:/� 等）
```

- `pageContents` 来自 `ContentFilterProcessor.getFilteredContents(...)`，其返回值是 `new ArrayList<>(...)`（经 `removeNullObjectsFromList` 再包一层 `ArrayList`），**可安全 `removeIf` 原地修改**。
- 调用点唯一：`DocumentProcessor.java:1359`，在 `shouldUseOcrFallback` 内。

---

## 完整定位过程（从问题到根因）

### 阶段 1：定位"图片主导页"判定入口

全仓搜索 `isImageDominantPage`，仅 1 处定义、1 处调用（均在 `DocumentProcessor.java`）。改动范围锁定在该私有方法内，不影响其它调用方。

### 阶段 2：阅读原实现，确认其判定维度单一

原实现（简化）：

```java
private static boolean isImageDominantPage(List<IObject> pageContents, double pageWidth, double pageHeight) {
    if (pageWidth <= 0 || pageHeight <= 0) return false;
    double pageArea = pageWidth * pageHeight;
    double imageArea = 0.0;
    for (IObject content : pageContents) {
        if (content instanceof ImageChunk) {
            BoundingBox bbox = content.getBoundingBox();
            if (bbox != null && !bbox.isEmpty()) {
                imageArea += bbox.getWidth() * bbox.getHeight();
            }
        }
    }
    return imageArea / pageArea > 0.8;
}
```

**问题**：只用"页面内所有图片面积之和 / 页面面积 > 0.8"这一个阈值判定。这会带来两类误判：

- **误判为图片页（假阳性）**：一页上多张中等图片（如插图、图表、logo）面积累加起来超过 80%，但正文文字很多、明显不是扫描页 → 不该走 OCR 却被 OCR 覆盖，丢失可提取文本。
- **无法识别"单张大图 + 少量文字"的扫描页特征**：原逻辑把所有图片面积求和，不区分"一张铺满整页的扫描图"与"一堆零散小图"。而真实扫描件往往是**一张接近整页的大图**，文字极少（甚至只有页眉页脚）。
- **超大背景图污染下游**：即便判定为非图片页，一张覆盖 95%+ 页面的装饰/背景 `ImageChunk` 仍会留在 `pageContents` 里参与后续表格/文本/JSON 写出，产生无意义的大图块。

### 阶段 3：根因 —— 判定维度单一，且未对大图做隔离处理

根因不是"阈值 0.8 取错了"，而是**缺少对"单张大图"与"剩余内容"的分层处理**：

- 没有把"单张接近整页的大图"从"零散图片/正文"中剥离出来单独判断；
- 没有分别刻画"去掉大图后，剩下内容还占多少"（剩余元素占比）与"剩下图片还占多少"（剩余图片占比）；
- 缺少"非图片页时清理超大背景图"的兜底动作。

因此优化方向是**分层 + 多维判据**：先隔离大图（>80%），再用"剩余元素占比（阈值 40%）"和"剩余图片占比（阈值 80%）"两个维度组合判定，并在判否时移除超大背景图（>95%）。

### 阶段 4：第一次度量口径 —— 剩余元素按"面积占比"

按用户最初的 4 步需求实现第一版：`remainingElementsRatio = Σ(剩余元素面积) / pageArea`。

### 阶段 5：用户修正 A —— 剩余元素改为"高度占比"

用户指出：剩余元素**不按面积占比**，改为**高度占比**。即 `remainingElementsRatio = Σ(剩余元素 bbox 高度) / pageHeight`。

理由：判定"去掉大图后还剩多少内容"时，垂直方向占用的高度比"面积"更贴近"版面被内容铺满的程度"——一张高而窄的图或一段竖向排列的文字，用高度更能反映"内容纵向铺展比例"。

### 阶段 6：用户修正 B —— 高度不应"求和"而应取"垂直跨度"

用户进一步指出：剩余元素高度**不是**各元素高度求和，而是取所有剩余元素 `BoundingBox` 的**最大 `topY` 减最小 `bottomY`**（即剩余元素整体覆盖的垂直跨度）。

理由：求和会把多个元素的高度重复累加（例如两行文字各高 10pt，求和得 20pt，但实际只占了 10pt 的纵向空间），虚高比例。取 `maxTopY - minBottomY` 才代表"剩余内容在页面纵向上实际铺开的范围"，与"占页面高度多少"的语义一致。

最终口径落成：`remainingElementsHeight = max(0, maxTopY - minBottomY)`，`remainingElementsRatio = remainingElementsHeight / pageHeight`。

---

## 已实现方案（最终版，已编译通过）

### 改动文件（唯一）

`java/opendataloader-pdf-core/src/main/java/org/opendataloader/pdf/processors/DocumentProcessor.java`

`isImageDominantPage`（约 1375–1425 行）：

```java
private static boolean isImageDominantPage(List<IObject> pageContents, double pageWidth, double pageHeight) {
    if (pageWidth <= 0 || pageHeight <= 0) {
        return false;
    }
    double pageArea = pageWidth * pageHeight;
    double bigImageThreshold = 0.8 * pageArea;
    double removeThreshold = 0.95 * pageArea;

    // Step 1: collect ImageChunks whose area exceeds 80% of the page separately.
    List<ImageChunk> bigImages = new ArrayList<>();
    double maxTopY = Double.NEGATIVE_INFINITY;
    double minBottomY = Double.POSITIVE_INFINITY;
    double remainingImagesArea = 0.0;
    for (IObject content : pageContents) {
        BoundingBox bbox = content.getBoundingBox();
        if (bbox == null || bbox.isEmpty()) {
            continue;
        }
        double area = bbox.getWidth() * bbox.getHeight();
        if (content instanceof ImageChunk) {
            if (area > bigImageThreshold) {
                bigImages.add((ImageChunk) content);
                continue;
            }
            remainingImagesArea += area;
        }
        maxTopY = Math.max(maxTopY, bbox.getTopY());
        minBottomY = Math.min(minBottomY, bbox.getBottomY());
    }

    // Step 2: ratio of remaining elements (by vertical span: max topY - min bottomY) and
    // remaining images (by area) relative to the page.
    double remainingElementsHeight = (maxTopY == Double.NEGATIVE_INFINITY || minBottomY == Double.POSITIVE_INFINITY)
            ? 0.0 : Math.max(0.0, maxTopY - minBottomY);
    double remainingElementsRatio = remainingElementsHeight / pageHeight;
    double remainingImagesRatio = remainingImagesArea / pageArea;

    // Step 3: a page is image-dominant when, excluding a full-page scan image,
    // almost nothing else covers the page (<= 40%), or when the remaining images
    // alone cover more than 80% of the page.
    boolean imageDominant = (remainingElementsRatio < 0.4 && !bigImages.isEmpty())
            || remainingImagesRatio > 0.8;

    // Step 4: when not image-dominant, drop ImageChunks covering more than 95%
    // of the page so they are not treated as extractable content downstream.
    if (!imageDominant) {
        pageContents.removeIf(content ->
                content instanceof ImageChunk && getContentArea(content) > removeThreshold);
    }
    return imageDominant;
}

private static double getContentArea(IObject content) {
    BoundingBox bbox = content.getBoundingBox();
    if (bbox == null || bbox.isEmpty()) {
        return 0.0;
    }
    return bbox.getWidth() * bbox.getHeight();
}
```

要点说明：

- **大图隔离**：面积 `> 0.8 * pageArea` 的 `ImageChunk` 进入 `bigImages`，并在循环内 `continue`，**既不计入 `remainingImagesArea`，也不参与 `maxTopY/minBottomY` 统计**（即不污染"剩余"度量）。
- **两个维度分治**：`remainingElementsRatio` 用**高度（垂直跨度）**；`remainingImagesRatio` 用**面积**。
- **返回 `true` 的两种情形**：
  1. 存在大图（步骤 1 非空）且剩余元素纵向跨度 < 40% 页面高度（去掉整页扫描图后所剩无几）→ 典型扫描页；
  2. 剩余图片面积仍 > 80% 页面（无单张大图、但零散图片铺满整页）→ 同样判为图片页。
- **副作用移除**：返回 `false` 时，把覆盖 > 95% 页面的 `ImageChunk` 从 `pageContents` 中 `removeIf` 移除（这些通常是装饰/背景大图，不应作为可提取内容）。注意移除阈值（95%）高于收集阈值（80%），所以被移除的一定是"超大背景图"，不会误删已参与判定的普通大图（其是否保留取决于调用方是否走 OCR 替换）。

---

## 验证

- 编译：`cd d:\Code\JavaCode\opendataloader-pdf\java\opendataloader-pdf-core; mvn -o -q -DskipTests compile` → **BUILD SUCCESS（无输出即成功）** ✅
- `read_lints` 对 `DocumentProcessor.java` → 0 告警 ✅
- **未完成**：`mvn test` 多次被环境判定为"耗时较长"而跳过，core 模块完整单测未跑。

---

## 关键决策（Key Decisions）

1. **大图阈值 80% 与移除阈值 95% 分开**：收集用 80%（用于"单张大图"存在性判定），移除用 95%（只清理极端背景图，避免误删普通大图）。两个阈值均从 `pageArea` 派生，便于统一调整。
2. **剩余元素用垂直跨度而非面积/求和**：垂直跨度 = `maxTopY - minBottomY`，避免多元素高度重复累加虚高比例，更贴合"内容纵向铺展占比"语义（采纳用户修正 B）。
3. **`pageContents` 原地修改是安全的**：来源为 `ContentFilterProcessor.getFilteredContents` 返回的 `ArrayList`，`removeIf` 不会抛 `UnsupportedOperationException`；且后续 `countTextAndGarbage` 本就忽略 `ImageChunk`，移除大图不影响文本乱码统计。
4. **`getContentArea` 抽成私有方法**：供 `removeIf` 复用，对 `bbox == null || isEmpty()` 返回 0，防御性处理。
5. **坐标语义**：`BoundingBox` 为 y-up 坐标，`getTopY()` 值大于 `getBottomY()`，故 `maxTopY - minBottomY` 为正；若为负（异常 bbox）用 `Math.max(0.0, ...)` 兜底。

---

## 已知限制 / 后续可做

1. **`remainingElementsRatio` 用"垂直跨度"是全局跨度**，不区分"内容集中在一小块"与"内容分散在上下两端但中间空白"——两者跨度可能相近。若需更精细，可改为"剩余元素在纵向上的投影覆盖长度"（将每个元素高度段投影到 y 轴后求并集长度）。当前按用户明确要求采用 `maxTopY - minBottomY`。
2. **图片面积可做重叠扣减**：当前 `remainingImagesArea` 直接累加各图片面积，若多张图片纵向重叠会高估占比。对扫描/插图场景通常可接受，未做 IOU 扣减。
3. **未补充单元测试**：方法为 `private static`，建议后续补用例：① 单张 90% 大图 + 少量文字 → 返回 true；② 多张小图累计面积 > 80% → 返回 true；③ 大段正文 + 一张 96% 背景图 → 返回 false 且该图被移除；④ 无大图、正文占满 → 返回 false。

---

## 相关文件（Relevant Files）

- `java/opendataloader-pdf-core/src/main/java/org/opendataloader/pdf/processors/DocumentProcessor.java`
  - `isImageDominantPage(List<IObject>, double, double)`（约 1375–1425 行）— **核心改动**
  - `getContentArea(IObject)`（约 1427 行起）— 新增私有辅助
  - `shouldUseOcrFallback(...)`（约 1355 行）— 唯一调用方；`isImageDominantPage` 返回 true 即走 OCR 兜底
  - `fallbackOcrPage(...)`（约 1424 行起）— OCR 兜底实现（判为图片页后的实际动作）
- `java/opendataloader-pdf-core/src/main/java/org/opendataloader/pdf/processors/ContentFilterProcessor.java`
  - `getFilteredContents(...)`（约 53 行）— `pageContents` 来源，返回可修改的 `ArrayList`
- 涉及类型：`ImageChunk`、`BoundingBox`（`getWidth/getHeight/getTopY/getBottomY/isEmpty`）、`IObject`（`getBoundingBox`）

---

## 环境 / 命令备忘

- Maven 本地仓库在 `D:\Maven_Repo`（`D:\Applications\apache-maven-3.9.x\conf\settings.xml` 配置），`mvn` 需带 `-o` 离线；shell 实际为 PowerShell，`cd` 直接跟路径即可。
- 编译验证命令：

```powershell
cd d:\Code\JavaCode\opendataloader-pdf\java\opendataloader-pdf-core
mvn -o -q -DskipTests compile
```

- 调试入口 `org.opendataloader.pdf.DebugSample` 由用户自行维护当前指向的 PDF，本次未改动该文件。
