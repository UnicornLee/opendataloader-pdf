# 2026-10-13 第25页表格脚注列"(5)"重复到空白行 - JsonWriter 文本组装按 owner 坐标回捞内容 - 根因定位与修复

## 现象描述

用户反馈：解析 `docs/pdf/202609071788789239330011123.pdf`（江波龍配發結果公告）第 25 页（JSON `page_index: 25`，0 基页码 24，横版页 793.7×595.3）的 lattice_table 时：

- 第 2 行第 1 列单元格输出了 `text: ["(5)"]`，应为空；
- 第 3 行第 1 列单元格输出了 `text: ["(5)"]`，应为空；
- `(5)` 只应存在于第 4 行第 1 列单元格。

修复前 JSON（节选，`.agents/p25_out.txt`）：4 行表格，第 1 行为跨 13 列的"附註：…"大单元格；第 2、3、4 行第 1 列**各有一个** `"(5)"` 单元格。其中第 2/3 行的畸形：

```json
{ "y0": 595.276, "cell_radio": 0.0476, "width": 0.0,
  "x0": 1.7976931348623157E308, "y1": -1.7976931348623157E308, "x1": 0.0,
  "row_len": 1, "column_len": 1, "text": [ "(5)" ], "height": 0.0 }
```

`x0=Double.MAX_VALUE`、`y1=-Double.MAX_VALUE`、`width/height=0` 是"包围盒从未被设置"的空盒初始状态——这已经是强烈的"幽灵单元格"信号。

## TL;DR

PDF 里 `(5)` 只有**一处**（第 4 行第 1 列区）。表格模型中第 2/3 行第 1 列是**同一个错位占位 cell 对象**（contents 为空，但 `getRowNumber()` 指向第 3 行 0 基=第 4 行）。`JsonWriter.generateJsonPageContentData` 主表格文本组装循环以 `cell.getRowNumber()/getColNumber()`（owner 坐标）为锚点遍历槽位回捞内容，占位 cell 于是跳去读了第 4 行第 1 列真实 cell 的内容，把 `(5)` 抄了过来。**修复**：遍历起点改用当前网格坐标 `m/i`——规范单元格两者恒等（no-op），错位占位则只读自身空内容。**验证**：`(5)` 全文档仅出现 1 次于第 4 行第 1 列；第 2/3 行第 1 列为空 text；表格相关测试 33 个全部通过。

## 完整定位过程

### 第 1 步：确认 JSON 现状（probe_p25.ps1）

落地脚本截取主 JSON `"page_index" : 25` 起 9000 字符（`.agents/probe_p25.ps1` → `.agents/p25_out.txt`），确认上表现象。同时注意到：

- 第 2 行（表头）其余 4 格 `x0` 从 **76.786** 起（名称/資產管理人/…，bg `#000059`）；
- 第 4 行 `(5)` 格坐标正常：`x 42.77~76.786`、`y0 366.113~443.008`（top-down）。

即表格几何上"第 1 列（x 42.8~76.8）"只在第 4 行真实存在，表头/数据行的内容列全部从 x=76.8 起。

### 第 2 步：PDF 实测（TmpCellChunkProbe 文本块坐标）

复用既有探针模式（`DocumentProcessor.preprocessing` + `ContentFilterProcessor.getFilteredContents(page=24)`），粗过滤 `x∈(40,80)`、`y∈(140,290)`（PDF 下起上坐标，页高 595.276；JSON y = height − topY），输出（`.agents/cellprobe2.txt`）：

```
TextChunk  [48.4, 171.1, 55.7, 184.5]  (5)          ← 全页唯一一处 (5)
TextChunk  [76.8, 200.2, 716.2, 215.6] 據中信證券國際所知…
LineChunk  [42.5, 151.8, 76.8, 152.3]               ← 第4行底边（覆盖第1列）
LineChunk  [76.8, 228.7, 326.5, 229.2]              ← 行分隔线，从 76.8 起！
LineChunk  [76.8, 251.2, 326.5, 251.7]              ← 行分隔线，从 76.8 起！
ShapeChunk [76.8, 251.2, 751.7, 274.2]              ← 表头背景矩形，也从 76.8 起
```

关键读数：

