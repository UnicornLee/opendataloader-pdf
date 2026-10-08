# 2026-10-08 — 第10页 `PCIeAEC及PCIeAOC` 丢失空格：TextLineProcessor(0.17) 与 JsonWriter.getText(0.4) 空格阈值不一致的根因定位与修复

> 样本：`docs/pdf/20260507AN202606291826520711.pdf`（港股 IPO 草案，繁体 + 中英混排技术术语）
> 现象页：**第 10 页**，非 tagged / 非 hybrid 路径下的正文段落（bullet 列表项）
> 缺陷文本：`• PCIeAEC及PCIeAOC產品…` 应为 `• PCIe AEC及PCIe AOC產品…`（丢了 PCIe↔AEC、PCIe↔AOC 两处空格）
> 涉及文件（本次会话改动的生产文件）：
>
> - `java/.../pdf/json/JsonWriter.java`（**唯一生产改动**：`getText` 空白 chunk 分支按边界字符类型选阈值 + `getSpaceStr` 三参重载 + 新增 `resolveSpaceThreshold`/`neighborBoundaryChar`/`isLatinLetter`）
>
> 状态：**已用真实 PDF 第 10 页复现并验证修复；编译 + DebugSample 全跑通过（exit 0）；无关路径确认无回归。未 git 提交。**
> 本文完整记录"从问题到根因"的定位链路（含两处方向纠偏），并给出经用户批准的最终方案。

---

## 1. 现象

用户报告：第 10 页解析结果中 "PCIeAEC及PCIeAOC" 应为 "PCIe AEC及PCIe AOC"，丢了两个空格。

修复前（JSON `content`，L3198 / L61106 两处相同句）：

```
"•  PCIeAEC及PCIeAOC產品，可實現服務器及加速卡的高速光電互連，提供更高傳輸頻"
      ↑ bullet 后 2 空格正常      ↑ PCIe↔AEC、PCIe↔AOC 的真实空格被丢
```

期望（也是修复后）：

```
"•  PCIe AEC及PCIe AOC產品，可實現服務器及加速卡的高速光電互連，提供更高傳輸頻"
```

---

## 2. 结论速览（TL;DR）

1. **上游 `TextLineProcessor` 是对的**：它用 `TEXT_LINE_SPACE_RATIO=0.17`（gap>0.17×fontSize 即插入空格 chunk），已正确识别并插入了 PCIe↔AEC/AOC 之间的空格 chunk。
2. **锅在下游 `JsonWriter.getText`**（段落/标题正文的 content 字符串生成器）：它对每个空白 chunk 用 `getSpaceStr(TextChunk, boolean)` **按宽度重算**要输出几个空格，硬编码阈值 **0.4**。真实空格宽度仅 ~2.5pt（fs=10 → ratio 0.25），`0.25<0.4` 且两侧非中文 → 返回 `""`，空格被丢弃。
3. **两处阈值不一致是根本矛盾**：抽取端 0.17 判"该有空格"，序列化端 0.4 二次判"这个空格够不够宽"，窄真实空格被后者否决。
4. **表格单元格不受影响**：`renderTextChunkValue` 有 `spaceStr.isEmpty() ? " " : spaceStr` 兜底，段落正文 `getText` **缺这个兜底**——这才是"段落丢空格、表格不丢"的分野。
5. **系统性缺陷**：同文档所有 ~2.5pt 的拉丁边界空格都被丢（`PCIe6.0AOC`、`6.4TNPO/CPO` 等），用户只注意到其中一处。

---

## 3. 完整定位过程（从问题到根因）

### 3.1 确认处理路径
DebugSample 未设 `useStructTree`/`hybrid` → 该 PDF 走**非 tagged、非 hybrid** 的 `DocumentProcessor.processDocument`：
Loop1 ContentFilter → Loop2 `TextLineProcessor.processTextLines` → Loop3 `ParagraphProcessor` → `extraction()` 里 `sortContents` + `ContentSanitizer.sanitizeContents`（空规则 + 半角转全角，不删空格，排除）。`businessId=123456789` 触发 `writeToCustomJson` 自定义 JSON 路径，段落 content 用 `JsonWriter.getText(...)` 生成（**不是** `TextLine.getValue()`，也**不是** `TextLineSerializer`）。

### 3.2 第一处纠偏：一度以为空格在 TextLineProcessor 丢
在 `TextLineProcessor.getTextLineWithSpaces` 加临时诊断，dump 输入 raw chunks 与输出 newLine chunks，跑 DebugSample（~3.5min）：
- 输入 raw chunks：`[•][PCIe][AEC][及][PCIe][AOC][產品…]`，**无空格 chunk、afterWs=false**（veraPDF 抽取时把 U+0020 字形整个丢了，只剩几何间距）。
- threshold=1.7（0.17×10），PCIe→AEC gap=2.50>1.7 → hasGap 成立。
- **输出 RESULT：`[•][_][PCIe][_][AEC][及][PCIe][_][AOC][產品]`** —— `getTextLineWithSpaces` **已正确插入空格 chunk**。
→ 方向纠正：不是 TextLineProcessor 丢的空格，继续往下游查。

