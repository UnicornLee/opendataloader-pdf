# logo 矢量图形区域截图缺失 —— GraphicRegionProcessor 新增与根因定位

> 样本：`docs/pdf/202609021788354354805052601-1.pdf`（用户指定，1 页港股公告）
> 参照文档：`docs/memory/2026-09-22-柱状图截图吞并正文并重复-根因定位.md`、
> `docs/memory/2026-08-20-每页公式检测have_formula字段与OCR截图.md`、
> `docs/memory/2026-09-15-流程图识别三问题-根因定位与修复.md`

## 一、用户诉求

页面中部的矢量图形区域（实测为**中国大冶 logo 锁定区**：红色风车标记 + 中文名 + 英文名）
既没有被识别成表格，也没有被任何图表/流程图处理器截图，最终在 JSON 中**完全消失**。
要求定位原因并给出方案，确认后才允许改代码。

用户决策（2026-10-08）：

| 决策点 | 选择 |
|---|---|
| 截图范围 | 整块 logo 锁定区（用 `LineArtChunk` 的 bbox） |
| 改动范围 | 只改 `opendataloader-pdf`，不动 veraPDF 上游 |
| 开关 | 不加开关，无条件执行（与 chart / flowchart 一致） |
| 验证规模 | 目标样本 + 少量高风险对照样本 |

## 二、现象与定位过程

### 2.1 先排除「样本选错」

`tmp_output/` 下已有的 `202609021788354354805052601.json` 是**另一份 PDF**（无 `-1` 后缀，8 页）的产物，
全篇只有 `text` item、`_images` 目录为空，容易误判为「已经查过」。
用 `TmpRenderPages` 渲染确认 `-1.pdf` **只有 1 页**，图形区域就在这一页顶部（距顶约 150–200pt）。

### 2.2 端到端现象（跑 `DebugSample1`）

```
page=1 items=11 -> textx11        （无 image）
_images 目录：空
JSON 中检索 "中國大冶" / "China Daye"：均为 False
```

即这块内容**在产物里既不是图也不是文字**，彻底消失。

### 2.3 chunk 构成（`TmpArtifactDump`，走真实 `DocumentProcessor#preprocessing`）

| 类型 | 数量 | 覆盖范围（PDF 用户空间，y 向上） |
|---|---|---|
| `LineArtChunk` | 1（`innerLineChunks=0`） | **[97.0, 643.7, 498.4, 691.2]** ← 唯一覆盖红色标记的框 |
| `LineChunk` | 44 | x∈[159.9, 475.4]，y∈[647.6, 689.0] |
| `ShapeChunk` | 20（18 rectangle + 1 polyline + 1 group） | x∈[159.9, 464.5]，y∈[666.9, 689.0] |
| `TextChunk` | 33 | **与该区域零交集**（logo 中英文名也不是文字） |

### 2.4 决定性对比实验（`TmpCropRender`）

| 截图 bbox | 结果 |
|---|---|
| `LineChunk ∪ ShapeChunk` 并集 `[159.9, 647.6, 475.4, 689.0]` | **红色风车标记完全丢失** |
| `LineArtChunk` bbox `[97.0, 643.7, 498.4, 691.2]` | 完整 logo 锁定区 |

**结论：截图框必须取 `LineArtChunk` 的 bbox，不能用原始线条重新聚类。**
用户据此选定「整块 logo 锁定区」。

### 2.5 五道闸门逐一道否决（`TmpLoop4Probe`，反射调用 `FlowchartProcessor` 私有方法取真实数值）

| 环 | 位置 | 实测值 | 结果 |
|---|---|---|---|
| Loop 2 | `DocumentProcessor.java:637` | 44 个 `LineChunk` 被整类删除 | 颗粒度信息丢失 |
| Loop 4 ① | `BarChart` / `PieChart` / `LineChart` | `bar=false pie=false line=false` | 跳过 |
| Loop 4 ② | `FlowchartProcessor.isFlowchartCluster:709` | box=401.5×47.6 → aspect **8.44 > 6.0** | 否决 |
| Loop 4 ③ | `LineArtProcessor.scanAndMerge:357` | `h<=3 && w<=300` → 47.6 / 401.5 | 不能做合并种子 |
| Loop 4 ④ | `DocumentProcessor.java:775` | `paddleUrl` 为 null | `haveFormulas` 根本没被调用 |
| 输出 | `JsonWriter.java:695` | `LineArtChunk` / `ShapeChunk` 被 `continue` 跳过 | 静默消失 |

