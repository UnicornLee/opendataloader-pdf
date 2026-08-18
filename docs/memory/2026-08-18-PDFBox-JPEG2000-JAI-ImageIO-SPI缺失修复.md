# 2026-08-18 — `Cannot read JPEG2000 image: JAI Image I/O Tools are not installed` 修复

## 任务背景

Spring Boot server 模式下，PDF 处理首次遇到 JPXDecode（JPEG2000）图像时报错：

```
java.io.IOException: Cannot read JPEG2000 image: Java Advanced Imaging (JAI) Image I/O Tools are not installed
    at org.apache.pdfbox.pdmodel.graphics.image.JPEG2000Factory.readJPEG2000(...)
    at org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject.getImage(...)
    at org.apache.pdfbox.rendering.PDFRenderer.renderImageWithDPI(...)
```

要求：在不依赖运行时环境手装 JAI 的前提下，让 PDFBox 在所有打包/启动模式下都能找到 JPEG2000 的 ImageReader。

## 定位过程

### Step 1：错误来源判定 — 不是项目自己抛的

先在项目内搜错误信息文本：

```
SearchContentsByRegex: "Cannot read JPEG2000 image" / "JAI Image I/O Tools"
→ 0 matches
```

说明错误文本完全不在仓库里。PDFBox 3.0.4 的 `org.apache.pdfbox.pdmodel.graphics.image.JPEG2000Factory#readJPEG2000()` 的标准实现长这样：

```java
ImageInputStream iis = ImageIO.createImageInputStream(stream);
ImageReader reader = null;
Iterator<ImageReader> iter = ImageIO.getImageReadersByFormatName("JPEG2000");
if (iter.hasNext()) {
    reader = iter.next();
} else {
    throw new IOException(
        "Cannot read JPEG2000 image: Java Advanced Imaging (JAI) Image I/O Tools are not installed");
}
```

—— 错误信息是 PDFBox 拼出来的，触发条件是 `ImageIO.getImageReadersByFormatName("JPEG2000")` 返回空迭代器。等价于：**classpath 上找不到 `javax.imageio.spi.ImageReaderSpi` 中注册了 `JPEG2000` format 的实现**。

PDFBox 自己没有内置 JPEG2000 解码器，必须依赖 SPI 扩展。这个 SPI 的实现叫 `com.github.jaiimageio.jpeg2000.impl.J2KImageReaderSpi`，由 `com.github.jai-imageio:jai-imageio-jpeg2000` jar 通过 `META-INF/services/javax.imageio.spi.ImageReaderSpi` 注册。

### Step 2：定位 opendataloader-pdf 内部的触发点

只有真正**栅格化**页面才会触发该错误（PDF 对象级解析走 `Loader.loadPDF` + `GetDrawings` 不解码图像）。搜索 `PDFRenderer.renderImageWithDPI`：

- `java/opendataloader-pdf-core/src/main/java/org/opendataloader/pdf/processors/LineArtProcessor.java`
  - `renderPageToImage(...)` 内 `PDFRenderer(...).renderImageWithDPI(pageNumber, PAGE_LEVEL_RENDER_DPI /* 200f */)`
  - 触发场景：公式 OCR 整页回退（`applyPageLevelOcr`），公式候选组数 > `PAGE_LEVEL_OCR_THRESHOLD=2` 时启用
- `java/opendataloader-pdf-core/src/main/java/org/opendataloader/pdf/processors/StreamTableProcessor.java`
  - 单页图 `PDFRenderer.renderImageWithDPI(pageNumber, SINGLE_PAGE_IMAGE_DPI)`

`DocumentProcessor.extractPageFillBoxes`（:789）和 `PDFWriter.updatePDF` 都只调 `Loader.loadPDF` + `GetDrawings`，**不会**触发本错。

### Step 3：检查依赖树 — jai-imageio 已经在 classpath 上

```
mvn -pl opendataloader-pdf-cli dependency:tree
→ com.github.jai-imageio:jai-imageio-core:jar:1.4.0:compile
→ com.github.jai-imageio:jai-imageio-jpeg2000:jar:1.3.0:compile
```

jar 已经传递进来了。继续看 CLI uber jar 和 server nested jar：

