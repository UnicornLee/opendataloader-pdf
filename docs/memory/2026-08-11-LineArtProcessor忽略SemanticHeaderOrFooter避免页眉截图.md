# opendataloader-pdf 任务记忆 — 2026-08-11（LineArtProcessor 忽略 SemanticHeaderOrFooter 避免页眉截图）

- 时间：2026-08-11
- 任务：修复单页 PDF `202306221687332923509014994-3.pdf` 解析后无截图，而多页 PDF 第 3 页有截图的不一致问题。
- 状态：已按方案 B 实现 —— `LineArtProcessor` 合并线条时跳过 `SemanticHeaderOrFooter`，使单页/多页页眉区域统一不生成截图。

## 目标（Goal）
- 解析 `docs/pdf/202306221687332923509014994-3.pdf`（仅第 3 页）与完整 PDF `docs/pdf/202306221687332923509014994.pdf` 第 3 页时，输出行为一致。
- 页眉处的装饰水平线不再被渲染成截图，页眉文字保留为可检索文本。

## 根因（Root Cause）
1. 多页 PDF 中，`HeaderFooterProcessor` 通过跨页比对识别重复页眉，把页眉文字包成 `SemanticHeaderOrFooter` 容器。
2. `LineArtProcessor` 在后续处理中，会把顶部细线与该页眉容器合并，渲染成 `ImageChunk`（即完整 PDF 第 3 页的 `imageFile2.png`）。
3. 单页 PDF 只有 1 页，`HeaderFooterProcessor` 无法做跨页比对，不会生成 `SemanticHeaderOrFooter`；细线没有可合并对象，因此不产生截图。
4. 根源是同一页内容因“是否处于多页文档”而得到不同输出。

## 修复策略
- 方案 A（单页兜底）：在 `LineArtProcessor` 中对单页文档增加小间距合并兜底，可让单页也产生截图，但只能截到装饰线本身，与多页截图（线 + 公司名）仍不一致。
- 方案 B（忽略页眉容器）：`LineArtProcessor` 合并邻居时跳过 `SemanticHeaderOrFooter`，多页 PDF 页眉不再被截图，单页/多页行为统一为“无页眉截图”。
- 用户选择方案 B。

## 实现（Implementation）
`java/opendataloader-pdf-core/src/main/java/org/opendataloader/pdf/processors/LineArtProcessor.java`：
- 在前后两个邻居扫描循环中，把 `SemanticHeaderOrFooter` 与 `ShapeChunk` 一并跳过：
  ```java
  if (candidate instanceof ShapeChunk || HeaderFooterProcessor.isHeaderOrFooter(candidate)) {
      continue;
  }
  ```
- 删除了此前为方案 A 临时加入的 `StaticContainers` 单页判断、`MAX_SINGLE_PAGE_VERTICAL_GAP`、`shouldMerge`、`hasHorizontalOverlap`。

`java/opendataloader-pdf-core/src/main/java/org/opendataloader/pdf/DebugSample.java`：
- 保持用户原有设置（OSS 配置注释、指向完整 PDF），未纳入本次改动。

## 关键决策（Key Decisions）
- **采用方案 B**：以“不截图”达成单页/多页一致性，同时保留页眉文字可检索性。
- **直接复用 `HeaderFooterProcessor.isHeaderOrFooter(...)`**：同包 public static 方法，无需新增 import 或重复判断逻辑。
- **不改动 `HeaderFooterProcessor`**：只在 `LineArtProcessor` 中屏蔽合并，页眉检测逻辑本身不变。
- **清理临时文件**：删除调查过程中产生的 `ImageProbe.java`、`cp2.txt`、`tmp_review/` 等无关文件。

## 验证结果
- `mvn -q compile`：✅ 编译通过。
- `DebugSample` 跑 `202306221687332923509014994.pdf`：
  - JSON 中页眉相关 `item_type: image` 已消失。
  - 仅保留第 1 页封面图 `imageFile1.png` 的引用。
- `DebugSample` 跑 `202306221687332923509014994-3.pdf`：
  - JSON 中无 `item_type: image`。
  - 页眉文字全部以 `text` 形式输出。
- 旧的 `imageFile2.png` / `imageFile3.png` 等文件仍留在 `tmp_output/..._images/` 目录中（历史残留），新 JSON 已不再引用。

## 构建 / 运行
- 编译：
  ```bash
  cd D:\Code\JavaCode\opendataloader-pdf-parse\opendataloader-pdf\java\opendataloader-pdf-core
  mvn compile -q
  ```
- 运行示例（完整 PDF）：
  ```bash
  cd D:\Code\JavaCode\opendataloader-pdf-parse\opendataloader-pdf\java\opendataloader-pdf-core
  CP=$(cat cp.txt)
  java -cp "target/classes;$CP" org.opendataloader.pdf.DebugSample
  ```
- 输出目录：`D:\Code\JavaCode\opendataloader-pdf-parse\opendataloader-pdf\tmp_output`

## 相关文件（Relevant Files）
- `java/opendataloader-pdf-core/src/main/java/org/opendataloader/pdf/processors/LineArtProcessor.java`：在前后两个循环中跳过 `SemanticHeaderOrFooter`。
- `java/opendataloader-pdf-core/src/main/java/org/opendataloader/pdf/processors/HeaderFooterProcessor.java`：提供 `isHeaderOrFooter(IObject)` 判断。
- `java/opendataloader-pdf-core/src/main/java/org/opendataloader/pdf/processors/DocumentProcessor.java`：定义 `HeaderFooterProcessor` → `LineArtProcessor` 的处理顺序。
- `docs/pdf/202306221687332923509014994.pdf`：多页测试样例。
- `docs/pdf/202306221687332923509014994-3.pdf`：单页测试样例（第 3 页提取）。

## 备忘
- 该改动仅影响 `LineArtProcessor` 与 `SemanticHeaderOrFooter` 的合并行为；其他基于重叠的 LineArt 合并（如公式下划线、图形边框）保持不变。
- 若后续希望“保留页眉截图但让单页也截图”，需回退本改动并改用在 `HeaderFooterProcessor` 中增加单页页眉启发式检测，或在 `LineArtProcessor` 中按页眉位置加大合并间距。
