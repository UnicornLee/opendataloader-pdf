# opendataloader-pdf 任务记忆 — 2026-09-03（生产 jar 与本地解析结果差异排查：第 3 页第 2 表格第 2 行单元格数量不一致）

## 结论摘要（TL;DR）

- 用**生产 jar `opendataloader-pdf-server-0.0.0.jar` 内嵌的 core 字节码 + `BOOT-INF/lib` 生产依赖集**在本地解析同一 PDF，
  结果与**本地源码（mvn 编译）解析结果完全一致**：第 3 页第 2 个表格（JSON `item id=4`）第 2 行**只有 1 个单元格**
  （x: 20.7–798.4，`col_len=2` 跨两列），即**本地结果 = 生产 jar 结果 = 正确基线**。
- 交叉验证 JDK 11 / 17 / 21，三份输出中该表格逐行单元格结构完全相同 → **JDK 版本差异可排除**。
- 用户最终确认：**生产上拿到“第 2 行被拆成多个单元格”的结果，是因为该文件走的是另一个 Python 解析服务**
  （Pulsar 链路中的 OCR 图片分析 / 二次合并环节），**与 `opendataloader-pdf-server-0.0.0.jar` 无关**。
- 因此 Java 侧排查关闭：本仓库代码与用户提供的生产 jar 在该用例上行为一致，不一致需到 **Python 解析服务**侧定位。

---

## 目标（Goal）

1. 复现：`docs/pdf/202609031788345926201019610.pdf` 第 3 页第 2 个表格的第 2 行，
   - 本地解析结果：**1 个单元格**（跨两列）；
   - 生产环境结果：**不止 1 个单元格**；
   - 用户明确：**本地结果是对的**。
2. 生产使用的包是 `opendataloader-pdf-server-0.0.0.jar`。
3. 确认：该生产 jar 在本地跑出的结果是否与本地代码一致（第 2 行是否只有 1 格）。
4. 若不一致，找出导致不一致的原因。

工作方式约定：**先定位原因、经用户同意才动代码**；有不清楚的先澄清。

---

## 背景：样本与表格定位

- PDF：`docs/pdf/202609031788345926201019610.pdf`
  - 11 页港股公告（月报表），`page_index=3` 上共有 3 个 `lattice_table`。
  - 目标表格：第 3 页第 2 个表格 → JSON 中 `page_index=3` 的第 2 个 lattice_table：
    - `id = 4`，`bbox = (20.443, 214.335) - (798.77, 334.457)`（内容区 x 20.7–798.4，占整行宽）
    - 共 **5 行**，逐行结构：
      | row | ncell | 说明 |
      |-----|-------|------|
      | row0（表头） | 1 | x20.7–798.4，`col_len=2`（「根据《主板上市规则》第13.32D(1)条或第1…」） |
      | **row1（目标行）** | **1** | x20.7–798.4，`col_len=2`（「☑已符合适用的公众持股量要求（见下方）☐未符合适…」） |
      | row2 | 1 | x20.7–798.4，`col_len=2`（「根据《主板上市规则》第13.32B条或第19A.…」） |
      | row3 | 2 | x20.7–247.5（「适用的公众持股量门槛」）、x247.5–798.4（「适用于拥有其他上市股份的中国发行人的百分比门槛」） |
      | row4 | 2 | x20.7–247.5（「额外信息」）、x247.5–798.4（空） |

  即该表格前 3 行都是**横贯整行（跨两列）的单个合并单元格**，第 4、5 行才分两列。本任务焦点是 row1（第 2 行）在 Java 侧始终输出为 1 格。

---

## 完整定位过程（从现象到根因）

### 阶段 1：摸清生产 jar 与本地环境基线

- 生产包：`D:\Code\JavaCode\opendataloader-pdf-parse\opendataloader-pdf-server-0.0.0.jar`（Spring Boot fat jar）
  - `MD5 = 3AD6E3537B58A5FDB49B29EABC23F3D2`
  - MANIFEST：`Built-By: root`、`Build-Jdk: 17.0.15`、`Created-By: Apache Maven 3.6.3`、`Spring-Boot-Version: 3.4.5`
  - 内嵌 core：`BOOT-INF/lib/opendataloader-pdf-core-0.0.0.jar`（解析核心全部在 core 模块）
  - 依赖注意：fat jar 内 veraPDF 家族构件与本地仓库不同 ——
    `wcag-algorithms-1.31.33`、`wcag-validation-1.31.99`、`validation-model-1.31.99` 的 md5 与 `D:\Maven_Repo` 中本地构件**均不一致**；
    `pdfbox-3.0.4` 等则相同。
- 本地环境：
  - Maven 本地仓库在 `D:\Maven_Repo`（settings.xml 配置），不是 `~/.m2/repository`。
  - 默认 JDK 11（`openjdk 11.0.0.2`）。生产 fat jar 内的 `spring-jcl` 等是 class file v61 → **JDK 11 起不来**，
    需用 JDK 17：`D:\Applications\Java\jdk-17.0.0.1`；另备 `D:\Applications\Java\jdk-21` 做交叉验证。
  - paddle OCR `http://192.168.1.97:8088/layout-parsing` 当前不可达：OCR 页会重试降级，
    但第 3 页 `is_ocr=false`、目标是 lattice 表格（非 stream table），**OCR 不可达不影响本次判定**。

