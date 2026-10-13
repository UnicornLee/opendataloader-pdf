# 第 1 页房子 logo 被"外轮廓当横线"否决 + 空表格吞掉笔画 —— GraphicRegionProcessor 细长限定与内联小标记截图

> 样本：`docs/pdf/200707031781619292844066114.pdf`（用户指定，2007 年「建屋贷款」公告，页面 **612.0 × 792.0 pt**，第 1 页 14 个条目）
> 参照文档：`docs/memory/2026-10-08-logo矢量图形区域截图缺失-GraphicRegionProcessor新增与根因定位.md`（该处理器的新增记录）、
> `docs/memory/2026-10-12-第2页左侧logo矢量图形丢失-GraphicRegionProcessor碎片聚类与ConsecutiveImageProcessor水平间距-根因定位与修复.md`（同一处理器的第二个坑，本次是第三个）

## 一、用户诉求

第 1 页两处问题：

1. 有一个 **2 行 2 列、范围不大、所有单元格都为空的表格**，它其实是一个**路径图片**（矢量图形）。
2. **「新百利有限公司」那一行前面有一个路径图片**，应该截图，然后跟「新百利有限公司」拼接在一起。

本次任务中用户的三条关键决策：

| 决策点 | 选择 |
|---|---|
| 房子标志（`屋` 字 logo）的呈现 | **删掉空表格 + 整个房子截成一张图**（而不是只删空表、或按矢量子块分别截图） |
| 行首标志的拼接形式 | **内联 `<img>` 并入同一文字行**（而不是独立的 image 条目、也不是 `<img> + 文字` 的自定义段落） |
| 改动范围 | **两处都做 + 补回归测试** |

另按要求：临时文件在任务收尾时全部清理（含 `.agents/surefire-baseline/` 86 个基线 XML）。

## 二、现象与定位过程

### 2.1 端到端现象（跑 `DebugSample1`）

```
tmp_output\200707031781619292844066114.json
  page 1 items = 14
  #2  type=lattice_table  id=18  x=[413.5, 464.6]  y=[667.1, 715.3]  w=51.03 h=48.28   ← 4 个单元格全空
  #4  type=lattice_table  id=12  x=[245.2, 273.4]  y=[673.5, 701.1]  w=28.21 h=27.56   ← 4 个单元格全空
  #6  type=text  内容 = 屋
  #13 type=text  src=heading  x0=276.817  内容 = 新百利有限公司                          ← 无 <img>
```

两个空表的 bbox 与第 2 节 2.4 记录的两处矢量图形**逐边重合**（房子表 413.53 vs 房子图形的 413.53；
标志表比 Σ 标志外框各方向多出 4 pt，正是 `TableBorderProcessor` 的行/列边距），
这就是"空表格其实是路径图片"的直接证据。

### 2.2 先把 PDF 渲染成图，确认肉眼事实

不信任自己的空间想象，先把第 1 页渲染成 PNG 再看（`PDFRenderer.renderImageWithDPI(page, 288f)`）：

- 行首是一个**带圆形边框的 Σ / 箭头标志**，紧跟「新百利有限公司」；
- 下方是一个把汉字「**屋**」画成**房子**的 logo —— 屋顶三角形 + 两侧墙 + 门洞。

由此确认两处目标图形的实际形状，避免后面把闸门常量调到"刚好让这一页过"的错误方向。

### 2.3 关键未知：Loop4 入口到底有哪些对象

`JsonWriter` 会**跳过** `LineArtChunk` / `ShapeChunk`（见 2026-10-08 文档），
所以"被否决"等于"内容静默消失"，光看 JSON 无法判断矢量对象是否存在、尺寸多少。
而 `DocumentProcessor` 里 Loop4 内联在方法体中、无可调用的接缝（`GraphicRegionProcessor.processGraphicRegions`
是 `private static`，无法直接调用）。

