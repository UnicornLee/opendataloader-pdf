# 2026-10-12 — Wingdings 符号字体 PUA 字符渲染成"带对角线矩形"（豆腐块）：浏览器安全 Unicode 映射的根因定位与修复

> 样本：`docs/pdf/202002131783792423397044602.pdf`（长城汽车类公告 PDF，第 1/2/6 页含 Wingdings 项目符号）
> 现象：解析结果在浏览器里本应是**实心黑色方块**，肉眼却看到**带对角线的矩形**（.notdef / tofu）
> 涉及文件（生产代码 2 个）：
>
> - **新增** `java/.../pdf/utils/SymbolFontMapper.java`（符号字体 PUA → 浏览器安全 Unicode 映射工具）
> - `java/.../pdf/processors/ContentFilterProcessor.java`（+6 行，在 `getFilteredContents` 管线早期接入）
>
> 调试入口：`org.opendataloader.pdf.DebugSample1`（按用户要求已指向目标 PDF，属调试文件，不入库）
> 状态：**已编译、已用 DebugSample1 全文验证通过；单测套件失败项经基线比对确认为既有环境问题，本次改动零新增失败；尚未 git 提交**
> 本轮 = 现象 → 输出取证（确认 PUA 流入 JSON/JS）→ 字体取证（确认字体名可用）→ 渲染取证（纠正"F06E 是箭头"的误判）→
> 权威映射表生成 + 浏览器安全过滤 → 用户澄清决策 → 实现工具类 → 接入过滤管线 → 全文验证 → 基线比对排除回归 → 清理探针

---

## 1. 现象

用户报告 `202002131783792423397044602.pdf` 第 1、2、6 页存在 Wingdings 字体的符号（项目符号方块）：

- 在 PDF 阅读器里肉眼看到的是**实心黑色方块 ■**；
- 但本项目解析后（JSON/HTML 渲染到浏览器）同一位置显示为**带对角线的矩形**（缺字符号 tofu / .notdef）。

用户要求：
1. 把该符号替换为"页面默认字体或浏览器默认字体兼容的符号"，尽可能兼容主流浏览器；
2. 有不清楚的地方需澄清；
3. 可用 `DebugSample1` 解析/调试，或 IntelliJ Debugger。

---

## 2. 结论速览（TL;DR）

1. **根因：Wingdings 等符号字体通过 ToUnicode 把字形映射到私用区（PUA）`U+F000–U+F0FF`**（源字节 + `0xF000`）。
   主流浏览器的默认字体在 PUA 段**没有字形**，于是落到 .notdef，渲染为"带对角线矩形"。
   - 本文档样本里出现的两个 PUA：`U+F06E`（源字节 `0x6E`，第 1 页）、`U+F0A7`（源字节 `0xA7`，第 2/6 页）。
2. **这不是提取错误，而是"提取正确但语义无法被下游字体承接"。** 文本值本身忠实反映了 PDF 里的字符码，
   缺的是"把符号字体码点翻译成通用 Unicode 码点"这一步。
3. **修复方式：在提取管线里按 `TextChunk.getFontName()` 识别符号字体，把其 PUA 字符 1:1 映射为浏览器强支持的 BMP 区块**
   （Arrows `U+2190–U+21FF`、Enclosed Alphanumerics `U+2460–U+24FF`、Geometric Shapes `U+25A0–U+25FF`、
   Misc Symbols `U+2600–U+26FF`、Dingbats `U+2700–U+27BF`）。
4. **方块符号按用户确认统一归一到 `U+25A0 ■`**（含 `0x6E` 与 `0xA7` 两个字节）。
5. **映射表以权威 npm 包 `dingbat-to-unicode`（MIT）为源**，经浏览器安全区块过滤后落地：Wingdings 71 条、Webdings 8 条、Symbol 17 条。
6. **替换严格 1:1（输入 1 字符 → 输出 1 字符，长度不变）**，以保证 `TextChunk.getSymbolEnds()` 的逐符号几何坐标依然有效，
   不打乱下游行/段合成与截图定位。
7. **踩坑与自我纠正**：起初基于"Wingdings `0x6E`=右箭头"的通用惯例告知用户 F06E 是箭头；
   用 `PDFRenderer` 把该区域渲染成 PNG 后**发现该文档里 F06E 实际是实心方块**，据此向用户澄清，用户最终拍板"F06E 也替换为实心方块"。

---

## 3. 完整定位过程（从问题到根因）

### 3.1 确认"到底是什么字符流进了输出"

`DebugSample1` 已经配置为目标 PDF（输出到 `tmp_output/`，`setGenerateMarkdown(true)`）。
用 Node 脚本扫描 `tmp_output` 下该样本的 `.json` / `.js`，统计码点分布，得到：

