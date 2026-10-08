# 2026-10-08 — `page_bookmarks` 只有 1 条一级目录即整体丢弃：定位、正文句被当目录输出的根因与修复

> 样本：`docs/pdf/202609011788275402820062798.pdf`（用户指定）
>
> 改动文件（working tree）：
> - `java/opendataloader-pdf-core/src/main/java/org/opendataloader/pdf/processors/PageBookmarkProcessor.java`（新增 `discardSoleTopLevelBookmark`，+24 行）
> - `java/opendataloader-pdf-core/src/main/java/org/opendataloader/pdf/json/JsonWriter.java`（2 处调用点包裹）
> - `java/opendataloader-pdf-core/src/test/java/org/opendataloader/pdf/processors/PageBookmarkProcessorTest.java`（+2 个用例）
>
> 探针/产物（均在 `tmp_output/probe/`，**未入库**）：`one.txt`、`multi.txt`、`base_one/`、`after_one/`、`after_multi/`、`testcls/`、`SingleRootProbe.java`；根目录 `cp.txt`（临时 classpath，已删除）
>
> 状态：**已实现 + 已验证**（目标 PDF 前后对比通过；多一级目录文档对照无变化；新增 2 个单测实跑通过）。遗留待用户决策：规则是否收窄为「单根且无子级」才丢（见 §七影响面）。

---

## 一、任务背景（Goal）

用户提出一条规则：**如果最后得到的 `page_bookmarks` 只有 1 条一级目录，则可以抛弃，把 `page_bookmarks` 置空**，并指定 `docs/pdf/202609011788275402820062798.pdf` 作为验证样本。

接到任务后先做了一件事：**跑基线、看清现状到底是什么**（而不是直接改代码）——因为「只有 1 条一级目录」既可能是抽取层的误判，也可能是选型层的误判，两者修法完全不同，必须先定位到层。

---

## 二、现象（基线实跑）

用未改动的 HEAD 编译后跑目标 PDF，选型日志：

```
[BookmarkQualitySelector] catalog: total=0, effective=0, ..., score=-Infinity
[BookmarkQualitySelector] page:    total=1, effective=1, dup=0.000, unlinked=0.000,
                                   strange=0.000, nonMono=0.000, invalidLink=0.000,
                                   penalty=0.000, score=0.693
[BookmarkQualitySelector] self:   total=0, effective=0, ..., score=-Infinity
[BookmarkQualitySelector] eliminated catalog_bookmarks: empty source (total=0)
[BookmarkQualitySelector] eliminated self_bookmarks: empty source (total=0)
[BookmarkQualitySelector] selected page_bookmarks (score=0.693):
        only surviving source, no catalog
```

基线产物 JSON（`tmp_output/probe/base_one/202609011788275402820062798.json`）：

```json
"bookmarks": [
  { "text": "1.   該 等 數 字 乃 按 德 安 華 所 發 佈 的 二 零 二 五 年 規 模 溢 價 研 究 估 計 得 出。",
    "page_num": 9, "related_id": 11, "children": [], "font_size": 10.0 }
]
```

要点：
1. `page_bookmarks` 确实只有 **1 条一级目录**，且该条**没有任何子节点**；
2. 这条「标题」是 p9 的一句**正文说明文字**（正常行高 10pt），只是恰好以 `1.` 开头；
3. 由于它 `related_id` 有值、页码单调（单条必然单调）、文本无乱码，`penalty = 0`，`score = ln(1+1) × (1-0) = 0.693`；
4. 文档无自带目录（`catalog_bookmarks` 空）、无 PDF 书签（`self_bookmarks` 空），于是 page 成为**唯一非空来源**并直接胜出 → **一句正文被当作正式目录输出到 `bookmarks`**。

---

## 三、根因定位：两层同时缺判据

### 3.1 抽取层：`PageBookmarkProcessor` 只做「局部一致性」，不看「整棵树像不像目录」

现有 L1 判据是纯局部的：候选文本命中编号模板（`第#章` / `#` / `一、` 等）+ 编号从 1 起连续 + 模板在同级唯一。因此：

- 一个 `value=1` 的候选，只要模板自洽，就能**独自**满足 L1 的全部条件；
- `buildBookmarksFromCandidates` 不对「最终产出的树只有一根」做任何否决；
- 于是正文里一句以 `1.` 开头的说明段落，被当成整份文档**唯一的一级目录**。

这不是某个具体规则（如 TOC 残渣过滤、数据行否决）失效，而是**缺少一个整体性判据**：真实的目录树几乎不可能只有一根且无子级。

