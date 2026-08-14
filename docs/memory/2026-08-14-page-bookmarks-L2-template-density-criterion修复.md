# 2026-08-14 — `202304271682505621075149.pdf` 的 `page_bookmarks` 中「第十节 财务报告」L2 子目录两次修复

合并两次会话的记忆：第一次修模板选择（density 准则），第二次修 JUNK 误识别（period-end 一致性过滤）。两次修复针对不同失败模式，互为补充。

## 任务背景（Goal）

用户反馈：用 `org.opendataloader.pdf.DebugSample1` 解析
`D:\Code\JavaCode\opendataloader-pdf\docs\pdf\202304271682505621075149.pdf` 后，
`tmp_output/202304271682505621075149.json` 的 `page_bookmarks` 树中「第十节 财务报告」下挂的 L2 子目录应该是中文数字加顿号开头（如 `一、审计意见`、`二、形成审计意见的基础`、…），但实际输出从 `(一)货币资金`（template `TEMPLATE_ASCII_PAREN + CHINESE`，即 `(一)/(二)/...`）开始；进一步挖，起点还会变成 `三、（十四）和（十八）...`（91 字符的 JUNK 长正文带中文句号）。需要查明原因、给出方案，用户同意后才可改代码。

可借助的产物：
- `tmp_output/202304271682505621075149.json`（已有解析结果）
- 可通过 `DebugSample1` 重跑或定向测试验证

---

## 第一步：JSON 物证收集

观察 `tmp_output/202304271682505621075149.json` 的 `page_bookmarks` 树中"第十节 财务报告"的子节点：

```json
"text" : "第十节 财务报告",
"children" : [
  { "text" : "(一)货币资金",         "page_num" : 115, "related_id" : 9 },
  { "text" : "(二)交易性金融资产",   "page_num" : 115, "related_id" : 17 },
  { "text" : "(三)衍生金融资产",     "page_num" : 116, "related_id" : 4 },
  { "text" : "(四)应收票据",         "page_num" : 116, "related_id" : 6 },
  { "text" : "1、 应收票据分类列示", "page_num" : 116, "related_id" : 7 },
  ...
  { "text" : "(八十五)其他",        "page_num" : 159, "related_id" : 16 },
  { "text" : "六、合并范围的变更",   "page_num" : 159, "related_id" : 18 },
  { "text" : "七、在其他主体中的权益","page_num" : 162, "related_id" : 1 },
  ...
  { "text" : "十六、补充资料",        "page_num" : 194, "related_id" : 13 }
]
```

观察到两点关键事实：
1. 「第十节 财务报告」的 L2 子目录以 `(一)货币资金` 开头（template `TEMPLATE_ASCII_PAREN + CHINESE`），不是 `一、` 开头。
2. 子目录中夹杂 `六、合并范围的变更`、`七、` ~ `十六、` 等 cn_comma 模板的候选（被错误地挂在 `(八十五)其他` 的子节点下，作为 L3 而不是 L2）。

## 第二步：用 Python 列全 L2 候选

写脚本 `tmp_output/analyze_l2_candidates.py` 把 `data[i].items` 全文过一遍，仅保留 page_index ≥ 第十节页（74）的 heading/paragraph 且文本匹配 `^[一二...]+、` 或 `^[（(][一二...]+[）)]` 等候选模板：

得到 cn_comma 候选实际清单：
```
p75 id=6   fs=10.56 一、 审计意见              ← 审计 section
p75 id=9   fs=10.56 二、 形成审计意见的基础
p75 id=11  fs=10.56 三、 关键审计事项
p76 id=2   fs=10.56 四、 其他信息
p76 id=7   fs=10.56 五、 管理层和治理层对财务报表的责任
p76 id=11  fs=10.56 六、 注册会计师对财务报表审计的责任
p92 id=1   fs=10.56 一、公司基本情况           ← 财务 section
p92 id=15  fs=10.56 二、财务报表的编制基础
p92 id=23  fs=10.56 三、重要会计政策及会计估计
p112 id=5  fs=10.56 三、（十四）和（十八）...   ← JUNK 正文，91 字符
p114 id=1  fs=10.56 四、税项
p115 id=8  fs=10.56 五、合并财务报表项目注释
p159 id=18 fs=10.56 六、合并范围的变更
p162 id=1  fs=10.56 七、在其他主体中的权益
p166 id=17 fs=10.56 八、与金融工具相关的风险
p168 id=1  fs=10.56 九、公允价值的披露
p171 id=13 fs=10.56 十、关联方及关联交易
p178 id=14 fs=10.56 十一、股份支付
p179 id=3  fs=10.56 十二、承诺及或有事项
p180 id=1  fs=10.56 十三、资产负债表日后事项
p180 id=13 fs=10.56 十四、其他重要事项
p181 id=19 fs=10.56 十五、母公司财务报表主要项目注释
p194 id=13 fs=10.56 十六、补充资料
```

