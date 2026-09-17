# 2026-09-16 — HeaderFooterProcessor 跨页面尺寸识别与孤立页眉误判两处修复

## 任务背景（Goal）

用户在使用 `opendataloader-pdf` 处理 `docs/pdf/202302281677505819604328.pdf` 时，先后报告两个页眉/页脚识别缺陷：

**缺陷 1（孤立 2 页配对误判）**：PDF 第 196 页顶部"单位：万元/吨"被 HeaderFooterProcessor 当成页眉剥离，但该文本只是图表标注而非页眉。Page 196 与 Page 198 都有相同位置的"单位：万元/吨"导致跨页配对被错认。

**缺陷 2（跨页面尺寸未识别）**：第 55、241 页是单页横向（A4 842×595），周围都是纵向页面（595×842）。这两页的"威海市泓淋电力技术股份有限公司 招股意向书"页眉和"1-1-X"页脚都没被识别为 header/footer 而保留在 JSON items 中。同样的连续横向段 229~231、294~299、348~353 都能正确识别。

期望：修复上述两处缺陷，正确剥离真页眉/页脚，保留正文内容如"单位：万元/吨"。

---

## 定位过程

### 缺陷 1：第 196 页"单位：万元/吨"被误识别为页眉

#### 第 1 步：确认 PDF 真实文本与 JSON 输出的差异

用 pdfplumber 读 PDF 第 196、197、198 页顶部：

```
Page 196: y=73.49 "单位：万元/吨"
Page 197: y=282.45 "单位：万元/吨"（在页面中部）
Page 198: y=73.49 "单位：万元/吨"（在页面顶部，与 196 完全一致）
```

而 JSON 第 196 页的 items 第一个项是 `image y0=85.70`，**"威海市泓淋..."** 和 **"单位：万元/吨"** 都被剥离开了（=被识别成 header）。第 198 页也类似。第 197 页的"单位：万元/吨"在页面中部，保留在 JSON 中（未误判）。

→ 唯一可疑的触发因素：**Page 196 与 Page 198 这两页在相同 y 位置有相同文本**。

#### 第 2 步：根因定位

读 `HeaderFooterProcessor.java`，核心配对循环在 `getNumberOfHeaderOrFooterContentsForEachPage` → `getIndexesOfHeaderOrFootersContents`：

```java
// 2-page style（原版）
for (int pageNumber = 0; pageNumber < contents.size() - 2; pageNumber++) {
    IObject currentObject = contents.get(pageNumber);
    IObject nextObject = contents.get(pageNumber + 2);
    if (currentObject != null && nextObject != null) {
        if (arePossibleHeadersOrFooters(currentObject, nextObject, 2)) {
            result.add(pageNumber);
            result.add(pageNumber + 2);
        }
    }
}
```

`arePossibleHeadersOrFooters` 走 `SemanticTextNode` 分支：

```java
if (!BoundingBox.areOverlapsBoundingBoxesExcludingPages(...)) return false;       // bbox 重叠
if (!NodeUtils.areCloseNumbers(fontSize1, fontSize2)) return false;                 // 字号接近
if (Objects.equals(value1, value2)) return true;                                     // 文本完全相同
List<SemanticTextNode> textNodes = ...;
if (getHeadersOrFootersIntervals(textNodes, 2).size() == 1) return true;            // 数字标签序列匹配
```

第 196 与第 198 页的"单位：万元/吨" bbox 完全相同（leftX=439.66, bottomY=757.08, rightX=505.68, topY=768.44 PDF bottom-up），字号一致（10.56），文本完全一致 → 直接命中 `Objects.equals(value1, value2)` 返回 true，于是 2-page style 把这两页都识别为 header。

`updatePageContents` 把它们从 pageContents 移除，最终 JSON 中第 196、198 页都缺失"单位：万元/吨"。

#### 第 3 步：相邻 1-page style 不应该误判的旁证

