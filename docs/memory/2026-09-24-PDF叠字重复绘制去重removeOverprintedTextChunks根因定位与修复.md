# opendataloader-pdf 任务记忆 — 2026-09-24（PDF 叠字去重：同一文本被微位移重复绘制，`TextProcessor.removeOverprintedTextChunks` 根因定位与修复）

> 样本：
> - `docs/pdf/200812311782183951489043113-1.pdf`（目标文件，前 3 行"叠字"）
> 涉及主改动文件：
> - `java/opendataloader-pdf-core/src/main/java/org/opendataloader/pdf/processors/TextProcessor.java`（新增 `removeOverprintedTextChunks` 及一整套常量/内部类/门控谓词）
> - `java/opendataloader-pdf-core/src/main/java/org/opendataloader/pdf/processors/ContentFilterProcessor.java`（`getFilteredContents` 接入去重）
> 新增单测：`java/opendataloader-pdf-core/src/test/java/org/opendataloader/pdf/processors/TextProcessorTest.java`（6 个新用例）
> 探针/扫描工具（根目录，**未入库**）：`TmpOverlapProbe.java`、`TmpOverprintDebug.java`、`TmpScanRunner.java`；Python：`sim_dedup.py`/`scan_dedup.py`/`sim2.py`/`diff_scan.py`/`dbg_row*.py`/`show_json.py`/`render.py`；扫描产物 `tmp_output/scan_before`（stash 基线）、`tmp_output/scan_after`、`tmp_output/probe/scan_files_{1,2,3}.txt`（85 份切 3 批）
> 状态：**产品代码修复完成，`TextProcessorTest` 全部通过；目标文件 3 行已正确；Python sim2 复核目标文件及 4 丢字用例均恢复。但 `tmp_output/scan_before` 为有效基线、`scan_after` 仍是旧宽泛版结果（含丢字），`TmpScanRunner.java` 加 BOM 处理后的重跑尚未完成。用户尚未要求 git 提交。**
> 本轮 = **现象 → 多工具定性（pdfplumber/PyMuPDF/Java 探针）→ 定位根因（单一内容流 + 伪粗体 ±0.24pt 微位移重复绘制）→ 确认只能按坐标+内容去重 → 现有 `removeSameTextChunks` 失效原因 → 方案 A + 阈值 + 宁漏不误门控 → 3 轮迭代修复（整副本优先 / 逐字绘制位置 / 幽灵字误删合法窄字收紧）→ 全量 85 份扫描暴露丢字回归 → 逆向验证闭环**

---

## 1. 现象

### 问题：`docs/pdf/200812311782183951489043113-1.pdf` 前 3 行"叠字"

该 PDF 首页前 3 行文字在解析输出里被重复出现多次（同一行文本出现 2~4 份），即"叠字"。期望清理后只保留 1 份，输出应当为：

- id1 = `表格 : 致香港聯合交易所有限公司 (「本交易所」)`
- id2 = `的股份購回報告`
- id3 = `G 表 格`（多空格为 veraPDF 补间隔，与现状一致）

用户要求：**先给方案、批准后改码**；可用 `org.opendataloader.pdf.DebugSample1` 或 IntelliJ Debugger 调试。

---

## 2. 结论速览（TL;DR）

1. **根因**：该页 `/Contents` 是**单一内容流**，没有 `Tr` 渲染模式差异、也没有 `BDC`/`OC` 图层差异；字体 `R9 = LVVDUR+TT491A9C96tCID` 用 `0.240148 0 Td` / `-0.240148 0 Td` / `0 0.24 Td` / `-0.240148 -0.24 Td` 等 **±0.24pt 微位移重复绘制同一文本 2~4 次**（典型的"伪粗体"/描边叠印效果）。副本的**字体 / 字号 / 字重(400) / 颜色全部相同**，唯一差异是每字约 ±0.24pt 的位置偏移。
2. **因此无法靠字体、颜色、图层区分副本**，只能走**坐标重叠 + 文本一致**的去重路线。
3. **现有 `TextProcessor.removeSameTextChunks`（基于 `Objects.equals(value)` 整块相等）失效**：veraPDF 在切分 `TextChunk` 时，各副本被切分的边界不同（例如副本 A 在"交易所"三字处切一刀、副本 B 在"限公司"切一刀），导致同名副本的 chunk 边界不重合，`removeSameTextChunks` 认为它们是"不同文字"而全部保留 → 叠字残留。同时微位移会把一个真字形在相邻副本身上切成 0.24pt 的"零宽幽灵字"，进一步干扰整块比较。
4. **关键抓手**：`TextChunk.getSymbolEnds()` 提供了**逐字（symbol）级别的真实坐标**，可在逐字粒度上做"绘制位置是否重叠"的判定，从而绕开 chunk 切分边界不一致的问题——这正是新方案的核心。
5. **方案 A（用户确认）**：新增 `TextProcessor.removeOverprintedTextChunks`，按"行"聚类符号 → 行级门控（宁漏不误）→ 逐字按"绘制位置"判定重叠并只保留覆盖最多、位置最全的一份副本 → 重建 chunk（收缩 bbox）。
6. **3 轮迭代修复**：
   - ① 首版保留字形会跨副本散落、顺序反转 → 改"整副本优先"；
   - ② 整块保留导致块内重复字残留 → 改按"绘制位置"逐字判定（保留覆盖最多未覆盖簇的那份副本）；
   - ③ **严重回归**：零宽幽灵字判定过宽，误删合法窄字（`0.135pt` 的 `「`、`0.539pt` 的 `3`）→ 把"无副本即删"收紧为"必须紧邻/覆盖同字真字形才算幽灵"，并加回归单测 + 逆向验证。
