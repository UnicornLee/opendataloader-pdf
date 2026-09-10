# 目录 page_num 回退至印刷页号 — 旧 JSON 缺 source_type 向后兼容修复

- 时间：2026-09-10
- 分支：`ocr-unification-20260820`
- 目标 PDF：`docs/pdf/eddf5bd0-cfce-49cb-a33c-742eec08181d.json`（用户提供的 111 页带目录 PDF，对应源 PDF 未提供）
- 复现入口：`org.opendataloader.pdf.DebugSample.main()`（默认已指向该 JSON，调用 `OpenDataLoaderPDF.rebuildBookmarks`）

## 目标（Goal）

`OpenDataLoaderPDF.rebuildBookmarks(jsonFile, config)` 处理 `eddf5bd0-cfce-49cb-a33c-742eec08181d.json` 后，目录 `bookmarks[0]` 的 `page_num` 错误地停留在 **1**，应该是 **4**（"第一章 總則" 在物理第 4 页）。用户要求：

1. 先查出真正原因，不要改代码
2. 提供解决方案
3. 不清楚的地方可以澄清

## 问题定位全过程（From Symptom to Root Cause）

### 第一步：复现 + 收集现场证据

跑 `DebugSample`（已内置指向目标 JSON）。输出日志：

```
[CatalogBookmark] detected catalog page range: 2-3 (2 pages, 32 toc lines)
[CatalogBookmark] extracted 32 bookmarks (32 top-level)
[BookmarkQualitySelector] catalog: total=32, effective=32, score=3.497
[BookmarkQualitySelector] selected catalog_bookmarks
```

→ 目录范围、条目数都正常。问题不在 catalog 检测阶段，而是 catalog bookmark → 物理页的 **解析阶段** 出错。

直接读输出 JSON：`bookmarks[0] = {page_num: 1, original_page_num: 1, related_id: 1, text: "第一章  總則"}`，`bookmarks[1] = {page_num: 6, original_page_num: 3, ...}`，等等——`original_page_num` 都是目录印刷页号（1、3、5、12…），而 `page_num` 一律等于 `original_page_num`（1、6、8…）。也就是说 **没有任何一条 bookmark 的 `page_num` 被重新解析过**。

### 第二步：把"未解析"映射到代码路径

`OpenDataLoaderPDF.rebuildBookmarks` → `JsonWriter.rebuildBookmarksFromJson`，最终调用 `CatalogBookmarkProcessor.extractCatalogBookmarksFromJson`，里面关键的"目录 bookmark → 物理页"步骤是 `resolveCatalogBookmarkTargets(roots, data, ...)`（约 207 行），其内部对每个 bookmark 调用 `resolveCatalogBookmarkTarget(bookmark, data, ...)`。

`resolveCatalogBookmarkTarget` 的核心循环（简化）：

```java
for (int pageIndex = 0; pageIndex < data.size(); pageIndex++) {
    if (pageIndex 在 catalogStart..catalogEnd 内) continue;
    for (Map<String, Object> item : items) {
        // 关键过滤：只接受 source_type 为 paragraph/heading 的 item
        String sourceType = (String) item.get("source_type");
        if (!"heading".equals(sourceType) && !"paragraph".equals(sourceType)) {
            continue;
        }
        // ... 调用 matchBookmarkTitle 计算 quality 和 distance
    }
}
if (bestMatch != null) {
    bookmark.setPageNum(bestMatch.pageIndex + 1);
    bookmark.setRelatedId(bestMatch.relatedId);
}
```

**如果循环里没有 item 通过过滤，`bestMatch` 始终为 null，方法静默返回，`pageNum` 保持初始值**。而 `pageNum` 初始值在 `extractBookmarksFromJson`（约 1272 行）里就是 `resolvePageIndex(rawPage, pageLabels, totalPages) + 1`，对 rawPage="1" 来说就是 1，对 "3" 来说就是 3，等等——也就是**目录印刷页号**。

