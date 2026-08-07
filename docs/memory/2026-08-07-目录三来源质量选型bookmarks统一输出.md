# 目录三来源质量选型（bookmarks 统一输出）

- 时间：2026-08-07
- 任务：从 `catalog_bookmarks` / `page_bookmarks` / `self_bookmarks` 三个目录来源中按质量选出最终目录，以 `bookmarks` 键写入 json 并移除三个原始键，`.js` 的 `var bookmarks` 同步切换
- 附带修复：HEAD 提交 c52e9d0 引用但未提交的 `org.opendataloader.pdfbox.GetDrawings` 导致全模块编译失败，按使用点契约补了实现

## 需求要点（用户拍板）
- 质量指标：目录总数（含 children 全层级）、related_id==0 占比、奇怪字符占比
- 补充指标（全部采纳）：重复文本率、去重后有效条目数（effectiveCount）、页码单调性、related_id 无效链接率
- 奇怪字符 = Unicode 异常（U+FFFD/控制符/PUA/未分配/孤立代理对）+ 乱码模式（连续 ≥2 个 U+0080–U+024F Latin 区字符，典型 GBK 误判码如 "Ŀ¼"）
- 优先级（仅质量相当时）：catalog > page > self
- 质量相当判定：综合评分差 < 10%
- 全部都很差：`bookmarks` 写空数组 `[]`；空来源永远最差
- `.js` 的 `var bookmarks` 同步用选中结果

## 评分公式与规则（BookmarkQualitySelector）
```
penalty = min(1.0, dupRatio + unlinkedRatio + strangeRatio + nonMonoRatio + invalidLinkRatio)
score   = ln(1 + effectiveCount) × (1 − penalty)
```
- total==0 → score=-∞ 最差；penalty ≥ 0.5 或 effectiveCount==0 → 淘汰
- 幸存者取最高分；前两名相对差 < 10% → 按优先级 catalog>page>self 决胜
- 全部淘汰 → Selection(source=null, bookmarks=[])

## 改动点
- 新增 `custom/utils/BookmarkQualitySelector.java`：`evaluate`（递归 walk 计算 6 指标）、`select`（淘汰+评分+优先级决胜）、`buildPageItemIds`（页码→item id 集合）、`containsStrangeChars`
- `json/JsonWriter.java:666-708`：
  - 行 689 后构建 pageItemIds、`mapper.convertValue` 转 self 列表、调用 `select` → `map.put("bookmarks", 选中)`
  - `map.remove("self_bookmarks"/"catalog_bookmarks"/"page_bookmarks")`
  - 重序列化从 `if (data != null)` 内移到 `if (config != null)` 内始终执行（data==null 时 bookmarks=[]）
  - `.js`：`var bookmarks = map.getOrDefault 语义（null→[]）`
- 新增 `pdfbox/GetDrawings.java`：PDFGraphicsStreamEngine 路径收集（type/closePath/rect），契约由 `DocumentProcessor.java:798-808` 反推
- 新增单测 `custom/utils/BookmarkQualitySelectorTest.java`（10 例全过）

## 决策日志（用户追加需求）
- `eliminated X: <empty|no distinct titles|penalty N >= 0.5 (各项比率明细)>`
- 明确获胜：`selected X: highest score A, runner-up Y B (diff N% >= 10% threshold, clear win)`
- 平局优先级：`selected X: top scores comparable (best=A[来源], runner-up=B, diff N% < 10% threshold), priority catalog>page>self applied -> X`
- 唯一幸存 / 全淘汰（warning）亦有对应格式

## 验证（DebugSample，两个样例）
- `202303181679059838994480.pdf`：catalog 832 条但 dup=0.621+nonMono=0.537 → penalty=1.0 淘汰；page(5.551) vs self(5.639) 差 1.6% <10% → 优先级 → **选中 page_bookmarks**（L1=17）。json 键 = url, data, catalog_page_range_*, bookmarks
- `202302281677505819604328.pdf`：self 空淘汰；catalog(5.805) vs page(5.781) → **选中 catalog_bookmarks**
- 中途发现并修复平局决胜 bug：原循环按分数序遍历、首个即最高分者导致永不换胜者；改为阈值内取 priority 最小者（有单测 `comparableQualityFallsBackToPageOverSelf` 覆盖）

## 构建/验证命令
- 编译：`mvn -q compile`（在 `java/opendataloader-pdf-core` 下）
- 单测：`mvn test -Dtest=BookmarkQualitySelectorTest`
- 跑样例：`$cp=(Get-Content cp.txt -Raw).Trim()+";target\classes"; java "-Dfile.encoding=UTF-8" -cp $cp org.opendataloader.pdf.DebugSample`
- 注意：PowerShell 中 JVM 参数 `-Dfile.encoding=UTF-8` 必须加引号，否则被当类名解析

## 关键文件
- `java/opendataloader-pdf-core/src/main/java/org/opendataloader/pdf/custom/utils/BookmarkQualitySelector.java`（新）
- `java/opendataloader-pdf-core/src/main/java/org/opendataloader/pdf/json/JsonWriter.java`（改）
- `java/opendataloader-pdf-core/src/main/java/org/opendataloader/pdfbox/GetDrawings.java`（新，修 HEAD 编译断裂）
- `java/opendataloader-pdf-core/src/test/java/org/opendataloader/pdf/custom/utils/BookmarkQualitySelectorTest.java`（新）
- 样例：`docs/pdf/202302281677505819604328.pdf`、`docs/pdf/202303181679059838994480.pdf`

## 备忘
- 阈值 BAD_PENALTY=0.5、COMPARABLE_THRESHOLD=0.10、MOJIBAKE_RUN_LENGTH=2 均为常量可调
- catalog 的 62% 重复源自 `fillCatalogChildrenFromPageData` 把同一候选段填入多个父节点（同一 L3 标题出现 8 次）；若后续文档证明是"多被合并方共用小节标题"的合理重复，需调 dup 权重
- json 输出契约变更：`bookmarks` 替代三原始键；`catalog_page_range_start/end` 保留；`samples/json/lorem.json`、`lorem.js` 仍是旧结构，需要时单独更新
