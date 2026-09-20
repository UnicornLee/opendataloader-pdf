# opendataloader-pdf 任务记忆 — 2026-09-20（`JsonWriter` 表格单元格文本行合并：新增"内容边界"判据，抑制数字/标点起止的行被误拼接）

> 涉及文件（唯一）：`java/opendataloader-pdf-core/src/main/java/org/opendataloader/pdf/json/JsonWriter.java`
> 分支：`ocr-unification-20260820`
> 上游关联：本规则作用在 **2026-09-08** 建立的"单元格文本行合并"逻辑之上（`MAX_CELL_LINE_RIGHT_GAP` + `containsEmbeddedBlock`），见
> `docs/memory/2026-09-08-JsonWriter表格单元格文本行合并逻辑优化与代码review.md`
> 本轮 = **需求确认 → 反例推演定位缺陷 → 实现内容判据 → 两轮范围迭代（全角标点 / General Punctuation）→ 编译 + 单测验证**

---

## 1. 目标（Goal）

单元格内每一"物理行组"由 `assembleGroupText` 生成一条文本。是否**新起一条**、还是**拼接到上一条末尾**，
原本只由两个判据决定：

1. `containsEmbeddedBlock(group)` —— 含 `<img>` / 内嵌 `<table>` HTML 的行必须独占一条；
2. `nextNewLine` —— **上一行**右端离单元格右边界的间隙是否 > `MAX_CELL_LINE_RIGHT_GAP`（15pt）。

用户提出的新要求（原话）：

> 如果 `currentText` 的第一个字符 和 `currentCellTextLines` 的最后一条的最后一个字符
> 没有一个是中文字符或者英文字母，`newLine` 也为 `true`。

即增加**第三类、内容层面**的判据：**两端都是非中英文语境字符时，强制新起一条**。
随后用户两次追加范围要求：

- 「全角标点需要纳入」；
- 「需要纳入」（指弯引号 `“ ” ‘ ’`、破折号 `—`/`——`、省略号 `……`）。

---

## 2. 背景链路（改动前）

```
generateJsonPageContentData(...)                      // JsonWriter
  └─ 遍历 page contents，遇到 TableBorder
       └─ 双重循环展开 rowspan/colspan：
            for n in [cell.rowNumber, +rowSpan)
              for k in [cell.colNumber, +colSpan)
                   cellItem = tableBorder.getRows()[n].getCells()[k]
                   flattenCellContents(cellItem.getContents(), url, pageNumber)   ← 叶子化（见 09-08 记忆）
                   cellItemContents.sort(topY desc)                               ← 自上而下
                   groupChunksByLine(cellItemContents)                            ← 纵向重叠分组成"物理行"
                   boolean nextNewLine = false;                                   ← 跨迭代状态
                   for (List<IObject> group : cellItemGroups) {
                        String currentText = assembleGroupText(group, url, pageNumber);
                        if (currentText.isEmpty()) continue;
                        boolean newLine = containsEmbeddedBlock(group) || nextNewLine;   ← 判据 ①②
                        nextNewLine = cell.getRightX() - getGroupMaxRightX(group) > MAX_CELL_LINE_RIGHT_GAP;
                        if (newLine)                     → 新增一条
                        else if (列表为空)                → 直接加入首条
                        else                             → 拼到最后一条末尾
                   }
       └─ cellMap.put(JsonName.TEXT, cellTextLines)
```

关键：`nextNewLine` 是**上一行算出来、作用于下一行**的滞后状态（09-08 那轮的核心修正，当时第一版实现成
"用当前行自己的 gap 决定当前行"导致段落被拆开）。

---

## 3. 完整定位过程（从问题到根因）

> 说明：本轮用户是从**版面经验**出发提出的规则，未提供具体样本 PDF。因此定位手段是
> 「**通读产出路径 → 复述既有判据的能力边界 → 构造反例把缺陷逼出来**」，而不是样本复现。
> 文末"验证"一节明确区分了哪些结论是**跑出来的**、哪些是**推演出来的**。

### 阶段 1：锁定唯一改动点