做法：写临时探针 `TmpLogoProbe`，**走真实 `DocumentProcessor#preprocessing`**
（与 `DebugSample1` 完全同参：sort / hyphenate / table 策略 / `ExtractOcr` / 单页），
挂一个 `StreamHandler` 到 `DocumentProcessor` 的 Logger 上，
在 `HEADER-FINISH` 这条日志回调里（此刻 Loop1–Loop3 已完成、Loop4 尚未开始）反射取
`document.getPageContents(pageIndex)` 并 dump。

```java
Logger log = Logger.getLogger(DocumentProcessor.class.getName());
log.addHandler(new StreamHandler(System.out, new SimpleFormatter()) {
    @Override public synchronized void publish(LogRecord record) {
        super.publish(record);
        if (record.getMessage() != null && record.getMessage().startsWith("HEADER-FINISH")) {
            dumpLoop4Input(document);   // 反射 getPageContents(int)，打所有非 TextChunk 对象
        }
    }
});
```

这一步是本次定位的**唯一有效入口**：闸门判定发生在 Loop4 中途，产物层零痕迹，
不在 Loop4 入口打数据就只能靠猜。

### 2.4 Loop4 入口的真实数据

| 对象 | bbox | 尺寸 | 备注 |
|---|---|---|---|
| `LineArtChunk`（房子） | `[413.532, 667.061, 464.759, 715.356]` | **51.23 × 48.29** | 164 条 raw line |
| `ShapeChunk`（房子外轮廓） | `[413.532, 667.686, 464.570, 715.356]` | 51.04 × 47.67 | 68 点 polyline，**紧贴外框** |
| `LineArtChunk`（Σ 标志） | `[249.206, 673.548, 272.420, 697.314]` | **23.21 × 23.77** | 7 条 raw line，5 个 shape 组件 |
| `TextLine`（宿主行） | `[276.817, 673.548, 365.602, 697.314]` | 88.8 × 23.8 | 内容「新百利有限公司」 |
| 散布的 `ShapeChunk` | 房子区内 23 个、标志区内 5 个 | — | 屋顶/墙/圆环等子块 |

宿主 `TextLine` 被包在 `SemanticHeading` → `TextColumn` → `TextBlock` 链中（三者 bbox 一致）。
标志右边缘 272.420 与文字左边缘 276.817 之间只有 **4.4 pt** —— 肉眼"紧挨着"，
但按区域闸门它什么都不是。

### 2.5 根因 A：房子被自己的外轮廓否决

`hasFullSpanStroke` 的语义是"有一根横贯的描边 ⇒ 这是 ruled structure（表格/标尺）"。
它的实现 `spansRegion` **只比长度、不看厚度**：

```java
// 修复前
return candidate.getWidth() >= boxSpan * FULL_SPAN_RULE_RATIO;   // 0.80
```

房子是一枚**线框图形**，它的外轮廓 polyline（51.04 × 47.67）占区域长边的
**99.6% ≥ 80%** ⇒ 被误判成表格横线 ⇒ 整枚房子否决 ⇒ 从输出中消失。

对照：实测本文件里**真正的表格横带** —— 一条无线表格行带为 **506 × 141 pt**（短长比 **0.28**），
另一处 **0.48**；而这条外轮廓 = 47.67 / 51.04 = **0.934**。
三者都能占满区域长边，**长度这一维根本分不开它们**，必须引入第二个维度。

### 2.6 根因 B：Σ 标志过不了闸门，而且没有"内联"这条通路

- `MIN_REGION_WIDTH = 24.0`，实测宽 **23.21** ⇒ **差 0.79 pt** 落空；
- 即使放大 1 pt，笔画数也不够：`MIN_RAW_LINE_COUNT = 8`（实测 7）、`MIN_SHAPE_COMPONENTS = 8`（实测 5）。

这两道闸门是为**独立成图的大 logo** 设计的，用来排除书法笔触、下划线、单条边框。
一个"嵌在文字行里的小标记"本质不属于这一类：它不需要大，只需要**贴着某一行文字**。
缺的不是阈值松紧，而是**一条内联通路**。

### 2.7 根因 C：空表格抢走了本属于图片的内容

