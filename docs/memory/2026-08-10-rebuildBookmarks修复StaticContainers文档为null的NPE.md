# opendataloader-pdf 任务记忆 — 2026-08-10（rebuildBookmarks 修复 StaticContainers 文档为 null 的 NPE）

## 目标（Goal）
- 修复 `DebugSample` 调 `OpenDataLoaderPDF.rebuildBookmarks(jsonPath, config)` 时抛出的 NPE：
  ```
  Exception in thread "main" java.lang.NullPointerException: Cannot invoke
    "org.verapdf.wcag.algorithms.entities.IDocument.getNumberOfPages()"
    because the return value of
    "StaticContainers.getDocument()" is null
    at CatalogBookmarkProcessor.collectPageLabels(CatalogBookmarkProcessor.java:331)
    ...
    at OpenDataLoaderPDF.rebuildBookmarks(OpenDataLoaderPDF.java:69)
    at DebugSample.main(DebugSample.java:40)
  ```
- 让 `rebuildBookmarks`（不重新解析 PDF，纯 JSON 重建书签）在 `StaticContainers` 未注册 `IDocument` 时仍能跑完 `catalog_bookmarks` / `page_bookmarks` / `bookmarks` 三条流水线。

## 根因（Root Cause）
- `OpenDataLoaderPDF.rebuildBookmarks` → `JsonWriter.rebuildBookmarksFromJson` 不解析 PDF，仅读 JSON 后依次调用：
  - `CatalogBookmarkProcessor.extractCatalogBookmarksFromJson(data, config)`
  - `PageBookmarkProcessor.extractPageBookmarksFromJson(data, ...)`
- 正常 `processFile` 路径会先在 `DocumentProcessor.preprocessing(...)` 中 `StaticContainers.setDocument(document)`，但 `rebuildBookmarksFromJson` 不走这条初始化。
- `CatalogBookmarkProcessor` 中三个私有静态方法仍依赖 `StaticContainers.getDocument().getNumberOfPages()`：
  - `collectPageLabels()`（用于 Roman / 自定义页标签匹配）
  - `resolvePageIndex(String, Set<String>)`（TOC 阿拉伯页码 → 物理页索引）
  - `buildPageLabelMap(Set<String>)`（label → 页索引映射）
- 这三个方法在 JSON-only 路径下首次调用即 NPE，文件根本写不回去。

## 修复策略
- **不修改 API 入口**：保持 `rebuildBookmarks(jsonPath, config)` 签名不变，不引入"传 PDF 才能跑"的隐性约束。
- **`collectPageLabels()` 加 null 守卫**：文档为 null 时返回空集合。JSON 数据不含 PDF 文档级 page labels 元数据，罗马/自定义标签匹配降级为"无匹配"即可，不影响阿拉伯数字 TOC 检测。
- **`resolvePageIndex` / `buildPageLabelMap` 加 `int totalPages` 形参**：从调用方传入，移除内部对 `StaticContainers` 的访问。调用方：
  - IObject 路径 `extractCatalogBookmarks(contents, ...)` 传 `contents.size()`
  - JSON 路径 `extractCatalogBookmarksFromJson(data, ...)` 传 `data.size()`
- **`extractBookmarks` / `extractBookmarksFromJson` 同步加 `int totalPages` 形参**并向 `resolvePageIndex` 透传。

## 实现（Implementation）
- `CatalogBookmarkProcessor.java`：
  - 新增 import `org.verapdf.wcag.algorithms.entities.IDocument`。
  - `collectPageLabels()`：先取 `IDocument document = StaticContainers.getDocument(); document == null` 直接 `return labels;`（空 `HashSet`）。
  - `resolvePageIndex(String rawPage, Set<String> pageLabels, int totalPages)`：去掉 `int totalPages = StaticContainers.getDocument().getNumberOfPages();`，改用形参。
  - `buildPageLabelMap(Set<String> pageLabels, int totalPages)`：同上。
  - `extractBookmarks(List<List<IObject>>, PageRange, Set<String>, int totalPages)`：形参新增 `totalPages`，内部 `resolvePageIndex(..., totalPages)` 调用同步更新。
  - `extractBookmarksFromJson(List<Map<String,Object>>, JsonPageRange, Set<String>, int totalPages)`：同上。
  - 调用方：
    - `extractCatalogBookmarks` 中 `int totalPages = contents.size(); extractBookmarks(contents, bestRange, pageLabels, totalPages);`
    - `extractCatalogBookmarksFromJson` 中 `int totalPages = data.size(); extractBookmarksFromJson(data, bestRange, pageLabels, totalPages);`