7. **全量回归暴露丢字**：git stash 基线 + 85 份 PDF 扫描（`scan_before` / `scan_after`）对比，发现 4~6 份文档因 ③ 的宽泛判定而**丢字**；收紧逻辑后已复核目标文件及 4 个丢字用例均恢复。

---

## 3. 完整定位过程（从问题到根因）

### 3.1 第 1 步：多工具定性，确认"真叠字"而非渲染问题

先用 `pdfplumber` / `PyMuPDF` 两种独立工具抽取该页文本与字符坐标，互相印证：

- 两个库都显示前 3 行每个字符出现 2~4 次，坐标呈 ±0.24pt 的网格状偏移簇；
- 不是"同一个 glyph 被引用多次"（合并字符），而是**逐字独立绘制多次**。

→ 确认这是"内容流里就把同一串文字画了多遍"的源头问题，去重必须在内容解析阶段做，不能寄望于某个库的"合并重复"。

### 3.2 第 2 步：读内容流，定位绘制机制

直接解析该页 `/Contents` 原始操作符（Java 探针导出 `raw_chunks.tsv`），发现：

- `/Contents` 是**单一流**（无多个 XObject / 无 `BDC` 包裹的图层）；
- 字体为 `R9 = LVVDUR+TT491A9C96tCID`，所有副本共用同一 `Font` 资源、同一 `Tf` 字号、同一 `rg`/`k` 颜色、**字重恒为 400**；
- 副本之间通过 `Td` 微位移切换：

  ```
  ... Tj            ← 主副本（基准位置）
  0.240148 0 Td     ← 右移 0.24pt
  ... Tj
  -0.240148 0 Td    ← 左移 0.24pt
  ... Tj
  0 0.24 Td / -0.240148 -0.24 Td  ← 上下微移
  ... Tj
  ```

→ **根因定性完成**：这是"伪粗体/描边叠印"手法——通过把同一字形在原位附近微位移重复绘制 N 次，制造笔画加粗的视觉效果。副本之间在字体/字号/字重/颜色上**没有任何可利用的差异维度**，唯一差异是约 ±0.24pt 的位置。因此**只能按"坐标重叠 + 文本一致"去重，没有任何字体/图层捷径**。

### 3.3 第 3 步：为什么现有 `removeSameTextChunks` 不工作

`TextProcessor.removeSameTextChunks` 的核心判定是**整块文本相等**：

```java
// 概要逻辑
if (Objects.equals(aChunk.getValue(), bChunk.getValue()) && 同页同框) {
    丢弃后出现的副本
}
```

问题在于 veraPDF 把内容流切成 `TextChunk` 时，**每个副本被切分的边界点不同**：

- 副本 A 在"交易"和"所"之间切一刀 → `"致香港聯合交易"` / `"所有限公司(「本交易所」)"`；
- 副本 B 在"限公"之间切一刀 → `"致香港聯合交易所有限"` / `"公 司 (「本交易所」)"`；

于是同名副本的字符串值 `value` 根本不相等，`Objects.equals` 全部返回 `false` → 所有副本都被当成"不同文字"保留 → 叠字原样输出。

**叠加效应**：±0.24pt 微位移还会把一个真正的字形在相邻副本身上"劈"成左右两半，产生宽度仅 ~0.24pt 的"零宽幽灵字"，进一步让逐块比较失真。