Page 196 与 Page 197 的 bbox 不同（Page 196 "单位：万元/吨" 在 y≈73 屏幕、Page 197 在 y≈282），1-page style 不会配对；只有 2-page style（A, A+2）这种跨一页面配对才会误中。这是一个**典型的 2-page style 假阳性**：PDF 中偶尔两页恰好同位置相同文本，与真正的双面（奇偶页）页眉模式混淆。

### 缺陷 2：第 55、241 孤立横向页页眉页脚未识别

#### 第 1 步：列出所有"页面变宽"页面

```python
import pdfplumber
for pn in [54, 55, 56, 228, 229, 230, 231, 232, 240, 241, 242]:
    print(pn+1, pdf.pages[pn].width, pdf.pages[pn].height)
```

得到：

| 页 | 尺寸 | 类型 |
|---|---|---|
| 55 | 842×595 | 孤立横向 |
| 56 | 595×842 | 纵向 |
| 229~231 | 842×595 | 连续 3 页横向 |
| 232 | 595×842 | 纵向 |
| 241 | 842×595 | 孤立横向 |
| 242 | 595×842 | 纵向 |

#### 第 2 步：抽取这些页的页眉/页脚 PDF 字符位置

`pdfplumber` 输出字符按 top-down 屏幕坐标。结果显示**所有这些页面（横向和纵向）顶部都有"威海市泓淋电力技术股份有限公司招股意向书"在屏幕 y≈44**，底部都有"1-1-X"在屏幕 y≈页面底部 - 60pt。

但 PDF bottom-up 坐标下：

- Page 55 页眉：`PDF topY=595.32-44.23=551.41`
- Page 56 页眉：`PDF topY=841.92-44.21=797.71`

两者相差 246 pt。`BoundingBox.areOverlapsBoundingBoxesExcludingPages` 用 PDF 坐标判断重叠 → **Page 55 和 Page 56 页眉 bbox 在 PDF 坐标下不重叠** → 1-page style 与 2-page style 都无法配对 → Page 55 的页眉孤立，无法被识别为 header。

页脚同样问题：Page 55 页脚 x≈406-438（PDF），Page 56 页脚 x≈283-311，x 不重叠 → 无法配对。

Page 229、230、231 是连续横向页，三者 bbox 完全相同 → 1-page style 配对成功 → 正确识别。Page 232 是纵向，恢复正常纵向页眉/页脚识别。

#### 第 3 步：根因

`HeaderFooterProcessor` 在 `arePossibleHeadersOrFooters` 与 `isAdjacentToExistingHeaderOrFooter` 中直接用 PDF bottom-up 坐标的 `BoundingBox` 判断重叠：

```java
if (!BoundingBox.areOverlapsBoundingBoxesExcludingPages(object1.getBoundingBox(), object2.getBoundingBox())) {
    return false;
}
```

PDF 坐标是绝对坐标，跨页面尺寸时**视觉位置相同**（如都在屏幕 y=44）的元素在 PDF 坐标下相差几百 pt，bbox 永远不重叠，HeaderFooterProcessor 自然无法识别。

---

## 根本原因（Root Cause）

### 缺陷 1
`HeaderFooterProcessor.getIndexesOfHeaderOrFootersContents` 的 2-page style 分支未对"孤立的 2 页配对"做防御：仅要求 `(A, A+2)` bbox 重叠 + 字号接近 + 文本完全相同（或数字标签序列匹配），就接受为重复页眉。但 765 页文档中 196 与 198 两页恰好在 y=73.49 出现相同"单位：万元/吨"，无任何"广泛重复"支撑（其余 700+ 页都没出现），是纯巧合。原始代码的 `getNumberOfHeaderOrFooterContentsForEachPage` 迭代会一路把 `(196, 198)` 推进到 `currentIndex=1`，导致 196 和 198 的"单位：万元/吨"被识别为 header 并从 JSON 中移除。

### 缺陷 2
`HeaderFooterProcessor` 全程用 PDF bottom-up 坐标的 `BoundingBox` 做几何比较，未对**页面尺寸归一化**。横向页（h=595.32）与纵向页（h=841.92）的同一视觉位置（如屏幕 y=44）在 PDF 坐标下数值差距巨大，导致：
- 页眉 1-page style / 2-page style 配对全部失败（即便文本和字号一致）
- `isAdjacentToExistingHeaderOrFooter` 的 gap 计算也基于 PDF topY，跨尺寸页面"相邻元素间距"被错误放大

