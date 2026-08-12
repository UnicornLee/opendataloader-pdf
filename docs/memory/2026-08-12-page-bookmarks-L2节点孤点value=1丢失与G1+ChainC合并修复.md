# 2026-08-12 — `202303251679660111823147.pdf` L2 节点「一、审计报告」丢失根因与孤点 value=1 合并修复

## 任务背景

用户反馈：用 `org.opendataloader.pdf.DebugSample` 解析

```text
D:\Code\JavaCode\opendataloader-pdf-parse\opendataloader-pdf\docs\pdf\202303251679660111823147.pdf
```

后,`tmp_output/202303251679660111823147_page_bookmarks_collected.md` 显示「第十节 财务报告」下应该挂「一、审计报告」作为 L2 第一个子节点,实际上是「二、财务报表」。需要查明原因、给出方案,用户同意后才可改代码。

可借助的产物：
- `tmp_output/202303251679660111823147.json`（已有解析结果）
- 可通过 `DebugSample` 重跑或定向测试验证

---

## 第一步：JSON 物证收集

观察 `tmp_output/202303251679660111823147.json` 的页面 items（`data[97]` 即页 98 1-based）:

| id | font_size | source_type | content |
|---|---|---|---|
| 1 | 16.08 | heading | 第十节 财务报告 |
| 2 | 12.0  | heading | 一、审计报告 |

两者都正确识别为 heading。所以问题在**候选收集之后的树组装**阶段,而非 item 提取阶段。

接下来 dump「第十节 财务报告」在 `page_bookmarks` 树下的子节点：

```json
{
  "text": "第十节 财务报告",
  "page_num": 98,
  "related_id": 2,        ← 这是 id=2 的 item，即「一、审计报告」
  "children": [
    { "text": "二、财务报表",   "page_num": 100, "related_id": 14 },
    { "text": "三、公司基本情况", "page_num": 126, ... },
    ...
    { "text": "十八、补充资料", "page_num": 199, ... }
  ]
}
```

观察到两点关键事实：
1. 「第十节 财务报告」自身的 `related_id=2`，但页 98 上 id=2 的 item 应该是「一、审计报告」——说明本节点被错误地指向了「一、审计报告」id，而不是自身 id=1。
2. 它的 children 从「二、财务报表」开始，「一、审计报告」完全不在树里。

---

## 第二步：用 python 列全 L2 候选

写脚本把 `data[i].items` 全文过一遍，仅保留 page_index ≥ 第十节页（97）的 heading/paragraph 且文本匹配 `^[一二三四五六七八九十]{1,3}、`：

```python
import re
target_re = re.compile(r"^[一二三四五六七八九十]{1,3}、")
for pi in range(di_section_idx, len(pages)):
    for it in pages[pi].get("items", []):
        ...
        if target_re.match(text):
            print(f"  p{pi+1} id={it.get('id')} fs={it.get('font_size')} text={text}")
```

得到实际候选：

```
p98 id=2  fs=12.0  一、审计报告
p98 id=6  fs=9.12  一、审计意见
p98 id=9  fs=9.12  二、形成审计意见的基础
p98 id=11 fs=9.12  三、关键审计事项
p99 id=19 fs=9.12  四、其他信息
p99 id=24 fs=9.12  五、管理层和治理层对财务报表的责任
p99 id=28 fs=9.12  六、注册会计师对财务报表审计的责任
p100 id=14 fs=12.0 二、财务报表
p126 id=1  fs=12.0 三、公司基本情况
p126 id=6  fs=12.0 四、财务报表的编制基础
...省略四五...
p142 id=1  fs=12.0 七、合并财务报表项目注释
...省略八至十七...
p199 id=8  fs=12.0 十八、补充资料
```

共 24 条 L2 候选,横跨 p98..p199。

---

## 第三步：手算「cleanCandidatesLocal」分组

阅读 `java/opendataloader-pdf-core/src/main/java/org/opendataloader/pdf/processors/PageBookmarkProcessor.java` 的 `cleanCandidatesLocal`（约 670–790 行），把分到「Chinese 一、」(full-width comma, NumberSystem.CHINESE) 模板下的 24 条代入 Step 2 (按 consecutive value 分组)：