- 无其它文件改动。`PageBookmarkProcessor.extractPageBookmarksFromJson` 本就不依赖 `StaticContainers.getDocument()`，无需同步修改。

## 关键决策（Key Decisions）
- **保持 `extractCatalogBookmarks` / `extractCatalogBookmarksFromJson` 的公共签名不变**：调用方只增加局部 `int totalPages` 变量，不破坏 `CatalogBookmarkProcessor` 已有 API 契约。
- **`collectPageLabels` 用 `IDocument document = StaticContainers.getDocument()` 局部缓存**：避免在 `null` 守卫之后又连调两次 `getDocument()`（一次判 null，一次后续循环），原代码就是两次调用都 NPE。
- **不引入"重新解析 PDF 顺便初始化 StaticContainers"的方案**：那样会让 `rebuildBookmarks` 退化回"完整跑一遍 processFile"，违背"只重建书签相关字段、不重做 OCR / 图片上传"的初衷。
- **JSON 路径下 pageLabels 为空是合理降级**：阿拉伯数字（`^\d{1,5}$`）走 `Integer.parseInt` 仍可正确解析；Roman / 自定义 label 不可能从 JSON 推导，放弃匹配是正确取舍。
- **不新建辅助类/工具方法**：仅一个 5 行 null 守卫 + 两个方法签名调整，patch 极小，便于回溯。

## 验证结果
- `mvn -pl opendataloader-pdf-core compile`：✅ **0 错误**（14 warning 全部是已有 deprecation 警告，与本次无关）。
- `mvn -pl opendataloader-pdf-core exec:java -Dexec.mainClass=org.opendataloader.pdf.DebugSample -q`：
  - 日志输出 `detected catalog page range from JSON: 3-3 (1 pages, 12 toc items)`
  - `extracted 12 bookmarks (12 top-level) from JSON range 3-3`
  - `complemented 10 catalog bookmark group(s) from page data`
  - `BookmarkQualitySelector selected self_bookmarks (score=5.762): ... clear win`
  - 最后打印 `Rebuilt JSON URL / local path: D:\...\tmp_output\202604291777459885575001342.json`
  - **不再抛 NPE**。
- 实际产出的 JSON 中 `bookmarks` / `catalog_bookmarks` / `page_bookmarks` / `self_bookmarks` / `catalog_page_range_start/end` 五个键均写入成功。

## 构建 / 运行
- 编译（项目根目录 PowerShell）：
  ```powershell
  mvn -q -f java\opendataloader-pdf-core\pom.xml -o compile
  ```
- 跑 DebugSample（验证 NPE 已修复）：
  ```powershell
  cd D:\Code\JavaCode\opendataloader-pdf-parse\opendataloader-pdf\java
  mvn -pl opendataloader-pdf-core exec:java "-Dexec.mainClass=org.opendataloader.pdf.DebugSample" -q
  ```
- 注意 PowerShell 中 `-Dexec.mainClass=...` 必须加引号，否则 `=` 后被当成参数值截断；输出日志若中文乱码，参考已有 `2026-08-09-ShapeRecognizer忽略白色矢量图形避免bar_chart误识别.md` 中"JVM 参数加引号"的小结。

## 相关文件（Relevant Files）
- `java\opendataloader-pdf-core\src\main\java\org\opendataloader\pdf\processors\CatalogBookmarkProcessor.java`：
  - import 新增 `IDocument`；
  - `collectPageLabels()` 加 null 守卫；
  - `resolvePageIndex` / `buildPageLabelMap` 加 `int totalPages` 形参；
  - `extractBookmarks` / `extractBookmarksFromJson` 加 `int totalPages` 形参并透传；
  - 两个公共入口（`extractCatalogBookmarks`、`extractCatalogBookmarksFromJson`）用局部 `int totalPages = contents.size() / data.size()` 传入。
- `java\opendataloader-pdf-core\src\main\java\org\opendataloader\pdf\DebugSample.java`：本次未改，仅作为复现 / 验证入口。
- `java\opendataloader-pdf-core\src\main\java\org\opendataloader\pdf\json\JsonWriter.java`：本次未改，`rebuildBookmarksFromJson` 已有的 `catalog_bookmarks` → `page_bookmarks` → `fillCatalogChildrenFromPageData` → `BookmarkQualitySelector.select` 流水线即可正常贯通。
- `docs\plans\fix-rebuild-bookmarks-npe.md`：本任务 plan 存档（仓库 `.agents\plans\` 也保留一份）。
- `tmp_output\202604291777459885575001342.json`：本次 DebugSample 验证使用的输入 JSON。