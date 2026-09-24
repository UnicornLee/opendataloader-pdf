# 2026-09-24 — `PageBookmarkProcessor`：Step 6.5「单条相邻回查」对最终 `page_bookmarks` 零影响（验证 + 真正残留定位到 p534）

> 样本（pick10 回归集合 10 份 PDF 的 JSON 产物）：
> - `tmp_output/200910301782365038553054634.json`（目标长文档，含印刷页号 p381 / p534；本次对表文档）
> - 其余 9 份：`00000mc20011101202534w`、`200706271781617929794015618-1`、`202302281677505819604328-114`、`202409271785065118796017983-3`、`202608131786611671823029303`、`202608261787657613816088033`、`202608281787925848775087954`、`202609081788825839483050125`、`354cb7d4-8f79-4429-8768-e3b0e3fcc4e3-149`
>
> 涉及主改动文件（working tree，`M`，含 Step 6.5 共约 76 行插入）：
> - `java/opendataloader-pdf-core/src/main/java/org/opendataloader/pdf/processors/PageBookmarkProcessor.java`
>
> 探针/验证脚本（均在 `tmp_output/probe/`，**未入库**）：
> - `redobaseline.ps1`（干净对比编排：先编译改动后代码跑 after3 → checkout 原始编译跑 base3 → 恢复改动并编译）
> - `run_pick10.ps1` + `TmpScanRunner.java`（`pick10.txt` 清单 → `pick10_$tag` 目录逐份输出 JSON）
> - `diff10.py`（旧对比：`pick10_base` vs `pick10_after`）、`diff3.py`（新对比：`pick10_base3` vs `pick10_after3`，逐节点 text 去空白）
> - `p534.py`（探查 `200910301782365038553054634.json` 的 page_index 533 = display p534 的候选项）
> - `check_p381.py` / `find_381.py` 等（早期探查 p381 的「結餘乃摘錄自」）
>
> 状态：**已验证 —— Step 6.5 对最终 `page_bookmarks` 零影响（10 份文档全部 `SAME`，目标文档 35 条逐节点 0 差异）。用户决定「先这样，不改了」：保留 Step 6.5 改动在 working tree（已证无效但无害），不提交、不回退、不修 p534。**
> 本轮 = **现象（自认为 Step 6.5 生效）→ 第一次回归假象（`BASE==AFTER` 与诊断矛盾）→ 定位基线不可信（restore_after 未重编译）→ 干净重跑（`redobaseline.ps1` + `diff3.py`）→ 证明零影响 + 误读候选层日志 → 重定位真正残留（p381 已不存在 / p534 正文误当子书签）**

---

## 一、任务背景（Goal）

用户早前在 `PageBookmarkProcessor` 的 `cleanCandidatesLocal` 末尾新增了 **Step 6.5「单条相邻回查」**（方法 `hasAdjacentPair`），意图消除一类"正文被误当成子书签"的残留。用户**自以为该改动生效**了——其中一个观察对象是目标文档 `200910301782365038553054634.json` 印刷页号 **p381** 上的「結餘乃摘錄自…」书签，认为它是被 Step 6.5 删掉的。

本轮任务：**用干净回归验证 Step 6.5 是否真的改变了最终 `page_bookmarks` 输出，并从问题追溯到根本原因；同时厘清真正需要修复的残留到底在哪。**

---

## 二、Step 6.5 的落点与逻辑（先讲清楚"它本该干什么"）

Step 6.5 位于 `cleanCandidatesLocal` 末尾，仅当某级（L2+）cleaning 后**只剩 1 条候选**时触发"相邻对回查"：若 pre-filter（周期末一致性过滤前的）候选列表里存在任意相邻对（`hasAdjacentPair`），则判定该孤点是正文残留，返回空列表 → 父节点不挂载该子书签。

```1733:1751:java/opendataloader-pdf-core/src/main/java/org/opendataloader/pdf/processors/PageBookmarkProcessor.java
        // Step 6.5: Re-check the surviving singleton against the pre-filter
        // list.
        // ... a body paragraph that merely mimics the numbering prefix can
        // be the *only* candidate left holding value=1 ...
        if (result.size() == 1 && hasAdjacentPair(unfiltered)) {
            return Collections.emptyList();
        }
        return result;
```

```1772:1778:java/opendataloader-pdf-core/src/main/java/org/opendataloader/pdf/processors/PageBookmarkProcessor.java
    private static boolean hasAdjacentPair(List<Candidate> list) {
        for (int i = 0; i < list.size(); i++) {
            for (int j = i + 1; j < list.size(); j++) {
                if (isSamePageAdjacent(list.get(i), list.get(j))) {
                    return true;
                }
```

设计意图：period-end 过滤器（Step 1.5）把"多数"真标题剔除后，可能只留下一个 value=1 的"孤点"（其实是正文里模仿编号前缀的段落），Step 6.5 用"pre-filter 列表里是否成相邻对排布（TOC/编号列表特征）"来识别并把该孤点判为 residue 丢弃。

---

## 三、第一次回归的假象：`BASE == AFTER` 与诊断日志自相矛盾

