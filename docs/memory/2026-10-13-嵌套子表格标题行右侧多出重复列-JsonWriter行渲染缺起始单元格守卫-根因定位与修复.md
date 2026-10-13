# 2026-10-13 嵌套子表格标题行右侧多出重复列 - JsonWriter行渲染缺起始单元格守卫 - 根因定位与修复

## 现象描述

文件：`docs/pdf/202609071788789239330011123.pdf`（江波龍配發結果公告）

第 3 页表格第 1 列第 3 行内嵌 3 个子表格（公司資料 / 價格資料 / 發售股份及股本），每个子表格的第 1 行右侧都多出 1 列：例如第 1 个子表格首行本应是 1 个 `公司資料`（colspan=2 占满 2 列），实际渲染成两个并排的 `公司資料`（各占 2 列）。第 4 页 continuation 表格中的 `發售量調整權（增發權）`、`超額分配`、`所得款項` 等标题行及整行跨列的长备注行同样全部重复。

修复前 JSON 片段（`tmp_output/202609071788789239330011123.json`）：

```html
<table><tr><td colspan="2">公司資料</td><td colspan="2">公司資料</td></tr>
<tr><td>股份代號</td><td>09976</td></tr>...</table>
```

## TL;DR

跨列（colspan）单元格在 `TableBorderRow.cells` 数组中以**同一引用重复填充每个被覆盖的槽位**；`JsonWriter.renderTableRowToHtml` 直接遍历 `getCells()` 逐个输出 `<td>`，没有像 `HtmlGenerator` / `MarkdownHTMLGenerator` 那样做 "仅起始位置输出" 的守卫，导致每个跨列单元格被输出 N 次（N = colSpan），行右侧凭空多出重复列。加守卫一行判断即修复。

## 完整定位过程

1. **复现**：运行 `org.opendataloader.pdf.DebugSample1`（目标文件已预设），检查生成的 JSON。第 3 页 `lattice_table`（id=5）第 3 行单元格的 `text` 数组中，3 个子表格 HTML 的首行均为 `<td colspan="2">X</td><td colspan="2">X</td>`；`公司資料` 全文出现 2 次。
2. **确认重复模式**：所有出问题行都是"整行 1 个单元格"（在 2 列网格上 colSpan=2）；普通 2 列内容行（colSpan=1）从不重复 → 重复次数 = colSpan，指向"按槽位输出"而非"按单元格输出"。
3. **查库结构**（`D:\Code\JavaCode2\veraPDF-wcag-algs`，`TableBorderRow.java`）：`getCells()` 返回原始数组，跨度单元格引用会被重复写入每个被覆盖槽位；库自身的 `getNumberOfCells()`/`getNumberOfCellWithContent()` 均通过 `cell.colNumber == colNumber && cell.rowNumber == rowNumber` 过滤只数起始单元格。
4. **对比三个 HTML 生成器**：
   - `HtmlGenerator`（L384）：有起始守卫 ✔
   - `MarkdownHTMLGenerator`（L46）：有起始守卫 ✔
   - `JsonWriter.renderTableRowToHtml`（L1786）：`for (TableBorderCell cell : row.getCells())` 无守卫、无 null 检查 ✘ ← **根因**
5. **确认所有建表路径都遵循"引用重复填槽"约定**：`TaggedDocumentProcessor.processTable`（`table.get(row+i).set(col+j, cell)`）、`DoclingSchemaTransformer`（L568-572 注释明确"Mark every slot covered by this cell's span with the same instance"）、边框表格构建路径。守卫判断安全。

## 根因表

| # | 位置 | 问题 |
|---|------|------|
| 1 | `JsonWriter.renderTableRowToHtml` | 直接遍历 `TableBorderRow.getCells()` 输出 `<td>`，跨列单元格的同一引用占据多个槽位时被输出多次，每多占 1 槽即在行右侧多出 1 个重复单元格 |

## 修复清单

1. **`java/opendataloader-pdf-core/src/main/java/org/opendataloader/pdf/json/JsonWriter.java`**
   `renderTableRowToHtml` 改为按列下标遍历，仅当 `cell.getRowNumber() == row.getRowNumber() && cell.getColNumber() == colNumber`（起始单元格）时输出；同时补 null 槽位跳过。与 `HtmlGenerator` / `MarkdownHTMLGenerator` 的既有守卫保持一致。