- `U+F0A7` 出现 23 处、`U+F06E` 出现 22 处 —— **确认 PUA 字符确实原样流进了文本输出**，而非中途已被替换。
- 这一步锁定了问题性质：输出层带着浏览器画不出来的 PUA 码点。

### 3.2 确认字体身份，以及"能否拿到字体名"（关键前提）

映射方案成立的前提是：运行时能在 chunk 上拿到可靠的字体名。用 PDFBox 探针 `TmpFontProbe.java` 列出资源字体：

- 第 1/2/6 页均含子集字体 `ABCDEE+Wingdings`（`PDType0Font`）。
- 第 1 页用 `F06E`，第 2/6 页用 `F0A7`。

再写 `DebugFontName.java`（放在 core `src/main`，遍历 `DocumentProcessor.preprocessing` 后的 `TextChunk`，
打印含 PUA 的 chunk 的 `getFontName()` 与 value），实测输出：

```
page 1  PUA=U+F06E  fontName=[Wingdings]
page 2  PUA=U+F0A7  fontName=[Wingdings]
```

**两个关键结论**：
1. `TextChunk.getFontName()` 返回的是**已剥离子集前缀（`ABCDEE+`）并归一化为 `"Wingdings"`** 的字体名，可直接用于识别；
2. 每个符号是**独立的单字符 TextChunk**，替换只需逐字符处理，不涉及跨 chunk 的复杂拆分。

### 3.3 纠正"F06E 是箭头"的误判（渲染取证）

Wingdings 的通用惯例是 `0x6E`（字母 n）= 右箭头。据此**最初告知用户 F06E 是箭头**。
但惯例未必符合具体文档。用 `TmpRenderProbe.java`（PDFBox `PDFRenderer`）把第 1 页 F06E 所在区域高分辨率渲染成 PNG，
肉眼核对：**该文档里 F06E 渲染出的就是实心黑色方块，不是箭头**。

→ 教训：符号字体的"字节↔字形"关系**以实际渲染为准**，不能只背通用惯例。
据此再次向用户澄清，用户回复"第 1 页的箭头符号也要替换为实心方块"，即 **F06E 与 F0A7 均 → `U+25A0`**。

### 3.4 与用户澄清的三个决策点（AskUserQuestion）

- **方块替换成什么** → 用户选 `■ U+25A0`（实心大方块，浏览器兼容性最好的方块码点）。
- **箭头替换成什么** → 起初选 `→ U+2192`；后因 3.3 的渲染取证，用户改判"箭头也 → 实心方块"。
- **生效范围** → 用户选"**覆盖常见符号字体**"（Wingdings / Webdings / Symbol / Zapf Dingbats），而非只修 Wingdings。

### 3.5 发现"不能用统一 • 兜底"（带圈数字语义）

查 `BulletedParagraphUtils` 时发现它**已内置**针对 Wingdings 带圈数字列表的 PUA 识别正则：

```java
BULLET_REGEXES.add(Pattern.compile("^[\uF081-\uF08A]")); // ➀-➉
BULLET_REGEXES.add(Pattern.compile("^[\uF08C-\uF095]")); // ➊-➓
```

含义：若把所有未映射 PUA 一律换成统一的 `•`，会**丢掉带圈数字的编号语义**，且破坏现有列表识别。
因此映射表必须**保留 0x81–0x95 → U+2780–U+2793（➀–➓）** 这类逐符号映射；
`•`（U+2022）只作为**真正无对应码点时的最后兜底**。

### 3.6 生成浏览器安全映射表

以 npm 包 `dingbat-to-unicode`（MIT，含 `SYMBOL`/`WEBDINGS`/`WINGDINGS`/`WINGDINGS 2`/`WINGDINGS 3` 表）为权威源，
写 `gen_final.js` 做两步过滤：

1. `browserSafe(u)`：只保留 2190–21FF / 2460–24FF / 25A0–25FF / 2600–26FF / 2700–27BF / 及 `2022`、`2023`、`25CF`、`25CB`；
   落在浏览器弱支持区块（`U+23xx`/`U+2Bxx`/`U+2BBx`）的条目剔除，交给兜底。
2. `normalize(u)`：把填充方块族（`25AA`/`25FB–25FE`/`2B1D`）归一到 `25A0`，空心方块族（`2751`/`2752`/`25A2`）归一到 `25A1`。

再叠加用户覆盖项 `overrides = { 0x6e: 0x25a0, 0xa7: 0x25a0 }`。
产出：Wingdings 71 条、Webdings 8 条、Symbol 17 条，作为 `SymbolFontMapper` 的 `put(...)` 初始化数据。

---

## 4. 根因