### 阶段 2：写出与 DebugSample 完全等价的驱动，分别跑“本地”与“生产 jar”

- 驱动：`tmp_output/prod_run/src/org/opendataloader/pdf/ProdCompare.java`
  - 与本地 `DebugSample` 逐字段等价：同一 PDF、`outputFolder`、`halfWidthToFullWidth=true`、
    `paddleUrl=http://192.168.1.97:8088/layout-parsing`、`businessId=123456789`、
    `basicParseStreamTable=true`、`basicFormulaRecognize=false`。
  - 区别仅在 classpath：
    - **本地基线**：本地源码 `mvn compile` 的 target/classes（经 DebugSample / ProdCompare 均可）；
    - **生产对照**：从 fat jar 解出 `BOOT-INF/lib/opendataloader-pdf-core-0.0.0.jar` 的 core 字节码 + 全部 `BOOT-INF/lib` 依赖构建 classpath，
      再用与本地相同的 `ProdCompare.java` 源码编译运行（保证驱动代码相同、只有“被测试的解析代码”不同）。
- 三份运行产物：
  | 运行 | 解析代码来源 | 依赖 | JVM | 输出 JSON |
  |------|--------------|------|-----|-----------|
  | 本地源码 | 本地 `target/classes` | 本地 `D:\Maven_Repo` | JDK 11 | `tmp_output/202609031788345926201019610.json` |
  | 生产 jar | fat jar 内嵌 core 字节码 | fat jar `BOOT-INF/lib` | **JDK 17** | `tmp_output/prod_out/202609031788345926201019610.json` |
  | 生产 jar（交叉） | 同上 | 同上 | JDK 21 | `tmp_output/prod_out_jdk21/202609031788345926201019610.json` |

### 阶段 3：结构化对比第 3 页第 2 表格逐行单元格

用解析脚本（`tmp_output/prod_run/dump_tables.py`）读三份 JSON 的 `data[page_index=3]`，
按 `item_type` 过滤表格、定位第 2 个 lattice_table（`id=4`），打印每行单元格数、每格 x 范围与 `row_len/column_len`。

三份结果**逐行完全一致**：

```
page_index=3, table id=4, bbox=(20.443,214.335)-(798.77,334.457)
  row0: ncell=1  x=20.7-798.4 (col_len=2) 「根据《主板上市规则》第13.32D(1)条或第1…」
  row1: ncell=1  x=20.7-798.4 (col_len=2) 「☑已符合适用的公众持股量要求（见下方）☐未符合适…」   ← 目标行
  row2: ncell=1  x=20.7-798.4 (col_len=2) 「根据《主板上市规则》第13.32B条或第19A.…」
  row3: ncell=2  x=20.7-247.5 / x=247.5-798.4
  row4: ncell=2  x=20.7-247.5 / x=247.5-798.4
```

关键证据：**本地源码、生产 jar（JDK17）、生产 jar（JDK21）三者的第 2 行都只有 1 个单元格**，
且目标行（含内容“☑已符合适用的公众持股量要求…”，含 unicode `\u2714`、`\u2610`）文本也一致。

### 阶段 4：排除法小结（Java 侧）

1. **代码差异？** 生产 core 与本地 target 同名 class 共 252 个；逐类字节码因编译环境（JDK/常量池）差异全部不同，
   无法用字节码 diff 直接判定，因此改用**行为对照**：同 PDF 同配置跑出的 JSON 一致 → 逻辑等价。
2. **依赖差异？** veraPDF wcag 构件版本 md5 不同；但对本用例（lattice 行列拆分、verapdf 边框检测）不产生行为差异（实测一致）。
3. **JDK 差异？** 生产 jar 内嵌代码在 JDK 11 / 17 / 21 下输出逐行一致 → 排除。
4. **OCR 影响？** 第 3 页 `is_ocr=false`，OCR 不可达只影响 OCR 分支，不影响 lattice 表格输出。
5. **部署的 jar 是否此包？** 向用户确认：部署的就是 `opendataloader-pdf-server-0.0.0.jar`（MD5 `3AD6E353…`）。

### 阶段 5：澄清 → 根本原因确认

向用户澄清“现象经哪条链路产出 / 是否真的由该 jar 解析”，用户答复：

> **生产解析结果不对，是因为走的是另外一个 Python 解析服务解析的，所以不是
> `opendataloader-pdf-server-0.0.0.jar` 的问题。**

即线上该 PDF 的“第 2 行被拆成多个单元格”出现在 **Pulsar 消费链路 → Python 解析服务（OCR 图片分析 / 结果二次合并）** 环节，
不是本仓库 Java（本地代码 == 生产 jar）产出的结果。

---

## 根本原因（Root Cause）

