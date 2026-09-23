# opendataloader-pdf 任务记忆 — 2026-09-23（同行 y 重叠片段合并：TextBlock → TextLine → 跨行续行，三层根因定位与修复）

> 样本：
> - `docs/pdf/202403201784967210628061686-6.pdf`（釋義页，跨栏定义条目）
> - `docs/pdf/202403201784967210628061686-7.pdf`（釋義页续页，"之一、"词尾续行）
> 涉及文件（唯一主改动）：`java/opendataloader-pdf-core/src/main/java/org/opendataloader/pdf/processors/ParagraphProcessor.java`
> 新增单测：`ParagraphProcessorTest`（4 个新用例，全套 6 个用例全绿）
> 状态：**改动已落地，jar 已重新打包并经真实 PDF 验证**（注意：改动尚未 git 提交）
> 本轮 = **现象复现 → 坐标系定性 → 反向 guard 根因 → 旧 jar 陷阱 → 行级合并需求 → 连续 3 个坐标/环境坑 → 合成行续行 → 列表标签误判 → 全部闭环**

---

## 1. 现象

### 问题 1（-6.pdf）：同一视觉行的两个片段没进同一个 TextBlock

釋義页的定义条目 `「《中央結算系統一般規則》」` 排版为"左栏术语 + 右栏释义"两栏结构，
同一 y 行上有两个独立 TextLine：

```
行A [79.789, 279.29, 168.252, 293.118]  "「《中 央 結 算 系 統"           ← 左栏术语
行B [212.598, 279.29, 510.239, 293.118] "規範中央結算系統使用的條款和條件（經不時修訂或修" ← 右栏释义
```

行A 与行B **y 范围完全相同**（279.29–293.118），却在输出的 JSON 中分属两个 content 条目；
下一行（y 296.289）的 `一 般 規 則》」` 与 `改），在 文 義 允 許 的 情 況 下…` 同理。

### 问题 2（-6.pdf，需求升级）：合并后 block 里仍是两行，希望合成**一个 TextLine**

初版方案只把它们并进同一个 TextBlock（保留两行、两个 content 条目），
用户期望同一视觉行直接合成**单个 TextLine**（chunks 拼接），每行只出一个 content 条目。

### 问题 3（-6.pdf）：`序 規 則》` 没跟前面合成一个段落

`…應包括《中央結算系統運作程` 的直接续行 `序 規 則》`（y 313.289，x0=212.598）独立成段。

### 问题 4（-7.pdf）：`一、曲 女 士 的 配 偶` 没跟前面合成一个段落

`「劉 先 生」…控股股東之`（y 602.283，满行）的续行 `一、曲 女 士 的 配 偶`
（y 619.283，x0=212.598，正常行距 3.171）独立成段。此处 `一、` 实为"控股股東**之一**"的词尾。

---

## 2. 结论速览（TL;DR）

1. **问题 1 的根因是一个"反向 guard"**：初版 `haveSignificantYOverlap` 里写了
   `if (aBottom < aTop || bBottom < bTop) return false;`。
   veraPDF 坐标系中 `getTopY()` 恒大于 `getBottomY()`（y-up，已用 `ShapeRecognizerTest`
   的 `topY=178.5 > bottomY=157.0` 印证），所以**正常块恒有 `aBottom < aTop`** →
   该方法永远返回 `false`，合并从未触发（与调用位置无关）。
2. **第一次"修了没生效"是环境问题**：只跑了 `mvn test`（编译 class），
   CLI 跑的是 `target` 里**修复前打包的旧 shaded jar**。必须 `mvn package` 重打包再验证。
3. **问题 2 连踩 3 个坑**：行重叠公式 `min(bottom)-max(top)` 写反；行高算成
   `rowBottom - rowTop`（负数）；`StaticContainers.getTextLineSpaceRatio()` 是 **ThreadLocal**，
   并行页处理的 worker 线程里为 null → NPE。
4. **问题 3 的根因**：行级合并后合成行的 `leftX` 是左栏起点（95.528），
   `ChunksMergeUtils.getAlignment` 只按整行 leftX/rightX 判对齐，
   右栏续行（x0=212.598）形成 117pt 的"假缩进" → 对齐类判据全部失效。
   新增 `mergeCompositeRowContinuations`：续行起点若与合成行**非首个 chunk** 的起点对齐则并入。