2. **新增回归测试 `java/opendataloader-pdf-core/src/test/java/org/opendataloader/pdf/json/JsonWriterNestedTableHtmlTest.java`**
   - `colspanHeaderCellIsRenderedOnceNotOncePerCoveredSlot`：2 列表格、首行 colspan=2 标题引用占 2 槽 → 期望仅 1 个 `<td colspan="2">`（旧代码会输出 2 个，精确复现本 bug）。
   - `rowspanCellIsRenderedOnlyInItsOriginRow`：rowspan=2 单元格延伸到下一行槽位 → 只在起始行输出并带 `rowspan="2"`。

## 验证结果

1. 重新运行 `DebugSample1` 解析该 PDF：
   - `公司資料` 出现次数 2 → **1**；
   - JSON 内嵌的全部 7 个子表格首行均为单个 `<td colspan="2">…</td>`，普通 2 列内容行不受影响；
   - 第 4 页的整行跨列长文本行（發售量調整權已獲悉數行使…、該超額分配可以…)也从重复恢复为单个单元格。
2. 表格/JSON 相关测试：`JsonWriterLineFontSizeTest`、`JsonWriterTableCellGroupingTest`、`MarkdownTableTest`、`SpecialTableProcessorTest`、`TableBorderProcessorTest` 共 28 个用例 + 新增 2 个用例全部通过（BUILD SUCCESS）。
3. 全量 `mvn test` 中的其余失败为环境性既有问题（fixture 指向 `D:\Code\JavaCode\opendataloader-pdf-parse` 不存在、poppler 文件锁、`tmp_output` 残留文件冲突等），与本次修改无关。

## 经验教训

- 凡遍历 `TableBorderRow.getCells()` 输出单元格的地方，**必须**加 `rowNumber/colNumber` 起始守卫；`cells` 数组是"槽位视图"不是"单元格列表"，跨列/跨行单元格的引用会在被覆盖的每个槽位重复出现。项目内已有三处 HTML 生成器，两处有守卫，说明这是该数据结构上的高频踩坑点，新增渲染路径时应对照 `HtmlGenerator` 的写法。
- `MarkdownTableTest` 中已有 `row0.getCells()[1] = cell00; // colspan duplicate` 的建模注释，可作为识别此类问题的线索。

## 遗留问题

- 无。外层表格 JSON 网格序列化路径（`generateJsonPageContentData` L1260+）使用 `cumulativeColSpans` 自行消化跨度槽位，且对重复文本行有 `contains` 去重，未受影响。

## 跟进（同日）：嵌套子表格标题行背景色

用户追加需求：3 个子表格（以及第 4 页各卡、子基金名稱表）首行在 PDF 中是深蓝底白字，要求嵌入 HTML 也带上背景色。

- **第二个缺口**：`assignBackgroundColorsFromRectangleShapes` 只在 `normalizeAndProcessTableBorder` 内对当前层表格执行，匹配用的是同层 contents；而 ShapeChunk 永远不会进入单元格内容列表（`addContentToTableBorder` 对 ShapeChunk 返回 null），所以嵌套子表格归一化时手里一个矩形 Shape 都没有，单元格背景色从未被赋值。页面级表格不受影响（banner 行的 `bg_color=#000059` 即证明）。
- **修复**：
  1. `TableBorderProcessor.processTableBorders` 在 `depth == 0`（页面级调用）末尾新增 `assignBackgroundColorsToNestedTables(contents, newContents)`：递归遍历每个表格单元格里嵌入的 TableBorder，用页面级原始 contents（含全部矩形 Shape）复用既有的 `assignBackgroundColorsFromRectangleShapes` 补赋背景色。
  2. `JsonWriter.renderTableCellToHtml` 在 `<td>` 上输出 `style="background-color:#rrggbb"`；RGB→hex 转换抽取为 `toHexColor`，与外层网格 `bg_color` 字段共用。
  3. 回归测试 `JsonWriterNestedTableHtmlTest.cellBackgroundColorIsEmittedAsInlineStyle`（0,0,0.35 → #000059）。
- **验证**：重跑 DebugSample1，JSON 内嵌全部 7 个子表格的表头 `<td>` 均带 `style="background-color:#000059"`；相关测试 31 个全部通过。重叠匹配仍受 50% 单元格面积阈值约束，不会把远处矩形的颜色误配到子表格单元格。

### 再跟进：表头文字反白（color）

深蓝底上的白字若不输出 color，渲染预览时文字不可见。

