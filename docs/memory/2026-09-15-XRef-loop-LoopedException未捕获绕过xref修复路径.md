# opendataloader-pdf 任务记忆 — 2026-09-15（`XRef loop` LoopedException 未捕获导致 xref 修复路径失效）

- 时间：2026-09-15
- 任务：修复 `DocumentProcessor.preprocessing()` 在 veraPDF 抛 `LoopedException`（unchecked）时绕过已有 PDFBox xref 修复路径、原始堆栈直接泄漏到调用方的问题。
- 状态：已实现、编译通过、原 reproduce PDF（`202609121789116433707014605.pdf`）走修复路径成功产出 JSON，临时文件无残留。
- 前情：2026-08-17 已实现 "Pages not found" xref 损坏自动修复（见 `docs/memory/2026-08-17-Pages-not-found-xref损坏PDF自动修复.md`），本次是同一修复路径覆盖不全的漏网 case。

## 目标（Goal）

- 用户跑 `DebugSample1` 处理 `docs/pdf/202609121789116433707014605.pdf` 时，控制台直接打印 veraPDF 内部堆栈（未经 `InvalidPdfFileException` 包装）：

```
	at org.verapdf.parser.PDFParser.getXRefInfo(PDFParser.java:582)
	at org.verapdf.parser.PDFParser.getXRefInfo(PDFParser.java:243)
	at org.verapdf.io.Reader.init(Reader.java:175)
	at org.verapdf.io.Reader.<init>(Reader.java:59)
	at org.verapdf.cos.COSDocument.initReader(COSDocument.java:127)
	at org.verapdf.cos.COSDocument.<init>(COSDocument.java:93)
	at org.verapdf.pd.PDDocument.<init>(PDDocument.java:67)
	at org.opendataloader.pdf.processors.DocumentProcessor.preprocessing(DocumentProcessor.java:979)
	at org.opendataloader.pdf.processors.DocumentProcessor.extractContents(DocumentProcessor.java:279)
	at org.opendataloader.pdf.processors.DocumentProcessor.processFileWithResult(DocumentProcessor.java:179)
	at org.opendataloader.pdf.api.OpenDataLoaderPDF.processFile(OpenDataLoaderPDF.java:49)
	at org.opendataloader.pdf.DebugSample1.main(DebugSample1.java:41)
```

- 疑点：上一版修复已在 `new PDDocument(pdfName)` 外包了 `catch (IOException)` → PDFBox 修复分支，为什么这个异常漏过去了？
- 期望：xref 链损坏类异常全部走 PDFBox 重建修复；其余异常仍走 `InvalidPdfFileException` 友好报错；不引入新配置开关。

## 定位过程

### 第 1 步：确认现有 catch 结构

读 `DocumentProcessor.preprocessing()`（当时第 974–1003 行）：

```java
try {
    pdDocument = new PDDocument(pdfName);
} catch (InvalidPasswordException pw) {
    throw pw;                       // 加密 PDF 交给上层密码处理
} catch (IOException cause) {
    File repaired = tryRepairPdfWithPdfBox(pdfName, cause);  // 仅 "Pages not found" 放行
    ...
}
```

→ 如果异常是 `IOException`，不可能漏到 `DebugSample1`（要么修复成功，要么抛 `InvalidPdfFileException`）。**唯一解释：这个异常不是 IOException 子类。**

### 第 2 步：反编译 veraPDF 1.31.99 确认第 582 行抛的是什么

本地无 veraPDF parser 源码仓库（`D:\Code\JavaCode2` 只有 validation / wcag-algs），`.m2` 默认路径下也找不到 jar，于是从项目 shaded jar 取类文件：

```powershell
jar xf java/opendataloader-pdf-cli/target/opendataloader-pdf-cli-0.0.0.jar org/verapdf/parser/PDFParser.class
javap -p -l -c org.verapdf.parser.PDFParser
```

私有方法 `getXRefInfo(List, Set<Long>, Long)` 的 LineNumberTable 显示 **line 582 对应 offset 68**，该处字节码：

```
68: new           #659   // class org/verapdf/exceptions/LoopedException
79: invokespecial #663   // LoopedException."<init>":(String)
82: athrow
```

即第 582 行是 `throw new LoopedException(getErrorMessage("XRef loop"))` —— 当从 `/Prev` / `/XRefStm` 栈里弹出的偏移量已被访问过（Set contains）时抛出，属于 xref 链成环检测。

### 第 3 步：确认 LoopedException 的继承体系

```powershell
jar xf ... org/verapdf/exceptions/LoopedException.class org/verapdf/exceptions/VeraPDFParserException.class
javap org.verapdf.exceptions.LoopedException
javap org.verapdf.exceptions.VeraPDFParserException
```

结果：