### 3.1 旧对比（`diff10.py`）：`pick10_base` vs `pick10_after`

早期做法是直接对比"原始 HEAD 跑出来的 `pick10_base`"与"改动后跑出来的 `pick10_after`"。结果 `BASE == AFTER`（全部 `SAME`），表面上说明 Step 6.5 没改任何输出。

### 3.2 矛盾出现

但 **`check_p381.py` / `find_381.py` 等诊断日志**显示：目标文档 p381 的「結餘乃摘錄自」是**"改动后才被删除"**的——即按 Step 6.5 的逻辑预期，after 应当比 base **少一条（p381）**。

→ 矛盾点：若 Step 6.5 真生效，`after` 应比 `base` 少 p381；但旧对比却给出 `BASE == AFTER`。两者不可能同时为真，说明**其中一端的数据不可信**。

---

## 四、矛盾根因：基线生成不可信（`restore_after` 未重新编译）

追查 `restore_after.ps1`（"恢复改动后代码"的步骤）发现关键缺陷：

- `restore_after.ps1` 把改动后的 `.java` 文件拷回 `src/` 后，**没有重新执行 `mvn compile`**；
- 于是 `target/classes` 里一直是 **原始类**（base 阶段编译的产物）；
- 后续 `after` 跑的其实是**原始类**，与 `base` 用的是同一份字节码 → 自然 `BASE == AFTER`。

→ 这就是"BASE==AFTER 与诊断矛盾"的真相：**旧对比的 `after` 根本没用上改动后的代码**，是假象。诊断日志（p381 在 after 阶段被删）来自更早、确实编译了改动类的某次运行，旧对比把它"平均"掉了。

> 同时被否定的假设：`BASE==AFTER` 不是因为"Step 6.5 真的零影响"，而是因为 after 跑的是旧字节码。必须先做一次**先编译改动后、再 checkout 原始编译**的干净对比，才能下结论。

---

## 五、干净重跑对比（编排 `redobaseline.ps1` + 逐节点 `diff3.py`）

### 5.1 编排脚本要点（`redobaseline.ps1`）

按严格顺序，每一步都用 `Select-String` 校验 `hasAdjacentPair` 是否真在源码里，规避上次的"未重编译"陷阱：

1. **改动后（after3）**：确认 `hasAdjacentPair` 存在 → `mvn -pl opendataloader-pdf-core -am compile` → `run_pick10.ps1 after3` → `tmp_output/probe/pick10_after3`；
2. **原始基线（base3）**：`git checkout -- PageBookmarkProcessor.java`（确认 `hasAdjacentPair` 消失）→ 编译 → `run_pick10.ps1 base3` → `tmp_output/probe/pick10_base3`；
3. **恢复改动**：拷回备份 → 确认 `hasAdjacentPair` 回来 → 重新编译。

### 5.2 逐节点对比（`diff3.py`）

`diff3.py` 把 `page_bookmarks` 展开为 `(depth, page_num, related_id, text去空白)` 元组序列，对 `pick10_base3` vs `pick10_after3` 逐一比较（忽略文本空白，避免 `SmartTextJoiner` 间歇性空格差异造成噪声）。

### 5.3 权威结果（实跑输出）

```
SAME  00000mc20011101202534w.json  (0 bookmarks)
SAME  200706271781617929794015618-1.json  (0 bookmarks)
SAME  200910301782365038553054634.json  (35 bookmarks)
SAME  202302281677505819604328-114.json  (0 bookmarks)
SAME  202409271785065118796017983-3.json  (0 bookmarks)
SAME  202608131786611671823029303.json  (0 bookmarks)
SAME  202608261787657613816088033.json  (176 bookmarks)
SAME  202609081788825839483050125.json  (0 bookmarks)
SAME  202608281787925848775087954.json  (73 bookmarks)
SAME  354cb7d4-8f79-4429-8768-e3b0e3fcc4e3-149.json  (0 bookmarks)

files compared : 10
files changed  : 0
total removed  : 0
total added    : 0
```

→ **10 份文档全部 `SAME`，0 变化**；目标文档 `200910301782365038553054634.json` 的 **35 条 `page_bookmarks` 逐节点完全一致（text 去空白后相等）**。

---

## 六、结论：Step 6.5 对最终输出零影响，之前"生效"是误读候选层日志

1. **Step 6.5 对最终 `page_bookmarks` 零影响**——在"真正编译了改动类 vs 真正编译了原始类"的干净对比下，10 份文档、含目标文档 35 条书签，逐节点 0 差异。
2. **之前以为"生效"是误读候选层日志**：Step 6.5 在 `cleanCandidatesLocal` 内部的 `unfiltered`（pre-filter）列表这一**中间态**确实触发过数据变动，但该变动**没有传导到最终输出**——最终选出的 `result` 在此样本中要么不止 1 条、要么 `hasAdjacentPair` 不满足，Step 6.5 的分支从未真正 return 空。候选层的"有动静"被误当成"最终结果变了"。
3. **旧对比 `BASE==AFTER` 是假象**（详见 §四）：after 端未重编译、跑的是原始字节码。
4. 因此，**p381 的「結餘乃摘錄自」在最终 `page_bookmarks` 里本来就不存在**——既不是 Step 6.5 删的，也不是"改动后新删的"，而是**原始 HEAD 代码就已经正确去掉它**了。之前"p381 被改动后删除"的判读源于读错了日志层级。

