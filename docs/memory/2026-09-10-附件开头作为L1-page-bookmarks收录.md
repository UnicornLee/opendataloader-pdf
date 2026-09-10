# 2026-09-10 — 附件一 / 附件二 作为 L1 page_bookmarks 收录

## 任务背景
`PageBookmarkProcessor` 当前只识别阿拉伯数字 / 中文数字 + 顿号 / `（一）` / `1.` / `第#章` 等**章节模板**作为 L1/L2/L3 候选。
部分公告 PDF（如 `202609081788820439424027803.pdf`，广东领益智造 2026 年第一次临时股东会通知）正文里夹带「附件一：xxx」「附件二：xxx」两页，整页从这两个字起头，正文其实是「参会网络投票的具体操作流程」「授权委托书」。这两页当前**完全不会**出现在 `bookmarks` 顶级，读者跳转时只能跳到「六、备查文件」。

需要把「附件一：」「附件二：」这类整页首段识别成 L1 bookmark。

## 根因 / 定位过程

### 1. 现有 L1 识别路径
- `PageBookmarkProcessor.collectCandidates(List<List<IObject>>)` 与 `collectJsonCandidates(List<Map<String,Object>>)` 是两条候选收集路径（IObject 走 `CustomSemanticParagraph/SemanticHeading`，JSON 走 `source_type ∈ {paragraph, heading}`）。
- 候选进入 `Candidate` 后按 `PATTERNS`（`第#章 / 第X章 / 一、/ （一）/ 1./ I./ ...`）模板分类，每条候选有 `TemplateKey = (template, numberSystem)`。
- `extractLevel(start, end, level, usedTemplates)` 递归调用 `selectTemplateForLevel` 选出本层最佳模板，再用 `cleanCandidates` 过滤后切片。

### 2. 为什么「附件一」当前进不来
- 「附件一：xxx」不会匹配 `PATTERNS` 中任何模板（不以「第」「一」+ 顿号 / paren / 点开头），所以**根本进不了** `collectCandidates` 的候选池。
- 即便能进，`selectTemplateForLevel` 按 `font desc → density → count → indent → page → topY` 排序，「附件一」作为单页单条，无法与一级目录的「一 / 二 / 三 / 四 / 五 / 六」在模板维度上竞争。
- 结论：必须**新增一条平行候选收集路径**，让「附件一/二/三」绕过模板匹配直接进池。

### 3. 设计约束澄清（与用户 5 轮往返）
1. **JSON 缺 `source_type` 的兼容** — 老 `JsonWriter` 输出的 JSON 只有 `item_type == "text"`，没有 `source_type`。判断「首段是不是文本」时也要接受 `item_type == "text"`。
2. **L2/L3 不特殊处理 ≠ 附件 L1 是空的** — 用户明确：「L2/L3 不考虑『附件』特殊处理」不是「附件 L1 下不能挂 L2/L3」。附件 L1 仍是一个正常的章节锚点，参与 `extractLevel` 的区间切片，子级照常抽取。
3. **IObject 路径也要加** — 不仅 `collectJsonCandidates`，`collectCandidates(IObject)` 也要有对应逻辑，保证 IObject 流水线（如 `rebuildBookmarks` 走 hybrid 模式时不依赖 OCR JSON）也能识别。
4. **`String.length()` 即可** — 不需要按 codepoint / grapheme cluster 数。
5. **沿用 catalog 跳过规则** — 目录页范围内的附件跳过，与现有 `collectCandidates` 的 `catalogStartPage / catalogEndPage` 行为保持一致。

### 4. 设计要点
- 附件不是 L1「模板」是 L1「锚点」。模板匹配系统里塞一个 `ATTACHMENT_MARKER` 模板会和 `PATTERNS` 真实模板混淆。
- 走法：附件候选用一份**专属 TemplateKey**（`("ATTACHMENT_MARKER", NumberSystem.ARABIC)`），单独一份 `isAttachment = true` 字段打标；
  - `selectTemplateForLevel` 在遍历 candidates 时**跳过** `c.isAttachment`（不让它干扰模板排序）；
  - `extractLevel(level=1)` 在 `cleanedIndices` 阶段把附件候选的 index 一并纳入（L1 锚点集合）；
  - 当一段没有任何常规模板选中（极端：文档全是附件页），需要 fallback：仅附件候选时直接把它们各发成 L1 bookmark，区间仍是 `start..end`（占满整段）。