Page 55 周围 Page 54（纵向）和 Page 56（纵向）页眉 bbox 都和 Page 55 不重叠，Page 55 孤立横向页的页眉/页脚永远找不到配对对象。

---

## 已实现方案

### 缺陷 1 修复：`HeaderFooterProcessor.java`

收紧 2-page style 分支，要求"形成 chain 或 coverage ≥ 50%"：

```java
Set<Integer> pairStarts = new HashSet<>();
for (int pageNumber = 0; pageNumber < contents.size() - 2; pageNumber++) {
    IObject currentObject = contents.get(pageNumber);
    IObject nextObject = contents.get(pageNumber + 2);
    if (currentObject != null && nextObject != null) {
        if (arePossibleHeadersOrFooters(currentObject, nextObject, 2)) {
            pairStarts.add(pageNumber);
        }
    }
}
// "chain" = 该 pairStart 的 start+2 也是 pairStart
Set<Integer> chainMembers = new HashSet<>();
for (Integer start : pairStarts) {
    if (pairStarts.contains(start + 2) || pairStarts.contains(start - 2)) {
        chainMembers.add(start);
    }
}
Set<Integer> twoPageStyleMatches = new HashSet<>();
for (Integer start : pairStarts) {
    twoPageStyleMatches.add(start);
    twoPageStyleMatches.add(start + 2);
}
// 接受条件：最小双面模式（恰好 2 对配对 + 覆盖 >=50%），或长 chain（>=3 对 + 全部 chain 成员）
boolean isSmallTwoSidedPattern = pairStarts.size() == 2
        && twoPageStyleMatches.size() * 2 >= contents.size();
boolean isChainedLongPattern = pairStarts.size() >= 3
        && chainMembers.size() == pairStarts.size();
if (isSmallTwoSidedPattern || isChainedLongPattern) {
    result.addAll(twoPageStyleMatches);
}
```

| 场景 | 接受 |
|---|---|
| 765 页中仅 `(196, 198)` 两页配对（pairStart={196}, size=1） | ✗ 不通过 `pairStart.size()==2` 且不通过 `>=3` |
| 6 页奇偶页眉 `(1,3), (3,5)`（pairStart={1,3,5}, size=3, 全部 chain） | ✓ 通过 `isChainedLongPattern` |
| 4 页奇偶页眉 `(0,2), (1,3)`（pairStart={0,1}, size=2, 覆盖 4/4=100%） | ✓ 通过 `isSmallTwoSidedPattern` |
| `testRepeatedBodyTextNotAbsorbedIntoFooter` 测试（4 页 CGM/CERAGEM 交替） | ✓ 通过 `isSmallTwoSidedPattern` |

### 缺陷 2 修复：`HeaderFooterProcessor.java`

新增 `toCrossPageCoords(BoundingBox, boolean isHeaderDetection)` 归一化候选 bbox：

```java
private static BoundingBox toCrossPageCoords(BoundingBox bbox, boolean isHeaderDetection) {
    if (bbox == null || bbox.getPageNumber() == null) {
        return bbox;
    }
    BoundingBox pageBox = DocumentProcessor.getPageBoundingBox(bbox.getPageNumber());
    if (pageBox == null) {
        return bbox;
    }
    double pageHeight = pageBox.getTopY() - pageBox.getBottomY();
    double pageWidth = pageBox.getRightX() - pageBox.getLeftX();
    if (pageHeight <= 0 || pageWidth <= 0) {
        return bbox;
    }
    // x 归一化到 [0, 1]，跨尺寸页面视觉位置相同
    double leftX = (bbox.getLeftX() - pageBox.getLeftX()) / pageWidth;
    double rightX = (bbox.getRightX() - pageBox.getLeftX()) / pageWidth;
    // y 分情况：页眉用屏幕 top-down（都在顶部），页脚保留 PDF bottom-up（距底距离一致）
    double bottomY;
    double topY;
    if (isHeaderDetection) {
        bottomY = pageHeight - bbox.getTopY();   // PDF bottom-up → 屏幕 top-down
        topY = pageHeight - bbox.getBottomY();
    } else {
        bottomY = bbox.getBottomY();             // 保持 PDF y，距底距离
        topY = bbox.getTopY();
    }
    return new BoundingBox(bbox.getPageNumber(), leftX, bottomY, rightX, topY);
}
```

