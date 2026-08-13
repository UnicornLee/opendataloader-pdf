# 2026-08-13 — self_bookmarks page_num=0 节点比例阈值淘汰 + 前后锚点范围匹配修复

## 任务背景

`JsonWriter.writeToCustomJson` 中已有两步针对 self_bookmarks 的预处理：
1. `resolveSelfBookmarkRelatedIds`（2026-08-07 任务二新增）：按现有 `page_num` 在对应 JSON 页里匹配标题文本回填 `related_id`，但 **`page_num ≤ 0` 的节点会被跳过**。
2. `BookmarkQualitySelector.select`：评估三个来源（catalog / page / self）的质量并选出最优写入 `bookmarks`。

当原 PDF 自带目录存在 `page_num=0`（即大纲里没给出页码、PDFBox 解析又补不上）时，步骤 1 跳过 → `related_id=0`、pageNum 仍为 0 → 进入选择器时 `unlinkedRatio` 升高、`nonMonotonicRatio` 升高 → self 来源被判低质量并输给 catalog/page。原本可救的自带目录被白白丢弃。

用户目标：在 `BookmarkQualitySelector.buildPageItemIds(data)`（约 L848）**之前**对 self_bookmarks 做一次修复：
1. 递归统计整棵树（含 children）`page_num == 0` 的占比；**超过 30% 则整本丢弃**（`selfBookmarks.clear()`），避免污染选择器。
2. 否则对每个 `page_num=0` 节点 B，按 **DFS 先序扁平列表**找 B 之前/之后 `pageNum != 0` 的最近锚点（prev / next），搜索范围取 `[prev != null ? prev.pageNum : 1, next != null ? next.pageNum : data.size()]`。
3. 范围内按页遍历 heading/paragraph 文本项，复用 `CatalogBookmarkProcessor.matchBookmarkTitle`（EXACT > PREFIX）做内容匹配；命中项 `related_id` 受顺序约束：
   - `match.pageNum == prev.pageNum` → `match.related_id > prev.related_id`
   - `match.pageNum == next.pageNum` → `match.related_id < next.related_id`
   - 其余区间无限制。
4. 命中即设置 B.pageNum / B.relatedId；未命中回退到 `B.pageNum = prev != null ? prev.pageNum + 1 : 1`，`related_id` 保持 0。

测试 PDF：`docs/pdf/202304271682523609840984.pdf`（自带目录存在 page_num=0 的节点）。

---

## 方案确认

通过 4 次 `AskUserQuestion` 确认了以下分歧点：

| 问题 | 用户选择 |
|---|---|
| pageNum=0 统计与恢复是否递归整棵树 | **递归整棵树（含 children）** |
| 前后锚点的查找顺序 | **DFS 先序扁平遍历** |
| 范围内匹配规则 | **复用 `matchBookmarkTitle`（EXACT > PREFIX）** |
| 未命中时回退策略 | **`prev != null ? prev.pageNum + 1 : 1`，related_id 保持 0** |

方案用 `ExitPlanMode` 提交并获得批准。

---

## 实施清单（计划）

1. `CatalogBookmarkProcessor.java`
   - 新增常量 `SELF_BOOKMARK_ZERO_PAGE_RATIO_THRESHOLD = 0.30`
   - 新增 `public static void repairSelfBookmarkPageNums(List<Bookmark>, List<Map<String, Object>>)`
   - 新增私有 helper `flattenPreOrder`（DFS 先序展平）、`findAnchor`（向前/后找 pageNum!=0 锚点）
   - 复用现有私有工具：`normalizeBookmarkText`、`matchBookmarkTitle`、`isTextItem`、`getJsonItemFullText`、`MatchQuality`
2. `JsonWriter.java`
   - 在 `writeToCustomJson` 的 `if (data != null)` 块内，`resolveSelfBookmarkRelatedIds` 后、`extractCatalogBookmarksFromJson` 前调用新增 helper
   - 新增私有 helper `repairSelfBookmarkRelatedPageNums`，结构与 `resolveSelfBookmarkRelatedIds` 一致（load → 处理 → `map.put("self_bookmarks", ...)`）
3. `DebugSample.java`
   - 把 `processFile(...)` 的 PDF 路径临时切到 `202304271682523609840984.pdf` 用于验证

---

## 实施过程与踩坑

### 第一次跑：JSON 输出看似没变化

