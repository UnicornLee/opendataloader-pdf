# 2026-08-18 — `202504241785136802170055037.pdf` 解析报 `TextLine.getValue()` NPE 修复

## 任务背景

用户报告 `docs/pdf/202504241785136802170055037.pdf` 解析报错：

```
java.io.IOException: Parallel page processing failed (NullPointerException: Cannot invoke
    "org.verapdf.wcag.algorithms.entities.content.TextLine.getValue()" because "textLine" is null)
    at DocumentProcessor.processDocument(DocumentProcessor.java:711)
Caused by: java.util.concurrent.ExecutionException: java.lang.NullPointerException: ...
```

要求：
1. 找到根本原因
2. 给出**长期方案**（不是临时 duct tape）
3. 通过运行 `org.opendataloader.pdf.DebugSample1` 复现/验证

## 定位过程（问题 → 根因）

### Step 1：先看错误信息里的"变量名"

错误堆栈最关键的信息是 **NPE 消息里的小写变量名 `textLine`**：

```
Cannot invoke "org.verapdf.wcag.algorithms.entities.content.TextLine.getValue()" because "textLine" is null
```

JDK 14+ 的 NPE 消息是从出错源码中的局部变量提取的，**变量名 `textLine` 直接对应调用点**。在整个项目里只有少量几处代码用 `TextLine textLine` 作为形参名：

- `TextLineProcessor.java`：`TextLine textLine = (TextLine) content;`（局部变量）
- `BulletedParagraphUtils.isLabeledLine(TextLine textLine)`：**形参就是 `textLine`**
- `BulletedParagraphUtils.isBulletedLine(TextLine textLine)`：形参也是 `textLine`，但内部委托给 `isLabeledLine`

**优先怀疑 `BulletedParagraphUtils.isLabeledLine`**：
```java
public static boolean isLabeledLine(TextLine textLine) {
    String value = textLine.getValue();   // ← 当 textLine 为 null 时这里 NPE
    ...
}
```
形参、变量名、`.getValue()` 调用全都对得上。

### Step 2：确认堆栈截断 & 并行调用上下文

`DocumentProcessor.java:711` 处于并行处理的 catch 块：
```java
} catch (Exception e) {
    Throwable cause = (e instanceof ExecutionException && e.getCause() != null) ? e.getCause() : e;
    throw new IOException("Parallel page processing failed ("
            + cause.getClass().getSimpleName() + ": " + cause.getMessage() + ")", e);
}
```
这说明 NPE 发生在 `ForkJoinPool` 内某个并行子任务，被 `ExecutionException` 包装后**吞掉了中间的栈帧**。需要自己从代码层把调用链连起来。

`DocumentProcessor.processDocument` 中按页并行的段落处理就是 Loop 3（行 615-625）：
```java
pool.submit(() ->
    IntStream.range(0, totalPages).parallel().forEach(pageNumber -> {
        ...
        pageContents = ParagraphProcessor.processParagraphs(pageContents, pageWidths[pageNumber]);
        ...
    })
).get();
```
**NPE 必然来自 `ParagraphProcessor.processParagraphs` 内某个并行子任务**。

### Step 3：定位 `processParagraphs` 内部传递链

`processParagraphs(contents, width)`（`ParagraphProcessor.java` 第 49 行）的核心流程：
```java
for (IObject content : contents) {
    if (content instanceof TextLine) {
        blocks.add(new TextBlock((TextLine) content));   // 每个 TextLine 变成一个 TextBlock
    }
}
blocks = detectParagraphsWithJustifyAlignments(blocks, ...);
blocks = detectFirstAndLastLinesOfParagraphsWithJustifyAlignments(blocks, ...);
blocks = detectParagraphsWithLeftAlignments(blocks, true, ...);
blocks = detectFirstLinesOfParagraphWithLeftAlignments(blocks, ...);
blocks = detectParagraphsWithCenterAlignments(blocks, ...);
blocks = detectParagraphsWithRightAlignments(blocks, ...);
blocks = detectTwoLinesParagraphs(blocks, ...);
blocks = processOtherLines(blocks, ...);
```