→ 假设：候选 item 全部被 `source_type` 过滤掉了。

### 第三步：验证假设 — 读 JSON 看 item 的 schema

用 Python 读 `eddf5bd0-cfce-49cb-a33c-742eec08181d.json` 第一个 page 的 items 抽 3 条：

```json
{
  "id": 1,
  "item_type": "text",
  "paragraph": false,
  "bounding box": {"x0": 70.0, "y0": 91.03, "x1": 525.31, "y1": 104.34},
  "content": [{ "content": ["第一章  總則  ..."], "x0": 92.51, "y0": 91.03, "x1": 525.31, "y1": 104.34, "font_size": 12.79 }],
  "x0": 70.0, "y0": 91.03, "x1": 525.31, "y1": 104.34, "font_size": 12.79
}
```

**没有 `source_type` 字段**。再随机抽 50 条全部确认：715 条 text item 全部缺 `source_type`，只有 `item_type: "text"` + `paragraph: bool`。

### 第四步：交叉验证 schema 演进

`JsonName.java` 里：

```java
public static final String SOURCE_TYPE = "source_type";
public static final String SOURCE_TYPE_PARAGRAPH = "paragraph";
public static final String SOURCE_TYPE_HEADING = "heading";
```

`grep "source_type"` 当前 `JsonWriter.java` 8 个写点（655/698/741/787/838/875/892/914/951 行）都在写 `source_type`——说明 **当前 `JsonWriter` 输出会带 `source_type`**，但用户这份 JSON 是**早期版本 `JsonWriter`** 生成的（或第三方生产者输出），写入的是 `paragraph: bool` 但**没有写 `source_type`**。

而 `CatalogBookmarkProcessor.resolveCatalogBookmarkTarget` 的过滤是 **strict-only on `source_type`**，旧 schema 的 item 全部被丢弃。

### 根因（Root Cause）

`CatalogBookmarkProcessor.resolveCatalogBookmarkTarget`（约 1492 行）、`repairSelfBookmarkPageNums`（约 1647 行）、`resolveSelfBookmarkRelatedId`（约 1751 行）三处都使用相同过滤：

```java
String sourceType = (String) item.get(JsonName.SOURCE_TYPE);
if (!JsonName.SOURCE_TYPE_HEADING.equals(sourceType)
        && !JsonName.SOURCE_TYPE_PARAGRAPH.equals(sourceType)) {
    continue;
}
```

对旧版 `JsonWriter` 输出的 JSON（`item_type: "text"` 但缺 `source_type`），所有候选都被过滤掉，`bestMatch == null`，bookmark 的 `pageNum` 永远保持 `extractBookmarksFromJson` 中 `resolvePageIndex(rawPage) + 1` 的初始值——也就是目录印刷页号——导致 "第一章 總則 ... 1" 永远指向第 1 页（实际是目录页）。

同一过滤在 `repairSelfBookmarkPageNums` 和 `resolveSelfBookmarkRelatedId` 也有，用户暂未察觉这两个的下游症状，但本质相同。

## 修复方案（Solution）

### 选 A 方案：consumer 侧向后兼容过滤

新增 helper `isParagraphOrHeadingItem(Map<String, Object> item)`：

- `source_type ∈ {paragraph, heading}` → true（现行 schema）
- **`source_type == null && item_type == "text"` → true（兼容旧 schema）**
- 其他 → false（包括 `source_type == null && item_type == "image"` 等非 text 项仍被排除）

三处调用点统一改为该 helper。最小修改面，不动 `JsonWriter`、不动 `CatalogBookmarkProcessor.extractBookmarksFromJson`（目录抽取逻辑本身是对的，只是缺跨 schema 兼容）。

### 备选 B：要求旧 JSON 必须带 `source_type`，由 producer 端补齐
- 优点：根治 schema 一致性
- 缺点：要修 `JsonWriter` 重读旧 JSON 时反推 source_type 的逻辑，改动面大；用户不一定能控制 JSON 来源（"第三方生产者"分支）；破坏现有 `JsonWriter` 旧 schema → 新 schema 的迁移路径假设
- 不选

