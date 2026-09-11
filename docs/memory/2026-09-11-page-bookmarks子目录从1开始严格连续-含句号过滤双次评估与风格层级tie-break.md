# 2026-09-11 — page_bookmarks：子目录必须从 1 开始且严格连续（含句号过滤「双次评估」与编号风格层级 tie-break）

## 任务背景

两起同类投诉，都是「某个一级目录只剩 1 个子节点，且该子节点以『2』开头」：

1. `docs/pdf/202609121789114545357081630.pdf`（深圳美丽生态 诉讼/仲裁公告，3 页）
   一级「一、累计诉讼、仲裁事项的基本情况」只挂 1 个子节点：
   `2、以上案件涉及金额与最终实际执行金额可能存在一定差异。`
2. `docs/pdf/202609121789113649042076392.pdf`（凤凰航运 2026 年第二次临时股东会通知，6 页）
   **最后一个**一级「附件2：」只挂 1 个子节点：
   `2、授权委托书剪报、复印或按以上格式自制均有效，单位委托须加盖单位公章。`

两份文件的 `catalog_bookmarks` / `self_bookmarks` 均为空 → 实际生效的是 **`page_bookmarks`**。

与 catalog 侧最根本的区别：`PageBookmarkProcessor` 是**「前缀模板分组 + 连续编号校验」**机制，**完全不看缩进**。

---

## 一、文档① 的定位（`202609121789114545357081630`）

### 1.1 候选全集（全文只有 3 页，候选共 5 条）

| 模板 | value | 文本 | 来源 |
|---|---|---|---|
| `#、`/CHINESE | 1 | `一、累计诉讼、仲裁事项的基本情况` | heading，fs 12.0，P1 |
| `#`/ **ARABIC** | **2** | `2、以上案件涉及金额与最终实际执行金额可能存在一定差异。` | **paragraph**，fs **10.45**，P2 id=2，x0=111.12，**以「。」结尾** |
| `#、`/CHINESE | 2 | `二、已披露累计诉讼、仲裁进展情况` | heading，fs 12.0，P2 |
| `#、`/CHINESE | 3 | `三、其他尚未披露的诉讼、仲裁事项` | heading，fs 12.0，P2 |
| `#、`/CHINESE | 4 | `四、本次公告的诉讼、仲裁事项对公司本期利润或期后利润的可能影响` | heading，fs 12.0，P2 |

同一页还有一条关键正文（**不是候选**）：

```
P2 id=1  paragraph  x0=90.12  fs=10.45  | 注：1、本表仅列示涉案金额为人民币500万元以上的案件；…
```

### 1.2 链路逐环

1. `matchPrefix` + `hasValidBookmarkSuffix` 允许「**裸阿拉伯数字 + 、**」，于是正文句 `2、以上…` 被收为候选：模板 `#`/ARABIC、value=2。
2. **它的同组 value=1 就是那句 `注：1、本表…`，而该行以「注：」开头，永远不会成为候选**——`matchPrefix` 的唯一准入是 `text.startsWith(pattern.constant)`，而 `BookmarkConstant` 里**没有任何「注」相关常量**。于是该模板**全局只剩一个孤立 value=2**。
3. L1 选择：`isValidGroup` 对「单值」要求 `value == 1` → `#`/ARABIC 被判无效（正确）；L1 主干正确选中 `#、`/CHINESE → 一、二、三、四。
4. **但** `一、` 的子区间（阅读序 index 1..1）**恰好只包住这条孤立候选**；L2 的 `selectTemplateForLevel` 允许「局部连续段可从任意值开始」（本意是支持跨父级续号）→ **已经在 L1 被否掉的模板在 L2 被「复活」**。
5. `cleanCandidatesLocal`(L2) 的三道拦截全部失效：
   - 句号过滤器需 `size >= MIN_CANDIDATES_FOR_PERIOD_FILTER(=3)`，此处 **size=1 不运行** → `periodFilterApplied=false`；
   - 旧 Step 4.6「丢弃 value≠1 的孤立单链」**被 `periodFilterApplied` 门控** → 跳过；
   - `isTocLikeGroup` 对 `size < 2` 直接返回 false。
   → 泄漏为 `一、` 的唯一子节点。

