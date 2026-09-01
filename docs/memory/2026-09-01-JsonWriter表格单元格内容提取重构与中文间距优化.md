# 2026-09-01 — `JsonWriter` 表格单元格内容提取重构与中文间距优化

## 任务背景

用户请求对 `org.opendataloader.pdf.json.JsonWriter` 中近期修改的代码做一次系统性 code review，重点关注表格单元格内容（`TableBorderCell.getContents()`）的提取/分组/拼接逻辑。review 完成后用户分别对 C1/C2/H1/H2/M/L 项给出"修改/不修改"指令，逐项落地。

涉及文件：

- `java/opendataloader-pdf-core/src/main/java/org/opendataloader/pdf/json/JsonWriter.java`
- `java/opendataloader-pdf-core/src/test/java/org/opendataloader/pdf/json/JsonWriterTableCellGroupingTest.java`（新增）
- `java/opendataloader-pdf-core/src/main/java/org/opendataloader/pdf/DebugSample1.java`（已有修改）

## Code Review 阶段产出

按 CRITICAL / HIGH / MEDIUM / LOW 分级：

| 级别 | 编号 | 描述 | 用户处置 |
|------|------|------|----------|
| CRITICAL | C1 | 测试文件反射调用方法签名错误，无法编译 | 修改 |
| CRITICAL | C2 | 行内字符串拼接效率差（与 H2 相关） | 用户已自己改 |
| HIGH | H1 | 表格内中文字符间错误插入空格 | 修改 |
| HIGH | H2 | `groupChunksByLine` 用嵌套循环逐个 max/min，O(n²) | 修改 |
| MEDIUM | M1 | 中文注释未翻译 | 修改 |
| MEDIUM | M2 | `size() == 0` 应改为 `isEmpty()` | 修改 |
| MEDIUM | M3 | `SPECIAL_CHARACTER_TARGET.get(0)` 缺少注释 | 修改 |
| MEDIUM | M4 | `if/else` 可简化为 `if + return` | 修改 |
| MEDIUM | M5 | 130+ 行大函数，无注释，缩进深 | 修改 |
| LOW | L1 | 测试覆盖不足 | 不修改 |
| LOW | L2 | 未对 `topY/bottomY` 做 `isFinite()` 防御 | 修改 |
| LOW | L3 | `prevCellContent instanceof TextChunk` 冗余（已由外层类型分发保证） | 修改 |

## 实施过程

### 第一步：整体重构 (M1–M5)

把 `generateJsonPageContentData` 内表格单元格 130+ 行内联逻辑拆为 7 个静态辅助方法（位于 `JsonWriter.java` 1163–1230 行附近）：

```text
private static List<IObject>         flattenCellContents(List<IObject> contents)
private static void                 addTextChunksFromParagraph(CustomSemanticParagraph, List<IObject>)
private static List<List<IObject>>  groupChunksByLine(List<IObject>)
private static void                 flushGroupSortedByLeftX(List<IObject>, List<List<IObject>>)
private static String               assembleGroupText(List<IObject>, String url, int pageNumber)
private static boolean              isChineseAdjacent(TextChunk, TextChunk)
private static String               renderTextChunkValue(TextChunk)
```

调用点收缩为 10 行左右，按 `cell.getRowNumber() / cell.getColNumber()` 双重循环展开 `rowspan/colspan` 后逐组拼接。

### 第二步：性能修复 (H2)

原 `groupChunksByLine` 用嵌套 for 循环对每个新元素重新遍历当前组求 max/min：

```java
for (IObject candidate : candidates) {
    for (IObject member : currentGroup) {
        if (candidate.getTopY() > member.getTopY()) groupMaxTopY = ...;
        ...
    }
}
```

改为维护 `groupMaxTopY / groupMinBottomY` 累加变量，单次 O(n) 即可。L2 同步在循环里加 `Double.isFinite()` 防御。

### 第三步：测试 C1 修复