两处矢量图形的描边被 `TableBorderProcessor`（Loop2）当成了表格边框，
各产出一个 2×2 的**全空** `TableBorder`（2.1 的 #2、#4）。
这些空表在 JSON 里输出成 `lattice_table`，既不是图片也不是正文。

而 `captureRegion` 的移除逻辑是
`pageContents.removeAll(lineArt)` + `removeIf(content -> box.contains(...) && content instanceof ShapeChunk)`
—— **只让路 `ShapeChunk`，不让路 `TableBorder`**。
于是即便截图成功，那张空表仍会留在页面上，与图片重叠输出。

### 2.8 定位过程中的两次自我修正

1. 一开始怀疑"是聚类没并上"。查过 2026-10-12 记录的 `clusterFragments` 后否掉：
   `fragmentsTouch` 是**双向最近邻**判定，且房子 `LineArtChunk` 单框就有 164 条线（远超 8），
   它压根不需要聚类，是被 veto 掉的。方向从"聚类"转向"veto 判据"。
2. 一度想把 `FULL_SPAN_RULE_RATIO` 从 0.80 调到 0.99 来放过房子。
   拿实测的表格横带（短长比 0.28 / 0.48）一对照就发现是**判据缺维度**而非阈值问题 ——
   0.934 的外轮廓与 0.48 的横带在"长度占满区域"这一维上完全无法区分，
   调阈值会连带影响所有既有的表格否决，且线宽稍粗的规则仍会误伤。

## 三、修复落点

### 3.1 改动文件清单

| 文件 | 改动 |
|---|---|
| `processors/GraphicRegionProcessor.java` | ① `spansRegion` 增加细长限定（新常量 `RULE_MAX_THICKNESS_RATIO`）<br>② `captureRegion` 顺带删除被截图框完全覆盖的**全空** `TableBorder`（新 `isRemovableWithRegion` / `isCoveredBy` / `isContentFreeTable` / `carriesReadableContent`）<br>③ 新增内联小标记通路 `captureInlineMark` + `findInlineMarkHost` / `collectTextLineHosts` / `sharesTextLine` / `horizontalGap` / `insertInReadingOrder` / `growTo` / `TextLineHost` / `nullSafe`<br>④ 类 javadoc 补"Inline marks"一节与细长说明 |
| `test/.../GraphicRegionProcessorTest.java` | 4 → **9** 个用例，`@BeforeEach` 初始化 `StaticContainers` 全局开关 |
| `test/.../CapturingImagesUtils.java` | `saveImageChunk` 补 `chunk.setIndex(saved.size() + 1)`，对齐真实 `ImagesUtils#writeImage` 的契约 |

### 3.2 根因 A 的修复：全跨描边必须同时"细长"

| 常量 | 值 | 依据 |
|---|---|---|
| `RULE_MAX_THICKNESS_RATIO` | 0.60 | 实测真表格横带短长比 **0.28**（506 × 141 pt）/ **0.48**，房子外轮廓 **0.934**。0.60 落在 0.48 与 0.934 之间，给粗描边 / 圆角框留了余量 |

```java
private static boolean spansRegion(BoundingBox box, BoundingBox candidate, double boxSpan) {
    if (candidate == null || candidate.isEmpty() || !box.overlaps(candidate)) return false;
    double longSide = Math.max(candidate.getWidth(), candidate.getHeight());
    double shortSide = Math.min(candidate.getWidth(), candidate.getHeight());
    if (longSide / boxSpan < FULL_SPAN_RULE_RATIO) return false;
    return shortSide / longSide <= RULE_MAX_THICKNESS_RATIO;
}
```

`hasFullSpanStroke` 的两个调用点（候选框 + envelope）共用此判据，行为一致。

### 3.3 根因 C 的修复：只删"覆盖且真空"的表

```java
pageContents.removeAll(lineArt);
pageContents.removeIf(content -> isCoveredBy(box, content) && isRemovableWithRegion(content));
```

三层守卫，缺一不可：