5. **问题 4 的根因**：`一、` 被 `BulletedParagraphUtils.isLabeledLine` 按首字符判为
   中文数字列表标记，7 处合并门卫（`isOneParagraph`、`isTwoLinesParagraph` 等）全部拒绝。
   但此处的 `一、` 是承接上一行末字"之"的词"之一"的**一半**，不是列表。
   新增包装 `isListLabeledNextLine`：前块末行以"之"结尾 + 下一行以中文数字开头 → 不视为列表标记。

---

## 3. 完整定位过程（从问题到根因）

### 3.1 问题 1：从"怀疑合并逻辑没跑到"到"反向 guard"

**第 1 步：拿几何数据定性。** 从既有输出 JSON 里读出两行 y 范围完全一致
（279.29–293.118，重叠率 1.0），x 方向却是左栏（79.789–168.252）与右栏（212.598–510.239），
中间隔着 44pt 的栏间空隙——上游 TextLine 检测按"水平间隙过大"拆成了两个 TextLine，
y 重叠却提示它们是同一视觉行。

**第 2 步：检查合并逻辑是否覆盖。** 初版在 `processParagraphs` 末尾加了
`mergeVerticallyOverlappingBlocks`（union-find 分组 + 合并 TextBlock）。
用户把它挪到方法开头（构建 blocks 之后、detect passes 之前）后反馈"没实现合并"。

**第 3 步：逐行审合并谓词，抓到反向 guard。**

```java
// 初版 haveSignificantYOverlap（已删）
double aTop = a.getBoundingBox().getTopY();
double aBottom = a.getBoundingBox().getBottomY();
...
if (aBottom < aTop || bBottom < bTop) {   // ← 这一行是反的！
    return false;
}
```

这段 guard 的本意是"排除倒置的 bbox"，但它写成 `aBottom < aTop`——
而 veraPDF 的 **正常** bbox 就是 `topY > bottomY`（y-up），所以这个条件**恒真**，
方法对任何输入都返回 `false`。合并逻辑从未生效过，与调用位置无关。

**第 4 步：修复方式。** 不再假设方向，对 top/bottom 取 `Math.min/max` 归一化成 `[low, high]`
再算 overlap，从根上消除方向假设：

```java
double aLow  = Math.min(a.getBoundingBox().getTopY(), a.getBoundingBox().getBottomY());
double aHigh = Math.max(a.getBoundingBox().getTopY(), a.getBoundingBox().getBottomY());
double overlap = Math.min(aHigh, bHigh) - Math.max(aLow, bLow);
```

**第 5 步：编译过了、测试过了，真实 PDF 还是没合并 —— 旧 jar 陷阱。**
`mvn -o -q test` 只编译 class；CLI 的 `target/opendataloader-pdf-cli-0.0.0.jar` 是修复前
打包的 shaded jar，包含的是旧字节码。重新 `mvn -o -q package -DskipTests -pl opendataloader-pdf-cli -am`
后用新 jar 验证，id6/id7 两处**块级合并**立即生效。
（教训写进了后续每次验证流程：改完代码必须 `mvn package` 再跑 CLI。）

**附带澄清**：用户问"改动没影响 contents？`getContentsWithDetectedParagraphs` 会不会又拆开？"
—— 不会。合并只改 `blocks`；回填方法按 `getFirstLine().getIndex()` 锚点匹配，
命中锚点时把**整个块**输出为一个 paragraph，被合并行的 index 不等锚点自然跳过，
不做任何"拆分"。

### 3.2 问题 2：行级合并（TextLine 级）+ 连续三个坑

**需求**：同一视觉行的 TextLine 直接合成一个 TextLine。实现要点：

- `buildBlockWithMergedRows`：组内 lines 按 topY 降序分"视觉行"（同 y 重叠的一组），
  行内按 leftX 升序，用 `TextLine.add(TextLine)` 逐个拼 chunks（bbox 并集）；
- 合并行复用最左**原始行对象**，`getIndex()` 锚点天然保留，回填机制不受影响；
- 空格插入：主流程 `isDataLoader=true`（`DocumentProcessor:1137`）时
  `TextLine.addSpaceIfRequired` 被跳过，所以手动实现 `addSpaceChunkIfRequired`
  （gap ≥ fontSize × 行距比例时插入空格 chunk）。

**坑 1：行重叠判断的 top/bottom 又写反了。**

```java
// 错：overlap = Math.min(rowBottom, lineBottom) - Math.max(rowTop, lineTop);
// 对：overlap = Math.min(rowTop,    lineTop)    - Math.max(rowBottom, lineBottom);
```