### 3.2 选型层：`BookmarkQualitySelector` 的评分对「条目数极少」没有否决线

评分公式：

```
penalty = dup + unlinked + strange + nonMono + invalidLink   （封顶 1）
score   = ln(1 + effectiveCount) × (1 - penalty)
```

- `effectiveCount = 1` 时，`ln(2) = 0.693`，**不产生任何 penalty**——五项比率全都是 0（单条文本必然不重复、已链接、无乱码、页码必然单调、链接必然有效）；
- `BAD_PENALTY = 0.5` 的淘汰线对「1 条干净条目」完全无效；
- 再叠加「catalog 缺席时，唯一存活来源直接胜出」的规则（`alive.size() == 1` 分支），**一个垃圾条目也能以 0.693 分成为最终 `bookmarks`**。

> 结论：这是「抽取层产出畸形树」+「选型层缺少最小规模判据」共同导致，两者都能拦住，但**用户要的语义只针对 `page_bookmarks`**，所以修复点选在抽取层出口（见 §五的落点取舍）。

---

## 四、修复前的落点取舍（为什么不改另外两个更「自然」的位置）

| 候选落点 | 是否采用 | 原因 |
|---|---|---|
| `PageBookmarkProcessor.extractPageBookmarksFromJson` 内部 | ❌ | 抽取函数有**大量**现存单测依赖「单根目录」这一中间态（如 `testTocFilter_smallChainNoAdjacency_isKept` 断言 `assertEquals(1, bookmarks.size())`），塞进去会大面积破测；且该函数还被 `extractChildrenForAnchor`（catalog 子目录补全）间接复用，语义不该被污染 |
| `BookmarkQualitySelector`（加 `effectiveCount==1` 否决） | ❌ | 选型层是三来源共用的，`effectiveCount==1` 会连带砍掉「合法但只有一条」的 `catalog_bookmarks` / `self_bookmarks`；且 `page_bookmarks` 字段在选型之前就写进 JSON 了，只改选型无法让 `page_bookmarks` 置空，达不到用户要求 |
| **抽取调用点之后统一收口**（采用） | ✅ | 只作用于 `page_bookmarks` 一个来源，且同时作用于「写进 JSON 的字段」与「送进选型的列表」，两者天然一致；两个调用点各加一层包装，语义直白 |

---

## 五、最终实现

### 5.1 `PageBookmarkProcessor`：新增单根否决（+24 行）

```401:423:java/opendataloader-pdf-core/src/main/java/org/opendataloader/pdf/processors/PageBookmarkProcessor.java
    /**
     * Discards a page bookmark tree that holds a single top-level entry.
     *
     * <p>One lone top-level node is almost never a genuine document outline: it is
     * either a body paragraph that merely starts with a numbering prefix or the
     * title of a document without any per-page heading structure. Such a tree is
     * dropped so the source is reported as empty instead of winning the quality
     * selection purely because it is the only non-empty source.</p>
     *
     * @param bookmarks freshly extracted page bookmark tree, may be null
     * @return an empty list when {@code bookmarks} holds exactly one top-level
     *         entry, otherwise {@code bookmarks} unchanged
     */
    public static List<Bookmark> discardSoleTopLevelBookmark(List<Bookmark> bookmarks) {
        if (bookmarks == null || bookmarks.size() != 1) {
            return bookmarks;
        }
        Bookmark sole = bookmarks.get(0);
        LOGGER.info(String.format(
            "[PageBookmark] discarding page bookmarks: only one top-level entry (text=%s, page_num=%s)",
            sole != null ? sole.getText() : null, sole != null ? sole.getPageNum() : null));
        return Collections.emptyList();
    }
```

设计要点：
- **只判「一级目录条数 == 1」**，不看子级、不看文本长度、不看页码——严格按用户规则字面实现，不夹带其它判据；
- `null` / 空列表 / ≥2 条**原样返回**（不做拷贝、不改顺序），对其它路径零副作用；
- 打 INFO 日志记录被丢弃的文本与页码，便于日后回溯「为什么某文档没有目录」。

### 5.2 `JsonWriter`：两个产出点都包裹

正常解析流程（`writeToCustomJson`）：

```422:425:java/opendataloader-pdf-core/src/main/java/org/opendataloader/pdf/json/JsonWriter.java
                List<Bookmark> pageBookmarks = PageBookmarkProcessor.discardSoleTopLevelBookmark(
                    PageBookmarkProcessor.extractPageBookmarksFromJson(
                        data, catalogStartPage, catalogEndPage));
```