| 守卫 | 作用 |
|---|---|
| `isCoveredBy` = `box.contains(contentBox)` | 只处理被截图框**完全罩住**的对象，框外的表绝不动 |
| `isRemovableWithRegion` 白名单 | 只放行 `ShapeChunk` / `LineArtChunk` / `TableBorder`，文本与位图碰不得 |
| `isContentFreeTable` | 逐行逐格查 `TableBorderCell.getContents()`，**任何一个单元格有内容就不删** |

`isContentFreeTable` 与 `carriesReadableContent` 是两条独立判据：前者查表内格子，
后者查 `pageContents` 里的 `ImageChunk` / 有内容的嵌套表 / 非空文本，用于确认整块区域除描边外无其它可读内容。

### 3.4 根因 B 的修复：内联小标记通路

在聚类与区域闸门**之前**先跑一遍 `captureInlineMark`，认领成功的候选直接从 `pageContents` 移除、不再参与后续判定：

```java
for (LineArtChunk candidate : candidates) {
    if (!pageContents.contains(candidate)) continue;
    if (!captureInlineMark(candidate, pageContents, imagesUtils, rawLines, pageNumber)) {
        regions.add(candidate);   // 不是内联标记 → 交给区域闸门
    }
}
```

内联标记的形状闸门（都按实测标定，不靠"看着办"）：

| 常量 | 值 | 实测 |
|---|---|---|
| `INLINE_MARK_MIN_SIZE` | 8.0 pt | 下限 |
| `INLINE_MARK_MAX_SIZE` | 40.0 pt | 标志 23.2 × 23.8；上限远大于它，**不是为单样本凑数** |
| `INLINE_MARK_MAX_ASPECT_RATIO` | 4.0 | 标志 1.02；排除下划线 / 书法笔触 |
| `INLINE_MARK_MIN_STROKES` | 4 | 标志 7 条 raw line / 5 个 shape 组件，两个条件取"或" |
| `INLINE_MARK_MAX_GAP` | 12.0 pt | 标志到文字实测 **4.4 pt**，留近 3 倍余量 |

落位与尺寸联动：

1. `collectTextLineHosts` 走 `TextLine` / `CustomSemanticParagraph` / `SemanticTextNode`
   → `getColumns` → `getBlocks` → `getLines` 三种宿主形态，记录各级祖先；
   三处遍历都套 `nullSafe(...)`（半成品语义节点不能拖垮整页）。
2. `sharesTextLine`：标记垂直中心落在行内 **且** 行垂直中心落在标记内（双向包含，比单纯 `overlaps` 严格）。
3. `findInlineMarkHost`：多个候选行时取 `horizontalGap` **最小**者，保证多列排版落到正确的行。
4. `insertInReadingOrder`：按 `TextChunk::getLeftX` 定位插入点。
   `CustomChunksMergeUtils.convertImageChunkToTextChunk` 生成的 chunk 用**图片自身 bbox**，
   故 `leftX = 249.2 < 文字 276.8`，自然插到「新百利有限公司」之前。
5. `growTo`：把标记 bbox `union` 回 TextLine 及所有祖先，否则后续按行 bbox 计算
   margin / 读序的代码看不到这张图（实测 heading x0 从 276.817 变为 **249.206**）。
6. **渲染失败要退回**：`imagesUtils.saveImageChunk(...)` 后检查 `imageChunk.getIndex() == null`
   （真实 `ImagesUtils#writeImage` 在无栅格可解时不写索引）。此时**不注入悬空 `<img>`**，
   把该候选退回区域闸门 —— 这个契约正是 `CapturingImagesUtils` 必须补 `setIndex` 的原因。

## 四、实跑验证数据

### 4.1 目标样本

修复日志：

```
INFO: Page 1: captured inline vector mark [249.2, 427.7, 272.4, 451.5] 23.2x23.8 pt
        as image #1 of the text line 新百利有限公司
INFO: Page 1: captured vector graphic region [413.5, 667.1, 464.8, 715.4] 51.2x48.3 pt as image #2
```

JSON 第 1 页：

