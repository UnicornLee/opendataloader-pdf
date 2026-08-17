# opendataloader-pdf 任务记忆 — 2026-08-17（`Pages not found` xref 损坏 PDF 自动修复）

- 时间：2026-08-17
- 任务：让 `DocumentProcessor.preprocessing()` 在 veraPDF 抛 `IOException("Pages not found")` 时，自动利用 PDFBox 重建 xref 并恢复解析，不再让用户拿到 `InvalidPdfFileException`。
- 状态：已实现、编译通过、原 `DebugSample1` 的 reproduce PDF 走修复路径成功产出 JSON。

## 目标（Goal）

- 用户跑 `DebugSample1` 在 `202304211681992320803737.pdf` 上抛 `InvalidPdfFileException: ... is not a valid PDF file (corrupted or truncated content). Caused by: java.io.IOException: Pages not found`。
- 该 PDF 字节级完整（14,339,547 字节），PDFBox 宽松解析能拿到 92 页；veraPDF 因为 xref 校验严格而失败——属"跨解析器容错"而不是"文件真的损坏"。
- 期望：探测到这个特定症状时，用 PDFBox 重建 xref，复用 veraPDF 继续解析；其他损坏类型保持原行为（仍抛 `InvalidPdfFileException`），避免误伤。
- 不破坏正常 PDF 路径。
- 修复生成的临时 PDF 必须有清理机制（长跑进程不堆积 ~14 MB × N）。

## 报错与现象

```
org.opendataloader.pdf.exceptions.InvalidPdfFileException: '202304211681992320803737.pdf' is not a valid PDF file (corrupted or truncated content).
    at org.opendataloader.pdf.processors.DocumentProcessor.preprocessing(DocumentProcessor.java:895)
    at org.opendataloader.pdf.processors.DocumentProcessor.extractContents(DocumentProcessor.java:264)
    at org.opendataloader.pdf.processors.DocumentProcessor.processFileWithResult(DocumentProcessor.java:165)
    at org.opendataloader.pdf.api.OpenDataLoaderPDF.processFile(OpenDataLoaderPDF.java:49)
    at org.opendataloader.pdf.DebugSample1.main(DebugSample1.java:33)
Caused by: java.io.IOException: Pages not found
```

veraPDF 日志同时出现 3 条 xref 警告：

```
警告: Incorrect xref section(offset = 13073109)
警告: Incorrect xref section(offset = 13799555)
警告: Incorrect xref section(offset = 14135921)
```

## 定位过程

### 第 1 步：复现 + 全栈捕获

写临时 `DebugSample1` 跑同一文件，截到 `Caused by` 帧为止：

```
Caused by: java.io.IOException: Pages not found
    at org.verapdf.pd.PDDocument.checkPages(PDDocument.java:251)
    at org.verapdf.pd.PDDocument.<init>(PDDocument.java:68)
```

→ "Pages not found" 一定来自 `org.verapdf.pd.PDDocument.checkPages()`。

### 第 2 步：反编译 `org.verapdf.pd.PDDocument` 验证

`org.verapdf.parser:parser-1.31.44.jar` 的字节码：

```java
private void checkPages() throws IOException;
  Code:
     0: aload_0
     1: invokevirtual #252  // Method getNumberOfPages:()I
     4: ifne          17
     7: new           #66   // class java/io/IOException
    10: dup
    11: ldc           #255  // String "Pages not found"
    13: invokespecial #257  // <init>(String)
    16: athrow
    17: return
```

`grep` 在所有 `D:\Maven_Repo` jar 里找字符串 "Pages not found"：**0 个匹配**，进一步确认是 veraPDF 内部 literal，"Pages not found" 触发条件唯一入口就是 `getNumberOfPages() == 0`。

`new PDDocument(String)` 在被调用的前后都跑 `checkPages()`，三个构造器一致。

### 第 3 步：判定是文件损坏还是解析器差异

写 `DebugPdfLoading`（调试用，结束后已删）：