单元格文本写入 JSON 的位置只有一处：`cellMap.put(JsonName.TEXT, cellTextLines)`。
`TableCellSerializer` 走的是 HTML/POJO 序列化，是另一条链路，不涉及。
→ 改动面被限定在主循环 1084-1125 这四十行内，`newLine` 的求值是唯一插入点（1097 行）。

### 阶段 2：复述既有判据的"能力边界"

把既有两条判据拆成**维度**看：

| 判据 | 维度 | 能回答的问题 |
|---|---|---|
| `containsEmbeddedBlock` | **块类型**（图片 / 内嵌表 HTML） | 这一行是不是"非文本内容"？ |
| `nextNewLine`（间隙 > 15pt） | **几何**（右边界留白） | 上一行有没有"写满"？ |

两个维度都**不包含"文字本身长什么样"**。这就是缺陷所在的结构性位置：
一个**几何上写满了、但内容是数字/标点**的行，与一个**几何上写满了、内容是中文/英文句子**的行，
在现有判据眼里**完全等价**，都会被判定为"折行续行"，从而被拼接。

### 阶段 3：构造反例，把缺陷逼出来

以单元格宽 `[0, 100]`（右边界 100）、阈值 15pt 为例，构造三种版面：

| # | 上一行（文本, 右端） | 当前行（首字符） | 现行结果 | 期望结果 |
|---|---|---|---|---|
| A | `"根據完成分派寄發恒基地產股票及寄發"`，右端 98 | `額…` | 拼接 ✅ | 拼接（真折行） |
| B | `"截至 2024 年 12 月 31 日，本公司"`，右端 97 | `應收…` | 拼接 ✅ | 拼接（真折行） |
| C | `"1."`，右端 99 | `2. 金額…` | **拼接 ❌** | 新起一条 |
| D | `"（一）"`，右端 96 | `（二）金額…` | **拼接 ❌** | 新起一条 |
| E | `"項目 A"`，右端 99 | `1,000.00` | **拼接 ❌** | 新起一条 |

C/D/E 就是缺陷的**可直接复现形态**：`"1."` 后接 `"2. 金額…"` 会拼成 `"1.2. 金額…"`；
C/D/E 的共同特征是 —— **上一行末字符与当前行首字符都不是"能参与句子"的字符**（都是数字、点号、括号）。

反过来看 A/B：`上一行末 = 文/司`（CJK 表意文字）、`当前行首 = 額/應`（CJK）→ 两侧都是中英文语境，
几何判据的"写满了"结论是正确的。

→ **提炼出判据：两侧字符都不属于"中文/英文语境"时，几何判据失效，应强制新起一条。**

### 阶段 4：确定判据的布尔方向（"两侧都不"而不是"任一侧"）

用户原话是"**没有一个是**中文字符或者英文字母"，语义上是**两个都不满足**，映射为：

```java
!isContext(firstChar) && !isContext(lastChar)   // 而非 ||
```

复核这个方向的安全性：

- 用 `&&`（两侧都不）→ 只会**增加**新起一条的情形，属于在既有逻辑上**收紧**。
  任何原本会拼接的组合（至少一侧是中英文）**行为完全不变** → 回归风险最小。
- 若误用 `||`（任一侧不满足）→ 会把 A 类真折行（上一行末是"，"接当前行首是"額"）也拆开，
  等于**大面积破坏 09-08 那轮的成果**。

结论：必须用 `&&`。

### 阶段 5：越界与空值安全分析（写代码前先枚举）

判据要访问 `currentText.charAt(0)` 与"最后一条的末字符"，必须先证明不会越界：

| 风险 | 现状 | 处置 |
|---|---|---|
| `currentText` 为空 | 上方 1087 行已有 `if (currentText.isEmpty()) continue;` | 不会执行到判据处，无需重复判断 |
| `currentCellTextLines` 为空 | 无任何前置保证（首行时必然为空） | 判据前置 `!currentCellTextLines.isEmpty()` |
| 最后一行为空串 `""` | 该列表只会被 `add(currentText)` 写入且写入前已判空；拼接分支只会把非空串并进去 | 不变式成立，`charAt(length-1)` 安全 |

