# 2026-10-12 — 内嵌字体 ToUnicode 把可打印码映射到控制符，致数字渲染成"带对角线矩形"（豆腐块）：精准改写坏条目的根因定位与修复

> 样本：`docs/pdf/200708261781628503910035221.pdf`（國美電器 澄清公佈，股份代號 493，共 2 页）
> 现象：解析结果里多处**数字 `1`/`2`** 在本应显示阿拉伯数字的位置，渲染成**带对角线的矩形**（.notdef / tofu 豆腐块）
> 涉及文件（生产代码 2 个）：
>
> - **新增** `java/.../pdf/utils/ToUnicodeRepair.java`（扫描文档全部字体，精准改写坏 ToUnicode 条目的修复工具）
> - `java/.../pdf/processors/DocumentProcessor.java`（+~100 行：字段、清理步骤、`preprocessing` 接入、两个辅助方法与 import）
>
> 调试入口：`org.opendataloader.pdf.DebugSample1`（已指向目标 PDF，属调试文件）
> 状态：**已编译、已用 DebugSample1 端到端验证通过（控制字符 20→0）；临时修复文件处理后自动清理；尚未 git 提交**
> 本轮 = 现象 → 输出取证（确认控制字符流入 JSON）→ 渲染取证（PDFBox 渲染源 PDF 数字正常，排除"缺字形"）→ 字体取证（dump ToUnicode CMap，锁定 `<31>→<0018>`、`<32>→<0018>`）→ 剥离 ToUnicode 验证修复方向 → 用户澄清决策 → 实现精准改写工具类 → 接入 preprocessing → 端到端验证 → 清理探针

---

## 1. 现象

用户报告 `200708261781628503910035221.pdf` 解析后**多处数字变成了带对角线的矩形符号**。用户要求：

1. 查明原因并提供修复方案；
2. 有不清楚的地方需澄清；
3. 可用 `DebugSample1` 解析/调试（该类已预置目标 PDF），或 IntelliJ Debugger。

肉眼观察：受影响的都是数字 `1` 与 `2`（例如年份 `2007`、`23`、列表序号 `1.` / `2.`、条款号 `8.1` / `4.2`），
而其它数字 `0/3–9` 与中文均正常。

---

## 2. 结论速览（TL;DR）

1. **根因：内嵌子集字体 `RSWKOF+Times-Roman` 的 `/ToUnicode` CMap 损坏**，把可打印 ASCII 码错误映射到 C0 控制字符：
   - `<31> <0018>` → 字符码 `0x31`（即 `'1'`）被映射到 `U+0018`
   - `<32> <0018>` → 字符码 `0x32`（即 `'2'`）被映射到 `U+0018`
2. **veraPDF 与 PDFBox 都优先信任 `/ToUnicode`**，于是忠实提取出控制字符 `U+0018`；控制字符无法被任何渲染器画出，
   落到 .notdef，在 Markdown/HTML 里显示为**带对角线矩形（豆腐块）**。
3. **这是"解码错误"，不是"缺字形"**：用 PDFBox 把源 PDF 直接渲染成 PNG，数字 `1`/`2` 完全正常 →
   证明字形存在、问题纯在文本层的 ToUnicode 解码。此点与 10-12 另一份 Wingdings/PUA 豆腐块问题**性质不同**（那是提取对、下游字体接不住；这是提取本身就错）。
4. **判定坏条目的依据**：可打印区码（0x20–0x7E）永远不该解码成 C0 控制符（<0x20 且非 TAB/LF/CR）；
   WinAnsi/PDFDoc/Standard 等基础编码在 0x20–0x7E 区 `code == unicode`，故此类坏映射的正确目的地就是 identity（回写原码）。
5. **修复方式（用户拍板"精准改写坏条目 + 本仓库 preprocessing"）**：在 `DocumentProcessor.preprocessing()` 里、veraPDF 解析之前，
   用 PDFBox 加载文档 → `ToUnicodeRepair.repairBrokenToUnicode` 只把"可打印码→C0 控制符"的 `bfchar`/`bfrange` 坏条目改写为 identity，
   其余合法映射与块结构原样保留；有改动则存临时 PDF，让 veraPDF 从修复副本解析。全文件 20 处受控字符全部恢复。

---

## 3. 完整定位过程（从问题到根因）

### 3.1 确认"到底是什么字符流进了输出"

