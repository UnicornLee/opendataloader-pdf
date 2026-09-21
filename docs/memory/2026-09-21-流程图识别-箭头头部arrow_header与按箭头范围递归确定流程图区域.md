# 流程图识别优化：新增 `arrow_header` 并"按箭头范围递归"确定流程图区域

- 日期：2026-09-21
- 涉及仓库/模块：`opendataloader-pdf` / `java/opendataloader-pdf-core`（无 veraPDF 改动）
- 状态：已实现并验证；改动仍在工作区，**未提交**

---

## 1. 需求（用户提出，含澄清）

1. 新增箭头**头部**的识别：在 `ShapeChunk` 中新增 `arrow_header` 类型，**只识别箭头的头部**。
2. 如果存在 `arrow_header` 类型，在流程图中**优先根据每个箭头的范围递归**找出和它有交集的**线或其他图形**，最终确定流程图的范围；**如果两个箭头确定的范围有交集，就把它们的范围合并**。
3. 如果不存在 `arrow_header` 类型，继续按现有流程识别流程图。
4. 重点回归文件：
   - `20260507AN202606291826520711`：106、107、154、160、161
   - `200706271781617948530064564`：7、8
   - `202302281677505819604328`：83、84
5. 可通过 `org.opendataloader.pdf.DebugSample` 或 IntelliJ Debugger 调试。

### 澄清结论（两轮提问）

| 问题 | 用户选择 |
|---|---|
| `arrow_header` 与现有 `arrow` 的关系 | **保留 `arrow` 不变**（仍是"轴∪头"的范围），**额外**产出一个只覆盖头部的 `arrow_header` |
| "有交集"的判定 | **bbox 相交 + 小容差**（沿用 `ADJACENCY_GAP = 2pt`） |
| 递归收集的对象 | **`ShapeChunk` + 原始线条**（`LineChunk` / `LineArtChunk`） |
| 106/107 的期望 | **识别成流程图截图**，两个误判表格随之消失 |
| 箭头范围与"常规表格否决"（`REGULAR_TABLE_AREA_RATIO=0.3`）冲突时 | **箭头范围优先，跳过表格否决**（尺寸/aspect/整页占比/正文保护等仍生效） |

---

## 2. 现状量化（改动前的实测基线）

用临时探针跑完整流程（`OpenDataLoadPDF.processFile`，单页样本）：

| 页 | 改动前输出 |
|---|---|
| 招股书 106 | **无截图**，2 个 `lattice_table`（`T[126.46,261.81,508.39,314.62]`、`T[115.33,350.84,462.05,417.87]`） |
| 招股书 107 | **无截图**，2 个 `lattice_table` |
| 招股书 154 / 160 / 161 | 各 1 张流程图截图 |
| 200706…564 p7 / p8 | 各 2 张截图（两幅独立架构图） |
| 20230228…328 -83 / -84 | 各 1 张截图（另有正常表格） |

### 关键发现：106/107 上"箭头画了，但现有 `arrow` 识别看不到"

`20260507AN202606291826520711-106.pdf` 是一个真流程图（"緊接[編纂]完成前的公司架構"，4 个股东方框 → 本公司 → 5 个子公司方框）。探针证据：

- PDFBox 填充层里确实有一个 **5×4.3pt 的小三角** `[265.53,475.49,270.52,479.81]`（指向 `本公司` 方框的头部），
  它的轴是 x≈268 的两段竖线（`479.08→484.53`、`484.53→498.75`）。
- 但整页只认出 **2 条 `arrow`**（`[267.66,419.20,268.16,442.86]`、`[197.02,429.76,347.86,430.26]`），**且都没有头部**：
  `recognizeConnectorLines` 要求"轴的两端都靠近两个**已识别图形**且轴不被某个图形包住"（`isConnectorLine`），
  这条竖线不满足 → 直接 `continue`，**根本没走到找头那一步**（`findArrowBBox` 只在 `isConnectorLine` 通过后才调用）。
- 而原本的 `groupShapes`（bbox 相交 + y 容差 2pt）在 106 上把图分成两组
  `GROUP#8 [86.88,375.83,462.05,475.89]`、`GROUP#9 [126.46,484.28,508.39,531.89]`，
  两组各自都因"常规表格否决"（表格面积/簇面积 = 0.62 / 0.34 > 0.3）被否 → 一张截图都不出，
  两个误判表格留在输出里。

