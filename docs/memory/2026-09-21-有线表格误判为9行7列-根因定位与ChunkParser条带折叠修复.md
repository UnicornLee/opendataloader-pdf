# 2026-09-21 — 有线表格被拆成 9 行 × 7 列：根因定位与 ChunkParser 条带折叠修复

## 任务背景

用户反馈：`docs/pdf/200706271781617929794015618-1.pdf`（建业实业 Chinney Investments 2007-06-27
股东特别大会投票结果公告，单页）里的**有线表格识别不对**：

1. 表格里出现**很多列宽很窄的空单元格**；
2. **第一行是一个多余的空行**；
3. 这个表格"**应该是四行三列，其中 2 个合并单元格**：第一行第一个单元格是两行一列，第一行第二个单元格是一行两列"。

用户同时给了两条边界条件：

- 工作区 `D:\Code\JavaCode\opendataloader-pdf-parse` 下有两个 veraPDF 源码仓库：
  **`veraPDF-validation`** 和 **`veraPDF-wcag-algs`**（本次问题横跨二者）；
- **先查出真正原因、给出方案，先不要改代码**；
- 可通过运行 `org.opendataloader.pdf.DebugSample`（第 45 行当时已指向本 PDF）或 IntelliJ Debugger 调试；
- 有不清楚的地方需要澄清。

---

## 定位过程

### 阶段 0：全貌与三个必须回答的问题

先明确"表格"在工程里有三条互斥的产出路径，本任务属于哪一条：

| 路径 | `item_type` | 触发条件 | 核心类 |
|---|---|---|---|
| ① 有线边框表格 | `lattice_table` | 默认（`structured`，非 cluster） | veraPDF `TableBorder` + `TableBorderProcessor` |
| ② 聚类表格（无线） | `lattice_table` | `config.isClusterTableMethod()` | `ClusterTableProcessor` |
| ③ OCR 无线表格 | `stream_table` | `basicParseStreamTable` + `paddleUrl` | `StreamTableProcessor` + `PaddleOcrResultUtils` |

`DebugSample` 里确实开着 `basicParseStreamTable=true`，但 JSON 输出的两个表格都是 `lattice_table`，
所以走的是 **① 有线路径**，即"PDF 里的线 → 线条集合 → 表格边界"这条链。

有线路径完整调用链：

```
OpenDataLoaderPDF.processFile
 └─ DocumentProcessor.processFileWithResult
      └─ extractContents → preprocessing
           ├─ new GFSAPDFDocument(pdDocument)
           ├─ document.parseChunks()                       ← ChunkParser 把 PDF 图形解析成 LineChunk
           ├─ ShapeRecognizer.recognize(...)
           ├─ LinesPreprocessingConsumer.findTableBorders() ← 把线按相交关系聚成 TableBorderBuilder
           └─ StaticContainers.setTableBordersCollection(new TableBordersCollection(...))
                 └─ new TableBorder(builder)                ← 由顶点聚类出行列边界、算 rowSpan/colSpan
      └─ processDocument → TableBorderProcessor.processTableBorders
      └─ generateCustomOutputs → JsonWriter（写 row_length / column_length / text）
```

要回答的三个问题：**期望结构到底是什么、实际输出错在哪、错在哪一层**。

### 阶段 1：确认"用户看到的表格"与"实际输出"

**先看实际输出。** 跑 `DebugSample`，读 `tmp_output/200706271781617929794015618-1.json`，
定位 id=10 的 `lattice_table`（第 354–857 行）。把它的坐标一张一张列出来：

```
表格 bbox: x[28.296, 566.979]  y[414.716, 565.505]

列边界：28.346 | 28.856 | 399.061 | 399.572 | 482.74 | 483.25 | 566.419 | 566.929
行边界：414.766|415.276|448.838|449.348|468.964|469.474|516.983|517.436|565.002|565.455
```

相邻边界成对出现、**每对间距都是 0.51pt 或 0.453pt**：

