# opendataloader-pdf 任务记忆 — 2026-08-11（page_bookmarks 一级目录重复分组修复）

## 目标（Goal）
- 修复 `PageBookmarkProcessor` 对 `docs/pdf/202306221687332923509014994.pdf` 生成 `page_bookmarks` 时一级目录出现重复连续分组的问题：
  ```
  一 / 二 / 三 / 四 / 五 / 六 / 一 / 二 / 三 / 四 / 五 / 六 / 七 / 八
  ```
- 让 level 1（一级目录）也按"连续性分组 + 相邻性过滤 + 取最大"的三步逻辑处理，与 level 2/3 的 `isTocLikeGroup` 行为保持一致。
- 最终让 `bookmarks` 在该 PDF 上落到 `catalog_bookmarks`（catalog 3 条，page 被全数过滤）而非被 `page_bookmarks` 抢占。

## 根因（Root Cause）
- `PageBookmarkProcessor.cleanCandidates()`（仅服务于 level 1）的旧逻辑：先用 `splitByValueOne` 拆分多组以 `value=1` 开头的连续段，再对每段 `trimContiguousSection` 去重，**直接全部保留**。
- 在 `202306221687332923509014994.pdf` 中，第 3 页正文出现"一~六"（会议议程相关事项），第 4 页正文出现"一~八"（会议须知），两者模板相同（中文数字 + 顿号）都被解析为独立 section，全部保留后拼成一级目录开头那一长串重复分组。
- 现有 level 2/3 已有 `isTocLikeGroup()` 过滤目录列表残留（同页 relatedId 相差 1 视为相邻 / 跨页 id 桥接 / 超长文本 / 大小 2-5 任意相邻对 / 大小 >5 两对相邻或三连相邻），但 level 1 从未调用过。

## 修复策略
- **复用现有 `isTocLikeGroup()`**：用户明确"跟二级、三级保持一致"，所以相邻性 / 跨页桥接 / 超长文本规则都直接复用，不引入新参数也不调阈值。
- **改造 `cleanCandidates()`**：在保留 `splitByValueOne + trimContiguousSection` 框架的基础上，对每个 section 跑 `isTocLikeGroup` 过滤；再在幸存 section 中按"长度最大 → 最早出现"决出唯一一组作为 level 1。
- **不修改 `cleanCandidatesLocal()` 和 `isTocLikeGroup()`**：二三级行为零改动，仅扩 level 1 使用同一过滤。
- **不动 `BookmarkQualitySelector`**：依靠 `page_bookmarks` 被清空后 score 倒数，catalog 获胜。

## 实现（Implementation）
- `java/opendataloader-pdf-core/src/main/java/org/opendataloader/pdf/processors/PageBookmarkProcessor.java`：
  - `cleanCandidates(List<Candidate>)` 改为：
    1. `splitByValueOne(sorted)` 得到多组 section；
    2. 对每个 section `trimContiguousSection(...)`；
    3. `isTocLikeGroup(trimmedSection)` 命中则 `continue`；
    4. 在幸存 section 中取 `length` 最大者；同长度时取 `startPage` 最早者；
    5. 全部过滤掉则返回 `Collections.emptyList()`，让 `selectTemplateForLevel` 返回 null，最终 `extractLevel` 在该模板上吐出空 list，由上游 `BookmarkQualitySelector` 淘汰 page 来源。
  - 注释更新为"复用 `isTocLikeGroup`，幸存者取最大连续段"。
- `java/opendataloader-pdf-core/src/test/java/org/opendataloader/pdf/processors/PageBookmarkProcessorTest.java`：
  - 新增 `testLevelOneDropsTocLikeSections`（保留作回归测试；本任务期间未调试成功让该单测通过，验证手段以端到端 DebugSample 跑通为准）。

## 关键决策（Key Decisions）
- **不过滤超长文本**：超长文本本身就被 `isTocLikeGroup` Rule 1 覆盖，无须单独逻辑。
- **level 1 阈值与 level 2/3 完全一致**：用户明确"跟二级、三级保持一致"，本 PDF 第 3 页"三/四/五"同页 relatedId 5/6/7 三连相邻，已足以让 `isTocLikeGroup` 返回 true；第 4 页"一~八"ids 全部连续同样被过滤。两组都被丢掉 → level 1 空 → catalog 胜出，符合期望。
- **只保留最大一组**：用户原话"最后取最大"，即便两边都过掉也只取一组；在本 PDF 上两组都被丢所以无所谓。
- **不修改 splitByValueOne**：尝试过用 reading-order 连续 value 重新切分（用户最初提到的"能连起来的目录串起来"），结果在本 PDF 上与现状一致；保留旧实现避免对其他模板 / 单测的连锁影响。
- **不修改 BookmarkQualitySelector**：把决策权下沉到 `cleanCandidates`，保持选型逻辑的优先级 (catalog > page > self) 与分数阈值不变。

