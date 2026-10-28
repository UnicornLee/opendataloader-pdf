# 2026-10-28 — Wingdings 2 被当成 Wingdings 查表，勾选框 ☑ 渲染成 ☼（"带刺的空心圆圈/齿轮"）：字体族识别顺序与映射表补齐的根因定位与修复

> 样本：`docs/pdf/202610291793174452476036921.pdf`（12 页，港股公告类"是/否、适用/不适用"勾选表）
> 现象：页面上**打对号的方框（☑）**，解析结果在浏览器里显示为**带刺的空心圆圈 / 齿轮状符号（☼）**
> 涉及文件（生产代码 1 个）：
>
> - `java/.../pdf/utils/SymbolFontMapper.java`（enum +2 字体族、映射表 +57 条、`detectSymbolFont` 判定顺序与归一化）
>
> 调试入口：`org.opendataloader.pdf.DebugSample1`（按用户要求已指向目标 PDF，属调试文件，不入库）
> 状态：**已编译、已用 DebugSample1 全文验证通过；Wingdings 既有样本回归逐字节一致；多文档影响面已实测；尚未 git 提交**
> 本轮 = 现象 → 输出取证（☼ 计数与 PDF 勾选框一一对应）→ 渲染取证（确认真实字形是 ☑）→ 字体取证（`Wingdings2` + `U+F052`）
> → 权威映射表比对（Wingdings 2 的 0x52 = U+2611）→ 修判定顺序 + 补表 → 端到端验证 → 影响面回归 → 清理探针

---

## 1. 现象

用户报告 `202610291793174452476036921.pdf` 解析结果中，**打对号的方框**在页面上看到的都是
"带刺的空心圆圈"或者"齿轮"图形。

输出 JSON 里的典型文本：

```
"□是 ☼否"        "☼适用 □不适用"        "□适用 ☼不适用"
```

即：**未勾选的方框 `□` 是正确的**，只有**勾选过的方框**变成了 `☼`。

---

## 2. 结论速览（TL;DR）

1. **根因：字体族识别把 `Wingdings2` 吞进了 `WINGDINGS` 分支，用错了字节→字形表。**
   `detectSymbolFont` 用 `name.contains("wingdings")` 判定，`"wingdings2"` 命中 → 返回 `WINGDINGS`。
2. 该文档的勾选框来自 **`QJLWVE+Wingdings2`**，ToUnicode 给出 `U+F052`（源字节 `0x52`）。
   查 `WINGDINGS[0x52]` 得到 `U+263C ☼`（WHITE SUN WITH RAYS，正是"带刺的空心圆圈/齿轮"）。
3. **正确值**：`Wingdings 2` 的 `0x52` 是 `U+2611 ☑`（BALLOT BOX WITH CHECK）。
   **同一字节在不同字体族语义不同，两张表不可混用。**
4. 修复 = ①枚举补 `WINGDINGS_2`/`WINGDINGS_3`；②按同一权威数据补 44/13 条浏览器安全映射；
   ③`detectSymbolFont` 先判 `2/3` 再判通用 `wingdings`，并先剥离非字母数字以容忍 `Wingdings 2`。
5. 修复严格 1:1 等长，`getSymbolEnds()` 与几何坐标不受影响；兜底 `•` 策略不变。

---

## 3. 完整定位过程

### 3.1 输出取证：☼ 与勾选框一一对应

`DebugSample1` 输出 `tmp_output/202610291793174452476036921.json`，按码点统计：

| 码点 | 字符 | 次数 |
|---|---|---|
| `U+263C` | ☼ | **12** |
| `U+25A1` | □ | **12** |

用 PDFBox 逐字符探针扫全 12 页，统计符号字符：

- `U+F052` × **12**，全部 `font=QJLWVE+Wingdings2`
- `U+25A1` × **12**，全部 `font=LNUHNF+SimSun`（文本字体的方框，本就正确）

→ 12/12 完全对齐，**锁定问题只出在 Wingdings2 的 12 个勾选框**。

### 3.2 渲染取证：真实字形确实是"方框+勾"

`PDFRenderer` 6 倍放大第 1 页 y≈406 区域出 PNG，肉眼核对为 **"□是 ☑否"**。
→ 确认目标字形是 `☑`，不是 `☼`，也不是单纯的对勾 `✓`。
（延续 2026-10-12 那条方法论：**符号字体的字节↔字形以渲染为准，不背通用惯例。**）

### 3.3 字体取证：`getFontName()` 返回 `Wingdings2`

用探针遍历 `DocumentProcessor.preprocessing` 前的 page artifacts，打印含 PUA 的 `TextChunk`：

```
page=1 fontName=[Wingdings2] U+F052
```

要点：

- 子集前缀（`QJLWVE+`）已被剥离，得到稳定的 `Wingdings2`；
- **没有空格**（不是 `Wingdings 2`），但为兼容其它 PDF 生成器，判定时统一先去掉非字母数字；
- PUA `U+F052` 的索引字节就是 `0x52`（`PUA_BASE=0xF000`），与表索引口径一致。