| index | candidate | value | group |
|---|---|---|---|
| #1 | 一、审计报告                | 1 | G1 [1] |
| #2 | 一、审计意见                | 1 | G2 [1,2,3,4,5,6]（与 #3..#7 连续） |
| #3 | 二、形成审计意见的基础      | 2 | ↑ |
| #4 | 三、关键审计事项            | 3 | ↑ |
| #5 | 四、其他信息                | 4 | ↑ |
| #6 | 五、管理层…的责任           | 5 | ↑ |
| #7 | 六、注册会计师…的责任       | 6 | ↑ |
| #8 | 二、财务报表                | 2 | G3 [2,3,4,5,6,7,…,18]（17 项连续） |
| #9..#24 | 三、公司基本情况 … 十八、补充资料 | 3..18 | ↑ |

> 关键观察：#1 与 #2 都 value=1，因 Step 2 的「`c.value != prev+1`」判定，prev=#1.value=1, c=#2.value=1,「1 != 2」成立，#2 被切成**新组**。所以 #1 (G1) 单独成组。

Step 4 chain 构建：

| chain | 组 | 值域 | 宽度 |
|---|---|---|---|
| Chain A | [G1] | 1..1 | **1** |
| Chain B | [G2] | 1..6 | **6** |
| Chain C | [G3] | 2..18 | **17** |

Step 5 选最宽：`Chain C` 胜（17 > 6 > 1）。`Chain A` 和 `Chain B` 都被丢弃——这就是「一、审计报告」消失的直接原因。

---

## 第四步：用户期望的合并规则

用户口头给出的合并规则：

> 开头应该分为「一」、「一 / 二 / 三 / 四 / 五 / 六」和「二 / 三 / ... / 十七 / 十八」共 3 组,
> 第 1 组和第 3 组能连起来组成「一 / 二 / ... / 十七 / 十八」共 18 个目录,
> 比第 2 组的 6 个目录长, 所以保留这个, 抛弃第 2 组。

即：把孤立的 G1 (value=1 单点) **prepend 到 Chain C 上**,得到值域 1..18 宽度 18,胜过宽度 6 的 Chain B,Chain B 被丢弃。

---

## 第五步：方案与确认

得到用户四点确认后开始动代码：
- (a) 同意「孤点 value=1 + 紧邻 value=2 chain」是允许的合并前提；
- (b) 同意新增 3 个回归单测；
- (c) 不需要 mvn 全量 + bench；
- (d) 同意用 DebugSample 重跑该 PDF 并写回 `tmp_output/`。

### 修改方案（最小修复）

在 `cleanCandidatesLocal` 的 **Step 4.5 (TOC 过滤)** 与 **Step 5 (选最宽 chain)** 之间插入 **Step 4.7**：

```java
private static void prependValueOneOrphansOntoValueTwoChains(
        List<List<List<Candidate>>> chains) {
    // 把 chain.size()==1 && group.size()==1 && value==1 的单点拆出来
    List<List<Candidate>> orphans = ...;
    List<List<List<Candidate>>> nonOrphans = ...;
    if (orphans.isEmpty()) return;
    for (List<Candidate> orphan : orphans) {
        // 找到首个 first.value==2 的 chain，把 orphan 插到头部
        ...
    }
    chains.clear(); chains.addAll(nonOrphans);
}
```

合并后 Chain C 变成 `[G1] + [二、财务报表 … 十八、补充资料]`，Step 5 计算 `chainMin=1, chainMax=18, length=18`，Chain B 宽度 6 不再胜出。

### 「value-disjoint」的理论保证

Step 2 的分组规则保证任何 chain 内同一 value 只出现一次；G1 是 value=1 单点、value=2 chain 头部必然是 value=2，prepend 不会引发值冲突。

---

## 改动清单

### 1. `java/opendataloader-pdf-core/src/main/java/org/opendataloader/pdf/processors/PageBookmarkProcessor.java`

**Step 4.5 之后新增 Step 4.7 调用：**