按计划改完三个文件，`mvn clean compile -DskipTests` 通过；`java -cp ... org.opendataloader.pdf.DebugSample` 跑 `202304271682523609840984.pdf`，输出关键日志：

```
[BookmarkQualitySelector] self: total=481, effective=462, dup=0.040, unlinked=0.004, ...
[BookmarkQualitySelector] selected self_bookmarks (score=5.844): ...
```

但用 node 解析 `tmp_output/202304271682523609840984.json` 检查 `bookmarks`：

```
bookmarks[1].children[4] "五、主要会计数据和财务指标" page_num=0 related_id=0
bookmarks[1].children[5] "六、分季度主要财务指标" page_num=0 related_id=0
```

zeroPage=2、zeroRel=2 — **修复未发生**。

### 第一次定位：方法可能没被调用

`Select-String -Pattern "repair" run.log` **完全没有匹配** —— 我的方法日志 `[CatalogBookmarkProcessor] self_bookmarks repair: ...` 一行都没打印。

推断可能性：
- (a) 编译用的是旧字节码；
- (b) 编译产物没问题但调用没走到。

### 第二次定位：调到了错的 `if (data != null)` 块

`javap -p target/classes/.../CatalogBookmarkProcessor.class | grep repair`：

```
public static void repairSelfBookmarkPageNums(java.util.List<org.opendataloader.pdf.custom.entities.Bookmark>, java.util.List<java.util.Map<java.lang.String, java.lang.Object>>);
```

→ 方法在 jar 里，是编译问题排除 (a)。

接着用 `SearchContentsByRegex` 搜 `if \(data != null\) \{` 在 `JsonWriter.java` 里出现 **两次**：

1. **L169 附近**（在 `rebuildBookmarksFromJson` 方法体内，约 L144）：
   ```java
   @SuppressWarnings("unchecked")
   List<Map<String, Object>> data = (List<Map<String, Object>>) map.get(JsonName.DATA);
   if (data != null) {
       resolveSelfBookmarkRelatedIds(mapper, map, data);
       repairSelfBookmarkRelatedPageNums(mapper, map, data);   // ← 我加到了这里
       CatalogBookmarkProcessor.CatalogResult catalogResult =
           CatalogBookmarkProcessor.extractCatalogBookmarksFromJson(data, config);
       ...
   }
   ```

2. **L818 附近**（在 `writeToCustomJson` 方法体内，**用户实际指的位置 ≈ L848**）：
   ```java
   if (config != null) {
       ...
       if (data != null) {
           resolveSelfBookmarkRelatedIds(mapper, map, data);
           // ← 这里应该插但被我漏了
           CatalogBookmarkProcessor.CatalogResult catalogResult =
               CatalogBookmarkProcessor.extractCatalogBookmarksFromJson(data, config);
           ...
       }
       ...
   }
   ```

**根因**：当初我用 `EditFile` 锚定的原文 `"if (data != null) {\n                    resolveSelfBookmarkRelatedIds(mapper, map, data);\n                    CatalogBookmarkProcessor.CatalogResult catalogResult =\n                        CatalogBookmarkProcessor.extractCatalogBookmarksFromJson(data, config);"` 在 **两个块里都存在且完全相同**（缩进也一致），`EditFile` 命中的是 **先出现的那一处**——也就是 `rebuildBookmarksFromJson` 的块。`rebuildBookmarksFromJson` 的代码路径只有在外部显式调用 `JsonWriter.rebuildBookmarksFromJson(...)`（如 CLI `--rebuild-bookmarks`）才会触发，`DebugSample` 走的是 `writeToCustomJson` 路径，所以我的调用根本没跑到。

### 修复：把调用挪回正确的位置

1. `EditFile` 把第一处（`rebuildBookmarksFromJson` 内的）调用删掉。
2. `EditFile` 在第二处（`writeToCustomJson` 内的）`resolveSelfBookmarkRelatedIds(mapper, map, data);` 之后插入 `repairSelfBookmarkRelatedPageNums(mapper, map, data);`。

### 临时加调试日志辅助验证

为防止再次被静默吞掉，在 `repairSelfBookmarkPageNums` 入口加了：

```java
LOGGER.log(Level.INFO, "[CatalogBookmarkProcessor] repairSelfBookmarkPageNums: entered, total=N, zeroCount=M");
```