### 3.3 第二处纠偏：与 JSON 对不上，怀疑序列化阶段重建
`getTextLineWithSpaces` 输出是"bullet 后 1 空格、PCIe-AEC 有 1 空格"，但 JSON 却是"bullet 后 2 空格、PCIe-AEC 无空格"。两者不符 → 空格在 **序列化阶段被重建/丢弃**。锁定 `JsonWriter.getText`（L1677）。

### 3.4 建 classpath 编译坑
单编 `JsonWriter.java` 失败（`target\classes` 里 `PageBookmarkProcessor` 陈旧，缺 `discardSoleTopLevelBookmark`）。改用 JDK17 整体重建：`$env:JAVA_HOME='...jdk-17.0.0.1'; mvn -o -q compile`（模块 opendataloader-pdf-core）成功。

### 3.5 决定性取证：getText 输入 dump
在 `getText` 加临时诊断 dump 输入 chunks，重跑（`tmp_output/gettext_dump.txt`）：
```
v=[•]     L=93.56 R=97.06   ws=false
v=[_]     L=97.06 R=116.24  ws=true  (宽 19.18, ratio 1.92 → 2 空格)
v=[PCIe]  L=116.24 R=136.54 ws=false
v=[_]     L=136.54 R=139.04 ws=true  (宽 2.50, ratio 0.25 <0.4, 非中文 → "" 空格被丢)
v=[AEC]   L=139.04 R=160.07 ws=false
v=[及]    L=160.06 R=170.06 ws=false
v=[PCIe]  L=170.06 R=190.36 ws=false
v=[_]     L=190.36 R=192.86 ws=true  (宽 2.50 → "" 空格被丢)
v=[AOC]   L=192.86 R=215.00 ws=false
```
`getText` 确实收到了带空格 chunk 的行（空格 chunk 的 bbox 已是 gap 区间），但 `getSpaceStr(chunk, haveChinese)` 按宽度重算把 2.50/10=0.25 判成 `""`。精确复现 "•  PCIeAEC及PCIeAOC"。**根因确认。**

### 3.6 还原诊断
`git checkout -- JsonWriter.java TextLineProcessor.java`，两处生产代码回到干净状态（全程遵守"先不改代码"约束）。诊断产物临时文件已全部删除。

---

## 4. 根因

| 层 | 事实 | 定性 |
|---|---|---|
| 抽取端判空格 | `TextLineProcessor.getTextLineWithSpaces` 用 `TEXT_LINE_SPACE_RATIO=0.17`，gap>0.17×fs 即插空格 chunk | **正确**（已插入 PCIe↔AEC 空格 chunk） |
| 序列化端重建 | `JsonWriter.getText` 对空白 chunk 用 `getSpaceStr(TextChunk,boolean)` 硬编码阈值 `0.4` 按宽度重算空格数 | **代码缺陷**：0.25<0.4 且非中文 → 返回 "" 丢弃 |
| 阈值不一致 | 0.17（该不该有）vs 0.4（够不够宽） | **根本矛盾** |
| 表格路径 | `renderTextChunkValue` 有 `isEmpty()?" ":...` 兜底 | 对照组：段落正文缺此兜底才丢空格 |
| 触发量级 | 拉丁词内真实空格宽度 ~2.5pt（0.25em），窄于 0.4em | PDF 字体度量特性 |

---

## 5. 修复方案（经用户批准）

用户明确否定了"空白 chunk 至少给一个空格"的粗暴兜底，改为**按边界字符类型差异化放宽阈值**：

- 只作用于 `getText` 的**空白 chunk 分支**（L1704）。
- 看空白 chunk **前后最近的非空白 chunk** 的首/末字符类型：
  - **字母-字母 / 字母-数字 / 数字-字母** → 阈值放宽到 **0.2**（PCIe↔AEC ratio 0.25∈[0.2,1) → 输出 1 空格 ✅）；
  - **数字-数字** → 保持 **0.4**（数字串不被拆开，如 "1000"）；
  - **中文 / 标点 / 符号** → 保持原逻辑（0.4 + 中文给空格）。
- **不动**：两个非空白 chunk 之间的 gap-based `getSpaceStr(double,double)`（L1693）保持 0.4，防幻影空格回归。
- **不动**：表格单元格路径——原 `getSpaceStr(TextChunk,boolean)` 保留并委托新三参重载 `getSpaceStr(chunk, haveChinese, 0.4)`，`renderTextChunkValue` 行为完全不变。

