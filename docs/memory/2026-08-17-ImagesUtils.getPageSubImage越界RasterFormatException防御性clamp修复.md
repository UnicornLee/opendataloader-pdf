# 2026-08-17 — ImagesUtils.getPageSubImage 越界 → RasterFormatException 防御性 clamp 修复

## 任务背景

线上 Pulsar 消费者抛出：

```
handleReceiveMessage failed, businessId=1785131172233015158_1333488931853148160: Parallel page processing failed (RasterFormatException: (y + height) is outside raster)
```

外层 message 形态说明 `DocumentProcessor` 的 catch 块已正确解包 `ExecutionException` 的 cause（见 `2026-08-12` 那次修复），但 **具体的子异常类型是 `RasterFormatException`，源自 Java AWT** 而非业务 NPE/IO 类。

需要：
1. 确认这条 `RasterFormatException` 的真正代码点
2. 找到根因
3. 给出一个安全的修复，避免单页坏图把整篇文档处理搞挂

## 定位过程

### Step 1：识别异常性质

`RasterFormatException: (y + height) is outside raster` 是 `BufferedImage.getSubimage(x, y, width, height)` 的标准错误，含义：**子区域起点 `y` 加上高度 `height` 超出整张图的高度**。

属于图像像素裁剪越界，与网络/OCR 无关，先排除掉 Paddle、HTTP、IO 这条线。

### Step 2：在仓库里搜 `getSubimage`

```
SearchContentsByRegex: getSubimage
```

命中：
- `D:\Code\JavaCode2\veraPDF-wcag-algs\src\main\java\org\verapdf\wcag\algorithms\semanticalgorithms\utils\ImagesUtils.java` —— veraPDF 自带的裁剪工具
- `D:\Code\JavaCode\opendataloader-pdf\java\opendataloader-pdf-core\src\main\java\org\opendataloader\pdf\hybrid\HancomAIClient.java` —— 项目内的 Hancom AI 调用，已自带 try-catch
- `D:\Code\JavaCode\opendataloader-pdf\java\opendataloader-pdf-core\src\main\java\org\opendataloader\pdf\utils\ImagesUtils.java` —— 项目内的图片写入工具，**未 catch**

### Step 3：读取 veraPDF `ImagesUtils.getPageSubImage` 源码

关键片段（修改前）：

```java
int x = (int) (Math.floor(scaledBBox.getLeftX()));
int y = (int) (Math.ceil(scaledBBox.getTopY()));
int width = getIntegerBBoxValueForProcessing(scaledBBox.getWidth(), 1);
int height = getIntegerBBoxValueForProcessing(scaledBBox.getHeight(), 1);
return renderedPage.getSubimage(x, renderedPage.getHeight() - y, width,  height);
```

坐标流：PDF 点 (72 DPI, y 向上) → 像素 (y 向下) → `getSubimage`。中间依赖 `scaledBBox.cross(pageBBox)` 做了一次几何 clamp。

### Step 4：分析 `cross` 为什么兜不住

`cross` 把 `scaledBBox` 限制在 `pageBBox = (0, 0, renderedPageWidth, renderedPageHeight)` 内。但 clamp 之后再叠加 `floor/ceil/round` 的取整误差：

- `y = ceil(topY)`：上边界向上取整 1 像素
- `height = round(topY - bottomY)`：高度向上取整 1 像素

两个取整方向都是「向外扩张」，叠加后即使 `topY` 正好在页面内，也可能算出 `y + height > renderedPageHeight`。

例：`renderedPageHeight = 1684`，`topY' = 1684.0`，`bottomY' = 0.0` → `y = 1684`，`height = 1684`，`subY = 0`，`0 + 1684 = 1684`，刚好等于不超。但若 `topY' = 1684.2`（因 PDF 高度 841.89pt × 2 = 1683.78 在 PDFBox 渲染成 1684 像素后略有溢出），clamp 到 1684，`ceil(1684) = 1684`，height 再被算成 1685 → 越界。

### Step 5：顺带发现 `dpiScaling` 可能为 null

```java
double dpiScaling = getDpiScalingForPage(bBox.getPageNumber());
```

`getDpiScalingForPage` 返回 `renderDpiForPages.get(pageNumber)`，可能为 null（虽然当前代码路径不会发生，但属于潜在 NPE）。一并防御。

### Step 6：确认调用链能传到线上

`DocumentProcessor.processDocument` 的并行循环里：

```java
if (!config.isImageOutputOff() && (config.isGenerateHtml() || ...)) {
    ImagesUtils imagesUtils = new ImagesUtils();
    imagesUtils.write(contents);   // 写入图片触发 getPageSubImage
}
```

但更常见的是并行 page 循环内 `TextLineProcessor` / `LineArtProcessor` 内部触发的图片保存。`org.opendataloader.pdf.utils.ImagesUtils.createImageFile` 调 `StaticContainers.getImagesUtils().getPageSubImage(imageBox)`，**没有 try-catch**。