重建书签流程（`rebuildBookmarksFromJson`）：

```212:214:java/opendataloader-pdf-core/src/main/java/org/opendataloader/pdf/json/JsonWriter.java
                List<Bookmark> pageBookmarks = PageBookmarkProcessor.discardSoleTopLevelBookmark(
                    PageBookmarkProcessor.extractPageBookmarksFromJson(
                        data, catalogStartPage, catalogEndPage));
```

> 两处都必须改：只改正常流程的话，`rebuild-bookmarks`（对已有 JSON 重算书签）仍会产出单根 `page_bookmarks`，出现「同一文档两条链路结果不一致」。

### 5.3 单测：`PageBookmarkProcessorTest` 新增 2 个用例

- `testDiscardSoleTopLevelBookmark_singleRoot_isDiscarded`：单根 → 空；并覆盖 `null` 与空列表；
- `testDiscardSoleTopLevelBookmark_multipleRoots_areKept`：两根 → `assertSame` 原样返回（确认没有多余的拷贝/重建）。

---

## 六、验证（实跑）

### 6.1 目标 PDF 前后对比

| 字段 | 改动前（base） | 改动后（after） |
|---|---|---|
| `page_bookmarks` | 1 条：`1.   該等數字乃按德安華所發佈的二零二五年規模溢價研究估計得出。`（p9 / related_id=11 / 10pt 正文） | `[]` |
| 选型 | page 唯一非空来源，score=0.693，直接胜出 | `eliminated page_bookmarks: empty source (total=0)` |
| `bookmarks` | 那条正文句 | `[]` |
| JSON 顶层 key | `page_bookmarks` 被选中后从 map 中移除 | `page_bookmarks: []` 保留（三来源全空 → `selectedSource == null`，三个 key 都留） |

after 运行日志（关键行）：

```
[PageBookmark] discarding page bookmarks: only one top-level entry
        (text=1.   該等數字乃按德安華所發佈的二零二五年規模溢價研究估計得出。, page_num=9)
[BookmarkQualitySelector] page: total=0, ..., score=-Infinity
警告: [BookmarkQualitySelector] no winner: all sources bad (...), bookmarks will be empty
```

**附带效果**：由于规则在选型之前生效，`bookmarks` 不再选中 page 来源，顶层 JSON 会把 `page_bookmarks: []` 保留下来，产物语义从「假装有目录」变成「明确无目录」。

### 6.2 对照回归（多一级目录文档不受影响）

选 `docs/pdf/181d064e-b964-471a-a7e4-a00c17620f3d.pdf`（历史产物 `page_bookmarks` 为 2 条根：`(1) 川開電氣` / `(2) 宜昌特銳德`）：

| | 历史产物 | 本次 after |
|---|---|---|
| 一级目录数 | 2 | 2 |
| `bookmarks` 条数 | 28 | 28 |
| 一级目录文本 | `(1) 川開電氣` / `(2) 宜昌特銳德` | 同上 |

→ 规则只在 `size() == 1` 时触发，多根文档零影响（逻辑上必然，实跑复核）。

### 6.3 单测实跑

`mvn test` **无法使用**（见 §八），改为：`javac` 单独编译 `PageBookmarkProcessorTest` → 反射小 runner（`SingleRootProbe`）逐个调用两个新方法：

```
PASS testDiscardSoleTopLevelBookmark_singleRoot_isDiscarded
PASS testDiscardSoleTopLevelBookmark_multipleRoots_areKept
DONE failed=0
```

---

## 七、影响面（已统计，供后续决策）

扫描 `tmp_output/**/*.json` 历史产物，`page_bookmarks` 恰为 1 条根的有 **3 份**，它们会被新规则**整体丢弃**（注意：其中 2 份的单根是**带有效子级**的，若收窄规则即可保留）：

| 文档 | 被丢弃的单根 | 子节点数 |
|---|---|---|
| `202609081788871773509077070.json` | `(1)發行及購回股份之一般授權；(2)重選董事；`（p9） | 13 |
| `202306221687332923509014994.json` | `（一）变更公司住所并修订《公司章程》`（p3） | 1 |
| `202302281677505819604328-83(流程图).json` | `（一）控股股东及实际控制人情况`（p1） | 2 |

**已向用户提出的收窄方案**：把判据改成「只有 1 条一级目录**且其子节点为空**才丢弃」，即可保住上表 3 份中的 2 份，同时仍然拦掉本例（目标 PDF 那条正文 `children: []`）。用户未答复前保持字面规则。

