# opendataloader-pdf 任务记忆 — 2026-08-11（page_bookmarks 一级目录误丢与正文越位修复）

## 目标（Goal）
- 修复 `PageBookmarkProcessor` 在 `docs/pdf/202306221687344038470064294.pdf` 上生成 `page_bookmarks` 时一级目录不是 `一 / 二 / 三 / 四 / 五` 的问题：
  - 现状 A（提交 f2e7e9c 后）：一级目录被错误地降级为 `（一）/（二）`，真实的一级 `一 / 二 / 三 / 四 / 五` 被丢弃埋到第二/三层。
  - 现状 B（仅撤销 isTocLikeGroup 后）：一级目录变成正文长段 `1、2、3、4`（"1、品牌营销服务网络拓展项目：受外部环境..." 等 200+ 字的长正文），真正的 `一 / 二 / 三 / 四 / 五` 仍埋在子层。
- 目标：让 `bookmarks` 的顶级条目回到 `一、 前次募集资金的募集情况`、`二、 前次募集资金的实际使用情况`、`三、 募集资金投资项目产生的经济效益情况`、`四、 前次募集资金结余及节余募集资金使用情况`、`五、 前次募集资金使用的其他情况`，并把 `（一）/（二）...`、`1、/2、...` 正确归到对应层级。

## 根因（Root Cause）
- **第一层（f2e7e9c 引入）：`isTocLikeGroup` 误用。**
  - `PageBookmarkProcessor.cleanCandidates()`（level 1 专用）在提交 f2e7e9c 中被改造成调用 `isTocLikeGroup(trimmedSection)`。
  - `isTocLikeGroup` 的小段阈值是 `size <= 5 && pairCount >= 1`：一段里只要有 1 对"同页相邻 relatedId"就被丢弃。
  - 本 PDF 第 1 页上 `一、`（id=4）和 `（一）`（id=5）共页 id 相邻，命中此规则 → 整个 `一 / 二 / 三 / 四 / 五` 段被丢掉 → level 1 空 → fallback 到 `（一）/（二）` 这类 paren 模板作为顶级（一级目录被整体下移一级）。
- **第二层（撤销 isTocLikeGroup 后暴露）：长正文 + indent 排序问题。**
  - 完全去掉 level 1 过滤后，长正文段（如 `1、品牌营销服务网络拓展项目：受外部环境变化因素的影响...`，fullText > 200 字符）也通过 `isValidGroup` 检查，被当成了"候选 level 1"。
  - `selectTemplateForLevel` 的排序 `font desc → indent asc → count desc → page asc → topY desc` 中，`indent asc` 排在 `count desc` 之前。`1、2、3、4` 正文段的平均 x0=89.784（正文边距），而 `一、二、三、四、五` 标题的平均 x0=110.93（稍缩进）。`89.784 < 110.93` → 正文段在 indent 这一步胜过真实一级标题 → 一级目录变成 4 条长正文。

## 修复策略
- **不再复用 `isTocLikeGroup` 全套规则到 level 1**：相邻对 / 跨页桥接 / 大小阈值对小段（如 `一/二/三/四/五`）都是误伤。
- **把 `isTocLikeGroup` 的 Rule 1（超长文本）单独抽出来作为 level 1 过滤**：仅当 section 中任意一条 fullText > `MAX_ENTRY_TEXT_LENGTH`（200 字符）时丢弃该 section。这条规则对长正文永远命中，对真正的短标题（如 `一、 前次募集资金的募集情况`，约 14 字符）永远不命中，正是区分"长正文 vs 短标题"的有效信号。
- **调整 `selectTemplateForLevel` 排序顺序**：把 `count desc` 提前到 `indent asc` 之前。幸存条目数更多者更可能是真正的章节目录骨架；缩进只在条目数相同时作为 tie-breaker。
- **删除 f2e7e9c 引入的回归测试 `testLevelOneDropsTocLikeSections`**：该测试断言"两个中文 section 段都被丢弃"是 f2e7e9c 的错误行为，已被本次修复回滚。
- **保留 `cleanCandidatesLocal()` 与 `isTocLikeGroup()` 本体不动**：level 2/3 行为零改动。