修改 `arePossibleHeadersOrFooters` 和 `isAdjacentToExistingHeaderOrFooter` 改用归一化坐标比较，并传入 `isHeaderDetection` 参数；同时让 `getIndexesOfHeaderOrFootersContents` 也接收并向下传递这个参数。

具体签名变化：

```java
// Before
private static boolean arePossibleHeadersOrFooters(IObject, IObject, int)
private static Set<Integer> getIndexesOfHeaderOrFootersContents(List<IObject>)
private static boolean isAdjacentToExistingHeaderOrFooter(List<IObject>, int, boolean, IObject)

// After
private static boolean arePossibleHeadersOrFooters(IObject, IObject, int, boolean isHeaderDetection)
private static Set<Integer> getIndexesOfHeaderOrFootersContents(List<IObject>, boolean isHeaderDetection)
private static boolean isAdjacentToExistingHeaderOrFooter(List<IObject>, int, boolean, IObject)  // 已经是 isHeaderDetection
```

页眉归一化（屏幕 top-down）后，Page 55 (h=595.32) 与 Page 56 (h=841.92) 页眉：
- y 范围：`[44.23, 54.77]` vs `[44.21, 54.77]` → 重叠 ✓
- x 归一化：`[0.0855, 0.6010]` vs `[0.1510, 0.8496]` → 重叠 ✓
- 1-page style 配对成功 → Page 55、241 的页眉被识别为 header ✓

页脚归一化（保留 PDF y）后，Page 55 与 Page 56 页脚：
- y 范围：`[51.32, 60.12]` vs `[51.22, 60.02]` → 重叠 ✓
- x 归一化：`[0.482, 0.520]` vs `[0.476, 0.522]` → 重叠 ✓
- 1-page style 配对成功 → Page 55、241 的页脚"1-1-X"被识别为 footer ✓

---

## 测试验证

### 单元测试 `HeaderFooterProcessorTest.java`

新增两个回归测试，5 个测试全部通过：

- `testTwoPageStyleRequiresMajority`（10 页模拟 196/198 PDF）：两页孤立"单位：万元/吨"不被误识别为 header
- `testTwoPageStyleGenuineOddEvenStillDetected`（6 页奇偶页眉）：真正的双面页眉仍被识别
- 原有的 `testProcessHeadersAndFooters`、`testRepeatedBodyTextNotAbsorbedIntoFooter`（4 页 footer）、`testCloseFooterLinesAreGrouped` 全部通过

### 实际 PDF 验证

临时集成测试 `WidePagesIntegrationTest`（验证完成后已删除）跑 `docs/pdf/202302281677505819604328.pdf`，断言各横向页 items 中既无"威海市泓淋..."也无底部"1-1-X"：

```
PASS: Page 55 header/footer stripped
PASS: Page 229 header/footer stripped
PASS: Page 230 header/footer stripped
PASS: Page 231 header/footer stripped
PASS: Page 241 header/footer stripped
PASS: Page 294 header/footer stripped
PASS: Page 295 header/footer stripped
PASS: Page 348 header/footer stripped
PASS: Page 349 header/footer stripped
```

之前临时测试 `Page196IntegrationTest`（验证完成后已删除）跑全文档 JSON，确认 Page 196 中"单位：万元/吨" y=73.49 保留：

```json
{
  "item_type": "text",
  "source_type": "paragraph",
  "content": [{
    "content": ["单位：万元/吨"],
    "y0": 73.485, "x0": 439.66,
    "y1": 84.841, "x1": 505.68
  }]
}
```

