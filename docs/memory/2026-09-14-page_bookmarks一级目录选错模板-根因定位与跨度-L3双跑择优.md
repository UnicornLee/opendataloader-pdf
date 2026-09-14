# 2026-09-14 — `page_bookmarks`：`202609121789113649510035537.pdf` 一级目录选错模板（完整定位过程 + 跨度判据 / L3 双跑择优修复）

## 任务背景（Goal）

用户报：用当前代码解析 `docs/pdf/202609121789113649510035537.pdf`（4 页董事会公告，**`catalog_bookmarks` / `self_bookmarks` 均为空 → 实际生效的是 `page_bookmarks`**）后：

- **一级目录**开头不是 `一 / 二 / 三 / …`，而是 `1、业务品种 … 6、业务授权`（阿拉伯数字）；
- **二级目录**不是 `（一）/（二）/ …`，唯一子节点（`（一）风险分析`、`（二）控制措施`）被挂在 `6、业务授权` 之下；
- 期望：一级 = `一、开展外汇套期保值业务概述 … 五、备查文件`，二级 = `（一）…/（二）…`。

用户要求：**先查真正原因并给方案，先不要改代码；不清楚要澄清**；可用 `org.opendataloader.pdf.DebugSample` 或 IntelliJ Debugger 复现。

> 与 `CatalogBookmarkProcessor` 的根本区别：`PageBookmarkProcessor` 是「**前缀模板（`TemplateKey`）分组 + 连续编号校验**」机制，选模板 = `selectTemplateForLevel`。

---

## 一、定位过程（从现象到根因）

### 1.1 第一步：JSON 物证收集（不猜，先看数据）

从 `tmp_output/202609121789113649510035537.json` 把全文候选按 `TemplateKey` 分组，得到 28 个候选、3 个模板组：

| 模板 | 条目 | 文本示例 | `source_type` | 字号 | x0 |
|---|---|---|---|---|---|
| `#、`/CHINESE | 5 | `一、开展外汇套期保值业务概述`、`二、审议程序`、`三、…风险分析及控制措施`、`四、会计政策及核算原则`、`五、备查文件` | **全部 heading** | 12.0 | 114.0 |
| `#`/ARABIC | 19 | 第 2 页 `1、业务品种 … 6、业务授权`（正文编号列表）、第 1 页长段落 1-3、第 3 页两组 1-4、第 4 页 1-2 | **全部 paragraph** | 12.0 | 114.0 |
| `（#）`/CHINESE | 4 | `（一）目的`、`（二）情况`（p1/p2）、`（一）风险分析`、`（二）控制措施`（p3） | paragraph | 12.0 | 110.9 |

→ 三个组**字号全是 12.0**、**清理后平均缩进也几乎相同（114.0）**，所以「字号」和「缩进」都分不出胜负，胜负只能由后面的判据决定。这一点是所有后续推论的起点。

### 1.2 第二步：Python 复刻算法做 what-if（先定位到排序，再验证 TOC 规则）

写 `tmp_output/analyze_pb_202609121789113649510035537.py`（含 `BookmarkConstant` 前缀表的 Python 版；注意中文数字拼法 11→"十一"）与 `tmp_output/sim_pb_*.py`（复刻 `cleanCandidates` / `cleanCandidatesLocal` / `selectTemplateForLevel`，可用 `python sim_....py [组序号] [notoc]` 强制 L1 模板并旁路 TOC 丢弃规则）。

发现两个可疑点：

1. **排序**里 `count desc` 的位置太靠前：`#`/ARABIC 在第 2 页的 `1、…6、` 是 6 条，真脊线 `#、`/CHINESE 只有 5 条 → 6 > 5 就赢了；
2. **真脊线 `#、`/CHINESE 在 `cleanCandidates` 里被 `isTocLikeGroup` 整体丢弃**（5 条本该全在结果里，实际索引列表是空的）。

### 1.3 第三步：Java 侧插桩（真实代码 + 纯 JSON 路径）验证