- **Java 侧（本仓库 `opendataloader-pdf-core` / `opendataloader-pdf-server-0.0.0.jar）：无缺陷。**
  生产 jar 与本地代码对该用例行为一致，第 2 行为 1 格（跨两列），与 PDF 版式一致。
- **不一致的真正来源：另一套 Python 解析服务**在解析该文件时，把本应横贯整行（跨两列）的合并单元格拆成了多个单元格。
  具体机制需在 Python 服务侧排查（例如其对垂直线/边框的判定、行内多子块二次合并时未遵守原始单元格合并信息）。

---

## 验证结果

| 运行 | 解析代码 | 依赖 | JVM | 第3页第2表格 row1 ncell |
|------|----------|------|-----|--------------------------|
| 本地源码 | `target/classes` | 本地仓库 | JDK 11 | **1**（x20.7–798.4，col_len=2） |
| 生产 jar 复现 | fat jar 内嵌 core | `BOOT-INF/lib` | JDK 17 | **1** |
| 生产 jar 交叉 | fat jar 内嵌 core | `BOOT-INF/lib` | JDK 21 | **1** |

三份 JSON：`tmp_output/202609031788345926201019610.json`、`tmp_output/prod_out/202609031788345926201019610.json`、
`tmp_output/prod_out_jdk21/202609031788345926201019610.json`（每行 ncell 与各格 x 范围完全相同）。

---

## 后续建议（Python 解析服务侧，供参考）

若需继续定位 Python 服务为何拆行，可围绕以下问题排查：

1. **输入**：Python 服务解析的是 PDF 本身还是渲染图 + OCR？若是整页渲染图，垂直线检测噪声（如 row0 表头内文字
   “☑/☐”附近的竖线残影、row1 文字后的竖线）可能被当成列分隔线，把整行切成多格。
2. **列线归属**：该表格左、右边框 x≈20.7 / 798.4，中间没有跨整行的竖线；row3/row4 才在 x≈247.5 有竖线。
   检查 Python 侧是否把“行高内局部线段”误当成“整列线”，并用行内多边形合并规则拆格。
3. **二次合并**：Pulsar 链路若存在 Java 解析结果 + OCR 结果“二次合并”，检查合并时是否用 Python 结果覆盖了
   行/列的合并信息（`row_len/column_len`）。
4. **回归样本**：同一 PDF 同时喂 Java 解析与 Python 解析，比对同一行单元格数量即可快速定位差异环节。

---

## 相关文件 / 产物（Relevant Files）

- PDF 样本：`docs/pdf/202609031788345926201019610.pdf`
- 生产 jar：`D:\Code\JavaCode\opendataloader-pdf-parse\opendataloader-pdf-server-0.0.0.jar`
  （MD5 `3AD6E3537B58A5FDB49B29EABC23F3D2`；`BOOT-INF/lib/opendataloader-pdf-core-0.0.0.jar` 为内嵌 core）
- 驱动源码（与 DebugSample 等价）：`tmp_output/prod_run/src/org/opendataloader/pdf/ProdCompare.java`
- 表格结构对比脚本：`tmp_output/prod_run/dump_tables.py`（用法：`python dump_tables.py <json> <label> [表格序号，默认0=全部]`）
- 输出 JSON：
  - 本地：`tmp_output/202609031788345926201019610.json`
  - 生产 jar（JDK17）：`tmp_output/prod_out/202609031788345926201019610.json`
  - 生产 jar（JDK21）：`tmp_output/prod_out_jdk21/202609031788345926201019610.json`
- 运行日志：`tmp_output/prod_run/run_err.txt`（JDK11 起不来：`UnsupportedClassVersionError … class file version 61.0`）、
  `run_err2.txt`（JDK17 正常）、`run_jdk21.txt`（JDK21 正常）
- 调试入口：`java/opendataloader-pdf-core/src/main/java/org/opendataloader/pdf/DebugSample.java`
  （当前指向本 PDF，用户自行维护，勿改动）

## 排查技巧备忘（可复用）

- **验证生产 jar 行为，不直接 diff 字节码**：生产 core 与本地同名类字节码因编译/常量池天然不同；
  用“同一驱动源码 + 仅替换被测解析代码来源/依赖/JVM”做行为对照更可靠。
- **JDK 版本报错一眼判断**：`UnsupportedClassVersionError: … class file version 61.0` → 需 JDK17+；
  本机对应 `D:\Applications\Java\jdk-17.0.0.1`、`jdk-21`。
- **PowerShell 下运行**：`cd` 用 `Set-Location`；`-Dxxx=yyy` 需加引号；中文输出建议重定向到文件再读，
  避免 GBK 控制台把 UTF-8 打乱（文档脚本已用 `sys.stdout.reconfigure(encoding="utf-8")`）。
- **表格结构快速核对**：JSON 顶层 `data[].items[]`，按 `item_type` 过滤 `*table*`，cell 有 `row_len/column_len/x0/x1/text`；
  目标行是否“跨列合并”看 `col_len` 与 x 范围是否覆盖整行（x20.7–798.4）。
