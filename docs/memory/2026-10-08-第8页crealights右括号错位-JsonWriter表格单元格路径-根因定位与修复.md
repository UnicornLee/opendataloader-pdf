# 2026-10-08 — 第8页表格 `www.crealights.com` 后右括号 `)` 跑到句末：JsonWriter 表格单元格路径根因定位与修复

> 样本：`docs/pdf/20260507AN202606291826520711.pdf`（港股 IPO 草案，繁体 + `[編纂]` 占位符密排）
> 现象页：**第 8 页**，有边框表格（lattice_table）单元格内免责声明段落
> 缺陷位置：`data[7].items[?].content[0][0].text[2]`（单元格内一个 1×1 文本格）
> 涉及文件（本次会话改动的生产文件）：
>
> - **新增** `java/.../pdf/utils/ReadingOrderSortUtils.java`（leftX 聚类 + 簇内保流顺序的共享实现）
> - `java/.../pdf/processors/TextLineProcessor.java`（行内 chunk 排序改走共享工具 + 删除临时探针）
> - `java/.../pdf/json/JsonWriter.java`（表格单元格取回：删行内严格 leftX 排序 + 删逐 chunk topY 预排序）
> - `java/.../pdf/json/JsonWriterTableCellGroupingTest.java`（新增 `narrowBracketIsKeptBeforeWideCjkRunOnTheSameVisualLine`）
>
> 状态：**已用真实 PDF 第 8 页复现并验证修复；单测通过；已确认无关测试失败为环境基线。未 git 提交。**
> 本文与 `2026-10-08-CJK幻影连续空格-严格leftX排序错位-根因定位与修复.md` 同源（同一缺陷在表格单元格路径的副本），
> 但那是第 2 页 `[編纂]` 幻影空格场景；本文是第 8 页 `crealights` 右括号错位场景，
> 且记录了"只改 flush 排序仍不生效 → 发现逐 chunk topY 预排序才是隐藏元凶"的关键弯路。

---

## 1. 现象

用户报告：第 8 页表格里最后一句话的右括号 `)` 位置不对——解析结果中 `)` 在句子最末尾，
实际应在网址 `www.crealights.com` 后面。

修复前解析输出（JSON 单元格 `text[2]` 末段）：

```
…加以依賴。我們的網站(www.crealights.com所載資料並不構成本文件的一部分。)
                                                        ↑ ")" 被甩到句末，且 URL 与 ) 之间丢了右括号
```

期望（也是修复后）：

```
…加以依賴。我們的網站(www.crealights.com)所載資料並不構成本文件的一部分。
```

---

## 2. 结论速览（TL;DR）

1. **上游 `TextLineProcessor` 的段落文本顺序是正确的**（`)` 已在 `所載` 之前）。缺陷只在
   `JsonWriter` 组装 **lattice_table 单元格文本**的那条独立重组路径里。
2. **两个错位来源叠加**，缺一不可才能修好：
   - `groupChunksByLine` 之前对扁平化后的 chunk **逐个按 `topY` 降序预排序**；同一视觉行上
     字高不同的窄 `)` 与宽 CJK 串 `所載…` topY 不相等，预排序把 `所載`（topY 更大）排到了 `)` 前面，
     **破坏了 flatten 本已正确的流顺序**。
   - 行内 `flushGroupSortedByLeftX` 用 **严格 `leftX` 升序**再排一次：`)` 的 leftX(279.204)
     略大于 `所載…` chunk 的 leftX(278.476)（全角 CJK em box 左沿系统性偏左），于是 `)` 被排到最后。
3. **只改其一无效**：第一次只把 flush 换成聚类排序，第 8 页仍是 `)` 在句末——因为聚类"簇内保流顺序"
   依赖输入是真实流顺序，而逐 chunk topY 预排序已把流顺序打乱，簇内保住的是"错顺序"。
   **必须同时移除逐 chunk topY 预排序**，改为对**整个行组**按 `getGroupMaxTopY` 降序排（重排行、不重排行内 chunk）。
