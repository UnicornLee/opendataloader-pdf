# opendataloader-pdf 任务记忆 — 2026-09-08（`JsonWriter` 表格单元格文本行合并：`cellTextLines` 折行续接 + 块级内嵌内容隔离 + 代码 review）

## 目标（Goal）

表格单元格的文本以 `List<String>` 形式写进 JSON 的 `text` 字段。改动前，**每一个"行组"就产出一条字符串**，单元格内的折行（软换行）会被拆成多条，前端按数组逐条渲染就会把一个逻辑段落切成多行。需要改成：

1. 行组右边界离单元格右边界 **超过 15pt** → 该行自然收尾，其**后一行**独立成一条；
   否则说明是折行续接 → 拼到上一条末尾；列表为空时直接放入。
2. 但行组里若含 `ImageChunk`，或 `TextChunk` 内容含 `<table` 标签（内嵌嵌套表渲染产物），
   **必须独立成条**，绝不能拼到上一条正文后面（否则 `<img>` / `<table>` 会粘在正文尾部破坏标记）。

随后用户在我第一版实现基础上做了修正，并要求 review + 同步更新注释。

涉及文件（唯一）：

- `java/opendataloader-pdf-core/src/main/java/org/opendataloader/pdf/json/JsonWriter.java`

---

## 背景链路（改动前）

```
generateJsonPageContentData(...)                      // JsonWriter
  └─ 遍历 page contents，遇到 TableBorder
       └─ 双重循环展开 rowspan/colspan：
            for n in [cell.rowNumber, +rowSpan)
              for k in [cell.colNumber, +colSpan)
                   cellItem = tableBorder.getRows()[n].getCells()[k]
                   flattenCellContents(cellItem.getContents(), url, pageNumber)   ← 叶子化
                   cellItemContents.sort(topY desc)                               ← 自上而下
                   groupChunksByLine(cellItemContents)                            ← 纵向重叠分组成"行"
                   for group : groups
                        assembleGroupText(group, url, pageNumber)                 ← 组内横向拼接成一行文本
                        cellTextLines.add(currentText)                            ← 本次改动点
       └─ cellMap.put(JsonName.TEXT, cellTextLines)
```

关键既有语义（理解它们是本次改动的前提）：

| 方法 | 语义 |
|------|------|
| `flattenCellContents` | 把单元格内容叶子化：`CustomSemanticParagraph` / `SemanticCaption` → `TextChunk`；**1×1 嵌套表**内联展开（空表用 `\u00A0` 占位 `TextChunk`）；**多行/多列嵌套表** → `renderTableBorderToHtml()` 生成的 HTML 字符串**包成单个 `TextChunk`**；`ImageChunk` 等原样透传 |
| `groupChunksByLine` | 纵向区间重叠的连续 `TextChunk` 归为一组（即"行"）；**非 `TextChunk`（图片、子表）单独成组**，不与文本合并；每组内按 `leftX` 升序 |
| `assembleGroupText` | 组内拼接：`ImageChunk` → `<img>` 标签；`TableBorder` → 只打日志不输出；`TextChunk` → 按间隙补空格（中文相邻不补）+ 正文 |

改动前的核心三行（`JsonWriter.java:1030-1035`）：

```java
for (List<IObject> group : cellItemGroups) {
    String currentText = assembleGroupText(group, url, pageNumber);
    if (currentText.length() > 0 && !cellTextLines.contains(currentText)) {
        cellTextLines.add(currentText);
    }
}
```

---

## 完整定位过程（从问题到根因）

### 阶段 1：定位"单元格文本"唯一产出点

全仓搜索 `JsonName.TEXT` 在表格分支的写入，确认只有 `JsonWriter` 的 `cellMap.put(JsonName.TEXT, cellTextLines)` 一处（约 1071 行），`TableCellSerializer` 走的是另一套（HTML/POJO 序列化），本次不涉及。改动范围被锁定在一个 20 行左右的循环里。

### 阶段 2：确认"行组"到底代表什么

读 `groupChunksByLine`（1163-1212 行）：一个 group = **纵向重叠的一批 `TextChunk`**，即版面上的一行；组已按 `leftX` 升序排好。因此 `assembleGroupText` 的输出天然是"一行的文本"。

结论：**"一个 group 一条字符串"= 物理行 = 逻辑段落被硬拆**。要还原段落，就得判断"这一行是不是上一行的续行"。