---

## 八、验证过程中的一个坑：模块 `mvn test` 改动前就编译不过

```
[ERROR] .../custom/utils/CustomChunksMergeUtilsTest.java:[28,53] 找不到符号
        符号: 方法 isPageBackgroundImage(BoundingBox, BoundingBox)
        位置: 类 CustomChunksMergeUtils
```

`CustomChunksMergeUtils` 里**根本没有** `isPageBackgroundImage` / `isPageBackgroundSize`，属既有问题，与本次书签改动无关。

**绕行方式**（本次采用，后续可复用）：
1. `mvn -o -pl opendataloader-pdf-core -am compile -DskipTests` 只编主源码；
2. `mvn -o -pl opendataloader-pdf-core dependency:build-classpath -Dmdep.outputFile=cp.txt` 生成 classpath（**必须离线**，本地仓库在 `D:\Maven_Repo`）；
3. `javac` 单独编译需要的那一个测试类；
4. 用反射 runner 直接调 `@Test` 方法（本地仓库**没有** `junit-platform-launcher` jar，无法用 ConsoleLauncher）。

端到端跑单份 PDF 用根目录已有的 `TmpScanRunner.java`（参数：PDF 清单文件 + 输出目录），清单文件每行一个绝对路径。

---

## 九、经验教训

1. **先跑基线再改代码**。本例如果先改代码再验证，就会错过「那条一级目录其实是 10pt 正文」这个关键事实，也无从判断规则该落在抽取层还是选型层。
2. **「局部一致性通过」≠「整体结构合理」**。`PageBookmarkProcessor` 的模板 + 连续编号判据只能保证「这批候选彼此自洽」，无法保证「这棵树像一份目录」；缺的是整体性判据（单根否决就是一个最小化的例子）。
3. **质量评分对「规模过小」天然无感**。`ln(1+n)×(1−penalty)` 在 `n=1` 且 `penalty=0` 时给 0.693 分，五项比率对单条目全部归零——**任何基于比率的 penalty 都惩罚不了「少」**。若要通用否决，需要引入绝对量判据（如 `effectiveCount < 2`），或像本次一样按来源语义在抽取层收口。
4. **同类规则改动要一次覆盖所有产出点**。`page_bookmarks` 有「正常解析」和「rebuild 书签」两条链路，只改一条会造成同文档两套结果。
5. **规则的副作用要主动量化**。改动前先统计历史产物里命中规则的文档数量（本次 3 份），比事后被用户发现要好。

---

## 十、相关文件 / 复现命令

改动文件：

- `java/opendataloader-pdf-core/src/main/java/org/opendataloader/pdf/processors/PageBookmarkProcessor.java`（+24 行，`discardSoleTopLevelBookmark`）
- `java/opendataloader-pdf-core/src/main/java/org/opendataloader/pdf/json/JsonWriter.java`（第 212-214、423-425 行包裹调用）
- `java/opendataloader-pdf-core/src/test/java/org/opendataloader/pdf/processors/PageBookmarkProcessorTest.java`（+38 行，2 个用例）

复现命令：

```powershell
# 编译（离线）
cd d:\Code\JavaCode\opendataloader-pdf\java
mvn -q -o -pl opendataloader-pdf-core -am compile -DskipTests
# 生成 classpath 后跑目标 PDF（清单见 tmp_output\probe\one.txt）
mvn -q -o -pl opendataloader-pdf-core dependency:build-classpath "-Dmdep.outputFile=d:\Code\JavaCode\opendataloader-pdf\cp.txt"
cd d:\Code\JavaCode\opendataloader-pdf
java -cp "tmp_output\probe\runner;java\opendataloader-pdf-core\target\classes;<deps>" `
     org.opendataloader.pdf.TmpScanRunner 'tmp_output\probe\one.txt' 'tmp_output\probe\after_one'
```

邻近历史：

- `2026-09-24-PageBookmarkProcessor-Step6.5单条相邻回查零影响与p534正文误当子书签定位.md` — 同文件先例，同样是「正文误当书签」族；本例的残留（p9 的 `1. 該等數字…`）同样性质是正文句被编号前缀伪装。
- `2026-08-07-目录三来源质量选型bookmarks统一输出.md` — `BookmarkQualitySelector` 的由来与评分公式背景。
- `2026-08-11-书签长度过滤与catalog强胜率.md` — 同为「在选型/抽取侧加否决判据」的先例。