1. `(5)` 位于 y 171.1~184.5，换算行区（第 4 行 = PDF y 152.3~229.2）→ **物理上就在第 4 行第 1 列内**。
2. 第 2/3 行与第 4 行之间、以及第 2/3 行相互之间的水平分隔线**都从 x=76.8 起笔**，不穿过第 1 列区（42.5~76.8）→ 第 1 列不是贯穿 2~4 行的合并大格，而是"第 2/3 行根本没有第 1 列边框，该列只在第 4 行存在"。

### 第 3 步：dump 表格单元格模型（决定性证据）

在探针里对第 24 页内容直接补跑管线 loop-2 的同一步骤 `TableBorderProcessor.processTableBorders(pc, 24, null)`，遍历归一化后 `TableBorder` 的每个网格槽位，打印 `owner(rowNumber,colNumber)`、`span(rowSpan,colSpan)`、bbox、contents 与 `System.identityHashCode`：

```
r1 c0: owner=(3,0) span=(1,1) bbox=[INF,INF,0,0]      hash=1552221437  (contents 空)
r2 c0: owner=(3,0) span=(1,1) bbox=[INF,INF,0,0]      hash=1552221437  (contents 空)   ← 与 r1c0 同一对象
r3 c0: owner=(3,0) span=(1,1) bbox=[42.8,152.3,76.8,229.2] hash=1027319653  {CustomSemanticParagraph}  ← 真正的 (5) 格
r3 c1: owner=(3,1) span=(1,4) bbox=[76.8,152.3,751.4,229.2] hash=633079302
```

决定性发现：

- `r1c0` 与 `r2c0` 是**同一个 cell 对象**（identityHashCode 相同），自身 `contents` 为空、bbox 为空盒；
- 但它的 `rowNumber=3`（0 基，指向第 4 行），`colNumber=0` 与所在槽位列一致——即"人排在网格第 1/2 行，户口本写的是第 3 行"；
- 真正含 `(5)` 的对象是另一个（hash=1027319653），合法占据网格 (3,0)。

### 第 4 步：对代码找泄漏路径（根因确认）

`JsonWriter.generateJsonPageContentData` TableBorder 分支（约 L1300 起）的槽位文本组装循环原本写作：

```java
for (int n = cell.getRowNumber(); n < cell.getRowNumber() + cell.getRowSpan(); n++) {
    for (int k = cell.getColNumber(); k < cell.getColNumber() + cell.getColSpan(); k++) {
        ...
        TableBorderCell cellItem = tableBorder.getRows()[n].getCells()[k];
        List<IObject> cellItemContents = flattenCellContents(cellItem.getContents(), ...);
```

- 输出 r1c0（网格 m=1, i=0）时，循环却从 **owner 坐标 (3,0)** 起遍历 → 读到 `rows[3].cells[0]`（hash=1027319653）的 contents → `(5)` 泄漏进第 2 行；
- r2c0 同理（同一对象、同一 owner）→ `(5)` 再泄漏一次；
- r3c0 自己输出时 `m==rowNumber==3`，正常输出 `(5)`。

外层的 `if (m < cumulativeRowSpans[i]) continue;` 守卫为什么没拦住？该守卫防的是"被跨度覆盖的槽位"（跨行合并时重复出现的**同一引用**），语义基于前序单元格申报的 rowSpan 累计。而这里的占位 cell 是 span=(1,1) 的"独立"槽位宿主，不在任何跨度的覆盖路径上，守卫按设计放行——它防不住"owner 坐标与网格坐标错位"这类上游识别器产出的畸形对象。

对规范表格，一个合并 cell 只会在其 owner 槽位被输出（其余覆盖槽位被守卫跳过），因此输出时刻**恒有 `m==rowNumber && i==colNumber`**，两种锚点等价——这使"改用网格坐标"成为零行为差异的安全修复面。

## 修复

`java/opendataloader-pdf-core/src/main/java/org/opendataloader/pdf/json/JsonWriter.java`（主表格文本组装循环）：

```java
// 修改前：锚定 owner 坐标
for (int n = cell.getRowNumber(); n < cell.getRowNumber() + cell.getRowSpan(); n++) {
    for (int k = cell.getColNumber(); k < cell.getColNumber() + cell.getColSpan(); k++) {
// 修改后：锚定当前网格坐标（附注释说明错位占位 cell 场景）
for (int n = m; n < m + cell.getRowSpan(); n++) {
    for (int k = i; k < i + cell.getColSpan(); k++) {
```