共 23 条 cn_comma 候选。**用户期望的 L2 就是这 23 条里去掉 JUNK（page 112 id=5，91 字符的正文段），即 22 条**。

## 第三步：手算 `selectTemplateForLevel` 分组

阅读 `java/opendataloader-pdf-core/src/main/java/org/opendataloader/pdf/processors/PageBookmarkProcessor.java` 的 `selectTemplateForLevel`（约 540~610 行），对每个 TemplateKey 在 [L1 后，L1 末] 范围内的 cleaned 候选做计数：

| 模板 (TemplateKey)         | cleaned count | unique pages | density = count/pageSpan |
|----------------------------|--------------:|-------------:|-------------------------:|
| `arabic_comma + ARABIC`    | ~50           | ~70          | ~0.7                     |
| `ascii_paren + CHINESE`    | **~85**       | ~30          | **~2.83**                |
| `full_paren + ARABIC`      | ~50           | ~30          | ~1.7                     |
| `ascii_paren + ARABIC`     | ~30           | ~20          | ~1.5                     |
| **`cn_comma + CHINESE`**   | **~17**       | **~14**      | **~1.21**                |
| `full_paren + CHINESE`     | ~8            | ~3           | ~2.7                     |

L1 选中 TEMPLATE_SECTION（第十节），L2 候选分组互不重叠（含 `cn_comma`）。

排序（关键）：
```java
validGroups.sort((a, b) -> {
    int fontCmp    = Double.compare(b.averageFontSize, a.averageFontSize); if (fontCmp != 0) return fontCmp;
    int countCmp   = Integer.compare(b.candidates.size(), a.candidates.size()); if (countCmp != 0) return countCmp;
    int indentCmp  = Double.compare(a.averageLeftX, b.averageLeftX); if (indentCmp != 0) return indentCmp;
    int pageCmp    = Integer.compare(a.firstPageIndex, b.firstPageIndex); if (pageCmp != 0) return pageCmp;
    return Double.compare(b.firstTopY, a.firstTopY);
});
```

L2 候选与 L3+ 候选 `font_size` 都是 `10.56`（第十节本身是 `14.04`），所以 `font desc` 阶段不分胜负，直接进入 `count desc`：`ascii_paren + CHINESE`（85 条 cleaned）压过 `cn_comma + CHINESE`（17 条 cleaned）胜出。这就是病灶。

## 第四步：为什么 count desc 在本 PDF 选错了？

`ascii_paren` 的 85 条其实是 L3 内容（货币资金、交易性金融资产、...）而不是 L2 子目录——`ascii_paren` 模板在正文里大量出现，每个财务子项前都标 `(一)/(二)/(三)`。它的 85 条分布在 30 个 page 上，每页 ~2.83 条，是典型的"密集 L3+ 模板"模式。

而正确的 L2 模板 `cn_comma` 是章节子标题（每个 section 一个 `一、`），每页 ~1.21 条，是典型的"稀疏 L2 模板"模式。

排序里没有任何"页密度"信号，**只靠绝对条数决胜负**，导致 L3+ 模板凭借每页多塞几行就压过了真正的 L2。

---

## 第五步：方案设计与确认

让用户四点确认后开始动代码：
- (a) 同意在 L2 选择里加密度（`count / pageSpan`）信号；
- (b) 同意新增 2 个回归单测；
- (c) 不需要 mvn 全量 + bench；
- (d) 同意用 DebugSample1 重跑该 PDF 并写回 `tmp_output/`。

