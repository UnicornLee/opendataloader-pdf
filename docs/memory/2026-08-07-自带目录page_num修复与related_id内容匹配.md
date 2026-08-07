# 自带目录 page_num 修复 + self_bookmarks related_id 内容匹配

- 时间：2026-08-07
- 任务一：修复 `BookmarkUtils.getSelfBookmarks` 使 `self_bookmarks.page_num` 为真实 1 基页码
- 任务二：为 `self_bookmarks` 每个书签按页码定位对应页内容、按标题匹配文本项后把对应 item 的 `id` 赋给 `related_id`（未命中保持默认 0）

## 任务一：page_num 修复

### 根因
- 原实现调用 `bookmark.setPageNum(item.getOpenCount())`，`getOpenCount()` 返回的是 `/Count`（子项展开计数），不是页码，导致 `self_bookmarks[].page_num` 全为 0。

### 修复
- `BookmarkUtils` 新增私有 `getSelfBookmarks(PDOutlineNode, PDDocument)`，用 PDFBox 3.0.4 的 `item.findDestinationPage(doc)` + `doc.getPages().indexOf(page) + 1` 解析真实 1 基页码。
- `item.hasChildren()` 时递归 `bookmark.setChildren(...)`。
- 每条解析在 try/catch 内，异常时跳过该条页码，不中断整棵大纲。
- `getSelfBookmarks(String)` 加载 `PDDocument` 后委托私有重载；大纲为 null 时返回空列表。

## 任务二：self_bookmarks related_id 内容匹配

### 设计
- 匹配完全复用 `CatalogBookmarkProcessor` 已有私有工具，避免重复实现：
  - `normalizeBookmarkText`（去所有空白）
  - `matchBookmarkTitle`（归一化后 EXACT 相等 > PREFIX 前缀）
  - `isTextItem`（仅 `item_type == "text"`）
  - `getJsonItemFullText`
- `related_id` 直接复用 JSON item 的 `id`（`JsonName.ID`，Number），与 `PageBookmarkProcessor`/`CatalogBookmarkProcessor` 既有语义一致。
- 未命中任何匹配时保持默认值 0。

### 改动点
- `CatalogBookmarkProcessor.java`：新增 public `resolveSelfBookmarkRelatedIds(List<Bookmark>, List<Map<String,Object>> data)`（递归遍历书签树）+ 私有 `resolveSelfBookmarkRelatedId`：
  - 按 `page_num` 定位 JSON 页 `data[pageNum-1]`（越界直接返回）；
  - 归一化书签 `text`，遍历该页 `items`，仅保留 `source_type` 为 heading/paragraph 的 text 项；
  - 用 `matchBookmarkTitle` 打分，取 quality 更优者（EXACT ordinal < PREFIX），命中则 `bookmark.setRelatedId(item.id)`。
- `JsonWriter.java`：在重新读取 JSON 的 `if (config != null)` 块内（`data` 取出后）调用 `resolveSelfBookmarkRelatedIds(mapper, map, data)`：
  - 把 re-read 的 `self_bookmarks`（raw Map 列表）用 `mapper.convertValue` 转成 `List<Bookmark>`；
  - 解析后 `map.put("self_bookmarks", selfBookmarks)` 写回，最终重新序列化回 json 文件并用于 `.js`。

## 验证（重建 CLI 后跑目标 PDF docs/pdf/202303181679059838994480.pdf）
- 21 条顶层 self_bookmarks 全部命中，`page_num` 为真实 1 基页码（6、10、13、22、26、77、82…），`related_id` 非零（章节页的标题都是该页 item id 1）。
- 18 条含子书签，子书签 `related_id` 为各异 id（2、3、4、6、7、8、9、10、16…），抽查页面 15/17/54/56/60/78/147/199 均与该页标题项精确匹配。
- 无回归：`catalog_bookmarks` 109、`page_bookmarks` 17（related_id>0），`.js` 与 `_page_bookmarks_collected.md` 正常输出。

## 构建/验证命令（重要）
- 构建 CLI 时必须加 javadoc skip，PowerShell 里 JVM 参数要用引号包裹：
  `mvn -q -o -pl opendataloader-pdf-cli -am package -DskipTests "-Dmaven.javadoc.skip=true"`
- 运行：
  `java -jar java\opendataloader-pdf-cli\target\opendataloader-pdf-cli-0.0.0.jar "docs\pdf\202303181679059838994480.pdf" --output tmp_output --format json`

## 关键文件
- `java/opendataloader-pdf-core/src/main/java/org/opendataloader/pdf/custom/utils/BookmarkUtils.java`
- `java/opendataloader-pdf-core/src/main/java/org/opendataloader/pdf/processors/CatalogBookmarkProcessor.java`
- `java/opendataloader-pdf-core/src/main/java/org/opendataloader/pdf/json/JsonWriter.java`
- `java/opendataloader-pdf-core/src/main/java/org/opendataloader/pdf/custom/entities/Bookmark.java`
- 样例 PDF：`docs/pdf/202303181679059838994480.pdf`
- 输出：`tmp_output/202303181679059838994480.json`

## 仓库约定（备忘）
- `page_num` 为 1 基页码。
- `related_id` 未命中匹配时保持默认值 0。
- Java 11 源/目标；Maven 本地仓库 `D:\Maven_Repo`。
- 直接 `mvn javadoc` 会因仓库既有 javadoc 错误失败（TriageProcessor 的 `>=` 被当 HTML、PaddleOcrProcessor @throws 未抛出异常），属无关预存问题，构建须加 `-Dmaven.javadoc.skip=true`。