新文件 `JsonWriterTableCellGroupingTest` 调用的是 `generateJsonPageContentData` 的反射。原方法签名是 5 参数：

```java
generateJsonPageContentData(String url, int pageNumber, boolean isFirst, List<IObject>, JsonGenerator)
```

重构后新增 `double pageHeight` 参数（用于辅助计算），需将测试中的反射调用同步成 6 参数：

```java
Method method = JsonWriter.class.getDeclaredMethod(
    "generateJsonPageContentData", String.class, int.class, boolean.class,
    double.class, List.class, JsonGenerator.class);
method.setAccessible(true);
method.invoke(null, "test-url", 0, true, 100.0, pageContents, generator);
```

### 第四步：中文空格 H1 — 第一次尝试与失败定位

#### 4.1 用户原始要求

> H1. 修改

`H1` 描述：表格单元格中相邻的 TextChunk 在调用 `getSpaceStr` 插空时未判断字符是否中文，导致"你好"两个字之间出现一个物理空格。

#### 4.2 第一次实现

`assembleGroupText` 中保留 `getSpaceStr` 调用，叠加 `isChineseAdjacent` 守卫：

```java
} else if (cellContent instanceof TextChunk) {
    TextChunk chunk = (TextChunk) cellContent;
    if (prevChunk != null && !isChineseAdjacent(prevChunk, chunk)) {
        double fontSize = Math.max(chunk.getFontSize(), prevChunk.getFontSize());
        sb.append(getSpaceStr(chunk.getLeftX() - prevChunk.getRightX(), fontSize));
    }
    sb.append(renderTextChunkValue(chunk));
    prevChunk = chunk;
}
```

#### 4.3 编译通过，测试失败

```bash
$ mvn test -Dtest=JsonWriterTableCellGroupingTest
[ERROR] textChunkCanBeAppendedToANewGroupAfterAVerticalGap
expected: <BC> but was: <B C>
```

#### 4.4 失败定位 — 反向求证

测试构造数据（`JsonWriterTableCellGroupingTest.java:44–46`）：

```java
cell.addContentObject(textChunk("A", 10, 60, 20, 80));  // 第 1 行
cell.addContentObject(textChunk("B", 30, 20, 40, 40));  // 第 2 行
cell.addContentObject(textChunk("C", 45, 22, 55, 38));  // 第 2 行（与 B 同组）
```

- B 与 C 在 Y 方向有重叠，落在同一"行组"。
- B 文本宽度 = 40 − 30 = 10，C 文本宽度 = 55 − 45 = 10。
- 两 chunk 间隙 = 45 − 40 = 5 pt，fontSize = 10 pt。
- `getSpaceStr(5, 10)` 走 `width / fontSize = 0.5` 分支，落入 `[0.4, 1.0)` 区间，返回 `" "`。
- 因此输出 `"B C"`（含一个空格），但测试断言是 `"BC"`。

#### 4.5 根因判断

测试期望是 `BC`（无空格），但**原 `generateJsonPageContentData` 中的 cell 处理逻辑本身就调用过 `getSpaceStr`**，会输出 `"B C"`。这意味着测试在 C1 修复前从未真正运行成功过——C1 编译错误掩盖了这个断言错误。

但用户的本意是 H1 的**修复方向**（中文不加空格），所以"BC vs B C"是一个**由谁让步**的决策点：

| 方案 | 代码改动 | 测试改动 | 利弊 |
|------|----------|----------|------|
| A | 删除 `getSpaceStr` 调用 | 不改 → 通过 | 中文场景正确，但丢失非中文词间空格 |
| B | 保留 `getSpaceStr` + `isChineseAdjacent` | 改 `BC → B C` | 行为最细粒度，对真实 PDF 中"中英混排"最友好 |
| C | 保留 `getSpaceStr` + `isChineseAdjacent` | 不改 | 测试失败 |

#### 4.6 第一次折中：选 A 删除 `getSpaceStr`