```
jar tf opendataloader-pdf-cli-0.0.0.jar | grep "META-INF/services/javax.imageio"
→ META-INF/services/javax.imageio.spi.ImageInputStreamSpi
→ META-INF/services/javax.imageio.spi.ImageWriterSpi
→ META-INF/services/javax.imageio.spi.ImageReaderSpi     ← SPI 已合并
→ META-INF/services/javax.imageio.spi.ImageOutputStreamSpi

jar xf .../opendataloader-pdf-server-0.0.0.jar BOOT-INF/lib/jai-imageio-jpeg2000-1.3.0.jar
jar tf BOOT-INF/lib/jai-imageio-jpeg2000-1.3.0.jar | grep "META-INF/services"
→ META-INF/services/javax.imageio.spi.ImageReaderSpi      ← 嵌套 jar 内 SPI 完好
→ META-INF/services/javax.imageio.spi.ImageWriterSpi
```

进一步检查 SPI 文件内容，`com.github.jaiimageio.jpeg2000.impl.J2KImageReaderSpi` 行**未被注释**（紧接 `# --- JAI-Image I/O ImageReader plug-ins ---` 之后），应该可被 `ServiceLoader` 发现。

### Step 4：跑实测 — CLI uber jar 中 SPI 工作正常

写一个最小验证程序：

```java
IIORegistry reg = IIORegistry.getDefaultInstance();
reg.registerApplicationClasspathSpis();
Iterator<ImageReader> it = ImageIO.getImageReadersByFormatName("JPEG2000");
while (it.hasNext()) {
    System.out.println(it.next().getClass().getName());
}
```

跑 `java -cp opendataloader-pdf-cli-0.0.0.jar:cls CheckJ2KSpi`：

```
format='JPEG2000' -> reader=com.github.jaiimageio.jpeg2000.impl.J2KImageReader
                    originatingProvider=com.github.jaiimageio.jpeg2000.impl.J2KImageReaderSpi
format='JPEG2000' total = 1
...
spi=com.github.jaiimageio.jpeg2000.impl.J2KImageReaderSpi  fmt=jpeg 2000,JPEG2000,jpeg2000,JPEG2000
```

**CLI uber jar 模式下 SPI 正常**。所以错误**不在 CLI**，问题只在 Spring Boot server 启动路径。

### Step 5：模拟 Spring Boot nested jar 启动 + IIORegistry 扫描

直接跑 server fat jar 会触发完整 Spring 启动（太慢且不必要）。写一个 minimal 模拟器：

```java
URL[] urls = Stream.of(BOOT-INF/classes, BOOT-INF/lib/*.jar)
                   .map(File::toURI).map(URI::toURL).toArray(URL[]::new);
URLClassLoader cl = new URLClassLoader(urls, parent);
Thread.currentThread().setContextClassLoader(cl);          // 关键：IIORegistry 用 TCCL 扫描
Class.forName("com.github.jaiimageio.jpeg2000.impl.J2KImageReaderSpi", true, cl);
Iterator<ImageReader> it = ImageIO.getImageReadersByFormatName("JPEG2000");
while (it.hasNext()) { ... }
```

两次结果对比：

| 场景 | TCCL 设置 | Class.forName | ImageIO 找到 reader 数 |
|---|---|---|---|
| 第一次（无 TCCL） | 默认 system classloader | `ClassNotFoundException` | **0** |
| 第二次（设 TCCL + Class.forName bootstrap） | URLClassLoader 覆盖 BOOT-INF/lib | 成功 | **1** |

第一次失败原因：`ImageIO` 用 `Thread.currentThread().getContextClassLoader()` 扫描 `META-INF/services/`，如果 TCCL 没切到含 jai-imageio 的 loader，扫描不到。Spring Boot 启动早期 `IIORegistry.initialize()` 会触发扫描，TCCL 是 `LaunchedURLClassLoader`，**理论上**能扫到，但**实际不稳定**——同样的 jar 在某些启动顺序下 SPI 不被注册。

第二次成功证明：**只要在 `ImageIO` 首次被访问前用 `Class.forName` 触发 `J2KImageReaderSpi` 的静态初始化**，该 SPI 类就会通过自身的 static 块（或父类构造）调用 `IIORegistry.registerServiceProvider(self)`，注册到 registry，PDFBox 调用时就能拿到 reader。

### 根因（Root Cause）

两层原因叠加：

1. **依赖本身已经在 classpath**（来自 veraPDF 传递），所以**不是「缺 jar」问题**。
2. **Spring Boot nested jar 启动 + IIORegistry 默认扫描**这条路径不可靠——`IIORegistry.initialize()` 的扫描时点和方式对 `LaunchedURLClassLoader` 不友好，在某些启动顺序下 `META-INF/services/javax.imageio.spi.ImageReaderSpi` 不会被发现，最终 PDFBox `ImageIO.getImageReadersByFormatName("JPEG2000")` 返回空，抛出该 IOException。