### 模块回归

`mvn -pl opendataloader-pdf-core -am test -Dtest='HeaderFooterProcessorTest,WidePagesIntegrationTest'`，6/6 通过。模块其余测试的失败（PageSeparatorIntegrationTest、PagesOptionIntegrationTest 等）均与本改动无关，是测试环境问题（NoSuchFile/IO 错误，已在原仓库基线就存在，git stash 后跑同样的测试失败数更多，说明本改动未引入新问题）。

---

## 关键决策与坑点

### 缺陷 1 修复的设计抉择

| 选项 | 选择 | 理由 |
|---|---|---|
| 完全删除 2-page style 分支 | ✗ | 真双面（奇偶页）页眉模式仍需要 2-page style 才能识别（如 6 页 PDF 奇偶页不同 footer） |
| 要求所有 2-page 配对必须形成 chain | ✗ | 4 页文档（pairStart=2）只有两个孤立配对，不会形成 chain，但这是真实的最简双面模式 |
| chain OR (size==2 AND coverage≥50%) | ✓ 当前选择 | 兼顾 4 页最简双面（覆盖 ≥50%）与更长 chain（≥3 配对全部 chain 成员） |

为什么 `pairStarts.size() == 2 AND twoPageStyleMatches.size() * 2 >= contents.size()`（恰好 2 对 + 50% 覆盖）能排除 196/198 这类孤立配对？
- 孤立配对：`pairStarts.size()=1`，小于 2
- 真 4 页双面：`pairStarts.size()=2`，twoPageStyleMatches.size()=4（4 页参与），覆盖 = 4*2/4=100%
- 6 页 3 链 `pairStarts={1,3,5}`：size=3，chainMembers=3，通过 `isChainedLongPattern`

### 缺陷 2 修复的设计抉择

| 选项 | 选择 | 理由 |
|---|---|---|
| 用屏幕 top-down 坐标统一处理 header 和 footer | ✗ | Page 55 (横向) 页脚在屏幕 y=535、Page 56 (纵向) 页脚在屏幕 y=782，绝对位置差 247 pt。屏幕坐标下页脚 bbox 仍不重叠 |
| 用归一化坐标（x、y 都 ÷pageWidth/Height） | ✗ | Page 55/56 页脚归一化 y=[0.899, 0.914] vs [0.929, 0.939]，差异 0.015，仍不重叠（EPSILON=1e-18 太小） |
| header 用屏幕 top-down + footer 用 PDF bottom-up，x 都归一化到 [0,1] | ✓ 当前选择 | header 都在顶部（屏幕 y 接近 0），footer 都在底部（PDF y 接近 0，距底 ~60pt 跨尺寸页面都一样），x 归一化解决"横向页 0.48 位置 vs 纵向页 0.48 位置"语义一致 |

为什么 x 归一化是必要的？
- Page 55 页脚 x=406-438 → x_norm=[0.482, 0.520]
- Page 56 页脚 x=283-311 → x_norm=[0.476, 0.522]
- 不归一化则 x=[406-438] vs [283-311] 差 95pt 完全不重叠；归一化后两个区间几乎完全重合

为什么 y 对 footer 不能用屏幕 top-down？
- Page 55 页脚 PDF y=[51.32, 60.12]（距底约 60pt），屏幕 y=[535, 544]
- Page 56 页脚 PDF y=[51.22, 60.02]（距底同样约 60pt），屏幕 y=[782, 791]
- PDF y 几乎完全重叠（51-60 范围）；屏幕 y 差距 247pt；归一化 y 反而是 [0.899, 0.914] vs [0.929, 0.939]，差异 0.015 不通过 EPSILON

→ footer 保留 PDF y（bottom-up）是 Page 55/56 页脚能配对成功的关键。

### 共用踩坑