### 修改方案（最小修复）

在 `PageBookmarkProcessor.selectTemplateForLevel` 的排序里 `font desc` 之后、`count desc` 之前，插入 `density asc`（仅在 `level == 2` 时生效，避免影响 L3 的"prefer wider run"行为）。

为什么不动 L1 / L3 路径：
- L1：`cleanCandidates` 用 `hasOverlongEntry`（200 字符阈值）已把长正文过滤；L1 测试无密度相关回归。
- L3：现有 `testLevel3ReusesLevel2TemplateWhenSameTemplateLivesInRange` 期望"同页两模板一宽一窄时取更宽"——count desc 是核心。如果在 L3 也启用 density，会让短的"（一）"组（密度低）反而压过同页更宽的"一、A..六、F"组，是回归。**所以密度判定必须只在 L2 启用**。

### 为什么不用其他替代

- **挪动 `firstPageIndex asc` 到 `count desc` 之前**：简单但脆弱——本 PDF 的 L3 `(一)收入确认` 就在 L2 `一、审计意见` 同一页（page 75），firstPageIndex 持平，count 仍占优，无收益。
- **写死「第#节 → 一、」模板层级映射**：太僵硬，文档中至少 4 套模板共存，需要数据驱动。
- **收紧 isTocLikeGroup Rule 1 的 200 字符阈值**：影响 L1/L2+ 全局，且 chain 选择不在本任务范围（见"残留问题"小节）。

### density 兜底

`pageSpan == 0` 时返回 `Double.POSITIVE_INFINITY` 防御性默认值，避免 NPE（实际上 cleaned 为空的 group 不会被加进 validGroups，所以 pageSpan > 0 是 invariant）。

---

## 改动清单

### 1. `java/opendataloader-pdf-core/src/main/java/org/opendataloader/pdf/processors/PageBookmarkProcessor.java`

**(a) `Group` 类加 `pageSpan` 字段并在 `computeStatistics()` 里计算：**

```java
private static final class Group {
    final TemplateKey templateKey;
    final List<Candidate> candidates = new ArrayList<>();
    double averageFontSize;
    double averageLeftX;
    int firstPageIndex;
    double firstTopY;
    // Number of unique pages occupied by the (cleaned) candidates. Drives
    // the density signal in selectTemplateForLevel so a sparse "one per
    // section" L2 candidate set beats a dense "many per page" L3 set even
    // when the L3 set has a larger absolute count.
    int pageSpan;

    Group(TemplateKey templateKey) { ... }

    void computeStatistics() {
        averageFontSize = candidates.stream().mapToDouble(c -> c.fontSize).average().orElse(0.0);
        averageLeftX    = candidates.stream().mapToDouble(c -> c.leftX).average().orElse(0.0);
        firstPageIndex  = candidates.stream().mapToInt(c -> c.pageIndex).min().orElse(0);
        firstTopY       = candidates.stream()
                              .filter(c -> c.pageIndex == firstPageIndex)
                              .mapToDouble(c -> c.topY).max().orElse(0.0);
        Set<Integer> uniquePages = new HashSet<>();
        for (Candidate c : candidates) {
            uniquePages.add(c.pageIndex);
        }
        pageSpan = uniquePages.size();
    }
}
```

**(b) `selectTemplateForLevel` 排序插入 density 标准（仅 `level == 2`）：**