## 实现（Implementation）
- `java/opendataloader-pdf-core/src/main/java/org/opendataloader/pdf/processors/PageBookmarkProcessor.java`：
  - `cleanCandidates(List<Candidate>)`：
    - 删除 `if (isTocLikeGroup(trimmedSection)) { continue; }`；
    - 替换为 `if (hasOverlongEntry(trimmedSection)) { continue; }`；
    - 注释更新为"按超长文本规则过滤 + 取最大连续段"。
  - 新增 `hasOverlongEntry(List<Candidate> section)`（独立静态方法）：遍历 section，任一 `candidate.fullText != null && fullText.length() > MAX_ENTRY_TEXT_LENGTH` 即返回 true。语义即 `isTocLikeGroup` 的 Rule 1，但不被相邻对/跨页桥接规则牵连。
  - `selectTemplateForLevel` 的排序：
    - 由 `font desc → indent asc → count desc → page asc → topY desc`
    - 改为 `font desc → count desc → indent asc → page asc → topY desc`。
- `java/opendataloader-pdf-core/src/test/java/org/opendataloader/pdf/processors/PageBookmarkProcessorTest.java`：
  - 删除 `testLevelOneDropsTocLikeSections`（f2e7e9c 引入的回归测试，其断言的"一级丢弃"是错误行为）。其余 `testTocFilter_*` 系列（针对 `cleanCandidatesLocal`）保持原样，level 2/3 行为零改动。
  - 删除后 `PageBookmarkProcessorTest` 31 用例全通过。

## 关键决策（Key Decisions）
- **不复用 `isTocLikeGroup`**：用户最初口径是"跟 level 2/3 保持一致"，但实际验证发现相邻对规则对 `一 / 二 / 三 / 四 / 五` 这种"5 条、共页相邻 1 对"的合法一级目录误伤率 100%，且没有简单阈值能修复（`一` 和 `（一）` 在第 1 页就是 relatedId 相邻，无法靠模板区分）。改为复用 Rule 1（超长文本）独立抽出的最小子集。
- **排序顺序的取舍**：把 `count desc` 提前会改变其他模板下"少条目模板更上层"的可能场景，但本项目其它 PDF 的 hierarchy 多依赖 `chapter / section / article` 三层模板（`第#章 / 第#节 / 第#条`），这三类在 `isValidGroup` 阶段就会按 `template` 分流，模板内条目数天然接近，`count desc` vs `indent asc` 顺序对其影响极小。`indent asc` 降到 tie-breaker 后，对原本"正文段少条目但 indent 小而胜出"的伪正情况也能稳定压制。
- **不动 `BookmarkQualitySelector` / `JsonWriter` / `splitByValueOne` / `trimContiguousSection` / `cleanCandidatesLocal`**：仅在 level 1 决策面做最小改动。
- **不动 `cleanCandidatesLocal` 的相邻对过滤**：level 2/3 在父级（一级目录）范围内运行，相邻对规则正是用来抓"父级范围内混入目录残渣"，仍然有效。

## 验证结果
- `mvn -Dtest=PageBookmarkProcessorTest test`：**31/31 全通过**。
- `DebugSample.main()` 跑 `docs/pdf/202306221687344038470064294.pdf`：
  - `[PageBookmark] collected 33 JSON candidates (catalog pages skipped: -1--1)`
  - 关键日志：`[BookmarkQualitySelector] page: total=12, effective=12, score=2.565`，`selected page_bookmarks`（catalog/self 为空，唯一存活来源）。
  - `tmp_output/202306221687344038470064294.json` 的 `bookmarks` 字段：
    ```
    一、 前次募集资金的募集情况         (page 1)
      ├── （一）实际募集资金金额及资金到账情况 (page 1)
      └── （二）2023 年度 1-4 月募集资金使用及结余情况 (page 1)
    二、 前次募集资金的实际使用情况     (page 1)
      ├── 1、闲置募集资金临时补充流动资金情况 (page 3)
      │   └── （五）前次募集资金使用情况与公司定期报告的对照 (page 4)
      └── 2、闲置募集资金进行现金管理情况   (page 3)
    三、 募集资金投资项目产生的经济效益情况 (page 5)
    四、 前次募集资金结余及节余募集资金使用情况 (page 8)
      ├── （一）截至 2023 年 4 月 30 日止...   (page 8)
      └── （二）公司尚未使用的募集资金使用计划 (page 9)
    五、 前次募集资金使用的其他情况     (page 10)
    ```
  - 一级目录以 `一 / 二 / 三 / 四 / 五` 开头，符合用户期望。