**结论**：`arrow_header` 必须**独立于现有 `arrow`（`isConnectorLine`）识别**，否则 106/107 上不会出现任何 `arrow_header`，
也就走不到新流程（第 3 条要求会把它送回旧流程）。

---

## 3. 实现

改动 4 个主文件 + 3 个测试文件（`+539 / −27`）。

### 3.1 `ShapeChunk`：新增类型

```java
/** The filled head (triangle) at one end of an arrow, without its shaft. */
public static final String TYPE_ARROW_HEADER = "arrow_header";
```

> `ShapeChunk` **不写入 JSON**（`JsonWriter` 会跳过 `ShapeChunk`），所以新增类型本身不改变 JSON 结构，
> 只影响分组/裁剪决策。

### 3.2 `ShapeRecognizer`

1. **头部识别（独立于 connector 判定）** —— `recognizeConnectorLines`：
   - 对**每一条**"单段 / 共线链"（原判据不变）解析头部；命中且"切片可用"就产出一个 `arrow_header`；
   - `arrow` 的产生条件与范围**完全不变**（原 `findArrowBBox` 拆成 `findArrowhead` + `unionOf`，行为等价）。
2. **`arrowheadOnly(candidate, shaft)`**：把候选裁剪成"**轴端之外**"的那一段。
   很多 PDF（如 200706…564、招股书 154）把整支箭头画成**一个填充多边形**（3×100pt 的"块箭头"），
   候选本身包含轴，只有轴端之外的部分才是头部。
3. **`isUsableArrowhead`**：面积 ≥ `MIN_ARROWHEAD_AREA=4pt²` 且两边 ≥ `MIN_ARROWHEAD_DIMENSION=2pt`。
4. **`filterCandidatesInsideShapes`**：候选被某个**已识别 `rectangle` / `bar_chart` 覆盖 ≥ 90%** 就丢弃
   （`MAX_ARROWHEAD_SHAPE_COVERAGE=0.9`）。这是与既有 `filterShapeCoincidentFills` 不同的判据：
   后者按"面积比"过滤（保留"戳进大方框里的箭头"），而表格**表头填充带**必须按"是否落在实心图形内部"过滤。
5. **`groupShapesByArrowHeaders(shapes, extraItems)`**：新分组算法
   - 每个 `arrow_header` 一个种子区域；
   - **反复**吸收与"当前区域 bbox"相交（容差 2pt）的所有项（`ShapeChunk` + 原始线条）→ 区域 bbox 随之变大 → 继续吸收，
     即用户要求的"递归找出有交集的线或其他图形"；
   - **范围相交的区域合并**（同一张图的多支箭头 → 一个区域）；
   - 页面无 `arrow_header` 时返回空列表（第 3 条要求）。
6. `containsArrowHeader(items)` 辅助方法。
7. 性能：两个候选源的过滤**提到链循环之外只做一次**（头部现在对每条链都要解析，逐链重复过滤会是 O(链 × 候选 × 图形)）。

### 3.3 `FlowchartProcessor`

- 新增 7 参重载 `processFlowchartGroups(..., pageHeight, boolean arrowDriven)`；旧签名委托 `arrowDriven=false`。
- `arrowDriven=true` 时**跳过 `isRegularTable` 否决**（`isFlowchartCluster(cluster, skipTableVeto)`），
  其余保护（`MIN_WIDTH`/`MIN_HEIGHT`/`MAX_ASPECT_RATIO`/`CHECK page frame`/`coversWholePage`/`containsBodyText`）全部保留。
- `collectCluster` 与"吸收后续组"的起始框由 `unionShapeBoundingBoxes` 改成 `unionBoundingBoxes`
  （arrow 驱动的组里含原始线条，必须计入框；对旧组而言两者等价）。
- `Cluster.arrowCount` 把 `arrow_header` 也计为箭头（头部就是箭头的一部分，且 arrow 驱动的组里可能只有头没有轴）。

### 3.4 `DocumentProcessor`

- 新增 `collectRawLineChunks(pageNumber)`：从 `StaticContainers.getDocument().getArtifacts(page)`
  取回 `LineChunk` 与 `LineArtChunk`（含其子线）——**普通 `LineChunk` 在 Loop 2 已被从 `pageContents` 过滤掉**，
  而"头部→轴→节点"的递归恰恰需要它们。
- `arrowDriven = ShapeRecognizer.containsArrowHeader(shapeChunks)`；
  - `BarChartProcessor` / `PieChartProcessor` **仍用旧分组**（`groupShapes`，只含 `ShapeChunk`）；
  - 流程图分组：`arrowDriven` 时用 `groupShapesByArrowHeaders(shapeChunks, rawLines)`，为空则回退旧分组；
  - 把 `arrowDriven` 传给 `FlowchartProcessor`。