→ 结论：**必须下沉到 symbol（逐字）级别，用 `TextChunk.getSymbolEnds()` 提供的真实逐字坐标做"绘制位置是否重叠"的判定**，才能既绕开 chunk 切分边界不一致，又能识别逐字重复绘制。

### 3.4 第 4 步：方案与门控确认（用户拍板）

用户确认（"1. 方案A 2. 接受 3. 接受 4. 确认，没问题 5. 需要"）：

1. **方案 A**：`TextProcessor.removeOverprintedTextChunks` 在内容过滤阶段介入；
2. **阈值**：位置重叠覆盖比 `OVERPRINT_COVERAGE_RATIO = 0.7` 等一组阈值（见 §5）；
3. **宁漏不误门控**：`isOverprintedRow` 先判断"这行到底是不是叠印行"，只有高度确信才进入去重，避免误伤正常文本；
4. **期望输出**：id1/id2/id3（见 §1）；
5. **需要**：做全量回归扫描（85 份 PDF）。

---

## 4. 实现与 3 轮迭代修复

### 4.1 入口与流程（落点 `TextProcessor`）

```java
public static void removeOverprintedTextChunks(List<IObject> contents) {
    // 1) 收集所有带 symbolEnds 的符号（order / chunkIndex / symbolIndex / 文本 / 左 / 右 / 基线 / 字号 / 字体）
    // 2) 按行聚类 groupOverprintSymbolsIntoRows（baseline 差 ≤ max(0.5, 0.05×字号) 且同字号同字体）
    // 3) isOverprintedRow 门控（不是叠印行直接跳过，宁漏不误）
    // 4) removeOverprintedSymbolsInRow 逐字去重
    // 5) rebuildChunksWithoutDroppedSymbols（用 TextChunk.getTextChunk(chunk,s,e) 重建连续段、收缩 bbox）
}
```

`ContentFilterProcessor.getFilteredContents` 接入顺序（去重先于既有 `removeSameTextChunks`）：

```java
List<IObject> pageContents = new ArrayList<>(contents);
TextProcessor.removeOverprintedTextChunks(pageContents);
pageContents = DocumentProcessor.removeNullObjectsFromList(pageContents);
TextProcessor.removeSameTextChunks(pageContents);
pageContents = DocumentProcessor.removeNullObjectsFromList(pageContents);
```

### 4.2 常量一览

```java
private static final double OVERPRINT_COVERAGE_RATIO    = 0.7;   // 重叠覆盖比阈值
private static final double OVERPRINT_DEGENERATE_FACTOR = 0.05;  // 幽灵字宽度因子
private static final double OVERPRINT_DEGENERATE_ABS    = 0.4;   // 幽灵字宽度绝对值上限
private static final double OVERPRINT_MIN_REAL_WIDTH    = 2.0;   // 真字形最小宽度
private static final double OVERPRINT_HALF_GLYPH_RATIO  = 0.6;   // 较窄者 ≤ 0.6×较宽者视为叠印
private static final double OVERPRINT_ROW_BASELINE_FACTOR = 0.05;
private static final double OVERPRINT_ROW_BASELINE_ABS    = 0.5;
private static final double OVERPRINT_FONT_SIZE_RATIO     = 0.1;
private static final double OVERPRINT_SAME_X_FACTOR       = 0.05;
private static final double OVERPRINT_SAME_X_ABS          = 0.3;
private static final double OVERPRINT_NEARBY_ABS          = 0.5;  // gap ≤ 0.5 视为紧邻
```

### 4.3 迭代修复 1：保留字形跨副本散落、顺序反转 → 整副本优先

**现象**：首版按 symbol 直接挑"重叠中保留一个"，结果同字形的不同部分来自不同副本，重建后的 chunk 里字符顺序被打乱/反转。

**修复**：去重时以"整副本（同一次 `Tj` 绘制的连续符号序列）"为单位优先保留，保证同一字形始终来自同一副本，顺序不被打乱。

### 4.4 迭代修复 2：整块保留致块内重复字残留 → 按"绘制位置"逐字判定

**现象**：改整副本优先后，某些副本内部仍残留重复字（同一副本自身就含有被微位移拆出的重复字符），整块保留反而把重复字一并留下了。

**根因**：副本并非"整体多画一遍"，而是"每个字在原位附近画多遍"；按整副本保留无法消除**块内**逐字重复。

**修复**：改为逐字判定。核心数据结构 `OverprintSymbol`（order, chunkIndex, symbolIndex, value, left, right, baseLine, fontSize, fontName）。`removeOverprintedSymbolsInRow` 流程：

