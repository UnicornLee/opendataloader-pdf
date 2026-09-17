# opendataloader-pdf 任务记忆 — 2026-09-17（catalog_bookmarks 缺子目录：从"三源择一"澄清到复用 page 层级判定补全）

> 样本：`docs/pdf/202303251679660111823147.pdf`（招股说明书，201 页，目录页在第 10 页）
> 分支：`ocr-unification-20260820`
> 用户问题："为什么 catalog_bookmarks 都没有子目录呢？难道整个 page_bookmarks 不是最后一个 catalog_bookmark 的子目录？"
> 本轮 = 澄清机制 → 定位补全失败原因 → 按用户要求"复用 page 的二级/三级判定"实现 → 全量语料回归（bookmarks 层）

## 目标（Goal）

1. 先解释清楚 `catalog_bookmarks` / `page_bookmarks` / `bookmarks` 之间的关系（用户认为它们是父子关系）。
2. 找出 `catalog_bookmarks` 全为 `children: []` 的原因。
3. 用户给定实现口径：**"收集完之后也要确定二级目录是什么、三级是什么，这部分逻辑可以复用 page_bookmarks 的二级和三级处理逻辑。
   当然如果 catalog_bookmarks 有二级的话，只需要补三级。"**
4. 全量语料回归，确认改动影响面。

## 结论速览（TL;DR）

1. **它们不是父子关系，而是"三源择一"**：`catalog_bookmarks`（目录页提取）、`page_bookmarks`（正文页候选建树）、
   `self_bookmarks`（PDF 自带 outline）三个**并列**来源，由 `BookmarkQualitySelector.select(...)` 按质量评分**选一个**写入 `bookmarks`，
   并把**胜者那个 key 从 JSON 里删掉**（内容已复制，避免重复）。所以"JSON 里看不到 `page_bookmarks`"通常是它胜出后被移除。
2. **catalog 补子目录的机制确实存在**（`CatalogBookmarkProcessor.fillCatalogChildrenFromPageData`），
   但 `PageBookmarkProcessor.extractChildrenByRange` 里有一道**写死的模板白名单**（L2 只接受 `第#节`/`第#条`，L3 只接受 `第#条`）。
   本文档目录一级本身就是"第X节"、正文二级是 `一、`、三级是 `（一）`，**全部不在白名单内** → 日志 `complemented 0` → `children` 全空。
3. 按用户口径改造后：新增 `extractChildrenByCatalogRange(...)`，把切片交给 page 的完整层级管线
   （`selectTemplateForLevel` → `childAnchorIndices` → `extractLevel`），层级由**局部连续编号 + 字号/密度/页跨度排序 + 句末一致性过滤**共同决定。
4. 顺带修掉两个"被模板闸门遮住"的既有缺陷：`anchorPage(null)` 返回 0 导致区间恒空、L3 最后一个同级的上界错用"文档末尾"。
5. 效果：`complemented 0 → 8`，`catalog_bookmarks` 325 条（**零重复、页码单调、最长标题 57 字符**），
   `BookmarkQualitySelector` 评分反超 page（catalog 5.787 vs page 5.078）→ **catalog 自然胜出**。
   全量语料 66 份中 **13 份变化，无整份退化**。

## 背景：先澄清"三源择一"

`JsonWriter.writeToCustomJson()`（主路径）与 rebuild 路径都会产出三个 key，然后：

```java
Map<Integer, Set<Integer>> pageItemIds = BookmarkQualitySelector.buildPageItemIds(data);
BookmarkQualitySelector.Selection selection = BookmarkQualitySelector.select(
    catalogBookmarks, pageBookmarks, selfBookmarks, pageItemIds);
map.put("bookmarks", selection.getBookmarks());
String selectedSource = selection.getSource();
if (selectedSource != null) {
    map.remove(selectedSource);       // 胜者 key 被移除
}
```

评分（`BookmarkQualitySelector`）：