`DebugSample1` 已配置为目标 PDF（输出到 `tmp_output/`，`setGenerateMarkdown(true)`）。先离线编译 core 模块并运行产出 JSON。
扫描输出 JSON 的字符分布：全是干净的 CJK，但用正则匹配转义序列 `\u00xx` 时发现 **`U+0018` 出现 20 次**
（控制字符在 JSON 里以 6 字符转义序列 `\u0018` 存储，故按码点直接扫不到，要扫转义形式）。

- 定位受影响片段：`2007→\u0018007`、`23→\u00183`、列表序号 `1./2.`、`8.1→8.\u0018`、`4.2→4.\u0018`。
- 这一步确认问题性质：**输出层带上了 C0 控制字符 `U+0018`**，渲染端画不出→豆腐块。

### 3.2 渲染取证：排除"缺字形"，锁定"解码错"

写 PDFBox 探针把源 PDF 第 1、2 页用 `PDFRenderer` 渲染成 PNG，肉眼核对：**数字 `1`/`2` 渲染完全正常**。

→ 结论：字形存在，PDF 视觉层没问题；错误只发生在**文本提取层的 ToUnicode 解码**。这把方向从"字体缺字/符号映射"收敛到"字体的 Unicode 映射表坏"。

### 3.3 字体取证：dump ToUnicode CMap（锁定根因）

`TmpDigitProbe` 先证实提取出的 DIGIT 列表里**没有 `1`、`2`**（被替换成了控制符）。
再用 `TmpFontProbe` 遍历页面资源字体、打印其 `/ToUnicode` CMap，直接读出坏映射：

```
beginbfchar
<31> <0018>
<32> <0018>
endbfchar
```

即字符码 `0x31`（`'1'`）、`0x32`（`'2'`）被错误映射到 `U+0018`。其余数字（`0`、`3`–`9`）映射正常，故只有 `1`/`2` 变豆腐块。
问题字体为子集字体 `RSWKOF+Times-Roman`。

### 3.4 验证修复方向：剥离坏 ToUnicode 后重跑

写 `TmpToUnicodeRepair`：把该字体的 `/ToUnicode` 从文档中剥离后重新走解析管线。
结果 `2007`、`23`、`1.`、`8.1`、`4.2`、`493` **全部正确恢复**，无回归。

- 说明"让解码不再产出控制符"即可根治；
- 但**整体剥离 ToUnicode** 过于粗暴（会影响同字体里非 ASCII 的合法映射），因此需要更精准的方案：只改坏条目。

### 3.5 与用户澄清的两个决策点（AskUserQuestion）

- **修复粒度** → 用户选"**精准改写坏条目**"（只改写"可打印码→控制符"的坏映射，保留其余合法映射），而非整体剥离/重建。
- **修复落点** → 用户选"**本仓库 preprocessing**"（在 opendataloader-pdf 自身实现，不跨仓库改 veraPDF fork）。

---

## 4. 根因

| 层 | 事实 | 定性 |
|---|---|---|
| 字体编码 | 子集字体 `RSWKOF+Times-Roman` 的 `/ToUnicode` 把 `<31>`/`<32>` 映射到 `<0018>` | **producer 缺陷（PDF 生产端写坏映射表）** |
| 提取侧 | veraPDF/PDFBox 优先信任 ToUnicode，忠实产出 `U+0018` 控制符 | 非缺陷，但被坏表带偏（"garbage in, garbage out"） |
| 渲染侧 | 控制符无字形 → .notdef 豆腐块 | 现象的直接来源 |
| 视觉层 | PDFBox 渲染源 PDF 数字正常 | **证明是解码错、非缺字形**（关键取证） |

> 与 `2026-10-12-Wingdings符号字体PUA...` 那份记录的区别：那份是"提取对、下游字体接不住 PUA"；本份是"提取本身就错"（坏 ToUnicode 让可打印码解码成控制符）。同为豆腐块现象，根因层级不同。

---

## 5. 修复清单

### 5.1 新增 `utils/ToUnicodeRepair.java`

- 入口 `repairBrokenToUnicode(PDDocument)`：遍历 `document.getPages()` → `resources` → fonts，并**递归 Form XObject**，
  用 `IdentityHashMap` 对已访问字体/资源去重防环。返回被修改的字体 CMap 数量（0 表示无损坏）。
- `repairFontDictionary` → 取 `COSName.TO_UNICODE`，仅当为 `COSStream` 时修复；
  `repairCMapStream`：`createInputStream`（ISO-8859-1 读取）→ `repairCMapText` → `createOutputStream(FLATE_DECODE)` 写回。
