# 2026-08-12 — `202303251679660111823147.pdf` L3 节点「一/二/三/四/五/六」丢失根因与同模板可用修复

## 任务背景

用户反馈：fix#1（孤点 value=1 prepend 到 value=2 链）实施后，L2 节点「一、审计报告」已恢复为「第十节 财务报告」的第一个子节点；但其下应该挂的 L3 子节点「一、审计意见 … 六、注册会计师…的责任」依然不在树里，实际输出的是「（一）商誉减值」「（二）收入确认」两个错误节点。

用户提示："**在二级目录筛选中被淘汰，不代表不能用于三级目录的筛选呀！**" 这句话指出了问题的本质：被 L2 淘汰的链（Chain B）即使不需要作为 L2 输出，也应该作为 L3 候选供使用。

可借助的产物：
- `tmp_output/202303251679660111823147.json`（fix#1 已生效的最新解析结果）
- `tmp_output/202303251679660111823147_page_bookmarks_collected.md`
- 可通过 `DebugSample` 重跑验证

---

## 第一步：物证收集

观察 fix#1 后的 `tmp_output/202303251679660111823147.json` 的 `page_bookmarks` 树：

```
第十节 财务报告 [p98 id=1]            ← L1
  一、审计报告 [p98 id=2]              ← L2
    （一）商誉减值 [p98 id=13]          ← L3（错！）
    （二）收入确认 [p99 id=5]            ← L3（错！）
  二、财务报表 [p100 id=14]            ← L2
    1、合并资产负债表 [p100 id=16]       ← L3（正确）
    2、母公司资产负债表 [p103 id=3]       ← L3（正确）
```

期望的 L3：
```
一、审计报告 [p98 id=2]
  一、审计意见 [p98 id=6]
  二、形成审计意见的基础 [p98 id=9]
  三、关键审计事项 [p98 id=11]
  四、其他信息 [p99 id=19]
  五、管理层和治理层对财务报表的责任 [p99 id=24]
  六、注册会计师对财务报表审计的责任 [p99 id=28]
```

注意：「二、财务报表」下的 L3 是「1、合并资产负债表」「2、母公司资产负债表」，模板是「1、」（数字），与 L2 的「一、」（中文）不同，所以正常工作。「一、审计报告」下需要 L3 沿用 L2 的「一、」模板，所以失败。

---

## 第二步：候选清单

从 `data[97]` 和 `data[98]`（即 p98/p99）逐项筛出落在「一、审计报告」range 内的所有 bookmark 候选：

| 候选 | 模板 | value | source_type | font_size | page | id |
|---|---|---|---|---|---|---|
| 一、审计报告 | 一、 | 1 | heading | 12.0 | 98 | 2 (L2 anchor) |
| 一、审计意见 | 一、 | 1 | heading | 9.12 | 98 | 6 |
| 二、形成审计意见的基础 | 一、 | 2 | heading | 9.12 | 98 | 9 |
| 三、关键审计事项 | 一、 | 3 | heading | 9.12 | 98 | 11 |
| 四、其他信息 | 一、 | 4 | heading | 9.12 | 99 | 19 |
| 五、管理层和治理层…的责任 | 一、 | 5 | heading | 9.12 | 99 | 24 |
| 六、注册会计师…的责任 | 一、 | 6 | heading | 9.12 | 99 | 28 |
| （一）商誉减值 | （#） | 1 | paragraph | 9.12 | 98 | 13 |
| （二）收入确认 | （#） | 2 | paragraph | 9.12 | 99 | 5 |

按 `selectTemplateForLevel` 的排序规则（fontSize → count → indent → page → topY）：fontSize 同为 9.12 → count 多者胜 → 「一、」组 6 个 vs 「（#）」组 2 个 → **「一、」本该胜」**」。

但实际 L3 输出是「（#）」组。说明「一、」组在 L3 选择阶段根本没进入候选。

---

## 第三步：根因定位

读 `PageBookmarkProcessor.java` 的 `extractLevel`（约 494 行）：

```java
List<Bookmark> bookmarks = new ArrayList<>();
Set<TemplateKey> newUsed = new HashSet<>(usedTemplates);
newUsed.add(selectedTemplate);   // ← L2 选中的"一、"被加进去

for (int i = 0; i < cleanedIndices.size(); i++) {
    int idx = cleanedIndices.get(i);
    Candidate candidate = candidates.get(idx);
    int childStart = idx + 1;
    int childEnd = (i + 1 < cleanedIndices.size()) ? cleanedIndices.get(i + 1) - 1 : end;
    List<Bookmark> children = extractLevel(candidates, childStart, childEnd, level + 1, newUsed);
    ...
}
```

`selectTemplateForLevel` 内部：