```
score = ln(1 + 有效条目数) × (1 − penalty)
penalty = dupRatio + unlinkedRatio + strangeRatio + nonMonoRatio + invalidLinkRatio   （封顶 1）
penalty ≥ 0.5 → 淘汰；catalog 有"强胜率"保护，其它来源需 score ≥ catalog × 2.0（CATALOG_STRONG_WIN_RATIO）才能赢
```

本文档改动前的实测日志：

```
catalog: total=10, effective=10, penalty=0.000, score=2.398
page:    total=199, effective=192, dup=0.035, penalty=0.035, score=5.078
self:    total=0 → eliminated (empty source)
selected page_bookmarks (5.078): 5.078 is 2.12x catalog 2.398 (>=2.0x threshold), wins
```

→ 所以 JSON 里 `bookmarks` 是 page 的树（`一、审计意见 > （一）商誉减值 > 1、事项描述`），
`page_bookmarks` 被移除，`catalog_bookmarks` 以 10 条扁平条目保留（它是败者）。

## 定位过程

### 第 1 步：确认补全机制"跑了但没补上"

日志：

```
[CatalogBookmark] detected catalog page range from JSON: 10-10 (1 pages, 10 toc items)
[CatalogBookmark] extracted 10 bookmarks (10 top-level) from JSON range 10-10
[CatalogBookmark] complemented 0 catalog bookmark group(s) from page data      ← 关键
```

目录页只有 1 页 10 行 → catalog 只有 10 条一级条目（`第一节…第十节`），**扁平是正常的**；
但"补子目录"这一步 `complemented 0` 说明一条都没补上。

### 第 2 步：定位到模板白名单

```java
// PageBookmarkProcessor.extractChildrenByRange(...)
Set<String> acceptedTemplates = new HashSet<>();
if (level == 2) {
    acceptedTemplates.add(TEMPLATE_SECTION);    // 第#节
    acceptedTemplates.add(TEMPLATE_ARTICLE);    // 第#条
} else {
    acceptedTemplates.add(TEMPLATE_ARTICLE);    // 第#条
}
...
for (Candidate c : all) {
    ...
    if (c.templateKey == null || !acceptedTemplates.contains(c.templateKey.template)) {
        continue;                                 // ← 卡点
    }
```

该方法的注释也写明：这道闸门是为了挡掉正文里的 `（#）` 残留。

### 第 3 步：实证"候选在池子里、也在区间里，只是模板不匹配"

读本次跑出的诊断文件 `*_page_bookmarks_collected.md`（`writeCollectedPageBookmarkMarkdown` 产出）：

```
|98|一、审计意见|
|98|二、形成审计意见的基础|
|98|五、管理层和治理层对财务报表的责任|
|100|二、财务报表|
```

这些候选确实在候选池中、页码也落在"第十节财务报告（p98 起）"的区间内，
但模板是 `#、`（`TEMPLATE_CHINESE_COMMA`）→ 不在白名单 → 全被 `continue` 掉。

> 补充澄清：`*_page_bookmarks_collected.md` 是**给人看的诊断产物**，不是数据来源；
> 真正的候选池是 `collectJsonCandidates(data, catalogStart, catalogEnd)` 从 JSON items 重建的。两者内容一致，所以看起来像"从 md 收集"。

### 第 4 步：先做一次"直接删闸门"的实验（用户提议）

给闸门加临时开关 `-DnoTemplateFilter=true` 后重跑，对比 `bookmarks`：

| | 有闸门（现状） | 去掉闸门 |
|---|---|---|
| 顶层数 / 总条数 | 10 / 209 | 10 / **363** |
| 第一~五节 children | 0 | 9 / 10 / 81 / 43 / 11 |
| 第六~九节 | 0 | **仍为 0**（那些页的候选池里没有编号开头的候选） |
| 第十节 | 18 | 18 |

**两个致命副作用**：

