# MEMORY.md — 长期记忆（跨会话稳定事实）

> 细节归档在 `docs/memory/*.md`；本文件只保留结论级事实。维护原则：条目化、去重、只留仍在生效的结论。

## 用户偏好与协作约定
- **先定位真正原因、给方案，得到明确同意后才改代码**（用户常写“先查因、别改代码”）。
- 重要任务归档为 `docs/memory/YYYY-MM-DD-任务简述.md`。
- `DebugSample` / `DebugSample1` 由用户维护，**借用后必须还原**；临时插桩用完删除；
  调试产物放 `tmp_output*/`，探针放 `tmp_debug/`（已 gitignore）。
- 探针要访问 package-private 时放 `tmp_debug/probe_src/<包名>/*.java`，`javac -encoding UTF-8 -d tmp_debug/probe_classes`，
  运行用 `-cp "probe_classes;target/classes;<cp.txt 内容>"`（PowerShell：`Get-Content -Raw cp.txt`，勿用 `$(type cp.txt)`）。
- 改动前把 diff 备份成 `tmp_debug/<描述>.patch`，便于定点回退。

## 工程环境（本机）
- Maven 本地仓库 **`D:\Maven_Repo`**，编译统一加 **`-o`**；聚合 pom 是 `java/pom.xml`，用
  `-f <绝对路径>\java\pom.xml -pl opendataloader-pdf-core`。
- **JDK**：core/cli 源码级别 **11**；server 必须 **JDK 17**（`D:\Applications\Java\jdk-17.0.0.1`）。
  改 core 后要让 server 能编译：先 `mvn -o -DskipTests install`。
- Shell 实为 **PowerShell**：`cd` 后单条命令；无 `tail`/`&&`；`$(type cp.txt)` 会破坏 classpath，用 `Get-Content -Raw`。
- 单测：`surefire:test` 不编译，须先 `compile test-compile`，加 `-Dsurefire.failIfNoSpecifiedTests=false`。
- 改 veraPDF（`../veraPDF-*`）后必须 `mvn -o -pl <模块> -DskipTests -Dmaven.javadoc.skip=true -Dmaven.source.skip=true install`。
- 单页调试：`Config.setPages("108,140")` + 独立输出目录，比跑全量快得多。

## 线上环境（生产）
| program | 主机 | profile | 消费 topic |
|---|---|---|---|
| `opendataloader-pdf-server-hk` | `192.168.0.26` / `.215` / `.232` | `prod` | `hk_announcement_parse_all` |
| `opendataloader-pdf-server-hk` | `192.168.0.136` | 疑似 `prod-inc` | 疑似 `hk_announcement_parse_inc` |
| `opendataloader-pdf-server-hjs` | `192.168.0.88` | `prod-hjs` | `announcement_parse` |
| `opendataloader-pdf-server-hjs` | `192.168.0.11` / `.150` | `prod-hjsinc` | `announcement_parse_increment` |
- **Kibana** `https://kibana-huawei.valueonline.cn`（elastic / elastic@123），视图 `logs-jetty-default`；
  `host.name` 才是 IP，`@timestamp` 为 UTC。
- **Pulsar 客户端 2.9.2**：`ackTimeout(0, unit)` 关闭客户端重投；`ackTimeoutTickDuration` 是 2.10+ API 不可用。
- 每 consumer 一个 daemon 线程，**同步** `receive() → handleReceiveMessage() → acknowledge()`；失败也 ack（无 nack/DLQ）。
- “卡住”三病因：消费线程被 `Error` 打死；超大文档占住；CPU 满载（如 `ShapeRecognizer.buildChains`）。

## 流水线顺序（DocumentProcessor.processDocument）
`Loop1 ContentFilter（颜色过滤）→ OCR 兜底 → Loop2 TableBorder+TextLine → 跨页 HeaderFooter →
Loop3 Paragraph → Heading → chart/formula 并行循环（BarChart → PieChart → LineChart → Flowchart →
haveFormulas → ConsecutiveImageProcessor）→ setIDs → 跨页 List/Table 链接/层级`。
- 图片渲染取自 PDF 页面区域，**与 contents 无关**：从 contents 删掉的元素仍会出现在截图里。

## 图表 / 流程图 / 折线图截图（当前生效状态）
- `ShapeRecognizer`：填充矩形→`detectBarGroups`+`guessFilledShapeType`+`isValidBarGroup`（`hasBarGaps` 排除 stacked/adjacent）→
  `TYPE_BAR_CHART`；细长线→`TYPE_POLYLINE`；端点相连→`TYPE_ARROW`；填充三角→`TYPE_ARROW_HEADER`。