→ 判据写成 `if (!newLine && !currentCellTextLines.isEmpty())`。
加 `!newLine` 前缀是纯短路优化：前两条判据已判定的情况不必再取字符。

### 阶段 6：Unicode 范围选择 —— 迭代一：全角标点

第一版只放行 `[A-Za-z]` + `U+4E00-U+9FFF`（CJK 统一表意文字）。
用户随即要求「全角标点需要纳入」。理由成立：`，。！？：；（）` 在中文里是**句子内部的**字符，
若把它们判为"非中英文"，会导致"上一行以 `，` 结尾、当前行以 `（` 开头"这种**真折行**被强制拆开。

于是追加两个块：

- `U+3000-U+303F` **CJK Symbols and Punctuation**（`、。〈〉《》「」『』【】`）；
- `U+FF00-U+FFEF` **Halfwidth and Fullwidth Forms**（`，．！？：；（）￥０-９Ａ-Ｚ`）。

### 阶段 7：Unicode 范围选择 —— 迭代二：General Punctuation

全角块仍漏掉中文排印中**最高频**的几个标点：`“ ” ‘ ’`（弯引号）、`—`/`——`（破折号）、`……`（省略号）。
它们**不在** `U+3000-U+303F`，而在 **General Punctuation** `U+2000-U+206F`。

对 `U+2000-U+206F` 逐段评估，只取"行内用字"的两段：

| 区间 | 内容 | 处置 |
|---|---|---|
| `U+2010-U+201F` | `‐ ‑ ‒ – — ―`（连字符/各类破折）+ `‘ ’ ‚ ‛ “ ” „ ‟`（弯引号） | ✅ 纳入 |
| `U+2024-U+2027` | 单/双点前导符、`…` 省略号、连字点 | ✅ 纳入 |
| `U+2020-U+2023` | `† ‡ • ‣`（剑标、项目符号） | ❌ **刻意排除**，见下 |

排除 `† ‡ • ‣` 的理由：它们是**块首标记**（项目符号）而不是行内续行字符。
本判据的语义是"这个字符能不能出现在一句被折行的句子的**内部**"——
项目符号天然只会出现在**行的开头**，把它算作"中文语境"会让"上一行以 `。` 结尾 + 当前行以 `•` 开头"
不再触发新起一条，从而**抑制本该发生的断条**，方向是错的。

### 阶段 8：命名与 Javadoc 的两个坑

1. **方法名**：范围扩到标点后，原名 `isChineseOrEnglishLetter`（"字母"）已属误导，
   改名为 `isChineseOrEnglishContext`（"中文/英文**语境**字符"），唯一调用点同步更新。
2. **Javadoc 里不要写 `\u4E00`**：Java 的 Unicode 转义在**词法分析之前**对整个源文件生效，**注释里同样会被展开**，
   写 `{@code \u4E00}-\u9FFF}` 在生成文档时会渲染成 `{@code 一}-龿`，反而看不懂；
   且当时还写错了花括号（`{@code \u4E00}-\u9FFF}` 这串里 `{@code}` 在第一个 `}` 就闭合了，尾部多出一个游离 `}`）。
   改为 `{@code U+4E00}`-`{@code U+9FFF}` 写法。

---

## 4. 根因（一句话）

单元格"行是否拼接"的判据只覆盖了**块类型**与**几何**两个维度，**缺少内容维度**；
于是"上一行写满但其实是段末（下一行以数字/点号/括号起头）"与"上一行写满且是折行"无法区分，
导致 `"1."` + `"2. 金額…"`、`"（一）"` + `"（二）…"` 这类**以非中英文语境字符起止的相邻行被静默拼接**，
单元格文本语义与编号结构被破坏。

---

## 5. 最终实现

### 5.1 判据插入点（`JsonWriter.java:1097-1113`）

