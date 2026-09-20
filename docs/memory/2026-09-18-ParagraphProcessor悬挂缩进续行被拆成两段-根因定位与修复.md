# opendataloader-pdf 任务记忆 — 2026-09-18（`ParagraphProcessor` 悬挂缩进续行被拆成两段：根因定位与修复）

> 样本：`docs/pdf/200711131781638086275024718-12.pdf`（单页）
> 涉及文件（唯一）：`java/opendataloader-pdf-core/src/main/java/org/opendataloader/pdf/processors/ParagraphProcessor.java`
> 分支：`ocr-unification-20260820`
> 状态：**改动已落地但尚未提交**（工作区 `git diff` 显示 57 insertions，0 deletions）
> 本轮 = **现象复现 → 版面定性 → 判据链逐条排除 → 根因定位 → 新增判据 → 单页验证 + 单测回归**

---

## 1. 现象

该单页文档里，下面两行**在视觉上属于同一段**（编号行 + 悬挂缩进续行），
但输出成两个独立的 `text` item：

```
text [70.70, 527.66, 524.31, 542.02]  font_size=12.0
     "9. 根據完成分派寄發恒基地產股票及寄發現金付款支票與透過中央結算系統作出"
text [93.38, 546.26, 258.03, 559.54]  font_size=12.0
     "額外現金分派及股息金額付款"
```

即 `"…作出"` 与 `"額外現金分派…"` 本应连成一句，却被切成了两段。

---

## 2. 结论速览（TL;DR）

1. 这两行的排版形态是 **"编号行顶格 + 续行悬挂缩进"**：`9.` 起于版心左边界 `70.70`，
   续行缩进 `93.38 - 70.70 = 22.68pt`（≈ `"9.  "` 三个字符的宽度），行距正常。
2. `ParagraphProcessor.prejudgeParagraphs` 的判据链里**唯一与缩进沾边的那条规则是"镜像"的**
   —— 它判断的是"**首行缩进、续行顶格**"，对于本例（首行顶格、续行缩进）表达式算出 `-22.68`，
   不满足 `> -5`，直接落空。
3. 与此同时，两行**既非左对齐也非右对齐，更非居中**：缩进 22.68pt 远大于对齐容差
   `maxFontSize × ALIGNMENT_PARAM(0.2) = 2.4pt`，因此 `ChunksMergeUtils.getAlignment()` 返回 `null`，
   所有基于对齐的检测步骤全部认不出这两行。
4. → **判据链里没有任何一条覆盖"悬挂缩进"这一形态**，代码落到各步骤的兜底分支，两行**各自成段**。
5. 修复：新增一条**正向**判据 `isHangingIndentContinuation(...)`，用
   **前一行填满版心 + 本行缩进量合理 + 本行不是编号行 + 垂直间距是正常行距** 四要素识别悬挂缩进续行。

---

## 3. 完整定位过程（从问题到根因）

### 第 1 步：渲染版面，给现象"定性"

先不看代码，直接把这两行的几何拿出来比对：

| 项 | 编号行 | 续行 | 结论 |
|---|---|---|---|
| `x0`（左边界） | `70.70` | `93.38` | 续行**右移 22.68pt** |
| `x1`（右边界） | `524.31` | `258.03` | 编号行**贴住版心右边界**，续行是短行 |
| `y0 / y1` | `527.66 / 542.02` | `546.26 / 559.54` | 行高分别 14.36 / 13.28 |

两个关键读数：

- **`9.` 是顶格编号**（`x0` 正好在版心左边界），续行缩进量 `22.68` 与 `"9.  "` 的宽度吻合
  → 典型的 **编号 + 悬挂缩进**（hanging indent）排版。
- **编号行的 `x1 = 524.31` 恰好等于版心右边界** → 这一行是**被折行的满行**，不是段末短行。

### 第 2 步：用行距排除"其实是两段"的可能

如果这是两个真实段落，通常伴随增大的段间距。实测：

```
行距 = 续行.y0 - 编号行.y1 = 546.256 - 542.02 = 4.236pt
行距 + 行高(13.28) = 17.516pt ≈ 1.46 × 字号(12)
```

`1.46 × 字号` 正是该文档的正常行距（典型 1.2~1.5 倍），**没有段间距特征**。
→ 排除"间距过大被拆开"这条路径，问题必然出在**判据逻辑**上。

### 第 3 步：把 `prejudgeParagraphs` 的判据链逐条排除