- `BarChartProcessor`/`PieChartProcessor`/`LineChartProcessor`/`FlowchartProcessor` 都是“增长循环吸收 + 截图 + `pageContents.removeAll(...)`”。
- **增长循环只吸收相距 ≤ `COLLECTION_MARGIN`(1pt) 的对象**。
- **表格 veto 与堆叠柱豁免（2026-09-29，未提交）**：`BarChartProcessor` 的 `liesInsideTable`（图形区 ≥50% 落在
  TableBorder/Table 内）+ `looksLikeTableGrid`（分量底边 ≥3 簇 ≥6 个 → 填充表格）防"表格被裁成图"
  （守卫对象：20230420 p137/195/243）。**坑**：堆叠柱段底边 Y 各不相同，会误中 `looksLikeTableGrid`——
  已在数簇前加 `isStackedBarComponents` 豁免（同 x 列分量垂直 gap ≤0.5pt 成链 ≥2 列 = 堆叠柱；
  堆叠段同路径 gap=0，表格单元格有网格线间隙不成链）。教训：该守卫是 09-28 无记录改动、无单测、未在多文档回归。
  备份 `tmp_debug/barchart_table_grid_guard_backup.patch`；验证 20260507 p110-117 与 after7 逐点一致。
- **p110 上方柱状图修复（`BarChartProcessor`）**：`VERTICAL_LABEL_MARGIN=8.0` + `VERTICAL_LABEL_OVERLAP_RATIO=0.5` +
  `isVerticallyAdjacentLabel`——短单行文本与当前框水平重叠 ≥50% 且位于**正上/正下方 ≤8pt** 也吸收（左右仍 1pt）。
  效果：p110 上图 `x[70.9,525.1] y[94.6,262.1]`，p113 与基线一致，p117 折线图把单位+图例并入图片。归档见 docs/memory。
- **p154 左图右文守卫（`ParagraphProcessor`）**：`isSeparatedByShapeNode(...)` 必须 `hasChartShape()` 收窄
  （页面含 bar/pie/line chart shape 时禁用），否则改坏 p110。
- **p23/p29/p114 表格误杀线图（2026-09-27 已修，未提交）**：表格最后一行的右边框+底边框会被读成"轴框+ bent 数据线"。
  修复在 `ShapeRecognizer`：`hugsFrameEdges`（数据线贴 frame ≥2 边各 ≥50% 边长则拒）+ 标记点必须严格在框内 1pt（`LINE_CHART_MARKER_INSET`）。
  after9_full 765 页回归仅 3 页预期差异。改动前备份：`tmp_debug/p23_guard_backup.patch`。
- **路径族召回（2026-09-27 已落地，未提交）**：`ShapeRecognizer.applyPathFamilyChartTypes`——≥2 条 polyline 首尾链接
  + dash 小矩形 + 无节点 + 带宽 ≥120/高 ≥20pt → 合成 TYPE_LINE_CHART 复合块（Flowchart 之前）。p119/p120 召回成功，
  after11_full 765 页 0 diff。**教训：扁带必须限高**（p113 瀑布基线 8pt 高曾误触发）。
- **线图曲线盲区（2026-09-27 调查结论：无需改代码）**：有子线段的 LineArtChunk 子段早已并入 ShapeRecognizer 的
  allLines 并参与 polyline 链/路径族；真正无几何的 bbox-only LineArtChunk（如样本 200910301782365038553054634 p95
  曲线）无法链分析。【更正】p95 实为折线图（私人辦公室價格指數）被 findTableBorders 误判成 10×19 表，并非"表格+趋势线"；
  理论缺口修正为：bbox-only 曲线+有框图表，及"折线图被当表格"的反向误判（仅横线+外框的图表型网格应排除在表格判定外）。