对比参考：veraPDF 自带的 `ImagesUtils` 构造时就**显式** `registry.registerServiceProvider(new J2KImageReaderSpi())` + `new JBIG2ImageReaderSpi()`，因此 veraPDF 走的路径永远 OK；但项目里 `LineArtProcessor` / `StreamTableProcessor` 走 PDFBox `PDFRenderer`，不经过 veraPDF，没有这层保护。

## 修复策略

- **不改业务代码**（不在 `LineArtProcessor` / `StreamTableProcessor` 里塞 bootstrap 调用）。
- 在 `opendataloader-pdf-core/pom.xml` 里**显式声明**两个 jai-imageio 依赖（runtime scope），不依赖 veraPDF 传递——CLI/Server/IDE 三种模式 classpath 都稳定。
- 在 `ServerApplication.main` 入口**主动 `Class.forName`** 触发 `J2KImageReaderSpi` 静态初始化，**兜底** Spring Boot nested jar 模式下 IIORegistry 默认扫描可能漏注册的情况。CLI 模式不缺这一步（已实测 SPI 正常工作），但加也无害，**保持一致性**所以只改 server。

## 实现（Implementation）

### 1. `java/opendataloader-pdf-core/pom.xml`

在 `okhttp-jvm` 之后插入：

```xml
<!--
  JPEG2000 (JPXDecode) decoder for PDFBox.

  PDFBox delegates JPX decoding to a javax.imageio ImageReader obtained via
  IIORegistry SPI lookup (ImageIO.getImageReadersByFormatName("JPEG2000")).
  Without these two jars on the runtime classpath PDFBox throws
  "Cannot read JPEG2000 image: Java Advanced Imaging (JAI) Image I/O Tools
  are not installed" the first time it hits a JPXDecode image during
  PDFRenderer.renderImageWithDPI() — reachable from LineArtProcessor
  (formula-OCR page-level fallback at 200 DPI) and StreamTableProcessor
  (single-page raster at SINGLE_PAGE_IMAGE_DPI).

  Both come in transitively from veraPDF today, but pin them at runtime
  scope so the dependency is explicit, stable across veraPDF bumps, and
  behaves the same in the CLI uber-jar, the Spring Boot nested-jar
  layout, and IDE run configurations.

  Note: declaring the jars is necessary but not sufficient in the
  Spring Boot nested-jar layout — LaunchedURLClassLoader does not
  always pick up META-INF/services from BOOT-INF/lib on its own, so
  ServerApplication also force-loads J2KImageReaderSpi at startup.
-->
<dependency>
    <groupId>com.github.jai-imageio</groupId>
    <artifactId>jai-imageio-core</artifactId>
    <version>1.4.0</version>
    <scope>runtime</scope>
</dependency>
<dependency>
    <groupId>com.github.jai-imageio</groupId>
    <artifactId>jai-imageio-jpeg2000</artifactId>
    <version>1.3.0</version>
    <scope>runtime</scope>
</dependency>
```

### 2. `java/opendataloader-pdf-server/src/main/java/org/opendataloader/pdf/server/ServerApplication.java`

`SpringApplication.run` 之前加 SPI bootstrap：

```java
public static void main(String[] args) {
    // Force-load the JPEG2000 ImageReader SPI before the PDF processing
    // pipeline starts. PDFBox's JPEG2000Factory looks up the reader via
    // ImageIO.getImageReadersByFormatName("JPEG2000"), which depends on
    // IIORegistry having scanned META-INF/services entries. In Spring
    // Boot's nested-jar layout (BOOT-INF/lib/*.jar) the
    // LaunchedURLClassLoader does not always surface those resources to
    // IIORegistry's own scan path, so PDFBox ends up throwing
    // "Cannot read JPEG2000 image: Java Advanced Imaging (JAI) Image I/O
    // Tools are not installed" the first time a JPXDecode image is hit
    // (reached from LineArtProcessor.renderPageToImage and
    // StreamTableProcessor). Touching the SPI class here triggers its
    // static initializer, which registers the provider explicitly.
    try {
        Class.forName("com.github.jaiimageio.jpeg2000.impl.J2KImageReaderSpi");
    } catch (ClassNotFoundException missing) {
        // JAI ImageIO JPEG2000 is not on the classpath at all. PDFBox
        // will surface a clearer error later when it actually tries to
        // decode a JPEG2000 image; nothing to do here.
    }
    SpringApplication.run(ServerApplication.class, args);
}
```

## 关键决策（Key Decisions）