`ParagraphProcessor.prejudgeParagraphs` 是一条 `if / else if` 判定链，命中任何一条就 `return`。
按顺序逐条对照本例（`prevLastLine` = 编号行，`nextFirstLine` = 续行）：

| # | 判据（`ParagraphProcessor.java`） | 本例取值 | 是否命中 |
|---|---|---|---|
| 1 | `margin > MAX_LINE_SPACING_RATIO(3.0) × prevFontSize`（L355） | `4.236 > 3.0 × 12 = 36`？否 | ❌ |
| 2 | `hasSeparatorBetween(...)`（L359） | 两行之间无横线/分隔图形 | ❌ |
| 3 | `\|prevFontSize - nextFontSize\| >= 2`（L369） | `\|12 - 12\| = 0` | ❌ |
| 4 | `margin - prevMargin >= 5`（L372） | 行距均匀 | ❌ |
| 5 | `margin - nextMargin >= 5`（L375） | 行距均匀 | ❌ |
| 6 | `rightX - prevLastLineRightX > 20 \|\| 上一行中心 < width/2 - 20`（L378-379） | `\|524.31 - 524.31\| = 0`；中心在版心内 | ❌ |
| 7 | **"段首缩进"判据**（L382-383） | 见下方专述 | ❌ **关键** |
| — | 其余各 `detect*` 步骤（左/右/居中/两行段等） | `getAlignment` 返回 `null` | ❌ |

#### 3.1 关键判据：那条"缩进规则"是镜像的

```java
// ParagraphProcessor.java:382-383（改动前既有代码）
} else if ((Math.abs(prevLastLineRightX - rightX) < 5
            || width / 2 - (prevLastLineLeftX + prevLastLineRightX) / 2 < 10)
        && prevLastLineLeftX - nextFirstLineLeftX > -5
        && prevLastLineLeftX - nextFirstLineLeftX < MAX_PARAGRAPH_BEGINNING_INDENT /* 50 */) {
```

它想表达的是：**上一行比下一行更靠右（即上一行缩进、下一行顶格）**，差值落在 `(-5, 50)` 区间内。
把本例代进去：

```
prevLastLineLeftX - nextFirstLineLeftX = 70.70 - 93.38 = -22.68
-22.68 > -5  →  false
```

**符号方向正好相反**：既有判据覆盖的是"**首行缩进、续行顶格**",
而本例是它的**镜像**形态"**首行顶格、续行缩进**"。

注意第一组括号里的条件其实**是满足的**（`|524.31 - 524.31| = 0 < 5`，即"上一行填满版心"），
所以这条规则差的只有缩进符号那一项 —— 说明它的**设计意图已经包含了本例的几何前提**，
只是**没把镜像情形写进去**。

#### 3.2 对齐路径为什么也全军覆没

对齐判定在 `veraPDF-wcag-algs` 的 `ChunksMergeUtils.getAlignment()`：

```java
// ChunksMergeUtils.java:406-411
public static TextAlignment getAlignment(TextLine previousLine, TextLine currentLine) {
    double maxFontSize = Math.max(previousLine.getFontSize(), currentLine.getFontSize());
    double delta = maxFontSize * ALIGNMENT_PARAM;      // ALIGNMENT_PARAM = 0.2（L55）
    boolean isLeft  = NodeUtils.areCloseNumbers(previousLine.getLeftX(),  currentLine.getLeftX(),  delta);
    boolean isRight = NodeUtils.areCloseNumbers(previousLine.getRightX(), currentLine.getRightX(), delta);
    ...
}
```

容差 `delta = max(12, 12) × 0.2 = 2.4pt`，而实际差值：

- 左差 `|70.70 - 93.38| = 22.68` ≫ 2.4 → `isLeft = false`
- 右差 `|524.31 - 258.03| = 266.28` ≫ 2.4 → `isRight = false`

→ `getAlignment()` 返回 **`null`**，于是 `JUSTIFY / LEFT / RIGHT / CENTER` 四个 detect 步骤**全部不认这两行**。
**悬挂缩进的本质就是"故意不对齐"**，所以所有以"对齐"为前提的启发式天然覆盖不到它。

### 第 4 步：确认没有其它路径能救

逐条检查完，结论是：`prejudgeParagraphs` 返回 `false`（`hasJudge` 未被置位），
后续各 `detect*` 步骤也因 `getAlignment == null` 而无法合并 → 两行被分别放进 `newBlocks`
→ 各自成为一个 `SemanticParagraph`（后经 `HeadingProcessor` 判定，前者块类型为 `heading`）。