- 其他集成测试（`AutoTaggerTest`、`EmbedImagesIntegrationTest`、`ImageDirIntegrationTest`、`IncludeHeaderFooterJsonIntegrationTest`、`Issue336IntegrationTest`、`PageSeparatorIntegrationTest` 等）在 main 分支上同样失败（38 failures + 18 errors），与本次改动无关；本任务只核对 `PageBookmarkProcessorTest` 全绿。

## 构建 / 运行
- 编译：
  ```powershell
  mvn -q -f java\opendataloader-pdf-core\pom.xml compile
  ```
- 跑 PageBookmarkProcessorTest：
  ```powershell
  mvn -q -f java\opendataloader-pdf-core\pom.xml test -Dtest=PageBookmarkProcessorTest
  ```
- 跑 DebugSample 复现修复效果（需先把 `DebugSample.java` 第 35 行的 PDF 路径切到目标 PDF，再恢复；本会话已经历过"目标 PDF 与 OBS 上传配置"反复切换）：
  ```powershell
  cd D:\Code\JavaCode\opendataloader-pdf\java\opendataloader-pdf-core
  java -cp "target\classes;<cp.txt 中全部 jar>" org.opendataloader.pdf.DebugSample
  ```
- 注意点：`DebugSample` 默认带 OBS 上传（`ossTempBucketName=stock-temp-bucket` 等），开启后会把 JSON 上传到 `https://stock-temp-bucket.obs.cn-north-1.myhuaweicloud.com/...` 并尝试删除本地 PDF。复现 bookmark 验证时建议先把这几行注释掉，跑完再恢复。

## 相关文件（Relevant Files）
- `java/opendataloader-pdf-core/src/main/java/org/opendataloader/pdf/processors/PageBookmarkProcessor.java`：
  - `cleanCandidates(List<Candidate>)` 改用 `hasOverlongEntry` 替代 `isTocLikeGroup`；
  - 新增 `hasOverlongEntry(List<Candidate>)` 静态方法（Rule 1 独立抽出）；
  - `selectTemplateForLevel` 排序顺序：`count desc` 提前到 `indent asc` 之前；
  - `cleanCandidatesLocal` / `isTocLikeGroup` / `isSamePageAdjacent` / `isCrossPageAdjacent` / `maxConsecutiveRelatedIdRun` / `splitByValueOne` / `trimContiguousSection` / `isValidGroup` 未改动。
- `java/opendataloader-pdf-core/src/test/java/org/opendataloader/pdf/processors/PageBookmarkProcessorTest.java`：删除 `testLevelOneDropsTocLikeSections`（f2e7e9c 引入的回归测试）。其余 31 用例保持不变。
- `java/opendataloader-pdf-core/src/main/java/org/opendataloader/pdf/custom/utils/BookmarkQualitySelector.java`：未改动。
- `java/opendataloader-pdf-core/src/main/java/org/opendataloader/pdf/json/JsonWriter.java`：未改动。
- `docs/pdf/202306221687344038470064294.pdf`：本次修复的目标样例文件。
- `tmp_output/202306221687344038470064294.json`：修复后重新跑 DebugSample 生成的输出，`bookmarks` 字段顶级为 `一 / 二 / 三 / 四 / 五`。
- `tmp_output/202306221687344038470064294_page_bookmarks_collected.md`：原始候选收集文件（只反映 collect 阶段，与清洗逻辑无关，未改动）。
- `.agents/plans/1-d-code-javacode-opendataloader-pdf-par-glowing-blum.md`：本任务 plan 存档。