```java
// Step 4.5: Discard chains that look like table-of-contents residue.
chains.removeIf(chain -> isTocLikeGroup(flattenChain(chain)));

// Step 4.7: Recover orphaned "value=1" singletons by prepending them
// onto chains that start at value=2. ...
prependValueOneOrphansOntoValueTwoChains(chains);
```

**新增 helper 方法（紧挨 `flattenChain` 之后）：**

```java
private static void prependValueOneOrphansOntoValueTwoChains(
        List<List<List<Candidate>>> chains) {
    if (chains == null || chains.size() < 2) return;
    List<List<Candidate>> valueOneOrphans = new ArrayList<>();
    List<List<List<Candidate>>> nonOrphans = new ArrayList<>(chains.size());
    for (List<List<Candidate>> chain : chains) {
        if (chain.size() == 1 && chain.get(0).size() == 1
                && chain.get(0).get(0).value == 1) {
            valueOneOrphans.add(chain.get(0));
        } else {
            nonOrphans.add(chain);
        }
    }
    if (valueOneOrphans.isEmpty()) return;
    for (List<Candidate> orphan : valueOneOrphans) {
        List<List<Candidate>> target = null;
        for (List<List<Candidate>> chain : nonOrphans) {
            if (!chain.isEmpty() && !chain.get(0).isEmpty()
                    && chain.get(0).get(0).value == 2) {
                target = chain; break;
            }
        }
        if (target == null) {
            List<List<Candidate>> wrapped = new ArrayList<>();
            wrapped.add(orphan);
            nonOrphans.add(0, wrapped);
            continue;
        }
        target.add(0, orphan);
    }
    chains.clear();
    chains.addAll(nonOrphans);
}
```

详细 javadoc 解释 orphan 概念、value-disjoint 保证、多个 orphan 时的匹配顺序。

### 2. `java/opendataloader-pdf-core/src/test/java/org/opendataloader/pdf/processors/PageBookmarkProcessorTest.java`

新增 3 个回归用例：

| 测试 | 输入特征 | 期望 |
|---|---|---|
| `testLonelyValueOnePrependsToValueTwoChain` | 用户 PDF 的完整版（24 条 L2 候选） | 第十节下挂 18 个 L2 子节点,以「一、审计报告」开头,last 是「十八、补充资料」 |
| `testLonelyValueOneNotMergedWhenNoValueTwoChain` | 一、A/B/C/D 四条 1..3 候选,无 value=2 单独 chain | merge 是 no-op,得到原 [一、B, 二、C, 三、D] |
| `testLonelyNonOneNotMerged` | 一、A, 二、B, 五、E（含 value=5 单点,值 3、4 缺） | merge 不触发（孤儿 value=5 不是 value=1）,得到原 [一、A, 二、B] |

> L1 在测试里用「第1章 测试」(value=1 单个,font=20) 而不是「第十节 财务报告」,后者因为第#节模板需要 values.get(0)==1 才能通过 `isValidGroup`,单条第10节会被拒。

---

## 关键决策与坑点

### `cleanCandidatesLocal` 是私有，但 `extractPageBookmarks` / `extractPageBookmarksFromJson` 公开

测试改用这两个公开入口即可，不必反射私有方法。

### `multiPage` 工具方法的限制

传入的 L1 文本若字号大(>其他 candidates),L1 模板选举就能挑中。第#章 单值 1 通过 `isValidGroup` 的 `values.size()==1 && values.get(0)==1` 分支;第#节 只有 `values.get(0)==1` 早返——「第十节」value=10 单独一只就被拒,这是第一次测试 `bookmarks.size()==1` 出 18 的真因(整组 L2 candidates 顶上去了)。

### Stash 反弹坑

中途为对照测试基线需要 `git stash`, 被 Maven 触发的 `samples/json/lorem.{js,json}` 改动挡了 stash pop;后续先用 `git restore samples/json/lorem.{js,json}` 再 stash pop 才成功。38 个 E2E 集成测试失败经 `git stash` 对照确认是**预先存在的**(Markdown 输出/PDF fixture 相关),与本页修改无关。