> 手法（用完已还原）：在 `PageBookmarkProcessor` 加 `DEBUG_BOOKMARK = "1".equals(System.getProperty("dbgBookmark"))` 的临时打印；加临时类 `DebugRebuildTmp` 调 **`OpenDataLoaderPDF.rebuildBookmarks`**（纯 JSON 路径、不触发 OCR，适合离线排查）跑 JSON 复制件。查完 `git checkout -- PageBookmarkProcessor.java` + 删临时类 + 重新 compile。

真实日志（关键两行）：

```
[DBG cleanCandidates] tpl=#、/CHINESE -> DROPPED isTocLikeGroup (crossPage[4(p3,id13,last=13)~5(p4,id2,id1text=true)])
[DBG select] WINNER=#/ARABIC n=6 (groups: #、/CHINESE=5, #/ARABIC=6, （#）/CHINESE=2)
```

**旁路实验**：即使临时关掉 TOC 丢弃规则（`#、`/CHINESE 拿到 n=5），`WINNER` 仍然是 `#/ARABIC n=6` → 证明"条目数 6 > 5"是**决定性**原因，TOC 误杀是**放大**原因。

### 1.4 根因（三个缺陷叠加，按重要性排序）

1. **【决定性】L1 排序「条目数最多者胜」压过了一切结构信号。**
   三个模板字号相同、缩进相同 → 直接进入 `count desc` → 正文的 `1、…6、`（6 条）击败真脊线（5 条）。
   ```java
   validGroups.sort((a, b) -> {
       int fontCmp = Double.compare(b.averageFontSize, a.averageFontSize);
       ...
       int countCmp = Integer.compare(b.candidates.size(), a.candidates.size());
   ```
2. **【放大】`isTocLikeGroup` 把真脊线当成"跨页目录桥"整组删掉。**
   命中的是它对 `size ≤ 5` 的「单对相邻」判定：`四、` 恰好是第 3 页最后一个 item（`relatedId == pageLastId`），`五、` 恰好是第 4 页第 2 个 item 且该页第 1 个 item 是文本 → 被判成"跨页目录残留"。所以**即使把条目数判据挪后，L1 也找不到中文脊线**。
3. **【连锁】二级目录错挂。** L1 锚点变成第 2 页的 6 条后，`6、业务授权` 的子区间 = 「其后 … 文档末」，区间内唯一能通过 `cleanCandidatesLocal`（Step 4.8 要求首值 = 1）的只有第 3 页的 `（一）风险分析/（二）控制措施`；而 `（一）目的/（二）情况` 位于第一个 L1 锚点**之前** → 落在所有子区间之外，被完全丢弃。

### 1.5 同时排除的假设（都有数据支撑）

- **不是句号过滤器**：`size < MIN_CANDIDATES_FOR_PERIOD_FILTER(=3)` 时不运行；
- **不是 `styleDepth`**：`styleDepth` 排在 `count` 之后，只改它没用；且把中文顿号列为更浅会与 `第#章`(depth=0) 冲突；
- **L1 套用 L2 的"密度优先"会更差**（反面验证）：`（#）/CHINESE` 2 条/2 页 = 1.0 < `#、/CHINESE` 5 条/4 页 = 1.25 → 会选中 `（一）（二）`，不可取；
- **"L1 标题优先（heading 优先）"能单独修好本例**（5 条中文脊线全是 `source_type=heading`，其余 26 条全是 paragraph），但属于另一个维度的判据，最终用户选择了"跨度 / 双跑择优"路线。

---

## 二、用户确认的规则与实现

### 2.1 规则 1：跨页目录桥收紧（首行缩进）

用户口径：**跨页桥 = 代码里的 `isCrossPageAdjacent`；如果第二页的第一个段落/文本是多行、且第一行相对第二行有缩进，就不认为目录连续**；多行门槛**不**提高到 3 行；缩进判据 = `第一行 x0 − 第二行 x0 >= 10`；`relatedId == 1` 分支**不**覆盖。

数据前提核实（`tmp_output/dbg_first_text_lines.py`）：第 4 页第 1 个文本项 id=1 是 3 行，`line0 x0 = 114.0` / `line1 x0 = 90.0` → `diff = 24 ≥ 10` → `firstLineIndentedWrap = true` ✓。

实现：