### 2.6 上游根因：为什么 `LineArtChunk` 粒度这么粗

因为 `useStructTree=false` → `StaticStorages.setIsIgnoreMCIDs(true)`：

1. `veraPDF-validation/wcag-validation/.../LineArtContainer.java:88-89`
   —— ignoreMCIDs 时把**整页所有 line-art bbox 无条件 union 成一个框**。
2. `ChunkParser.java:861-867` + `:848-850`
   —— 填充路径 / 曲线（红色标记）只累加 bbox，**不生成 `LineChunk`**。
3. `ChunkParser.java:1336` —— 最终 `new LineArtChunk(box)`，`innerLineChunks=0`。

所以 `LineArtChunk` 在默认配置下不携带任何形状信息，只是粗粒度包围盒。
**只放宽 `LineArtProcessor` 的种子门槛是不可行的**：那会把整页所有线条（表格线、页眉线）一起框进来。

## 三、修复落点

### 3.1 改动文件清单

| 文件 | 改动 |
|---|---|
| `processors/GraphicRegionProcessor.java` | **新增**，识别 + 截图矢量图形区域 |
| `processors/DocumentProcessor.java:809-814` | Loop 4 末尾接线（Flowchart 之后、`ConsecutiveImageProcessor` 之前） |
| `processors/DocumentProcessor.java:1517` | `collectRawLineChunks` 由 `private` 放宽为包内可见，供密度闸门复用 |

### 3.2 五道闸门（全部实测标定）

| 闸门 | 阈值 | 依据 |
|---|---|---|
| 尺寸 | `≥24 × 12 pt`，长宽比 `≤20` | logo 是「小标记 + 长文字」，实测 401.5×47.6（8.44）。**不能复用 FlowchartProcessor 的 6.0** |
| 页面占比 | `≤30%` | 防止 ignoreMCIDs 的整页并集框吞掉正文 |
| 不在表格内 | `TableBordersCollection.getTableBorder(box) == null` | 表格框线也是 line art |
| 密度 | `rawLines ≥8` **或** `ShapeChunk components ≥8` | 页眉横线、章节分隔线只有 1–2 笔 |
| 无通栏笔画 | 无单笔笔画跨度 `≥80%` 区域长边 | 见 3.3 |
| 无文字 | 交集 / 较小框面积 `>5%` 即否决 | 见 3.3 |

### 3.3 两次收紧的取舍（都是被对照样本实测逼出来的）

**第一轮 —— 文字闸门太松。**
初版用 `box.getIntersectionPercent(textBox) > 0.5`，在 `200910061781738030852011943.pdf`
（10 页披露易 PDF，第 7–9 页为「已發行股本的其他變動」**无线表格**）上产出 **15 张假截图**：
表格每一行都是一条独立 `LineArtChunk`，行内文字只占行高约 1/3，按「占区域面积」算交集 ≈0.3，全部漏过。
→ 改为**按较小框面积比**、阈值降到 `0.05`。理由站得住：矢量图形区域**本来就不含任何文字对象**
（实测整个 logo 锁定区与 `TextChunk` 零交集），所以「有任何文字」就该否决。假截图降到 8 张。

**第二轮 —— 空行格没有文字可否决。**
剩下的 8 张是无线表格里**完全没有内容的空行格**（裁剪渲染确认：只有左侧竖线、上下横线、右端一小段）。
继续探针实测才发现根因：该表格被 `ShapeRecognizer` 识别成 **4 个横跨整页宽的 `polyline` 形状**
（page 7：`[52.26, 528.44, 558.12, 770.96]`、`[52.26, 384.44, 558.12, 528.44]`、
`[52.26, 237.74, 558.12, 384.44]`、`[52.26, 96.62, 558.12, 237.74]`，宽均为 506pt），
所以第一版只查 `LineChunk` 的「通栏 ruling」判据完全没生效。
→ 新增 `hasFullSpanStroke`：同时检查原始 `LineChunk` **与非 group 的 `ShapeChunk`**，
跨度 `≥80%` 区域长边即否决。`TYPE_GROUP` 豁免——group 是多个部件的包络而非一笔，
实测 logo 自带的 group 跨度 286pt / 401.5pt = 71.2%，若不豁免就会误杀。
假截图归零。