| | before | after |
|---|---|---|
| `#2`（房子区） | `lattice_table` id=18，4 格全空 | **`image`** `[413.5, 464.8, 667.1, 715.4]` → `imageFile2.png` |
| `#4`（标志区） | `lattice_table` id=12，4 格全空 | 空表消失，该位置由 `paragraph` 承载（见 6.1） |
| `#13` heading | `新百利有限公司`，x0=276.817 | `<img src='...imageFile1.png' style='height: 23.766pt !important; width: 23.214pt !important;' />新百利有限公司`，x0=**249.206** |
| 条目数 | 14 | 14（无条目丢失） |

**截图内容肉眼核对**（`Read` 两张 PNG）：

- `imageFile2.png`（1892 B）：完整的「屋」房子 logo，屋顶/墙/门齐全；
- `imageFile1.png`（1064 B）：完整的圆形 Σ / 箭头标志，无裁切。

坐标 + 尺寸 + 肉眼三重确认，不依赖"日志说成功了"。

### 4.2 单测

`GraphicRegionProcessorTest` **9/9 PASS**，新增 5 个：

| 用例 | 钉住的规则 |
|---|---|
| `capturesAMarkEnclosedByItsOwnOutline` | 外轮廓 99.6% 全跨但 ratio 0.934 > 0.60 ⇒ 不再是横线否决；房子截成 **1** 张 |
| `stillRejectsABandSpannedByOneWideRule` | 真规则带（用例几何：带 507 × 141，描边 506 × 141，ratio 0.279 ≤ 0.60）**仍然否决** —— 防"细长限定"变成万能放行 |
| `dropsOnlyTheContentFreeTableCoveredByTheCrop` | 全空表被删、**有内容的表必须留下** |
| `inlinesASmallVectorMarkIntoTheAdjacentTextLine` | `<img>` 排在文字之前 + TextLine/heading 的 bbox 都扩到 249.206 |
| `leavesAMarkThatIsNotSetIntoAnyTextLine` | 同一枚标记挪到 153 pt 外的沟槽 ⇒ 不内联，交回区域闸门 |

构造 `TableBorder` 的三个坑（都靠 `javap -c` 看字节码确认，源码不在本仓库）：

1. 4 参构造器 `TableBorder(BoundingBox, TableBorderRow[], int, int)` 会调
   `StaticContainers.getNextID()`，脱离文档运行时时 NPE ⇒ 改用 `super(rows, cols)` + 覆写 `getRows()`。
2. `TableBorderRow(int, int, Long)` 只 `anewarray` 分配格子数组、**元素全为 null**
   ⇒ `getCell(i)` 返回 null，需子类直接写 protected 的 `cells[index]`。
3. `StaticContainers.getIsIgnoreCharactersWithoutUnicode()` 默认为 null，
   而 `isNonEmptyText` 会拆箱 ⇒ `@BeforeEach` 里 `setIsIgnoreCharactersWithoutUnicode(false)`
   （项目内 24 处测试均如此）。

### 4.3 全量回归与零回归证明

```
mvn -o -pl opendataloader-pdf-core test
  改动后  Tests run: 892, Failures: 42, Errors: 26, Skipped: 1
  基线    Tests run: 887, Failures: 42, Errors: 26, Skipped: 1
```

**不能只看数字相等**，因此做了两步：

1. `git stash push -- <本次 3 个文件>` 取净基线 → 保存 86 个 surefire XML 到临时目录 → `stash pop` → 重跑。
2. 解析两边的 `TEST-*.xml`，取"失败/错误用例的 `classname.name` 全集"做 `Compare-Object`：

```
BASELINE_FAILING = 68
AFTER_FAILING    = 68
REGRESSIONS      = (none)
NEWLY PASSING    = (none)
```

失败**逐条同名**，全部为既有环境问题，与本次无关：

- `ArrowE2ETest`（35）：测试里硬编码 `D:\Code\JavaCode\opendataloader-pdf-parse\docs\pdf\...`，该目录不存在；
- `Issue408IntegrationTest`：`FileSystemException ... 另一个程序正在使用此文件`（Windows 临时目录删除抖动）；
- `RenderCompareTest`：`FileAlreadyExistsException`；
- `AutoTaggerTest` / `ParentTreeOrderTest` / `HybridReferenceTrailerTest`：既有 `Parallel page processing failed (NPE)`。