---

## 二、文档② 的定位（`202609121789113649042076392`）

### 2.1 输出实况（修复前）

```
L1 一、召开会议的基本情况 / 二、会议审议事项 / 三、会议登记方法
L1 四、参加网络投票的具体操作流程 / 五、其他事项 / 六、备查文件
  L2 1.公司第九届董事会第二十九次会议决议
L1 附件1：  →  L2 1、互联网投票系统开始投票的时间为…
L1 附件2：  →  L2 2、授权委托书剪报、复印或按以上格式自制均有效，单位委托须加盖单位公章。
```

`附件1：` / `附件2：` 是**附件锚点**：某页的第一个 paragraph/heading 以「附件」开头且长度 ≤ 20 → 使用 `ATTACHMENT_TEMPLATE_KEY` 哨兵模板、在 L1 参与锚点列表（`isAttachment`）。

### 2.2 机制一：`附件2：`（用户所问的「最后一个一级」）

- 第 6 页位于 `附件2：` 之后、能成为候选的**只有 1 条**：`2、授权委托书剪报…`（P6 id=11，paragraph）。
- 它的 value=1 兄弟是 `注：1、如欲投票同意议案，请在同意栏内相应空格内打"√"…`（P6 id=10）——**同样因以「注：」开头而永不可捕获**（与文档①同一机制）。
- 于是该父区间只剩孤立 value=2；句号过滤器 `size=1 < 3` 不运行 → 旧 Step 4.6 被 `periodFilterApplied` 门控跳过 → 漏出。

### 2.3 机制二：`附件1：` 也只有 1 个子节点，但原因不同

在 `extractLevel` / `selectTemplateForLevel` 临时插桩（打印 range、组统计、锚点）后拿到真实数据。range=[31,43] 处的候选组：

| 模板组 | count | leftX | pageSpan |
|---|---|---|---|
| `#`/CHINESE（`一．/二./三.`） | 3 | 112.04 | 1 |
| `（#）`/ARABIC | 3 | 104.0 | 1 |
| `#`/ARABIC | **1** | 90.0 | 1 |

- L2 选择里有一条「**密度优先**」（`pageSpan` 归一化的 sparsity，越小越优先；本意是「每节一条更像 L2」）→ **count=1 的退化组胜过了两个正常的 count=3 组**。
- 那个 count=1 又是**句号过滤器**造成的：区间 7 条候选中 6 条以「。」结尾（ratio=0.857 > 0.8），**唯一的例外**是跨页续行 `2、股东通过互联网投票系统进行网络投票，需按照《深圳证券交易所投资`（value=2，句子在下一页才结束，所以本行没有「。」）。它被当作「少数派」剔除 → 链 `[1,2,3]` 被**打断**成 `[1]` 与 `[3]` → `[3]` 又不从 1 起被丢弃 → 只剩 `[1]` → 成为唯一子节点。

> 插桩在查完后立即移除并恢复源文件（恢复后 52/52 单测仍绿），仓库中未留调试代码。

### 2.4 顺带核实：句号过滤器到底在哪一侧（回答「是不是只改了 catalog」）

git 证据：

| 提交 | 内容 | 位置 |
|---|---|---|
| `00d2e5d` | 引入句号一致性过滤器 + `MIN_CANDIDATES_FOR_PERIOD_FILTER = 3` | `PageBookmarkProcessor` |
| `b19b126`（2026-09-10） | 加入 `minorityContainsValueOne`（保住 value=1 锚点）+ `periodFilterApplied` | `PageBookmarkProcessor` |

**两者都只在 `PageBookmarkProcessor`（page_bookmarks）；`CatalogBookmarkProcessor` 里根本没有句号过滤器**（grep 只命中无关中文注释）。所以「只改了 catalog」不成立，恰好相反。