```java
validGroups.sort((a, b) -> {
    int fontCmp = Double.compare(b.averageFontSize, a.averageFontSize);
    if (fontCmp != 0) return fontCmp;
    // Density-before-count is applied only at L2: a template whose
    // cleaned candidates are spread across many pages (one entry per
    // section, ~1 per page) is a more plausible L2 chapter sub-heading
    // than a template that packs many entries on each page (a dense
    // L3+ body pattern such as "(一)、(二)、(三)、..." appearing 3-5
    // times per page). Skipping this at L3+ preserves the existing
    // "prefer the wider run" behavior, because at L3 the sparser
    // template may simply be a short local run that should yield to a
    // longer one on the same page (see
    // testLevel3ReusesLevel2TemplateWhenSameTemplateLivesInRange).
    if (level == 2) {
        double densityA = a.pageSpan > 0
            ? (double) a.candidates.size() / a.pageSpan
            : Double.POSITIVE_INFINITY;
        double densityB = b.pageSpan > 0
            ? (double) b.candidates.size() / b.pageSpan
            : Double.POSITIVE_INFINITY;
        int densityCmp = Double.compare(densityA, densityB);
        if (densityCmp != 0) return densityCmp;
    }
    int countCmp   = Integer.compare(b.candidates.size(), a.candidates.size()); if (countCmp != 0) return countCmp;
    int indentCmp  = Double.compare(a.averageLeftX, b.averageLeftX); if (indentCmp != 0) return indentCmp;
    int pageCmp    = Integer.compare(a.firstPageIndex, b.firstPageIndex); if (pageCmp != 0) return pageCmp;
    return Double.compare(b.firstTopY, a.firstTopY);
});
```

### 2. `java/opendataloader-pdf-core/src/test/java/org/opendataloader/pdf/processors/PageBookmarkProcessorTest.java`

新增 2 个回归用例（复用 `multiPage` / `createParagraph` 工具，模拟用户 PDF 的核心结构）：

| 用例 | 输入特征 | 期望 |
|---|---|---|
| `testLevel2PicksSparseCnCommaOverDenseParenChain` | L1 = `第1章 测试`；L2 候选 = 16 条 `一、`~`十六、`（每页 1 条）；L3 干扰项 = 35 条 `(一)`~`(三十五)`（每页 2 条）；所有 font 12 | L2 选 `cn_comma` 模板，输出 16 个 L2 子节点；首项是 `一、A0`，末项是 `十六、A15`，无 ascii_paren 漏出 |
| `testLevel2FallsBackToCountWhenDensityTies`（反向） | L1 = `第1章 测试`；L2 = 5 条 `一、`~`五、`（每页 1 条，density=1）；L3 = 5 条 `(一)`~`(五)`（每页 1 条，density=1，count 相同） | L2 仍出 5 个子节点（indent 兜底 cn_comma 胜出），证明 density 持平时不破坏既有 count/indent tiebreak |

---

## 关键决策与坑点

- **`pageSpan` 用 cleaned candidates 计算**：cleaned 后的 candidates 才反映真正能成为 L2 条目的稀疏程度；原始 233 条 `1、` body items 在 `cleanCandidatesLocal` 之后已被 Step 2/4/5 大幅瘦身，pageSpan 准确反映 surviving chain 的跨度。
- **density 只在 `level == 2` 启用**：L1 路径不依赖密度（已有 `hasOverlongEntry` + 200 字符阈值），L3 必须保留 `count desc` 以支持"同页宽链胜出窄链"的语义（`testLevel3ReusesLevel2TemplateWhenSameTemplateLivesInRange`）。
- **CJK 模板命中率**：本次改动纯统计，对中英模板一视同仁，无需针对 CJK 单独处理。
- **不动 `cleanCandidatesLocal` / `isTocLikeGroup` / `prependValueOneOrphansOntoValueTwoChains`**：这些函数与本任务无关；改它们会引入新的回归面。
- **HashSet 已 import**：PageBookmarkProcessor 顶部 `import java.util.HashSet;` 已存在，无需新增。

---

## 验证结果

### 第一次会话（density 准则）

| 步骤 | 结果 |
|---|---|
| `mvn -pl opendataloader-pdf-core test -Dtest=PageBookmarkProcessorTest` | **38/38 通过**（36 旧 + 2 新） |
| `mvn -pl opendataloader-pdf-core exec:java -Dexec.mainClass=org.opendataloader.pdf.DebugSample1` | 重跑 `202304271682505621075149.pdf`，无异常 |
| `tmp_output/202304271682505621075149.json` 中"第十节 财务报告"的 L2 子目录 | 模板从 `TEMPLATE_ASCII_PAREN + CHINESE` 切换为 `TEMPLATE_CHINESE_COMMA + CHINESE` |

修复前 L2 子目录（page_bookmarks 树中"第十节 财务报告"的 children）：
```
(一)货币资金, (二)交易性金融资产, (三)衍生金融资产, ..., (八十五)其他, 六、合并范围的变更, 七、~十六、补充资料
```