### 3.4 权威映射表比对：Wingdings 2 的 0x52 = ☑

用仓库内已有的 dingbat 数据（`.agents/package/dist/dingbats.js`，与上次修复同源）比对：

| 字体族 | `0x52` |
|---|---|
| `WINGDINGS` | `U+263C ☼` ← 被错误使用的表 |
| `WINGDINGS 2` | **`U+2611 ☑`** ← 正确 |
| `WINGDINGS 3` | 未定义该字节 |

`Wingdings 2` 的 `0x54` 则是 `U+2612 ☒`（打叉方框），进一步印证它是"选票方框"家族。

---

## 4. 根因

| 层 | 事实 | 定性 |
|---|---|---|
| 抽取 | `U+F052` + `fontName=Wingdings2`，忠实反映 PDF | 非缺陷 |
| 字体族识别 | `contains("wingdings")` 把 `Wingdings2` 归到 `WINGDINGS` | **根因（判定过宽 + 顺序不对）** |
| 映射表 | `WINGDINGS[0x52]=U+263C` 被用在 Wingdings 2 的字符上 | **根因（跨族查表）** |
| 映射表缺失 | `WINGDINGS_2`/`WINGDINGS_3` 两族根本没有表 | **同类缺陷（潜在）** |

即：**同名字族前缀相同时，"更具体的族"必须先判**，否则通用族会静默吞掉它。

---

## 5. 修复清单（单文件 `SymbolFontMapper.java`）

### 5.1 枚举增补

```java
private enum SymbolFont {
    WINGDINGS,
    WINGDINGS_2,
    WINGDINGS_3,
    WEBDINGS,
    SYMBOL,
    ZAPF_DINGBATS
}
```

`MAPS = new int[SymbolFont.values().length][0x100]` 自动扩容，无需另改。

### 5.2 补两张映射表（沿用上次完全相同的生成规则）

数据源同上次（MIT `dingbat-to-unicode`），规则同上次：
`browserSafe()`（只留 2190–21FF / 2460–24FF / 25A0–25FF / 2600–26FF / 2700–27BF 及 2022/2023/25CF/25CB）
+ `normalize()`（填充方块族 → `25A0`，空心方块族 → `25A1`）。

落地 **Wingdings 2 = 44 条、Wingdings 3 = 13 条**；其中关键条目：

```java
put(SymbolFont.WINGDINGS_2, 0x52, 0x2611); // R -> ☑
put(SymbolFont.WINGDINGS_2, 0x54, 0x2612); // T -> ☒
put(SymbolFont.WINGDINGS_3, 0x58, 0x21EA); // X -> ⇪
put(SymbolFont.WINGDINGS_3, 0x70, 0x25B2); // p -> ▲
```

**生成器的自校验**：用同一规则跑 `WINGDINGS` 表可**逐条复现**现有 71 条（含 `0x6E/0xA7→25A0` 用户覆盖项），
证明"两份数据/两套规则"没有漂移。`WINGDINGS` 表本身**一个字节都没改**。

### 5.3 判定顺序 + 归一化

```java
String name = fontName.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
if (name.contains("wingdings3")) return SymbolFont.WINGDINGS_3;
if (name.contains("wingdings2")) return SymbolFont.WINGDINGS_2;
if (name.contains("wingdings"))  return SymbolFont.WINGDINGS;
// webdings / zapfdingbats / symbol 分支不变
```

先剥离非字母数字，同时兼容 `Wingdings 2` / `Wingdings-2` / `Wingdings2` 三种写法。

### 5.4 未改动

- PUA 扫描范围 `F020–F0FF`、1:1 等长替换、未命中兜底 `U+2022 •`、调用点（`ContentFilterProcessor.getFilteredContents` 最前）。
- 类注释补充一句"Wingdings 2/3 是独立字体，不可并入 Wingdings 表"。

---

## 6. 验证

### 6.1 目标样本（`DebugSample1`）

编译：`mvn -q -o -pl opendataloader-pdf-core -am compile -DskipTests` 通过。

| 输出 | `U+F0xx`（PUA） | `U+263C ☼` | `U+2611 ☑` | `U+25A1 □` |
|---|---|---|---|---|
| `.json` | 0 | **0** | **12** | 12 |

文本形如 `"□是 ☑否"`、`"☑适用 □不适用"` —— 与页面渲染一致。

### 6.2 回归：既有 Wingdings 样本逐字节一致

`202002131783792423397044602.pdf`（上次修复的样本）改动前后 `.json` / `.js` / `.html` **完全一致**：
`PUA=0`、`U+25A0 ■ = 45`，与 2026-10-12 记录的数字相同。

### 6.3 影响面（全部含符号字体的样本，改动前/后对比）