另外核实：`size < 3` 时过滤器本就不运行，即「**只有 1 条候选就不过滤**」一直是现状——这恰好证明 `附件2：` 的泄漏**不可能**来自句号过滤器（它压根没跑），元凶是 `periodFilterApplied` 门控下的旧 Step 4.6。

### 2.5 顺带核实：「注：」行的性质，以及语料规模

- 已核实：`注：…` 行**永远不会被识别为目录**（无「注」常量 + `matchPrefix` 前缀匹配）。
- 语料扫描（`tmp_output` 下 57 个 JSON，共 213 个「注」开头段落）：

| 形式 | 数量 | 说明 |
|---|---|---|
| **内联式** `注：1、…` → 后续编号行 | 19 | value=1 粘在「注：」上不可捕获 → 该模板全文无法到达 1 → 会被下面的规则整体丢弃（符合预期） |
| **分体式** `注：…`（未带编号）→ 后续编号行 | 36 | ⚠️ 其中**绝大多数目标其实是真标题**，例如 `58、外币货币性项目`、`54、营业外支出`、`56、库存股`、`2、合并利润表主要数据`、`1、工艺流程`、`（2） 合并成本及商誉` |

→ 结论：**不能**做「凡在『注：』之后一律排除」的规则，否则会误删大量正常目录。

---

## 三、规则演进与改动清单

### 3.1 第一轮：模板级全局校验（针对文档①）

在 `buildBookmarksFromCandidates` 排序后、`extractLevel` 之前新增 `discardTemplatesNotStartingAtOne(candidates)`：按 `templateKey` 汇总全文 value，**凡从未到达 value=1 的模板整体丢弃**（attachment 哨兵模板不参与）。

**两次试错记录（很重要，避免以后重复踩坑）**：

1. 先按「**每层每个父区间都必须从 1 重开**」实现 → 破坏 `testThreeLevelBookmarks`（`第2章`→`第3节`）与 `testTopLevelSelectsSingleTemplate`（`第2章`→`二、D`）。当时判断「跨父级续号是合法结构」，于是回退。
2. 再按「**全文无缺口才有效**」实现 → 破坏 `testLonelyNonOneNotMerged`（`{1,2,5}` 应产出 `[一、A, 二、B]`，缺口应由「最宽严格连续链」隔离，而不是把整个模板丢弃）。于是放宽为「**只要求到达 1**」。

### 3.2 第二轮：父区间级「从 1 开始且严格连续」（用户明确要求）

用户先表述为「value 必须从 1 开始，且严格连续，适用所有模板」，我起初理解为**模板全局级**；随后用户澄清「**我想让父目录的子目录从 1 开始，且严格连续**」——即**每个父目录之下**都要重开。

实现（`cleanCandidatesLocal`）：

- 新增 **Step 4.8**（放在 Step 4.7 之后，使 `value=1` 孤儿仍可把 `[2..N]` 补成合法的 `[1..N]`）：

```java
// 链本身由 Step 2（+1 游程）与 Step 4（仅当 prev.max+1 == group.min 才拼接）保证严格连续，
// 所以「首值 == 1」等价于「从 1 开始且严格连续」。
chains.removeIf(chain -> chain.isEmpty()
    || chain.get(0).isEmpty()
    || chain.get(0).get(0).value != 1);
```

- 同时**删除**被 Step 4.8 完全包含、且门控已失效的旧 Step 4.6，以及随之无用的 `periodFilterApplied` 变量（Step 1.5 简化为 `sorted = applyPeriodEndFilter(sorted)`）。旧 Step 4.6 的注释本身就在说「只有句号过滤器生效时才丢弃孤立单链」——正是 `附件2：` 漏出的直接原因。
- L1 路径不变（L1 本来就由 `isValidGroup` + `trimContiguousSection` 保证从 1、无缺口）。
- 单测同步：
  - `testThreeLevelBookmarks` / `testTopLevelSelectsSingleTemplate` 的**数据**改为「每个父目录从 1 重新编号」（如 `第2节→第1条 条款三`、`第2章→第1节 分则一般`、`二、D→一、D`），保留各自原本的测试意图（三级嵌套 / 顶层单选模板）；
  - 新增 `testChildNumberingMustRestartAtOneUnderEachParent`，显式钉住新规则（`第2章` 下的 `第3节` 续接上一章编号 → 必须被丢弃）。