- 新增常量 `FIRST_LINE_INDENT_THRESHOLD = 10.0`；
- `Candidate` 新增页级标记 `pageFirstTextIndentedWrap`（4 个构造器补参数）；
- 新增 `isFirstTextIndentedWrap(IObject)`（`CustomSemanticParagraph.getTextLines()` 前两行比 `getLeftX()`；`SemanticHeading` 只暴露首行 → 恒 false）与 `isFirstTextIndentedWrapJson(Map)`（比 JSON `content` 前两个 line map 的 `x0`）+ 辅助 `uncheckedMap()`；
- 4 个候选收集器（`collectCandidates` / `collectJsonCandidates` / 两个 attachment 收集器）都计算并传入该标记；
- `isCrossPageAdjacent` 收敛到 **`relatedId == 2` 子分支**（用户澄清后二次修正，`relatedId == 1` 保持原判据）：

```java
return b.pageIndex == a.pageIndex + 1
    && a.relatedId == a.pageLastId
    && (b.relatedId == 1
        || (b.relatedId == 2 && b.pageIdOneIsText
            && !b.pageFirstTextIndentedWrap));
```

- `maxConsecutiveRelatedIdRun` 的跨页桥同条件（`minId == 2 && idOneIsText && !firstTextIndentedWrap`），javadoc 同步。

效果：`[DBG cleanCandidates] tpl=#、/CHINESE -> KEPT len=5`（原先 `DROPPED isTocLikeGroup (crossPage[...])`）。**但 L1 结果仍未变**（`WINNER=#/ARABIC n=6`）→ 必须再解决"条目数 6 > 5"。

### 2.2 规则 2：条目数 → 跨度

规则 2 的原始口径是「判断条目数是不是最多时，要先把连续的目录去掉再比较；该过滤只用于条目数比较」。试实现（新增 `Group.comparisonCount`，比较处替换 `candidates.size()`）后语料变化太大（阈值 ≥2：**8 份**变化；≥3：**7 份**变化，好坏混杂）→ **已回退**，代码中不保留。

用户随后改口径：「关于规则2，改成过滤掉连续的目录后判断**跨度最大**而不是条数最多来选目录」。即：**不在清理后的集合上比条目数，而是比跨度**（`pageExtent = maxPageIndex − minPageIndex + 1`，基于 `cleanCandidates` / `cleanCandidatesLocal` **清理后**的候选）。

---

## 三、判据演进（A → E → 最终）

| 变体 | L1 | L2 | L3 | 语料变化份数 | 目标文档 | `202304181681731304971104` 的 `六、…` | 备注 |
|---|---|---|---|---|---|---|---|
| A | 跨度 → 条目数 | 同 | 条目数（不变） | 4 | ✓ | 仍错 | 影响最小 |
| B | 跨度 → 条目数 | 同 | **跨度 → 条目数** | 6 | ✓ | ✓ 修好 | 新增 `202304201681906512315204`、`202304271682510470028924` 变化 |
| C | 跨度 | 跨度 | 条目数（不变） | 4 | ✓ | 仍错 | 用户一度选 C |
| D | 完全删掉条目数 | 同 | 同 | 7 | ✓ | ✓ | ❌ `202609121789113649042076392` 的 L1 脊线 `一、…六、` 消失 |
| **E** | 跨度 → 条目数 | 跨度 → 条目数 | **条目数 → 跨度** | **5** | ✓ | ✓ | 两例都对，且不引入 B 的那处错误 |
| **最终（双跑择优）** | 跨度 → 条目数 | 同 | 条数跑 / 跨度跑各一次 → 择优 | **5**（与 E 完全相同） | ✓ | ✓ | 用户指定形式 |

`202304271682510470028924` 的 `五、重要会计政策及会计估计` 是选型的**分水岭样本**（`tmp_output/probe_l3_202304271682510470028924.py` → `probe2_utf8.txt`）：