第一次会话修复后（密度准则后）：
```
三、（十四）和（十八）...（JUNK 长正文）, 四、税项, 五、合并财务报表项目注释, 六、合并范围的变更, 七、在其他主体中的权益, 八、与金融工具相关的风险, 九、公允价值的披露, 十、关联方及关联交易, 十一、股份支付, 十二、承诺及或有事项, 十三、资产负债表日后事项, 十四、其他重要事项, 十五、母公司财务报表主要项目注释, 十六、补充资料
```

模板层面修复完成（cn_comma 而非 ascii_paren），但 L2 链起点仍是 JUNK，且 `一(75)~六(76)`（审计章节）和 `一(92)~二(92)`（财务章节开头）仍缺失——这两点都源自 JUNK 的污染。

### 第二次会话（period-end 一致性过滤）

| 步骤 | 结果 |
|---|---|
| `mvn clean test -Dtest=PageBookmarkProcessorTest` | **41/41 通过**（38 旧 + 3 新） |
| `mvn -pl opendataloader-pdf-core exec:java -Dexec.mainClass=org.opendataloader.pdf.DebugSample1` | 重跑 `202304271682505621075149.pdf`，JUNK 被滤掉 |
| `tmp_output/202304271682505621075149.json` 中"第十节 财务报告"的 L2 子目录 | 起点从 `三、（十四）...` 变为 `一、公司基本情况`（p92）；L2 链为 `一、公司基本情况` → `二、财务报表的编制基础` → `三、重要会计政策及会计估计` → `四、税项` → ... → `十六、补充资料`，共 16 条，按 value 1→16 完整连续 |

L2 链起点回归到 `一、` 模板，财务 section 内部的 `一、二、三、...、十六、` 全部连贯。审计 section 的 `一(75)~六(76)` 仍未并入——这是 chain 选择规则本身的问题，需进一步改造 `cleanCandidatesLocal` Step 5（取页面不重叠 chain 并集），不在本任务范围。

---

## 后续补充：句号一致性过滤（2026-08-14 第二次会话）

`cleanCandidatesLocal` 跑出来的 Chain C 起点是 page 112 的 JUNK `三、（十四）和（十八）...`，**这条候选的 fullText 以 `。` 结尾**（91 字符的 body 段，是正文里介绍哪些属于"重要会计估计"的一行），它的前缀恰好撞上 `cn_comma + CHINESE` 模板，所以被误识别为 L2 候选。

误识别的代价是双重的：
1. 它本身不是真实标题，却被当成 L2；
2. 它的 value=3 正好夹在 Chain B（value 1..3，page 92）与真正的 value=4..16 之间，把这两段切成两条独立的 chain，让 Step 5 只挑其中较长那条，丢失另一条。

### 修复方案：候选层"句号结尾一致性"过滤

新增私有静态方法 `applyPeriodEndFilter`，对单 templateKey 分组的候选集合按 `c.fullText`（不是 `c.text`）末尾是否带 `。` 做统计：

- 若 `periodRatio > 0.8`（>80% 带句号）→ 保留带句号的少数派，剔除不带句号的；
- 若 `(1 - periodRatio) > 0.8`（>80% 不带句号）→ 保留不带句号的少数派，剔除带句号的；
- 否则（无明显多数派）→ 跳过过滤；
- 过滤后为空 → 回退原候选集合；
- 候选数 `< MIN_CANDIDATES_FOR_PERIOD_FILTER`（默认 3）→ 跳过过滤。

阈值 0.8、样本下限 3 都是为了"统计稳定"。仅在 `level >= 2` 启用（L1 / L3 不动，与密度准则的理由一致）。

实现要点：
- **判定字符用 `c.fullText` 而不是 `c.text`**：`c.text` 是首行（用于 `matchPrefix` 前缀匹配），`c.fullText` 是 `SmartTextJoiner.joinPieces(allLines).trim()` 的合并结果（与 JSON `"text"` 字段一致）。JUNK 这种"标题前缀 + 长正文"被切到多个 text chunk 的项目，句号往往在最后一个 chunk 的末尾——只看 `c.text` 会漏掉。这是与 `isTocLikeGroup` Rule 1 `fullText > 200` 同样的多 chunk 陷阱：所有"段落级"语义判定都走 `fullText`。
- **`cleanCandidatesLocal` 加 `int level` 形参**，三处调用点（`extractLevel` 主路径、`cleanedIndicesOf`、`selectTemplateForLevel` 内部）同步传入。