### 3.4 明确的功能边界（用户已知悉并选择该方案）

- **本处理器从不删文字**，因此区域内只要有文字就否决。
  副作用：**wordmark 是真文字的 logo 不会被截图**（会走文字层）。这是用户选择的「不配文字移除」的必然结果。
- 用户未选择的上游方案（本次不做）：改 `veraPDF-validation` 的 `LineArtContainer.add`，
  让 ignoreMCIDs 模式也走 `overlaps` 重叠合并，从源头拿回正确粒度。
  代价是会连带影响 `ContentFilterProcessor` 背景过滤、`ParagraphProcessor` 分隔符、
  `TableBorderProcessor` 边框归属。

## 四、实跑验证数据

### 4.1 目标样本

```
Page 1: captured vector graphic region [97.0, 643.7, 498.4, 691.2] 401.5x47.6 pt as image #1
```

| | before | after |
|---|---|---|
| page 1 items | `text x11` | `text x11, image x1` |
| `_images` 目录 | 空 | `imageFile1.png`（23,730 bytes，401.5×47.6 pt） |
| JSON 中 `中國大冶` / `China Daye` | 检索不到 | 已含在截图中 |

截图经肉眼核对：红色风车标记 + 中文名 + 英文名完整，右侧「Limited」未被裁切
（文字实际右边界约 481pt，bbox 右边界 498.4pt）。

### 4.2 A/B 对照（严格基线对比）

做法：把改动文件备份后临时还原、离线编译跑基线 → 恢复改动 → 重新编译跑新版，
两边用同一个 `TmpGraphicRunner`（图片 + markdown 开，配置同 `DebugSample1`）。

| 文档 | 类型 | before | after | 结论 |
|---|---|---|---|---|
| `202609021788354354805052601-1` | 目标 logo | txt=11 img=0 | txt=11 **img=1** | ✅ 预期变化 |
| `200910061781738030852011943` | 无线表格（10 页） | txt=94 img=1 | txt=94 img=1 | identical |
| `201303221781999362299013425` | 多图（23 页） | txt=337 img=2 | txt=337 img=2 | identical |
| `202302281677505819604328-83(流程图)` | 流程图 | txt=11 img=1 | txt=11 img=1 | identical |
| `02333_長城汽車…股東特別大會適用的代表委任表格` | 有线表格 | txt=19 img=1 | txt=19 img=1 | identical |
| `02333_長城汽車…職工董事薪酬方案及股東特別大會通告` | 有线表格 + logo（9 页） | txt=125 img=3 | txt=125 img=3 | identical |
| `200706271781617929794015618-1` | 有线表格 | txt=13 img=0 | txt=13 img=0 | identical |

6 份对照文档的 items 数量与**每张图片的 bbox 逐个比对完全一致**，只有目标样本新增 1 张图。

### 4.3 单测（绕行已损坏的 testCompile）

`mvn -o -pl opendataloader-pdf-core -am test-compile` 的**唯一**失败仍是既有的
`CustomChunksMergeUtilsTest`（引用了 `CustomChunksMergeUtils` 中不存在的
`isPageBackgroundImage` / `isPageBackgroundSize`），与本次改动无关。

用反射 runner（`TmpTextNearLogo.java`，支持 `@BeforeEach` / `@BeforeAll`）跑相关测试类：

```
PASS DocumentProcessorPropagationTest  run=3  failed=0
PASS LineArtProcessorTest              run=6  failed=0
PASS ContentFilterProcessorTest        run=3  failed=0
PASS FlowchartProcessorTest           run=13  failed=0
PASS BarChartProcessorTest             run=8  failed=0
PASS ConsecutiveImageProcessorTest     run=6  failed=0
PASS ShapeRecognizerTest              run=28  failed=0
TOTAL run=67 failed=0
```

## 五、踩坑与经验教训

1. **别把 ignoreMCIDs 的 `LineArtChunk` 当成「一块线条区域」**。它是「整页绘制组的 bbox 并集」，
   而且填充图形只贡献 bbox、不产生 `LineChunk`。本例中红色标记**只**存在于这个并集框里，
   任何基于原始线条的重新聚类都会把它切掉。
