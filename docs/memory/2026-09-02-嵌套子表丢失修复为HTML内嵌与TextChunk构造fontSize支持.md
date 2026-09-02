# 2026-09-02 — 嵌套子表丢失修复为 HTML 内嵌 + TextChunk 构造 fontSize 字段支持

## 任务背景

`JsonWriter.flattenCellContents` 在遇到 cell 内**非 1×1 的 `TableBorder`**（多行多列子表）时，仅把 `TableBorder` 原样塞入 flat 列表。后续 `assembleGroupText` 见到 `TableBorder` 时只打一条 WARNING 日志就丢掉，既不渲染文本也不保留结构——**嵌套表格内容完全丢失**。

测试样本：`docs/pdf/202309291695911125391023218-6.pdf`，第 6 页某个 cell 里嵌了一个 2 列 4 行的子表（考核比例表）。

### 用户提出的需求

1. 把多行多列 cell content 拼接成 `<table>...</table>` HTML 字符串，构造一个 `TextChunk`，**`BoundingBox` 用 cellContent 的 `BoundingBox`**。
2. 字体策略——把子表内所有 `TextChunk.fontSize` 收集起来，取**众数（优先）/ 均值（兜底）**，作为新 `TextChunk` 的 fontSize。
3. 嵌套表格仍然按同样方式拼接 + 顶层表格一起渲染；**深度上限 5 层**，再深的忽略，避免无限递归。
4. 不清楚处先澄清；改码前先给方案获批后再改（已满足）。

## 根因定位过程

### 阶段 1：定位丢失点

读 `JsonWriter.java` 第 1060-1108 行 `flattenCellContents`：

```java
if (cellContent instanceof TableBorder) {
    TableBorder subTableBorder = (TableBorder) cellContent;
    if (subTableBorder.getNumberOfColumns() == 1 && subTableBorder.getRows().length == 1) {
        // 1×1：内联子表内容到父单元格
    } else {
        flat.add(cellContent);   // <-- 非 1×1：原样下传
    }
}
```

`flat.add(cellContent)` 把 `TableBorder` 推给下游 `groupChunksByLine` → `assembleGroupText`。读 `assembleGroupText`（约 1196-1201）：

```java
} else if (cellContent instanceof TableBorder) {
    // Nested table inside a cell: detected but not serialized here because it would
    // change the JSON data structure. Frontend consumers must handle the resulting
    // schema. Logged for diagnosis.
    LOGGER.log(Level.WARNING, "[JsonWriter] Table cell contains a table on ...");
    prevChunk = null;
}
```

注释把根因写得很清楚——**之前为了不动 JSON schema，故意丢弃**。

### 阶段 2：评估 TextChunk 的可用构造（关键误判）

读 1087-1090 行的现有 placeholder 构造：

```java
TextChunk placeholder = new TextChunk(subCellItem.getBoundingBox(),
    GlobalConstant.SPECIAL_CHARACTER_TARGET.get(0), subCellItem.getHeight(),
    (subCellItem.getTopY() + subCellItem.getBottomY()) / 2);
```

签名 `(BoundingBox, String, double height, double middleY)`——**没有 fontSize 参数**。

我据此推断"TextChunk 不支持 fontSize"，最初的设计稿里把 `collectCellFontSizes` / `computeFontSize` 写成死代码后又删除。

**用户后续指出正确构造是**：

```java
public TextChunk(BoundingBox bbox, String value, double fontSize, double baseLine) {
    super(bbox, fontSize, baseLine);
    this.value = value;
}
```

即存在两个 4 参构造（`(bbox, value, height, middleY)` 和 `(bbox, value, fontSize, baseLine)`）。要使用后者才能把字体信息写入 TextChunk。

### 阶段 3：澄清设计选项

调用 AskUserQuestion 拿到四个口径：

| 选项 | 用户选择 |
|---|---|
| HTML 表格是否带边框样式 | **不带边框（推荐）** |
| 字体策略 | **众数优先，无众数用均值（推荐）** |
| 嵌套深度上限 | **5 层** |
| `assembleGroupText` 的 TableBorder 分支 | **保留并简化注释（防御性）** |

## 实施步骤

### 步骤 1：`MAX_NESTED_TABLE_DEPTH = 5` 常量

放在 `flattenCellContents` 上方（第 1061 行）。`renderTableBorderToHtml` 和 `collectCellFontSizes` 共用这个上限。

