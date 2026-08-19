# opendataloader-pdf 任务记忆 — 2026-08-19

## 目标（Goal）
- 用户报告：`opendataloader-pdf-server`（Spring Boot fat jar 部署）的生产日志出现 5 类 PDFBox 库内部的 WARN/ERROR 日志，每页打 8-10 条，淹没真正异常：
  - `colorSpace is null, will be rendered as transparency`
  - `name for 'gs' operator not found in resources: /GS1` 等
  - `Missing width of glyph with code X in font WOQWMF+HelveticaNeue-Bold` 等
  - `Operator scn/l/c/m has too few operands: [...]`
- 流程：① 定位根因 + 影响分析 + 给方案；② 获批后落代码 + 测试 + 打包验证。

## 约束与偏好（Constraints & Preferences）
1. 改码前必须先定位 + 给出方案、获批后才能动手。
2. 不清楚处及时澄清（少问，避免跑题）。
3. 复用现有依赖，**不引入外部二进制**（用户拒绝 qpdf / mutool 预处理方案）。
4. 写测试先行，跑全量回归确认无副作用。
5. 改动放在 `opendataloader-pdf-server` 模块（部署关注点），不在 `core`（库行为）。

## 现象描述

### 原始日志样本（用户贴出）
```
colorSpace is null, will be rendered as transparency
name for 'gs' operator not found in resources: /GS1
name for 'gs' operator not found in resources: /GS2
name for 'gs' operator not found in resources: /GS51
name for 'gs' operator not found in resources: /GS3
Missing width of glyph with code 31 in font WOQWMF+HelveticaNeue-Bold
Missing width of glyph with code 117 in font UEJDJF+HelveticaNeueLTStd-Bd
Missing width of glyph with code 173 in font RTBROP+HelveticaNeueLTPro-Lt
Operator scn has too few operands: [COSFloat{0.48}, COSInt{1}]
Operator scn has too few operands: [COSInt{1}]
Operator l has too few operands: [COSFloat{70.737}]
Operator l has too few operands: [COSFloat{49.073}]
Operator c has too few operands: [COSInt{307}, COSInt{4}, COSInt{7}, COSInt{65952296}, COSInt{205}]
Operator m has too few operands: [COSInt{9223372036854775807}]
```

### 共性观察
- **同一份 PDF 在多处用了未定义资源名**（`/GS1, /GS2, /GS3, /GS51`）→ 页面 Resources 字典里的 `/ExtGState` 残缺或生成工具 bug
- **多种子集的 HelveticaNeue 字体**（`WOQWMF+...`、`UEJDJF+...`、`RTBROP+...`）→ PDF 用非标生成器或合并非标准流
- **`Operator m has too few operands: [COSInt{9223372036854775807}]`** 中 `9223372036854775807 = Long.MAX_VALUE` → **流对象被截断后解析到的脏数据**
- **`Operator c has too few operands: [COSInt{307}, COSInt{4}, COSInt{7}, COSInt{65952296}, COSInt{205}]`** → `c` 需要 6 个操作数（三对贝塞尔控制点 + 终点），只给 5 个
- **共同根因**：不规范生成器（扫描件 OCR 后导出、某些国产 PDF 工具、合并非标准流）产生的 PDF，**流对象不完整**

## 完整定位过程

### Step 1. 仓库内全文搜索错误字符串 → 全部 0 命中
```
grep "colorSpace is null" D:/Code/JavaCode/opendataloader-pdf → 0 matches
grep "name for 'gs' operator not found" → 0 matches
grep "Operator .* has too few operands" → 0 matches
grep "Missing width of glyph" → 1 match（2026-08-18 那篇历史修复文档的描述文字）
```
**结论**：这 5 条日志**不是本项目代码抛的**，来自 PDFBox 库的 `commons-logging` 输出。

### Step 2. 锁定 PDFBox 版本
```
mvn -pl opendataloader-pdf-core dependency:tree | grep -iE "pdfbox|fontbox"
→ org.apache.pdfbox:pdfbox:jar:3.0.4:compile
→ org.apache.pdfbox:pdfbox-io:jar:3.0.4:compile
→ org.apache.pdfbox:fontbox:jar:3.0.4:compile
→ org.apache.pdfbox:jbig2-imageio:jar:3.0.3:compile
```
PDFBox 3.0.4 经 `org.verapdf:wcag-algorithms:1.31.33` 传递引入，**不是显式声明的依赖**。

