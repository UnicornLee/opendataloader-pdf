# 第 2 页左侧 logo 矢量图形丢失 —— GraphicRegionProcessor 碎片聚类 + ConsecutiveImageProcessor 水平间距闸门

> 样本：`docs/pdf/202609021788351369223042914.pdf`（用户指定，3 页港股公告，页面 595.276 × 841.89 pt）
> 参照文档：`docs/memory/2026-10-08-logo矢量图形区域截图缺失-GraphicRegionProcessor新增与根因定位.md`（同一处理器的新增记录，本次是它的第二个坑）、
> `docs/memory/2026-09-22-柱状图截图吞并正文并重复-根因定位.md`、
> `docs/memory/2026-09-15-流程图识别三问题-根因定位与修复.md`

## 一、用户诉求

第 2 页是一张"新旧标志对照"图：右侧**新標誌**（赛孚迅 SFQ GLOBAL）是位图，正常输出；
左侧**前標誌**（森馬 RIMBACO）**完全丢失**。要求定位原因并给出修复方案，不清楚处需澄清。

本次任务中用户的三条关键决策：

| 决策点 | 选择 |
|---|---|
| 左右两枚 logo 的呈现 | **合成一张对比图**（而不是两张独立图） |
| 回归范围 | 不必扩大到 21 个 >3MB 的大文件，77 个样本足够 |
| 裁切标记场景的输出外溢 | **可接受**（详见 4.3，属顺带修好的既有缺陷） |
| 临时文件 | 任务收尾时全部清理 |

用户还有一句决定性的反问，直接引出第二层根因：
> "代码中不是有逻辑会把连续的图片截成一张吗？" —— 指 `ConsecutiveImageProcessor`

## 二、现象与定位过程

### 2.1 端到端现象（跑 `DebugSample1`）

```
page 2 items: text x8, image x1
唯一 image：x=[340.3, 495.9]  ← 只覆盖右侧 SFQ logo
左侧 [92.1, 262.7] 区间：既不是 image 也不是 text，彻底消失
```

### 2.2 chunk 构成（探针 `TmpPage2Probe`，走真实 `DocumentProcessor#preprocessing`）

第 2 页与左侧 logo 相关的对象全部是**矢量**，被 Tagged PDF 拆成 3 个独立 `LineArtChunk`
（每个 marked group 一个），外加一条灰色下划线 `ShapeChunk`：

| 碎片 | bbox | 尺寸 | 单独判定结果 |
|---|---|---|---|
| 图形 mark | `[92.1, 496.4, 136.0, 545.6]` | 43.9 × 49.2 | ❌ 太稀（3 raw line / 3 shape 组件 < 8） |
| 字形轮廓 | `[139.1, 517.3, 162.6, 541.2]` | 23.5 × 23.9 | ❌ 宽 23.5 < `MIN_REGION_WIDTH` 24 |
| wordmark | `[138.4, 495.4, 262.7, 540.2]` | 124.3 × 44.7 | ❌ 太稀（6 raw line / 1 shape 组件 < 8） |
| 下划线 | `[92.1, 497.2, 135.2, 502.5]` | 14.1 × 5.3 | `ShapeChunk` rectangle，不参与候选 |

碎片之间的间距只有 **2.4 – 3.1 pt**。三者的并集：

```
[92.1, 495.4, 262.7, 545.6]  170.6 × 50.2 pt
raw lines = 10  ✅ ≥8        aspect = 3.4  ✅ ≤20
页面占比 = 1.7% ✅ ≤30%      文字 / 图片交集 = 0  ✅
```

**即：整块 lockup 能通过全部闸门，只是没有任何一步把它们当一个整体看。**

### 2.3 为什么"被否决"等于"内容消失"

`LineArtChunk` 与 `ShapeChunk` **都不进 JSON 序列化**（`JsonWriter` 直接 `continue` 跳过，
见 2026-10-08 文档记录的 `JsonWriter.java:695`）。所以矢量区域只有两种命运：
被 `GraphicRegionProcessor` 认领并截图成 `ImageChunk`，或者**静默消失**。日志里只有一行
`skipped N vector line-art region(s)`，产物层面零痕迹。