### 备选 C：放宽 isTextItem（在 analyzeJsonPages 和 extractBookmarksFromJson 里用的）
- 缺点：会污染目录抽取（让 caption/toc/list 等被当成目录行候选）；改变 TOC 检测行为，可能误检
- 不选

## 实现（Implementation）

`java/opendataloader-pdf-core/src/main/java/org/opendataloader/pdf/processors/CatalogBookmarkProcessor.java`：

1. 在 `isTextItem` 之后（约 1177 行）新增 `isParagraphOrHeadingItem` 私有 helper，附 Javadoc 解释为什么允许 `source_type == null && item_type == "text"`，并强调 `item_type` 守卫保证非 text 项仍被排除。

2. 替换 3 个调用点（行号因方法顺序略有漂移）：
   - `resolveCatalogBookmarkTarget`（约 1492 行）：catalog bookmark → 物理页解析
   - `repairSelfBookmarkPageNums`（约 1647 行）：self bookmark 的 pageNum 自愈
   - `resolveSelfBookmarkRelatedId`（约 1751 行）：self bookmark 的 relatedId 解析

3. **保持不动**：`analyzeJsonPages` 和 `collectJsonLines` 中的 `isTextItem(item)`（约 1138 行、1307 行）——这两个是遍历整页文本行做 **TOC 检测/收集**，需要包含所有文本（包括 caption/toc 行）才能识别 TOC 页，不属于 candidate 过滤的语境。

## 验证（Verification）

### 真实数据回放

恢复备份 `eddf5bd0-cfce-49cb-a33c-742eec08181d.json.bak`（md5 与原文件一致），跑 `mvn -pl opendataloader-pdf-core compile && DebugSample`，读输出 JSON 的 `bookmarks` 字段：

```
[0]  page_num=4  orig=1   text=第一章  總則           ← 修复前 page_num=1
[1]  page_num=6  orig=3   text=第二章  釋義
[2]  page_num=8  orig=5   text=第三章  股份轉讓
[3]  page_num=8  orig=5   text=第三章  ...
[4]  page_num=15 orig=12  text=第二章  ...
[5]  page_num=18 orig=15  text=第三章  ...
...
[31] page_num=110 orig=107 text=...
```

全部 32 条 `page_num` 都正确解析到物理页号，`original_page_num` 保留为目录印刷页号供审计。用户报告的 `bookmarks[0].page_num == 4` 达成。

### 回归测试

新建 `java/opendataloader-pdf-core/src/test/java/org/opendataloader/pdf/processors/CatalogBookmarkProcessorTest.java`，3 个用例：

- `testLegacyJson_resolvesToPhysicalPage`：构造 `item_type: "text"` 但缺 `source_type` 的旧 schema 数据，验证 catalog bookmark 能解析到物理页（page 2/3/4 而非 fallback 1/5/9）。
- `testCurrentSchema_resolvesToPhysicalPage`：同样数据但带 `source_type: "heading"`，验证新旧 schema 行为一致（control case，防止未来变更让新旧路径行为分叉）。
- `testLegacyJson_imageItemStillExcluded`：构造 `item_type: "image"` 缺 `source_type` 的旧 schema image 项，验证 `item_type` 守卫仍生效，image 不会被错误地当成候选。

跑：
```
mvn -pl opendataloader-pdf-core test -Dtest=CatalogBookmarkProcessorTest
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

### 全模块回归

```
mvn -pl opendataloader-pdf-core test -Dtest='*Bookmark*,DocumentProcessorPropagationTest'
[INFO] Tests run: 65, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

其他模块（`AutoTaggerTest`、`PageSeparatorIntegrationTest`）的失败是 `git stash` 后**仍然存在**的预存问题（缺文件 `1901.03003.md`、并行处理 NPE），与本次修改无关。

