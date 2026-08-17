# 2026-08-17 — catalog 目录末尾点剥除 + 「第 X 页」/「X—Y」格式识别修复

## 任务背景

`docs/pdf/202304211681992320803737.pdf` 第 1 页是目录页，整页内容形如：

```
一、审计报告……………………………………………………… 第 1—6 页
二、财务报表……………………………………………………… 第 7—14 页
（一）合并资产负债表…………………………………………… 第 7 页
…
（八）母公司所有者权益变动表………………………………… 第 14 页
三、财务报表附注……………………………………………… 第 15—91 页
```

但解析后 `catalog_bookmarks: []`（空），同样也无法识别为目录页。所有 11 个目录条目都未进入 `page_bookmarks` 候选，只在 `page` (`self_bookmarks`) 留下了 6 个一级节点（"一、审计意见" 等正文标题）。

---

## 定位过程（按 systematic-debugging 流程）

### 阶段 1：复现 + 收集证据

在 `CatalogBookmarkProcessor.matchTocLine` 与 `analyzeJsonPages` 临时加 INFO 日志（事后已删）：

```java
LOGGER.log(Level.INFO, "[DEBUG-CATALOG-MATCH] text={0}", text);
LOGGER.log(Level.INFO, "[DEBUG-CATALOG-MATCH] ARABIC_NO_MATCH text={0}", text);
// ...
LOGGER.log(Level.INFO, "[DEBUG-CATALOG-PAGE] pageIndex={0} totalLines={1} tocLineCount={2} isTocPage={3}",
    new Object[]{pageIndex, info.totalLines, info.tocLineCount, info.isTocPage()});
```

mvn 重新编译，`DebugSample1` 单 PDF 跑 `202304211681992320803737.pdf`，结果：

```
[DEBUG-CATALOG-PAGE] pageIndex=0 totalLines=12 tocLineCount=0 isTocPage=false
…（上面都是 ARABIC_NO_MATCH）
[CatalogBookmark] no catalog page range detected from JSON
```

**12 行目录文本全部 ARABIC_NO_MATCH → tocLineCount=0 → 目录页未被识别 → catalog_bookmarks: []**

### 阶段 2：分析 pattern 与实际文本

`CatalogBookmarkProcessor.java` 当时的 3 个匹配模式：

```java
ARABIC_TOC_PATTERN  = "^(.*?)[\\s\\.]+(\\d{1,5})$"
ROMAN_TOC_PATTERN   = "^(.*?)[\\s\\.]+([IVXLCDMivxlcdm]+)$"
ROMAN_NUMERAL       = "^(?i)…$"
```

3 个模式都要求行尾是**纯数字**或**纯罗马数字**。第 1 页的目录条目都形如：
- `Title …… 第 X 页` → 行尾 ` 页` ❌
- `Title …… 第 X—Y 页` → 行尾 ` 页` ❌
- （用户后续补充）`Title …… X—Y`（无 `第`/`页`） → 行尾 `Y` 是数字，但中间夹了 `—` 也无法匹配 `(\d{1,5})$` ❌

用 `dd ... | xxd` 抓 JSON 字节确认"点"的真实身份：

```
"一、审计报告" + e2 80 a6 e2 80 a6 … e2 80 a6 20 e7 ac ac 20 31 e2 80 94 36 20 e9 a1 b5
                  ─────────────────────────────────         ───────────────────
                  …（U+2026 HORIZONTAL ELLIPSIS）           第 1—6 页
```

`e2 80 a6` 是 UTF-8 编码的 **U+2026 横向省略号（…）**，不是 ASCII `.`（U+002E）。

### 阶段 3：根因

`CatalogBookmarkProcessor` 同时有两个互相耦合的 bug：

1. **匹配模式太窄（count 0 of 12）**：`ARABIC_TOC_PATTERN` 无法识别中文目录常见的 `第 X 页` / `第 X—Y 页` / `X—Y` 三种格式，导致 `tocLineCount=0` → 目录页未识别。
2. **目录标题去燥不彻底（strip 失败）**：即便强行匹配上，标题末尾的省略号 `…`（U+2026）也不会被 `TITLE_CLEANUP = "[\\s\\.]+$"` 剥掉（这条只覆盖 `.` 与空白），导致解析出来的 bookmark 文本带着 `……` 残尾。