- 直接 `Loader.loadPDF(file)` (PDFBox 3.0.4)：`Pages: 92`，正常
- 直接 `new org.verapdf.pd.PDDocument(path)` (veraPDF 1.31)：抛 `Pages not found`
- PDFBox 保存的副本 → 喂给 veraPDF：成功，`Pages: 92` ✅

→ 文件本身有效；问题是 veraPDF 解析 xref 时丢失了页面树；**PDFBox 重新保存可重建 xref**，作为修复手段可行。

### 第 4 步：观察全部 IO 异常类型，确认窄门范围

把另一个 PDF（`202304211681997115596529.pdf`，4.8 MB）截断到首 1024 字节测试"非 xref 损坏"：

```
Caused by: java.io.IOException: Document doesn't contain startxref keyword in the last 1024 bytes
```

→ 与 "Pages not found" 消息不同。"startxref" 这类损坏属于结构/正文损坏，重存无法修复，必须保留原来的 `InvalidPdfFileException` 路径。

### 第 5 步：设计窄门条件

仅当 `cause.getMessage().equals("Pages not found")` 时走 PDFBox 重存；其他 IOException 原样抛出。理由：

- 修复只对 xref 损坏有效（已实测）。
- 盲改（任何 IOException 都重存）会延迟发现真正的损坏，且 `boxDoc.save` 同样可能抛 IOException 再次吞掉原 cause。
- 通过 message 精确匹配可在不引入新配置开关的前提下做到安全激活。

### 第 6 步：清理责任分析

veraPDF `PDDocument.close()` 关闭其内部的 `SeekableInputStream`（parser jar `COSDocument.class` 里 `close()` 调用 `getPDFSource().close()` 等）。临时文件只要 `closePdfResources()` 之后删即可，因此：

- 临时文件 `deleteOnExit()` 兜底应对崩溃。
- 静态字段 `repairedPdfTempFile` 跟踪当前 process 产生的文件，`closePdfResources()` 主动删。
- 静态字段在每次成功 `tryRepairPdfWithPdfBox()` 中被覆盖，在 cleanup 中读取后立即置 null（防止下次 shutdown 误删上一次的文件）。

为什么不直接覆盖原文件（`Files.move(repaired, pdfName, REPLACE_EXISTING)`）：会改变用户磁盘上的字节，下次 CLI 失败用户没法再用其他工具验证；改用 temp 文件保输入完全不破坏。

为什么不直接用 veraPDF `PDDocument(InputStream)`：13 MB 左右 PDFBox 全内存保存再回传会影响内存峰值；走 tmp 文件让 SDK 自己管理 IO。

### 第 7 步：调用链末端确认下游是否仍依赖原 `pdfName`

`preprocessing()` 中后续调用点都是用 `pdfName`（原始名）：

- `StaticContainers.setFileName(pdfName)`：给 `ImagesUtils`、`PDFWriter` 等下游读取；
- `extractPageFillBoxes(pdfName, ...)`：该方法本身就是 `Loader.loadPDF(new File(pdfName))`，对原文件 OK。

下游读路径要么用 `StaticContainers.getFileName()`（持原名）走 PDFBox，要么传 `inputPdfName` 从 CLI 入口传入——都是对原文件可读。所以**修复路径下不需要改任何下游 `pdDocument` 的来源**。

## 根本原因（Root Cause）

`org.verapdf.pd.PDDocument(String)` 构造时通过 `PDFParser.parseXrefTable` 做 xref 严格校验，对损坏的 xref 段降级为"页面树不可解析"，使 `getNumberOfPages() == 0`，再被 `checkPages()` 抛 `IOException("Pages not found")`。原 `DocumentProcessor.preprocessing()` 直接把所有 IOException 包成 `InvalidPdfFileException`，掩盖了"veraPDF 解析失败但 PDFBox 仍能读"这一状况，使用户无法继续处理内容完整的 PDF。

## 已实现方案

### 改动（`DocumentProcessor.java`）

#### 1. 静态字段 + 清理注册

```java
private static File repairedPdfTempFile;
```

`closePdfResources()` 在 PDDocument.close() 后注册清理步骤：

```java
clearCleanupStep("RepairedPdfTempFile", DocumentProcessor::deleteRepairedPdfTempFile);
```