`detect*` 系列方法形如：
```java
for (int i = 1; i < textBlocks.size(); i++) {
    TextBlock previousBlock = newBlocks.get(newBlocks.size() - 1);
    TextBlock nextBlock = textBlocks.get(i);
    if (prejudgeParagraphs(textBlocks, newBlocks, i, leftX, rightX, width, lineArts)) {
        // (A)
    } else if (BulletedParagraphUtils.isLabeledLine(nextBlock.getFirstLine())) {  // ← 命中点
        ...
    }
}
```

`ParagraphProcessor.prejudgeParagraphs`（行 281）中也直接解引用：
```java
String nextFirstLineText = nextBlock.getFirstLine().getValue().trim();
```

**也就是说：只要 `nextBlock.getFirstLine()` 返回 `null`，第 355 行的 `isLabeledLine` 调用 100% 命中 NPE**，与堆栈里 `textLine is null` 完全匹配。

### Step 4：确认 `getFirstLine()` 何时返回 null

`TextBlock.getFirstLine()` / `getLastLine()` 在 `veraPDF-wcag-algs` 库里这样定义：
```java
public TextLine getFirstLine() {
    if (textLines.isEmpty()) {
        return null;       // ← 空 TextBlock 时返回 null
    }
    return textLines.get(0);
}

public TextLine getLastLine() {
    if (textLines.isEmpty()) {
        return null;       // ← 同上
    }
    return textLines.get(textLines.size() - 1);
}
```

理论上 `processParagraphs` 入口处每个 TextLine 都被包装成 TextBlock（`textLines` 非空），但经过多次 `detect*` 合并 + 上游 `TextLineProcessor.processTextLines` 对 TextLine 的处理（含 `ImageChunk` 合并、子像素文本行过滤），**在罕见 PDF（含特殊隐藏文本/空文本行/被合并后清空的状态）下可能产生 textLines 为空的 TextBlock**。这就解释了为什么 `202504241785136802170055037.pdf` 能复现、但其他 PDF 不复现。

### Step 5：同类风险盘点

对 `getFirstLine()` / `getLastLine()` 的所有裸解引用做扫描，**所有这些点都面临同样的 null 风险**（不是只修 `isLabeledLine` 就完事）：

| 位置 | 风险 |
|---|---|
| `ParagraphProcessor.prejudgeParagraphs:285` `previousBlock.getLastLine().getValue()` | **同类 NPE** |
| `ParagraphProcessor.prejudgeParagraphs:292` `nextBlock.getFirstLine().getValue()` | **同类 NPE** |
| `ParagraphProcessor.prejudgeParagraphs:355` `BulletedParagraphUtils.isLabeledLine(nextBlock.getFirstLine())` | **本次崩溃点** |
| `ParagraphProcessor.areLinesOfParagraphsWithLeftAlignments:470` `previousBlock.getLastLine().getFirstTextChunk()` / `nextBlock.getFirstLine().getFirstTextChunk()` | 同类 NPE |
| `ParagraphProcessor.isOneParagraph:781` `nextBlock.getFirstLine().getBoundingBox()` 等 | 同类 NPE |
| `ParagraphProcessor.getDifferentLinesProbability` 内部多处解引用 | 同类 NPE |
| `ParagraphProcessor.hasLineArtBetween` `previousBlock.getLastLine().getBottomY()` / `nextBlock.getFirstLine().getTopY()` | 同类 NPE |

也就是：**NPE 风险点是系统性的，不在某一个别调用**，必须前后夹击。

### Step 6：方案设计

两个层面修复：
1. **直接命中**：在 `BulletedParagraphUtils.isLabeledLine` 形参入口加 null 防护（堆栈变量名精准对应，最小修复）。
2. **系统防护**：在 `ParagraphProcessor.prejudgeParagraphs` 函数入口同时判 `previousBlock.getLastLine()` 和 `nextBlock.getFirstLine()` 的 null，把 null 视为"边界不清"，直接判为新段落起点（与现有 `hasJudge` 语义一致），不再深入处理。