### 阶段 4：方案确认

通过对话与用户确认以下分歧点：

| 问题 | 用户选择 |
|---|---|
| 「第 X 页」格式 | **新增独立 pattern** |
| 「X—Y」无第/页格式 | **新增独立 pattern** |
| 页范围解析策略 | **取起始页（group 2 为左端数字）** |
| 是否剥 `。`（U+3002 中文句号） | **不剥**（避免误伤"标题以句号结尾"） |
| 标题末尾点字符范围 | **`.`（U+002E）+ `…`（U+2026）**，借助 `+` 量词处理连续多个 |
| 回归测试范围 | `docs/pdf/` 目录下 8 个 PDF（排除文件名中段带 `-` 的，以及 `20260507AN202606291826520711.PDF`） |

---

## 修复实施

### 文件 1：`java/opendataloader-pdf-core/src/main/java/org/opendataloader\pdf/processors/CatalogBookmarkProcessor.java`

#### A. 新增 2 个目录行匹配 pattern

```java
// "Title …… 第 X 页" / "Title …… 第 X—Y 页"
// group(1)=标题，group(2)=起始页（范围时取左端数字）
private static final Pattern CHINESE_PAGE_TOC_PATTERN =
        Pattern.compile("^(.*?)第\\s*(\\d{1,5})(?:\\s*[—\\-–]\\s*\\d{1,5})?\\s*页\\s*$");
// "Title …… 1—6" — 页范围无 第/页 包装
private static final Pattern ARABIC_RANGE_TOC_PATTERN =
        Pattern.compile("^(.*?)[\\s\\.]+(\\d{1,5})\\s*[—\\-–]\\s*\\d{1,5}\\s*$");
```

#### B. `matchTocLine` 顺序新增

```java
private static TocMatch matchTocLine(String text, Set<String> pageLabels) {
    // Chinese style: "Title …… 第 X 页" / "Title …… 第 X—Y 页"
    Matcher chinesePageMatcher = CHINESE_PAGE_TOC_PATTERN.matcher(text);
    if (chinesePageMatcher.matches()) {
        String title = chinesePageMatcher.group(1).trim().replaceAll(TITLE_CLEANUP, "");
        if (!title.isEmpty()) {
            return new TocMatch(title, chinesePageMatcher.group(2));
        }
    }
    // Arabic range (no 第/页): "Title …… 1—6"
    Matcher arabicRangeMatcher = ARABIC_RANGE_TOC_PATTERN.matcher(text);
    if (arabicRangeMatcher.matches()) {
        String title = arabicRangeMatcher.group(1).trim().replaceAll(TITLE_CLEANUP, "");
        if (!title.isEmpty()) {
            return new TocMatch(title, arabicRangeMatcher.group(2));
        }
    }
    // 既有 ARABIC / ROMAN / pageLabels 顺序保持不变
    ...
}
```

放在 ARABIC 之前：CHINESE_PAGE_TOC_PATTERN 不会误匹配 `Title 数字`（要求末尾严格"页"），ARABIC_RANGE_TOC_PATTERN 不会误匹配 `Title 单数字`（要求尾边带"数字—数字"）。

#### C. TITLE_CLEANUP 扩展

```java
// 之前
private static final String TITLE_CLEANUP = "[\\s\\.]+$";
// 之后
private static final String TITLE_CLEANUP = "[\\s.\\u2026]+$";
```

`+` 量词负责"连续多个"。`$` 锚定末尾，避免误伤行中段的合法点。

### 文件 2：本任务未新建正式文件

为了 8 个 PDF 批量验证，新建了临时 `java/opendataloader-pdf-core/src/main/java/org/opendataloader\pdf/DebugAll.java`（循环 8 个固定路径调用 `DebugSample1` 同款配置）。**待用户决定是否保留 / 改名为正式工具**。