1. **层级混编**：同一层 children 里 `一、公司信息` 与 `1、股东及股东大会`、`（1）…` 平铺在一起
   （第三节同时出现 `一、报告期内…` 和 `1、医药外包服务行业…`），父子关系全丢。
2. **正文段落混入**：第五节下出现
   `（1）2022 年，长寿生产基地获取《重庆博腾制药科技股份有限公司技术中心建设项目环境影响报告书的批`（148 字符）等正文段落；
   `BookmarkUtils.trimOverlongNodes` 的 200 字符闸门挡不住 100–200 字符的段落。

→ 结论：不能简单删闸门，必须"**分层**"。

### 第 5 步：按用户口径实现——复用 page 的层级判定

调研结论（`PageBookmarkProcessor` 内部）：

- 判定"某候选属于第几级"的核心是 `selectTemplateForLevel(candidates, start, end, level, usedTemplates)`
  —— 先按 `TemplateKey`（模板 + 数字体系）分组，跳过 `usedTemplates`；L1 用 `isValidGroup` + `cleanCandidates`，
  L2+ 用 `cleanCandidatesLocal`（**局部从 1 起连续** + 句末一致性过滤）；排序 `groupComparator`：
  **字号降序 → (L2) 密度升序 → 页跨度降序 → 条目数降序 → styleDepth → 平均左缩进 → 起始页 → topY**；L3 用 `runOffLevel3` 双指标 runoff。
- 建树递归是 `extractLevel(candidates, start, end, level, usedTemplates)`；注意它**只把 L1 的选中模板往下传播**
  （L2 模板不传，因为"`一、` 下再套 `一、`"是合法的 L3）。

新增（`PageBookmarkProcessor`）：

```java
public static List<Bookmark> extractChildrenByCatalogRange(
        List<Map<String, Object>> data,
        int catalogStartPage, int catalogEndPage,
        int anchorPage, int anchorRelatedId,
        int nextPage, int nextRelatedId,
        int level,                            // 2 或 3
        Collection<String> ancestorTemplates) // 祖先已用的模板名
```

实现要点：

1. 边界仍用 catalog 锚点的 `(page_num, related_id)` 半开区间 `[T, S)`（`isWithinSiblingRange`）过滤出 `slice`；
2. **不再做模板白名单**，直接 `extractLevel(slice, 0, slice.size()-1, level, templatesNamed(ancestorTemplates))`；
3. 新增 `static String templateNameOf(String text)`：用同一张 `PATTERNS` 表识别锚点文本的模板名，
   供 `CatalogBookmarkProcessor` 的 `ancestorTemplateNames(...)` 生成 `usedTemplates`，避免 L2 又选到父级模板。

`CatalogBookmarkProcessor.fillCatalogChildrenFromPageData` 两处调用改为新方法（L2 pass / L3 pass 结构不变，
即"catalog 已有二级就只补三级"）。

### 第 6 步：顺带修掉两个既有缺陷（被模板闸门遮住的）

| 缺陷 | 现象 | 修法 |
|---|---|---|
| `anchorPage(null) == 0` | 被 `isWithinSiblingRange` 当作"上界是第 0 页"→ **区间恒空**，最后一个同级永远补不上 | 调用处显式传 `-1`（开放区间） |
| L3 pass 的最后一个同级上界 | 用了"文档末尾"→ 一个 L2 会吞掉整份文档剩余内容（实测 `ch=60`、`nonMono=0.952`、`dup=0.495`） | 继承父 L1 的上界：`upperBound = nextSibling != null ? nextSibling : nextTop` |

## 验证（目标文档）

```
complemented 0 → 8 组
catalog_bookmarks: 325 条, distinct=325（零重复）, maxlen=57（无正文段落）, pages_sorted=True
  第一节 1、2、3…   第二节 一、公司信息…   第三节 12 个   第四节 17 个
  第五节 3 个       第六节 17 个          第七节 4 个    第十节 18 个（一、审计报告…）
BookmarkQualitySelector: catalog 5.787 (penalty 0.000) vs page 5.078 → selected catalog_bookmarks
单测 70/70 绿（PageBookmarkProcessorTest 50 / BookmarkQualitySelectorTest 17 / CatalogBookmarkProcessorTest 3）
```