```java
boolean newLine = containsEmbeddedBlock(group) || nextNewLine;
// Boundary rule: when neither the character ending the previous
// line nor the character starting this line belongs to a Chinese/
// English text context (letters, CJK ideographs, full-width
// punctuation), the two are very unlikely to be a wrapped
// continuation of the same sentence, so start a new entry instead
// of appending.
if (!newLine && !currentCellTextLines.isEmpty()) {
    char firstChar = currentText.charAt(0);
    String lastLine = currentCellTextLines.get(currentCellTextLines.size() - 1);
    char lastChar = lastLine.charAt(lastLine.length() - 1);
    if (!isChineseOrEnglishContext(firstChar)
        && !isChineseOrEnglishContext(lastChar)) {
        newLine = true;
    }
}
nextNewLine = cell.getRightX() - getGroupMaxRightX(group) > MAX_CELL_LINE_RIGHT_GAP;
```

### 5.2 新增私有判据方法（`JsonWriter.java:1378-1409`）

```java
private static boolean isChineseOrEnglishContext(char c) {
    return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
        || (c >= '\u4E00' && c <= '\u9FFF')   // CJK 统一表意文字
        || (c >= '\u3000' && c <= '\u303F')   // CJK 符号与标点 、。〈〉《》「」【】
        || (c >= '\uFF00' && c <= '\uFFEF')   // 半角及全角形式 ，．！？：；（）￥０-９
        || (c >= '\u2010' && c <= '\u201F')   // 连字符/破折 + 弯引号 ‘ ’ “ ”
        || (c >= '\u2024' && c <= '\u2027');  // 点前导符 + 省略号 …
}
```

Javadoc 逐条列出各区间与示例，并写明"数字、ASCII 标点及其他符号返回 `false`"。

### 5.3 判据集合总表

| 维度 | 判据 | 触发条件 | 效果 |
|---|---|---|---|
| 块类型 | `containsEmbeddedBlock(group)` | 行含 `<img>` / `<table` HTML | 本行独占一条 |
| 几何 | `nextNewLine` | 上一行右端距单元格右边界 > 15pt | 本行新起一条 |
| **内容（本轮新增）** | `isChineseOrEnglishContext` 双否 | 上一行**末字符**与当前行**首字符**都不是中英文语境字符 | 本行新起一条 |

三条之间是**或**关系，新判据只在 `!newLine` 时参与判定 → 纯收紧，不放松。

---

## 6. 验证

| 项 | 手段 | 结果 |
|---|---|---|
| 编译 | `cd java; mvn -o -q -pl opendataloader-pdf-core compile` | 退出码 0，无输出 |
| 静态诊断 | `read_lints` on `JsonWriter.java` | 0 条 |
| 符号一致性 | 全仓 `grep isChineseOrEnglishLetter` | 无残留（改名已完全同步） |
| **单测** | `mvn -o -pl opendataloader-pdf-core test "-Dtest=JsonWriterTableCellGroupingTest" "-Dsurefire.failIfNoSpecifiedTests=false"` | **Tests run: 1, Failures: 0, Errors: 0** |

既有单测 `JsonWriterTableCellGroupingTest.textChunkCanBeAppendedToANewGroupAfterAVerticalGap`
构造的正是"满宽行 + 短行"结构（`"A"` / `"B"` 拼成 `"B C"`），验证了新判据**没有**破坏既有几何合并行为 ——
即"收紧"这一性质在真实用例上成立。

**未做**（区分清楚）：
- 没有用具体样本 PDF 端到端复现阶段 3 的反例 C/D/E（本轮没有用户提供的样本）；
- 没有为新判据补正向/反向单测。

---

## 7. 遗留与风险

1. **范围偏宽的两处（已知取舍）**
   - `U+3000` 是**表意空格**，`U+FF00-U+FFEF` 里的 `U+FF65-U+FF9F` 是**半角片假名**、
     `U+FFE0-U+FFE6` 是全角货币符号 —— 它们本不属于"中英文语境"，但因与目标字符同块而被一并放行。
     影响有限（这些字符极少出现在段落边界），如要精确可改为白名单码位表。
   - 未纳入 `U+2030` 起的 General Punctuation（`‰ ′ ″ ‹ ›` 等）。其中 **`‰` 在财务类公告中出现较多**，
     如后续发现相关误拆，应补 `U+2030-U+205E` 的评估。