## 关键决策（Key Decisions）

- **不在 producer 端补 `source_type`**：用户的 JSON 来源不可控（"旧版 JsonWriter 或第三方生产者"）；在 consumer 端兼容才是稳定契约。
- **不过度放宽**：明确 `item_type == "text"` 才接受，避免 image/caption/list 等被错误识别为 heading 候选；TOC 检测路径继续用 `isTextItem`，不做 schema 兼容——因为目录检测需要扫描所有文本。
- **新建独立 helper 而非修改 `isTextItem`**：两个 helper 语义不同（`isTextItem` = "JSON 里是不是文本项"，`isParagraphOrHeadingItem` = "是否可能是目录标题候选"）。合并会改变 `analyzeJsonPages`/`collectJsonLines` 的行为。

## 关键文件（Relevant Files）

- `java/opendataloader-pdf-core/src/main/java/org/opendataloader/pdf/processors/CatalogBookmarkProcessor.java`
  - 新增 `isParagraphOrHeadingItem(Map<String, Object>)`（约 1177 行）
  - `resolveCatalogBookmarkTarget`（约 1492 行）调用点改用 helper
  - `repairSelfBookmarkPageNums`（约 1647 行）调用点改用 helper
  - `resolveSelfBookmarkRelatedId`（约 1751 行）调用点改用 helper
  - `isTextItem` / `analyzeJsonPages` / `collectJsonLines` **未改动**
- `java/opendataloader-pdf-core/src/test/java/org/opendataloader/pdf/processors/CatalogBookmarkProcessorTest.java`：新建，3 用例
- `java/opendataloader-pdf-core/src/main/java/org/opendataloader/pdf/json/JsonName.java`：未改动（仅参考 `SOURCE_TYPE_PARAGRAPH`、`SOURCE_TYPE_HEADING`、`ITEM_TYPE` 常量）
- `java/opendataloader-pdf-core/src/main/java/org/opendataloader/pdf/json/JsonWriter.java`：未改动（输出 schema 本就正确，问题不在 producer）
- `java/opendataloader-pdf-core/src/main/java/org/opendataloader/pdf/api/OpenDataLoaderPDF.java`：未改动
- `java/opendataloader-pdf-core/src/main/java/org/opendataloader/pdf/DebugSample.java`：未改动（默认已指向目标 JSON）
- `docs/pdf/eddf5bd0-cfce-49cb-a33c-742eec08181d.json`：目标 JSON
- `docs/pdf/eddf5bd0-cfce-49cb-a33c-742eec08181d.json.bak`：调试前备份（md5 一致，未被修改）

## 仓库约定（备忘）

- `page_num` 为 1 基页码。
- `related_id` 未命中匹配时保持默认值 0。
- Java 11 源/目标；Maven 本地仓库 `D:\Maven_Repo`。
- 直接 `mvn javadoc` 会因仓库既有 javadoc 错误失败（TriageProcessor 的 `>=` 被当 HTML、PaddleOcrProcessor @throws 未抛出异常），属无关预存问题，构建须加 `-Dmaven.javadoc.skip=true`。
- `DebugSample` 默认带 paddleUrl / OSS 配置，复现 catalog bookmark 行为可保留；如要避免网络依赖可注释 paddleUrl。
- 跨 schema 兼容性的最小代价写法：helper + 严格的 `item_type` 守卫 + 文档说明场景适用范围。

## 后续可能工作

- 若 `JsonWriter` 输出 `source_type` 成为契约，未来可以考虑在 helper 里加 `// TODO: remove legacy fallback when all in-flight JSON migrates to current schema` 提示，配合 `JsonWriter` 版本号在 output 头部写入字段（如 `schema_version`），consumer 据此切分支。
- 如果有第三批 schema 演进（如 `paragraph` 字段被替换为 `kind` 等），同样走 helper 兼容模式即可。