- `repairCMapText`：依次处理 `bfchar` / `bfrange` 块，无变化返回 `null`（调用方据此判断"没改就不落盘"）。
  - `repairBfcharBlocks`：按 (src, dst) 成对解析 `<hex>`，改写奇数位（目的地）。
  - `repairBfrangeBlocks`：分别处理数组式 `<s> <e> [ <d1> ... ]` 与 `dstStart` 式 `<s> <e> <dstStart>`；
    `dstStart` 式仅在整个源区间都在可打印 ASCII 内、且 base 命中坏映射时才改写。
- 核心判定 `correctedDestination(srcHex, dstHex)`：

  ```java
  int src = parseHexCode(srcHex);
  if (src < 0x20 || src > 0x7E) return null;        // 只管可打印 ASCII 源码
  if (dstHex == null || dstHex.length() != 4) return null; // 仅单 UCS-2 码元可推理，多码元不动
  int dst = Integer.parseInt(dstHex, 16);
  if (dst >= 0x20 || dst == 0x09 || dst == 0x0A || dst == 0x0D) return null; // 合法目的地放行
  return hex(src);                                   // identity：%04X of src
  ```

  即：**可打印源码 + 目的地是"非空白 C0 控制符"** → 判定为坏条目，改写为 identity；其余一律不动。

### 5.2 接入 `processors/DocumentProcessor.java`

- 新增静态字段 `toUnicodeRepairedTempFile`（镜像既有 `repairedPdfTempFile`）。
- `closePdfResources()` 增加 `clearCleanupStep("ToUnicodeRepairedPdfTempFile", DocumentProcessor::deleteToUnicodeRepairedTempFile);`
  （置于关闭 PDDocument 之后，避免句柄占用删不掉）。
- `preprocessing()` 在 `updateStaticContainers(config)` 之后、`new PDDocument(...)` 之前：

  ```java
  String sourcePdfName = repairBrokenToUnicodeIfNeeded(pdfName);
  pdDocument = new PDDocument(sourcePdfName);          // 改为从修复副本（若有）解析
  ...
  File repaired = tryRepairPdfWithPdfBox(sourcePdfName, cause); // xref 兜底也基于同一副本
  ```

- 新增 `repairBrokenToUnicodeIfNeeded(String pdfName)`：用 `Loader.loadPDF(new File(pdfName))` 加载 PDFBox 文档 →
  `ToUnicodeRepair.repairBrokenToUnicode(boxDoc)`；`repairedCount<=0` 直接返回原路径（正常文件零影响、零临时文件）；
  否则 `Files.createTempFile("opendataloader-pdf-tu-", ".pdf")` + `deleteOnExit` + `boxDoc.save` + 记录 `toUnicodeRepairedTempFile` + 返回临时路径。
  **Best-effort**：加载/保存异常时降级返回原路径，绝不把可恢复的运行变成硬失败；`finally` 关闭 `boxDoc`。
- 新增 `deleteToUnicodeRepairedTempFile()`：镜像 `deleteRepairedPdfTempFile()`，删临时文件并置空。

---

## 6. 验证

### 6.1 编译

`mvn -q -o -pl opendataloader-pdf-core compile` 通过；`GetProblems` 对两个文件无报错。

### 6.2 端到端（DebugSample1 输出到 tmp_output）

运行 `DebugSample1`，日志（WARNING）确认修复触发：

```
DocumentProcessor repairBrokenToUnicodeIfNeeded
警告: Repaired malformed ToUnicode CMaps in 1 font(s) of '200708261781628503910035221.pdf'
(printable codes mapped to control characters); processing continues from the corrected copy
at 'C:\Users\...\Temp\opendataloader-pdf-tu-15228288925882060576.pdf'.
```

输出文件指标核对：

| 输出文件 | 转义控制符 `\u00xx` | 原始控制字节 | 关键数字 |
|---|---|---|---|
| `.js`  | **0** | **0** | — |
| `.json`| **0** | **0** | `493` 正确出现（修复前此处为豆腐块） |

→ 修复前 20 处 `U+0018` 全部消除，数字正确恢复。

### 6.3 临时文件清理

运行后 `Get-ChildItem $env:TEMP -Filter 'opendataloader-pdf-tu-*.pdf'` 计数为 **0**，证明 `closePdfResources()` 及时删除修复临时 PDF。

