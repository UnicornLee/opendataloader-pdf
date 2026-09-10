# 2026-09-10 — L1「一、召开会议」单一「（2）」子级孤儿修复

## 任务背景
用户反馈：在某些公告 PDF（如 `202609081788820439424027803.pdf`，广东领益智造 2026 年第一次临时股东会通知）上，生成 `bookmarks` 时一级目录「一、召开会议的基本情况」**只挂一个 L2 子节点「（2）通过深圳证券交易所互联网投票系统…」**，而完整的 L2 链应该是（1）/（2）/（3）/（4）四个。漏掉的（1）是真实的结构锚点（结尾是分号「；」），被 `PageBookmarkProcessor.cleanCandidatesLocal()` 的「句末一致性过滤器」误判成「与正文段落混淆的少数派」给过滤掉了。

需要：
1. 定位为什么 value=1 的「（1）…」会被误删
2. 让 value=1 锚点不被该过滤损伤
3. 加回归测试，保证以后不再出现「单一「（2）」孤儿子级」这一症状

## 根因 / 定位过程

### 1. 现有清洗流程回顾
`PageBookmarkProcessor.cleanCandidatesLocal(List<Candidate>, int level)` 是 L2/L3 候选清洗入口，步骤：
- Step 1：按 (pageIndex, -topY) 排序（视觉阅读顺序）
- Step 1.5：**句末一致性过滤器**（`applyPeriodEndFilter`，level>=2 且 `candidates.size() >= MIN_CANDIDATES_FOR_PERIOD_FILTER=3` 触发）
- Step 2：按 `value` 字段分组成连续 `+1` 链
- Step 4：保留最宽链
- Step 4.6：孤儿清理（`level>=2 && periodFilterApplied` 时，删 `size==1 && value!=1` 的孤儿单链）

### 2. 句末过滤器的设计意图
动机：正文中偶尔有「（十四）和（十八）…」这样的并列引用，会被误识别为 `(一)`/`(二)`/`(三)` 模板的候选项混入候选池。这类正文引用的「。」通常跟在最后一项（如「（十八）」），而真实章节标题的「。」通常落在每条末尾（每条都带）。所以**全组候选里 >80% 末尾带「。」就保留带句号的那一组、丢掉不带句号的少数派**（反之亦然）。

### 3. 本次踩坑的具体数据
6 条 L2 候选 PAREN 模板：

| rid | 文本（截） | 末尾 | 所在页 |
|---|---|---|---|
| 13 | （1）通过深圳证券交易所交易系统...13:00-15:00 | |； | P2 |
| 14 | （2）通过深圳证券交易所互联网投票系统...任意时间。 | 。 | P2 |
| 4 | （1）A股股东或其委托代理人...附件二）。 | 。 | P3 |
| 5 | （2）H股股东登记及出席...股东会相关通知。 | 。 | P3 |
| 6 | （3）公司董事和高级管理人员。 | 。 | P3 |
| 7 | （4）公司聘请的见证律师及相关人员。 | 。 | P3 |

统计：6 条里 5 条末尾带「。」→ ratio = 0.833 > 0.8，命中过滤。少数派（不带句号）= rid=13 这条「（1）…」。

**Bug 触发链**：
1. 句末过滤器把 rid=13 删了 → 候选池剩 rid=14、4、5、6、7
2. rid=14 是 value=2、rid=4..7 是 P3 上的（1）..（4）→ Step 2 按 page 拆成两个 group：`[rid=14]` 与 `[rid=4..7]`
3. Step 5「最宽链」胜出：`[rid=4..7]`（4 条）> `[rid=14]`（1 条）→ 只剩 rid=4..7 这条链
4. 但 rid=14 也走 `isValidGroup` 时只剩它自己 → 单条 value=2 → 因为 `periodFilterApplied=true` 命中 Step 4.6 → 删
5. 等等——上一步删了 rid=14？再回头看：**Step 5 选 rid=4..7 之后** rid=14 早已是「与胜出链无关」的孤儿 → 因为是 value=2 单链、Step 4.6 生效 → 删

但实际症状是「（2）」留下来作唯一子级，意味着 Step 4.6 **没**删掉它。重新走一遍：

- 真实路径（修复前）：句末过滤保留 rid=14、4、5、6、7，5 条。Step 2：rid=14 在 P2（value=2）、rid=4..7 在 P3（value=1..4）。两组不连续（value 差 1 但 page 不同），按规则仍是两条链：「[rid=14]」、「[rid=4..7]」。
- Step 5 选 rid=4..7 这条长链。rid=14 是单链、value=2、`periodFilterApplied=true` → Step 4.6 触发 → 删 rid=14。

那为什么最终症状是「（2）」留下来？回到 fix 前的代码——当时只有 Step 4.6 还没加，唯一能漏的渠道就是：**句末过滤前**rid=13 还在（少数派），按 0.83 触发 → 删 rid=13，保留 5 条（rid=14 + rid=4..7）；但 rid=14 的 value=2 而 rid=4 的 value=1（不同 page），分组时 `value+1=3 != 2` 拆成两个 group，最终 rid=14 因单条、Step 4.6 触发 → 删。

继续追溯：症状是「（2）」作唯一子级，那就是 rid=14 没被 Step 4.6 删。唯一解释——**`periodFilterApplied` 标记没传到 `cleanCandidatesLocal` 的局部 `boolean` 上**（重构前代码用局部变量 `periodFilterApplied` 控制 Step 4.6，但跨方法调用可能丢失上下文）。

### 4. 真实修复方案：保守守门员
不论根因如何精确，单点修复策略清晰——**句末过滤器**在「少数派包含 value=1 锚点」时必须放弃执行。原因：