### 2.4 第一层修复后暴露的第二层问题

加上碎片聚类后重跑 `DebugSample1`，左侧 logo 出来了，但变成**两张并排的图**：

```
image id=5  x=[340.3, 495.9]  ← 右 logo，排在前面
image id=6  x=[92.1, 262.7]   ← 左 logo，margin_top = -49.864（负数）
```

原因正是用户指向的 `ConsecutiveImageProcessor`：它判断"连续图片"时**只校验垂直间距**
（`gap = 前框.bottomY - next.topY`，与 `MAX_VERTICAL_GAP_RATIO × 较小高度` 比较），
**完全不看水平方向**。两个并排框的垂直 gap 恒为负数，永远满足"间距过小"，
于是该检查对并排布局完全失效 —— 它既拦不住不该合的，也解释不了该合的。

`DocumentProcessor.sortPageContents`（L1957-2003）只按 `topY` 降序 + `leftX`，**没有同行容差**，
所以两张"同一行"的图会按 topY 大小排（右 logo 顶边 550.1 > 左 logo 545.6 → 右图在前），
后一张的 `margin_top` 算成负数。这是既有排序规则，本次未改；合并成一张图后问题自然消失。

### 2.5 第一次尝试失败：绝对阈值分不开两类"大间距"

先加了一条 `MAX_HORIZONTAL_GAP = 5.0` 的绝对阈值 → 结果**仍是两张图**，
因为 5pt 太紧，两枚 logo 中缝 77.6pt 被判定为"不相邻"。

放宽到绝对值（比如 100pt）也不行，因为对照样本里有反例：
`docs/pdf/202504241785136802170055037.pdf` 的**印刷裁切标记**是两个 14.1 pt 的十字，
相距 **521.6 pt**，老逻辑把它们并成了一张 550.2 × 14.1 pt 的横带截图（无意义内容）。

| 场景 | 绝对间距 | 较小框短边 | 归一化后 |
|---|---|---|---|
| logo 对比图中缝 | 77.6 pt | 50.2 pt | **1.5 个短边** → 该合 |
| 裁切标记 | 521.6 pt | 14.2 pt | **37 个短边** → 不该合 |

**结论：容差必须按邻居自身尺寸归一化。**取 `max(5.0, 2.0 × 较小框短边)`。

### 2.6 第二次失败：run 扩展只与"上一个元素"比较

换成相对阈值后 `DebugSample1` **仍然输出两张图**。探针打出来才发现：
右侧新 logo 本身就由两片 `ImageChunk` 组成，第二片只有 **31.3 pt 宽**：

```
run = [newLogoLeft 340.3–464.8] → [newLogoRight 464.6–495.9]
下一个 oldLogo 右边界 262.7
若与"上一个元素"(newLogoRight) 比较：
   容差 = 2 × 31.3 = 62.6 pt   <   实际间距 464.6 − 262.7 = 201.9 pt  → run 断裂
若与"累积并集"(340.3–495.9, 短边 54.4) 比较：
   容差 = 2 × min(54.4, 50.2) = 100.4 pt  ≥  340.3 − 262.7 = 77.6 pt  → 合并 ✅
```

改为维护 `runBox`（run 的累积并集）参与判定后一次通过。

## 三、修复落点

### 3.1 改动文件清单

| 文件 | 改动 |
|---|---|
| `processors/GraphicRegionProcessor.java` | 碎片聚类：`clusterFragments` / `envelopeVetoReason` / `overlapsImage` / `unionBox` / `fragmentsTouch` / `passesGeometryGates`；`captureRegion` 签名改为接收 `List<LineArtChunk>` |
| `processors/ConsecutiveImageProcessor.java` | 新增水平相邻判定（相对阈值）；run 扩展改用累积并集 `runBox` |
| `test/.../GraphicRegionProcessorTest.java` | **新建**（该类原先无单测），4 个用例 |
| `test/.../ConsecutiveImageProcessorTest.java` | 新增 2 个用例，1 个用例改用真实裁切标记几何 |