- 列：`28.346/28.856`、`399.061/399.572`、`482.74/483.25`、`566.419/566.929` → 8 个边界 = **7 列**
  （3 个真列 + 4 个宽 0.51pt 的空列）
- 行：10 个边界 = **9 行**（4 个真行 + 5 个高 0.51/0.453pt 的空行）

并且合并单元格的 span 被"顺带"放大：`Ordinary Resolutions` 是 `row_len=3`（应 2）、
`Number of Shares (%)` 是 `column_len=3`（应 2）。JSON 里还混着 `x0 = 1.7976931348623157E308`、
`y1 = -1.7976931348623157E308` 的**占位空 cell**（被 span 覆盖的位置）。

**再看用户看到的表格。** 用 PDFBox `PDFRenderer.renderImageWithDPI(page0, 150)` 渲染第 1 页，
结果与用户描述完全一致：

```
+--------------------------------------------+-------------------+
|                                            | Number of Shares  |
|          Ordinary Resolutions              |       (%)         |
|                                            +---------+---------+
|                                            |   For   | Against |
+--------------------------------------------+---------+---------+
| 1. To approve the acquisition of ...       |24,027,249|    0    |
|                                            |  (100%)  |  (0%)   |
+--------------------------------------------+---------+---------+
| 2. To approve the acquisition of 50% ...   |24,027,249|    0    |
|                                            |  (100%)  |  (0%)   |
+--------------------------------------------+---------+---------+
```

**结论：期望结构 = 用户说的"4 行 3 列 + `Ordinary Resolutions` 跨 2 行 + `Number of Shares (%)` 跨 2 列"，
与实际输出 9×7 的差距，全部集中在"每个线位多出一列/一行、宽度恰等于线宽"这个特征上。**

### 阶段 2：从 JSON 反推边界是怎么来的

`TableBorder` 的行列边界由两个方法生成（`veraPDF-wcag-algs`）：

```java
// TableBorder.java:91
private void calculateXCoordinates(TableBorderBuilder builder) {
    List<Vertex> vertexes = builder.getVertexes().stream()
            .sorted(new Vertex.VertexComparatorX()).collect(Collectors.toList());
    double x1 = vertexes.get(0).getLeftX();
    double x2 = vertexes.get(0).getRightX();
    for (Vertex v : vertexes) {
        if (x2 < v.getLeftX() - NodeUtils.VERTEX_TABLE_FACTOR * v.getRadius()) {   // ← 关键判据
            candidateXCoordinates.add(0.5 * (x1 + x2));
            x1 = v.getLeftX();
            x2 = v.getRightX();
        } else if (x2 < v.getRightX()) { x2 = v.getRightX(); }
    }
    ...
}
```

`NodeUtils.VERTEX_TABLE_FACTOR = 4`。判据含义是："新边界必须比当前簇的右边缘再远出 `4 × 顶点半径`"，
否则并入同一簇。

已知 JSON 里相邻边界间距是 0.51pt，若要让它们被拆成两簇，需要
`0.51 > 4 × radius`，即 `radius < 0.1275`（线宽 < 0.255pt）。

**这个不等式是分水岭**：如果 PDF 里确实存在两条相距 0.51pt 的**细线**，那这个聚类行为本身没错，
问题就在上游（"为什么一条表格线变成了两条"）；如果只有一条 0.5pt 的线但被算成了两组顶点，
问题就在构造顶点的地方。必须先拿到真实数据。

### 阶段 3：探针实测线条与顶点（定位的第一个决定性证据）

写了一个临时探针（复用 `DocumentProcessor.preprocessing` 的初始化序列，但只走到 `parseChunks`
与 `findTableBorders`，然后反射读取 `TableBorder` 的私有坐标数组）。**踩坑记录**：初始化必须用
`StaticContainers.updateContainers(document, fileName)`，用 `StaticContainers.setDocument(document)`
会让 `LinesCollection`（ThreadLocal）保持 null，直接 NPE：