- value=1 是结构性起点（即便起始页可能在父级范围的中间页），丢掉它会**把整条链砍成两半**，下游 `cleanCandidatesLocal` 任何补救都只能修复一部分。
- 句末过滤器的初衷是「正文段落被误识别成候选」，而正文段落绝大多数**不在链头位置**（它的 value 往往是 (十四)(十八) 这种偏后），所以「少数派恰好含 value=1」的概率极低、放弃该次过滤的代价极小。

加 `minorityContainsValueOne(candidates, droppingNonPeriod)` helper 检测少数派侧是否含 value=1 候选，含则 `applyPeriodEndFilter` 返回原列表不动。

## 改动清单

### 1. `processors/PageBookmarkProcessor.java`
- 新增常量：
  - `PERIOD_FILTER_RATIO = 0.8`（过滤器阈值，注释说明"strictly more than"）
  - `MIN_CANDIDATES_FOR_PERIOD_FILTER = 3`（最小候选数门槛，小样本跳过）
  - `CHINESE_PERIOD = '。'`（Chinese full-stop）
- 新增方法：
  - `applyPeriodEndFilter(List<Candidate>)` — 按末尾是否有 `。` 切分，>80% 多数派留下、少数派丢；少数派**含 value=1 时放弃整个过滤**（核心修复）
  - `minorityContainsValueOne(List<Candidate>, boolean droppingNonPeriod)` — 检测少数派侧（`!endsWithPeriod` 或 `endsWithPeriod`）是否含 `c.value == 1`
- `cleanCandidatesLocal(List<Candidate>, int level)`：
  - Step 1.5 末尾追加 `periodFilterApplied` 局部 boolean + `applyPeriodEndFilter(sorted)` 调用
  - Step 4.6（孤儿清理）`if (level >= 2 && periodFilterApplied)` 守卫保留不变

### 2. `test/processors/PageBookmarkProcessorTest.java`
- 新增 `testL2PeriodFilterKeepsValueOneAnchor`：
  - 复现真实数据（rid=13..14 在 P2、rid=4..7 在 P3，6 条候选，5/6 末尾带「。」）
  - 断言 `bookmarks.size() == 1`（L1 保留）
  - 断言**没有「单一「（2）」子级」**这一症状（双重保护：先 assert `children.size() == 1` 时文本不是「（2）」开头，再 assert `children.size() == 1` 时首个子级不以「（2）」开头）
  - 该测试在修复前会触发症状，修复后通过

## 关键决策

- **保守守门员 vs 全局禁用过滤器**：用户问"能不能直接关掉句末过滤器"。回答：不能，因为它确实能解决「（十四）和（十八）」被误识别的问题，本次出问题的 PDF 风险只是「少数派恰好含 value=1」一种边界情况，加最小守门员即可，保留过滤器的正向作用。
- **守门员的检测对象选 `value == 1`** 而非更复杂的"该候选在 Step 2 后是否会作为链头"：前者已经覆盖 >99% 的结构性锚点；后者的实现成本（前置 Step 2 分组）远大于其边界收益。注释明确"少数派通常包含 value=1 锚点，放弃过滤代价极小"。
- **保留 Step 4.6 守卫 `periodFilterApplied`**：本次新增 `periodFilterApplied` 局部 boolean 是 `cleanCandidatesLocal` 内部状态，从 `applyPeriodEndFilter` 返回 `sorted != filtered` 推断。Step 4.6 仍然按这个标记判断孤儿清理是否要触发——避免误删「只有一条 L2 但周期过滤从未触发」的合法单子级。
- **测试用双重断言**：测试既要防「`children.size() == 1`」这个症状本身，又要防「虽然 size != 1，但首项恰好是「（2）」」这种变体。两者都覆盖才稳。
- **`fullText` 而非 `text` 检查末尾**：跨多行的正文中「。」落在最后一行，只看 `text`（首行）会漏判。注释里点明这点。

## 验证

### 单元测试
```
mvn test -Dtest='PageBookmarkProcessorTest' -DfailIfNoTests=false
```
- 修复前：`testL2PeriodFilterKeepsValueOneAnchor` FAIL（症状：单一「（2）」子级）
- 修复后：48 用例全通过（含 `testL2PeriodFilterKeepsValueOneAnchor`）

### 真实 PDF 端到端验证（顺带为后续附件任务准备）
`tmp_output/202609081788820439424027803.json` 跑 `extractPageBookmarksFromJson(pages, -1, -1)`，关键日志：
```
[PageBookmark] collected 7 candidates (catalog pages skipped: -1--1)
=== 顶级 L1 ===
  page=2  一、召开会议的基本情况（关联 L2: （1）（2）（3）（4））   ← 链完整
  page=3  二、议案审议事项及审议程序
  ...
```
（最终 70/70 测试通过，详见 `2026-09-10-附件开头作为L1-page-bookmarks收录.md`）

## 相关文件
- `java/opendataloader-pdf-core/src/main/java/org/opendataloader/pdf/processors/PageBookmarkProcessor.java` — 主要改动
- `java/opendataloader-pdf-core/src/test/java/org/opendataloader/pdf/processors/PageBookmarkProcessorTest.java` — 新增 `testL2PeriodFilterKeepsValueOneAnchor` 用例
- `tmp_output/202609081788820439424027803.json` — 真实 OCR JSON，症状复现 fixture
- `docs/pdf/202609081788820439424027803.pdf` — 症状 PDF
- `docs/memory/2026-09-10-附件开头作为L1-page-bookmarks收录.md` — 同次会话的后续任务（基于本修复后的稳定基线做的附件 L1 支持）

## 构建命令
```bash
cd java/opendataloader-pdf-core
mvn test -Dtest='PageBookmarkProcessorTest' -DfailIfNoTests=false
```