第二次跑：`Select-String` 立刻捞出 2 条 `repairSelfBookmarkPageNums` 日志，说明已确认方法被实际调用。修复后清理掉入口调试日志，只保留结果汇总日志。

### 调试期间踩过的 PowerShell 坑

- PowerShell 不识别 `mvn ... -Dxxx=yyy`，会把 `=` 当成无效结束符（`Unknown lifecycle phase ".xxx=yyy"`）。改用 `mvn ... "-Dxxx=yyy"` 加引号包裹可绕过，但更可靠的做法是直接用 `java -cp <cp.txt>;target/classes;src/main/resources Main` 跑 main。
- PowerShell 没有 `tail`，但有 `Select-Object -Last N`；管道转发日志请用 `Tee-Object -FilePath xxx.log | Out-Null` 而不是 `>` 重定向（后者会被 PowerShell 当 UTF-16 写入导致 `Select-String` 读不到）。

---

## 验证

### 编译

`mvn clean compile -DskipTests` — BUILD SUCCESS；只有原本就有的 `PaddleOcrResultUtils` deprecation / `JsonWriter` unchecked 警告，与本任务无关。

### DebugSample 跑目标 PDF

```
$cp = (Get-Content cp.txt -Raw).Trim() + ";target\classes;src\main\resources"
java -cp $cp org.opendataloader.pdf.DebugSample
```

日志关键行：

```
[CatalogBookmarkProcessor] self_bookmarks repair: 2 repaired by content match, 0 by fallback page rule (zero ratio 0.4% <= 30%)
[BookmarkQualitySelector] self: total=481, effective=462, dup=0.040, unlinked=0.000, strange=0.000, nonMono=0.000, invalidLink=0.000, penalty=0.040, score=5.895
[BookmarkQualitySelector] selected self_bookmarks (score=5.895): ...
```

### JSON 校验（node 脚本读 `tmp_output/202304271682523609840984.json`）

| 指标 | 修复前 | 修复后 |
|---|---|---|
| `bookmarks` 总节点（递归） | 481 | 481 |
| `page_num=0` 节点 | **2** | **0** |
| `related_id=0` 节点 | **2** | **0** |
| self 评分 | 5.844 | **5.895** |

被修复的两个节点（`bookmarks[1].children[4]`、`bookmarks[1].children[5]`）：

| 文本 | page_num | related_id |
|---|---|---|
| 五、主要会计数据和财务指标 | **8** | **4** |
| 六、分季度主要财务指标 | **8** | **12** |

- `page_num=8` 与上下文一致：父节点 `bookmarks[1]` "第二节公司简介和主要财务指标" 的 page_num=7，"五/六" 作为其 children 落在第 8 页合情合理。
- 同页 related_id 顺序 `4 < 12` 与文档顺序一致（"五、" 在 "六、" 之前），相关 id 顺序约束 `prev.pageNum == 8` 区间生效。

---

## 改动文件

### `java/opendataloader-pdf-core/src/main/java/org/opendataloader/pdf/processors/CatalogBookmarkProcessor.java`

1. 常量：
   ```java
   private static final double SELF_BOOKMARK_ZERO_PAGE_RATIO_THRESHOLD = 0.30;
   ```

2. 新增 public：
   ```java
   public static void repairSelfBookmarkPageNums(List<Bookmark> selfBookmarks,
                                                 List<Map<String, Object>> data)
   ```
   - `flattenPreOrder` DFS 先序扁平
   - 统计 `zeroCount` / `total`
   - `zeroCount == 0` → 直接返回
   - `zeroCount / total > 0.30` → `selfBookmarks.clear()`，记 WARN 日志
   - 否则遍历 `flat` 中每个 `pageNum==0` 节点：`findAnchor(direction=-1)` 找 prev、`findAnchor(direction=+1)` 找 next
   - 范围 `[max(1, prev.pageNum), min(data.size(), next.pageNum)]`
   - 范围每页：复用 `isTextItem` + `SOURCE_TYPE` in {heading, paragraph} + `matchBookmarkTitle`，按用户的三条 related_id 顺序约束过滤候选；取当前页 `quality.ordinal()` 最小者（即 EXACT > PREFIX）；命中跳出
   - 未命中 → 回退 `pageNum = (prev != null ? prev.pageNum + 1 : 1)`，限制在 `[1, data.size()]`
   - 全程 INFO 日志：repaired / fallback 计数 + zero ratio