### 阶段 3：找折行判据 —— 用右边界间隙

版面直觉：排版折行的行会**顶到单元格右边界**（间隙很小），自然收尾的行（段落末行、短行）右侧留白大。于是判据定为：

```
gap = cell.getRightX() - max(group.rightX)
gap > 15  →  该行已收尾
gap ≤ 15  →  该行是折行，下一行要续接它
```

阈值 15pt 由用户给定，抽成常量 `MAX_CELL_LINE_RIGHT_GAP`。

### 阶段 4：确认 `IObject#getRightX()` 可直接使用

`group` 的元素类型是 `IObject`，需确认接口上有 `getRightX()`。搜索命中 `XYCutPlusPlusSorter.java:461` 对 `IObject obj` 调用 `obj.getRightX()`，接口方法确认存在，无需向下转型。

### 阶段 5：识别"块级内嵌内容"（用户第 2 条要求，需先读懂 `flattenCellContents`）

用户明确要求"需要理解 `flattenCellContents` 的逻辑"。通读后得到三类产物：

1. 普通 `TextChunk`（来自段落/题注，以及 **1×1 子表被内联展开**）→ 可以正常参与行合并；
2. `ImageChunk` → `assembleGroupText` 渲染成 `<img src='...' />`；
3. **多行/多列 `TableBorder` → HTML `<table>...</table>` 字符串，被包进单个 `TextChunk`**（1093-1110 行的 `htmlChunk`）。

第 2、3 类是块级标记，拼到上一条正文末尾会得到 `"正文文字<img src=... />"` 或 `"正文文字<table>..."`——结构被破坏。所以判定条件落成：

```java
item instanceof ImageChunk
|| (item instanceof TextChunk && ((TextChunk) item).getValue().contains("<table"))
```

用 `contains("<table")` 而非 `contains("<table>")` 是为了与文件里既有写法保持一致（`renderTableCellToHtml` 1365 行用的就是 `body.indexOf("<table") < 0`），同时兼容将来可能带属性的 `<table ...>`。

### 阶段 6：第一版实现（我的）—— 存在一个真实缺陷

```java
boolean newLine = containsEmbeddedBlock(group)
    || cell.getRightX() - getGroupMaxRightX(group) > MAX_CELL_LINE_RIGHT_GAP;
if (newLine) { add }
else if (list.isEmpty()) { add }
else { append to last }
```

这里用**当前行自己的 gap** 决定**当前行**是否另起一条。反例：一个段落折成两行——line1 满宽（gap ≤ 15）、line2 短（gap > 15）。正确结果应是 line2 拼到 line1 后面形成一条，但该实现会：line1 → 列表空 → 新起一条；line2 → 自己 gap > 15 → **再新起一条**，段落被拆成两条。

### 阶段 7：用户修正 —— 改用"上一行的 gap"决定"当前行"

用户在我基础上改为：

```java
boolean nextNewLine = false;                                   // 循环外声明
for (List<IObject> group : cellItemGroups) {
    ...
    boolean newLine = containsEmbeddedBlock(group) || nextNewLine;
    nextNewLine = cell.getRightX() - getGroupMaxRightX(group) > MAX_CELL_LINE_RIGHT_GAP;  // 末尾更新，作用于下一轮
    ...
}
```

我 review 后确认这个改动**修正了阶段 6 的缺陷**：语义变成"上一行是否顶到右边 → 决定下一行是否续接"。仍用上面的反例：line1 → `nextNewLine` 初值 false → 列表空 → 新起一条；line1 满宽 → `nextNewLine = false`；line2 → `newLine = false` → **拼到 line1 末尾**。符合预期。

### 阶段 8：用户第二处修正 —— 每个 `cellItem` 独立成表再合并

```java
List<String> currentCellTextLines = new ArrayList<>();   // 每个 (n,k) 一份
...
if (!currentCellTextLines.isEmpty()) {
    for (String text : currentCellTextLines) {
        if (!cellTextLines.contains(text)) {
            cellTextLines.add(text);
        }
    }
}
```

我 review 确认这修掉了第一版的另一个隐患：`rowspan/colspan` 会遍历多个 `(n,k)`，第一版共用一个 `cellTextLines`，后一个 `cellItem` 的首行会被拼到前一个 `cellItem` 的末行后面——跨单元格拼接是错的。改成每个 `cellItem` 先独立成表、再按文本去重合并，正确。