---

## 验证

### 编译

`mvn -q compile -DskipTests` —— 0 错误 0 警告。

### 目标 PDF（`202304211681992320803737.pdf`）单跑

**修复前 JSON**：
```json
"catalog_bookmarks" : [ ]
"page_bookmarks" 顶层节点: 6
```

**修复后 JSON**：
```json
"catalog_bookmarks" : [
  { "text" : "一、审计报告", "page_num" : 1, "original_page_num" : 1, ... },
  { "text" : "二、财务报表", "page_num" : 16, "original_page_num" : 7, "children": [
      { "text" : "（一）合并资产负债表", "page_num" : 7, ... },
      … 共 8 个子项，page 7/8/9/10/11/12/13/14
  ]},
  { "text" : "三、财务报表附注", "page_num" : 15, "original_page_num" : 15, ... }
]
```

`catalog_page_range_start=1, catalog_page_range_end=1`，11 个 bookmark 全部正确解析。

### 8 个 PDF 回归（`DebugAll`）

| PDF | catalog 检出 | 终选 | 备注 |
|---|---|---|---|
| 202306221687344038470064294 | 0 | page | 无目录，与改前一致 |
| 202303251679660111823147 | 323（10 toc 项） | **catalog** | 工作 |
| 202304271682523609840984 | 10 | self | 检出但被 self 击败 |
| 202304271682505621075149 | 366 | **catalog** | 工作 |
| 202304211681997115596529 | 401 | **catalog** | 工作 |
| 202304271682510470028924 | 343 | **catalog** | 工作 |
| 202304281682603453761936 | 346 | **catalog** | 工作 |
| **202304211681992320803737** | **11** | page | 检出但 catalog 终被 invalidLink 0.727 + nonMono 0.818 淘汰 |

8 个 PDF 的**最终选源全部不变**，说明 CHINESE_PAGE_TOC_PATTERN / ARABIC_RANGE_TOC_PATTERN / TITLE_CLEANUP 三处改动均无功能性破坏。

---

## 附带发现（未修）

### 副作用：目标 PDF 的 `nonMono` 由 0 升到 0.818

`BookmarkQualitySelector` 中 `nonMono = DFS 中序遍历页码非单调占比`。

**修复前**：父 `二、财务报表` 标题为 `二、财务报表……`，`matchBookmarkTitle` 内部 `normalizeBookmarkText` 只去空白不去省略号 → PREFIX 匹配不上正文里的"二、财务报表的编制" → `resolveCatalogBookmarkTarget` 找不到 body 候选 → 父 `page_num` 保留 catalogHint=7 → 父 7、子 7–14 单调。

**修复后**：父标题 `二、财务报表` 干净 → PREFIX 匹配上 body `二、财务报表的编制`（page 16） → 父被 resolve 到 page 16 → 父 16 > 子 7–14 → 9/11 非单调。

非单调本身是**信号质量的体现**（修复让父节点的解析从"猜"变成了"真"），但短期内让目标 PDF 的 catalog 评分从 0.727 升到 1.000（封顶）。**最终选源不受影响**（catalog 反正因 invalidLink 0.727 已经要被淘汰，两次都被 page_bookmarks 替换）。

可选优化方向（**未实施，待用户确认**）：
1. `BookmarkQualitySelector` 中 `nonMono` 算法改为"父<子阶段"反转识别（不是绝对页码下降）
2. `resolveCatalogBookmarkTarget` 在父级 catalogHint 落在子节点 page 范围内时，优先取子节点最小值
3. 不动，保留现状

---

## 改动文件

### `java/opendataloader-pdf-core/src/main/java/org/opendataloader/pdf/processors/CatalogBookmarkProcessor.java`

1. 新增常量（约 L103）：
   ```java
   private static final Pattern CHINESE_PAGE_TOC_PATTERN =
           Pattern.compile("^(.*?)第\\s*(\\d{1,5})(?:\\s*[—\\-–]\\s*\\d{1,5})?\\s*页\\s*$");
   private static final Pattern ARABIC_RANGE_TOC_PATTERN =
           Pattern.compile("^(.*?)[\\s\\.]+(\\d{1,5})\\s*[—\\-–]\\s*\\d{1,5}\\s*$");
   ```