- 提取链路的 `TextChunk.fontColor`（归一化 [0,1] RGB）在生产管线中确实被填充（`TextLineProcessor` 合并 chunk 时会透传），无需伪造默认色。
- **实现**：`renderTableCellToHtml` 将 flatten 提前，当单元格有背景色时追加 `color:#rrggbb`：取展平文本块中出现次数最多的非空 `fontColor`（`getDominantFontColor`，按 hex 量化后多数表决）；近黑（各分量 <0.02，`isNearBlack`）或无颜色信息时不输出，避免污染普通单元格。
- **验证**：新增 `blackOrMissingFontColorIsNotEmittedOnColoredBackground` 用例，相关测试 32 个全部通过；重跑 DebugSample1，7 个表头均输出 `style="background-color:#000059; color:#ffffff"`，正文单元格无 color 输出。

### 再跟进：近白色字体必须有背景色才输出

风险：背景色匹配失败（矩形重叠未达 50% 阈值、OCR 内容无 Shape 等）时，若仍输出 `color:#ffffff`，白字落在白底上直接不可见。

- **规则**（`renderTableCellToHtml`）：近黑任何情况都不输出；近白（各分量 >0.95，`isNearWhite`）仅在单元格有背景色时输出；其他可见颜色（如红字）无论有无背景都输出。
- 踩坑：测试字符选 “R” 失败 —— `GlobalConstant.SPECIAL_CHARACTER_ORIGIN` 把单字母 "R" 当作 ® 注册标记源字符替换为 ☑，与颜色逻辑无关，换用 "Z" 后通过。
- **验证**：新增 `nearWhiteFontColorIsOnlyEmittedOnTopOfABackground`（白字无背景不输出、红字无背景输出 `color:#ff0000`）；相关测试 33 个全部通过；重跑 DebugSample1，本文件表头因背景匹配成功，`background-color + color` 组合输出不变，正文单元格仍无任何 style。

### 再跟进：颜色规则应用到主表格（外层网格）

用户要求把背景色/文字色及近白限制同样应用到主表格，如第 7 页表格第一行（深蓝底白字表头）。

- 外层网格本就输出 `bg_color`（页面级 Shape 匹配在主表格层一直有效），缺口是**没有文字色字段**。
- **实现**：
  1. 把嵌套表的输出规则抽成共用判断 `shouldEmitFontColor(fontColor, hasBackground)`（近黑不输出；近白仅在有背景时输出；其他可见色总输出），`renderTableCellToHtml` 同步改用，两处规则保持单一来源。
  2. 主表格网格序列化（`generateJsonPageContentData` 的 TableBorder 分支）：文本组装循环里累积单元格所覆盖槽位的展平内容 `cellFlatContents`，用 `getDominantFontColor` 取主导字体色，按共用规则写入新字段 `text_color`（`JsonName.TEXT_COLOR_HEX`，hex 表示与 `bg_color` 一致）。
- **验证**：重跑 DebugSample1，第 7 页表格第一行 5 个表头单元格均新增 `"text_color" : "#ffffff"`（配合原有 `bg_color=#000059`），黑色正文单元格不输出 `text_color`；嵌套子表格 HTML 样式无回归（仍为 `background-color:#000059; color:#ffffff`）；相关测试 33 个全部通过。
- **踩坑（预览仍显黑）**：JSON 已带 `text_color` 但用户打开生成的预览 HTML 仍看到黑字——模板 `templates/announcementAnalysis.html`（及 `_font` 变体、`samples/json/lorem.html`）的 `<td>` 只绑定了 `:style="{background: td.bg_color}"`，未绑定文字色。三处模板补上 `color: td.text_color`（Vue 对象语法对 undefined 自动省略，普通单元格不受影响）；注意模板是 classpath 资源，改完需重新 compile（拷贝 resources）再解析才会生效。

## 再跟进：主表格第 25 页脚注列 "(5)" 被复制到空白行

用户反馈：第 25 页表格第 2、3 行的第 1 列应为空，`(5)` 只应存在于第 4 行第 1 列单元格。实际 JSON 三行都输出 `(5)`，其中第 2/3 行的第 1 列是畸形空盒（`width 0`、`x0=1.79E308`）。