### 阶段 9：遗留风险（已报给用户，本轮未改，等确认）

1. **合并单元格的右边界基准**：`nextNewLine` 用的是外层 `cell.getRightX()`。对 `colSpan > 1` 的合并单元格，内层每个 `cellItem` 的文本行都跟"横跨多列的外边界"比，gap 几乎必然 > 15 → 该单元格内每行都独立成条、永不拼接。若本意是按内层单元格宽度判断，应改成 `cellItem.getRightX()`。
2. **拼接分支的 `contains` 去重可能丢内容**：`else` 分支里 `if (!currentCellTextLines.contains(currentText))` 才拼接，意味着单元格内**合法重复出现**的同一行文本（例如两处"合计"）第二次会被静默丢弃（既不新增也不拼接）。旧代码的 `!contains` 只控制"是否新增"，不会造成"该拼的没拼"。
3. **空文本 group 不更新 `nextNewLine`**：`if (currentText.isEmpty()) continue;` 时直接跳过（典型是仅含 `TableBorder` 的组，`assembleGroupText` 对它不产出内容），其后文本行会沿用更早的 `nextNewLine` 值。

---

## 最终实现

主循环（`JsonWriter.java:1020-1070`）：

```java
List<String> cellTextLines = new ArrayList<>();
for (int n = cell.getRowNumber(); n < cell.getRowNumber() + cell.getRowSpan(); n++) {
    for (int k = cell.getColNumber(); k < cell.getColNumber() + cell.getColSpan(); k++) {
        if (n < tableBorder.getRows().length && k < tableBorder.getRows()[n].getCells().length) {
            List<String> currentCellTextLines = new ArrayList<>();
            TableBorderCell cellItem = tableBorder.getRows()[n].getCells()[k];
            List<IObject> cellItemContents = flattenCellContents(cellItem.getContents(), url, pageNumber);
            if (!cellItemContents.isEmpty()) {
                cellItemContents.sort(Comparator.comparingDouble(IObject::getTopY).reversed());
                List<List<IObject>> cellItemGroups = groupChunksByLine(cellItemContents);
                boolean nextNewLine = false;
                for (List<IObject> group : cellItemGroups) {
                    String currentText = assembleGroupText(group, url, pageNumber);
                    if (currentText.isEmpty()) {
                        continue;
                    }
                    // The gap is measured on the PREVIOUS line and decides whether THIS
                    // line starts a new entry: ...
                    boolean newLine = containsEmbeddedBlock(group) || nextNewLine;
                    nextNewLine = cell.getRightX() - getGroupMaxRightX(group) > MAX_CELL_LINE_RIGHT_GAP;
                    if (newLine) {
                        if (!currentCellTextLines.contains(currentText)) {
                            currentCellTextLines.add(currentText);
                        }
                    } else if (currentCellTextLines.isEmpty()) {
                        currentCellTextLines.add(currentText);
                    } else {
                        if (!currentCellTextLines.contains(currentText)) {
                            int lastIndex = currentCellTextLines.size() - 1;
                            currentCellTextLines.set(lastIndex,
                                currentCellTextLines.get(lastIndex) + currentText);
                        }
                    }
                }
            }
            // Each spanned (n, k) position yields its own line list; merge them ...
            if (!currentCellTextLines.isEmpty()) {
                for (String text : currentCellTextLines) {
                    if (!cellTextLines.contains(text)) {
                        cellTextLines.add(text);
                    }
                }
            }
        }
    }
}
cellMap.put(JsonName.TEXT, cellTextLines);
```

新增常量（1093-1100 行）：

```java
/**
 * Gap (in points) used to decide whether a text line was wrapped onto the next one.
 * Measured on a line against the cell's right edge: a line ending further than this
 * from the edge is considered complete, so the NEXT line starts a new entry in the
 * cell's text list; otherwise the next line is the wrapped continuation of that line
 * and is appended to it.
 */
private static final double MAX_CELL_LINE_RIGHT_GAP = 15;
```

新增两个辅助方法（放在 `assembleGroupText` 之后，1307-1345 行）：