4. **间隙空格无需改公式**：排序正确后 `)` 与 `所載` 邻接间隙变负，`assembleGroupText` 现有
   `getSpaceStr` + `isChineseAdjacent` 自然不再插幻影空格。**禁止**在此加 `coveredRightX` 兜底
   （会吞表格真实列间隙造成数字粘连，见同源记忆第 6 节）。

---

## 3. 完整定位过程（从问题到根因）

### 3.1 复现与取证（DebugSample / 探针）

- 用户指定可用 `org.opendataloader.pdf.DebugSample` 解析调试，目标即该 PDF。
- OCR 服务 `http://192.168.1.97:8088` 本机不可达（`Test-NetConnection` False），但该 PDF 有完整文本层，
  paddle 失败时仍产出 JSON，缺陷不依赖 OCR 成功 → 可离线复现。
- `config.setPages("8")` 实际未把处理限制到第 8 页（探针跑出 423 页），只作观察，非阻塞。

### 3.2 用 pdfplumber 拿到第 8 页真实几何

目标句在 `top≈247`（目录页免责声明），字符级 x0：

```
"(" x0=183.2   www.crealights.com(HGARCP bold) x0=186.9 x1=277.4
")" x0=275.1   所載 x0=278.5     —— 均在同一行 top≈247
```

### 3.3 加临时探针确认"上游是对的、锅在 JsonWriter"

在 `TextLineProcessor` 临时插了一段 `System.getProperty("odl.probe")` 守护的 TextLine/chunk 转储，
并建 `ProbeCrealights` 走完整 API。探针结论：

- **`TextLineProcessor` 产出的 TextLine 顺序正确**（`)` 在 `所載` 前），排除段落路径；
- 无 hyperlink 注解（排除链接拆字假设）；
- 关键 chunk bbox（veraPDF 坐标，topY 反转）：`)` L=279.204 R=281.451；`所載…一部分。` L=278.476 R=456.914。
  → **`)` 的 leftX 比 `所載` 的 leftX 大 0.728pt**，正是严格 leftX 排序会把 `)` 排到 `所載` 之后的根因量级。

> 该探针块与 `ProbeCrealights.java` 属临时诊断，修复后已删除还原。

### 3.4 定位到 JsonWriter 单元格重组路径

`JsonWriter.writeToCustomJson` 里 lattice_table 单元格文本不是直接用已排好序的段落字符串，而是
`flattenCellContents → (逐 chunk topY 预排序) → groupChunksByLine → flushGroupSortedByLeftX → assembleGroupText`
**重新组装**。两条排序逻辑都只认几何坐标，未复用 `TextLineProcessor.sortChunksByReadingOrder` 的
"leftX 聚类 + 流顺序"修复——属同源缺陷的第二份副本。

---

## 4. 根因

| 层 | 事实 | 定性 |
|---|---|---|
| 单元格行内排序 | `flushGroupSortedByLeftX`：`Comparator.comparingDouble(IObject::getLeftX)` 严格全序，无容差 | **代码缺陷**：窄字形被全角 CJK em box 左沿抢位 |
| 单元格行分组前置 | `cellItemContents.sort(Comparator.comparingDouble(IObject::getTopY).reversed())` 逐 chunk 排 topY | **代码缺陷**：同行不同字高字形被打乱流顺序，使后续"簇内保流顺序"失效 |
| 触发量级 | `)` 与 `所載` leftX 差 0.728pt；全角 CJK em 左沿系统性偏左（字体度量特性） | PDF 字体度量特性 |

> 与第 2 页 `[編纂]` 幻影空格是**同一根因家族**，差别在：本例偏差 0.728pt（需字号≥约9.1 才落入容差）且发生在表格单元格路径。

---

## 5. 修复方案（方案 A + 公共工具，已获用户批准）

