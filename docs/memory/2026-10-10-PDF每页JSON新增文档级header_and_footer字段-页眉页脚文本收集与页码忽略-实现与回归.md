# 2026-10-10 — PDF 解析 JSON 新增文档级 `header_and_footer` 字段（页眉页脚文本收集 + 页码忽略）

> 任务：在每页 JSON 序列化链路中收集页眉/页脚文本，输出一个 map 字段 `header_and_footer`（含 `header`/`footer` 两个 key）。
> 需求经三次演进：① 最初"每页一个"→ ② 页码识别规则按真实语料两轮扩展 → ③ 最终改为"整个文件一个、与 `data` 同级"。
> 回归样本（`docs/pdf/` 选 5 份）：`201501131782598903817032205.pdf`、`20260507AN202606291826520711.pdf`、`202302281677505819604328.pdf`、`202609301790668003861064835.pdf`、`200910301782365038553054634.pdf`
> 涉及文件（生产改动仅 2 个）：
>
> - `java/.../pdf/json/JsonName.java`（新增 3 个字段常量）
> - `java/.../pdf/json/JsonWriter.java`（新增 4 个 import + 6 个方法/常量 + 1 处顶层调用）
>
> 核心类 `SemanticHeaderOrFooter` / `TextLine` / `SemanticTextNode` / `TextColumn` / `TextBlock` / `PDFList` / `ListItem` 均在依赖库 **veraPDF-wcag-algs**（本工作区非源码，只读）。
> 状态：**已实现并通过 5 份真实 PDF 全文回归（`ISSUES FOUND: False`），临时文件已清理，未 git 提交**。

---

## 1. 原始需求（用户 5 点）

1. 保存每页数据到 JSON 时收集页眉、页脚内容（**只收集文本**），字段名 `header_and_footer`，是一个 map，含 `header` 和 `footer` 两个 key。
2. 若每页**第一个元素**是 `SemanticHeaderOrFooter` → 视为页眉：以每个 `TextLine` 为一条收集字符串到 list；已存在则跳过（判存在时忽略空格）；所有字符总长 ≤ **500**；末了非空则作为 `header` 值，否则不保存。
3. 若每页**最后一个元素**是 `SemanticHeaderOrFooter` → 视为页脚：**页码信息忽略**；其余按 TextLine 逐条收集；去空格去重；总长 ≤ **400**；非空则作 `footer`，否则不保存。
4. 有不清楚处需向用户澄清。
5. 从 `docs/pdf` 选 5 份 PDF 做回归。

### 澄清结论（AskUserQuestion，用户拍板）
| 议题 | 用户选择 |
|---|---|
| 页码识别 | **宽松**：数字 + 常见修饰 |
| TextLine 提取 | **递归提取所有**（不止直接 children） |
| 长度计数 | **原始长度**（含空格） |
| 是否受 `includeHeaderFooter` 开关约束 | **始终独立收集**（不 gate） |

---

## 2. 结论速览（TL;DR）

1. **"第一个=页眉、最后一个=页脚"的判定不是约定，而是排序保证**：`writePageToGenerator` 对 `pageContents` 按 `getTopY()` 升序排序后 `Collections.reverse`（即 topY 从大到小 = 从上到下），所以首元素是最靠顶的块、末元素是最靠底的块。收集逻辑必须复用同一套排序才能对齐"首/尾"语义。
2. **`SemanticHeaderOrFooter.getContents()` 是多态的，必须递归**：tagged 文档里 contents 是原始 `TextLine`；untagged 文档里会被 `HeaderFooterProcessor` 经 ParagraphProcessor/ListProcessor/Heading/Caption 包成语义节点（`SemanticTextNode`/`TextBlock`/`PDFList`）。`IObject` 不暴露通用 children/contents，所以递归要**按具体类型分派**取行，无法用统一 API。
3. **页码识别是"跑出来的"不是"想出来的"**：初版只覆盖纯数字 + 简单修饰，第一次回归即漏掉 `1-1-1`（招股书章节页码）、`–i–`（装饰罗马）；第二次又漏 `–I-1–`（罗马+阿拉伯混合章节标签）。最终合并为"装饰符包裹的数字/罗马/字母数字序列"一条通用规则。
4. **需求从"每页一个"改为"整个文件一个"是结构性变更，不是简单搬调用点**：需要在 `writeToCustomJson` 里遍历**所有页**聚合出两个文档级去重列表（共享 `seen` 集合 + `int[]` 长度累加器），在 `data` 数组写完之后、顶层 `writeEndObject` 之前写出，并用 try/catch 包裹以防聚合异常破坏整棵 JSON 树。
5. **复用既有 `getText(List<TextChunk>)`**（JsonWriter 内的精炼文本提取）而非 `TextLine.getValue()`，与其它 JSON 字段的文本口径保持一致。