### 3.2 GraphicRegionProcessor：碎片聚类

新增常量：

| 常量 | 值 | 依据 |
|---|---|---|
| `FRAGMENT_GAP_MIN` | 6.0 pt | 实测旧 logo 碎片间距 2.4 – 3.1 pt，取一倍余量作下限 |
| `FRAGMENT_GAP_RATIO` | 0.25 | 大标记到 wordmark 的距离可以比两个小字形之间更宽；容差 = `max(6.0, 0.25 × 较小短边)` |
| `IMAGE_VETO_MIN_OVERLAP_SHARE` | 0.05 | envelope 专属闸门，见下 |

主流程（`processGraphicRegions`）：

1. 候选 `LineArtChunk` 按 topY 降序，`clusterFragments` 逐个尝试并入已有簇的 envelope。
2. **增长拒绝**：并入后若 `passesGeometryGates`（尺寸 / 长宽比 / 页面占比）不再成立就拒绝，
   这是防止"整页表格线本来就是一条互相接触的长链"被折成一个跨页区域的关键。
3. 多碎片簇**先判 envelope**（`envelopeVetoReason` = 常规闸门 + `overlapsImage`）；
   通过则整簇一次截图。
4. envelope 被否决 → **回退逐成员判定**，被否决的原因记进 `skipped` 日志（带 `cluster of N:` 前缀）。
   这条回退保证"今天已能捕获的区域不会因为聚类而丢失"。
5. envelope 比单 box 多一道 `overlapsImage`：envelope 是**人为放大**出来的框，
   必须禁止侵入既有 `ImageChunk`，否则邻居位图会被截两遍（单 box 不需要，它不是长出来的）。

### 3.3 ConsecutiveImageProcessor：水平闸门 + runBox

| 常量 | 值 | 依据 |
|---|---|---|
| `MIN_HORIZONTAL_GAP` | 5.0 pt | 下限：框太小时，短边说明不了问题 |
| `MAX_HORIZONTAL_GAP_RATIO` | 2.0 | 容差 = `max(5.0, 2.0 × min(shortSide(a), shortSide(b)))`；2.0 恰好落在实测 1.5（该合）与 37（不该合）之间 |

判定改为 `areClose(runBox, next)`，`runBox` 是 run 的**累积并集**（垂直方向同样用它，语义一致且低风险）。

### 3.4 单测覆盖

`GraphicRegionProcessorTest`（4/4）：

- `capturesIndividuallyVetoedFragmentsAsOneLockup` —— 用目标文件真实几何，断言产出 1 张
  `[92.1, 495.4, 262.7, 545.6]`，三碎片与下划线均被移出 `pageContents`
  （注：测试里的下划线取 `[92.5, 496.8, 135.6, 502.1]`，是真实框严格内嵌的近似值；
  真实框左边界与区域并集完全相等，触到第六节记录的 `contains` 边界问题）
- `judgesDistantFragmentsSeparately` —— 超容差不聚类
- `fallsBackToIndividualJudgementWhenTheEnvelopeReachesAnImage` —— envelope 撞图时回退
- `capturesASingleDenseFragment` —— 单碎片路径不回归

`ConsecutiveImageProcessorTest`（8/8），新增：

- `mergesSideBySideLogosOfOneComparison` —— 真实 3 chunk，断言合成 1 张 `[92.1, 495.4, 495.9, 550.1]`
- `keepsDistantSideBySideImagesSeparate` —— 真实裁切标记几何，断言 0 张截图

## 四、实跑验证数据

### 4.1 目标样本

探针：

```
ImageChunk bbox=[92.1, 495.4, 495.9, 550.1] w=403.8 h=54.6
```

JSON 第 2 页：

```
5 image  id=6  y=[291.82, 346.45]  x=[92.13, 495.93]  mt=19.216  -> imageFile8.png
```

