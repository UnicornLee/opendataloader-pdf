# opendataloader-pdf 任务记忆 — 2026-08-09

## 目标（Goal）
- 增强 `CatalogBookmarkProcessor` 对 `catalog_bookmarks` 的层级识别能力。
- 一级标题以最小 `leftX`/`x0` 为准（容差 2）；二级/三级标题通过明显缩进或 `BookmarkConstant` 前缀类型连续性分组来判定。
- 以 `docs\pdf\202303181679059838994480.pdf` 为主要验证样例，同时保持泛化能力。
- 用户已确认：
  1. 采用聚类算法自动找缩进断点。
  2. 前缀类型的"连续性分组"指阅读顺序上的连续。
  3. "二级/三级目录同一种开头类型"指**同一个父节点下**二级子节点同前缀。
  4. 开头/结尾的无前缀条目一定属于 L1。
  5. 当前缀类型与 `leftX` 冲突时，按前缀修正为 L2/L3。
  6. 无预设期望层级，先按约定逻辑实现，用户会检查结果并指出问题。
  7. 需要新增 `CatalogBookmarkProcessorTest` 单元测试。

## 约束与偏好（Constraints & Preferences）
- 用户原话要求：
  1. `catalog_bookmarks` 识别要做层级处理。
  2. 一级标题 `leftX`/`x0` 最小，容差 2。
  3. 明显缩进（`leftX` 偏大）判为二级或三级。
  4. 缩进不明显时，依据 `BookmarkConstant` 常量对相同类型开头做连续性分组；二级/三级目录应为同一种开头类型；一级标题中间可有连续同类型前缀，开头/结尾可无标志性前缀。
  5. 以指定 PDF 验证，尽量泛化。
  6. 不清楚处须澄清。
  7. 改代码前须提供思路，用户同意后改码。
  8. 可用 `org.opendataloader.pdf.DebugSample.java` 跑出解析结果。

## 实现（进行中）
### 代码改动
- 新建 `org.opendataloader.pdf.custom.utils.BookmarkPrefixClassifier`：
  - 抽取并复用 `BookmarkConstant` 前缀常量，提供 `classify(String)` 与 `isBookmarkCandidate(String)`。
  - 返回 `PrefixType`（template + numberSystem），供层级分组使用。
- 修改 `org.opendataloader.pdf.processors.CatalogBookmarkProcessor`：
  - `Candidate` 增加 `prefixType` 字段（已调整可见性以便测试）。
  - 重写 `assignLevels(List<Candidate>)`：
    1. 为每个候选标题计算 `prefixType`。
    2. 对 `leftX` 做 1D 聚类：排序唯一 `leftX`，计算 gap，用 `max(3.0, avgFontSize * 0.45)` 作为显著断点阈值，最多 3 簇。
    3. 若聚类清晰（2/3 簇），初始层级 = 簇索引 + 1；若只有 1 簇，按前缀类型深度先验初始化层级。
    4. 前缀修正：
       - 开头/结尾的无前缀条目强制为 L1。
       - 阅读顺序中连续同前缀的 run 统一为最众层级。
       - 单个候选偏离其前缀类型的典型层级时，向典型层级移动一级。
    5. 层级钳制在 1..3。
  - 保留 `buildHierarchy`，按修正后的 `level` 建树。
- 新增 `org.opendataloader.pdf.processors.CatalogBookmarkProcessorTest`：
  - 明显缩进 3 级。
  - 缩进不明显时按前缀连续性分组。
  - L1 开头/结尾无前缀。
  - 前缀类型与 `leftX` 冲突时前缀优先。
  - 样例 PDF 关键结构的简化版本。

### 当前进度
- `BookmarkPrefixClassifier.java` 已创建。
- `CatalogBookmarkProcessor.java` 已增加 import、`Candidate.prefixType`、新的 `assignLevels` 及聚类/修正辅助方法；编译通过。
- 下一步：调整方法可见性、完成单元测试、跑回归测试与 DebugSample 验证。

## 关键决策（Key Decisions）
- 缩进聚类优先：用数据驱动 gap 阈值自动找断点；`max(3.0, avgFontSize * 0.45)` 作为"明显缩进"阈值，可兼顾不同字号文档。
- 前缀连续性兜底：当 `leftX` 聚类无法区分时，按 `BookmarkConstant` 前缀类型的阅读顺序连续 run 修正层级。
- 冲突解决：前缀类型优先于 `leftX`（满足用户"按前缀修正为 L2"的要求），但移动幅度限制为 1 级以避免震荡。
- 新建独立 `BookmarkPrefixClassifier`，不直接改动 `PageBookmarkProcessor`，降低回归风险。
- `CatalogBookmarkProcessor.Candidate` 与 `assignLevels` 可见性调整为包级，方便单元测试直接构造候选并调用层级分配逻辑。

## 下一步（Next Steps）
1. 调整 `assignLevels` 及辅助方法可见性（包级），便于测试。
2. 编写并运行 `CatalogBookmarkProcessorTest`。
3. 运行 `PageBookmarkProcessorTest` 等现有测试做回归验证。
4. 运行 `DebugSample.java`（`202303181679059838994480.pdf`），对比修改前后 `catalog_bookmarks` 输出。
5. 根据用户反馈迭代修正层级逻辑。
6. 更新本记忆文件，补充验证结果与最终代码改动。

## 关键上下文（Critical Context）
- 环境：Maven 3.9.14、Java 11.0.0.2（`D:\Applications\Java\jdk-11.0.0.2`）。
- 工作目录：`D:\Code\JavaCode\opendataloader-pdf-parse\opendataloader-pdf`。
- 关键文件：
  - `java\opendataloader-pdf-core\src\main\java\org\opendataloader\pdf\processors\CatalogBookmarkProcessor.java`
  - `java\opendataloader-pdf-core\src\main\java\org\opendataloader\pdf\custom\utils\BookmarkPrefixClassifier.java`
  - `java\opendataloader-pdf-core\src\test\java\org\opendataloader\pdf\processors\CatalogBookmarkProcessorTest.java`（待创建）
- 验证入口：`org.opendataloader.pdf.DebugSample.java`，目标 PDF：`docs\pdf\202303181679059838994480.pdf`。

## 相关文件（Relevant Files）
- `java\opendataloader-pdf-core\src\main\java\org\opendataloader\pdf\custom\utils\BookmarkPrefixClassifier.java`：前缀分类工具类。
- `java\opendataloader-pdf-core\src\main\java\org\opendataloader\pdf\processors\CatalogBookmarkProcessor.java`：目录层级识别核心逻辑。
- `java\opendataloader-pdf-core\src\main\java\org\opendataloader\pdf\processors\PageBookmarkProcessor.java`：参考其前缀匹配/模板逻辑，本次未直接改动。
- `java\opendataloader-pdf-core\src\main\java\org\opendataloader\pdf\custom\constants\BookmarkConstant.java`：前缀常量源。
- `java\opendataloader-pdf-core\src\main\java\org\opendataloader\pdf\DebugSample.java`：验证入口。
- `docs\memory\`：本记忆目录。