```java
PDDocument pdDocument = new PDDocument(pdfName);          // org.verapdf.pd.PDDocument
StaticResources.setDocument(pdDocument);
GFSAPDFDocument document = new GFSAPDFDocument(pdDocument);
StaticResources.setFlavour(Collections.singletonList(PDFFlavour.WCAG_2_2_HUMAN));
StaticStorages.setIsFilterInvisibleLayers(false);
StaticContainers.updateContainers(document, pdfName);    // ← 不是 setDocument
StaticContainers.setIsDataLoader(true);
StaticStorages.setIsIgnoreMCIDs(true);
StaticStorages.setIsAddSpacesBetweenTextPieces(true);
document.parseChunks();
// 然后遍历 StaticContainers.getLinesCollection().get{Vertical,Horizontal,Squares}(page)
// 再 new LinesPreprocessingConsumer().findTableBorders() → new TableBorder(builder)
```

实测 `LinesCollection` 中本表格区域的线（节选）：

```
V x=28.3461 y[372.8760..427.1740] width=0.1000
V x=28.8563 y[372.8760..426.6640] width=0.1000     ← 同一个"左外框"位置
H y=427.1240 x[28.2961..399.6210] width=0.1000
H y=426.6140 x[28.8066..399.1110] width=0.1000     ← 同一条"上边框"
```

对应的 `TableBorder`：

```
xCoordinates=[28.3463, 28.85645, 399.061, 399.5715, 482.74, 483.25, 566.4185, 566.929]
xWidths     =[0.1004, 0.1003, 0.1, 0.101, 0.1, 0.1, 0.101, 0.1]
yCoordinates=[427.124, 426.614, 393.052, 392.542, 372.926, 372.416, 324.907, 324.454, 276.888, 276.435]
rows=9 cols=7
```

**两个关键事实**：

1. **每个表格线位置确实存在两条独立的线**，线宽只有 **0.1pt**，间距 0.51pt；
2. 每条线的长度不同（外沿 538.58pt、内沿 537.56pt），且成对出现 —— 这是"**梯形带的两条长边**"的形状特征，
   不是"一条线被算了两遍"。

于是半径 0.05、`4 × radius = 0.2 < 0.51`，`TableBorder` 把它们判成两个独立边界 ——
**这一层的行为在它的输入前提下是"自洽"的，真正的 bug 在上游：一条表格线为什么变成了两条。**

### 阶段 4：直接 dump PDF ContentStream（定位的第二个决定性证据）

用 PDFBox `PDFStreamParser` 遍历页面操作符，打印 `w / m / l / re / S / f* / B`：

```
path(f*) lw=1.0
    m=(283.4610,6913.7002)              ← 点 A（外沿左端）
    l=(5669.2900,6913.7002)             ← 边 AB（外沿长边，水平）
    l=(5664.1802,6908.6001)             ← 边 BC（斜边）
    l=(288.5660,6908.6001)              ← 边 CD（内沿长边，水平）
w  lineWidth=0.0000
path(S) lw=0.0
    （同样这 3 条边）
```

坐标被页面的 `cm` 放大了 10 倍（`5669.29 → 566.929`）。换算后：

- 外沿：`(28.3461, 691.37) → (56.929, 691.37)`，内沿：`(28.8566, 690.86) → (56.4185, 690.86)`；
- **只有 3 条边**（第 4 条边 `D→A` 靠 `f*` 的隐式闭合补齐）；
- 带厚 = `691.37 − 690.86 = 0.51pt`，即视觉上一条 **0.5 磅（≈0.176mm）的表格线**；
- 先 `f*`（奇偶填充）后 `w 0` + `S`（hairline 描边），**同一路径被画了两遍**。

表格的 4 条竖线、5 条横线全部是这种"**梯形填充带 + hairline 描边**"。
（渲染图）证实这与视觉一致 —— 用户看到的就是普通单线表格。