| | before | after |
|---|---|---|
| page 2 image 数 | 1（只有右 logo） | **1（完整对比图，含左 logo）** |
| 左 logo | 丢失 | 森馬 RIMBACO 完整，肉眼核对无裁切 |
| 顺序 / 负 margin_top | 中间态出现过 | 合并后自然消失 |
| 全文本条目数 | 23 | 23（仅一处 `margin_top` 21.784 → 21.536，截图框变化的正常联动） |

### 4.2 单测

```
GraphicRegionProcessorTest      4/4 PASS
ConsecutiveImageProcessorTest   8/8 PASS
全模块 mvn -o -pl opendataloader-pdf-core test:
  改动后  Tests run: 881, Failures: 42, Errors: 26, Skipped: 1
  基线    Tests run: 875, Failures: 42, Errors: 25
  +6 = 新增用例数；失败集合完全一致
```

唯一差异 `Issue408IntegrationTest` +1 error，报错是
`FileSystemException: ...input.pdf 另一个程序正在使用此文件`（Windows 临时目录删除抖动），
单独重跑 `Tests run: 2, Failures: 0, Errors: 0` ✅，与本次改动无关。

### 4.3 77 个真实 PDF 回归扫描

harness：`TmpRegionSweep.java`（给 `GraphicRegionProcessor` 的 Logger 挂自定义 Handler），
按体积升序遍历 `docs/pdf`、跳过 >3MB，逐文件输出 `BEGIN / REGION / END / SKIP / ERROR`。
A/B 做法：`git stash` 目标文件 → 跑 before → `git stash pop` → 跑 after → 对比截图数与矢量捕获数。

**第一轮**（聚类 + 绝对 5pt 阈值 vs 全基线）：

- 矢量区域捕获：**只有目标文件 +1，全库零丢失**
- 截图数量：只有 2 个文件变化 —— 目标 7 → 8；`202504241785136802170055037` 9 → 8

**第二轮**（隔离验证 `ConsecutiveImageProcessor` 的相对阈值 + runBox，两边都带聚类）：

- 矢量区域捕获：**0 个文件变化**
- 截图数量：只有 `202504241785136802170055037` 9 → 8
- 目标文件 8 张截图与 before2 **逐字节大小完全一致**（说明 runBox 改动没有改变本样本结果，
  只是把"该合的仍能合、不该合的不再合"这条规则显式化）

被去掉的那张 `imageFile9.png`（345 bytes）经肉眼核对是
**550 × 14 pt 的横带：左右两端各一个裁切标记，中间大片空白** —— 老逻辑误合并的产物，
不是页面内容。用户确认该外溢"可接受"。

## 五、踩坑与经验教训

1. **"未识别即丢弃"的内容类型，判定粒度必须与 PDF 标记结构可能的拆分粒度对齐。**
   `LineArtChunk` / `ShapeChunk` 不进 JSON，闸门又是逐个 box 判的，
   而 Tagged PDF 会把一个 lockup 的 mark / 字形 / wordmark 各包成一个 marked group。
   三者叠加的结果是"内容静默消失、日志只有一行 skipped"。
2. **几何邻近判定的两个方向都要查。**只查垂直间距时，并排框的 gap 恒为负数、
   永远"看起来是堆叠"，这个检查对并排布局是完全失效的，不是"不够严格"。
3. **阈值要按被判定对象自身尺寸归一化，绝对阈值分不开同类现象。**
   77.6 pt 与 521.6 pt 的绝对值都算"大"，除以短边后是 1.5 与 37，一眼分开。
4. **run / 区间扩展类逻辑，判定基准要用累积并集，不能用上一个元素。**
   本例右 logo 本身是两片，末片只有 31.3 pt 宽，用它当基准直接把 run 截断 ——
   这是改完相对阈值后仍然失败的唯一原因，靠"结果还是两张图"这个现象 + 探针打数值才挖出来。
5. **对照样本要主动找反例，不能只看目标。**裁切标记那张是扫描全库时"意外多出来的一处变化"，
   顺着它才把绝对阈值方案否掉。