| 组 | 条目 | 字号 | 平均 x0 | 跨度 | styleDepth | 内容 |
|---|---|---|---|---|---|---|
| `#`/ARABIC（正确） | **5**（全在 p117） | 10.56 | 56.64 | **1** | 0 | `1、遵循企业会计准则的声明`…`5、同一控制下企业合并的会计处理方法` |
| `（#）`/ARABIC（错误，"职工薪酬"下的子项） | 4（p128–p129） | 10.56 | 56.64 | **2** | 1 | `（1）短期薪酬的会计处理方法`…`（4）其他长期职工福利的会计处理方法` |

→ 两组字号/缩进完全相同，HEAD（条目数优先）靠 5 > 4 选对，变体 B 靠跨度 2 > 1 选错。这就是最终**不让跨度无条件压过条目数**的直接理由。

而 `202304181681731304971104` 的 `六、公司关于公司未来发展的讨论与分析`（`probe_l3_*.py`）是反向样本：两组**条目数打平（5 vs 5）**，只有跨度不同（5 vs 2），而 HEAD 在平局后落到 `styleDepth`（`1.`=0 比 `(一)`=1 更浅）→ 选错。**两个样本正好构成"条数优先要保留 + 平局时要用跨度"的完整约束**，这也是双跑择优准则 `(条目数, 跨度)` 的来源。

### 最终实现（用户答复"2"：双跑择优）

`selectTemplateForLevel` 末尾：

```java
        // L1/L2 rank on a single ordering (span ahead of count). L3 runs the
        // ranking twice — once as "most entries", once as "widest coverage" —
        // and then compares the two winners (see runOffLevel3).
        if (level >= 3 && validGroups.size() > 1) {
            return runOffLevel3(validGroups, level);
        }
        validGroups.sort(groupComparator(level, true));
        return validGroups.get(0).templateKey;
```

```java
    private static TemplateKey runOffLevel3(List<Group> validGroups, int level) {
        List<Group> spanOrdered = new ArrayList<>(validGroups);
        spanOrdered.sort(groupComparator(level, true));
        Group spanWinner = spanOrdered.get(0);

        validGroups.sort(groupComparator(level, false));
        Group countWinner = validGroups.get(0);

        if (spanWinner != countWinner) {
            int countCmp = Integer.compare(countWinner.candidates.size(), spanWinner.candidates.size());
            if (countCmp > 0) { return countWinner.templateKey; }
            if (countCmp < 0) { return spanWinner.templateKey; }
            int spanCmp = Integer.compare(countWinner.pageExtent, spanWinner.pageExtent);
            if (spanCmp < 0) { return spanWinner.templateKey; }
        }
        return countWinner.templateKey;   // 两项都平局 → 保留条数跑赢家，保证确定性
    }
```

`groupComparator(int level, boolean spanFirst)`：

字号降序 → （L2：密度升序）→
- `level <= 2`：**跨度降序** → 条目数降序 → …
- `level >= 3 && spanFirst`（跨度跑）：**跨度降序** → 条目数降序 → …
- `level >= 3 && !spanFirst`（条数跑）：**条目数降序** → …
→ 编号风格层级（`styleDepth`）→ 缩进 → 首页 → 首个 topY。

> 双跑用 `new ArrayList<>(validGroups)` 副本排序，避免两个排序互相污染。
> 之所以**不用**"一次排序把两个指标串起来"，是因为那会失去"两个指标各自选出赢家再比"的语义（用户明确要的形态）；不过在本语料上两者结果**完全相同**（见下），所以当前实现可以理解为 E 的等价强化版，保留了以后调整择优准则的空间。

---

## 四、验证

### 4.1 单元测试

```bash
cd java/opendataloader-pdf-core            # 或仓库根 java/
mvn -o -q -DskipTests compile test-compile
mvn -o surefire:test -Dtest=PageBookmarkProcessorTest,CatalogBookmarkProcessorTest -DfailIfNoTests=false
```

- `PageBookmarkProcessorTest` **50/50** + `CatalogBookmarkProcessorTest` **3/3** = **53/53 全绿**（改前改后一致）。

### 4.2 全量语料回归（49~51 份 JSON，纯 JSON 路径）