而 `ContrastRatioConsumer.calculateContrastRation` 已经 catch 了 `Exception`，所以这条路径不会让异常逃逸到 `Parallel page processing failed`。

## 根本原因

`veraPDF-wcag-algs` 的 `ImagesUtils.getPageSubImage` 在 PDF → 像素坐标转换时，对边界坐标做 `floor/ceil/round` 取整，三个取整方向叠加可能让子区域底部超出渲染图高度。叠加 `scaledBBox.cross(pageBBox)` 的几何 clamp 只能保证几何形状在页面内，无法保证取整后的像素矩形在 raster 内。

触发条件（任一即可）：
1. 混尺寸 PDF：某页 `getPageBoundingBox` 返回零宽高时，文档级别 `modeOfValues` fallback 让 `pageWidths/pageHeights` 与真实渲染尺寸不一致
2. 极端 CropBox / MediaBox：元素 bbox 缩放后接近或超出页面像素边界
3. 并行 worker 共享 `ImagesUtils` 实例，`renderDpiForPages` 状态被覆盖导致 scaling 与渲染图尺寸不匹配

## 修复策略

**方案 A**（采用）：在 `getPageSubImage(BufferedImage, BoundingBox)` 内做三层防御：

1. `dpiScaling == null` → 返回 null + WARNING 日志
2. 像素级 clamp：`x ∈ [0, imageWidth-1]`、`y ∈ [0, imageHeight]`、`width/height` 不超过剩余空间
3. clamp 后 `width<=0 || height<=0` → 返回 null + WARNING 日志
4. `getSubimage` 外层 try-catch `RasterFormatException` 兜底，返回 null + WARNING 日志

为什么不选其他方案：
- **方案 B（调用端 catch）**：要改 `opendataloader-pdf` 的 `ImagesUtils.createImageFile`，能止住异常但**没有真正修复 bug**，坏页会沉默地被跳过，难定位。
- **方案 C（修 fallback）**：只能覆盖混尺寸 PDF 一种触发场景，无法根治「取整叠加越界」的通病。

## 改动清单

### 1. `D:/Code\JavaCode2/veraPDF-wcag-algs/src/main/java/org/verapdf/wcag/algorithms/semanticalgorithms/utils/ImagesUtils.java`

#### 1.1 新增 import
```java
import java.awt.image.RasterFormatException;
```

#### 1.2 `getPageSubImage(BufferedImage, BoundingBox)` 重写核心片段

```java
Double dpiScaling = getDpiScalingForPage(pageNumber);
if (dpiScaling == null) {
    LOGGER.log(Level.WARNING,
            "No DPI scaling found for page {0}; skipping sub-image extraction.", pageNumber);
    return null;
}
int renderedPageWidth = renderedPage.getWidth();
int renderedPageHeight = renderedPage.getHeight();
// ... cross() 之后:

int x = Math.max(0, Math.min((int) Math.floor(scaledBBox.getLeftX()), renderedPageWidth - 1));
int y = Math.max(0, Math.min((int) Math.ceil(scaledBBox.getTopY()), renderedPageHeight));
int subY = renderedPageHeight - y;
int width = getIntegerBBoxValueForProcessing(scaledBBox.getWidth(), 1);
int height = getIntegerBBoxValueForProcessing(scaledBBox.getHeight(), 1);
width = Math.min(width, renderedPageWidth - x);
height = Math.min(height, renderedPageHeight - subY);

if (width <= 0 || height <= 0) {
    LOGGER.log(Level.WARNING,
            "Clamped sub-image has zero size for page {0}; bbox={1}, image={2}x{3}",
            new Object[]{pageNumber, scaledBBox, renderedPageWidth, renderedPageHeight});
    return null;
}

try {
    return renderedPage.getSubimage(x, subY, width, height);
} catch (RasterFormatException e) {
    LOGGER.log(Level.WARNING,
            "getSubimage failed even after clamping for page {0}; bbox={1}, image={2}x{3}, " +
                    "subimage=(x={4}, y={5}, w={6}, h={7})",
            new Object[]{pageNumber, scaledBBox, renderedPageWidth, renderedPageHeight,
                         x, subY, width, height});
    return null;
}
```

同时把 `renderedPage.getRaster().getWidth()` / `.getHeight()` 换成 `renderedPage.getWidth()` / `.getHeight()`，两者数值上等价但语义更清晰，且后续 clamp 直接复用变量。

## 关键决策

### clamp 而不是 throw
线上日志已经能看出坏页来自哪个 `businessId` 哪个 PDF，再加一条 WARNING 即可定位；不抛异常则避免整页处理失败。