6. **A/B 必须自己重跑基线，并且要能隔离单个文件的贡献。**
   本次两轮扫描用 `git stash push -- <单个文件>` 分别隔离 `GraphicRegionProcessor`
   与 `ConsecutiveImageProcessor`，才能说清"9 → 8"是哪个改动带来的。
7. **回归验证要包含"零丢失"方向。**除了看新增，还要断言既有捕获数不减少 ——
   envelope 优先 + 否决回退逐成员，就是为这条专门设计的。

## 六、遗留与已知边界

- `captureRegion` 里 `box.contains(shapeBox)` 对**边界恰好相等**的 `ShapeChunk` 返回 false，
  导致 `[92.1, 497.2, 135.2, 502.5]` 这条下划线未被一并移除。
  因 `ShapeChunk` 不进 JSON，**无输出影响**，属既有的边界严格性问题，本次未动。
- `DocumentProcessor.sortPageContents` 无"同行容差"，同一行的多个块仍可能按 topY 倒序。
  本次靠合并消掉了症状，排序规则本身未改。
- `DebugSample1.java` 的样本路径已指向本目标 PDF（用户本次调查前设置），提交前需自行决定是否还原。

## 七、复现命令

本次的探针与脚本（`TmpPage2Probe.java`、`TmpRegionSweep.java`、`.agents\*.ps1` / `*.py`、
`.agents\sweep|probe|baseline-bak`）已按用户要求全部清理，以下为仍然有效的部分：

```powershell
# 编译（离线）
cd D:\Code\JavaCode\opendataloader-pdf\java
mvn -o -pl opendataloader-pdf-core compile -DskipTests

# 端到端（DebugSample1 内已配置目标 PDF）
$cp = (Get-Content "java\cp.txt" -Raw).Trim()      # cp.txt 为既有 maven classpath 基建
java -Dfile.encoding=UTF-8 -cp "java\opendataloader-pdf-core\target\classes;$cp" `
  org.opendataloader.pdf.DebugSample1
# 产物：tmp_output\202609021788351369223042914.json 与 _images\imageFile8.png

# 相关单测
mvn -o -pl opendataloader-pdf-core test -Dtest=GraphicRegionProcessorTest
mvn -o -pl opendataloader-pdf-core test -Dtest=ConsecutiveImageProcessorTest

# 全量（注意：会重写 samples/json/lorem.*，跑完 git checkout -- 还原）
mvn -o -pl opendataloader-pdf-core test
```

**PowerShell 环境陷阱**（本次多次踩到，命令一律落地成 `.ps1` 再执行）：

- 内联命令里的双引号会被剥掉，`python -c "..."`、`Write-Host ("a" + $b)` 全部被拆坏
- 5.1 不支持 `&&` 作分隔符；`-Dtest=A,B` 会被当成自身参数列表（`+` 也不是合法分隔符）
- `... | Select-Object -ExpandProperty Name` 在该 shell 的管道对象上报"找不到属性"，改用 `ForEach-Object { $_.Name }`
- `Handler.formatMessage(LogRecord)` 不存在，自定义 LogHandler 要用 `record.getMessage()` + `Arrays.toString(record.getParameters())`

## 八、相关旧文档

- `docs/memory/2026-10-08-logo矢量图形区域截图缺失-GraphicRegionProcessor新增与根因定位.md`
  —— **同一处理器的直接前序**：五道闸门的标定过程、`ignoreMCIDs` 下 `LineArtChunk` 的粗粒度成因。
  本次的"碎片聚类"是对它那套单 box 闸门的补丁。
- `docs/memory/2026-09-22-柱状图截图吞并正文并重复-根因定位.md`
  —— 截图与文字层重复的同类问题，本次 `overlapsImage`（envelope 禁侵入位图）是同一条经验的延伸。
- `docs/memory/2026-09-24-PDF叠字重复绘制去重removeOverprintedTextChunks根因定位与修复.md`
- `docs/memory/2026-09-15-流程图识别三问题-根因定位与修复.md`
  —— `MAX_ASPECT_RATIO` 等常量的出处，聚类时复用的就是这套闸门。