```java
private static double getGroupMaxRightX(List<IObject> group) {
    double maxRightX = Double.NEGATIVE_INFINITY;   // 空组 → gap = +Infinity → 下一行必另起一条
    if (group == null) {
        return maxRightX;
    }
    for (IObject cellContent : group) {
        if (cellContent != null && cellContent.getRightX() > maxRightX) {
            maxRightX = cellContent.getRightX();
        }
    }
    return maxRightX;
}

private static boolean containsEmbeddedBlock(List<IObject> group) {
    if (group == null) {
        return false;
    }
    for (IObject cellContent : group) {
        if (cellContent instanceof ImageChunk) {
            return true;
        }
        if (cellContent instanceof TextChunk) {
            String value = ((TextChunk) cellContent).getValue();
            if (value != null && value.contains("<table")) {
                return true;
            }
        }
    }
    return false;
}
```

---

## 注释同步（用户要求部分）

用户逻辑改完后，以下注释/Javadoc 与新语义不符，已全部改写：

| 位置 | 改动前 | 改动后 |
|------|--------|--------|
| 主循环 1038-1044 | "A line that stops far from the cell's right edge starts a new entry"（描述的是**当前行**） | 明确 gap 在 **PREVIOUS line** 上测量、决定 **THIS line** 去向；上一行离右边 > 15 → 该行已收尾 → 本行新起；否则本行拼到上一行 |
| `MAX_CELL_LINE_RIGHT_GAP` Javadoc（1093-1099） | "…for the line to be treated as a wrapped continuation of the previous line" | 改为"该 gap 在某一行上测量，超过 → **NEXT line** 另起一条；否则下一行是它的续行并被拼接" |
| `getGroupMaxRightX` Javadoc（1307-1313） | "…never counts as reaching the edge" | 说明它与单元格右边界比较后决定的是 **FOLLOWING line** 的去向；空组返回 `-Infinity` → gap 为 `+Infinity` → 下一行必另起 |
| `containsEmbeddedBlock` Javadoc（1327-1333） | "must never be glued to the previous text line" | 改为"must always start its own entry"，与 `newLine` 分支语义一致 |
| 合并块 1061-1063（新增注释） | 无 | 说明每个 `(n,k)` 产出独立行列表、再按文本去重合并的原因（span 跨位置会重复同一段文本） |
| 1062 行 | `for(String text : ...)` | `for (String text : ...)`，与文件其余风格统一 |

---

## 验证

```bash
cd java/opendataloader-pdf-core
mvn -o -q -DskipTests compile
# 退出码 0，无输出（quiet 模式成功）
```

- 静态诊断：`read_lints` 对 `JsonWriter.java` 返回 0 条。
- **未跑单元测试**（本机 `mvn test` 常被环境判定为耗时较长而跳过）。改动落在表格 JSON 写出路径，
  如要补测建议沿用 `JsonWriterTableCellGroupingTest` 的反射入口，覆盖三种用例：
  ① 满宽行 + 短行 → 合并成一条；② 短行 + 短行 → 两条；③ 含 `<img>` / `<table` 的行 → 独立成条且不拼接。

---

## 经验教训

1. **"折行判据"要看前一行，不是当前行**。判断"这一行是不是新行"的依据是上一行有没有写满——
   用当前行自己的右边界会得出相反结论。这类"跨迭代状态"用循环外变量（`nextNewLine`）+ 循环末更新表达最清晰，
   但**必须在注释里点明是 PREVIOUS / NEXT**，否则后来者极易误读（本次第一版就是栽在这里）。

2. **块级内嵌内容的识别必须回到 `flattenCellContents` 的契约**。同一个"文本行"里可能混着
   `<img>` 和整段 `<table>` HTML——它们不是文本，拼接会破坏结构。凡是"把多行并成一条"的优化，
   都要先枚举数据源里所有可能的非文本产物，逐个决定是否豁免。

3. **去重语义要分清"新增去重"和"拼接去重"**。旧代码 `!contains` 只管"要不要新增一条"；
   把它照搬进"拼接"分支会引入"该拼的没拼"式静默丢数据（遗留风险 2）。两者需要分别决策。

4. **合并单元格（`rowspan/colspan`）场景下"单元格右边界"有二义性**：外层 `cell.getRightX()` 是合并后的总右边界，
   内层 `cellItem.getRightX()` 是单个物理格右边界。用错会让合并单元格里的文本永远不拼接。
   本次沿用用户选择的 `cell`，已作为风险项记录待确认。

5. **注释与实现必须同批更新**。这次用户改了实现但注释还是旧语义，review 时是靠逐句比对
   "注释描述的判定对象（当前行 vs 上一行）"才发现的——说明注释里写清"作用的主体和方向"比写清"阈值"更重要。