1. `findSqueezedCopyOrders`：先丢"幽灵字"（见 4.5）；
2. `clusterSymbols`：把"同字 + 同绘制位置"的符号聚成簇；
3. 贪心：每次选**覆盖最多未覆盖簇**的那份副本，记入 `keptOrders`，其余丢；
   → 最终每个字只保留覆盖最全的一份绘制，块内重复彻底消除。

### 4.5 迭代修复 3（严重回归）：零宽幽灵字判定过宽误删合法窄字 → 收紧

**现象（全量扫描发现的严重回归）**：4~6 份文档出现**丢字**：

- `202303251679660111823147.pdf`（股票代码窄字）
- `202410231785072233292001596.pdf`
- `202504171785131172233015158.pdf`
- `202504281785145532722080227.pdf`
- `202504291785149927447006139.pdf`（技防「智控」的 `「`）

**根因**：初版 `isDegenerateSymbol` 的判定是"**宽度 ≤ max(0.4, 0.05×字号) 且 同行同字存在 ≥2pt 宽者 → 视为幽灵字删除**"。但 `「` 这种合法窄字宽度只有 `0.135pt`、`3` 这类字符宽度 `0.539pt`，它们**根本没有副本、是真实字符**，却被"同行有更宽同字"的宽松条件误判成幽灵字而删除。

**修复**：把"无副本即删"收紧为"必须 `isSqueezedCopy`——与同字真字形**紧邻或覆盖**（gap ≤ `OVERPRINT_NEARBY_ABS=0.5`）才算幽灵"。新增常量 `OVERPRINT_HALF_GLYPH_RATIO`、`OVERPRINT_NEARBY_ABS` 及 `isSamePaintedPosition` / `isSqueezedCopy` / `getGap` / `sortedByLeft` / `findSqueezedCopyOrders`。Python 镜像 `sim2.py` 同步收紧。

**闭环验证（逆向）**：新增回归单测 `...KeepsCollapsedWidthCharacterWithoutCopy`（0.135pt `「` 无副本不删），并**逆向验证**——把旧宽松逻辑恢复后该用例必然失败，证明新判定确实解决了丢字且不会退化回旧行为。

---

## 5. 关键方法/谓词清单（均在 `TextProcessor`）

| 方法 | 作用 |
|---|---|
| `removeOverprintedTextChunks(List<IObject>)` | 入口，驱动全流程 |
| `groupOverprintSymbolsIntoRows` | 按 baseline/字号/字体聚类成行 |
| `isOverprintedRow` | 门控：先 `isDegenerateSymbol` 排除幽灵字行，再判 `isSamePaintedPosition`/`isSqueezedCopy` |
| `isSamePaintedPosition` | 交叠 ≥ 0.7 且左差 ≤ max(0.3, 0.05×字号) 或 较窄者 ≤ 0.6×较宽者 |
| `isSqueezedCopy` | 一真一幽灵且 gap ≤ 0.5 → 幽灵 |
| `getGap` / `sortedByLeft` | 相邻符号间隙 / 按左缘排序 |
| `findSqueezedCopyOrders` | 先丢幽灵字 |
| `clusterSymbols` | 同字同位置聚簇 |
| `removeOverprintedSymbolsInRow` | 贪心保留覆盖最多未覆盖簇的副本 |
| `rebuildChunksWithoutDroppedSymbols` | 用 `TextChunk.getTextChunk(chunk,s,e)` 重建连续段、收缩 bbox |
| 内部类 `OverprintSymbol` | 单符号的 order / chunkIndex / symbolIndex / value / left / right / baseLine / fontSize / fontName |

---

## 6. 单测（`TextProcessorTest` 6 个新用例）

- `testRemoveOverprintedTextChunksCollapsesFourCopies`：4 份副本折叠为 1 份；
- `testRemoveOverprintedTextChunksKeepsWholeGlyphOverSplitHalves`：真字形跨副本切分半字时保留完整；
- `testRemoveOverprintedTextChunksKeepsFrontMergedCopyOnce`：前并副本只保留一次；
- `testRemoveOverprintedTextChunksLeavesNormalTextUntouched`：正常文本不受影响；
- `testRemoveOverprintedTextChunksIgnoresChunksWithoutSymbolEnds`：无 symbolEnds 的 chunk 跳过；
- `testRemoveOverprintedTextChunksKeepsCollapsedWidthCharacterWithoutCopy`：**回归用例**（0.135pt `「` 无副本不删，逆向验证收紧逻辑）。

helper：`overprintChunk` / `renderByLeftX` / `countChar`。

---

## 7. 全量回归扫描（进行中）

### 7.1 基线与方法