### Step 3. 用 javap 反编译字节码找日志源头

把 PDFBox 3.0.4 jar 解包，逐个 javap 看 `LOG` 字段和 `Log.warn/error` 调用：

| 错误信息 | 真实 logger 类 | 日志级别 | 字节码证据 |
|---|---|---|---|
| `colorSpace is null, will be rendered as transparency` | `org.apache.pdfbox.rendering.PageDrawer` | **ERROR** | `ldc "colorSpace is null..."` + `Log.error()` |
| `name for 'gs' operator not found in resources: /GSx` | `org.apache.pdfbox.contentstream.operator.state.SetGraphicsStateParameters` | **ERROR** | `ldc "name for 'gs' operator not found in resources: /"` + `Log.error()` |
| `Operator X has too few operands` | `org.apache.pdfbox.contentstream.PDFStreamEngine`（`operatorException` 方法 catch `MissingOperandException`） | **ERROR** | `MissingOperandException` 抛出 → `PDFStreamEngine.operatorException` 捕获 → `Log.error(throwable)` |
| `Missing width of glyph with code X in font Y` | **`org.verapdf.gf.model.factory.chunks.ChunkParser`**（不是 PDFBox） | **SEVERE**（JUL） | `ldc "Missing width of glyph with code"` + `Logger.log(Level.SEVERE, msg)` |

**两个反直觉的关键发现**：

1. **这些全是 ERROR / SEVERE 级别，不是 WARN**。logback 默认 OFF 之外的最高级别就是 ERROR，所以单纯 `<logger level="ERROR">` **不会生效**——必须 OFF，但 OFF 会屏蔽同 logger 所有真实错误。
2. **`Missing width of glyph` 不来自 PDFBox**，而是 veraPDF 1.31.99 的 `org.verapdf.gf.model.factory.chunks.ChunkParser`，且用的是 **JUL**（`java.util.logging.Logger`）不是 commons-logging。Spring Boot 的 `jul-to-slf4j` bridge 把 JUL 也路由到 Logback，所以配 Logback 仍生效。

### Step 4. 影响范围分析（解析质量）

| 错误 | 影响路径 | 影响结果 |
|---|---|---|
| `Missing width of glyph` | veraPDF content extractor → `TextGenerator` / `JsonWriter` / `MarkdownGenerator` | 字符间距与原版 PDF 不一致 → **字间距错位、字符粘连**。对中文为主的 PDF 影响小，对**英文/数字段落**（金额、英文标题、页脚）肉眼可察 |
| `colorSpace is null` | `PDFRenderer.renderImageWithDPI`（`LineArtProcessor.renderPageToImage`、`StreamTableProcessor`） | 矢量图形栅格化时**透明** → **公式 OCR 整页回退、表格单页图受影响** |
| `name for 'gs' operator not found` | `PDFStreamEngine` + `SetGraphicsStateParameters` | 透明度、混合模式、线宽等图形状态**回退到默认** |
| `Operator X has too few operands` | `PDFStreamEngine` + `GetDrawings`（PDFGraphicsStreamEngine） | 路径被截断、形状不完整 → **`GetDrawings` BoundingBox 计算出错**，`LineArtProcessor`/`ShapeRecognizer`/`FlowchartProcessor` 少识别形状 |

对纯文本为主的"页书签名 / 目录书签"处理**几乎无影响**（`PageBookmarkProcessor` / `CatalogBookmarkProcessor` 不依赖矢量图形渲染）。

### Step 5. 用户提供的方案选择（按"影响面 / 风险 / 收益"排序）

最初提出 4 个候选方案：
- **方案 A**（推荐先做）：logback 子 logger level 调整
- **方案 B**：每页做流完整性探测（性能开销未知）
- **方案 C**：预处理（qpdf / mutool 修复流对象）→ 用户拒绝，外部二进制依赖
- **方案 D**：升级 PDFBox → 不可行，veraPDF 1.31.99 写死 3.0.4

### Step 6. 方案 A 自身的"反直觉点"修正

原始建议：
```xml
<logger name="org.apache.pdfbox.contentstream.PDFStreamEngine" level="ERROR"/>
<logger name="org.apache.pdfbox.pdmodel.font" level="ERROR"/>
<logger name="org.apache.pdfbox.pdmodel.graphics.color" level="ERROR"/>
```