区间 `[bottom, top]` 的交集是 `min(top1,top2) - max(bottom1,bottom2)`，
写反后恒为负 → 行级合并从未触发（当时输出与块级版完全一致，靠单测才暴露）。

**坑 2：行高算成负数。**

```java
// 错：minHeight = Math.min(rowBottom - rowTop, lineBottom - lineTop);   // 负数！
// 对：minHeight = Math.min(rowTop - rowBottom, lineTop - lineBottom);
```

`minHeight > 0` 恒不成立。这个坑是靠**针对性单测**（用真实几何
79.789/168.252 与 212.598/510.239，y 279.29–293.118，断言合并后 `getLinesNumber()==1`）复现的，
期望 1 实际 2，一眼定位。

**坑 3：ThreadLocal 在 worker 线程为 null → NPE。**
单测全绿但真实 PDF 直接抛 `NullPointerException`，完整堆栈指向：

```
java.lang.NullPointerException
  ... StaticContainers.getTextLineSpaceRatio()" is null
  at ParagraphProcessor.addSpaceChunkIfRequired
```

`StaticContainers.textLineSpaceRatio` 是 **ThreadLocal\<Double\>**，只在主线程
（static 块 / `updateContainers`）初始化；`DocumentProcessor` 的并行页处理跑在
ForkJoin worker 线程上，ThreadLocal 未传播 → `getTextLineSpaceRatio()` 返回 null。
原版 `TextLine.addSpaceIfRequired` 恰好被 DataLoader 模式跳过所以从未踩到。
修复：null-safe 回退到常量 `TextChunkUtils.TEXT_LINE_SPACE_RATIO`(0.17)。
（同类问题在 `docs/memory/2026-08-12-Parallel-page-processing-failed根因与ThreadLocal传播修复.md`
已有先例，本次是同一根因的又一处表现。）

**验证结果**：id6 一个 paragraph 内两行各自为**单条 content**：

```json
"content" : [
  { "y0": 279.29,  "content": ["「《中 央 結 算 系 統     規範中央結算系統使用的條款和條件（經不時修訂或修"] },
  { "y0": 296.289, "content": ["一 般 規 則》」      改），在 文 義 允 許 的 情 況 下，應 包 括《中 央 結 算 系 統 運 作 程"] }
]
```

### 3.3 问题 3：`序 規 則》` 的"合成行假缩进"

**第 1 步：几何定性。** `序 規 則》`（x0=212.598，y 313.289）与上一行
（合成行，leftX=95.528，y 296.289–310.118）行距 3.171（正常），x 上恰好对齐
合成行**第二列**（右栏释义列）的起点。

**第 2 步：为什么对齐判据全灭。** `ChunksMergeUtils.getAlignment` 用整行
leftX/rightX/centerX 判对齐，容差 `maxFontSize × 0.2`：

- 左差 `|95.528 - 212.598| = 117.07` ≫ 容差 → isLeft = false
- 右差 `|510.233 - 258.535| = 251.7` → isRight = false
- 中心差 ≈ 65 → isCenter = false

→ alignment = null，所有 `detect*WithAlignment` 步骤集体失效。
**根因本质**：行级合并把两个不同 leftX 的行拼成一个 TextLine 后，
"行的 leftX"这个概念对合成行来说不再唯一，但下游对齐判据仍按单值使用。

**第 3 步：为什么不能简单放宽缩进容差。** `isHangingIndentContinuation` 的缩进上限
`MAX_PARAGRAPH_BEGINNING_INDENT(50)` 对 117pt 也不适用，而且放宽容差会把
真实的新段落（定义列表新条目缩进恰好也在 212.598）误并进来。
需要的是**结构信息**：续行对齐的是合成行内部的某一列起点。

**第 4 步：方案——利用 chunk 级结构。** 合成行的 chunks 天然带有各列的起点 x
（`一 般 規 則》」` 的 chunk 从 95.528 开始，空格 chunk，`改），…` 的 chunk 从 212.598 开始）。
于是 `mergeCompositeRowContinuations` 判据（全部满足才合并）：

1. 前块末行是**合成行**（`buildBlockWithMergedRows` 用 `Set<TextLine>` 收集并透传，
   不用几何推断，避免误判天然多 chunk 的普通行）；