---

## 七、真正需要修复的残留：p534 正文说明被误当子书签

`p534.py` 探查 `tmp_output/200910301782365038553054634.json`（page_index 533 = display p534）后发现，当前 `page_bookmarks` 里**真正**残留的不是 p381，而是 **p534 的 L3 节点**：

> 「(1)、(2)、(3)、(4)及(5)項決議案各項是否獲通過（作為普通決議案）而定。」

这是一段**正文说明文字**（决议案是否获通过的条件从句），被错误地识别为该层级（L3）的子书签挂了上去。这类"长句条件说明被误判为编号子项"才是后续若要做精确修复时应针对的目标——但用户决定暂不改。

> 与 Step 6.5 的关系：Step 6.5 的"相邻对回查"针对的是"只剩 1 条孤点"的场景，而 p534 这条是**成段正文被当成一个子节点**，不走 Step 6.5 这条分支，所以 Step 6.5 也救不了它——这从侧面再次印证 Step 6.5 与真正残留无关。

---

## 八、用户决定（2026-09-24 晚间）

**「先这样，不改了」**——暂停修复，保持现状：

- Working tree 中 `PageBookmarkProcessor.java` 的 Step 6.5 改动**保留不变**（已验证对最终输出零影响、无害），**不提交、不回退**；
- 不针对 p534 的"正文误当子书签"做新修复；
- 后续若需要：可随时回退这处无效改动，或正式设计 p534 精准规则并跑回归。

---

## 九、经验教训

1. **回归对比前必须先确认"两端字节码真的不同"**。本次教训的根子是 `restore_after` 修改源码后没重编译，导致 after 端实际跑的是 base 字节码，制造 `BASE==AFTER` 假象。任何"改前 vs 改后"对比，都要像 `redobaseline.ps1` 那样用 `Select-String` 校验源码特征位 + 显式重编译，否则结论不可信。
2. **"候选层/中间态有变动" ≠ "最终输出有变动"**。Step 6.5 在 `cleanCandidatesLocal` 内部的 `unfiltered` 列表确实动过，但没传导到最终 `result`。看日志要分清"中间态"与"最终产物"，否则会把无效改动误判为生效。
3. **诊断日志与聚合对比结果冲突时，先怀疑对比流程而非日志**。本次是聚合对比（`BASE==AFTER`）伪造了"无变化"，而单点诊断（p381 在 after 阶段被删）才是真实现象的线索。冲突信号 = 流程有 bug 的强提示。
4. **"自以为修好的残留"要先验证是否还存在**。用户以为 p381 是 Step 6.5 删的，实情是 p381 在原始 HEAD 就已被正确去除；真正残留是另一个位置（p534）。修复前先用 `p534.py` 类探针确认真实残留点，避免对着假想敌改代码。
5. **逐节点对比要忽略文本空白**：`SmartTextJoiner` 对同份输入可能给出间歇不同的空格（`1、遵循…` ↔ `1、 遵循…`），`diff3.py` 用 `text.replace(' ','').replace('\u3000','')` 归一化后再比，避免噪声淹没真实差异。

---

## 十、相关文件 / 命令 / 产物

改动文件（`git status` 显示 `M`）：

- `java/opendataloader-pdf-core/src/main/java/org/opendataloader/pdf/processors/PageBookmarkProcessor.java`（Step 6.5 + `hasAdjacentPair`，约 76 行插入；当前保留在 working tree，未提交）

验证产物（均在 `tmp_output/probe/`，可随时删）：

- `redobaseline.ps1`（干净对比编排）、`run_pick10.ps1`、`TmpScanRunner.java`、`pick10.txt`
- `pick10_after3/`、`pick10_base3/`（10 份 JSON 产物目录）
- `diff10.py`（旧、失效对比）、`diff3.py`（新、权威对比）
- `p534.py`（p534 候选探查）、`check_p381.py` / `find_381.py`（p381 探查）

复现命令：

```bash
cd d:\Code\JavaCode\opendataloader-pdf
# 干净对比（先编译改动后 → checkout 原始编译 → 恢复）
powershell -NoProfile -ExecutionPolicy Bypass -File tmp_output\probe\redobaseline.ps1
# 逐节点对比 after3 vs base3（忽略文本空白）
python tmp_output\probe\diff3.py
```

邻近历史：

- `2026-09-14-page_bookmarks一级目录选错模板-根因定位与跨度-L3双跑择优.md` — `cleanCandidatesLocal` / `preferUnfilteredSelection` / `selectTemplateForLevel` 的同文件先例，本次 Step 6.5 即挂在该链路上。
- `2026-09-24-PDF叠字重复绘制去重removeOverprintedTextChunks根因定位与修复.md` — 同日另一任务，方法学上同样强调"干净基线 + 全量扫描"才可信。