---

## 4. 验证

### 4.1 重点回归 9 页（完整流程，前后对比）

| 页 | 改动前 | 改动后 | 评价 |
|---|---|---|---|
| 招股书 106 | `table=2 image=0` | `table=0 image=1`，截图 `I[81.88,260.81,513.39,418.87]` | ✅ 符合期望（误判表格随裁剪消失） |
| 招股书 107 | `table=2 image=0` | `table=0 image=1`，截图 `I[65.63,132.60,529.65,285.41]` | ✅ 同上 |
| 招股书 154 | `image=1 [87.73,93.83,277.08,588.41]` | 完全一致 | ✅ 不变 |
| 招股书 160 | `image=1 …277.61` | `image=1 …283.15`（底边多 5.5pt） | ✅ 改善：把"第三方芯片"虚线框完整纳入 |
| 招股书 161 | `image=1` | 完全一致 | ✅ 不变 |
| 200706…564 p7 | `image=2` | 完全一致 | ✅ 不变 |
| 200706…564 p8 | `image=2` | 完全一致 | ✅ 不变 |
| 20230228…328 -83 | `table=2 image=1` | 完全一致 | ✅ 不变（中途曾回归，见 4.3） |
| 20230228…328 -84 | `table=1 image=1` | 完全一致 | ✅ 不变 |

### 4.2 语料库扫描 + 全量前后对比

1. **扫描**（临时探针只跑 `DocumentProcessor.preprocessing`，79 个 ≤20MB 的 `docs/pdf` 样本）：
   存在 `arrow_header` 的文档只有 **7 份**：`200706271781617948530064564`(p7,p8)、
   `200711131781638086275024718`(p32)、`202302281677505819604328`(p83,p84)、
   `202303251679660111823147`(p3~p5)、`202304271682510470028924`(p35,p38)、
   `20260507AN202606291826520711`(p106,107,109,112,154,160,161)、`354cb7d4-…-e3b0e3fcc4e3`(p304)。
   其余 72 份文档**一个 `arrow_header` 都没有** → 新流程对它们完全不可达。
2. **完整流程前后对比**：把上述 7 份文档涉及的 **18 个页面**（拆成单页后）各跑一遍改动前/后：
   **只有 4 页发生变化**（106、107、p160、p32），其余 **14 页逐行相同**；
   4.1 中的 4 处变化方向均为"预期 / 改善"。
3. 变化量最小的 p32：截图底边 +1.5pt（`247.90 → 249.42`），文本条目数不变（`text=10`），属纯几何扩展。

### 4.3 中途两次数据驱动的修正（重要）

1. **-83 页回归**：初版在 `202302281677505819604328-83` 上识别出一个 **1.44×1.44pt 的"表格右下角填充"**
   `[510.24,397.37,511.68,398.81]`，区域据此扩张把**整张真表格**（`T[83.78,443.11,511.68,535.03]`）吞掉并裁成截图。
   修正：`isUsableArrowhead` 最小尺寸护栏（面积 ≥4pt²、两边 ≥2pt）。
2. **p35/p38 回归**：`202304271682510470028924` 的 p35/p38 上识别出 **表格表头填充带**
   （`120.26×12.00`、`49.44×14.06`）→ 同样吞掉真表格。
   p35 能被既有 `filterShapeCoincidentFills` 覆盖（外层矩形 120.26×24.24 与候选面积相当），
   但 p38 的外层矩形是 `481.90×127.34`（整张表），面积比判据 `shapeArea <= 10 × fillArea` 不成立而漏过。
   修正：新增 `filterCandidatesInsideShapes`（"被实心图形覆盖 ≥90% 即丢弃"），两个源都过滤。
   修正后 p35/p38 **不再产生任何 `arrow_header`**，输出与基线逐行一致。
3. 连带效果：`202303251679660111823147` p4/p5、`20260507AN…` p109 的误判头部也被过滤掉 →
   这些页面回落到旧流程，输出与基线一致（p109 原先 1.5pt 的范围变化随之消失）。

### 4.4 单测