### 3.3 第三轮：句号过滤器改为「双次评估、择优」（用户方案）

用户原话：「**能不能过滤前选出子目录，过滤后再选出子目录，这两个子目录选一个更合适的**」——即不要在候选集上做单次破坏性的过滤。

实现：

- `cleanCandidatesLocal` 与 `selectTemplateForLevel` 各加一个 `boolean applyPeriodFilter` 重载；**旧签名委托为 `true`**，所以 `cleanedIndicesOf`、`extractChildrenForAnchor` 等其它调用点行为不变。Step 1.5 门控变为 `applyPeriodFilter && level >= 2 && size >= 3`。
- `extractLevel`：把原先内联的「选模板 → 过滤候选 → 清洗 → 映射索引」抽成助手 `childAnchorIndices(...)`；L2+ 再以 `applyPeriodFilter = false` 跑一遍 `selectTemplateForLevel` + `childAnchorIndices`，由新准则择优。**空值检查移到双跑之后**，因此「启用过滤跑到空、禁用过滤有结果」也能被救回。
- **择优准则**：

```java
private static boolean preferUnfilteredSelection(List<Integer> unfiltered, List<Integer> filtered) {
    return unfiltered.size() > filtered.size() && filtered.size() <= 1;
}
```

默认**保留「启用过滤」的结果**（守住过滤器剔除正文残留的本职）；**仅当它塌缩到 ≤1 个子节点、而禁用过滤能得到更长的序列时才改用后者**——这正是「过滤器打断链、留下退化单条」的签名。

### 3.4 第四轮：并列 tie-break 增加「编号风格层级」先验

- 原因：`（1）/（2）/（3）` 与 `一．/二./三.` 在 font(12.0) / L2 密度(3.0) / count(3) 上**全部并列**，原先只靠 `leftX` 平均值分胜负（104.0 < 112.04）；而 `leftX` 平均值会被**换行的正文续行**（x0=90）拉偏，不可靠。
- 实现：新增 `styleDepth(TemplateKey)`：

| 风格 | depth |
|---|---|
| `第#章` / `第#节` / `第#条`、`#`（如 `1.`）、`#、`（如 `一、`） | 0 |
| `（#）` / `(#)` | 1 |
| `#）` / `#)` | 2 |

  插入 `validGroups.sort` 的 **count 之后、leftX 之前**，最终顺序为：
  **字体大小 → L2 密度 → 条目数 → 编号风格层级 → 缩进 → 首页 → 首个 topY**
  放在这个位置可保证视觉信号与条目数仍优先决策，只在并列时才生效。

---

## 四、关键决策

- **「注：」行天然不是目录**：不为其加任何特殊规则——它本来就匹配不上 `matchPrefix`；而正因为它的 value=1 不可捕获，同模板才会「到不了 1」，从而被模板级校验整体丢弃。这是**结构性根治**，不是打补丁。
- **不做「正文句判据」**（如「以『。』结尾即正文」「L2/L3 只收 heading」）：会引入误杀风险，且当前规则已覆盖绝大多数情形。文档② 的 `六、备查文件 → 1.公司第九届董事会第二十九次会议决议` 就是不以「。」结尾的例子。
- **不做「注：之后一律排除」**：语料扫描显示该类触发点 36 处中绝大多数目标是真标题，误删代价高。
- **句号过滤器保留但改为非破坏性**：不再单次改写候选集，而是「两次评估取更合适者」，兼顾过滤器本意与链完整性。
- **跨父级续号被明确放弃**：这是用户规则（每个父目录从 1 重开）的直接后果，并已用两个既有单测的语义确认（数据改为每个父目录重编号 + 新增钉住规则的用例）。