→ **根因成立：悬挂缩进这一排版形态在整条判据链里是空白。**

---

## 4. 根因

`ParagraphProcessor.prejudgeParagraphs` 中唯一的"缩进类"判据
（`ParagraphProcessor.java:382-383`）只覆盖 **"首行缩进、续行顶格"** 一种方向，
其缩进量表达式 `prevLastLineLeftX - nextFirstLineLeftX > -5` 对 **"首行顶格、续行缩进"** 的镜像情形
必然算出负值而落空；同时悬挂缩进的续行与编号行**故意不对齐**（缩进 22.68pt ≫ 对齐容差 2.4pt），
使 `getAlignment()` 返回 `null`，所有对齐类检测步骤一并失效。

**两条路径同时失效 → 悬挂缩进续行没有任何判据覆盖 → 落入兜底"各自成段"，
同一个段落的编号行与续行被拆成两个 `text` item。**

---

## 5. 修复

### 5.1 新增常量（`ParagraphProcessor.java:53-57`）

```java
/**
 * Tolerance (pt) for judging that a line fills the whole text column, i.e. that its right
 * edge reaches the column right edge (see {@link #isHangingIndentContinuation}).
 */
private static final double HANGING_INDENT_RIGHT_TOLERANCE = 5.0;
```

选 `5.0` 的理由：与既有判据 L382 里 `Math.abs(prevLastLineRightX - rightX) < 5`
（判"上一行填满版心"）**保持同一口径**，避免两条规则对"什么叫满行"给出不同答案。

> 小瑕疵（未改）：L382 里那个 `5` 仍是硬编码字面量，后续可一并换成该常量。

### 5.2 新增判据方法（`ParagraphProcessor.java:434-477`）

```java
/**
 * Returns true when {@code nextBlock} is the continuation line of a numbered heading laid
 * out with a <em>hanging indent</em>: the previous line fills the whole text column and the
 * next line is indented by roughly the width of the leading label (e.g. {@code "9.  "}).
 *
 * <p>The existing opening-indent rule only covers the mirrored case — a first line indented
 * relative to the lines that follow it. A hanging-indent pair matches none of the other
 * rules either: its two lines are neither left-, right- nor center-aligned (the indentation
 * exceeds the alignment tolerance), so without this test the continuation is emitted as a
 * separate paragraph.</p>
 *
 * <p>The "previous line fills the column" requirement is what keeps real paragraph breaks
 * out: a block that starts a new paragraph is normally preceded by a short last line, whose
 * right edge sits well inside the column.</p>
 */
private static boolean isHangingIndentContinuation(TextBlock previousBlock, TextBlock nextBlock,
                                                   double rightX, double margin) {
    TextLine previousLine = previousBlock.getLastLine();
    TextLine nextLine = nextBlock.getFirstLine();
    if (previousLine == null || nextLine == null) {
        return false;
    }
    // A filled line ends on the column right edge; otherwise it is the last line of a
    // paragraph (e.g. a short heading) and the next block opens a new one.
    if (Math.abs(previousLine.getRightX() - rightX) >= HANGING_INDENT_RIGHT_TOLERANCE) {
        return false;
    }
    double indent = nextLine.getLeftX() - previousLine.getLeftX();
    if (indent <= 0 || indent > MAX_PARAGRAPH_BEGINNING_INDENT) {
        return false;
    }
    // A labeled line ("10. ...", "一、...") opens its own entry, never a continuation.
    if (BulletedParagraphUtils.isLabeledLine(nextLine)) {
        return false;
    }
    double fontSize = previousLine.getFontSize();
    return fontSize > 0 && margin <= MAX_LINE_SPACING_RATIO * fontSize;
}
```

**四条判据的设计意图（必须全部满足才合并，缺一不可）：**

| # | 判据 | 代码 | 防住什么 |
|---|---|---|---|
| 1 | 前一行**填满版心** | `\|previousLine.getRightX() - rightX\| < 5` | 真实段落切换：段末短行右边界离版心很远 → 不会被误并。**这是本方法最重要的护栏** |
| 2 | 续行**缩进量合理** | `0 < indent <= MAX_PARAGRAPH_BEGINNING_INDENT(50)` | `indent <= 0` 时是"顶格"或"更靠左"（应交给既有的镜像判据 / 左对齐路径）；`> 50` 时更可能是排版缩进或表格内容，不是编号宽度 |
| 3 | 续行**不是编号行** | `!BulletedParagraphUtils.isLabeledLine(nextLine)` | `"10. …"` / `"一、…"` 是**新条目的开头**，绝不能并进上一条 |
| 4 | 垂直间距是**正常行距** | `margin <= MAX_LINE_SPACING_RATIO(3.0) × fontSize` | 段间距（约 2 倍字号）与跨块大间距（可达 15 倍）不得被当成折行 |