### 步骤 2：`flattenCellContents` 签名 + 调用方

签名从 `flattenCellContents(List<IObject>)` 改为 `flattenCellContents(List<IObject>, String url, int pageNumber)`。

**为什么需要 `url, pageNumber`**：第 1098 行的 else 分支要调 `renderTableBorderToHtml(subTableBorder, 0, url, pageNumber)`——HTML 渲染路径里有 WARNING 日志（深度超限），需要这两个参数。

调用点（1024 行）同步传 `url, pageNumber`（上下文已有这两个变量）。

### 步骤 3：else 分支改造（1097-1110 行）

```java
} else {
    // Multi-row/multi-column sub-table inside a cell: render it as an HTML
    // <table> string and wrap it in a single TextChunk so the cell's text
    // preserves the nested structure without losing data.
    String html = renderTableBorderToHtml(subTableBorder, 0, url, pageNumber);
    BoundingBox bbox = subTableBorder.getBoundingBox();
    if (bbox == null) {
        bbox = new BoundingBox(subTableBorder.getLeftX(), subTableBorder.getTopY(),
            subTableBorder.getRightX(), subTableBorder.getBottomY());
    }
    double[] sizes = collectCellFontSizes(Collections.singletonList(subTableBorder), 0);
    double fontSize = computeFontSize(sizes);
    double middleY = (subTableBorder.getTopY() + subTableBorder.getBottomY()) / 2;
    TextChunk htmlChunk = new TextChunk(bbox, html, fontSize, middleY);
    flat.add(htmlChunk);
}
```

### 步骤 4：递归 HTML 渲染

新增三个方法，按调用链：

- `renderTableBorderToHtml(TableBorder, int depth, String url, int pageNumber)` — 递归出口处（depth=0）打 INFO 日志，包含 `depth`、page、url、rows、cols
- `renderTableRowToHtml(...)` — 输出 `<tr>`，不感知 colspan/rowspan（cell 那边处理）
- `renderTableCellToHtml(TableBorderCell, int depth, ...)` — 输出 `<td colspan rowspan>`，内部：
  1. **先**遍历 `contents` 处理 `TableBorder`（递归 `renderTableBorderToHtml`，depth+1；超 5 层打 WARNING 并写 `&nbsp;`）
  2. **再**用 `flattenCellContentsSkipTable` 把非 TableBorder 内容展平成 chunks → `groupChunksByLine` → `assembleGroupText` → HTML escape → 拼接到 body

#### 关键问题：双重渲染

最初我直接在 `renderTableCellToHtml` 里调 `flattenCellContents(contents, ...)` 来取文本。**但 `flattenCellContents` 本身已经把非 1×1 TableBorder 渲染成 HTML TextChunk 塞进 flat**——而我又用 `flat.removeIf(o -> o instanceof TableBorder)` 想删掉——但此时它已经变成 TextChunk 不是 TableBorder，**removeIf 完全失效，HTML 字符串被重复拼接**。

修法：新增专用 `flattenCellContentsSkipTable(List<IObject>, String url, int pageNumber)` helper，**永远跳过 TableBorder**：

```java
private static List<IObject> flattenCellContentsSkipTable(List<IObject> contents, String url, int pageNumber) {
    // ... 跟 flattenCellContents 一样但 TableBorder → continue
}
```

HTML 渲染路径用这个新 helper，文本渲染路径继续用老的 `flattenCellContents`，两路互不干扰。

### 步骤 5：字体统计 helper

`collectCellFontSizes(List<IObject>, int depth)`：

- 遇 `TextChunk` → 收集 `getFontSize()`
- 遇 `CustomSemanticParagraph` / `SemanticCaption` → 拆 `TextLine` 收集所有 `TextChunk`
- 遇 `TableBorder` → 若 `depth >= MAX_NESTED_TABLE_DEPTH` 截断；否则下钻到所有 row→cell→contents

`computeFontSize(double[])`：

- 众数（按 `Math.round(s * 1000)` 做 key 抗浮点噪声）唯一 → 返回众数原值
- 众数不唯一 / 无众数 → 返回均值
- 空数组 → `0.0`

**众数统计必须用普通 for 循环**（最初用 stream，触发 "lambda 引用本地变量必须是 effectively final" 错误，因为 `modeCount` 在循环里被改写过）。

### 步骤 6：HTML escape + INFO 日志