3. 新增私有 helper：
   ```java
   private static void flattenPreOrder(List<Bookmark>, List<Bookmark> out)
   private static Bookmark findAnchor(List<Bookmark> flat, int index, int direction)
   ```

### `java/opendataloader-pdf-core/src/main/java/org/opendataloader/pdf/json/JsonWriter.java`

1. 调用点（**`writeToCustomJson` 内**，约 L817，**不是** `rebuildBookmarksFromJson`）：
   ```java
   if (data != null) {
       resolveSelfBookmarkRelatedIds(mapper, map, data);
       repairSelfBookmarkRelatedPageNums(mapper, map, data);   // 新增
       CatalogBookmarkProcessor.CatalogResult catalogResult =
           CatalogBookmarkProcessor.extractCatalogBookmarksFromJson(data, config);
       ...
   }
   ```

2. 私有 helper（与 `resolveSelfBookmarkRelatedIds` 同结构）：
   ```java
   private static void repairSelfBookmarkRelatedPageNums(ObjectMapper mapper,
                                                        Map<String, Object> map,
                                                        List<Map<String, Object>> data) {
       Object selfObj = map.get("self_bookmarks");
       if (!(selfObj instanceof List)) {
           return;
       }
       List<Bookmark> selfBookmarks = mapper.convertValue(selfObj, new TypeReference<List<Bookmark>>() {});
       CatalogBookmarkProcessor.repairSelfBookmarkPageNums(selfBookmarks, data);
       map.put("self_bookmarks", selfBookmarks);
   }
   ```

### `java/opendataloader-pdf-core/src/main/java/org/opendataloader/pdf/DebugSample.java`

- 临时把 `OpenDataLoaderPDF.processFile(...)` 的 PDF 路径切到 `D:\Code\JavaCode\opendataloader-pdf\docs\pdf\202304271682523609840984.pdf` 用于验证（**未回滚**，待用户决定是否还原）。

---

## 经验与备忘

- `JsonWriter.java` 内有 **两个** `if (data != null) { resolveSelfBookmarkRelatedIds(...); CatalogBookmarkProcessor.extractCatalogBookmarksFromJson(...); }` 块：分别属于 `rebuildBookmarksFromJson`（CLI `--rebuild-bookmarks` 走的路径）和 `writeToCustomJson`（PDF → JSON 主路径）。`EditFile` 的精确匹配若两处文本完全相同，可能命中先出现的那一处；改之前先 `SearchContentsByRegex` 数一下有几处，确定要锚定的是哪一处。
- 对 `BookmarkQualitySelector` 的 `unlinkedRatio` / `nonMonotonicRatio` 而言，page_num=0 节点是双杀（related_id=0、且与 prev pageNum 比较时一定非单调）。先修 page_num 再谈 related_id，能让两个指标同步改善。
- 在 `BookmarkQualitySelector.buildPageItemIds(data)` 之前修 page_num 是有意的——选择器会复用 `pageItemIds` 校验 related_id 是否真在那一页；修复阶段只引用 `data` 自己、不会用 `pageItemIds`。
- 验证修复是否真生效的两条铁律：
  1. **必有 INFO/WARN 日志**：在入口或关键节点加一条带计数 / 阈值的日志，跑完 `Select-String -Pattern "修复关键字"`；日志没出来 = 方法没被调用 = 调用点放错地方。
  2. **JSON 必须复查**：`grep page_num=0` 不够（Jackson 序列化会把 0 写成 `"page_num": 0`，但如果 pageNum 已经被 set 过但被同名变量覆盖则可能保留 0）；用 node/python 脚本递归统计 zeroPage / zeroRel 才稳。

## 相关文件
- `java/opendataloader-pdf-core/src/main/java/org/opendataloader/pdf/processors/CatalogBookmarkProcessor.java`
- `java/opendataloader-pdf-core/src/main/java/org/opendataloader/pdf/json/JsonWriter.java`
- `java/opendataloader-pdf-core/src/main/java/org/opendataloader/pdf/DebugSample.java`
- `java/opendataloader-pdf-core/src/main/java/org/opendataloader/pdf/custom/utils/BookmarkQualitySelector.java`（下游消费者，本任务未改）
- 样例 PDF：`docs/pdf/202304271682523609840984.pdf`
- 输出：`D:\Code\JavaCode\opendataloader-pdf-parse\opendataloader-pdf\tmp_output\202304271682523609840984.json`