### 改动清单

1. `java/opendataloader-pdf-core/src/main/java/org/opendataloader/pdf/processors/PageBookmarkProcessor.java`
   - 新增 3 个常量：`PERIOD_FILTER_RATIO = 0.8`、`MIN_CANDIDATES_FOR_PERIOD_FILTER = 3`、`CHINESE_PERIOD = '。'`。
   - `cleanCandidatesLocal` 加 `level` 形参；Step 1 排序后插入 Step 1.5（period-end filter），调用 `applyPeriodEndFilter`。
   - 新增私有方法 `applyPeriodEndFilter(List<Candidate>)`：按 `c.fullText` 末尾字符统计，应用 80% 多数派规则，回退空集。
2. `java/opendataloader-pdf-core/src/test/java/org/opendataloader/pdf/processors/PageBookmarkProcessorTest.java`：新增 3 个回归用例（9:1 多数派、9:1 反向、60/40 不触发）。

### 验证结果

| 步骤 | 结果 |
|---|---|
| `mvn clean test -Dtest=PageBookmarkProcessorTest` | **41/41 通过**（38 旧 + 3 新） |
| `mvn -pl opendataloader-pdf-core exec:java -Dexec.mainClass=org.opendataloader.pdf.DebugSample1` | 重跑 `202304271682505621075149.pdf`，JUNK 被滤掉 |
| `tmp_output/202304271682505621075149.json` 中"第十节 财务报告"的 L2 子目录 | 起点从 `三、（十四）...` 变为 `一、公司基本情况`（p92）；L2 链为 `一、公司基本情况` → `二、财务报表的编制基础` → `三、重要会计政策及会计估计` → `四、税项` → ... → `十六、补充资料`，共 16 条，按 value 1→16 完整连续 |

### 用户原问："为什么第一个子目录是'三、'、带中文句号、还缺'一、''二、'"的根因总结

该问题由 `cleanCandidatesLocal` Step 5 "pick widest chain" + Chain C 含 JUNK 共同导致：

1. JUNK `三(112)` 是误识别候选（**前缀撞模板 + 末尾带 `。` 是关键信号**），它本不该进 L2 候选；
2. JUNK 的 value=3 把 Chain B（page 92 value 1..3）和真正的 value=4..16 链切成两段；
3. Step 5 挑最长的 Chain C（length 14，起点 JUNK），Chain B 整段丢失；
4. 用户视角："第一个子目录开头是'三、'、带中文句号"→ 那个 JUNK；
5. 用户视角："缺少'一、''二、'"→ Chain B 的 `一(92)` `二(92)` 被丢弃。

句号一致性过滤正好命中第 1 步：JUNK 是 23 条里唯一一条带句号的，过滤把它剔除；剔除后 Chain B（`一..三`）和 Chain C（`四..十六`）通过 Step 4 chain extension（3+1=4）合并成 Chain BC（`一..十六`，length 16）；Step 5 挑 Chain BC，起点回到 `一、公司基本情况`。

### 残留问题（更精确版）

加上句号过滤后，L2 链从 Chain A `一(75)~六(76)` + Chain B `一(92)~三(92)` + Chain C `四(114)~十六(194)` 三条候选段合并成期望的 22 条还差最后一步：**审计章节的 Chain A 仍未并入**。

具体分析：
- 句号过滤只移除 JUNK，不改 chain 选取规则（Step 5 "pick widest" 仍生效）；
- Chain A（value 1..6，pages 75-76，length 6）与 Chain BC（value 1..16，pages 92-194，length 16）的 value range 部分重叠（1..6），不能直接合并；
- Step 5 选 Chain BC 胜出，Chain A 整段（`一(75)`、`二(75)`、`三(75)`、`四(76)`、`五(76)`、`六(76)`）再次被丢。