| 动作 | 说明 |
|---|---|
| 新增 `ReadingOrderSortUtils.sortByReadingOrder(List<T>, ToDoubleFunction<T> leftXGetter, double fontSize)` | 第 2 页场景已验证的聚类算法**逐字搬入并泛型化**；常量 `X_TIE_MIN=0.5` / `X_TIE_FACTOR=0.08`，容差 `max(0.5, 0.08×字号)`，单调 key `cluster×count+streamIndex`。供两处共用，防副本重新长出 |
| `TextLineProcessor.sortChunksByReadingOrder` 改薄包装 | 转发共享工具，传 `chunk.getBoundingBox().getLeftX()`；删本地常量与不再用的 `Arrays`/`Comparator` 导入；**同时删除临时探针块** |
| `JsonWriter`：**删除逐 chunk topY 预排序** | 改为分组后 `groups.sort(Comparator.comparingDouble(JsonWriter::getGroupMaxTopY).reversed())`——重排"行"而非重排"chunk"，既保竖向顺序又不破坏行内流顺序 |
| `flushGroupSortedByLeftX` → `flushGroupInReadingOrder` | 行内改用共享工具；字号取行组内 `TextChunk` 的**最大字号**（`getGroupFontSize`），使容差足以把 `)` 与 `所載`(Δ0.728) 并入同簇并按流顺序摆正 |
| `assembleGroupText` **不改间隙公式** | 排序正确后邻接间隙自然为负；遵守同源记忆"禁止 coveredRightX 兜底"的教训（即用户第 3 点"同步对齐"的结论：对齐靠统一排序实现，不靠兜底） |

新增单测 `JsonWriterTableCellGroupingTest.narrowBracketIsKeptBeforeWideCjkRunOnTheSameVisualLine`：
以真实几何构造同行两 chunk——`)` 流顺序在前但 leftX(279.204)>CJK(278.476)、且 CJK chunk topY(252)>`)`(250)
（专门触发"旧预排序 + 旧严格排序"双重错位），断言输出 `")所載資料並不構成本文件的一部分。"`。

---

## 6. 关键弯路（务必记录）

**第一次只改 flush 排序 → 第 8 页仍失败。** 重新编译跑探针，`text[2]` 依旧 `…www.crealights.com所載…一部分。)`。
复盘才意识到：`flushGroupInReadingOrder`（聚类"簇内保流顺序"）的前提是**输入列表为真实流顺序**，
而 `groupChunksByLine` 上游那句 `cellItemContents.sort(topY desc)` 已把同视觉行的
`)`(topY 低) 与 `所載`(topY 高) **按 topY 拆序**，喂给聚类的"流顺序"本身就是错的。
**教训**：一个"保持输入相对顺序"的算法，其正确性完全依赖输入顺序；改造顺序相关逻辑时，
必须把**从数据产出到消费的整条重排链**（这里是 flatten→topY 预排→leftX 排）都审一遍，
而不是只盯着最内层那一次排序。移除逐 chunk topY 预排序、改按行组 topY 排序后，第 8 页 `)` 归位。

---

## 7. 验证

### 7.1 真实 PDF（第 8 页 crealights）

探针跑该 PDF，扫描 JSON：

```
/data[7]/items[2]/content[0][0]/text[2]
…加以依賴。我們的網站(www.crealights.com)所載資料並不構成本文件的一部分。   ✅ ")" 归位、句末恢复"。"、无幻影空格
```

### 7.2 单测

- `JsonWriterTableCellGroupingTest`：2/2 绿（原 `A / B C` 分组用例仍通过，证明行组 topY 排序未破坏竖向分组）。
- `TextLineProcessorTest`：5/5 绿（委托共享工具后行为等价）。
- 定向回归 `JsonWriter*`、`MarkdownTable*`、`TableBorderProcessorTest`、`TextLineProcessorTest` 全绿。

### 7.3 环境基线核对（重要）

`PagesOptionIntegrationTest` 有 10 项失败。用 `git stash push` **仅**回退本次两处生产文件
（`JsonWriter.java`、`TextLineProcessor.java`）后重跑，基线**同样失败且信息完全一致**（Tests run:12, Failures:10），
确认与本次改动无关（其判据是"整页 kids 内容有无"，非表格单元格文本）。随后 `git stash pop` 还原，重新编译通过。