方法：临时类 `DebugCorpusTmp`（把 `tmp_output/*.json` 逐份复制到 `tmp_output/corpus_scratch/` 后调 `rebuildBookmarks`，导出目录树）+ `tmp_output/diff_docs_nospace.py`（**忽略空白**逐行比对，输出 `changed docs N / M` + `DOC:` 列表）。跑完临时类已删除，仓库无调试代码。

结果（本次改动 vs HEAD，忽略空白）：脚本口径打印 `changed docs (whitespace-insensitive): 5 / 49`（`tmp_output/` 下共 51 份 JSON，2 份未产出目录树/被过滤）。

**5 份变化**，其余逐行一致：

| # | 文档 | 变化 | 评价 |
|---|---|---|---|
| 1 | `202609121789113649510035537`（本次目标） | 一级 `一、…五、`、二级 `（一）（二）`、L3 `1、…6、` | ✓ 修复 |
| 2 | `202303251679660111823147` | `10、存货` 补齐 `（1）…（5）` 子项 | ✓ 改善 |
| 3 | `202304281682603453761936` | `一、公司治理相关情况说明` 补齐 `1、…5、`；`一、股本变动情况` 改判 | ✓ 改善（后一处需人工确认） |
| 4 | `202304181681731304971104` | `六、公司关于公司未来发展的讨论与分析` 已修好（不在差异里）；仅 `一、环境信息情况` 子项改判 | 需人工确认 |
| 5 | `202304271682510470028924` | `五、重要会计政策及会计估计` 保持正确（不在差异里）；仅 `一、报告期内公司所处行业情况` 子项改判 | 需人工确认 |

**关键两例复算**（真实代码）：

- `六、…`：条数 5 vs 5 平 → 跨度 5 > 2 → 取跨度跑赢家 `(一)…(五)` ✓；
- `五、重要会计政策`：条数 5 > 4 → 取条数跑赢家 `1、遵循…` ✓。

**双跑 vs 变体 E：0 份差异**（`diff_docs_nospace.py corpus_E.txt corpus_runoff.txt` → `changed docs: 0 / 51`）→ 两种写法在本语料上等价，没有出现我预警过的分叉。

### 4.3 目标文档最终产物（真实代码验证）

```
一、开展外汇套期保值业务概述 [p1]
  （一）开展外汇套期保值业务的目的 [p1]
  （二）开展外汇套期保值业务的情况 [p2]
    1、业务品种 … 6、业务授权 [p2]        ← L3，符合文档真实层级
二、审议程序 [p2]
三、开展外汇套期保值业务的风险分析及控制措施 [p3]
  （一）风险分析 / （二）控制措施 [p3]
四、会计政策及核算原则 [p3]
五、备查文件 [p4]（无子节点）
```

---

## 五、关键决策与取舍

- **L1/L2 用"单次排序（跨度 → 条目数）"，L3 用"双跑择优"**：L1/L2 的区间长、脊线与正文列表的跨度差异明显，单次排序足够；L3 是短局部区间，跨度与条目数各有各的失效样本（见 §3 的两个 probe），因此把两个指标提升为"两个赢家之间的择优"。
- **平局时保留条数跑赢家**（而不是继续比 styleDepth/缩进）：避免在"双跑"之外再造一层隐式规则，保持确定性。
- **不做"L1 必须含 heading"**：语料里存在正文编号列表确实成为真目录的文档，heading 判据会误杀；本次改用跨度/双跑，不改候选来源。
- **规则 2 的"从条目数比较中剔除连续目录"被放弃**：语料代价过大（8 份 / 7 份变化，好坏混杂），改由"跨度"表达同一意图。
- **已知取舍/遗留**：变体 B（跨度一路优先）会把 `202304271682510470028924` 的 `五、重要会计政策及会计估计` 翻成 `（1）短期薪酬…`；最终版本在 L3 让条数跑赢了它，代价是 §4.2 第 4/5 份里各有一处二级子项改判（需人工对照 PDF 判定好坏）。
- **`202303251679660111823147` 等文档的"补齐子项"其实来自规则 1**（跨页桥放松），不是来自跨度判据 —— 用临时开关 `dbgNoIndentWrap` 单独关掉规则 1 做过归因实验。

---

## 六、待查 / 残留