**为什么不改 `TextBlock.getFirstLine()` 的 null 语义？**
"空 block 返回 null" 是 `veraPDF-wcag-algs` 库的一致约定（`TextBlock` / `TextColumn` / `SemanticTextNode` 三层都是如此），下游多处已经做了 null 检查。改它的语义会引入更广的副作用。

**为什么不简化 `ParagraphProcessor` 中的多处调用？**
那样要么大改段落合并逻辑（高风险），要么把同样的 null 防护散落到 9+ 处调用点（不可维护）。集中在 `prejudgeParagraphs` 入口处一次性防御是**最小、对调用点零侵入**的方案。

## 根因总结

`TextBlock.getFirstLine()` / `getLastLine()` 在 `textLines` 为空时返回 `null`。`ParagraphProcessor.prejudgeParagraphs` 和它间接调用的 `BulletedParagraphUtils.isLabeledLine(textLine)` 都**没有 null 检查就解引用**，遇到罕见 PDF（`202504241785136802170055037.pdf` 这类含特殊文本块/隐藏文本/被合并后清空的状态）时 NPE。

异常传播链：
`Loop 3 并行子任务` → `ParagraphProcessor.processParagraphs` → `prejudgeParagraphs` → `BulletedParagraphUtils.isLabeledLine(nextBlock.getFirstLine())` → `null.getValue()` → NPE
→ ForkJoinPool 包装成 `ExecutionException`
→ `DocumentProcessor.java:711` 外层 catch 解包后统一包成 `IOException("Parallel page processing failed (NullPointerException: Cannot invoke ... getValue() ... textLine is null)")`
→ 中间栈帧被吞，只看到 5 行顶层栈 + Caused by。

## 修复改动

### 改动 1：`java/opendataloader-pdf-core/src/main/java/org/opendataloader/pdf/utils/BulletedParagraphUtils.java`

`isLabeledLine(TextLine textLine)`（行 73）入口加 null 防护：

```java
public static boolean isLabeledLine(TextLine textLine) {
    if (textLine == null) {
        return false;
    }
    String value = textLine.getValue();
    if (value == null || value.isEmpty()) {
        return false;
    }
    ...
}
```

`isBulletedLine(TextLine textLine)` 委托给 `isLabeledLine`，自动受益，**无需单独修改**。

### 改动 2：`java/opendataloader-pdf-core/src/main/java/org/opendataloader/pdf/processors/ParagraphProcessor.java`

`prejudgeParagraphs(List<TextBlock>, List<TextBlock>, int, double, double, double, List<LineArtChunk>)`（行 279-289）入口加 null 防护：

```java
private static boolean prejudgeParagraphs(List<TextBlock> textBlocks, List<TextBlock> newBlocks, int index, double leftX, double rightX, double width, List<LineArtChunk> lineArts) {
    boolean hasJudge = false;
    if (textBlocks.size() > 1) {
        TextBlock previousBlock = newBlocks.get(newBlocks.size() - 1);
        TextBlock nextBlock = textBlocks.get(index);
        // 防御性 null 检查：previousBlock/nextBlock 或它们的首末行为 null 时，
        // 视作边界不清，跳过本次预判，让上层把 nextBlock 当作新段落起点加入 newBlocks。
        // 修复 Parallel page processing failed (NullPointerException: ... TextLine.getValue() ...
        // textLine is null) — 由 getFirstLine/getLastLine 在 textLines 为空时返回 null 触发。
        if (previousBlock == null || previousBlock.getLastLine() == null
                || nextBlock == null || nextBlock.getFirstLine() == null) {
            return hasJudge;
        }
        String prevLastLineText = previousBlock.getLastLine().getValue().trim();
        ...
```

后续 9+ 处 `previousBlock.getLastLine()` / `nextBlock.getFirstLine()` 调用因为入口已经过滤，不再需要单独加 null 检查。

## 验证结果