### 阶段 5：在解析层找到"多出来的那条线"

`veraPDF-validation/wcag-validation/.../chunks/ChunkParser.java` 里，路径操作符 `l` 会逐段生成
`LineChunk`，落到 `nonDrawingArtifacts`；随后按操作符分流：

- `processf()`（`f`/`f*`）：里面有 `parsingRectangleFromLines(i, fillColor)`，**本意就是把"填充的细长四边形"
  压成一条有宽度的中心线**。但它要求 4 条首尾相接的边：

  ```java
  private LineChunk parsingRectangleFromLines(int i, double[] color) {
      LineChunk line1 = (LineChunk) nonDrawingArtifacts.get(i);
      if ((i < nonDrawingArtifacts.size() - 3) && ... ) {   // ← 必须有第 4 条边
          ...
          if (Vertex.areCloseVertexes(line1.getEnd(), line2.getStart()) && ... ) {
              if (isHorizontalLine(line1, line2, line3, line4)) { ... }

  private static boolean isHorizontalLine(LineChunk line1, LineChunk line2,
                                          LineChunk line3, LineChunk line4) {
      return line1.isHorizontalLine() && (line2.isVerticalLine() ||
              Vertex.areCloseVertexes(line2.getEnd(), line2.getStart())) &&
             line3.isHorizontalLine() && (line4.isVerticalLine() ||
              Vertex.areCloseVertexes(line4.getEnd(), line4.getStart()));
  }
  ```

  本 PDF 只有 3 条边 → 连"取 4 条边"都做不到 → 返回 null → `processf` 只把 bbox 并进 lineArt，**不产生线**
  （所以填充部分是无害的）。

- `processS()` /（`s` 走 `processh()` 补边后同样调 `processS()`）：**完全没有压线逻辑**，
  把 `nonDrawingArtifacts` 里每个 `LineChunk` 逐条 `transformLineChunk` 后 `processLineChunk`：

  ```java
  private void processS() {
      ...
      for (Object chunk : nonDrawingArtifacts) {
          if (chunk instanceof LineChunk) {
              LineChunk lineChunk = transformLineChunk((LineChunk)chunk,
                      graphicsState.getLineWidth(), graphicsState.getLineCap());
              processLineChunk(boundingBox, mcid, lineChunk);      // ← 每条边都成为一根线
          } ...
  ```

  于是 3 条边 → 3 根线，其中 AB、CD 两条长边成了**相距 0.51pt 的平行线**。

**这里顺便澄清了两个容易误判的细节：**

- `w 0` 并不会写进 `graphicsState`（`case Operators.W_LINE_WIDTH:` 里写了 `if (width > 0.0)`），
  所以 hairline 用的是 GraphicsState 的**默认线宽 1.0**；`transformLineChunk` 里再乘 CTM 缩放 0.1，
  最终 `LineChunk.width = 0.1` —— 这与实测的 `width=0.1000` 完全吻合。
- 因此探针里读到的两条线不是"同一条线重复两遍"（`LineChunk.equals` 比较 start/end/width/color，
  若重复会在 `LinesCollection` 的 Set 里被去重），而是**几何上真实存在的两条不同长度的边**。

### 阶段 6：方案选型

按"改动位置"整理了三个方案：

| 方案 | 位置 | 改动量 | 评价 |
|---|---|---|---|
| A | `ChunkParser`：让描边路径也走"细带压中心线"，并把 `parsingRectangleFromLines` 从"必须 4 条边"放宽到"3 条边 + 隐式闭合" | 中等，跨仓库 | 从源头消灭重复线，所有下游（表格、下划线、图形）受益；风险最大 |
| B | `TableBorder.calculateX/YCoordinates` 的聚类阈值加下限（如 1.0pt），把过近的相邻边界合并 | 2 处各 1 行 | 最对症、易回归；但阈值是经验值，且属"在下游掩盖上游错误" |
| C | opendataloader 侧 `TableBorderProcessor`/`TableStructureNormalizer` 后处理删除窄空行空列 | 较大 | 不碰 veraPDF，但现有 `TableStructureNormalizer` 只对"行≤2 且列≥3"生效，覆盖不到本例 |