## 关于方案 B（"把 page 树挂到 catalog 下"）——已试验并撤销

用户曾要求"先按 B 试试"（把 page 树整体挂到 catalog 对应条目下），实现并验证后撤销：

- 新增 `BookmarkUtils.mergeCatalogWithPageTree(catalog, page, self)`：catalog 作骨架（浅拷贝），
  page 顶层节点按 `page_num` 挂到"页码 ≤ 自己的最后一个 catalog 条目"下。
- 实测结果正是用户最初猜测的形态：`bookmarks tops=10 total=209`，
  **第一~九节无子目录，第十节财务报告挂上了全部 18 个 L2（199 条）** ——
  因为 page 树的 18 个顶层节点页码全部 ≥ 98，前九节的区间内 page 没有任何顶层节点。
- 随后按用户要求**撤掉 merge**：`JsonWriter` 恢复 `selection.getBookmarks()` + `map.remove(selectedSource)`；
  `BookmarkUtils` 的三个新方法与 `Comparator` import 一并删除（该文件回到 HEAD 原状，`git diff` 无输出）。

## 全量语料回归（bookmarks 层，66 份）

方法：临时开关 `-DlegacyCatalogFill=true` 还原旧行为（模板白名单 + 缺失兄弟传 0），**同一份代码跑两遍**，
digest 记录 `BOOKMARKS tops/total/maxdepth` + 每个节点 `BM d<depth> p<page> ch=<n> <text>`；

结果 **13/66 变化，无整份退化**：

- **明显变好 8 份**：补出 L2/L3 或层级被规整（`103→395`、`110→351`、`69→293`、`16→73`、`199→325`）；
  其中两份**清掉了旧版混入的正文**（`424/5 层 → 193/3 层`，旧版把 `海南瑞泽新型建材股份有限公司全体股东：` 当书签；
  `335/4 层 → 161/3 层`，旧版含长正文段落）。
- **补了子目录但混入少量正文 2 份**：`20260507AN202606291826520711`（混入 `(1)-(2)請參閱上一頁所載詳情。`、`1)  技術研發壁壘`）、
  `202609081788820439508064469`（混入 1 条）。根因是 page 侧句末一致性过滤有 `MIN_CANDIDATES_FOR_PERIOD_FILTER=3` 下限，
  该层候选 ≤2 时不过滤。**未修**。
- **仅标题文本多一个空格（结构完全一致）3 份**：疑似既有非确定性，**未排查**。

> 同一轮回归还发现"流程图吸收正文"的问题（Goodbaby `202504171785131172233015158`），属流程图侧，记录在
> 《2026-09-17-流程图误否决与段落合并误合并-根因定位与修复.md》。

## 观测手法（可复用）

1. **A/B 开关**：`-DlegacyCatalogFill=true` 在**同一份代码**里切换新旧行为，避免动 git（当时工作区有大量未提交改动）。
2. **语料 digest + python 逐行 diff**：`tmp_output/run_corpus.ps1`（4 分片并行，`Start-Process -WindowStyle Hidden`）、
   `diff_corpus2.py`、`diff_by_doc.py`；`Out-File` 必须带 `-Width 100000`。
3. **跑分必须先校验完整性**：`ProcessingDeadline` 超时会留下"只有 `=== xxx FAILED` 一行"的不完整 digest，
   在 diff 里伪装成"整份文档文本全消失"；比对前先跑 `check_sizes.py` / `lines_count.py`。

## 遗留

1. catalog 补全时"候选 ≤2 不做过句末过滤"导致的少量正文混入（2/66）。
2. 3 份文档的标题多一个空格（疑似非确定性）未排查。
3. Goodbaby 那份的"吸收正文"问题（见流程图篇）。