| 层 | 事实 | 定性 |
|---|---|---|
| 字符映射 | 符号字体经 ToUnicode 产出 `U+F000–U+F0FF` 私用区码点（源字节 + 0xF000） | **PDF 符号字体的固有编码方式** |
| 渲染侧 | 浏览器默认字体在 PUA 段无字形 → .notdef 豆腐块 | **根因（语义无法被下游字体承接）** |
| 提取侧 | 提取忠实反映 PDF 字符码，本身无错 | 非缺陷，缺的是"翻译"步骤 |
| 惯例误导 | "0x6E=箭头"的通用惯例与本例文档实际字形（方块）不符 | **需以渲染取证为准**（方法论坑） |

---

## 5. 修复清单

### 5.1 新增 `utils/SymbolFontMapper.java`

- 内部枚举 `SymbolFont{ WINGDINGS, WEBDINGS, SYMBOL, ZAPF_DINGBATS }`。
- 静态 `int[][] MAPS`：按 `字体族 × 源字节(0x00–0xFF)` 索引，`0` 表示未映射；用生成的 71/8/17 条数据初始化
  （含 `0x6E→0x25A0`、`0xA7→0x25A0` 覆盖项）。
- `detectSymbolFont(String fontName)`：字体名转小写后按 `webdings` → `wingdings` → `dingbat`/`zapf` → `symbol`
  的 `contains` 判定（顺序：webdings 必须先判，避免被 wingdings/symbol 误吞；容忍子集前缀）。
- `normalizeSymbolFontText(List<IObject>)` / `normalizeSymbolFontText(TextChunk)`：
  仅对**已识别符号字体**的 chunk，扫描其中落在 `PUA_MIN=0xF020 … PUA_MAX=0xF0FF` 的字符，
  用 `MAPS[family][ch - 0xF000]` 反查；命中则替换为目标码点，**未命中且字节 ≥ 0x21 兜底为 `U+2022 •`**。
  **替换前后长度一致**，故 `getSymbolEnds()` 与 bounding box 不受影响；有变化才 `setValue`。

### 5.2 接入 `ContentFilterProcessor.getFilteredContents`（+6 行）

在 `List<IObject> pageContents = new ArrayList<>(contents);` 之后、`removeOverprintedTextChunks` 之前插入：

```java
SymbolFontMapper.normalizeSymbolFontText(pageContents);
```

**为什么放这里 / 放最前**：
- 这是**每页过滤的统一入口**，早于去重、合并、行/段合成与列表识别，转换一次即对全管线生效。
- 必须早于 `BulletedParagraphUtils` 的列表识别：转换后方块 `■`（`U+25A0`）已在 `POSSIBLE_LABELS` 内，
  带圈数字 `➀–➓`（`U+2780–U+2793`）也命中其既有正则 `^[\u2780-\u2789]`/`^[\u278A-\u2793]`，**列表识别不退化**。
- 早于 `replaceUndefinedCharacters`（那步只处理 `U+FFFD`，与本问题无关）。

---

## 6. 验证

### 6.1 真实 PDF 全文（`DebugSample1` 输出到 `tmp_output`）

编译：`mvn -q -pl opendataloader-pdf-core compile` 通过，`GetProblems` 无报错。
运行 `DebugSample1` 后用 Node 码点统计脚本核对：

| 输出文件 | PUA(`E000–F8FF`) | `U+F06E` | `U+F0A7` | `U+25A0 ■` |
|---|---|---|---|---|
| `.json` | **0** | 0 | 0 | **45** |
| `.js`  | **0** | 0 | 0 | **45** |
| `.html`| **0** | 0 | 0 | 0（HTML 正文不含该文本，实际数据在 `.js`） |

→ 目标 PUA 全部消除并变成 `U+25A0 ■`，浏览器不再出现豆腐块。

### 6.2 回归归因（单测失败 ≠ 本次改动）

`mvn -pl opendataloader-pdf-core test` 有大量失败。逐条判定为**既有环境问题**：
- `AutoTaggerTest` / `HybridReferenceTrailerTest` 的 NPE 栈顶是 `java.io.File.<init>` ← `DocumentProcessor:630` 的 `new File(null)`，
  源于测试 Config 未设 outputFolder，**与本次改动无关**（栈中不含 `SymbolFontMapper`）。
- 其余：Windows 文件锁（"另一个程序正在使用此文件"）、硬编码他机路径 `D:\Code\JavaCode\opendataloader-pdf-parse\...`、
  遗留 PNG `FileAlreadyExists`、poppler/PIL 未安装等。

**基线证明**：把 `ContentFilterProcessor.java` 用 `git stash push` 临时撤回，单跑 `AutoTaggerTest` 仍复现同样的 4 个错误
（`Tests run: 4, Errors: 4`）；`git stash pop` 恢复后重新编译并复跑 DebugSample1，指标不变（JSON：0 PUA、45 ■）。
→ **本次改动零新增失败**已确证。

### 6.3 复现命令