---

## 3. 完整定位过程（从需求到落点）

### 3.1 找"每页 JSON 是在哪写的"
`SearchSymbol` 找不到 `SemanticHeaderOrFooter`——确认它在依赖库 veraPDF-wcag-algs，非本仓源码。
读 `JsonWriter.java` 定位到 [writePageToGenerator](../../java/opendataloader-pdf-core/src/main/java/org/opendataloader/pdf/json/JsonWriter.java)（每页对象序列化入口），属于 `writeToCustomJson`（自定义 JSON：含 `data` 数组 / `page_index` / `items` / `header_pos` / `footer_pos`）路径。

关键排序证据：
```java
List<IObject> pageContents = contents.get(pageNumber);
pageContents.sort(Comparator.comparingDouble(item -> item.getTopY()));
Collections.reverse(pageContents);   // → topY 降序 = 视觉从上到下
```
→ **首元素=页眉、末元素=页脚**由此确立。

### 3.2 确认字段常量命名风格
读 `JsonName.java` 全文，已有 `HEADER_POS="header_pos"`、`FOOTER_POS="footer_pos"`，新增常量沿用同风格。

### 3.3 摸清 contents 的多态结构（决定递归方案）
读 `HeaderFooterProcessor.java`：
- `!isTagged`：header/footer 的 contents 会被 ParagraphProcessor/ListProcessor/Heading/Caption 加工成语义节点；
- `isTagged`：contents 保留原始 `TextLine`。

读依赖库类型层次确定"取行"路径：
| 类型 | 取 TextLine 的方式 |
|---|---|
| `TextLine` | 自身即一行 |
| `SemanticTextNode` | `getColumns()` → 每个 `TextColumn.getLines()` |
| `TextBlock`（`ListItem extends TextBlock`） | `getLines()` |
| `PDFList` | `getListItems()` → 每个 `ListItem`（递归） |
| `SemanticHeaderOrFooter` | `getContents()` → 每个 `IObject`（递归） |

`HtmlGenerator.writeHeaderOrFooter` 也遍历 `getContents()`，佐证"递归提取所有 TextLine"是正确处理。

### 3.4 落地逐页版（初版实现）
新增 `writeHeaderAndFooter(pageGenerator, pageContents)`，在 `writePageToGenerator` 的 `items` 数组写完、`writeEndObject` 前调用；配套 `collectTextLines`（递归）、`collectHeaderOrFooterLines`（去重 + 长度上限 + 页码忽略）、`isPageNumber`、`writeStringArrayField`。`GetProblems` 无错。

### 3.5 编译/运行环境的坑（Windows PowerShell）
| 现象 | 处理 |
|---|---|
| `mvn` 报"不支持发行版本 17" | 设 `$env:JAVA_HOME='D:\Applications\Java\jdk-17.0.0.1'`，`$env:Path = $env:JAVA_HOME + '\bin;' + $env:Path`（用 `+` 拼接，避免 `/bin` 被误解析） |
| `-Dmdep.outputFile=cp.txt` 被 harness 剥引号、在 `.` 处断裂 | 单引号包裹整个参数：`'-Dmdep.outputFile=cp_test.txt'` |
| 离线跑真实 PDF | `mvn dependency:build-classpath` 导出 `cp_test.txt`，`java -cp (cp + target\classes)` 跑临时 runner |

### 3.6 页码规则两轮扩展（回归驱动）
**第一轮**：分析脚本发现页脚 `1-1-1`（`20230228`）、`–i–` 未被识别为页码。据"宽松"选择扩展 `PAGE_NUMBER_PATTERN`，加入"数字序列 `\d+(?:[./-]\d+)*`"与"装饰罗马 `[-_.·–—]+[ivxlcdm]+[-_.·–—]+`"。重跑 `20230228` 的 `1-1-1`、`20091030` 的 `–i–` 均正确忽略；纯页码页整个字段省略。