- `extractLevel` 在 level 1 之外（L2/L3）**不**对附件做任何特殊处理——子级抽取照常按父级区间切片，对附件的 `isAttachment=true` 一无所知。
- 附件候选必须**位于页首**：`firstParagraphOrHeading` 在 IObject 路径上是首个 `CustomSemanticParagraph / SemanticHeading`；JSON 路径上是首个 `source_type ∈ {paragraph, heading}` 或 (`source_type` 缺失 ∧ `item_type == "text"`) 的 item。
- 文本规则：`text.trim().startsWith("附件") && text.trim().length() <= 20`。`length()` 是 Java 的 `String#length()`（UTF-16 code unit 数），4 个 codepoint 的「附件一：」正好 4 个 code unit，远低于 20，对长标题（如「附件一：广东领益智造股份有限公司2026年第一次临时股东会通知全文」36 字）会自然拒掉。

## 改动清单

### 1. `processors/PageBookmarkProcessor.java`
- 新增常量：
  - `MAX_ATTACHMENT_TEXT_LENGTH = 20`
  - `ATTACHMENT_PREFIX = "附件"`
  - `ATTACHMENT_TEMPLATE_KEY = new TemplateKey("ATTACHMENT_MARKER", NumberSystem.ARABIC)`
- `Candidate` 新增 `boolean isAttachment` 字段，新增带 `isAttachment` 的全参数构造方法（旧的 14 参构造保留兼容）。
- 新增 4 个 helper（紧跟 `collectJsonCandidates` 后）：
  - `isAttachmentText(String)` — 前缀 + 长度
  - `firstParagraphOrHeading(List<IObject>)` — IObject 路径取页首段
  - `collectAttachmentCandidates(...)` — IObject 路径
  - `isJsonTextLikeItem(Map)` — `source_type ∈ {paragraph, heading}` 或 (缺 `source_type` ∧ `item_type == "text"`)
  - `collectAttachmentCandidatesFromJson(...)` — JSON 路径
- `extractPageBookmarks(List<List<IObject>>)` 与 `extractPageBookmarksFromJson(...)`：
  - 调对应 `collectAttachmentCandidates(...)` 拿到附件候选；
  - `candidates.addAll(attachments)`，统一走 `buildBookmarksFromCandidates`。
- `selectTemplateForLevel(...)`：
  - 在选模板主循环 `for (Candidate c : candidates)` 入口加 `if (c.isAttachment) continue;` — 附件候选不参与模板打分。
- `extractLevel(int start, int end, int level, Set<TemplateKey> usedTemplates)` level == 1 分支：
  - 现有 `cleanedIndices` 计算后，把所有 `candidates.get(i).isAttachment` 的下标也并入 `cleanedIndices`（附件成为 L1 锚点）；
  - 新增 fallback：当 `selectedTemplate == null` 且 `cleanedIndices` 仅含附件时，逐条把附件发成 L1 bookmark，子级调用 `extractLevel(attIdx, end, 2, ...)` 正常递归抽取。

### 2. `test/processors/PageBookmarkProcessorTest.java`
- 新增 helper `attachmentItem(int id, String text, double y0)` — 构造 `source_type=paragraph` + `content=[text]` 的 JSON item。
- 新增 6 个测试用例（5 个新加 + 1 个原列表内修正）：
  - `testAttachmentBookmarkInjectedAsL1` — 两页各 1 个 `附件一/二` + 1 个 `一/二`，断言 4 条 L1。
  - `testAttachmentBookmarkComesBeforeRegularL1OnSamePage` — 附件在同页必须排在常规 L1 之前（按 topY 排序）。
  - `testAttachmentBookmarkSkippedOnCatalogPage` — `catalogStartPage=0, catalogEndPage=1` 时全部跳过。
  - `testAttachmentBookmarkTooLongIgnored` — 22 codepoint 的长标题拒收（fixture 文本本身也带 assertion 防止后续被改短）。
  - `testAttachmentBookmarkAcceptsLegacyJsonWithItemTypeText` — `source_type` 缺失 + `item_type="text"` 仍被识别。
  - `testAttachmentBookmarkHasL2Children` — 附件 L1 锚点下 `1./2./3.` 子级能被抽出（验证区间切片把附件当锚点）。

## 关键决策

- **用 `TemplateKey` 而不是新模板**：附件不是模板，是锚点。新增 `("ATTACHMENT_MARKER", ARABIC)` 仅用于在 `cleanedIndices` 阶段被并入 L1 集合，`selectTemplateForLevel` 通过 `if (c.isAttachment) continue;` 直接跳过，不让它参与模板打分，避免污染正常模板选择。
- **「页首」判定路径分裂**：
  - IObject 路径用 `instanceof CustomSemanticParagraph || instanceof SemanticHeading`（即 `collectCandidates` 已认可的段类型）；
  - JSON 路径用 `isJsonTextLikeItem(item)`（`source_type ∈ {paragraph, heading}` 或 legacy `item_type == "text"`）——严格匹配 `collectJsonCandidates` 已接受的段类型，避免把图表 / 表格当首段。