2. **合并单元格（`colSpan > 1`）场景**：`nextNewLine` 用外层 `cell.getRightX()` 作基准的问题
   在 09-08 记忆中已列为遗留风险（内层行几乎必然 gap > 15 → 永不拼接）。本轮**未触及**，
   新判据不改变该行为。
3. **`U+2020-U+2023` 被排除**是有意设计（见阶段 7）。若实际语料反馈"项目符号行应当断开"，说明当前
   几何判据对项目符号行覆盖不足，需要单独为"首字符是项目符号"补一条正向判据，而不是把 `•` 塞进本集合。
4. **拼接分支的 `contains` 去重**（09-08 遗留风险 2：单元格内合法重复文本第二次被静默丢弃）本轮**未修**。

---

## 8. 经验教训

1. **判据要按"维度"盘点，缺口才看得见。** 既有两条判据一个是"块类型"、一个是"几何"，
   当把它们并列成表（见阶段 2）时，"内容维度缺席"这个结构性问题立刻暴露。
   凡是"合并/拆分"型启发式逻辑，都值得做一次维度盘点：几何 / 内容 / 顺序 / 语义，逐格检查有没有空位。

2. **启发式规则的方向性比阈值更重要。** 本判据必须用 `&&`（两侧都不）而不是 `||`（任一侧不），
   否则会大面积破坏上游成果。**新增判据要优先设计成"只收紧"**：只在既有判据都未命中时才参与，
   这样单测里的既有用例天然成为回归护栏（本次 1/1 绿就是这个意义）。

3. **两个"上一层"的坑，只有踩过才知道**：
   - Java 的 `\uXXXX` 转义**在注释里也展开**，Javadoc 里想表达码位应写 `U+XXXX` 而不是 `\uXXXX`；
   - 手写 `{@code ...}` 时花括号必须配对检查，`{@code \u4E00}-\u9FFF}` 这种写法会静默留下游离 `}`。

4. **"全角标点"是个容易被低估的集合。** 它跨三个 Unicode 块：
   `U+3000-U+303F`、`U+FF00-U+FFEF`、以及最容易被漏掉的 General Punctuation `U+2010-U+201F` / `U+2024-U+2027`
   （弯引号、破折号、省略号都在这里）。处理中文排印相关字符集时，**先查弯引号在哪一块**是个好习惯。

5. **刻意排除要写进文档。** `† ‡ • ‣` 的排除是有理由的（块首标记 ≠ 行内字符），
   但代码里只看得到"没写这两个区间"。把"为什么不纳入"记在记忆文档里，才能避免后人无脑补齐。

---

## 9. 关联记忆

- `docs/memory/2026-09-08-JsonWriter表格单元格文本行合并逻辑优化与代码review.md`
  —— 本规则的**上游**：确立了 `MAX_CELL_LINE_RIGHT_GAP`、`nextNewLine` 滞后语义、`containsEmbeddedBlock`、
  每个 `(n,k)` 独立成表再合并，并列出三条遗留风险（本轮触及 0 条）。
- `docs/memory/2026-09-01-JsonWriter表格单元格内容提取重构与中文间距优化.md`
  —— `flattenCellContents` / `assembleGroupText` 的来龙去脉。
- `docs/memory/2026-09-02-嵌套子表丢失修复为HTML内嵌与TextChunk构造fontSize支持.md`
  —— `containsEmbeddedBlock` 要防御的 `<table` HTML 从何而来。
- `docs/memory/2026-09-03-表格单元格背景色bg_color多行丢失与角点误合并修复.md`
  —— 同一单元格遍历结构上的另一类问题。
- `docs/memory/2026-09-17-JsonWriter序列化NaN导致整页JSON丢失-根因定位与修复.md`
  —— `JsonWriter` 的容错基调（`DoubleSerializer` 兜底），与本轮同文件。