- 新增 5 个用例：
  - `ShapeRecognizerTest`：无头部时返回空；区域**通过原始线条**扩张到远端方框（去掉线条就不再包含）；相交区域合并 / 分离。
  - `FlowchartProcessorTest#arrowDrivenClusterIsNotVetoedByTablesInsideIt`：同一份内容，`arrowDriven=true` 裁成 1 张（表格随之移除），`arrowDriven=false` 仍被表格否决。
  - `ArrowE2ETest#flowDiagramIsGrownFromItsArrowheadOnPage106`：106 页头部 `[265.53,475.49,270.52,479.08]`、单区域、范围 `[86.88,375.83,508.39,531.89]`。
- 相关 8 个测试类：`Tests run: 77, Failures: 0, Errors: 0`（`ShapeRecognizerTest` 25、`FlowchartProcessorTest` 13、
  `TextDecorationProcessorTest` 21、`TableBorderProcessorTest` 8、`BarChartProcessorTest` 6、`ArrowE2ETest` 3、
  `SpecialTableProcessorTest` 1）。
- **全量**：`Tests run: 898, Failures: 40, Errors: 17, Skipped: 1`
  （改动前基线 `893/40/17/1`；`+5` 即新增用例；失败全部落在既有的
  `PageSeparatorIntegrationTest`(25)、`PagesOptionIntegrationTest`(10)、`EmbedImagesIntegrationTest`(5)、
  `ImageDirIntegrationTest`(4)、`AutoTaggerTest`(4) 等**环境相关**用例上，与本次改动无关）。

---

## 5. 已知限制与遗留

1. **头部是"轴端之外的切片"**：对"整支箭头是一个填充多边形"的 PDF（200706…564、招股书 154），
   头部 bbox 就是端部那一小段（如 `3.36×2.52pt`），而不是一个完整三角形；这是能从填充 bbox 得到的最大精度。
2. **头部识别依赖填充源**：若箭头只以描边绘制（无填充），或头部与轴落在同一描边路径里，则没有 `arrow_header`，
   该页回退旧流程（不产生回归，但也没有改善）。
3. **页面级切换**：页面一旦存在 `arrow_header`，流程图分组**整体**切换到 arrow 驱动，旧 `groupShapes` 分组不再参与流程图判定。
   若该页 arrow 区域被页面级保护拒绝（如 p4/p5 的整页区域）且旧分组本可产出截图，理论上会少一张截图；
   本次 18 页回归未出现（相关页面在过滤误判头部后都回到旧流程）。
4. **误判头部的过滤是"经验阈值"**：`4pt²`、`2pt`、`90%` 三个常量来自本次实测的两类反例（表格角点、表头填充带）；
   若后续出现新的反例，优先在这三个常量上收敛。
5. 需要在 server 侧生效时，记得先 `mvn -o -DskipTests install` 安装 core（本次未执行）。
6. 临时探针（`TmpArrowProbe`/`TmpArrowGroupProbe`/`TmpArrowHeaderScan`/`TmpFlowProbe`/`TmpRunPages`/`TmpRender`/`TmpSplitPages`）
   全部放在 `tmp_output/probe_src`（已 gitignore），**未进入 `src/`**；`DebugSample.java` 未改动。

---

## 6. 相关文件

- 改动：
  - `java/opendataloader-pdf-core/src/main/java/org/opendataloader/pdf/entities/content/ShapeChunk.java`
  - `java/opendataloader-pdf-core/src/main/java/org/opendataloader/pdf/processors/ShapeRecognizer.java`
  - `java/opendataloader-pdf-core/src/main/java/org/opendataloader/pdf/processors/FlowchartProcessor.java`
  - `java/opendataloader-pdf-core/src/main/java/org/opendataloader/pdf/processors/DocumentProcessor.java`
- 测试：
  - `.../src/test/java/org/opendataloader/pdf/processors/{ShapeRecognizerTest,FlowchartProcessorTest,ArrowE2ETest}.java`
- 调试产物（`tmp_output/`）：`flow2_before.log` / `flow2_after.log`（18 页前后对比）、
  `arrow_scan.log`（语料库头部扫描）、`full_test_after.log`（全量单测）、
  `flow2_after/*_images/*.png`（改动后截图）、`render_1682510_p35_1.png`（p35 真表格反例）。
- 相关记忆：`docs/memory/2026-09-21-有线表格误判为9行7列-根因定位与ChunkParser条带折叠修复.md`、
  `docs/memory/2026-09-21-有线表格右侧无闭合竖线-第4列内容丢失与多余第5列根因定位与修复.md`
  （106/107 页此前被记录为"流程图误识别为表格、无修改必要"，本次正是从流程图侧解决）。