设计取舍：

- **不删除/跳过占位 cell，仅让其读到空内容**。若把 r1c0/r2c0 整个跳过，第 2/3 行的 `cell_radio` 之和会从 1.0 掉到 0.9524（0.0476 的列份额丢失），破坏 JSON 消费端（预览模板按 radio 定列宽）的列对齐；保留空 text 单元格则几何不变、只去掉错误文字。
- 同一次遍历顺带累积的 `cellFlatContents`（供 `getDominantFontColor` 采样 `text_color`）自动跟随新锚点，占位 cell 不再从他行借文字颜色，无副作用。
- 不修上游识别器（veraPDF 库生成错位占位对象），因为库在另一个仓库（veraPDF-wcag-algs），且序列化端以网格坐标为准本就是更稳健的表达；此处与项目内既有惯例一致（`TableStructureNormalizer.countNonEmptyRows`、`assignBackgroundColorsFromRectangleShapes`、`renderTableRowToHtml` 都用 `rowNumber/colNumber == 槽位` 判断"真正 owner"）。

## 验证结果

1. 重新 compile 后重跑 `DebugSample1`（`.agents/run_debug.ps1`），检查脚本 `.agents/check_p25.ps1` 统计第 25 页片段：
   - `empty text cells: 2` → 第 2/3 行第 1 列 `"text": []`；
   - `"(5)" occurrences: 1` → 仅剩第 4 行第 1 列（`x0=42.77, y0=366.113`）。
2. 表格/JSON 相关测试（`JsonWriterLineFontSizeTest`、`JsonWriterNestedTableHtmlTest`、`JsonWriterTableCellGroupingTest`、`MarkdownTableTest`、`SpecialTableProcessorTest`、`TableBorderProcessorTest`）共 **33 个全部通过**（BUILD SUCCESS）。
3. GetProblems 无编译错误。

## 失败尝试与踩坑记录

- 第一版探针沿用上一任务的区域过滤条件（`box.overlaps` 式精确过滤）在页 24 无任何命中；改为 x/y 粗范围过滤后才拿到数据（与第 7 页任务时同样的教训：**区域过滤宁松勿严，先看到全部对象再收窄**）。
- PowerShell 内联 `-Command` 拼 `$matches`/正则的验证命令再次被引号剥离打碎（`= : 无法将…识别为 cmdlet`），落地为 `.agents/check_p25.ps1` 脚本才跑通——遵循已有经验"复杂命令必须落地 ps1"。
- 畸形 bbox 里 `x0=1.7976931348623157E308` 即 `Double.MAX_VALUE`、`y1=-1.79E308`，对应 veraPDF `BoundingBox` 从未扩展过的空盒初值（left=+INF、right/top=-INF、bottom=+INF 混合 clamped 输出），见到这种值应直接怀疑"cell 对象未被正常填充"。

## 经验教训

- **凡遍历 `TableBorderRow.getCells()` 按槽位输出或取内容，必须区分"网格坐标 (m,i)"与"owner 坐标 (getRowNumber/getColNumber)"**：识别器可能产出 owner 指向别行的错位占位对象（contents 空、bbox 空盒、同一实例被塞进多个槽位）。用网格坐标遍历槽位是序列化端的正确锚点；owner 坐标只适合做"是否真正 owner"的判断。
- JSON 里出现 `width/height=0`、`x0≈1.8E308` 的单元格时，把它当作模型畸变线索顺藤摸瓜，而不是在输出端特判删除——先弄清它的内容是从哪里被错误引入的。
- `identityHashCode` 转储是快速判定"多槽位是否共享同一 cell 引用"的低成本手段，探针里值得默认打印。

## 关联记忆

- common_pitfalls_experience：`JsonWriter主表格文本组装用owner坐标回捞内容导致错位占位单元格抄取他行文本`（本次新建）
- common_pitfalls_experience：`TableBorderRow.getCells()槽位重复引用导致HTML单元格重复`（同族问题：跨度覆盖槽位的重复引用）
- 分析文档：`docs/memory/2026-10-13-嵌套子表格标题行右侧多出重复列-JsonWriter行渲染缺起始单元格守卫-根因定位与修复.md`（本文是其主要修复的"再跟进"章节的展开版）
- tool_experience：`Windows PowerShell 下执行复杂命令需落地为 ps1 脚本`