```java
for (int i = start; i <= end; i++) {
    Candidate c = candidates.get(i);
    if (usedTemplates.contains(c.templateKey)) {
        continue;                // ← "一、"整组被跳过
    }
    groups.computeIfAbsent(c.templateKey, Group::new).add(c);
}
```

传到 L3 时 `usedTemplates = {第#节, 一、}`，「一、」整组被跳过，剩「（#）」组（2 个），于是 L3 输出 `（一）商誉减值`、`（二）收入确认`。

这就是用户那句话的精确命中点：

> **"在二级目录筛选中被淘汰，不代表不能用于三级目录的筛选呀！"**

L2 选择把「一、」模板的 Chain B（一、审计意见…六、…）丢弃了。但即便 L3 仍然想用「一、」模板，由于 `usedTemplates` 的传递机制，L3 选不到「一、」模板，只能退而求其次选择「（#）」模板。

---

## 第四步：与 fix#1 的区分

fix#1 解决的是 **L2 模板选举阶段**：让孤点 value=1 (`一、审计报告`) 通过 prepend 操作挂到 value=2..18 链上，从而在 L2 选择里胜出。

本任务解决的是 **L3 模板选举阶段**：L2 选完后，进入子节点范围重新选举模板时，**不再把 L2 选中的模板从候选里屏蔽**。L2 的 templateKey 在选完当前层后就该让位，因为：
1. L1 是文档级的，跨整篇选模板，必须向下屏蔽避免 L2 重复选 L1 模板；
2. L2 / L3 都是父节点范围内的局部选择，每个父节点的范围天然切掉了父节点自己的锚点（通过 `[anchorIdx+1, nextIdx-1]` 切片）；在子节点范围内「同模板的候选」是新层级的合法候选，**不应该被屏蔽**。

---

## 第五步：方案（用户确认）

修改 `extractLevel` 的「`newUsed.add(selectedTemplate)`」位置：只在 L1 写入；L2/L3 不再写。

修改 `extractChildrenForAnchor` 的对应位置（level==3 入口），同步删掉 `usedTemplates.add(levelTwoTemplate)`。

用户四点确认后开始改代码：

1. 同意两处修改
2. 同意只跑 `mvn -pl opendataloader-pdf-core test -Dtest=PageBookmarkProcessorTest`，不跑全量 E2E
3. 同意 DebugSample 重跑覆盖 tmp_output
4. 同意加正反向两个回归用例

---

## 第六步：修改清单

### 1. `PageBookmarkProcessor.java#extractLevel`

```java
List<Bookmark> bookmarks = new ArrayList<>();
// Only level-1's selected template is propagated to deeper levels. L1
// selects across the whole document, so re-selecting it inside a
// descendant range would recreate the L1 anchors as L2/L3 nodes. L2/L3
// selections are range-bounded and the parent anchor is already
// excluded from the child range by index slicing, so a template that
// happens to also appear in the child range (e.g. nested "一、" items
// under an "一、" L2 anchor) is a legitimate L3 candidate and must
// not be filtered out here.
Set<TemplateKey> newUsed = new HashSet<>(usedTemplates);
if (level == 1) {
    newUsed.add(selectedTemplate);
}
```

### 2. `PageBookmarkProcessor.java#extractChildrenForAnchor`

删除原 else 分支里的 `usedTemplates.add(levelTwoTemplate)`。理由同上——L3 调用此方法时不应把 L2 模板塞进 usedTemplates。

---

## 第七步：新增回归测试

### `testLevel3ReusesLevel2TemplateWhenSameTemplateLivesInRange`（正向）

模拟用户 PDF 的核心场景：

- L1 = `第1章 测试`（font 20，value=1）单条
- L2 候选里有一个 orphan value=1（`一、X`，font 12，在 p0 单独一页）—— 这就是 L2 锚点
- L2 候选里有一段 value=1..6（`一、A..六、F`，font 9，在 p1）—— 这就是「一、审计意见…六、…」的简化版
- L2 候选里有一段 value=2..8（`二、Y..八、S`，font 12，在 p2）—— 这就是「二、财务报表…18」那条宽链的简化版
- 同时还有「（一）P」「（二）Q」2 条（font 9）作为「（#）」模板的对照组

通过 fix#1 的 prepend pass，`一、X` 被 prepend 到 value=2..8 链上，形成 8 项宽链胜出，L2 输出 `[一、X, 二、Y..八、S]`。

L3 在「一、X」range 内（page 1）有 6 条「一、」+ 2 条「（#）」。修复前「一、」被屏蔽，「（#）」胜出；修复后「一、」组 6 条胜出。

### `testLevel3PicksDeeperTemplateWhenL2TemplateHasFewerCandidates`（反向）

页面布局同上，但 page 1 上没有「一、」候选、只有 `1、B..5、F`（value=1..5，font 9）。断言 L3 选「1、」组 5 条胜出。这条确保「L3 允许使用 L2 模板」**不是**「L3 总是优先 L2 模板」——count 多的「1、」组仍然胜出。