## 五、踩坑与经验教训

1. **"占满长边"不等于"是标尺"。** 判据必须包含第二个维度（细长比）。
   线框类 logo 的外轮廓天然贴着外接框，单靠长度判"ruled structure"必误伤，
   且调阈值救不了 —— 真横带 0.28 / 0.48 与方形外轮廓 0.934 **都能占满长边**，
   长度这一维上它们就是同一个值。
2. **闸门是为某一类对象标定的，别把另一类对象塞进去硬调。**
   Σ 标志差 0.79 pt 没过 `MIN_REGION_WIDTH`，最错的做法是把 24 改成 23；
   正解是承认"行内小标记"是**另一种东西**，给它一条独立的通路（尺寸区间 + 行长对齐 + 小间隙）。
3. **"未识别即丢弃"的静默失败，必须在处理阶段的入口打数据。**
   `LineArtChunk` / `ShapeChunk` 不进 JSON，产物层零痕迹。
   本次唯一有效的手段是挂 Logger 到 `HEADER-FINISH`、反射 `getPageContents(pageIndex)`  dump Loop4 输入。
   处理器的入口方法是 `private static`、主流程内联在 `DocumentProcessor` 时，
   用"真实管线 + 日志时机做钩子"比自己拼中间状态可靠得多。
4. **先渲染成图片看原样，再动判据。** 空间几何的口头想象容易反推结论；
   288 dpi 出一张 PNG 就能确认"圆形标志 + 房子化的屋字"，避免把常量往"刚好让这一页过"的方向凑。
5. **顺带删除既有对象时必须配三层守卫。** 覆盖判定（`contains`）+ 类型白名单 + 内容空检查。
   少了第三条，任何"被图片压住的有内容的表"都会被误删。
6. **测试替身要对齐真实协作者的契约。** `CapturingImagesUtils` 原来不设 `index`，
   而生产代码以 `getIndex() == null` 判渲染失败 —— 不对齐就会让新分支在单测里永远走"失败退回"，
   测了个假绿。
7. **回归判定用"失败用例集合逐条同名"，不要用"失败计数相等"。**
   计数相等但一进一出完全可以互相掩盖。surefire XML 是唯一可靠的对账口径
   （控制台日志会被 `Out-File` 按宽度折行打断，别用日志做 diff）。
8. **Windows PowerShell 复杂命令必须落地成 `.ps1` 再执行。**
   内联命令里的双引号会被剥掉、`$` 会被吞、`&&` 不是分隔符；
   `-Dtest=A,B` 的逗号会被当成 shell 自身的参数分隔。
9. **不要在 `git stash` 期间编辑源文件。** 本次基线在后台跑时改了产品代码，
   `stash pop` 直接把改动抹掉（SearchReplace 报"匹配不上"），只能重写一遍。
   基线对照应先取完数据、恢复工作区，再动代码。

## 六、遗留与已知边界

### 6.1 需后续留意（本次未影响目标输出）

- 第 1 页 `#3` / `#4` / `#5` 是 `id=21/22/23` 三个短段落，位置分别落在
  `[422.9, 455.1]`、`[245.2, 273.4]`、`[221.9, 403.8]`（即 `屋` 字与两处矢量标记附近）。
  表格被 `captureRegion` / `captureInlineMark` 移除后，原本挂在表格格子里的文本改由段落承载。
  **条目总数未变、目标两处输出正确**，但这三个段落的逐条内容本次未核对，
  若后续发现该页正文有异常应先看这里。
- **一处未解释的坐标差异**：内联日志打出的 y 区间是 `427.7 – 451.5`，
  而 2.4 的 Loop4 入口探针 dump 是 `673.548 – 697.314`，**相差恰好 245.8 pt**，对应同一枚标记。
  x 区间（249.2 – 272.4）与宿主行判据两边完全一致，JSON 输出也与探针那套坐标一致，
  所以本次全部推演基于**探针 dump 的坐标**。日志那一套疑为截图时刻页面上已有一批内容被整体上移，
  本次未深究；复核时不要拿日志的 y 去对 JSON。