**第二轮**：`20091030` p139 起页脚 `–I-1–`（罗马 I + 阿拉伯 1 混合的"章节-页码"标签）仍被保留。把"装饰数字"与"装饰罗马"两条**合并为一条通用规则**：
```
[-_.·–—]+[0-9ivxlcdm]+(?:[./-][0-9ivxlcdm]+)*[-_.·–—]+
```
重跑后 `–I-1–`/`–II–`/`–45–` 类被忽略；`20091030` 逐页有字段的页数从 399 降到个位数。而 `–EGM-1–`（含非罗马字母 E/G/M 的附录章节标签）**不**被误判，仍保留——符合预期。

### 3.7 需求变更：每页一个 → 整个文件一个（与 `data` 同级）
用户明确要求改为文档级单字段。改造步骤：
1. **移除** `writePageToGenerator` 里的逐页 `writeHeaderAndFooter` 调用。
2. **重写**为 `writeHeaderAndFooterField(jsonGenerator, contents, numberOfPages)`：遍历所有页，逐页排序取首/尾，用 `collectHeaderOrFooterLines` 的累加版 `appendHeaderOrFooterLines`（共享 `headerSeen`/`footerSeen` 去重集合、`headerLength`/`footerLength` 的 `int[]` 累加器）聚合两个文档级列表；两列表皆空则整体省略字段。
3. **调用点搬到大循环之外**：在 `writeToCustomJson` 中 `data` 数组 `writeEndArray` + `finally` 之后、顶层 `jsonGenerator.writeEndObject()` 之前，用 try/catch 包裹调用，确保聚合异常不污染 JSON 结构（对齐逐页 fallback 的健壮性设计）。

> 注意：`writeHeaderAndFooterField` 会对 `contents.get(pageNumber)` **原地再排一次序**；`writePageToGenerator` 之前也排过，排序幂等，不冲突。

---

## 4. 最终实现要点

### 4.1 `JsonName.java` 新增
```java
public static final String HEADER_AND_FOOTER = "header_and_footer";
public static final String HEADER_AND_FOOTER_HEADER = "header";
public static final String HEADER_AND_FOOTER_FOOTER = "footer";
```

### 4.2 `JsonWriter.java` 新增 import
```java
import org.verapdf.wcag.algorithms.entities.SemanticTextNode;
import org.verapdf.wcag.algorithms.entities.content.TextColumn;
import org.verapdf.wcag.algorithms.entities.content.TextBlock;
import org.verapdf.wcag.algorithms.entities.lists.ListItem;
```

### 4.3 常量与页码正则（最终扩展版）
```java
private static final int HEADER_MAX_TOTAL_LENGTH = 500;
private static final int FOOTER_MAX_TOTAL_LENGTH = 400;
// 源码中 unicode 用 \u00b7 \u2013 \u2014 \u7b2c \u9875 \u9805 \u5171 \u2116 转义书写
private static final Pattern PAGE_NUMBER_PATTERN = Pattern.compile(
        "^(?:\\d+(?:[./-]\\d+)*|"
        + "[-_.\u00b7\u2013\u2014]+[0-9ivxlcdm]+(?:[./-][0-9ivxlcdm]+)*[-_.\u00b7\u2013\u2014]+|"
        + "\u7b2c\\d+[\u9875\u9805](?:\u5171\\d+[\u9875\u9805])?|"
        + "(?:page|no|\u2116)\\d+)$",
        Pattern.CASE_INSENSITIVE);
```