```java
public class org.verapdf.exceptions.LoopedException extends java.lang.RuntimeException
public class org.verapdf.exceptions.VeraPDFParserException extends java.lang.RuntimeException
```

**根因确认**：`LoopedException` 是非受检异常，直接绕过 `catch (IOException)`，未经修复分支和 `InvalidPdfFileException` 包装，以原始堆栈泄漏。另外注意 `LoopedException` 与 `VeraPDFParserException` 是平级关系（都直接继承 RuntimeException），不能靠 catch 父类 `VeraPDFParserException` 兜住它。

顺带厘清堆栈中两处 `getXRefInfo` 帧：243 是公有重载（`throws IOException`，把 xref 解析委托给私有递归方法），582 是私有递归方法内部的 throw——所以堆栈看起来像"自己调自己"。

### 第 4 步：验证修复手段对该异常同样适用

该 PDF 的 xref 链在偏移量 84584 处被重复访问。这类"重复 Prev 指针 / 重复 XRefStm 偏移"属于链式结构问题，PDF 正文对象完好，PDFBox 宽松解析会忽略重复并照常重建页面树；`boxDoc.save()` 序列化时写入全新的线性 xref，环自然消失。与 "Pages not found" 的修复原理完全同源 → 复用同一修复路径即可，不需要新机制。

### 第 5 步：确定改动范围（窄门原则）

- `catch (IOException)` 扩为 `catch (IOException | LoopedException)`：只多兜 veraPDF 这一种已确认的 xref 环异常，不用 `catch (RuntimeException)`  broad 兜（避免掩盖 veraPDF 真正的内部 bug）。
- `tryRepairPdfWithPdfBox(String, IOException)` 签名放宽为 `(String, Throwable)`，放行条件 `"Pages not found".equals(msg) || cause instanceof LoopedException`：保持"只对 xref 类损坏重存"的窄门，截断 / startxref 缺失 / 加密错误等仍然原样抛 `InvalidPdfFileException`。
- 修复后重试 `new PDDocument(repaired...)` 的 catch 扩为 `catch (IOException | RuntimeException)`：若修复副本仍触发 LoopedException 等未受检异常，删掉临时文件并抛原始 cause 的 `InvalidPdfFileException`，而不是让二次异常泄漏或留下临时文件（`closePdfResources` 虽有兜底清理，但此处显式删除语义更一致）。

## 根本原因（Root Cause）

1. 直接原因：该 PDF 的 xref 链（`/Prev` / `/XRefStm`）重复指向偏移量 84584，veraPDF 的 `PDFParser.getXRefInfo` 环检测抛出 `LoopedException`；因其继承 `RuntimeException`，穿过 `preprocessing()` 的 `catch (IOException)` 修复分支，原始堆栈直达 `DebugSample1`。
2. 设计缺口：2026-08-17 的 xref 自动修复只覆盖了受检路径（`IOException "Pages not found"`），未考虑 veraPDF 会用非受检异常报告 xref 链问题——**修复入口按异常类型划分，而 veraPDF 对同类（xref）问题混用 checked/unchecked 两种通道**。

## 已实现方案

### 改动（`DocumentProcessor.java`，均在 `preprocessing` / `tryRepairPdfWithPdfBox` 附近）

#### 1. 新增 import

```java
import org.verapdf.exceptions.LoopedException;
```

#### 2. `preprocessing()` catch 块扩展

```java
} catch (IOException | LoopedException cause) {
    // 注释更新：说明两类症状——"Pages not found"（checked）与
    // LoopedException "XRef loop"（unchecked，xref 链重复访问同一偏移）
    File repaired = tryRepairPdfWithPdfBox(pdfName, cause);
    if (repaired != null) {
        try {
            pdDocument = new PDDocument(repaired.getAbsolutePath());
            LOGGER.log(Level.WARNING, /* 与原来相同的修复提示 */);
        } catch (IOException | RuntimeException retryCause) {
            // RuntimeException 纳入：veraPDF 以 unchecked 报告 xref 链问题，
            // 修复副本若仍触发，同样删临时文件、抛原始 cause 的友好错误
            deleteRepairedPdfTempFile();
            throw new InvalidPdfFileException(
                "'" + displayName(pdfName) + "' is not a valid PDF file (corrupted or truncated content).",
                cause);
        }
    } else {
        throw new InvalidPdfFileException(/* 同原来 */);
    }
}
```

#### 3. `tryRepairPdfWithPdfBox` 放行条件扩展

```java
private static File tryRepairPdfWithPdfBox(String pdfName, Throwable cause) {
    boolean xrefChainFailure = "Pages not found".equals(cause.getMessage())
        || cause instanceof LoopedException;
    if (!xrefChainFailure) {
        return null;   // 保持窄门：截断 / startxref 缺失 / 加密等不重存
    }
    // 以下逻辑（PDFBox 加载 → createTempFile → save → 注册 repairedPdfTempFile）不变
    ...
}
```