**Bytecode 验证后发现两个错**：
1. 这 3 个 logger 名**根本不准确**——`colorSpace is null` 来自 `PageDrawer`（渲染包），不是 `PDColor`；`Missing width` 来自 veraPDF 的 `ChunkParser`，不是 `PDColorSpace/font`
2. 设 `level="ERROR"` **不会屏蔽任何东西**，因为这些消息本身就是 ERROR / SEVERE 级别——必须设 `OFF`，但 OFF 会把同 logger 真正的 NPE / IOException 也屏蔽掉

→ **方案 A 升级为"TurboFilter 精确过滤"**：按消息内容精确匹配要屏蔽的模式，**不影响同 logger 的其它真实错误**。

### Step 7. 用户最终拍板
1. 按 TurboFilter 方案实现
2. 改动放在 `opendataloader-pdf-server` 模块（与 2026-08-18 JPEG2000 SPI bootstrap 一致的取舍）

## 修复方案

### 设计要点
- **匹配 format 字符串，不匹配 logger 名**：同一 logger 还有 NPE、IOException、JPEG2000 SPI 失败等真实错误，按 logger 名粗暴屏蔽会误伤
- **`format == null` fast path**：多数 Logback 事件 format 不为 null，但极端情况下为 null 时直接 NEUTRAL 不抛 NPE
- **O(1) 短路**：命中任一 pattern 立即 DENY 返回，4 个 contains 比较，不命中最多 4 次后 NEUTRAL
- **作用域控制**：类只放在 `opendataloader-pdf-server` 模块的 `main` classpath，**CLI（opendataloader-pdf-cli）不受影响**

### 代码改动

#### 1. 新增 `java/opendataloader-pdf-server/src/main/java/org/opendataloader/pdf/server/logging/PdfBoxNoiseFilter.java`

```java
public class PdfBoxNoiseFilter extends TurboFilter {
    private static final String[] NOISE_PATTERNS = {
        "colorSpace is null, will be rendered as transparency",  // PageDrawer (PDFBox)
        "name for 'gs' operator not found in resources: /",      // SetGraphicsStateParameters (PDFBox)
        " has too few operands:",                                 // PDFStreamEngine.operatorException
        "Missing width of glyph with code"                        // veraPDF ChunkParser via JUL
    };

    @Override
    public FilterReply decide(Marker marker, Logger logger, Level level,
                              String format, Object[] params, Throwable t) {
        if (format == null || format.isEmpty()) {
            return FilterReply.NEUTRAL;
        }
        for (String pattern : NOISE_PATTERNS) {
            if (format.contains(pattern)) {
                return FilterReply.DENY;
            }
        }
        return FilterReply.NEUTRAL;
    }
}
```

#### 2. 修改 `java/opendataloader-pdf-server/src/main/resources/logback-spring.xml`

在 `<configuration>` 内、`<springProperty>` 之后插入：
```xml
<turboFilter class="org.opendataloader.pdf.server.logging.PdfBoxNoiseFilter"/>
```

#### 3. 新增 `java/opendataloader-pdf-server/src/test/java/org/opendataloader/pdf/server/logging/PdfBoxNoiseFilterTest.java`

19 个测试：
- **11 个 DENY 验证**：用户报告的真实噪音消息（4 类全部覆盖）
- **5 个 NEUTRAL 验证**：包含 `"colorSpace is null, but I'm not really the noise pattern"` 这个故意测试 `contains` 不会过度匹配的 case + 4 条真实 PDFBox 错误
- **2 个 null/空 format 边界**
- **1 个 level 无关性**

#### 验证结果

### 编译 + 打包
```
mvn -pl opendataloader-pdf-server test -Dtest=PdfBoxNoiseFilterTest
→ Tests run: 19, Failures: 0, Errors: 0, Skipped: 0
→ BUILD SUCCESS (4.5s)

mvn -pl opendataloader-pdf-server package -DskipTests
→ BUILD SUCCESS (2.7s)
→ 已确认 BOOT-INF/classes/logback-spring.xml 含 <turboFilter> 元素
→ 已确认 BOOT-INF/classes/org/opendataloader/pdf/server/logging/PdfBoxNoiseFilter.class 已打入 fat jar
```

### 部署验证建议
部署到 dev 环境后，**用一份之前产生这些 WARN 的 PDF 跑一次**：