- **`length() <= 20` 用 Java 语义**：和 `MAX_ENTRY_TEXT_LENGTH=200` 等其他阈值保持一致都是 `String#length()`，不引入 codepoint 数规则。
- **fallback 仅在 level 1**：极端 PDF（全是附件页 + 无常规模板）`selectedTemplate == null`，fallback 必须存在；level 2/3 没有这种 fallback 需求。
- **不加进 `PATTERNS`**：附件不是模板类别，加进 `PATTERNS` 会被 `selectTemplateForLevel` 强制用作真实模板，与设计意图冲突。

## 验证

### 单元测试
```
mvn test -Dtest='PageBookmarkProcessorTest,CatalogBookmarkProcessorTest,BookmarkQualitySelectorTest,AttachmentPdfVerificationTest' -DfailIfNoTests=false
```
结果：
```
[INFO] Tests run: 49, Failures: 0, Errors: 0, Skipped: 0 -- PageBookmarkProcessorTest
[INFO] Tests run: 3,  Failures: 0, Errors: 0, Skipped: 0 -- CatalogBookmarkProcessorTest
[INFO] Tests run: 17, Failures: 0, Errors: 0, Skipped: 0 -- BookmarkQualitySelectorTest
[INFO] Tests run: 1,  Failures: 0, Errors: 0, Skipped: 0 -- AttachmentPdfVerificationTest
[INFO] Tests run: 70, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

### 真实 PDF 端到端验证
新增 `AttachmentPdfVerificationTest.verifyAttachmentBookmarksForRealPdf()` 直接读 `tmp_output/202609081788820439424027803.json`（OCR 重建后的 JSON），调 `PageBookmarkProcessor.extractPageBookmarksFromJson(pages, -1, -1)`，断言结果含「附件一」与「附件二」两条 L1 bookmark。

日志与输出：
```
[PageBookmark] collected 2 JSON attachment candidates (catalog pages skipped: -1--1)
=== Real PDF verification: 202609081788820439424027803.pdf ===
Total top-level bookmarks: 8
  page=2 relatedId=6   asciiPrefix=一、…    textLen=11  isSingleLine=true
  page=3 relatedId=9   asciiPrefix=二、…    textLen=13  isSingleLine=true
  page=4 relatedId=2   asciiPrefix=三、…    textLen=8   isSingleLine=true
  page=4 relatedId=12  asciiPrefix=四、…    textLen=15  isSingleLine=true
  page=4 relatedId=14  asciiPrefix=五、…    textLen=6   isSingleLine=true
  page=5 relatedId=3   asciiPrefix=六、…    textLen=6   isSingleLine=true
  page=6 relatedId=1   asciiPrefix=附件一…  textLen=4   isSingleLine=true   ← 新增
  page=7 relatedId=1   asciiPrefix=附件二…  textLen=4   isSingleLine=true   ← 新增
```
- 章节 L1（一～六）位置 / relatedId 不变。
- 附件 L1 排在一级目录后、且**页码正确**（附件一在第 6 页、附件二在第 7 页）。
- `textLen=4` 印证「附件一：」正好 4 个 code unit，远低于 20 的阈值。

## 相关文件
- `java/opendataloader-pdf-core/src/main/java/org/opendataloader/pdf/processors/PageBookmarkProcessor.java` — 主要改动
- `java/opendataloader-pdf-core/src/test/java/org/opendataloader/pdf/processors/PageBookmarkProcessorTest.java` — 新增 5 个用例 + 1 个旧用例修正
- `java/opendataloader-pdf-core/src/test/java/org/opendataloader/pdf/processors/AttachmentPdfVerificationTest.java` — 新增真实 PDF 端到端验证
- `java/opendataloader-pdf-core/src/main/java/org/opendataloader/pdf/custom/entities/Bookmark.java` — 未改动（已有 text / pageNum / relatedId / fontSize / isSingleLine / children 字段）
- `java/opendataloader-pdf-core/src/main/java/org/opendataloader/pdf/json/JsonName.java` — 未改动（已含 `SOURCE_TYPE_PARAGRAPH / SOURCE_TYPE_HEADING / ITEM_TYPE / TEXT` 等常量）
- `tmp_output/202609081788820439424027803.json` — 真实 OCR JSON 输入，端到端验证的 fixture
- `docs/pdf/202609081788820439424027803.pdf` — 验证目标 PDF

## 构建命令
```bash
cd java/opendataloader-pdf-core
mvn test -Dtest='PageBookmarkProcessorTest,CatalogBookmarkProcessorTest,BookmarkQualitySelectorTest,AttachmentPdfVerificationTest' -DfailIfNoTests=false
```