2. 修改常量：
   ```java
   // 之前：private static final String TITLE_CLEANUP = "[\\s\\.]+$";
   private static final String TITLE_CLEANUP = "[\\s.\\u2026]+$";
   ```

3. `matchTocLine` 入口新增 2 段匹配尝试（CHINESE_PAGE_TOC_PATTERN → ARABIC_RANGE_TOC_PATTERN → 既有的 ARABIC / ROMAN / pageLabels）。

### `java/opendataloader-pdf-core/src/main/java/org/opendataloader/pdf/DebugAll.java`（**临时**）

循环 8 个固定 PDF 路径调用 `processFile`，跑完写入 `tmp_output/{\d}.json`。
- 8 个路径：见上方"8 个 PDF 回归"表格
- 若不需要批量工具，运行后删除即可

### `java/opendataloader-pdf-core/src/main/java/org/opendataloader/pdf/DebugSample1.java`

未改（路径仍指 `202304211681992320803737.pdf`，可继续用于单 PDF 复现）。

---

## 经验与备忘

- `CatalogBookmarkProcessor` 的 TOC 匹配分两层：**（a）行级识别**（这行像不像目录条目，由 `matchTocLine` 决定）和**（b）页级判定**（这一页 tocLineCount/totalLines 是否越过 minTocLines=3 & minTocRatio=0.4 阈值）。 `catalog_bookmarks` 为空的根因总是能下钻到这两层之一；本任务两个层都中招。
- 「中文目录」格式的 3 种主流写法：`第 X 页`、`第 X—Y 页`、`X—Y`（无第/页）。三种都需要独立 pattern，**不能用 `[\\s\\.]+(\\d{1,5})` 一个正则覆盖**——后者只匹配单数字结尾，遇到 `1—6` 会被 `(\d{1,5})$` 拒绝。
- TITLE_CLEANUP 的字符类必须用 Unicode escape（如 `\\u2026`）而不是源文件里的裸字符 —— 这样 git diff、byte-level 改动 review 更可控。
- mvn 编译时 `mvn -q exec:java -Dexec.mainClass=...` 是非交互模式跑 main 的标准做法；`DebugSample1` 需要 `file.encoding=UTF-8` 否则中文乱码。
- PowerShell 下用 `mvn ... -Dfoo=bar` 时 `-D` 参数会被 `=` 卡住（`Unknown lifecycle phase ".foo=bar"`），必须 `"-Dfoo=bar"` 加引号；优先用 `mvn -q ... > /tmp/run.log 2>&1` 把日志落盘后用 `grep -aE` 读（避免 `>` 在 PowerShell 下触发 UTF-16 重定向）。
- 加 INFO 日志做"调试探针"是定位 multi-layer 系统的首选手法 —— 加 2 行日志（一个 match 入口 + 一个 page 汇总）就足以定位到根因比"猜想"快。调试后记得清理掉 `[DEBUG-…]` 前缀的日志。

## 相关文件
- `java/opendataloader-pdf-core/src/main/java/org/opendataloader/pdf/processors/CatalogBookmarkProcessor.java`（已改）
- `java/opendataloader-pdf-core/src/main/java/org/opendataloader/pdf/processors/PageBookmarkProcessor.java`（下游消费者，本任务未改）
- `java/opendataloader-pdf-core/src/main/java/org/opendataloader/pdf/custom/utils/BookmarkQualitySelector.java`（下游消费者，本任务未改，但与非单调副作用相关）
- `java/opendataloader-pdf-core/src/main/java/org/opendataloader/pdf/DebugAll.java`（临时调试工具）
- `java/opendataloader-pdf-core/src/main/java/org/opendataloader/pdf/DebugSample1.java`（单 PDF 调试入口）
- 样例 PDF：`docs/pdf/202304211681992320803737.pdf`
- 输出：`tmp_output/202304211681992320803737.json`