- **探针实测**：`TmpCellChunkProbe` 跑 `processTableBorders` 后 dump 单元格结构，发现 PDF 里只有一个 `(5)` TextChunk（`[48.4, 171.1, 55.7, 184.5]`，落在第 4 行区），归 `r3c0`（hash=1027319653，真实 bbox `[42.8,152.3,76.8,229.2]`）。而 `r1c0`/`r2c0` 是**同一个错位占位 cell**（hash=1552221437），自身 `contents` 为空，但 `owner=(3,0)`（`getRowNumber()=3`）。
- **根因**：`generateJsonPageContentData` 主表格文本组装循环用 `cell.getRowNumber()/getColNumber()` 作为遍历起点回捞内容（`for n = rowNumber; n < rowNumber+rowSpan; for k = colNumber; ...`）。对规范单元格，被输出时 `m==rowNumber && i==colNumber`，二者等价；但这个错位占位 cell 排在网格第 1/2 行、owner 却指向第 3 行，于是跳去读了第 3 行第 0 列的真实 cell，把 `(5)` 抄了过来。几何上表头/数据列从 x=76.8 起，第 0 列（x 42.8–76.8）只在第 4 行真实存在，第 2/3 行第 0 列本应是空占位。
- **修复**：文本遍历起点改用**当前网格坐标** `m/i`（`for n = m; n < m+rowSpan; for k = i; k < i+colSpan;`）。规范单元格 `m==rowNumber && i==colNumber`，行为完全不变；畸形占位 cell 则只读自身（空）内容。保留占位 cell 输出（仅清空 text）而非跳过，以维持每行 `cell_radio` 求和为 1 的列宽对齐。
- **验证**：重跑 DebugSample1，第 25 页表格 `empty text cells: 2`（第 2/3 行第 1 列）、`"(5)" occurrences: 1`（第 4 行第 1 列 `x0=42.77, y0=366.113`）；相关表格测试 33 个全部通过（BUILD SUCCESS）。

## 再跟进：子表格内联 HTML 增加外边距样式

用户需求：子表格拼装时加样式——上下 margin 5px，左右 margin 为该列宽度的 2%。

- **实现**：`JsonWriter.renderTableBorderToHtml` 输出 `<table>` 标签时统一加盖 `style="margin:5px 2%"`（常量 `NESTED_TABLE_STYLE`），覆盖所有嵌套层级（递归路径 `renderTableCellToHtml` 体内的子子表同样生效）。
- **关键取舍**：左右 margin 保持 CSS 百分比写法而非解析期算 px——table 的百分比 margin 按 containing block（即容纳它的单元格/列宽）解析，浏览器端天然就是“该列宽度的 2%”，且逐层嵌套自适应，无需在 `flattenCellContents`/`renderTableBorderToHtml` 调用链上穿透列宽，也没有 PDF pt→px 单位换算问题。
- **验证**：`JsonWriterNestedTableHtmlTest` 5 处精确字符串断言同步更新后表格相关测试 33 个全部通过；重跑 DebugSample1，输出 JSON 内嵌子表格 `<table style="margin:5px 2%">` 全部命中、无残留裸 `<table>`。
- **踩坑（右侧溢出父表格）**：用户反馈加 margin 后子表格右边跑到了父表格外。根因在预览样式表 `templates/index.css` 的全局规则 `table { border-collapse:collapse; box-sizing:border-box; width:100% }`——它命中所有层级的 table，包括内联进单元格的子表；而 margin 不在 box-sizing 计算范围内，于是 100% 本体 + 左右各 2% margin = 104% 必然溢出。修复：`NESTED_TABLE_STYLE` 改为 `"margin:5px 2%; width:96%"`，内联 width 优先级高于样式表，96% + 2% + 2% = 恰好 100% 填满列宽且两侧各留 2%（百分比 width/margin 都相对同一 containing block，恒等式成立，逐层嵌套自适应）。验证：重编译+重跑后输出内嵌子表全部为 `<table style="margin:5px 2%; width:96%">`，测试断言同步更新后 33 个全部通过。教训：给内联 HTML 片段加样式时，必须先看预览模板的全局 CSS 是否命中同类元素（这里全局 `table{width:100%}` 与外层 margin 叠加），不能只验证“字符串正确写入”。

### 再跟进：`width:96%` 仍溢出——auto 表格布局不遵守指定宽度

用户反馈：改成 `margin:5px 2%; width:96%` 后，子表格右侧**仍然**跑到父表格外。