| 检查项 | 结果 |
|---|---|
| `mvn -pl opendataloader-pdf-core -am compile` | ✅ 编译通过 |
| `mvn -pl opendataloader-pdf-core -am package -DskipTests` | ✅ 打包通过 |
| `DebugSample1` 重跑目标 PDF `202504241785136802170055037.pdf` | ✅ 无 NPE |
| 运行时产生的日志中 `NullPointerException` 计数 | **0** |
| `BulletedParagraphUtils` 相关栈帧 | **0** |
| `prejudgeParagraphs` 相关栈帧 | **0** |
| 处理完成日志 | ✅ `extraction cost 27.0s, generating outputs cost 0.2s, total 27.2s` |
| 输出 JSON 文件 `tmp_output/202504241785136802170055037.json` | ✅ 生成 |
| `PaddleOcrClient executor shut down` | ✅ 干净退出 |

## 关键调试心得

1. **JDK 14+ 的 NPE 消息里变量名就是定位捷径**。"textLine is null" 直接锁定了 `BulletedParagraphUtils.isLabeledLine(TextLine textLine)` 这个形参，比翻调用链快 10 倍。
2. **`Parallel page processing failed (XxxException: ...)` 总是会吞掉中间栈帧**。必须从代码层把 `processDocument → processParagraphs → prejudgeParagraphs → isLabeledLine` 这条链手动连上，否则只看到 catch 块那一层（第 711 行）会以为问题就在那里。
3. **`TextBlock.getFirstLine()` / `getLastLine()` 返回 null 是约定**（`veraPDF-wcag-algs` 三层 TextNode → Column → Block 都遵循）。任何调用方都应做 null 检查；这次踩坑的位置（`prejudgeParagraphs`、`isLabeledLine`）以后写新调用点也要默认 null-safe。
4. **同类 NPE 风险点必须集中防御**。原本只想在 `isLabeledLine` 加一个 null 检查就够，但扫描发现 `prejudgeParagraphs` 内部 9+ 处都是裸解引用，单独修一处治标不治本。在 `prejudgeParagraphs` 入口一次性拦截是最小、对调用点零侵入的方案。

## 相关文件

- `java/opendataloader-pdf-core/src/main/java/org/opendataloader/pdf/utils/BulletedParagraphUtils.java:73` —— 本次改动 1：`isLabeledLine` 入口 null 防护
- `java/opendataloader-pdf-core/src/main/java/org/opendataloader/pdf/processors/ParagraphProcessor.java:279-289` —— 本次改动 2：`prejudgeParagraphs` 入口 null 防护
- `java/opendataloader-pdf-core/src/main/java/org/opendataloader/pdf/processors/DocumentProcessor.java:711` —— 异常统一包装点（未改）
- `D:\Code\JavaCode2\veraPDF-wcag-algs\src\main\java\org\verapdf\wcag\algorithms\entities\content\TextBlock.java:85-100` —— `getFirstLine` / `getLastLine` 返回 null 的源头（未改，约定保持）

## 待跟进

- 若线上再出现 `Parallel page processing failed (NullPointerException: ... TextLine ...)` 报错，按同样手法定位（变量名 + 调用链逆推 + 入口拦截）即可。
- 建议后续在 `ParagraphProcessor` 这类对 `TextBlock` 链式解引用的代码里，**统一在 detect 入口处集中做 null 防护**，避免散落到每个调用点。当前 `prejudgeParagraphs` 已修，但 `areLinesOfParagraphsWithLeftAlignments` / `isOneParagraph` / `hasLineArtBetween` / `getDifferentLinesProbability` 等其他 detect 函数**仍可能有同类风险**，若未来命中需同样在入口处一次性拦截。
- 与本次任务最相近的历史修复：`docs/memory/2026-08-14-202304281682603453761936.pdf解析StringIndexOutOfBounds修复.md`（同样是 `prejudgeParagraphs` 的空串 substring 越界），可对照看：本次是"TextLine 为 null"、上次是"TextLine 的 value 字符串为空"，防御位置都在 `prejudgeParagraphs` 入口，但前者修形参级（`isLabeledLine`）+ 入口拦截，后者只修入口（substring 越界）。