#### 2. `preprocessing()` catch 块接入修复分支

```java
} catch (IOException cause) {
    File repaired = tryRepairPdfWithPdfBox(pdfName, cause);
    if (repaired != null) {
        try {
            pdDocument = new PDDocument(repaired.getAbsolutePath());
            LOGGER.log(Level.WARNING,
                "veraPDF could not parse '" + displayName(pdfName)
                    + "' because its xref table was rejected ("
                    + cause.getMessage() + "). PDFBox rebuilt the xref on the fly; "
                    + "processing continues from the repaired copy at '"
                    + repaired.getAbsolutePath() + "'.");
        } catch (IOException retryCause) {
            deleteRepairedPdfTempFile();
            throw new InvalidPdfFileException(
                "'" + displayName(pdfName) + "' is not a valid PDF file (corrupted or truncated content).",
                cause);
        }
    } else {
        throw new InvalidPdfFileException(
            "'" + displayName(pdfName) + "' is not a valid PDF file (corrupted or truncated content).",
            cause);
    }
}
```

#### 3. `tryRepairPdfWithPdfBox(...)` 私有方法

```java
private static File tryRepairPdfWithPdfBox(String pdfName, IOException cause) {
    if (!"Pages not found".equals(cause.getMessage())) {
        return null; // 只对该特定症状做修复
    }
    Path repairedPath = null;
    org.apache.pdfbox.pdmodel.PDDocument boxDoc = null;
    try {
        boxDoc = Loader.loadPDF(new File(pdfName));
        repairedPath = Files.createTempFile("opendataloader-pdf-repaired-", ".pdf");
        File repaired = repairedPath.toFile();
        repaired.deleteOnExit();
        boxDoc.save(repaired);
        repairedPdfTempFile = repaired; // 注册给 closePdfResources
        return repaired;
    } catch (IOException repairFailure) {
        if (repairedPath != null) {
            try { Files.deleteIfExists(repairedPath); }
            catch (IOException deleteFailure) {
                LOGGER.log(Level.WARNING, "Failed to delete unused repair tmp file: " + repairedPath, deleteFailure);
            }
        }
        LOGGER.log(Level.WARNING, "PDFBox-based repair attempt failed for '" + displayName(pdfName) + "': " + repairFailure.getMessage());
        return null;
    } finally {
        if (boxDoc != null) {
            try { boxDoc.close(); } catch (IOException ignored) { /* best-effort */ }
        }
    }
}
```

#### 4. `deleteRepairedPdfTempFile()` 幂等清理

```java
private static void deleteRepairedPdfTempFile() {
    File toDelete = repairedPdfTempFile;
    repairedPdfTempFile = null;
    if (toDelete == null) return;
    try {
        Files.deleteIfExists(toDelete.toPath());
    } catch (IOException e) {
        LOGGER.log(Level.WARNING, "Failed to delete repaired-PDF temp file: " + toDelete, e);
    }
}
```

### 关键决策（Key Decisions）

| 决策 | 选项 | 选定 | 理由 |
|---|---|---|---|
| 修复触发条件 | (a) 所有 IOException 都重存；(b) 仅 `Pages not found` | (b) | 实测只有 xref 损坏可重存修复；startxref、加密、截断等不会被重存治好——盲改会延迟暴露真正的损坏 |
| 副产物位置 | (a) 覆盖原文件；(b) 临时文件 | (b) | 保留用户原文件可让其他工具继续诊断；`deleteOnExit()` + 主动清理双保险 |
| 临时文件 IO | (a) tmp 文件；(b) `PDDocument(InputStream)` 接 ByteArrayOutputStream | (a) | 13 MB PDF 全内存加载对峰值不友好；`Files.createTempFile` 由 OS 管理磁盘 |
| 重存失败后 cause 选择 | (a) 重抛 `retryCause`；(b) 抛原始 `cause` | (b) | 用户遇到的第一个错误是 xref，retry 的新错误是噪声；保留原始 cause 帮助诊断 |
| cleanup 时机 | (a) 仅 `deleteOnExit`（JVM 退出时）；(b) `closePdfResources()` 主动删 + `deleteOnExit` 兜底 | (b) | 长跑服务端 `deleteOnExit` 不触发会堆积 ~14 MB × N；服务端版本必须做主动清理 |
| 静态字段同步 | (a) 读后立即 null；(b) 保留 | (a) | 上次 process 的文件被本次 shutdown 误删、同一个文件名被覆盖等异常场景都不会发生 |