代码改动（`JsonWriter.java`）：
```java
// getText 空白 chunk 分支
if ("".equals(val.trim())) {
    text += getSpaceStr(chunk, haveChinese, resolveSpaceThreshold(textChunks, i));
}

// getSpaceStr 拆两参委托 + 三参带 lowThreshold
private static String getSpaceStr(TextChunk chunk, boolean haveChinese) {
    return getSpaceStr(chunk, haveChinese, 0.4);
}
private static String getSpaceStr(TextChunk chunk, boolean haveChinese, double lowThreshold) {
    double ratio = (chunk.getRightX() - chunk.getLeftX()) / chunk.getFontSize();
    if (ratio < lowThreshold) { return haveChinese ? " " : ""; }
    else if (ratio < 1) { return " "; }
    else { return " ".repeat((int) Math.ceil(ratio)); }
}

// 新增：边界字符类型 → 阈值；向 ±1 跳过空白 chunk 取最近文本 chunk 的首/末字符
private static double resolveSpaceThreshold(List<TextChunk> textChunks, int i) { ... 字母/数字组合返回 0.2，否则 0.4 }
private static Character neighborBoundaryChar(List<TextChunk> textChunks, int index, int step) { ... }
private static boolean isLatinLetter(char c) { return Character.isLetter(c) && !isChinese(c); }
```

**用户三点澄清确认**：① "拉丁字符"= 所有非中文字母（`Character.isLetter && !isChinese`，含 é/ü 等）；② 只改阈值、不加兜底；③ 接受副作用 "PCIe6.0AOC" → "PCIe 6.0 AOC"（规范写法）。

---

## 6. 验证（DebugSample 重跑该 PDF，编译 + 运行 exit 0）

| 位置 | 修复前 | 修复后 |
|---|---|---|
| L3198 / L61106（第10页两处） | `•  PCIeAEC及PCIeAOC` | ✅ `•  PCIe AEC及PCIe AOC` |
| L3719 | `...800G硅光子AOC及PCIe6.0AOC...` | ✅ `...及PCIe 6.0 AOC...` |
| L64881 | `PCIe6.0/7.0` | ✅ `PCIe 6.0/7.0` |

无回归确认：
- 中文↔拉丁边界（"硅光子AOC"、"我们的AOC"）仍**不加空格**，与全文一致；
- 数字↔数字保持 0.4，数字串不拆；
- 改动只作用于 `TextLineProcessor` 已判定存在空白的空白 chunk，不会凭空造空格。

---

## 7. 经验教训

1. **同一"是否该有空格"的判定阈值必须在抽取端与序列化端对齐。** 抽取端（TextLineProcessor 0.17）与序列化端（JsonWriter.getText 0.4）分属两道关卡，任一道更严都会否决另一道已判定的空格；跨层加/减空格时先核对两端阈值语义是否一致。
2. **"段落 vs 表格"是同一批 chunk 的两条序列化路径。** `getText`（正文）与 `renderTextChunkValue`/`assembleGroupText`（表格）别忘记其中一条已有兜底而另一条没有——对照差异常直接指向缺陷点。
3. **上游对了不代表最终对。** 先证伪"TextLineProcessor 丢空格"，再定位到 `getText` 按宽度重算；遇到"中间产物正确、最终输出错误"，往序列化/渲染层查，并直接 dump 该层**输入**取证。
4. **差异化放宽优于无脑兜底。** 用户否决"至少一个空格"（会把数字串、极窄假 gap 也放大），改用"边界字符类型 + 阈值 0.2"精准命中拉丁词内空格，兼顾数字串不被拆。
5. **单文件编译失败先怀疑 target/classes 陈旧。** 用 JDK17 `mvn -o -q compile` 整体重建，别逐个 javac。

---

## 8. 遗留

1. **全文级正文回归未跑**：`getText` 改动影响**所有走自定义 JSON 路径的段落/标题正文**；建议对 `tmp_output/corpus_*` 语料做 before/after，重点看 2+ 连续空格总数、JSON 条目守恒、数字串是否被误拆。
2. **阈值 0.2 为样本驱动**：本例真实空格 ratio 0.25。若某文档拉丁词内空格更窄（<0.2）仍会被丢；若过松（0.2 已接近 TextLineProcessor 的 0.17 检出下限）需警惕——两端阈值现已基本对齐（0.17 检出 → 0.2 序列化）。
3. **未 git 提交**：仅 `JsonWriter.java` 为本次生产改动；`DebugSample.java`、`samples/json/lorem.*` 为工作区既有改动，非本次产生。

---

## 9. 关联记忆

- `docs/memory/2026-10-08-CJK幻影连续空格-严格leftX排序错位-根因定位与修复.md` —— 同为 `JsonWriter`/`TextLineProcessor` 空格相关缺陷，但那篇是"排序错造出幻影空格"，本文是"阈值不一致丢真实空格"，方向相反，可对照理解 `getSpaceStr` 家族。
- `docs/memory/2026-10-08-第8页crealights右括号错位-JsonWriter表格单元格路径-根因定位与修复.md` —— 记录了 `renderTextChunkValue`/`assembleGroupText` 表格路径的空格机制，是本文"段落缺兜底、表格有兜底"的对照来源。
- 长期记忆 `common_pitfalls_experience`（标题：PDF段落正文窄空格丢失：TextLineProcessor(0.17)与JsonWriter.getText(0.4)空格阈值不一致）与本文同源。