- `git stash` 出修复前的 `scan_before`（有效基线，无 `removeOverprintedTextChunks`）；
- `TmpScanRunner.java` 读 `tmp_output/probe/scan_files_{1,2,3}.txt`（85 份 PDF 切 3 批），逐份输出 JSON 到 `tmp_output/scan_after`；
- `diff_scan.py` 对比 `scan_before` / `scan_after`。

### 7.2 已发现与已解决

- 目标文件 `200812311782183951489043113-1.pdf` 前 3 行修复（id1/id2/id3 正确）；
- 收紧幽灵字逻辑后，4 个丢字用例（`20230325…`、`20241023…`、`20250417…`、`20250428…`、`20250429…`）经 Python `sim2.py` 复核均已恢复；
- **末次编辑**：`TmpScanRunner.java` 加 UTF-8 BOM 处理 `if (pdf.startsWith("\uFEFF")) pdf = pdf.substring(1);`（修复读清单遇 BOM 致路径错误）。该修改**尚未重编译/重跑**。

### 7.3 待完成

- 重编译 `TmpScanRunner` → 用收紧后代码重跑 `scan_after`（3 批）→ `diff_scan.py` 复核：目标文件前 3 行修复、5 份丢字文档无回归、其余文档零改动。

---

## 8. 根因汇总

| 问题 | 根因 | 类别 |
|---|---|---|
| 前 3 行"叠字" | 单一内容流 + 伪粗体手法：同字体/字号/字重/颜色，仅 ±0.24pt 微位移重复绘制同一文本 2~4 次 | **源 PDF 特征（伪粗体叠印）** |
| 现有 `removeSameTextChunks` 失效 | 副本被 veraPDF 切分的 chunk 边界不同 → `Objects.equals(value)` 恒 false；微位移产生 0.24pt 零宽幽灵字 | **既有去重粒度太粗（整块）** |
| 迭代 1 顺序反转 | 逐 symbol 挑重叠导致同字不同副本片段混排 | 实现 bug |
| 迭代 2 块内重复残留 | 副本是"逐字多画"而非"整块多画"，整副本保留消除不了块内重复 | 设计盲区 |
| 迭代 3 严重丢字 | `isDegenerateSymbol` 单看宽度+同行有更宽同字即删，误删合法窄字（`「` 0.135pt、`3` 0.539pt） | **实现 bug（判据过宽）** |
| `TmpScanRunner` 路径错 | 读清单遇 UTF-8 BOM 致首行路径含 `\uFEFF` | 环境/流程 |

---

## 9. 经验教训

1. **遇到"同一文本出现多次"，先问"副本之间有没有可区分维度"**。本例字体/字号/字重/颜色/图层全同，只有坐标微位移 → 只能按"坐标重叠+内容一致"去重，不存在字体/图层捷径。
2. **整块相等去重对"切分边界不同"的副本天然失效**；凡涉及"内容流被切分成 chunk"的副本比对，应下沉到 `getSymbolEnds()` 提供的**逐字坐标**粒度。
3. **"宁漏不误"门控是保命绳**：去重谓词一旦过宽，损失是不可逆的丢字（且难以发现）。对"幽灵字/窄字"这类边界，必须要求"与真字形紧邻/覆盖"才删除，而不能只看自身宽度。
4. **每次去重/过滤改动必须做全量语料扫描**：本次正是靠 85 份 PDF 的 `scan_before`/`scan_after` 差分，才暴露出"单测全绿但真实文档丢字"的回归——单测覆盖不到的合法窄字场景，只有全量扫描能抓到。
5. **逆向验证压缩判定范围**：收紧判据后，用"恢复旧逻辑必失败"的回归单测锁定修复边界，防止后续改动把 bug 改回去。
6. **扫描/清单类脚本要防 BOM**：`scan_files.txt` 这类 UTF-8 清单可能带 BOM，首行读取需 `startsWith("\uFEFF")` 剥离，否则路径含不可见字符导致整批失败。

---

## 10. 关联记忆 / 工具

- 探针：`org.opendataloader.pdf.DebugSample1`（用户指定调试入口）、根目录 `TmpOverlapProbe.java` / `TmpOverprintDebug.java` / `TmpScanRunner.java`。
- Python 镜像：`sim2.py`（Python 侧同步了收紧后的幽灵字判定，用于交叉验证）。
- 既有去重逻辑 `TextProcessor.removeSameTextChunks`（整块相等，本次在其**之前**插入坐标级去重）。
- 本次改动尚未 git 提交（用户仅要求全量回归，未要求入库）。