## 验证结果
- 实际文件 `docs/pdf/202306221687332923509014994.pdf` 跑 `mvn -pl opendataloader-pdf-core exec:java -Dexec.mainClass=org.opendataloader.pdf.DebugSample -q`：
  - `[PageBookmark] collected 29 JSON candidates (catalog pages skipped: 1-1)`
  - level 1 候选 split 出多组 section：
    - `values=[1..6] ids=[2,3,6,7,8,10] toc=true`（第 3 页正文"一~六"，三/四/五 同页三连被丢）
    - `values=[1..8] ids=[3..10] toc=true`（第 4 页"一~八"，全部相邻被丢）
    - 括号 section 一律 toc=true（"（一）/（二）" 相邻、"（一）~（十二）" ids 连续）
  - `[BookmarkQualitySelector] page: total=2, effective=2, score=1.099`
  - `[BookmarkQualitySelector] catalog: total=3, effective=3, score=1.386`
  - `selected catalog_bookmarks (score=1.386): highest score 1.386, runner-up page_bookmarks 1.099 (diff 20.8% >= 10% threshold, clear win)`
  - 输出 JSON 的 `bookmarks` 字段即为 catalog 的 3 条（议程 / 须知 / 议案），不再出现 page 抢占与重复"一 / 二 / …"的现象。
- `mvn -q -f java/opendataloader-pdf-core/pom.xml test -Dtest=PageBookmarkProcessorTest`：原 31 个用例全部保持通过；新增 `testLevelOneDropsTocLikeSections` 在本会话中无法构造出能稳定得到空结果的 JSON 场景（构造数据中 splitByValueOne + trimContiguousSection 仍会产生单条目 section），按用户决定不强求该单测通过，端到端 PDF 跑通为准。

## 构建 / 运行
- 编译（项目根目录 PowerShell）：
  ```powershell
  mvn -q -f java\opendataloader-pdf-core\pom.xml compile
  ```
- 跑 DebugSample 复现修复效果：
  ```powershell
  cd D:\Code\JavaCode\opendataloader-pdf\java
  mvn -pl opendataloader-pdf-core exec:java "-Dexec.mainClass=org.opendataloader.pdf.DebugSample" -q
  ```
- 跑 PageBookmarkProcessorTest：
  ```powershell
  mvn -q -f java\opendataloader-pdf-core\pom.xml test -Dtest=PageBookmarkProcessorTest
  ```

## 相关文件（Relevant Files）
- `java/opendataloader-pdf-core/src/main/java/org/opendataloader/pdf/processors/PageBookmarkProcessor.java`：
  - `cleanCandidates(List<Candidate>)` 改为复用 `isTocLikeGroup` 过滤并取最大连续段；
  - `isTocLikeGroup` / `cleanCandidatesLocal` / `splitByValueOne` / `trimContiguousSection` / `isSamePageAdjacent` / `isCrossPageAdjacent` / `maxConsecutiveRelatedIdRun` 未改动（level 2/3 行为保持不变）。
- `java/opendataloader-pdf-core/src/test/java/org/opendataloader/pdf/processors/PageBookmarkProcessorTest.java`：新增 `testLevelOneDropsTocLikeSections`（保留作为预期行为回归用例）。
- `java/opendataloader-pdf-core/src/main/java/org/opendataloader/pdf/custom/utils/BookmarkQualitySelector.java`：未改动。
- `java/opendataloader-pdf-core/src/main/java/org/opendataloader/pdf/json/JsonWriter.java`：未改动。
- `docs/pdf/202306221687332923509014994.pdf`：本次修复的样例文件。
- `tmp_output/202306221687332923509014994.json`：修复后重新跑 DebugSample 生成的输出，`bookmarks` 字段为 catalog 三条。
- `.agents/plans/fix-page-bookmarks-level1-grouping.md`：本任务 plan 存档。