### 测试调试中踩过的坑（写下来下次别再绕）

1. **「1、X」「2、X」」** value 各自是 1、2、3…5，必须用不同数字前缀才能形成 1..5 链。如果全写「1、A」「1、B」「1、C」…」** value 全是 1，Step 2 分组会切成 5 个单独的 value=1 孤儿组，全部被 prepend 逻辑无视，结果只有 1 条返回。
2. **「一、Y」紧跟「二、Y」」** 二者 value 分别是 1 和 2，**跨页也仍然会**被 Step 2 合并成同一个 group（G=[一、Y, 二、Y…]）。所以「一、Y」必须放在与「二、Y」**不在同 template-key 链上**的位置，或者干脆不放「一、Y」。
3. **前置一条 value=2 链是 prepend 触发的前提**。否则「一、X」会作为单独 orphan wrapped chain，宽度 1，被 1..6 的「一、A..F」链（宽度 6）压倒，L2 上「一、X」就丢了。

---

## 第八步：验证结果

| 步骤 | 结果 |
|---|---|
| `mvn -pl opendataloader-pdf-core test -Dtest=PageBookmarkProcessorTest` | **36/36 通过**（含 2 个新增正反向用例） |
| `mvn -pl opendataloader-pdf-core exec:java -Dexec.mainClass=org.opendataloader.pdf.DebugSample` | 重跑 `202303251679660111823147.pdf`，日志正常，无异常 |
| `tmp_output/202303251679660111823147.json` L3 子树 | 第十节下 L2 第一项「一、审计报告」(p98) ，下挂 6 项「一、审计意见 … 六、注册会计师…的责任」 |

修复后该 L3 子树：

```
第十节 财务报告 (p98)
  一、审计报告 (p98)
    一、审计意见 (p98)
    二、形成审计意见的基础 (p98)
    三、关键审计事项 (p98)
    四、其他信息 (p99)
    五、管理层和治理层对财务报表的责任 (p99)
    六、注册会计师对财务报表审计的责任 (p99)
  二、财务报表 (p100)
    1、合并资产负债表 (p100)        ← 不同模板「1、」，本来就能正确选中
    2、母公司资产负债表 (p103)
  三、公司基本情况 (p126)
  ...
```

「二、财务报表」下的 L3（`1、`模板）原本就没受影响，修复后依然正确。

---

## 关键决策与坑点

### 写测试时为什么要先调通手动 trace

`cleanCandidatesLocal` 内部有 5 步（分 7 步、含 prepend）、依赖**纯值连续**分组（不感知 page 边界）、Step 4.5 TOC 过滤、Step 4.7 prepend 等子步骤。直接摆 14 条候选进去的话，跑失败要花很多次试错才能猜到哪个分组逻辑没满足。最好先用 `python` dump 一下当前生产数据里的同一值分布 / 跨页情况再设计测试夹具。

### `extractLevel` 改动 vs `extractChildrenForAnchor` 改动

后者是前者的「专用深度接口」——为外部 caller 提供按 (page, relatedId) 锚点直接拉子树。两处都要改，否则只改 `extractLevel` 的话，`extractChildrenForAnchor` 调用路径上的子树仍然会用旧的 usedTemplates 集合。

### 为什么不反过来

考虑过反向方案——**保留 usedTemplates 写入，但只对 L3 选择放宽 L2 模板的屏蔽**。否决理由：需要对 `selectTemplateForLevel` 加 per-level 排除粒度，代码更脆。当前方案只改 `newUsed.add` 的条件，把传播规则变简单（L1 才传），更符合「只在最高层做全局屏蔽」的设计意图。

---

## 相关文件

- `java/opendataloader-pdf-core/src/main/java/org/opendataloader/pdf/processors/PageBookmarkProcessor.java`
  - `extractLevel` 改为只在 `level == 1` 写入 `newUsed`，附带注释解释
  - `extractChildrenForAnchor` 删除 else 分支里的 `usedTemplates.add(levelTwoTemplate)`
- `java/opendataloader-pdf-core/src/test/java/org/opendataloader/pdf/processors/PageBookmarkProcessorTest.java`
  - 新增 `testLevel3ReusesLevel2TemplateWhenSameTemplateLivesInRange`（正向）
  - 新增 `testLevel3PicksDeeperTemplateWhenL2TemplateHasFewerCandidates`（反向）
- `tmp_output/202303251679660111823147.json`、`tmp_output/202303251679660111823147_page_bookmarks_collected.md`
  - DebugSample 重跑后产物，「一、审计报告」下 6 项 L3 已正确出现
- `docs/memory/2026-08-12-page-bookmarks-L2节点孤点value=1丢失与G1+ChainC合并修复.md`
  - 上一步的 fix#1 记忆；本任务是它的下游修正：L2 已恢复后还要恢复 L3