1. **非确定性（与本次改动无关）**：同一份代码两次跑全量语料，**树结构完全一致**，但部分文档的**书签文本空格**不同（`1、遵循企业会计准则的声明` ↔ `1、 遵循企业会计准则的声明`、`达到了 80%` ↔ `达到了80%`、`第二节 总则` ↔ `第二节  总则`）。`SmartTextJoiner` 无状态，怀疑来自"上游给的书签来源 / 文本项不同"（择优选源）或按 identity hash 排序的集合；属**间歇性**（`corpus_indent10` vs `corpus_indent10_b` 完全一致）。→ **后续语料对比必须忽略空白或只比结构**。
2. §4.2 第 3/4/5 份中三处二级子项改判，需人工对照 PDF 判定。
3. 规则 1 的作用面（`isCrossPageAdjacent` 的 `relatedId == 2` 分支）是本次多数"补齐子项"的来源，若以后出现误判，优先回看这里。

---

## 七、相关文件 / 命令 / 调试产物

改动文件（`git diff --stat`）：

- `java/opendataloader-pdf-core/src/main/java/org/opendataloader/pdf/processors/PageBookmarkProcessor.java`（本次全部改动：`FIRST_LINE_INDENT_THRESHOLD`、`Candidate#pageFirstTextIndentedWrap`、`Group#pageExtent`、`isFirstTextIndentedWrap(IObject/Json)`、`isCrossPageAdjacent`、`maxConsecutiveRelatedIdRun`、`groupComparator`、`runOffLevel3`、`selectTemplateForLevel`）
- `java/opendataloader-pdf-core/src/main/java/org/opendataloader/pdf/DebugSample.java`（用户既有改动，非本次）

调试/分析产物（都在 `tmp_output/`，可随时删）：

- Python：`analyze_pb_202609121789113649510035537.py`、`sim_pb_202609121789113649510035537.py`、`count_variants.py`、`dbg_first_text_lines.py`、`probe_l3_202304181681731304971104.py`、`probe_l3_202304271682510470028924.py`、`diff_docs.py`、`diff_docs_nospace.py`、`to_utf8.py`、`check_determinism.py`、`check_source.py`
- 语料 dump：`corpus_before.txt`（HEAD）、`corpus_final.txt` / `corpus_finalB.txt`、`corpus_span*.txt`、`corpus_runoff.txt`（最终）、`ns_runoff_vs_HEAD_utf8.txt`、`ns_runoff_vs_E_utf8.txt` 等
- 目录树/日志：`docs_*.txt`、`probe_l3_utf8.txt`、`probe2_utf8.txt`、`corpus_scratch/`
- 临时 Java 类 `DebugCorpusTmp` / `DebugRebuildTmp` / `DebugRebuildTmp`：**均已删除**

复现命令：

```bash
cd java/opendataloader-pdf-core
mvn -o -q -DskipTests compile
# 单份：Idle 里跑 org.opendataloader.pdf.DebugSample（其解析目标即本 PDF，纯 JSON 路径用 rebuildBookmarks）
mvn -o exec:java -Dexec.mainClass=org.opendataloader.pdf.DebugSample -Dexec.classpathScope=runtime -Dfile.encoding=UTF-8
# 单测
mvn -o surefire:test -Dtest=PageBookmarkProcessorTest,CatalogBookmarkProcessorTest -DfailIfNoTests=false
```

## 邻近历史

- `2026-08-11-page-bookmarks-一级目录误丢与正文越位修复.md` — L1 把 `count desc` 提前，避免长正文压过章节标题。
- `2026-08-14-page-bookmarks-L2-template-density-criterion修复.md` — L2 引入 `pageSpan` / 密度判据（`Group.pageSpan` 的由来；本次 `pageExtent` 与它同源）。
- `2026-09-11-page-bookmarks子目录从1开始严格连续-含句号过滤双次评估与风格层级tie-break.md` — `styleDepth` 先验与"双跑择优"先例 `preferUnfilteredSelection`（本次 `runOffLevel3` 与它同构）。
- `2026-09-10-page-bookmarks的L1目录的孤儿子级修复.md` — `minorityContainsValueOne` 守门员。