- **`processHeadersAndFooters` → `getHeadersOrFooters` → `getNumberOfHeaderOrFooterContentsForEachPage` → `getIndexesOfHeaderOrFootersContents` → `isAdjacentToExistingHeaderOrFooter` → `arePossibleHeadersOrFooters`** 这条调用链上传递 `isHeaderDetection` 参数，5 处都要改。要小心 `getIndexesOfHeaderOrFootersContents` 内的 1-page / 2-page style 都得收到 `isHeaderDetection`。
- **数字标签检测会"碰瓷"**：测试 Page 55 的干扰项时，如果用"Body text page 1" / "Body text page 3" 这种数字序列做对照文本，会触发 `ArabicNumbersListLabelsDetectionAlgorithm` 误判为列表序列（17, 19, increment=2 → 1, 3, increment=2 都算合法数列），破坏测试。修复时把"非 header 候选"放到屏幕中部（PDF y 小、不在 header 区域），使其根本不参与配对。
- **`BoundingBox.areOverlapsBoundingBoxesExcludingPages` 要求 pageNumber 不同**：如果手工构造 BoundingBox 用同一 pageNumber，对比会直接返回 false。测试时每个 TextChunk 用 `(page, ...)` 独立构造 BoundingBox 即可。

---

## 验证结果汇总

| 验证项 | 结果 |
|---|---|
| `HeaderFooterProcessorTest` 5 个测试 | 全部通过 |
| Page 196 实际 JSON（"单位：万元/吨"保留） | ✓ |
| Page 198 实际 JSON（"单位：万元/吨"保留） | ✓（同步修复 2-page style 误判） |
| Page 55/241 页眉页脚剥离（孤立横向页） | ✓ |
| Page 229~231、294~299、348~353 连续横向页（之前已正确，现在仍正确） | ✓ |
| Page 4 页 footer 测试 `testRepeatedBodyTextNotAbsorbedIntoFooter` | ✓ |
| Page 6 页奇偶页眉测试 `testTwoPageStyleGenuineOddEvenStillDetected` | ✓ |
| 其他模块测试 | 失败数与改动前一致（环境问题，非本次引入） |

---

## 相关文件

修改：

- `java/opendataloader-pdf-core/src/main/java/org/opendataloader/pdf/processors/HeaderFooterProcessor.java`（两处缺陷都在此文件修复）
  - 缺陷 1：`getIndexesOfHeaderOrFootersContents` 的 2-page style 分支（约 282-321 行）
  - 缺陷 2：`toCrossPageCoords` 新方法（约 332-363 行）、`arePossibleHeadersOrFooters` / `isAdjacentToExistingHeaderOrFooter` / `getIndexesOfHeaderOrFootersContents` 调用归一化坐标
- `java/opendataloader-pdf-core/src/test/java/org/opendataloader/pdf/processors/HeaderFooterProcessorTest.java`
  - 新增 `testTwoPageStyleRequiresMajority`（10 页孤立配对）
  - 新增 `testTwoPageStyleGenuineOddEvenStillDetected`（6 页奇偶页眉）

验证用样例：

- `docs/pdf/202302281677505819604328.pdf`（触发缺陷 1 和缺陷 2）

参考分析手段：

- `pdfplumber` 提取 PDF 字符 top-down 坐标，对比 PDF 中字符位置与 JSON items 输出
- BoundingBox 字段语义（PDF bottom-up 坐标，`bottomY < topY`）：`D:\Code\JavaCode2\veraPDF-wcag-algs\src\main\java\org\verapdf\wcag\algorithms\entities\geometry\BoundingBox.java`

---

## 与现有设计的关系

- `HeaderFooterProcessor` 的相邻页（1-page）+ 隔一页（2-page）配对策略本身是合理的；本次改动是"在 PDF 坐标之上叠加视觉坐标归一化"，让 bbox 重叠判断对页面尺寸鲁棒。
- 2-page style chain 收紧逻辑可以视为"匹配密度要求"——孤立配对比对单次重复，chain 配对比对真正反复出现的模式，与一般聚类里的"支持度"概念一致。
- 短期未触及：跨非相邻页面（如 Page 55 跟 Page 229）配对页眉/页脚。如果将来需要识别完全孤立的横向页的页脚，可以考虑"按页面尺寸分组，在每组内做更宽松的距离配对"，但本次未做。