**用户选定方案 A。**

---

## 根因

一句话：**这份 PDF 把每条表格线画成"宽 0.51pt 的填充带 + hairline 描边"，而 veraPDF 的描边路径解析
把这条带子的两条长边各当成一根独立的线，于是每个线位在模型里变成两条相距 0.51pt 的平行线；
`TableBorder` 的边界聚类阈值（`4 × 半径 = 0.2pt`）小于这个间距，就把它们当成了两条独立的行列边界。**

拆成可复现的三级链条：

1. **PDF 绘制层**：表格线 = `m/l/l/l`（**3 条边的开放梯形**）+ `f*` + `w 0` + `S`；
   带厚 0.51pt（0.5 磅），外沿与内沿长度不同（斜接），视觉上是单条表格线。
2. **图形解析层（`ChunkParser`）**：
   - `parsingRectangleFromLines()` 只挂在 `processf()` 上，且要求 **4 条首尾相接的边**，
     本例 3 条边 → 不生效；
   - 即便放宽到 3 条边也**仍然匹配不上** —— 它内部的 `isHorizontalLine/isVerticalLine` 要求
     "连接边是竖线/水平线或退化点"，而斜接带的斜边两者都不是（`isVerticalLine` 与
     `areCloseVertexes(BC.end, BC.start)` 同时为 false）；
   - `processS()` 没有任何压线逻辑，逐边输出 `LineChunk` → 每个线位两根平行线（`width=0.1`）。
3. **表结构层（`TableBorder`）**：`calculateXCoordinates` / `calculateYCoordinates`
   （`TableBorder.java:91` / `:276`）以 `x2 < v.getLeftX() - NodeUtils.VERTEX_TABLE_FACTOR * v.getRadius()`
   聚类边界，`VERTEX_TABLE_FACTOR = 4`、hairline 半径 0.05 → 阈值仅 **0.2pt < 0.51pt**，
   两根平行线被保留为两个边界 → 4 条竖线变 7 列、5 条横线变 9 行，
   并连带把 `rowSpan/colSpan` 一起放大（`Ordinary Resolutions` 3→应 2，`Number of Shares (%)` 3→应 2）。

---

## 修复实施（方案 A）

文件：`veraPDF-validation/wcag-validation/src/main/java/org/verapdf/gf/model/factory/chunks/ChunkParser.java`（+115 / −1）

### 1. 新增三个阈值常量（75 / 81 / 87 行）

```java
/** 折叠为单线时允许的最大条带厚度（最终坐标系、pt）。更厚的四边形按普通轮廓保留。 */
private static final double MAX_STRIPE_THICKNESS = 3.0;
/** 只有厚度 ≤ 长边长度 × 该比例时才算条带（等价于长度 ≥ 5×厚度）。 */
private static final double MAX_STRIPE_THICKNESS_TO_LENGTH_RATIO = 0.2;
/** 连接两条长边的短边长度上限 / 厚度；45° 斜接约为 1.41。 */
private static final double MAX_STRIPE_JOINING_EDGE_FACTOR = 2.5;
```

### 2. 新增 `parsingStripeFromLines(int i, int[] consumed)`（929 行）

把"描边成一条路径的细长四边形"折叠成**一条带厚度的中心线**：

- 形态：**显式闭合 4 条边**（`s` 会先经 `processh()` 补出闭合边）或**隐式闭合 3 条边**
  （取 `line3.getEnd() → line1.getStart()` 作为补齐的第 4 条边）；