2. 续行本身**不是**合成行（定义列表新条目必带自己的左栏术语 → 自身就是合成行 → 排除）；
3. 续行 leftX 与合成行**非首个**非空 chunk 的起点 x0 对齐（容差 `max(1, 0.5×fontSize)`；
   排除首个 chunk 是因为对齐左栏起点属于普通左对齐场景，交给既有判据）；
4. 行距正常（gap ≤ fontSize）且字号一致。

**调试插曲**：第一版判据写完忘了真正执行 `previousBlock.add(nextBlock.getLines())`，
把 nextBlock 直接丢掉了——单测（期望 3 行实际 2 行）当场抓住。
**教训：新判据的"命中动作"和"命中条件"必须一起写、一起测。**

### 3.4 问题 4：`一、` 的列表标签误判

**第 1 步：几何定性。** -7.pdf 的 `一、曲 女 士 的 配 偶`（x0=212.598）与上一行
`「劉 先 生」…控股股東之`（满行 79.789→510.236）行距 3.171，几何形态与同页
其它已正常合并的条目（id2/id4/id5/…的"满行 + 212.598 续行"）**完全相同**。
唯一差异：文本以 `一、` 开头。

**第 2 步：锁定门卫。** `BulletedParagraphUtils.isLabeledLine` 按首字符
（`POSSIBLE_LABELS` 含中文数字）判为列表标记， ParagraphProcessor 里有
**7 处** `if (isLabeledLine(next...)) return false;` 门卫
（`isOneParagraph:1113`、`isTwoLinesParagraph:962`、`isFirstLineOfParagraphWithLeftAlignment:891`、
`prejudgeParagraphs:641`、`isHangingIndentContinuation:722` 等），
任何一处命中都拒绝合并。`processOtherLines → isOneParagraph`（无对齐要求的兜底合并）
本来能处理这个几何形态，正是被标签门卫挡住。

**第 3 步：判定"一、"在这里不是列表。** 上一行末字是 `之`，`之 + 一、` = "之一"（one of），
语义上是词的接续而非编号。真正的列表项（如 -6.pdf 的定义列表新条目）不会紧跟在
"之"后面。

**第 4 步：修复——包装函数替换所有门卫。**

```java
private static boolean isListLabeledNextLine(TextBlock previousBlock, TextLine nextLine) {
    if (!BulletedParagraphUtils.isLabeledLine(nextLine)) {
        return false;
    }
    TextLine previousLine = previousBlock.getLastLine();
    if (previousLine != null) {
        String previousText = previousLine.getValue().trim();
        String nextText = nextLine.getValue().trim();
        // "之一、" / "之二、" … — 数字直接跟在"之"后面，属于上一行的词
        if (previousText.endsWith("之") && !nextText.isEmpty()
                && CHINESE_NUMERAL_CHARACTERS.indexOf(nextText.charAt(0)) >= 0) {
            return false;
        }
    }
    return true;
}
```

`CHINESE_NUMERAL_CHARACTERS = "一二三四五六七八九十"`。
7 处调用点全部改为 `isListLabeledNextLine(previousBlock, nextBlock.getFirstLine())`。

**调试插曲（测试几何自身的 bug）**：加了正例/反例单测后正例仍失败。
逐层排查发现是**合成测试数据把 baseline 写反了**：veraPDF 内部是 y-up 坐标，
下一行 baseline 必须**小于**上一行；我按 JSON 的 y-down 习惯写了 `602.283 → 619.283`，
导致 `mergeLeadingProbability` 的基线差比为 -1.619，落在
`DATA_LOADER_DEFAULT_FONT_LEADING_INTERVAL [0.7, 2.2]` 之外 → 概率 0 → `isOneParagraph` 拒并。
把 baseline 改成 `604.3 → 587.4`（差 16.9，比 1.61 落在区间内）后通过。
**教训：写 ParagraphProcessor 合成测试必须遵守 y-up，行距比要落在 [0.7, 2.2]。**

---

## 4. 根因汇总