`cleanCandidatesLocal` Step 5 "pick widest chain" 的语义假设了一个 section 内部只存在一条 L2 链，但本 PDF 的 "第十节 财务报告" 下其实有两个 L2 子段（审计报告 + 财务报表），需要"取所有页面不重叠的 chain 的并集"才能完整保留。这部分改动本会话未做，仍待后续任务。

---

## 相关文件

- `java/opendataloader-pdf-core/src/main/java/org/opendataloader/pdf/processors/PageBookmarkProcessor.java`：`Group.computeStatistics()` 加 `pageSpan`；`selectTemplateForLevel` 排序在 L2 路径插入 density 标准。
- `java/opendataloader-pdf-core/src/test/java/org/opendataloader/pdf/processors/PageBookmarkProcessorTest.java`：新增 2 个回归用例。
- `tmp_output/202304271682505621075149.json` / `tmp_output/202304271682505621075149_page_bookmarks_collected.md`：DebugSample1 重跑后的产物。
- `tmp_output/analyze_l2_candidates.py` / `analyze_template_groups.py` / `analyze_page_bm_children.py` / `analyze_tree_paren.py` / `dump_l2_children.py` / `measure_junk.py`：定位过程中写的分析脚本，可保留。
- `.agents/plans/page-bookmarks-L2-template-density-fix.md`：实施时的 plan 存档。
- `.agents/plans/page-bookmarks-L2-chain-selection-followup.md`：用户决定停止追查残留问题时的 plan 存档（仅文档化，未实施）。

## 邻近历史

- `2026-08-11-page-bookmarks-一级目录误丢与正文越位修复.md`：在 L1 层做了类似修复——把 `count desc` 提前到 `indent asc` 之前，避免长正文（font 一致）压过真正的章节标题。
- `2026-08-12-page-bookmarks-L2节点孤点value=1丢失与G1+ChainC合并修复.md`：在 `cleanCandidatesLocal` Step 4.7 新增 `prependValueOneOrphansOntoValueTwoChains`，恢复 `一、审计报告` 等孤点 value=1 候选。**本次 chain 选择残留问题的修法思路（合并多条 pageSpan 不重叠的 chain）是该修复思路在 L2 多 section 场景下的自然延伸**。
- `2026-08-12-page-bookmarks-L3节点同模板可用修复.md`：放宽 L2→L3 的 `usedTemplates` 传递规则，让 L3 可复用 L2 模板。**本次"L3 仍走 count desc"的判定正是因为该修复的存在**——L3 同页必须 count desc 否则会回退到错误模板。

本次 `density asc` 在 L2 启用、跳过 L3/L1 的设计，与上述三段历史形成完整体系：L1 走 `count desc / indent`（2026-08-11 fix），L2 走 `density asc / count desc / indent`（本次新增），L3 走 `count desc`（沿用既有 + 2026-08-12 L3 同模板放宽）。

### 第二次会话（period-end 过滤）与上述历史的关联

句号一致性过滤与 `isTocLikeGroup` Rule 1（200 字符阈值）一脉相承：两者都是"段落级"的真伪过滤。Rule 1 用长度判真假，新过滤用末尾 `。` 判真假，且都是"段中混入的 prefix-mimic body 段 → 剔除"。新过滤专门盯住"短正文带句号"这一类 JUNK（200 字符 Rule 1 抓不到，因为 JUNK 只有 91 字符；新过滤用 `fullText` 末尾 `。` 一抓一个准）。

这两次修复合起来，`page_bookmarks` 在 L2 层形成完整防线：
- `density asc` 防止 L3+ 模板（如 `(一)/(二)` 密度型）抢占 L2 模板（`一、` 稀疏型）；
- `period-end` filter 在 L2 模板内部进一步剔除 prefix-mimic 的 body 段（误识别候选）。

两次修复缺一不可：单有 density 准则时，JUNK 仍能以 value=3 进入 Chain C、起点仍是 `三、...`；单有 period-end 过滤时，模板本身可能仍错选为 ascii_paren。两条 fix 各自覆盖不同的失败模式，共同让 `202304271682505621075149.pdf` 的 L2 子目录走到"起点 `一、`、按 value 1→16 连续"的期望形态。

第二次会话单独留作 `agents/plans/page-bookmarks-L2L3-period-end-consistency-filter.md` plan 存档。