### 设计的边界覆盖

- 多 orphan 全部 prepend：循环里每个 orphan 都尝试 prepend 到第一个 value=2 chain;还有剩余 chain 不够时,orphan 仍作为独立 chain 参与 Step 5 竞争。
- 多个 value=2 chain 时:只 prepend 到**第一个** value=2 chain(阅读顺序上最早),覆盖典型"只有一个财务表跨页"场景。

---

## 验证结果

| 步骤 | 结果 |
|---|---|
| `mvn -pl opendataloader-pdf-core test -Dtest=PageBookmarkProcessorTest` | **34/34 通过**（含 3 个新增用例） |
| `git stash` 对照其余 E2E 测试 | 38 个失败在 stash 前后同样存在, **与本修改无关** |
| `mvn -pl opendataloader-pdf-core exec:java -Dexec.mainClass=org.opendataloader.pdf.DebugSample` | 重跑原 PDF,日志显示 `[BookmarkQualitySelector] page: total=312`（修复前 309,新增 3 项） |
| `tmp_output/202303251679660111823147.json` | 第十节 财务报告 下挂 18 个 L2,首项「一、审计报告」(p98) ,末尾「十八、补充资料」(p199) |

具体子节点(从 JSON 实际 dump):

```
第十节 财务报告 (p98)
├── 一、审计报告 (p98)            ← 修复后新增
├── 二、财务报表 (p100)
├── 三、公司基本情况 (p126)
├── 四、财务报表的编制基础 (p126)
├── 五、重要会计政策及会计估计 (p126)
├── 六、税项 (p140)
├── 七、合并财务报表项目注释 (p142)
├── 八、合并范围的变更 (p173)
├── 九、在其他主体中的权益 (p175)
├── 十、与金融工具相关的风险 (p178)
├── 十一、公允价值的披露 (p180)
├── 十二、关联方及关联交易 (p181)
├── 十三、股份支付 (p183)
├── 十四、承诺及或有事项 (p190)
├── 十五、资产负债表日后事项 (p191)
├── 十六、其他重要事项 (p192)
├── 十七、母公司财务报表主要项目注释 (p194)
└── 十八、补充资料 (p199)
```

page_bookmarks 和 bookmarks(catalog) 两个树结构完全一致。

`tmp_output/202303251679660111823147_page_bookmarks_collected.md` 中:

```
|98|第十节 财务报告|
|98|一、审计报告|         ← 直接挂在第十节下
|98|一、审计意见|          ← 之后跟随 G2 的子项
...
|99|六、注册会计师…的责任|
|100|二、财务报表|
...
```

「一、审计意见 … 六、注册会计师…的责任」(原 G2 chain, 6 项) 被丢弃,符合用户口头规则「第 1 组和第 3 组连起来组成 18 个目录, 比第 2 组的 6 个目录长, 抛弃第 2 组」。

---

## 相关文件

- `java/opendataloader-pdf-core/src/main/java/org/opendataloader/pdf/processors/PageBookmarkProcessor.java`
  - Step 4.5 之后新增调用 `prependValueOneOrphansOntoValueTwoChains(chains)`
  - 新增私有 helper `prependValueOneOrphansOntoValueTwoChains(...)`,详细 JavaDoc 解释 orphan 概念与 value-disjoint 保证
- `java/opendataloader-pdf-core/src/test/java/org/opendataloader/pdf/processors/PageBookmarkProcessorTest.java`
  - 新增 3 个回归用例 `testLonelyValueOnePrependsToValueTwoChain` / `testLonelyValueOneNotMergedWhenNoValueTwoChain` / `testLonelyNonOneNotMerged`
- `tmp_output/202303251679660111823147.json`、`tmp_output/202303251679660111823147_page_bookmarks_collected.md`
  - DebugSample 重跑后产物,以「一、审计报告」开头的 18 项 L2 子节点已正确出现
- `java/opendataloader-pdf-core/src/main/java/org/opendataloader/pdf/DebugSample.java`
  - 用户此前已配置目标 PDF 为 `202303251679660111823147.pdf`,本任务未改其逻辑