2. **`JsonWriter:695` 静默跳过 `LineArtChunk` / `ShapeChunk`** 是个「无声黑洞」：
   区域既不进 JSON 也不进 markdown，日志里一行提示都没有。先确认「产物里有没有」，
   再去追「为什么没进」。
3. **阈值必须用对照样本实测标定，不能照抄同类处理器的常量**。
   本例两次返工都是因为沿用了「看起来合理」的阈值：
   - Flowchart 的 `MAX_ASPECT_RATIO=6.0` 会误杀 8.44 的 logo；
   - 文字交集按「占区域面积」算会漏掉表格行。
4. **排查误命中要先看渲染图，再看数据**。空行格的问题靠 `TmpCropRender` 看一眼就能定性，
   靠 bbox 数字猜不出来；而 `polyline` 跨 506pt 这个关键事实是靠探针实测才拿到的。
5. **A/B 必须自己重跑基线**。`tmp_output/` 里的历史 JSON 来自不同配置、不同代码版本，直接当基线会得出错误结论。

## 六、复现命令

```powershell
# 编译（离线）
cd d:\Code\JavaCode\opendataloader-pdf\java
mvn -q -o -pl opendataloader-pdf-core -am compile -DskipTests

# 生成 classpath（用完即删，未被 gitignore）
cd opendataloader-pdf-core
mvn -q -o dependency:build-classpath "-Dmdep.outputFile=cp.txt"
cd ..\..
$cp = (Get-Content "java\opendataloader-pdf-core\cp.txt" -Raw -Encoding UTF8).Trim()
$full = "java\opendataloader-pdf-core\target\classes;$cp"

# 目标样本端到端
java -cp $full org.opendataloader.pdf.DebugSample1

# A/B 批量
javac -encoding UTF-8 -cp $full -d tmp_output\probe\abrunner TmpGraphicRunner.java
java -cp "tmp_output\probe\abrunner;$full" org.opendataloader.pdf.TmpGraphicRunner tmp_output\probe\ab-list.txt <输出目录>

# 探针（均未入库，可随时删）
java -cp "tmp_output\probe\probecls;$full" TmpArtifactDump <pdf>              # 逐页 artifacts
java -cp "tmp_output\probe\probecls;$full" TmpLoop4Probe   <pdf>              # Loop 4 各判定数值
java -cp "tmp_output\probe\rendercls;$cp"   TmpCropRender  <pdf> 0 <x0> <y0> <x1> <y1> <dpi> <out.png>
java -cp "tmp_output\probe\probecls;$full" TmpChunkDump   <pdf> <page> <x0> <y0> <x1> <y1>

# 相关单测（绕开已损坏的 testCompile）
$tsrc="java\opendataloader-pdf-core\src\test\java"
javac -encoding UTF-8 -cp "$full" -d tmp_output\probe\testcls TmpTextNearLogo.java
javac -encoding UTF-8 -cp "$full;tmp_output\probe\testcls" -sourcepath $tsrc -d tmp_output\probe\testcls `
  "$tsrc\org\opendataloader\pdf\processors\DocumentProcessorPropagationTest.java" ...
java -cp "tmp_output\probe\testcls;$full" org.opendataloader.pdf.TmpTextNearLogo <FQCN> [<FQCN> ...]
```

## 七、相关旧文档

- `docs/memory/2026-09-22-柱状图截图吞并正文并重复-根因定位.md`
  —— 同类问题：截图与文字层重复，本次「无文字闸门」是同一条经验的延伸。
- `docs/memory/2026-08-20-每页公式检测have_formula字段与OCR截图.md`
  —— `Loop 4` 调用 `haveFormulas` 而非 `processLineArtGroups` 的由来（截图被还原丢弃）。
- `docs/memory/2026-09-15-流程图识别三问题-根因定位与修复.md`
  —— `FlowchartProcessor` 的否决条件与 `MAX_ASPECT_RATIO` 出处。
- `docs/memory/2026-08-09-ShapeRecognizer忽略白色矢量图形避免bar_chart误识别.md`
  —— `ShapeChunk` 的误识别来源，本次「无线表格被识别成 4 个 polyline」是同一类现象。
- `docs/memory/2026-09-21-有线表格误判为9行7列-根因定位与ChunkParser条带折叠修复.md`
  —— `LineArtContainer` / `ChunkParser` 的条带折叠行为。