```powershell
cd D:\Code\JavaCode\opendataloader-pdf\java
mvn -q -pl opendataloader-pdf-core compile
# 用 java/cp.txt 里的依赖 classpath 运行（PowerShell 会吞引号，务必写在 .ps1 脚本里执行）
# $cp = "target\classes;" + (Get-Content ..\cp.txt -Raw).Trim(); java -cp $cp org.opendataloader.pdf.DebugSample1
```

---

## 7. 经验教训

1. **"提取正确 ≠ 输出可用"。** 符号字体、CID 字体这类问题，文本码点忠实反映 PDF，但下游字体接不住。
   定位时要分清"码点错"还是"码点对但渲染端缺字形"——后者要靠**映射到通用区块**解决，而非改提取。
2. **动手前先确认"运行时能拿到判据字段"。** 本方案的前提是 `getFontName()` 返回归一化后的 `"Wingdings"`；
   若拿到的是带子集前缀或不稳定的 BaseFont 名，识别就得另做归一化。**先探针证实，再设计映射。**
3. **符号字体的字节↔字形以渲染为准，别背通用惯例。** 差点按"0x6E=箭头"交付错误映射；
   `PDFRenderer` 出 PNG 肉眼核对才纠正。凡涉符号语义，**渲染取证 > 文档惯例**。
4. **替换必须 1:1（等长）。** TextChunk 的逐符号几何（`getSymbolEnds`）与字符数一一对应；
   任何"合并/展开成多字符"的替换都会打乱坐标。方块→`■`、箭头→`■`、带圈数字→单个 `➀` 都是等长映射，安全。
5. **兜底要克制，别伤语义。** 已有逐符号映射（尤其带圈数字、方块的空心/实心区分）必须保留，
   `•` 只作"彻底无对应码点"的最后兜底；把一切 PUA 抹成 `•` 会毁掉 `BulletedParagraphUtils` 的列表识别。
6. **改动插入点要选"每页统一入口 + 尽量早"。** `ContentFilterProcessor.getFilteredContents` 是每页过滤总入口，
   在此转换一次对全管线生效；且必须早于依赖 value 的下游（去重/合并/列表识别/`replaceUndefinedCharacters`）。
7. **测试失败先做基线比对再归因。** 用 `git stash push -- <文件>` 撤回改动、复跑同一测试，看失败是否依旧——
   比逐条阅读栈更有力地证明"非本次引入"。

---

## 8. 遗留

1. **改动与调试入口尚未 git 提交**：`SymbolFontMapper.java`（untracked，别忘 `git add`）、`ContentFilterProcessor.java`（+6）。
   `DebugSample1.java` 指向目标 PDF 属调试改动，按惯例是否入库需与用户确认。
2. **兜底 `U+2022` 未在本样本触发验证**：样本仅用到 F06E/F0A7，二者均已命中显式映射；
   真正的未映射 PUA 兜底路径缺乏端到端样本证据，后续遇到其它符号字体文档需专项回归。
3. **Zapf Dingbats 仅有族识别、无显式映射表**：其 ToUnicode 通常直接产出通用 Unicode（本就非 PUA），故只在其
   偶发 PUA 时靠 `•` 兜底。若日后出现真实 Zapf 豆腐块样本，应补 `dingbat-to-unicode` 的相应表。
4. **"覆盖常见符号字体"广度依赖 `dingbat-to-unicode` 质量**：过滤掉弱支持区块后，部分冷门符号会退化为 `•`，
   属"宁缺毋滥避免豆腐块"的已知权衡；若某文档因此丢失过多语义，可考虑对特定字节扩充白名单区块。
5. **映射表随字号/语义不做区分**：同一字节在任何 Wingdings chunk 上都映射为同一码点，未区分"方块是列表符还是正文强调"，
   现实影响极小，但属简化假设。

---

## 9. 关联记忆 / 参考

- `docs/memory/2026-09-24-PDF叠字重复绘制去重removeOverprintedTextChunks根因定位与修复.md`
  —— `ContentFilterProcessor.getFilteredContents` 各步骤顺序与"几何判据"的来龙去脉；本次转换点选在该方法最前，需了解其步骤依赖。
- `docs/memory/2026-10-08-CJK幻影连续空格-严格leftX排序错位-根因定位与修复.md`
  —— 同为"TextChunk 逐符号几何/顺序敏感"类改动，印证"替换必须等长、勿破坏 `getSymbolEnds`"的约束。
- `utils/BulletedParagraphUtils.java` —— `POSSIBLE_LABELS`（含 `■`）与带圈数字 PUA 正则，是"不能用统一 `•` 兜底"的直接依据。
- 外部：npm `dingbat-to-unicode`（MIT）—— Wingdings/Webdings/Symbol 字节↔Unicode 的权威映射源。