## 验证（Verification）

### 编译 / 测试

- `mvn -pl opendataloader-pdf-core compile` → BUILD SUCCESS
- `mvn -pl opendataloader-pdf-core test-compile` → BUILD SUCCESS
- `AutoTaggerTest` 4 例 errors、`EmbedImagesIntegrationTest` 5 例 failures —— **与本修复无关**，在 `git stash` 撤掉所有改动后仍同样失败（NullPointerException at `DocumentProcessor.java:560`，源于测试侧 `config.getOutputFolder()` 为 null）。原项目未修，本次顺带未触及。

### 行为验证（手动跑 `DebugSample1` + 临时 `DebugNormal` 后已删）

| 用例 | 修复前 | 修复后 |
|---|---|---|
| `202304211681992320803737.pdf`（出问题的 PDF） | `InvalidPdfFileException` → `Pages not found` | WARNING 提示 `xref rejected`，92 页被解析，产出 `tmp_output\202304211681992320803737.json` (extraction 17.0 s, total 17.6 s) |
| `202304211681997115596529.pdf`（正常 PDF） | OK | OK，没有任何 WARNING 注入（无回归） |
| `D:\tmp_truncated.pdf`（保留 `%PDF-` 的 1024 字节截断文件） | `InvalidPdfFileException` | `InvalidPdfFileException`，cause = `Document doesn't contain startxref keyword in the last 1024 bytes`（窄门未触发，行为一致） |

### 临时文件清理验证

```powershell
Get-ChildItem $env:TEMP\opendataloader-pdf-repaired-*.pdf | Measure-Object
# Count : 0   ← 每次 `processFile` 结束 closePdfResources 都删掉了
```

## 与现有设计的关系

- 已存在 `extractPageFillBoxes()` 用 `Loader.loadPDF(new File(pdfName))` 独立二次加载（解决箭头头 PDFBox 回退），证明 PDFBox 加载路径在流水线中**本来就是一等公民**；本次修复复用同一机制但用于"启动期兜底"，方向一致、不冲突。
- `extractPageFillBoxes()` 仍按 `pdfName`（原名）调用 PDFBox，对原文件可读，所以 fix 路径不需要改它。
- `closePdfResources()` 已有 "RepairedPdfTempFile" 之外的各类 `clearCleanupStep(...)`，新步骤按相同模式加在 PDDocument.close() 之后（必须先 close，PDDocument 才会释放 veraPDF 的 SeekableInputStream 文件句柄）。

## 相关文件

- 修改：`java/opendataloader-pdf-core/src/main/java/org/opendataloader/pdf/processors/DocumentProcessor.java`
  - 新字段：`repairedPdfTempFile`（第 99–106 行）
  - 修改：`closePdfResources()` 注册 `deleteRepairedPdfTempFile`（第 118–122 行）
  - 修改：`preprocessing()` catch 块接入修复分支（第 904–933 行）
  - 新方法：`tryRepairPdfWithPdfBox(String, IOException)`（第 974–1031 行）
  - 新方法：`deleteRepairedPdfTempFile()`（第 1033–1057 行）
- 引用类（已 import）：`org.apache.pdfbox.Loader`（已有，因 `extractPageFillBoxes` 也在用）
- 验证用样例：`docs/pdf/202304211681992320803737.pdf`（92 页、14 MB、xref 损坏）
- 同主题历史相关：`docs/memory/2026-08-13-PDFBox-fill回退坐标转换方向修复.md`、`docs/memory/2026-08-13-表格行分隔线误识别为arrow排除与PDFBox-fill回退失效bug.md`、`docs/memory/2026-08-07-流程图箭头bbox含填充箭头头回退修复.md`（`extractPageFillBoxes` 设计源头，本修复在其上叠加启动期 xref 兜底）