- **p108/p140 方案 A（当前已实施，未提交）**：两页框是“闭合+曲线+仅描边”路径，原识别层（LineChunk/LineArtChunk + fillBoxes 兜底）
  进不去 → 无形状组 → 不出图。
  - `GetDrawings.Drawing` 加 `hasCurve` + `subpathRects`（`computeSubpathRects` 按子路径切，卡片图是“一条路径多个子路径”）。
  - `DocumentProcessor`：`extractPageFillBoxes` → `extractPagePathBoxes` 返回 `PathBoxBundle{fillBoxes, curvedClosedPathBoxes}`，
    仅把 `hasCurve` 闭合路径按 `subpathRects` 拆框传入。
  - `ShapeRecognizer`：新增 `recognizeCurvedNodeBoxes`（`CURVED_NODE_MIN_SIZE=15`、`MIN_CURVED_NODE_COUNT=2`，
    用 `filterShapeCoincidentFills` 排除与饼图/柱段重合的曲线）→ 每框一个 `TYPE_RECTANGLE` + 一个 `TYPE_GROUP` 复合框圈起整块网格。
  - `FlowchartProcessor`：新增 `cardGrid` 分支（`MIN_CARD_GRID_RECTANGLES=4`、`MIN_CARD_GRID_COMPONENTS=8`）；
    `absorbAdjacentShapes` 加 `coversNewProse` 守卫——合并后会新覆盖正文块（如表格标题）则跳过，避免误吞。
  - 验证：p108≈`x[65.5,529.8] y[352,462]`、p140≈`x[127.8,467.5] y[303.1,493.1]` 均出图；
    after5 全量回归 vs after2 基线确认守卫生效（20230420 的 137/195/243 表格标题回到基线）。
  - **p140 卡片内文字随图进图、从文本层移除（2026-09-25 用户确认可接受）**：与流程图一致，无需特殊处理。
  - **p108「硬件/軟件基礎設施」底色块并入截图（2026-09-25 已修，未提交）**：
    根因——底色块是「填充矩形」，属独立 shape group，距主图节点网格 ~4.7pt；`FlowchartProcessor.absorbAdjacentShapes`
    被注释掉（`// TEMP-EXPERIMENT`），且 veraPDF `BoundingBox.overlaps(box, margin)` 实测**不会**按 margin 扩展
    （8pt margin 也过不了 4.7pt gap），所以相邻图形合并循环 `screenshotBox.overlaps(laterBox)`（无 margin）永远合并不进来。
    修复——`processFlowchartGroups` 增长循环改用真实距离判断 `boxesWithinMargin(screenshotBox, laterBox, GROUP_MERGE_MARGIN=10)`
    （先把 search box 外扩 10pt 再 `overlaps`），并加 `coversNewProse` 守卫防误吞正文；`absorbAdjacentShapes` 仍保持注释（沿用 after5 基线）。
    验证——p108 截图 bbox 从主图组底边(~441.7)下延到 `y1≈463.85`，底色块已入图；p110/p111 图表不受影响。
  - 备份：`tmp_debug/p108_p140_p154.patch`（含早期含 p154 箭头的版本）。

## 目录（catalog）判定
- 三层判定在 `CatalogBookmarkProcessor`：行级 `matchTocLine`、页级 `isTocPage()`（≥3 条且占比 ≥0.4）、
  区间级 `score()=totalTocLines*(1+ln(1+pageCount))`。**评分只看条数是误判之源**。
  JSON 路径 `extractCatalogBookmarksFromJson`（生产在用）与内容路径是**两份拷贝**，改要同步。
- 候选区间起始页必须有单行段落含“目录/目錄”（`目\s*[录錄]`）且位于该页首个目录条目之上。
- 标题匹配三档（`matchBookmarkTitle`）：`EXACT` → `PREFIX` → `FUZZY`（公共前后缀 + 中间多出字符之和 ≤2）。

## 有线表格（lattice_table）
- 链路：`preprocessing` → veraPDF `parseChunks()` → `findTableBorders()` → `StaticContainers.setTableBordersCollection()`
  → `TableBorderProcessor` → `JsonWriter`。
- **双线问题（已修）**：修复在 `ChunkParser.parsingStripeFromLines()` + `processS()` 索引循环跳过已消费边（条带坐标在变换前坐标系，厚度须乘 CTM 缩放）。`processB()` 未同步（已知遗留）。
- **右侧无闭合竖线（已修）**：重建横线必须用 `LineChunk.createLineChunk(..., BUTT_CAP_STYLE, color)`，否则 bbox 外扩半线宽→整条横线被忽略。
- 探针初始化：`StaticResources.setDocument(pd)` + `new GFSAPDFDocument(pd)` + `StaticContainers.updateContainers(document, fileName)`
  + `StaticContainers.setIsDataLoader(true)` + `document.parseChunks()`。
- 跨页表格链接（`checkNeighborTables`）：忽略列表原缺 `ShapeChunk`（**不是** `LineArtChunk` 子类），已加 `content instanceof ShapeChunk`（**不加 `ImageChunk`**）。

## 页眉/页脚（HeaderFooterProcessor）
- `MIN_REPEATED_VALUE_PAGE_RATIO = 0.5`（`applyRepeatedValueCoverage`）——仅“文本相同/bbox 相同”须 ≥50% 页面；页码递增序列不受约束。
- 同批：`splitRejectedPageTopCandidates`、`TableBorderProcessor.checkNeighborTables` 跨页表格链豁免、JSON `lattice_table` 新增 `previous_table`(bool)。
- 并行处理下图片文件名编号非确定，对比 JSON 要排除。

## 叠字去重（overprint）— 已落地
- `TextProcessor.removeOverprintedTextChunks` 接入 `ContentFilterProcessor.getFilteredContents`；行触发门控已收紧，
  全量 85 份回归仅影响真正叠印文档且比基线少丢字。core 单测 `TextProcessorTest` 全绿（含逆向验证用例）。