---

## 8. 经验教训

1. **"保持输入顺序"的算法要先确认输入顺序是对的。** 聚类/稳定排序只在输入已是目标流顺序时才成立；
   若上游有任何按别的几何量做的重排，"流顺序"就被污染，看似修了排序实则喂错数据。
2. **同一缺陷常在多处有同构副本。** 段落路径修好后要 `grep` 全仓
   `comparingDouble(.*get(LeftX|TopY)` 找同类严格几何排序，逐处判定是否需要容差聚类；
   收敛到单一 `ReadingOrderSortUtils` 是防副本再长出的正解。
3. **修排序不碰测距。** 幻影空格/错位的正解是排序；在 `assembleGroupText`/`getText`/`getTextLineWithSpaces`
   加 `coveredRightX` 兜底会吞表格真实列间隙，属被证伪的反面做法（同源记忆第 6 节有完整回退记录）。
4. **区分"我的失败"与"环境失败"用基线复跑。** 集成测试大面积失败时，先 `git stash` 生产改动复跑同一测试，
   基线一致才敢归因为环境类；本次还额外用只 stash 生产文件（保留新增/测试文件）把范围收窄到本次改动。
5. **上游对、下游错**：JSON 单元格文本与处理器段落文本是同一批 chunk 的两条重组路径，二者必须共用同一阅读顺序实现，
   否则单元格文本会与段落文本不一致（本次即单元格路径落后于段落路径一轮修复）。

---

## 9. 遗留

1. **全文级表格单元格回归未跑**：本次只在第 8 页 crealights 场景 + 定向单测验证；
   `JsonWriter` 单元格路径的改动影响**所有含 lattice_table 的文档**（删除逐 chunk topY 预排序、改行组排序），
   建议对 `tmp_output/corpus_*` 语料做 before/after，重点看：破碎括号、粘连千分位数字 `\d,\d{3}\d,\d{3}`、
   2+ 连续空格总数、JSON 条目总数守恒。
2. **容差样本驱动**：0.5pt / 0.08×字号；本例 leftX 偏差 0.728pt 需 `fontSize≥9.1` 才落入容差。
   `getGroupFontSize` 取行内最大字号以放大容差；若极小字号脚注出现同类紧邻字形对偏差 > 容差需上调。
3. **`config.setPages("8")` 未生效**（探针仍跑全 423 页）：与本次缺陷无关，但值得后续查页面过滤路径。
4. **未 git 提交**：工作区含
   `ReadingOrderSortUtils.java`（**untracked，别忘 `git add`**）、`TextLineProcessor.java`、`JsonWriter.java`、
   `JsonWriterTableCellGroupingTest.java`；`TextLineProcessorTest.java`、`DebugSample.java`、`samples/json/lorem.*`
   为工作区既有改动，非本次产生。

---

## 10. 关联记忆

- `docs/memory/2026-10-08-CJK幻影连续空格-严格leftX排序错位-根因定位与修复.md`
  —— **同一根因家族的主记录**：聚类算法数学（0.273pt 偏差、阈值推导、量化分桶失败、死区比较器禁用、
  coveredRightX 三处兜底全部回退）与 `ReadingOrderSortUtils` 抽共享的完整背景都在那篇；本文是其
  "阶段二：表格单元格路径"在第 8 页 crealights 场景的落地验证与"逐 chunk topY 预排序"弯路的补充。
- `docs/memory/2026-09-01-JsonWriter表格单元格内容提取重构与中文间距优化.md`
  —— `getSpaceStr` + `isChineseAdjacent` 间距机制来源，理解本次"间隙变负即消失"需先看这篇。
- `docs/memory/2026-09-08-JsonWriter表格单元格文本行合并逻辑优化与代码review.md`
  —— `groupChunksByLine` / 行合并 / `MAX_CELL_LINE_RIGHT_GAP` 判据来源，本次删逐 chunk topY 预排序、
  改行组 topY 排序直接触及这套行分组逻辑。