### 6.2 有意保留的边界

- `growTo` 会把祖先 bbox 撑大（heading x0 276.817 → 249.206）。
  这是内联图片在**读序与 margin 计算中被视为行的一部分**所必需的，
  代价是依赖行 bbox 的下游代码会看到这个更宽的框。
- `INLINE_MARK_MAX_SIZE = 40` 与 `MIN_REGION_WIDTH/HEIGHT = 24/12` 有意重叠
  （一枚 30 pt 的密集成型小图两条路都能走）。内联通路先执行，命中即认领，
  未命中才退回区域闸门，因此重叠不会导致重复截图。
- `DebugSample1.java` 的样本路径已指向本目标 PDF（用户本次调查前设置），提交前需自行决定是否还原。

## 七、复现命令

本次的临时探针与脚本（`TmpLogoProbe.java`、根目录 `TmpRenderPage.java`、`.agents\*.ps1` 与产物、
`.agents\surefire-baseline\`）已按用户要求**全部清理**，以下为仍然有效的部分：

```powershell
# 编译 + 装到本地仓库（DebugSample1 依赖 reactor 解析，单独 -pl 会报依赖缺失）
cd D:\Code\JavaCode\opendataloader-pdf\java
mvn -o -q -pl opendataloader-pdf-core -am install -DskipTests

# 端到端（DebugSample1 内已配置目标 PDF，输出到 ..\tmp_output）
mvn -o -pl opendataloader-pdf-core exec:java `
  -D"exec.mainClass=org.opendataloader.pdf.DebugSample1"
# 产物：tmp_output\200707031781619292844066114.json 与 _images\imageFile1.png / imageFile2.png

# 相关单测（逗号分隔在 PowerShell 下可用，但要整串加引号）
mvn -o -pl opendataloader-pdf-core test -Dtest="GraphicRegionProcessorTest,ConsecutiveImageProcessorTest"

# 全量（注意：会重写 samples/json/lorem.*，跑完 git checkout -- 还原）
mvn -o -pl opendataloader-pdf-core test
```

调试期的两个高频动作，下次可直接复用：

- **渲染某页成 PNG 肉眼核对**：`Loader.loadPDF(...)` + `PDFRenderer.renderImageWithDPI(i, 288f)`，
  用 `Graphics2D.translate(-clip.x, -clip.y)` 平移到原点后再画白底，避免截图偏移。
- **dump Loop4 入口状态**：`Logger.getLogger(DocumentProcessor.class.getName())`
  挂自定义 `StreamHandler`，在 `"HEADER-FINISH"` 回调里反射 `getPageContents(int)`。

## 八、相关旧文档

- `docs/memory/2026-10-08-logo矢量图形区域截图缺失-GraphicRegionProcessor新增与根因定位.md`
  —— 五道闸门（尺寸 / 长宽比 / 全跨描边 / 文字否决 / 页面占比）的标定出处，本次改的就是其中的全跨描边闸门。
- `docs/memory/2026-10-12-第2页左侧logo矢量图形丢失-GraphicRegionProcessor碎片聚类与ConsecutiveImageProcessor水平间距-根因定位与修复.md`
  —— `clusterFragments` / `FRAGMENT_GAP_*` / `IMAGE_VETO_MIN_OVERLAP_SHARE` 的来历。
  本次确认房子**不是**聚类问题（单框 164 条线），是 veto 判据缺维度；两条通路互不冲突。
- `docs/memory/2026-09-21-有线表格误判为9行7列-根因定位与ChunkParser条带折叠修复.md`
  与 `docs/memory/2026-08-13-表格行分隔线误识别为arrow排除与PDFBox-fill回退失效bug.md`
  —— 描边被 `TableBorderProcessor` / 其它识别器当成"结构线"的同类现象：
  同一批矢量描边，在表格识别眼里是边框，在箭头识别眼里是箭头，在本次的全跨闸门眼里是横线。
- `docs/memory/2026-09-22-柱状图截图吞并正文并重复-根因定位.md`
  —— 截图与其它内容重叠导致重复输出的同类经验。