- **真正的根因（比 104% 叠加更深一层）**：`<table>` 默认是 **auto 布局**，其 used width = `max(指定 width, min-content width)`——表格永远不肯缩到内容所需最小宽度以下。所以 `width:96%` 只是个**下限**：当子表格的列数/文本使 min-content 超过父单元格的 96% 时，表格实际宽度撑到 min-content（>96%），再叠加 `margin-left:2%`，右边界 = 2% + min-content 直接越过父单元格右边。`box-sizing:border-box` 只把 padding/border 计入 width，同样管不住 min-content 撑宽。
- **修复**：`NESTED_TABLE_STYLE` 再加 `table-layout:fixed`。固定布局下有 used width 强制等于指定的 `96%`（内容在单元格内换行/裁切，而非把表壳撑宽），于是 `2% + 96% + 2% = 100%` 真正成立，右边界落在父单元格内容右沿内，任意嵌套层自适应。
- **验证**：`JsonWriterNestedTableHtmlTest` 5 处断言更新为 `<table style="margin:5px 2%; width:96%; table-layout:fixed">`，33 个测试通过（BUILD SUCCESS）；`.agents\check_nested_style.ps1` 同步正则后重跑 DebugSample1，当前解析文件内嵌子表格 4/4 命中新样式、无裸 `<table>`。
- **教训**：用 `width` 约束 `<table>` 宽度时，auto 布局不保证遵守——必须配 `table-layout:fixed` 才能让显式 width 生效；仅靠调小百分比 + box-sizing 不足以压住 min-content 撑宽。

## 再跟进：子表格上角标 `<sup>` 被转义成字面文本（未渲染为上标）

用户反馈：`docs/pdf/202609041788519783769009333.pdf` 第 3 页表格第 1 列第 2 行内最后一个子表格，其第 1 列第 2 行文本 `所得款項總額<sup>（附註）</sup>` 的上角标标签没渲染出来（显示成字面 `<sup>（附註）</sup>`）。

- **定位**：`TmpCellChunkProbe` 无关；直接对输出 JSON 统计（`.agents\check_sup.ps1`）——子表格内联 HTML 串里该处是 `所得款項總額&lt;sup&gt;（附註）&lt;/sup&gt;`（`&lt;`/`&gt;` 各 2、`&lt;sup&gt;` 1 处），而主表格外层网格里同样的角标则是原始 `<sup>`（`raw_sup` 48）。两者渲染差异直接指向嵌套表序列化路径。
- **根因**：`renderTableCellToHtml` 组装子表格单元格文本时，先经 `assembleGroupText`（其值由 `TextLineProcessor` 注入的 `<sup>/<sub>` 角标标记原样透传，见 `renderTextChunkValue`），随后 `body.append(escapeHtml(lineText))` 把 `<`/`>` 一律转义 → 角标标记变成字面 `&lt;sup&gt;`，浏览器就照字面显示。外层网格不受影响，是因为它的 `text` 数组原样写入 JSON，模板用 `v-html` 渲染，`<sup>` 得以生效。
- **修复**：新增 `escapeHtmlPreservingSupSub(s)` = `escapeHtml(s)` 后把 `&lt;sup&gt;/&lt;/sup&gt;/&lt;sub&gt;/&lt;/sub&gt;` 四个 token 还原为原始标签；`renderTableCellToHtml` 的文本追加改用它。只放行这四个精确 token，其他 HTML 字符仍转义（即使 PDF 文本里字面出现 `&lt;sup&gt;` 也会先变 `&amp;lt;sup&amp;gt;` 不匹配还原），不会注入任意标签。
- **验证**：新增用例 `superscriptMarkupInCellTextIsPreservedWhileOtherTagsAreEscaped`（`A<sup>1</sup><b>x</b>` → `A<sup>1</sup>&lt;b&gt;x&lt;/b&gt;`）；表格相关测试 34 个全通过。重跑 DebugSample1（当前解析 20260904），子表格单元格恢复为 `所得款項總額<sup>（附註）</sup>`，`&lt;`/`&gt;`/`&lt;sup&gt;` 均归 0，`raw_sup` 48→49。
- **教训**：向“会被前端 `v-html` 渲染的 HTML 片段”里转义文本时，要区分“用户数据里的尖括号”（必须转义）与“管线自己注入的布局标记”（如 `<sup>/<sub>`，应保留）；两者共用 `escapeHtml` 会把后者一并误伤。外层网格与嵌套子表两条文本路径的转义策略需保持一致。

## 关联记忆

- common_pitfalls_experience：`TableBorderRow.getCells()` 槽位重复引用导致嵌套表格 HTML 单元格重复输出