| 问题 | 根因 | 类别 |
|---|---|---|
| 同行片段不合并（块级） | `haveSignificantYOverlap` 的 `if (aBottom < aTop)` guard 与 veraPDF y-up 坐标系相反，恒真 → 合并谓词恒 false | **代码 bug（坐标方向假设）** |
| 修了却"没生效" | 只 `mvn test` 编译，CLI 跑的是旧 shaded jar | **环境/流程** |
| 合并后仍是两行 | 需求升级：需在 TextLine 级合并，初版只做了 TextBlock 级 | 需求演进 |
| 行级合并未触发 | 行重叠交集公式 `min(bottom)-max(top)` 写反 + 行高 `rowBottom-rowTop` 为负 | **代码 bug（坐标方向假设）×2** |
| worker 线程 NPE | `StaticContainers.getTextLineSpaceRatio()` 是 ThreadLocal，ForkJoin worker 未初始化返回 null | **环境（ThreadLocal 未传播）** |
| `序 規 則》` 不并入 | 合成行的 leftX 是左栏起点，对齐判据只看整行 leftX/rightX/centerX，右栏续行 117pt"假缩进" | **设计盲区（合成行引入的新形态）** |
| `一、曲 女 士 的 配 偶` 不并入 | `isLabeledLine` 按首字符判列表标记，未考虑"之+数字"构成"之一"的词接续 | **设计盲区（语义上下文）** |

---

## 5. 修复清单（全部在 `ParagraphProcessor.java`）

| # | 改动 | 位置 | 作用 |
|---|---|---|---|
| 1 | `haveSignificantYOverlap(TextBlock, TextBlock)` 归一化 top/bottom | 合并谓词 | 修复反向 guard |
| 2 | `mergeVerticallyOverlappingBlocks(blocks, Set<TextLine> compositeRowLines)` + `buildBlockWithMergedRows(lines, collector)` | 行级合并 | 同视觉行合并为单个 TextLine；收集合成行 |
| 3 | `addSpaceChunkIfRequired`（null-safe 行距比例） | 行级合并 | DataLoader 模式下补空格 chunk；worker 线程 NPE 修复 |
| 4 | `mergeCompositeRowContinuations` + `isCompositeRowContinuation` | detect passes 之后 | 合成行的右栏续行并入前段 |
| 5 | `isListLabeledNextLine` 包装 + `CHINESE_NUMERAL_CHARACTERS` 常量 | 7 处标签门卫 | "之一、"不再误判为列表标记 |

新增单测（`ParagraphProcessorTest`）：

- `testSameRowFragmentsAreMergedIntoSingleTextLine`（问题 1+2，真实几何）
- `testCompositeRowContinuationLineMergesIntoPrecedingParagraph`（问题 3，3 行同段）
- `testZhiNumeralLabelContinuationMergesIntoPrecedingParagraph`（问题 4 正例）
- `testGenuineChineseNumeralLabelStillStartsNewParagraph`（问题 4 反例：无"之"接续时 `一、` 仍独立成段）

---

## 6. 验证

### 6.1 真实 PDF 回归

| 样本 | 结果 |
|---|---|
| `-6.pdf` | id6 为一个 paragraph 含 3 行（含 `序 規 則》`），行内容均为单条 content；下一段「全 球 發 售」（间距 20pt）未被误并；整页 14 items 结构稳定 |
| `-7.pdf` | `一、曲 女 士 的 配 偶` 并入「劉 先 生」段（id12，2 行） |

验证命令（每次改码后必须重打包再跑）：

```
mvn -o -q package -DskipTests -pl opendataloader-pdf-cli -am
java -jar opendataloader-pdf-cli\target\opendataloader-pdf-cli-0.0.0.jar -f json -q -o <输出目录> <pdf路径>
```

### 6.2 单测回归

```
ParagraphProcessorTest        6/6 绿（4 新 + 2 既有）
TextLineProcessorTest         4/4 绿
ListProcessorTest             5/5 绿
HeaderFooterProcessorTest     6/6 绿
TableBorderProcessorTest     13/13 绿
```

全量 core 套件中 `ArrowE2ETest`/各 `*IntegrationTest` 失败均为缺外部样本
（`samples/pdf/`、`opendataloader-pdf-parse` 仓库在本机不存在），
`AutoTaggerTest` 是自身未设 outputFolder 的 `DocumentProcessor:609` NPE
——均为环境性预先存在问题，与本次改动无关（已逐一核对失败原因）。

---

## 7. 经验教训

1. **写坐标相关判据，先问"这个坐标系里谁大谁小"。**
   veraPDF 是 y-up（`topY > bottomY` 恒成立），本轮三个 bug
   （反向 guard、交集公式反、行高负号）全是同一类错误的三种变形。
   统一用 `Math.min/max` 归一化成 `[low, high]` 再算，从根上消灭方向假设。