### 4.4 文档级聚合方法
```java
private static void writeHeaderAndFooterField(JsonGenerator jsonGenerator, List<List<IObject>> contents,
                                              int numberOfPages) throws IOException {
    List<String> header = new ArrayList<>();
    List<String> footer = new ArrayList<>();
    Set<String> headerSeen = new HashSet<>();
    Set<String> footerSeen = new HashSet<>();
    int[] headerLength = {0};
    int[] footerLength = {0};
    for (int pageNumber = 0; pageNumber < numberOfPages && pageNumber < contents.size(); pageNumber++) {
        List<IObject> pageContents = contents.get(pageNumber);
        if (pageContents == null || pageContents.isEmpty()) continue;
        pageContents.sort(Comparator.comparingDouble(IObject::getTopY));
        Collections.reverse(pageContents);
        int size = pageContents.size();
        IObject first = pageContents.get(0);
        if (first instanceof SemanticHeaderOrFooter)
            appendHeaderOrFooterLines((SemanticHeaderOrFooter) first, header, headerSeen, headerLength, HEADER_MAX_TOTAL_LENGTH, false);
        if (size > 1) {
            IObject last = pageContents.get(size - 1);
            if (last instanceof SemanticHeaderOrFooter)
                appendHeaderOrFooterLines((SemanticHeaderOrFooter) last, footer, footerSeen, footerLength, FOOTER_MAX_TOTAL_LENGTH, true);
        }
    }
    if (header.isEmpty() && footer.isEmpty()) return;
    jsonGenerator.writeObjectFieldStart(JsonName.HEADER_AND_FOOTER);
    if (!header.isEmpty()) writeStringArrayField(jsonGenerator, JsonName.HEADER_AND_FOOTER_HEADER, header);
    if (!footer.isEmpty()) writeStringArrayField(jsonGenerator, JsonName.HEADER_AND_FOOTER_FOOTER, footer);
    jsonGenerator.writeEndObject();
}
```

### 4.5 递归取行 + 累加去重
```java
private static void collectTextLines(IObject obj, List<TextLine> sink) {
    if (obj instanceof TextLine) sink.add((TextLine) obj);
    else if (obj instanceof SemanticTextNode)
        for (TextColumn c : ((SemanticTextNode) obj).getColumns()) sink.addAll(c.getLines());
    else if (obj instanceof TextBlock) sink.addAll(((TextBlock) obj).getLines());
    else if (obj instanceof SemanticHeaderOrFooter)
        for (IObject content : ((SemanticHeaderOrFooter) obj).getContents()) collectTextLines(content, sink);
    else if (obj instanceof PDFList)
        for (ListItem item : ((PDFList) obj).getListItems()) collectTextLines(item, sink);
}

private static void appendHeaderOrFooterLines(SemanticHeaderOrFooter hf, List<String> result,
        Set<String> seen, int[] totalLength, int maxTotalLength, boolean ignorePageNumber) {
    List<TextLine> lines = new ArrayList<>();
    collectTextLines(hf, lines);
    for (TextLine line : lines) {
        String text = getText(line.getTextChunks());
        if (text == null || text.trim().isEmpty()) continue;
        if (ignorePageNumber && isPageNumber(text)) continue;
        String dedupKey = text.replaceAll("\\s+", "");
        if (dedupKey.isEmpty() || !seen.add(dedupKey)) continue;
        if (totalLength[0] + text.length() > maxTotalLength) return;  // 累计超上限即停止追加
        result.add(text);
        totalLength[0] += text.length();
    }
}

private static boolean isPageNumber(String text) {
    return PAGE_NUMBER_PATTERN.matcher(text.replaceAll("\\s+", "")).matches();  // 去空格后匹配
}
```

### 4.6 顶层调用点（`writeToCustomJson`）
```java
// ... data 数组 writeEndArray() + finally(SerializerUtil.clearElementMetadata()) 之后 ...
try {
    writeHeaderAndFooterField(jsonGenerator, contents,
        StaticContainers.getDocument().getNumberOfPages());
} catch (Exception hfEx) {
    LOGGER.log(Level.WARNING,
        inputPdfName + " - Error when generating header_and_footer: "
            + hfEx.getClass().getSimpleName() + ": " + hfEx.getMessage(), hfEx);
}
jsonGenerator.writeEndObject();
```

---

## 5. 回归验证（5 份真实 PDF，`OpenDataLoaderPDF.processFile` 离线跑）

自动校验脚本检查：① 无任何页对象残留 `header_and_footer`；② 每份文件仅一个顶层字段且与 `data` 同级、key 只含 header/footer；③ header 总长≤500、footer≤400；④ 去空格去重、无空白条目；⑤ 页脚无纯页码。

| 文件 | data 页数 | 顶层 header | 顶层 footer |
|---|---|---|---|
| 20150113… | 193 | 2 条（草擬本警告，分两行） | 12 条（`–S-1–`… 章节页脚，非页码保留） |
| 20260507AN… | 423 | 1 条（草擬本警告） | 0 |
| 20230228… | 765 | 1 条（公司名 + 招股意向书） | 0（`1-1-1` 类页脚已忽略） |
| 20260930… | 2 | 1 条（证券代码：600420 … 公告编号：2026-063） | 0 |
| 20091030… | 537 | 0 | 8 条（`–EGM-1–`… 附录标签） |