- 中心线端点取两条**连接边**的中点，宽度取两条**平行长边**的间距：
  ```java
  LineChunk stripe = new LineChunk(pageNumber, line2.getCenterX(), line2.getCenterY(),
          line4.getCenterX(), line4.getCenterY(), thickness, graphicsState.getStrokeColor());
  return transformLineChunk(stripe, thickness, LineChunk.BUTT_CAP_STYLE);
  ```
  （与既有 `parsingRectangleFromLines` 完全同一套公式）
- 四个放行判据：
  1. `line1` 与 `line3` **同时是水平线或同时是竖直线**（平行长边）；
  2. `thickness * CTM.getScaleValue() ≤ MAX_STRIPE_THICKNESS`；
  3. `thickness ≤ MAX_STRIPE_THICKNESS_TO_LENGTH_RATIO × length`（细长比）；
  4. `max(|BC|, |DA|) ≤ MAX_STRIPE_JOINING_EDGE_FACTOR × thickness`（连接边足够短）。

  > **判据 1 去掉了连接边的类型约束**，这是与 `parsingRectangleFromLines` 的本质差别：
  > 斜接带的斜边既不竖直也不水平，原来那套 `isHorizontalLine/isVerticalLine` 永远为 false。
  >
  > **判据 4 是防止误折叠 "Z 形" 折线**：Z 形虽然有两条平行水平边，但连接边长 ≈ 长边的 1.4 倍；
  > 而斜接带的连接边只有厚度的 1.41 倍。
  >
  > **绝对厚度必须换算到最终坐标系**：`nonDrawingArtifacts` 里的线处于 `cm` **变换前**坐标
  > （本例被放大 10 倍，厚度是 5.1），所以要与 `MAX_STRIPE_THICKNESS`（pt）比较前先乘
  > `graphicsState.getCTM().getScaleValue()`；比率型判据（3、4）与缩放无关，可直接用变换前坐标。

### 3. `processS()` 改为索引循环并跳过已消费的边（769–790 行）

```java
for (int i = 0; i < nonDrawingArtifacts.size(); i++) {
    Object chunk = nonDrawingArtifacts.get(i);
    if (chunk instanceof LineChunk) {
        int[] consumed = new int[1];
        LineChunk stripe = parsingStripeFromLines(i, consumed);
        if (stripe != null) {
            processLineChunk(boundingBox, mcid, stripe);
            i += consumed[0] - 1;            // 3 或 4 条边一次吃掉
            continue;
        }
        LineChunk lineChunk = transformLineChunk((LineChunk)chunk, graphicsState.getLineWidth(),
                graphicsState.getLineCap());
        processLineChunk(boundingBox, mcid, lineChunk);
    } else if (...) { ... }
}
```

未命中条带时行为与改动前**逐字节一致**。

### 4. 新增辅助方法（979 / 983 / 992 行）

`asLineChunk(Object)`、`getLineLength(LineChunk)`（欧氏长度）、
`getLineLength(LineChunk, boolean horizontal)`（沿主轴长度，忽略 hairline 宽度）。

### 5. 取舍说明

- **只改 `processS()`（覆盖 `S` 与 `s`），不动 `processf()` 与 `processB()`**。
  `processf` 的既有行为是"3 条边返回 null → 不产线"，对本例无害；
  `processB`（`B`/`b` = 填充+描边）存在同类问题，但为把影响面控制在一个函数内，本次未一并处理（见"遗留"）。
- 复用了既有公式与 `BUTT_CAP_STYLE`，避免引入新的几何约定。

---

## 验证

### 1. 端到端（`DebugSample` 重跑目标 PDF）

修复前 → 修复后：

| | 表格数量与结构 |
|---|---|
| 修复前 | `1x1{1,1}`、`1x1{1,1}`、**`9x7`**（含 4 个 0.51pt 空列、5 个空行）、`1x1{1,1}` |
| 修复后 | `1x1{1,1}`、**`4x3{2,1; 1,2; 1,1 ×8}`** |