先扫 `docs/pdf` 下全部 PDF 的字体资源，命中符号字体的共 17 份；对其中 8 份做前后对比：

| 文档 | 符号字体 | 改动前 → 改动后 | 判定 |
|---|---|---|---|
| `202610291793174452476036921` | Wingdings2 | ☼×12 → **☑×12** | 修复目标 |
| `202608261787657613816088033` | Wingdings2 | ☼×87 + ☑×17 → **☼×0 + ☑×104** | 同类错误一并修复 |
| `202304271682510470028924` | Wingdings2 | ☼×164 → **☑×164** | 同类错误一并修复 |
| `202303251679660111823147` | Wingdings2 | ☼×1 → **☑×1** | 同类错误一并修复 |
| `202609111789125045008092328` | Wingdings2 | `•`×2 + □×5 → **`•`×0 + □×7** | 兜底退化为真实方块，改善 |
| `202002131783792423397044602` | Wingdings | **完全一致** | 零回归 |
| `202007021783992299116091548` | SymbolMT | 符号字符无变化（仅 JSON 键序差异） | 噪声，非本次引入 |
| `202604211785280740120030048` | SymbolMT + Wingdings | 符号字符无变化（仅 `imageFileN` 编号顺序差异） | 噪声，非本次引入 |

> 噪声说明：`"singleLine"`/`"open"` 键序、`imageFile4/5/6/7` 编号顺序在**多线程**下本就不稳定，
> 改动前的同样两份文档重复跑也会出现；与本次改动无关（符号码点完全没变）。

---

## 7. 经验教训

1. **字体族判定必须"具体优先"。** `contains("wingdings")` 这种宽匹配在有 `Wingdings 2/3` 的体系里是定时炸弹；
   凡是"同名前缀 + 数字后缀"的族（Wingdings 2/3、Symbol MT vs Symbol），都先判具体族。
2. **同一字节跨字体族语义不同，绝不共用一张表。** `0x52` 在 Wingdings 是太阳、在 Wingdings 2 是勾选框；
   表现为"看起来像符号错位"，实为"查错表"。
3. **渲染取证仍是唯一裁判。** 若只按"☼ 视觉怪"去猜，可能误改成 `✓`（丢方框）或 `✔`；出图核对才确定是 `☑`。
4. **生成器自校验是廉价保险。** 用同一脚本复现既有表 → 证明新旧表同源同规则，避免"顺手引进第二种口径"。
5. **影响面要用真实样本跑前后对比，并把噪声认出来。** 本次两份"CHANGED 但同长度"的文档，逐字节 diff 后
   定位到 JSON 键序与图片编号顺序，是多线程固有噪声，避免了误判为回归。

---

## 8. 遗留

1. **尚未 git 提交**：`SymbolFontMapper.java`（本轮的 enum +57 行表 + 判定）。`DebugSample1.java` 指向目标 PDF 属调试改动，按惯例需与用户确认是否入库。
2. **`Wingdings 3` 无真实样本验证**：13 条映射只有构建期证据，`docs/pdf` 里没有用 Wingdings 3 的文档；
   若日后出现，需专项回归。
3. **Wingdings 2 仍有 12 个字节退化为 `•`**：它们只对应 emoji 区码点（如 `U+1F5xx`），被 `browserSafe` 过滤后走兜底。
   若某文档因此在语义上损失过大，可考虑对个别字节做显式白名单映射。
4. **`0x52` 的语义依赖字体族而非文档**：若某 PDF 的 Wingdings2 子集被重排了字形顺序（极端情况），
   仍会映射错——需要渲染取证的样本才能发现，无法靠码点判断。

---

## 9. 关联记忆 / 参考

- `docs/memory/2026-10-12-Wingdings符号字体PUA字符渲染成豆腐块-浏览器安全Unicode映射-根因定位与修复.md`
  —— `SymbolFontMapper` 的诞生、`browserSafe`/`normalize` 规则、`0x6E/0xA7→■` 用户覆盖项、1:1 等长约束的来龙去脉。
- `docs/memory/2026-09-24-PDF叠字重复绘制去重removeOverprintedTextChunks根因定位与修复.md`
  —— `ContentFilterProcessor.getFilteredContents` 步骤顺序，本次调用点仍在其最前。
- `utils/BulletedParagraphUtils.java` —— `POSSIBLE_LABELS` 与带圈数字正则，是"兜底不能一律 `•`"的依据；
  本次的目标字符 `☑` 本来就在 `POSSIBLE_LABELS`（`☐☑☒☓`）内，新增的 ①–⑩（`U+2460–U+2473`）、
  ❶–❿（`U+2776–U+277F`）也都命中其既有 `BULLET_REGEXES`，**列表识别不退化**。
- 外部：npm `dingbat-to-unicode`（MIT）—— Wingdings / Wingdings 2 / Wingdings 3 / Webdings / Symbol 的权威映射源。