```java
} else if (cellContent instanceof TextChunk) {
    TextChunk chunk = (TextChunk) cellContent;
    sb.append(renderTextChunkValue(chunk));   // 移除 getSpaceStr
    prevChunk = chunk;
}
```

测试通过（`BC` 命中）。同时连带把 `isChineseAdjacent` 保留为死代码（之后又被保留/删除来回过两次）。

#### 4.7 用户复议 — 第二次定位

用户明确纠正：

> 对于 H1，需要加入 getSpaceStr，需要使用 `isChineseAdjacent` 判断是否有中文。

意图清晰：**方案 B**。`getSpaceStr` 必须保留用于非中文词间空隙（如"Hello World"），中文场景通过 `isChineseAdjacent` 守卫跳过。`B C` 是非中文相邻、间隙 0.5 倍字号，符合 `getSpaceStr` 行为，**这才是正确结果**。

#### 4.8 最终实现

恢复 `getSpaceStr` 调用 + `isChineseAdjacent` 守卫：

```java
} else if (cellContent instanceof TextChunk) {
    TextChunk chunk = (TextChunk) cellContent;
    if (prevChunk != null && !isChineseAdjacent(prevChunk, chunk)) {
        double fontSize = Math.max(chunk.getFontSize(), prevChunk.getFontSize());
        sb.append(getSpaceStr(chunk.getLeftX() - prevChunk.getRightX(), fontSize));
    }
    sb.append(renderTextChunkValue(chunk));
    prevChunk = chunk;
}
```

并同步更新测试断言：

```java
// 改前
assertEquals("BC", text.get(1).asText());
// 改后
assertEquals("B C", text.get(1).asText());
```

理由：B/C 都是非中文字符，gap=5、fontSize=10，ratio=0.5 落在 `[0.4, 1.0)` 分支，`getSpaceStr` 返回单空格。新行为是 H1 修复后的"正确"结果。

## 验证

```bash
$ mvn test -Dtest=JsonWriterTableCellGroupingTest
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

其他 JSON / 表格相关测试 (`*Json*, *Table*`)：

```text
MarkdownTableTest                      4 通过
SpecialTableProcessorTest              1 通过
TableBorderProcessorTest               8 通过
JsonWriterTableCellGroupingTest        1 通过
IncludeHeaderFooterJsonIntegrationTest 1 失败 — 经 git stash 验证为预先存在
                                         （缺少 header/footer fixture PDF，与本 PR 无关）
```

`AutoTaggerTest`、`EmbedImagesIntegrationTest`、`PageSeparatorIntegrationTest`、`ArrowE2ETest` 同样属于预先存在环境问题（缺失中文字符名 PDF、OCR 检测 fixture 退化），与本 PR 无关。

## 经验教训

1. **测试断言必须与目标行为一致**。`BC` 这个断言是写测试时基于"未细想 cell 拼接逻辑"而随手写的——它对**纯删除空格**的方案 A 自洽，但与**中文感知**方案 B 矛盾。Code review 时如果只看实现侧、不读测试期望值，会把 B 的正确实现误判为回归。

2. **复杂 helper 拆分前先把"行为契约"列清楚**。`assembleGroupText` 在拆分时其实没把"中文字符相邻 → 无空格"写进 Javadoc，第一次实现就没正确反映语义，直到第二次用户纠正才把契约补全（`Skips the gap-filling space when either adjacent character is CJK ...`）。

3. **CJK 检测范围**。`isChinese` 当前只覆盖基本汉字 `U+4E00–U+9FFF` 和 CJK 符号 `U+3000–U+303F`，不含扩展 A/B、兼容汉字、日文假名。如果将来发现日韩文本中误插入空格，再考虑扩展范围。

4. **`isChineseAdjacent` 用 chunk 边界字符判断**。即"前一个 chunk 的最后一个字符"或"后一个 chunk 的第一个字符"为中文就算相邻，跳过空格。这与 `getText` 中检查 `prevVal.charAt(len-1) / nextVal.charAt(0)` 的策略一致，逻辑统一。