- 第 1 个 `1x1` 表格（页面顶部的公告文本框）从"外圈/内圈各算一个 1x1 表格"合并为 **1 个**；
- 目标表格变成 **4 行 × 3 列**，全 JSON 中**只有两处非 1 的 span**：
  - `"row_len" : 2` → `Ordinary Resolutions`（两行一列）✅
  - `"column_len" : 2` → `Number of Shares (%)`（一行两列）✅
- 窄空列、空行、`±Double.MAX_VALUE` 占位 cell 全部消失。

### 2. 回归：77 个样本前后逐字对比

写临时探针 `TmpTableShapeProbe`，只跑到 `DocumentProcessor.preprocessing`，然后 dump 每页每个
`TableBorder` 的 `RxC` 与逐格 `rowSpan,colSpan` 序列（不跑全文解析，单文档 2~45 秒）：

- 样本：`docs/pdf` 下 **66 个 ≤2MB** + **11 个 2~20MB**，共 77 个；
- 基线：`git stash push -- ChunkParser.java` → `mvn -o -pl wcag-validation install` → 跑一次 →
  `git stash pop` → 再 install；
- 结果：**仅目标文档的 2 个副本出现差异，且都是改善**，其余 **75 个样本结构逐字不变**：

```
DIFF: 200706271781617929794015618-1.pdf
  before: p0:[1x1{1,1;}1x1{1,1;}9x7{1,3;1,1;1,1;3,1;3,1;3,1;1,3;...;2,1;1,1;2,1;1,2;1,2;}1x1{1,1;}]
  after : p0:[1x1{1,1;}4x3{2,1;1,2;1,1;1,1;1,1;1,1;1,1;1,1;1,1;1,1;}]
changed: 2 / 66      （≤2MB 组）
changed: 0 / 11      （2~20MB 组）
```

结果文件：`tmp_output/table_shape_{before,after}_{small,mid}.txt`；探针源码备份在
`tmp_output/probe_backup/TmpTableShapeProbe.java`。

### 3. 单测

- `opendataloader-pdf-core`：改动前后均为
  `Tests run: 893, Failures: 40, Errors: 17, Skipped: 1` —— **基线一致**，
  那 40+17 个失败全部是图片渲染 / 页分隔 / `AutoTagger` / `Poppler` 等**环境相关的既有失败**；
- 与线条、表格相关的用例全绿：`ShapeRecognizerTest`(22)、`TableBorderProcessorTest`(8)、
  `TextDecorationProcessorTest`(21)、`SpecialTableProcessorTest`(1)；
- `wcag-validation` 模块自身没有测试。

### 4. 构建方式（重要）

改 `wcag-validation` 后必须重新装进本地仓库，`opendataloader-pdf-core` 才能用到：

```powershell
cd D:\Code\JavaCode\opendataloader-pdf-parse\veraPDF-validation
mvn -o -pl wcag-validation "-DskipTests" "-Dmaven.javadoc.skip=true" "-Dmaven.source.skip=true" install
```

（PowerShell 下所有 `-D` 参数要整体加引号，否则 `-Dmaven.javadoc.skip=true` 会被拆开报
`Unknown lifecycle phase ".javadoc.skip=true"`。）

---

## 附带发现与遗留

1. **`processB()`（`B`/`b`）未同步处理**：它同样把描边路径的每条边当作独立线。本次为控制影响面只改了
   `processS()`（`S`/`s`，覆盖绝大多数导出工具）。如遇到用 `B` 画表格线的文档，可复用
   `parsingStripeFromLines` 再改一处。
2. **`re` 矩形描边存在同类隐患**：`processS()` 里对 `Rectangle` 的分支只在
   `rectangle.getHeight() < lineWidth || rectangle.getWidth() < lineWidth` 时才压成一条线；
   对"宽 0.51pt、线宽 0.1pt"的细长矩形不满足该条件，会走 `getLines()` 产出 4 条边线 —— 与本次同类。