**最终 `ISSUES FOUND: False`。**

---

## 6. 走过的弯路 / 关键权衡

| 尝试/决策 | 结果/理由 |
|---|---|
| 初版逐页调用点写在 `writePageToGenerator` 内 | 后被需求变更推翻，改到 `writeToCustomJson` 大循环之外做文档级聚合 |
| 页码规则一次到位的幻想 | 两轮真实回归才补齐：`1-1-1`、`–i–`、`–I-1–`。教训：宽松规格必须靠语料迭代收敛，不能纯推理 |
| 用 `TextLine.getValue()` vs `getText(getTextChunks())` | 选后者，复用 JsonWriter 既有精炼提取，与其它字段口径一致 |
| 长度上限超限的处理 | "追加会使累计超上限即 `return` 停止追加"，不截断单条，保证条目完整性 |
| 文档级聚合是否 gate 在 `includeHeaderFooter` | 用户明确"始终独立收集"，不受该开关约束 |
| 两列表皆空 | 整个 `header_and_footer` 字段省略（不写空 map） |
| 临时调试入口 | 用独立 `HfRegressionRunner` 而非改 `DebugSample`；跑完删除。收尾 `git checkout` 还原了 `DebugSample.java` 的临时路径改动 |

---

## 7. 遗留 / 可继续优化

1. **章节标签 vs 页码的边界**：`–S-1–`、`–EGM-1–` 这类含非罗马字母的"章节-页码"标签当前**保留**（判定为页脚附加信息，非纯页码）。若业务希望一并忽略，需引入"字母前缀 + 数字"的更宽分支，但要防止误吞真实页脚说明文字。
2. **`PAGE_NUMBER_PATTERN` 的 `[0-9ivxlcdm]` 罗马字符集**：只覆盖小写罗马字母（配 `CASE_INSENSITIVE` 等效覆盖大写），未含 `s`（如某些页码用 `S` 表 section）——`–S-1–` 正因 `S` 不在集合内而保留，与"章节标签保留"目标巧合一致。
3. **改动尚未 git 提交**：工作区当前仅 `JsonName.java` + `JsonWriter.java` 两处正式改动，需按项目提交规范入库。
4. **长度上限（500/400）与"宽松页码"是样本驱动经验值**：跨更杂语料（超长页眉、多段页脚）可能需再调。

---

## 8. 经验教训

1. **"首元素=页眉/末元素=页脚"依赖上游排序，务必复用同一排序**：`writePageToGenerator` 的 `sort(topY) + reverse` 是这套语义的地基，任何脱离它的"取首尾"都可能选错块。
2. **依赖库对象多态时，"递归提取"必须按具体类型分派**：`IObject` 无通用 children，tagged/untagged 两条加工链产出不同结构（原始 `TextLine` vs 语义节点树），只有覆盖全部具体类型才能不漏。
3. **识别类规则（页码）要"跑语料"而非"拍脑袋"**：宽松规格下，数字序列、装饰罗马、字母数字混合三类都是回归里一版版补出来的；一次性写死大概率漏。
4. **字段从逐页升到文档级是"聚合 + 位置"双重改造**：不只是把方法挪个地方——要把逐页的局部 list/seen/length 提升为跨页共享累加器，并把写出点移到 `data` 数组之外、顶层对象闭合之前，同时用 try/catch 隔离以免破坏整棵树。
5. **收尾纪律**：临时 runner/脚本/classpath/输出目录全部删除，`DebugSample` 的调试路径改动 `git checkout` 还原，`git status` 只留正式改动文件——保证交付面干净可审。

---

## 9. 关联记忆

- `docs/memory/2026-09-01-JsonWriter表格单元格内容提取重构与中文间距优化.md` —— `getText(List<TextChunk>)` 精炼文本提取与 `JsonWriter` 逐页写出结构的来源。
- 本页眉页脚"首/尾元素"判定与 `header_pos`/`footer_pos`（`HEADER_POS`/`FOOTER_POS` 字段）同源，都建立在 `writePageToGenerator` 的 topY 排序之上。