判据 4 顺带带来一个**结构性好处**：它与判据链首条
（`margin > MAX_LINE_SPACING_RATIO × prevFontSize` → 判新块）**互斥**，
所以新规则不可能与首条规则产生矛盾结论，插入位置在这条前后在语义上都是安全的。

### 5.3 插入位置（`ParagraphProcessor.java:363-368`）

```java
} else if (hasSeparatorBetween(previousBlock, nextBlock, separators)) {   // ← 必须在这条之后
    newBlocks.add(nextBlock);
    hasJudge = true;
    return hasJudge;
} else if (isHangingIndentContinuation(previousBlock, nextBlock, rightX, margin)) {   // ← 新增
    previousBlock.add(nextBlock.getLines());
    previousBlock.setTextAlignment(TextAlignment.LEFT);
    previousBlock.setHasEndLine(false);
    hasJudge = true;
    return hasJudge;
} else if (Math.abs(prevFontSize - nextFontSize) >= 2) {                  // ← 之前
```

放在 `hasSeparatorBetween` **之后**是有硬性理由的：**分隔线优先级必须高于缩进**。
若两行之间跨着一条水平线（下划线、装饰横线），那是明确的分段信号，
此时哪怕几何上完美符合悬挂缩进，也**必须断开** —— 顺序颠倒会把分隔线两侧的内容错误合并。

放在字号差判据**之前**：悬挂缩进的编号行与续行**通常同字号**（本例都是 12.0），
字号差判据本就不会命中；提前是为了让"能识别的形态"尽早定型，不要再落到后面的通用兜底里。

**命中后的三个动作：**

| 动作 | 作用 |
|---|---|
| `previousBlock.add(nextBlock.getLines())` | 把续行的行并进前一块，形成一个段落 |
| `setTextAlignment(TextAlignment.LEFT)` | 块内两行故意不对齐，显式声明为左对齐，避免下游按 `null` 处理 |
| `setHasEndLine(false)` | 标记该块**不是**以"段末行"结束 → 段落被视为可继续延伸，供下游（如分段/换页判断）使用 |

---

## 6. 验证

### 6.1 样本回归（`200711131781638086275024718-12.pdf`，单页）

| 项 | 修复前 | 修复后 |
|---|---|---|
| 该页 `text` item 数 | 9 | **8** |
| 目标块 bbox | `[70.70, 527.66, 524.31, 542.02]` | `[70.70, 527.66, 524.31, **559.54**]` |
| 目标块文本 | 仅编号行（23 字级） | 编号行 + 续行合并（记录文本长度 52） |

`y1` 由 `542.02` 扩展到续行底部 `559.54`，说明续行确实被并进了同一个块。

**未误并**：同页另一段（`在發生完成之前提下…`）**未被牵连合并**，
印证"前一行填满版心"这条护栏有效（该段前置行是短行，右侧留白大）。

### 6.2 单测回归

```
相关套件 44/44 绿
  ParagraphProcessorTest   2
  FlowchartProcessorTest  12
  ShapeRecognizerTest     22
  BarChartProcessorTest    6
  ArrowE2ETest             2
```

因为这是**新增判据**（原先无任何规则覆盖该形态），既有用例全绿只说明**没有回归**，
并不构成对新判据本身的验证 —— 见遗留第 2 条。

### 6.3 环境清洁

临时入口 `DebugTmpPage.java` 与 `cp.txt` 已删除并重新编译，工作区无调试残留。

---

## 7. 遗留

1. **影响面需要语料回归**：这是**新增判据**，此前无任何规则覆盖悬挂缩进，
   影响面是所有"编号 + 缩进续行"排版的文档。
   建议跑一次 items 层语料回归，**重点看 `text` 行数减少的文档**（发生了合并）是否都合理；
   尤其要盯 `indent` 落在 `(0, 50]` 边界附近、以及判据 1 的 `|rightX 差|` 接近 `5pt` 的样本。