## 五、验证

### 单元测试
```bash
cd java
mvn -o -pl opendataloader-pdf-core test -Dtest='PageBookmarkProcessorTest,CatalogBookmarkProcessorTest' -DfailIfNoTests=false
```
- `PageBookmarkProcessorTest` **50/50**、`CatalogBookmarkProcessorTest` **3/3** 全绿。

### 全量回归（含基线对比）
```bash
mvn -o -pl opendataloader-pdf-core test
```
- 863 个测试 = **35 failures / 16 errors**。
- 这 7 个失败类（`AutoTaggerTest`、`EmbedImagesIntegrationTest`、`ImageDirIntegrationTest`、`IncludeHeaderFooterJsonIntegrationTest`、`Issue336IntegrationTest`、`PageSeparatorIntegrationTest`、`PagesOptionIntegrationTest`）全是**既有环境问题**（输出文件不存在、`parallel page processing` NPE 等），与书签无关。
- 基线对比方法：备份改动 → `git checkout -- <file>` 还原到 HEAD → 跑同样这 7 个类 → 恢复改动。基线结果 **61 run / 35 fail / 16 err，与含改动时完全一致** → 确认无新增回归。

### 真实 PDF 端到端（纯 JSON 路径 `extractPageBookmarksFromJson`，绕过不可达的 OCR）

- 文档① `202609121789114545357081630`：**4 个一级、无任何子节点**（`2、以上案件涉及金额…` 已消失）。
- 文档② `202609121789113649042076392`：8 个一级；`附件2：` **无子节点**；`附件1：` 变成正确的三级结构：

```
L1 附件1：
  L2 一．  网络投票的程序
    L3 （1）填报表决意见或选举票数
    L3 （2）股东对总议案进行投票，视为对除累积投票议案外的其他所有议案表达相同意见。
    L3 （3）对同一议案的投票以第一次有效投票为准。
  L2 二.  通过深交所交易系统投票的程序
  L2 三.  通过深交所互联网投票系统投票的程序
L1 附件2：      ← 无子节点
```

### 已知取舍（未处理）

- `（1）（2）（3）` 严格说属于 `一．网络投票的程序` 里的 `2. 议案意见表决。` 之下，所以它们成为 L2 `一．` 的 L3 属于「就近归位」，不是逐级精确。
- 若想取 `1./2.` 那组（`#`/ARABIC）作为 `一．` 的同级呈现，则需要调整「密度/风格层级」的优先级，代价是让 `一．/二./三.` 这一级小标题下沉。当前选择是保留小标题层级。

## 六、相关文件

- `java/opendataloader-pdf-core/src/main/java/org/opendataloader/pdf/processors/PageBookmarkProcessor.java` — 主要改动（`discardTemplatesNotStartingAtOne`、`cleanCandidatesLocal` Step 4.8、`childAnchorIndices` / `preferUnfilteredSelection`、`styleDepth`、`applyPeriodFilter` 重载）
- `java/opendataloader-pdf-core/src/test/java/org/opendataloader/pdf/processors/PageBookmarkProcessorTest.java` — 2 个用例数据更新 + 新增 `testChildNumberingMustRestartAtOneUnderEachParent`
- `docs/pdf/202609121789114545357081630.pdf`、`docs/pdf/202609121789113649042076392.pdf` — 症状 PDF
- `tmp_output/202609121789114545357081630.json`、`tmp_output/202609121789113649042076392.json` — 纯 JSON 路径验证 fixture
- `docs/memory/2026-09-10-page-bookmarks的L1目录的孤儿子级修复.md` — 前序修复（`minorityContainsValueOne` 守门员的由来）
- `docs/memory/2026-09-11-catalog书签问题10问题11未归入上级目录修复.md` — 同日 catalog 侧同类层级问题

## 七、构建命令

```bash
cd java
mvn -o -q -pl opendataloader-pdf-core compile
mvn -o -pl opendataloader-pdf-core test -Dtest='PageBookmarkProcessorTest' -DfailIfNoTests=false
```