2. **"编译通过 + 单测绿"≠"真实数据生效"。**
   CLI 是 shaded jar 分发，`mvn test` 只更新 class 不更新 jar；
   且单测跑在主线程（ThreadLocal 已初始化），真实解析跑在 ForkJoin worker（未初始化）。
   **每个修复必须 `mvn package` + 真实 PDF 双重验证**；涉及 StaticContainers 的
   ThreadLocal 时要专门想 worker 线程的场景。

3. **单元测试要复刻真实几何，且坐标约定必须与内部一致。**
   合成 TextChunk 的 baseline 是 y-up：下一行更小、行距比 = |Δbaseline|/fontSize
   要落在 `[0.7, 2.2]`（DataLoader 区间），否则 `mergeLeadingProbability` 打 0 分，
   测试会因数据错误而误导排查方向（本次正例失败一度让人怀疑修复本身）。

4. **行级合并会引入"行的 leftX 不再唯一"的新形态，下游单值对齐判据会集体失明。**
   合成行的各列起点保存在 chunks 里（`getTextChunks().get(i).getLeftX()`），
   需要列对齐判断时应该用 chunk 级结构，而不是放宽整行容差
   （放宽会把真实新段落误并进来）。

5. **列表标签门卫要区分"形态"与"语义"。**
   `isLabeledLine` 只看首字符，是纯形态判断；`之一/之二` 这类词接续是语义上下文。
   用"前一行末字 + 后一行首字"的最小上下文（`之` + 中文数字）做豁免，
   并用反例单测锁定"真列表仍独立成段"，防止豁免被滥用。

6. **新判据的"命中条件"与"命中动作"必须成对出现、成对测试。**
   `isCompositeRowContinuation` 第一版只写了条件忘了 `add`，导致续行被静默丢弃，
   输出表现为"段行数少一行"——若没有断言行数的单测，这类静默丢失很难被发现。

7. **连续排障时，先把"没生效"的三种可能分开：代码没跑到 / 跑到但条件不满足 / 跑了但输出被丢弃。**
   本轮问题 1→2→3 的排查分别对应这三种：反向 guard（谓词恒 false）、
   旧 jar（代码没进运行时）、忘 add（条件命中但动作缺失）。
   定位手段依次是：读谓词数学、比对 jar 字节码（`javap -cp jar -p 类名`）、
   对比单测断言粒度（内容 → 行数）。

---

## 8. 遗留

1. **影响面需要语料回归**：行级合并 + 合成行续行 + `之一` 豁免都是**新增合并路径**，
   建议对 items 层做一次语料回归，重点看 `text` 行数减少的文档是否合理；
   尤其盯 `MIN_Y_OVERLAP_MERGE_RATIO = 0.7` 边界附近（重叠率 0.7~0.9 的错行排版）
   以及"上一行以 之 结尾的真实列表"（当前豁免可能把真列表并进上一段，属已知权衡）。
2. **`isListLabeledNextLine` 只豁免 `之`**：类似"第/其/（"等可能构成词接续的上下文
   （如"第" + "一、"?）未覆盖，遇到新样本再按最小上下文扩展。
3. **改动尚未 git 提交**：工作区含 `ParagraphProcessor.java` + `ParagraphProcessorTest.java`
   两文件改动，需按项目提交规范入库。

---

## 9. 关联记忆

- `docs/memory/2026-09-18-ParagraphProcessor悬挂缩进续行被拆成两段-根因定位与修复.md`
  —— 同为"该合没合"类问题；本轮问题 4 的几何形态（满行 + 212.598 缩进续行）与之高度相似，
  但缩进量 132.8 超出其 `MAX_PARAGRAPH_BEGINNING_INDENT(50)` 上限，走的路径不同（`isOneParagraph` 兜底）。
- `docs/memory/2026-08-12-Parallel-page-processing-failed根因与ThreadLocal传播修复.md`
  —— ThreadLocal 在 worker 线程丢失的同类根因；本次 `getTextLineSpaceRatio()` null 是又一实例。
- `docs/memory/2026-08-12-中文序号一二三、与上一个段落合并修复.md`
  —— `BulletedParagraphUtils.isLabeledLine` 的历史需求来源（中文序号行识别）；
  本次 `isListLabeledNextLine` 是在其上加语义豁免，两者需一起理解。
- `docs/memory/2026-08-18-ParagraphProcessor-TextLine-NPE修复.md`
  —— ParagraphProcessor 空值防御的历史；本轮新方法沿用同样的空值前置返回写法。