2. **单测覆盖不足**：`isHangingIndentContinuation` **没有单测**，而 `ParagraphProcessorTest` 目前
   只有 2 个用例（`testProcessParagraphs`、`testRightAlignmentTakesPrecedenceOverTwoLineHeuristic`）。
   建议补：**1 个正例**（满行 + 缩进续行 → 合并）+ **3 个反例**：
   - 前一行**非满行**（`|rightX 差| >= 5`）→ 不合并；
   - 续行缩进**超限**（`indent > 50`）→ 不合并；
   - 续行**是编号行**（`BulletedParagraphUtils.isLabeledLine == true`）→ 不合并。
   另建议加一条"两行之间**有分隔线**"的反例，锁定 `hasSeparatorBetween` 的优先级不被后人调换。
3. **块类型是 `heading` 而非 `paragraph`**：合并后该块的 `source_type` 为 **heading**
   （首行是 `9.` 编号，被 `HeadingProcessor` 判为标题），**不是** `paragraph`。
   目前这是**有意不动**的：如果需求是"合并后仍应输出为 paragraph"，需要另调 `HeadingProcessor` 的口径，
   属于本次范围之外。
4. **改动尚未提交**：工作区 `git diff` 为 57 insertions / 0 deletions，需按项目提交规范入库。

---

## 8. 经验教训

1. **启发式判据链要按"排版形态"做覆盖度盘点，而不是只按"几何属性"。**
   既有的"缩进类"判据写的是 `A.leftX - B.leftX ∈ (-5, 50)` 这种**带符号**的区间，
   天然只覆盖一个方向。凡是用**差值**表达"谁缩进"的判据，都要问一句：
   **符号反过来是什么形态？** 这里反过来的正是悬挂缩进，而它恰好是编号列表的标准排版 —— 高频形态被漏掉。

2. **"故意不对齐"的排版天然是"对齐类启发式"的盲区。**
   `getAlignment()` 的容差是 `字号 × 0.2`（12pt 字号 → 2.4pt），悬挂缩进量动辄 20+ pt，
   远超容差 → 返回 `null` → 所有 `detect*WithAlignment` 步骤集体失效。
   排障时看到 `getAlignment == null`，**不要只当作"认不出来"，要意识到它是"一整类检测步骤的入口被堵死"**。

3. **先定性版面，再读代码，效率高得多。**
   本轮第一步是把两行的 `x0/x1` 摆在一起看，立刻读出"编号顶格 + 缩进 22.68 ≈ `9.  ` 宽度 +
   前一行贴满版心"；第二步用 `17.516pt ≈ 1.46 × 字号` 的行距**排除**"其实是两段"。
   这两步都是纯几何算术，不需要跑代码，却把后面要读的判据范围压缩到了个位数。

4. **用"镜像/对称"语言描述既有规则，能直接把缺口说出来。**
   Javadoc 里写"the existing opening-indent rule only covers the mirrored case"比写
   "该规则不适用"有价值得多 —— 它同时说明了**为什么**不适用，以及**新规则**该怎么写。

5. **新判据的位置本身就是设计决策，必须写下理由。**
   "插在 `hasSeparatorBetween` 之后"不是随手放的：分隔线优先级必须高于几何缩进。
   这类顺序约束如果不写进注释/记忆，后人一次重排就可能把分隔线两侧的内容并起来。

6. **新增判据的"护栏判据"要优先选最强的那个。**
   本方法四条判据里，第 1 条（前一行填满版心）是防误并的主力：
   真实段落的段末行几乎总是短行。把最强护栏放第一条 + 短路返回，
   既提高可读性（一眼看到主要排除条件），也降低无谓计算。

---

## 9. 关联记忆

- `docs/memory/2026-09-12-中文序号1、与上一个段落合并修复.md`
- `docs/memory/2026-09-12-中文序号一二三、与上一个段落合并修复.md`
  —— 同属"编号/序号行与相邻段落边界"问题域，且都用到了 `BulletedParagraphUtils.isLabeledLine`；
  本次新判据的第 3 条护栏与之同源。
- `docs/memory/2026-09-17-流程图误否决与段落合并误合并-根因定位与修复.md`
  —— 同一时期出现的"段落合并误合并"类问题；本次是反向的"该合没合"，
  两者对照可完整看出 `prejudgeParagraphs` 判据链的松紧边界。
- `docs/memory/2026-08-18-ParagraphProcessor-TextLine-NPE修复.md`
  —— 本文件内 `previousLine/nextLine == null` 防御的历史来源；
  本次新方法也沿用了同样的空值前置返回写法。
- `docs/memory/2026-08-14-202304291682681121000817-177.pdf表格后多出单字符文本修复.md`
  —— 同类"几何判据漏掉一种排版形态"的定位范式（先定性版式，再逐条排除判据）。