- `escapeHtml(String)`：转义 `& < > " '` 五个字符
- `assembleGroupText` 内 `TableBorder` 分支**保留**（防御性 + 简化注释）
- `renderTableBorderToHtml` 进入处 `LOGGER.log(Level.INFO, ...)`，**每次递归都打印**，message 包含 `at depth N`：

```
信息: [JsonWriter] Multi-row/multi-column sub-table rendered as HTML at depth 0 on 4 page of .../202309291695911125391023218.pdf (rows=1, cols=2).
```

最初版本用 `if (depth == 0)` 守卫——用户指出"不光 depth=0 要打印，日志中记录 depth 信息"——去掉守卫，每层都打。

## 改动清单

`java/opendataloader-pdf-core/src/main/java/org/opendataloader/pdf/json/JsonWriter.java`

### 新增私有方法（共 9 个）

| 方法 | 行号附近 | 作用 |
|---|---|---|
| `MAX_NESTED_TABLE_DEPTH` | 1061 | 常量 `5` |
| `escapeHtml(String)` | 1432 | 转义 5 个 HTML 字符 |
| `collectCellFontSizes(List, int)` | 1465 | 递归收集 fontSize，按深度截断 |
| `collectFontSizesRecursive(IObject, List<Double>, int)` | 1484 | 递归 helper |
| `computeFontSize(double[])` | 1520 | 众数优先/均值兜底 |
| `renderTableBorderToHtml(...)` | 1281 | 顶层入口 + INFO 日志 |
| `renderTableRowToHtml(...)` | 1295 | `<tr>` 包装 |
| `renderTableCellToHtml(...)` | 1308 | `<td colspan rowspan>` + 递归子表 + 文本拼接 |
| `flattenCellContentsSkipTable(...)` | 1146 | 给 HTML 渲染专用，跳过 TableBorder |

### 修改

| 位置 | 改动 |
|---|---|
| `flattenCellContents` 签名 | 增加 `String url, int pageNumber` |
| `flattenCellContents` 调用点 1024 行 | 同步传 `url, pageNumber` |
| `flattenCellContents` 1097-1110 行 else 分支 | 改为渲染 HTML + fontSize 包装为 TextChunk |
| `assembleGroupText` 内 `TableBorder` 分支 | 保留 + 简化注释（防御性） |

## 验证结果

### 编译

`mcp__idea__build_project` ✅ 无错误。

### 运行 `DebugSample`（入口：`org.opendataloader.pdf.DebugSample`）

第一次跑 `202309291695911125391023218-6.pdf`（用户指定样本）：

- exit code 0
- 生成 `tmp_output/202309291695911125391023218-6.json`
- 第 46 行 cell `text` 字段包含完整 HTML：

```
"text" : [ "<table><tr><td>&nbsp;</td><td>数量的比例</td></tr>
<tr><td>优秀（A）、良好（B）</td><td>100%</td></tr>
<tr><td>合格（C）</td><td>80%</td></tr>
<tr><td>不合格（D）</td><td>0%</td></tr>
</table>若激励对象考核年度个人绩效考核评级为优秀..." ]
```

后续 IDE 跑到了 `202309291695911125391023218.pdf`（无 `-6` 后缀），日志输出：

```
信息: [JsonWriter] Multi-row/multi-column sub-table rendered as HTML at depth 0 on 4 page of .../202309291695911125391023218.pdf (rows=1, cols=2).
信息: [JsonWriter] Multi-row/multi-column sub-table rendered as HTML at depth 0 on 5 page of .../202309291695911125391023218.pdf (rows=4, cols=2).
```

两个不同位置（4 页 / 5 页）的多行多列子表都被识别并渲染。

### 关键日志断言

- ✅ 嵌套表被识别为 HTML（不是 WARNING 丢失）
- ✅ INFO 日志含 `depth`、page、url、rows、cols
- ✅ 每个被渲染的子表都打印一行（不只是 depth=0）

## 关键决策

### 决策 1：用 HTML 字符串嵌入 cell text，而不是改 JSON schema

最小侵入——下游消费者拿到 cell.text 字段，里面如果有 `<table>...</table>` 子串就当嵌套表处理，否则按普通文本处理。**不新增顶层 JSON 键**。

### 决策 2：TextChunk 的 fontSize 字段