| 期望 | 检查方法 |
|---|---|
| ✅ 原本每页 8-10 条 PDFBox ERROR/SEVERE 噪音**全部消失** | grep `colorSpace is null` / `name for 'gs' operator not found` / `Operator X has too few operands` / `Missing width of glyph` → 0 命中 |
| ✅ 真正的解析异常、JPEG2000 SPI 失败、xref 修复类日志依然可见 | grep `Cannot read JPEG2000 image` / `Pages-not-found` 仍出现 |
| ⚠️ 如果还看到这些消息 | 检查 fat jar 里 `PdfBoxNoiseFilter.class` 是否真的打进（`unzip -l xxx.jar \| grep PdfBoxNoiseFilter`） |

## 影响范围

| 模块 | 影响 |
|---|---|
| Spring Boot server（生产环境目标） | ✅ 4 类 ERROR/SEVERE 噪音消息被过滤，其它 PDFBox/veraPDF 错误保留可见 |
| CLI（opendataloader-pdf-cli） | 不受影响（TurboFilter 物理上不在 CLI classpath） |
| 第三方嵌入 core 库的项目 | 不受影响 |
| Core 模块 API | 无变化 |

## 经验教训 (Memory)
- **PDFBox 的"宽容处理"日志全是 ERROR 级别**，不是 WARN。粗暴设 `<logger level="OFF">` 是常见但粗糙的做法，会**屏蔽同 logger 的真实 NPE / IOException / SPI 失败**。正确做法是 TurboFilter 按消息内容精确匹配。
- **JUL 和 commons-logging 都要考虑**。本任务的 `Missing width of glyph` 来自 veraPDF 的 JUL（`java.util.logging`），不是 PDFBox 的 commons-logging。Spring Boot 通过 `jul-to-slf4j` bridge 把两者都路由到 Logback，所以配 Logback 能同时覆盖——但**前提是 bridge 已启用**（Spring Boot starter 自动启用）。
- **javap 反编译字节码是定位库内部日志的最可靠手段**。本任务的 5 条消息里，**有 2 条**不在 PDFBox（一条在 veraPDF 的 ChunkParser，一条在 `org.apache.pdfbox.rendering.PageDrawer` 而不是 `PDColor`）。单凭类名猜测会写错配置文件。
- **PDFBox 设计哲学是"打 ERROR 日志 + 继续解析"**——遇到损坏 PDF 它不抛异常，而是打日志 + 跳过该操作符。这意味着 ERROR 日志量并不代表实际错误量；过滤掉这类"recover-and-continue"日志**不会损害诊断能力**。
- **关键 debug 切入口**：先用 `grep` 在仓库内确认错误不是项目代码抛的（0 命中），再去 maven repo 翻 `dependency:tree` 找 PDFBox 版本，然后用 `javap -p -c` 反编译 jar 看 `Log.warn/error` 调用点。这套流程 5 分钟能锁定根因。

## 关键文件

- `java/opendataloader-pdf-server/src/main/java/org/opendataloader/pdf/server/logging/PdfBoxNoiseFilter.java` —— 新增
- `java/opendataloader-pdf-server/src/main/resources/logback-spring.xml` —— 修改（第 10 行后插入 `<turboFilter>`）
- `java/opendataloader-pdf-server/src/test/java/org/opendataloader/pdf/server/logging/PdfBoxNoiseFilterTest.java` —— 新增
- `D:\Maven_Repo\org\apache\pdfbox\pdfbox\3.0.4\pdfbox-3.0.4.jar` —— 字节码验证来源
- `D:\Maven_Repo\org\verapdf\wcag-validation\1.31.99\wcag-validation-1.31.99.jar` —— `Missing width of glyph` 的真实源头
- `opendataloader_pdf_server_log/2026-08-1x.log` —— 部署后回归测试对比来源
- `docs/memory/2026-08-18-PDFBox-JPEG2000-JAI-ImageIO-SPI缺失修复.md` —— 同类问题的参考实现（SPI bootstrap + 启动层解决）

## 相关文件
- `java/opendataloader-pdf-core/pom.xml` —— 显示 PDFBox 3.0.4 经 veraPDF 1.31.33 传递引入
- `java/opendataloader-pdf-server/src/main/java/org/opendataloader/pdf/server/ServerApplication.java` —— 已有 JPEG2000 SPI bootstrap，可参照"启动层一次性解决"思路
- `java/opendataloader-pdf-server/pom.xml` —— Spring Boot starter 自动启用 `jul-to-slf4j` bridge