### 关键决策（Key Decisions）

| 决策 | 选项 | 选定 | 理由 |
|---|---|---|---|
| 捕获范围 | (a) `catch (RuntimeException)`；(b) `catch (IOException \| LoopedException)` | (b) | veraPDF 还可能因自身 bug 抛 NPE 等 RuntimeException，广兜会掩盖真实问题；只兜已确认的 xref 环异常 |
| 修复放行条件 | (a) 所有 IOException 都重存；(b) `"Pages not found" \|\| LoopedException` | (b) | 与 2026-08-17 的窄门原则一致：重存只对 xref 类损坏有效，其他损坏重存无意义且延迟暴露 |
| 重试失败的 catch | (a) 仅 IOException；(b) `IOException \| RuntimeException` | (b) | 修复副本仍抛 LoopedException 时，保证删临时文件 + 抛原始 cause 的 `InvalidPdfFileException`，不让二次异常泄漏（本次 bug 正是泄漏问题，修复路径内部不应再留同类缺口） |
| 签名兼容性 | `tryRepairPdfWithPdfBox(String, IOException)` → `(String, Throwable)` | 已改 | 私有方法，无外部调用方；放宽为 Throwable 同时容纳两种 cause |

## 验证（Verification）

### 编译

- IntelliJ 增量编译 `DocumentProcessor.java` → 成功，无告警。
- `mvn -q -pl opendataloader-pdf-core compile -DskipTests` → 成功。

### 行为验证（复现 PDF 全流程）

```powershell
mvn -pl opendataloader-pdf-core org.apache.maven.plugins:maven-dependency-plugin:3.6.1:build-classpath ...
java -cp "opendataloader-pdf-core\target\classes;<deps>" org.opendataloader.pdf.DebugSample1
```

修复前：`LoopedException` 堆栈直达 `DebugSample1.main`。
修复后日志：

```
警告: veraPDF could not parse '202609121789116433707014605.pdf' because its
xref table was rejected (XRef loop(offset = 84584)). PDFBox rebuilt the xref
on the fly; processing continues from the repaired copy at
'C:\Users\HW\AppData\Local\Temp\opendataloader-pdf-repaired-....pdf'.
...
信息: Created ...\tmp_output\202609121789116433707014605.json
信息: Created ...\tmp_output\202609121789116433707014605_ocr.json
信息: ... - extraction cost 1.9 s, generating outputs cost 1.5 s, total cost 3.4 s.
```

→ 修复路径触发，JSON / OCR JSON 均正常产出。

### 临时文件清理验证

```powershell
Get-ChildItem $env:TEMP -Filter "opendataloader-pdf-repaired-*"
# 无结果 ← closePdfResources 已清理，无堆积
```

### 回归面

- 正常 PDF 不经过 catch 分支，零影响。
- `InvalidPasswordException` 仍最先捕获、原样上抛，密码处理流程不变。
- 截断 / startxref 缺失类 IOException 消息不等于 "Pages not found" 且非 LoopedException，仍直接抛 `InvalidPdfFileException`，窄门未被意外放宽。

## 与现有设计的关系

- 本次是 `docs/memory/2026-08-17-Pages-not-found-xref损坏PDF自动修复.md` 的**覆盖补全**：同一修复机制（PDFBox 重存重建 xref + `repairedPdfTempFile` 清理），仅把入口从 checked IOException 扩到 unchecked `LoopedException`。
- 临时文件清理沿用既有双保险：`repairedPdfTempFile` 静态字段 + `closePdfResources()` 主动删 + `deleteOnExit()` 兜底，未改动。

## 相关文件

- 修改：`java/opendataloader-pdf-core/src/main/java/org/opendataloader/pdf/processors/DocumentProcessor.java`
  - 新增 import `org.verapdf.exceptions.LoopedException`
  - 修改：`preprocessing()` catch 块（`IOException` → `IOException | LoopedException`，注释同步更新）
  - 修改：`preprocessing()` 修复重试 catch（`IOException` → `IOException | RuntimeException`）
  - 修改：`tryRepairPdfWithPdfBox(String, IOException)` → `(String, Throwable)`，放行条件增加 `cause instanceof LoopedException`，javadoc 同步更新
- 验证用样例：`docs/pdf/202609121789116433707014605.pdf`（xref 链在 offset 84584 处成环）
- 分析手段：shaded jar（`opendataloader-pdf-cli-0.0.0.jar`）中 `org/verapdf/parser/PDFParser.class`、`org/verapdf/exceptions/LoopedException.class` 的字节码反编译（`javap -p -l -c`）