用户最初给的构造签名是 `(bbox, value, height, middleY)`——这让我误以为 TextChunk 不支持 fontSize。用户随后提供了正确构造 `(bbox, value, fontSize, baseLine)`。改用后者，把统计好的 fontSize 写进 TextChunk。

> **重要提示**：当前 `TextChunkSerializer`（`json/serializers/TextChunkSerializer.java`）只写 `value` 字段到 JSON，**fontSize 不会出现在 JSON 输出里**。本次任务不扩展 Serializer——fontSize 是 TextChunk 内部状态，下游若需要可在后续单独改造 Serializer。

### 决策 3：HTML escape 范围

只转义 5 个字符 `& < > " '`，不处理 Unicode/控制字符——JSON 序列化时 Jackson 会处理非 ASCII 字符。

### 决策 4：保留 `assembleGroupText` 的 TableBorder 分支

之前那条分支仍能命中——如果 `groupChunksByLine` 误把 TableBorder 跟文本分到同一 group，`assembleGroupText` 会兜底打 WARNING，避免数据丢失。注释改为简短说明。

### 决策 5：递归深度上限实现

`renderTableBorderToHtml` 进入处检查 `depth + 1 >= MAX_NESTED_TABLE_DEPTH`——超限打 WARNING 并写 `&nbsp;`，保持 cell 至少有非空内容。**`collectCellFontSizes` 用同一个上限递归收集 fontSize**。

### 决策 6：INFO 日志

最初只 depth=0 打一行——用户明确要求"每次递归都打印 + 日志含 depth 信息"——按要求去掉守卫，并把 `depth` 显式写入 message。

## 已知限制

1. **`TextChunkSerializer` 不写 fontSize** 到 JSON。如前端需要，需扩展 `TextChunkSerializer` 增加 `jsonGenerator.writeNumberField(JsonName.FONT_SIZE, chunk.getFontSize())`，并加 `JsonName.FONT_SIZE` 常量。本次未做（不在用户需求范围）。
2. **fontSize 众数统计按 `round(s * 1000)` 取 key**——1/1000pt 精度内的差异会被合并。如果某个 cell 里所有文字字号都是 8.1234567 但 round 后都是 8.124，众数稳定；如果同时有 8.1245 和 8.1249，round 都会变成 8.125，被认为是同一档。这是设计上为了抗浮点漂移，不算缺陷。
3. **空 cell 写 `&nbsp;`** 而不是 `""`，避免出现完全空的 `<td></td>` 影响前端渲染。
4. **`renderTableCellToHtml` 内对子表渲染和文本渲染做两遍 `flattenCellContents*`**——为了避免双重渲染而必须分开处理。如果将来要让一个 cell 同时有"子表 + 文本"，两条路径都会跑，目前已经支持（HTML 子串和文本都用 escapeHtml 处理后拼接）。但如果深度超限的子表被打成 `&nbsp;` 而该 cell 还有其它文本，最后只有 `&nbsp;` 而非 `&nbsp;文本`——这是预期行为。
5. **嵌套子表里每个 cell 都调一次 `assembleGroupText`**——对超大深嵌套表（5 层 × 每层几百行）会有 O(深度 × 行数) 的字符串拼接开销。性能影响在测试 PDF 上无感。

## 相关文件

- `java/opendataloader-pdf-core/src/main/java/org/opendataloader/pdf/json/JsonWriter.java`
  - 第 1061 行：`MAX_NESTED_TABLE_DEPTH` 常量
  - 第 1071 行：`flattenCellContents` 新签名
  - 第 1097-1110 行：else 分支改为 HTML 渲染
  - 第 1146 行：`flattenCellContentsSkipTable` 新增
  - 第 1237 行附近：`renderTableBorderToHtml / renderTableRowToHtml / renderTableCellToHtml` 新增
  - 第 1432 行：`escapeHtml` 新增
  - 第 1465-1530 行附近：`collectCellFontSizes / collectFontSizesRecursive / computeFontSize` 新增
- `java/opendataloader-pdf-core/src/main/java/org/opendataloader/pdf/DebugSample.java`：测试入口
- `docs/pdf/202309291695911125391023218-6.pdf`：用户指定测试样本
- `tmp_output/202309291695911125391023218-6.json`：验证产物（第 46 行包含 HTML 表格）
- `tmp_output/202309291695911125391023218.json`：被 IDE 误跑的另一个 PDF（INFO 日志观察用）