### 6.4 复现命令

```powershell
cd D:\Code\JavaCode\opendataloader-pdf\java
mvn -q -o -pl opendataloader-pdf-core compile
# classpath = target\classes + src\main\resources + java\cp.txt 内各 jar
# PowerShell 会吞引号/分号，复杂命令务必写进 .ps1 脚本再执行
```

---

## 7. 经验教训

1. **同为"带对角线豆腐块"，根因层级可能相反。** 一种是"提取正确但下游字体接不住"（符号字体 PUA），另一种是"提取本身就错"（本例坏 ToUnicode）。
   先用 **PDFBox 渲染源 PDF** 做分流判据：渲染正常→文本层解码错；渲染也缺字→字形缺失。
2. **扫描输出里的控制字符要扫"转义序列"而非码点。** JSON 把 `U+0018` 存成 6 字符 `\u0018`，直接按码点扫会漏；用正则 `\\u00[0-1][0-9A-Fa-f]` 才命中。
3. **`/ToUnicode` 优先于字体内嵌编码，是"忠实执行坏表"的放大器。** veraPDF/PDFBox 都信 ToUnicode，故一张坏表直接污染全部文本；修复要落在"解析之前"，改表比改提取器更治本、更通用。
4. **可打印码→控制符可证明为缺陷。** 0x20–0x7E 区在 WinAnsi/PDFDoc/Standard 下 `code==unicode`，identity 回写是唯一正确解；据此可安全地"只改坏条目、不动其余"，避免整体剥离 ToUnicode 误伤非 ASCII 合法映射。
5. **精准改写只处理"单 UCS-2 目的地"。** 多码元目的地（如 ligature、CID）无法用 identity 推理，必须原样保留，防止引入新错误。
6. **复用既有"PDFBox 修复 + 临时文件 + closePdfResources 清理"模式。** 与 `tryRepairPdfWithPdfBox` 同构，接入自然、清理可靠；并坚持 best-effort 降级，绝不让修复动作把可恢复运行变成硬失败。
7. **Windows PowerShell 下 `;` 与内联引号易被吞**，复杂命令落地为 `.ps1` 执行（既有记忆经验，本次再次印证）。

---

## 8. 遗留

1. **改动与调试入口尚未 git 提交**：`ToUnicodeRepair.java`（untracked，需 `git add`）、`DocumentProcessor.java`（+~100）。
   `DebugSample1.java` 指向目标 PDF 属调试改动，是否入库需与用户确认。
2. **仅覆盖"可打印码→C0 控制符"这一类坏映射**。其它形态的坏 ToUnicode（如映射到 PUA 私用区、映射到完全不相干的可见字符）本工具不处理，
   需真实样本再扩展判据。
3. **`bfrange` 的 `dstStart` 连续递增式仅在整段源区间可打印时改写**；跨越 0xFF 进位等畸形（日志中 veraPDF 另有一条
   `Incorrect bfrange ... past 255` 既有告警）与本次数字问题无关且未影响输出，本次未处理。
4. **identity 回写假设字体基础编码为 ASCII 兼容**（0x20–0x7E 区）。若某 PDF 该字体用了非 ASCII 兼容编码且同时把可打印码映射到控制符，
   属极罕见组合，未端到端验证。

---

## 9. 关联记忆 / 参考

- `docs/memory/2026-10-12-Wingdings符号字体PUA字符渲染成豆腐块-浏览器安全Unicode映射-根因定位与修复.md`
  —— 同为"豆腐块"现象但根因层级不同（那份：提取对、下游缺字形→映射到通用 Unicode；本份：提取本身错→修 ToUnicode 表）。二者对照可建立"先渲染分流"的定位范式。
- `common_pitfalls_experience`：`ToUnicode把可打印码映射到控制符导致数字渲染成豆腐块`、`PDF 字体 ToUnicode 损坏导致数字提取为控制字符的根因与修复`。
- `DocumentProcessor.preprocessing` / `tryRepairPdfWithPdfBox` / `closePdfResources` —— 本次接入点与"PDFBox 修复+临时文件+清理"模式的既有实现范本。
- PDFBox 3.0.4 API 注意：`PDFont#getName()`（非 `getBaseFont/getFontName`）、`PDResources#getFontNames()`、`COSDocument` 只有 `getObjectsByType()`，故遍历走 pages→resources→fonts 并递归 Form XObject。