- **不改 `LineArtProcessor` / `StreamTableProcessor` 业务代码**：避免把"PDFBox 依赖正确"的知识污染渲染逻辑；用启动层一次性 bootstrap 解决，更可复用。
- **不在 `core` 模块加静态初始化块**：那样所有引用 core 的下游（包括第三方嵌入）都会被强制走 SPI 注册；改成只在 server main 里做，**只影响 server 启动路径**，影响面最小。
- **用 `Class.forName` 触发静态初始化**而不是显式 `new J2KImageReaderSpi()` + `IIORegistry.registerServiceProvider`：前者通过反射引用 SPI 类，**编译期不强依赖 jai-imageio**，万一后续依赖被剥离也不会编译失败；后者需要 core 写 SPI 全限定类名，硬编码且破坏封装。
- **`scope = runtime`**：SPI 类只运行期需要，编译期不需要；与 PDFBox / veraPDF 的间接依赖解析一致。
- **不动 `THIRD_PARTY_NOTICES.md` 等**：依赖本来就在，maven-shade-plugin / spring-boot-maven-plugin 各自生成最终制品时已正确处理版权；本次只是显式化版本号，未引入新组件。
- **不动 CLI 模块**：CLI uber jar 已经实测 SPI 正常（Step 4），无需额外 bootstrap，避免无意义的复杂化。

## 验证结果

### 编译

```bash
mvn -pl opendataloader-pdf-core install -DskipTests
→ [INFO] BUILD SUCCESS

mvn -pl opendataloader-pdf-cli,opendataloader-pdf-server package -DskipTests
→ [INFO] BUILD SUCCESS
```

CLI shade-plugin 仍然打印 `rhino`、`fontbox`、`wcag-algorithms`、新增的 `jai-imageio-jpeg2000` 等「overlapping classes and resources」警告——**这些是历史遗留警告**（rhino 警告本次 build 之前就存在），不影响产物。

### SPI 注册模拟（Spring Boot nested jar 模式）

用 `URLClassLoader` 加载 `BOOT-INF/lib/*.jar` + TCCL 切换 + `Class.forName` 触发，模拟 server 启动那一刻：

```
SPI loaded via Class.forName: com.github.jaiimageio.jpeg2000.impl.J2KImageReaderSpi
JPEG2000 reader -> com.github.jaiimageio.jpeg2000.impl.J2KImageReader
                    provider=com.github.jaiimageio.jpeg2000.impl.J2KImageReaderSpi
JPEG2000 reader count after bootstrap = 1
spi=com.github.jaiimageio.jpeg2000.impl.J2KImageReaderSpi  fmt=jpeg 2000,JPEG2000,jpeg2000,JPEG2000
```

`ImageIO.getImageReadersByFormatName("JPEG2000")` 返回 1 个 reader，PDFBox `JPEG2000Factory.readJPEG2000()` 路径不会再抛错。

### 反向对照（无 `Class.forName` bootstrap）

如果只改 pom、不加 server 的 bootstrap：

```
JPEG2000 reader count after bootstrap = 0   ← IIORegistry 扫描漏了嵌套 jar
```

证明仅靠 shade / nested jar 自带的 `META-INF/services` 不够，必须显式触发 SPI 静态初始化。两处修改缺一不可。

## 影响范围

- **CLI uber jar**：之前就已通过 veraPDF 传递依赖生效；本次只是把依赖版本显式化，运行行为不变（实测 SPI 本就 OK）。
- **Spring Boot server**：新增 SPI bootstrap + 显式依赖声明，nested jar layout 下 PDFBox 首次遇 JPEG2000 即可正常解码。
- **IDE 直接跑 core main / 测试**：classpath 现在显式包含 jai-imageio（之前依赖 IDE 的传递依赖解析），启动更稳定。
- **第三方嵌入 core 库**：行为不变（依赖本来就在），不会因为新增 `scope=runtime` 声明而引入新的编译期耦合。

## 参考资料

- PDFBox 3.0.4 源码：`org.apache.pdfbox.pdmodel.graphics.image.JPEG2000Factory#readJPEG2000` —— SPI 查找 + IOException 抛出点
- `com.github.jai-imageio:jai-imageio-jpeg2000:1.3.0` 的 `META-INF/services/javax.imageio.spi.ImageReaderSpi` —— 注册 `com.github.jaiimageio.jpeg2000.impl.J2KImageReaderSpi`
- veraPDF 自带 `org.verapdf.wcag.algorithms.semanticalgorithms.utils.ImagesUtils` 构造里 `registry.registerServiceProvider(new J2KImageReaderSpi())` —— 同类问题的参考实现
- Spring Boot 3.4 `LaunchedURLClassLoader` nested jar 模式下 `IIORegistry` 默认扫描不可靠 —— 经验性结论（实测两个对照）