3. **`parsingRectangleFromLines` 的判据偏窄**：只认"连接边竖直/水平或退化点"的**矩形带**，
   对斜接产生的**梯形带**不生效，这正是本次没能被既有逻辑接住的原因。
4. **`TableBorder` 的边界聚类阈值偏小**：`4 × radius` 对 hairline（0.05）只有 0.2pt，
   任何"两条相距 0.3~3pt 的平行线"都会被当成两个边界。若后续还有别的 PDF 复现同类问题，
   方案 B（阈值下限）可作为第二道防线补上（本次未做，避免掩盖上游问题）。
5. **页数/文件变化提示**：调试期间该 PDF 被重新导出过一次（CreationDate 10:08 → 10:29，页数 2 → 1），
   但表格坐标与结构未变，不影响本次结论；回归时用的是同一份文件。

---

## 相关文件

**改动（1 个文件，+115 / −1）**

- `veraPDF-validation/wcag-validation/src/main/java/org/verapdf/gf/model/factory/chunks/ChunkParser.java`
  - 常量：75 / 81 / 87 行
  - `processS()`：769 行（调用点 780 行）
  - `parsingStripeFromLines()`：929 行
  - 辅助方法：`asLineChunk` 979、`getLineLength` 983 / 992
  - 参考（未改）：`parsingRectangleFromLines()` 869 行、`isHorizontalLine/isVerticalLine` 其后

**只读、未改但为根因所在**

- `veraPDF-wcag-algs/src/main/java/org/verapdf/wcag/algorithms/entities/tables/tableBorders/TableBorder.java`
  - `calculateXCoordinates` 91 行、`calculateYCoordinates` 276 行、`processMergedCells` 415 行
- `veraPDF-wcag-algs/.../semanticalgorithms/consumers/LinesPreprocessingConsumer.java`（边框分组，126–141 / 204 行）
- `veraPDF-wcag-algs/.../entities/tables/TableBorderBuilder.java`、`entities/geometry/Vertex.java`、
  `entities/content/LineChunk.java`（`getIntersectionVertex` 178 行、构造 55–69 行）
- `opendataloader-pdf/java/opendataloader-pdf-core/src/main/java/org/opendataloader/pdf/processors/DocumentProcessor.java`
  （`preprocessing` 1049 行起，`parseChunks` 1122 / `findTableBorders` 1125）
- `opendataloader-pdf/java/opendataloader-pdf-core/src/main/java/org/opendataloader/pdf/processors/TableBorderProcessor.java`

**验证证据（`opendataloader-pdf/tmp_output/`，已在 gitignore 内）**

- `table_shape_before_small.txt` / `table_shape_after_small.txt`（≤2MB 组，66 个）
- `table_shape_before_mid.txt` / `table_shape_after_mid.txt`（2~20MB 组，11 个）
- `tmp_border_probe.log`（线条与 `TableBorder` 坐标）、`tmp_content_probe.log`（ContentStream 操作符）
- `page0_render.png`（第 1 页渲染图，视觉确认为 4 行 3 列）
- `probe_backup/TmpTableShapeProbe.java`（回归探针源码；`src` 下的临时副本已删除）

**样例与产物**

- 样例：`docs/pdf/200706271781617929794015618-1.pdf`（另有一份同内容的 `...-1` 同名副本
  `200706271781617929794015618.pdf`，两者都随动修复）
- 输出：`tmp_output/200706271781617929794015618-1.json`

**调试入口**

- `java/opendataloader-pdf-core/src/main/java/org/opendataloader/pdf/DebugSample.java`（用户维护，本次未改动）

<!-- 状态说明：本次改动截至 2026-09-21 仍未提交（veraPDF-validation 工作区 git diff 中仅有 ChunkParser.java，+115/−1）；
     已 mvn install 到 D:\Maven_Repo\org\verapdf\wcag-validation\1.31.99，发布时需把该仓库改动一起构建。 -->