### 日志格式
- `{0}` 占位符用 `java.util.logging` 的 `Level.WARNING` + `Object[]` 形式（保持模块 Java 8 风格，不引入 SLF4J）。
- 日志字段：pageNumber、`scaledBBox`（自动用 `BoundingBox.toString()` 输出 `[leftX, bottomY, rightX, topY]`）、图像宽高、clamp 后实际传给 `getSubimage` 的参数。线上排查时一眼能看出 bbox vs 图像边界关系。

### `width <= 0 || height <= 0` 单独打日志
clamp 成功后仍可能为零（如 bbox 在 clamp 后被压扁成一条线），这种情况属于几何退化，不是异常路径，单独提示便于调试。

### `getSubimage` 仍加 try-catch
clamp 是「算对了」，但 `RasterFormatException` 的触发条件内部还有边界检查，万一 `cross` 把 `scaledBBox` 算成非法值（如 topY < bottomY），clamp 之后 width/height 可能非零但仍越界。try-catch 是最后兜底。

## 验证结果

| 步骤 | 命令 | 结果 |
|---|---|---|
| 编译单测 | `mvn clean test -Dtest=ContrastRatioConsumerTests` (在 veraPDF-wcag-algs) | ✅ 通过（exit 0），含 `bbox-outside-page.pdf` / `bBoxWidthZeroValueTest` / `bBoxHeightZeroValueTest` 等边界 case |
| 安装到本地 Maven | `mvn clean install -DskipTests` (在 veraPDF-wcag-algs) | ✅ 新 jar 1.31.33 写入 `~/.m2/repository/org/verapdf/wcag-algorithms/` |
| 重新构建 opendataloader-pdf | `mvn clean install -DskipTests` (在 opendataloader-pdf/java) | ✅ 通过，本地依赖解析走新 jar |

`GetFileProblems` 对修改后的 `ImagesUtils.java` 检查：**✅ No problems found**。

## 修复后线上预期表现

线上 PDF 不再因 `RasterFormatException` 抛出 `Parallel page processing failed`。坏页会输出 WARNING 日志，例如：

```
Clamped sub-image has zero size for page 168; bbox=[..., ..., ..., ...], image=1240x1754
```

或：

```
getSubimage failed even after clamping for page 168; bbox=[..., ..., ..., ...], image=1240x1754, subimage=(x=..., y=..., w=..., h=...)
```

文档处理继续完成，只是对应的图片不会被裁出。后续可在 Kibana 按 WARNING + `ImagesUtils` + 特定页号定位到原始 PDF。

## 部署注意

- `opendataloader-pdf` 通过 Maven 依赖 `org.verapdf:wcag-algorithms:1.31.33` 引入该模块，**版本号未变**。
- 必须先把修改后的 `veraPDF-wcag-algs` `mvn install` 到目标环境使用的 Maven 仓库（本地仓库 / 私服），否则 `opendataloader-pdf` 仍会拉到旧 jar。
- 该模块 Java 8 + `java.util.logging`，无需引入新依赖。

## 同一会话的旁支任务（顺手记录）

任务开始前还有一个日志重构：`StreamTableProcessor.callPaddleWithRetry` 的 4 个 `LOGGER.log` 调用全部用 `String.format` 改写：

- 去掉 `{" + pdfPath + "}` 这种会被 `MessageFormat` 误解析的写法
- 消息正文里附带 `e.toString()`，对「Formatter 不输出 record.getThrown() 栈」的情况兜底
- 仍把异常对象作为第三个参数传入，保留给支持栈输出的 Formatter

文件：`java/opendataloader-pdf-core/src/main/java/org/opendataloader/pdf/processors/StreamTableProcessor.java`，行 49 / 99-103 / 111-114 / 119-123。

## 相关文件

- `D:/Code\JavaCode2/veraPDF-wcag-algs/src/main/java/org/verapdf/wcag/algorithms/semanticalgorithms/utils/ImagesUtils.java` —— 本次修改点
- `java/opendataloader-pdf-core/src/main/java/org/opendataloader/pdf/utils/ImagesUtils.java` —— 项目的图片写入工具，调用 veraPDF 的 `getPageSubImage`，未 catch `RasterFormatException`
- `D:/Code\JavaCode2/veraPDF-wcag-algs/src/main/java/org/verapdf/wcag/algorithms/semanticalgorithms/consumers/ContrastRatioConsumer.java` —— 已 catch 异常，所以这条调用路径不会让异常逃逸
- `java/opendataloader-pdf-core/src/main/java/org/opendataloader/pdf/processors/DocumentProcessor.java` —— 并行页处理循环与 `IOException("Parallel page processing failed", e)` catch 块
- `docs/memory/2026-08-12-Parallel-page-processing-failed根因与ThreadLocal传播修复.md` —— 上一次让 `Parallel page processing failed` 能暴露具体子异常类型的修复，本次复用了它带来的可观测性
- `java/opendataloader-pdf-core/src/main/java/org/opendataloader/pdf/processors/StreamTableProcessor.java` —— 同会话顺手重构的日志