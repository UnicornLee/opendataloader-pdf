# processLineArts N×M 扇出 OOM：由上游 `5d59d2dd` 引入、由上游 `eb9b71f8` 官方修复 —— 取证与对齐建议

> 关联 fork：`D:/Code/JavaCode2/veraPDF-validation`（branch `valueonline-20260722`，remote `origin` = UnicornLee/veraPDF-validation）
> 关联官方仓库：`github.com/veraPDF/veraPDF-validation`
> 关联文件：`wcag-validation/src/main/java/org/verapdf/gf/model/factory/chunks/ChunkParser.java`
> 关联记忆：
> - `common_pitfalls_experience / ChunkParser.processLineArts 给每条LineChunk挂全量StreamInfo导致O(N×M)堆溢出`
> - `project_introduction / processLineArts冗余循环的引入与安全删除依据`
> 触发场景：`DebugSample1` 处理 `docs/pdf/201709201783166134141014381.pdf`，`-Xmx` 加到多少都不够。

## 一、结论先说

1. 那段把窗口全量 `pending` 深拷贝到每条 `LineChunk` 的 N×M 扇出代码，**不是我们 fork 的原创**，是上游 vera 官方提交 `5d59d2dd`（作者 Bundo Lee `<leebd@hancom.com>`，2026-09-01 撰写，2026-09-14 由 vera 核心 Maxim Pliushchov 合入 `integration`）。
2. **上游已在 6 周后自己修掉**：官方提交 `eb9b71f8`「Give each line chunk only the operator that painted it」，作者同上，2026-10-19 撰写，2026-10-20 由 Maxim Pliushchov 合入。上游 commit message 里给出的现象与我们的完全一致：*"A 55-page paper (one form with 7,500 strokes) made 56 million copies and ran out of a 2 GB heap in parseChunks; a 2.6 MB document made 325 million and failed at 5 GB."*
3. 我们本 session 手工"纯删"的修法，**方向与官方一致**（都删掉 `processLineArts` 里的双重循环、区域全量仅挂到 `LineArtChunk`）；**差异**在于官方还多做了一步：让每条 `LineChunk` 在 `processLineChunk` 里携带"自己那一个 operator"（L 条而非 L×P 条），以支撑 `odl-pdfua` 的 `SemanticMapper` 从 line 反查所属窗口。opendataloader 自己的 tagger 不读 line art 的 stream infos，所以对 opendataloader 无影响；若未来接入 odl-pdfua 那条链路，应对齐官方实现。

## 二、上游取证时间线（同一文件 `ChunkParser.java`）

通过 GitHub API 拿到的近期历史（`?path=.../ChunkParser.java`）：

| sha | 日期 | 说明 |
|---|---|---|
| `eb9b71f8` | 2026-10-19 | **Give each line chunk only the operator that painted it**（本次 OOM 修复） |
| `8e8d6941` | 2026-09-17 | Use `TEXT_SPACE_UNIT` constant in `TextChunksHelper` |
| `5d59d2dd` | 2026-09-01 | **Give line art chunks the stream position of the operator that painted them**（引入 N×M 扇出） |
| `2d86a4f2` | 2026-08-20 | Add alternative source for Ascent/Descent for Type3 fonts (#753) |
| … |  | 更早无关 |

两次都是 Bundo Lee 撰写、Maxim Pliushchov 合入 `integration`，均为 vera 官方提交。fork 通过 `origin/integration` 承接了 `5d59d2dd`，但**尚未跟进 `eb9b71f8`**（fork 分支 `valueonline-20260722` 停在 9 月中旬）。

## 三、官方 `eb9b71f8` 的实际 diff（两段）

### 3.1 `processLineChunk`：line 携带自身 operator

```java
private void processLineChunk(BoundingBox boundingBox, Long mcid, LineChunk lineChunk,
                              int operatorIndex) {
    lineArtContainer.add(mcid, lineChunk, operatorIndex, xObjectName);
    if (StaticStorages.getIsIgnoreMCIDs()) {
        boundingBox.union(lineChunk.getBoundingBox());
        // The line carries only the operator that painted it. The region's
        // full list goes once onto the LineArtChunk that processLineArts
        // builds, whose box holds the line, and a consumer that needs the
        // whole region finds that chunk through this operator and that box.
        // Giving every line the full list cost lines x operators objects: one
        // chart of 7,500 strokes made 56 million and ran out of heap.
        if (StaticContainers.isDataLoader()) {
            lineChunk.getStreamInfos().add(new StreamInfo(operatorIndex, xObjectName, null));
        }
    }
}
```

### 3.2 `processLineArts`：删掉 N×M 双重循环

```java
public void processLineArts() {
    if (!StaticStorages.getIsIgnoreMCIDs()) { return; }
    List<StreamInfo> pending = lineArtContainer.getStreamInfos(null);
    List<LineChunk> lineChunks = lineArtContainer.getLineChunks(null);
    if (lineChunks != null && !lineChunks.isEmpty()) {
        // Each line already holds its own operator (processLineChunk). The
        // region's list is attached to the boxes below only, once per box.
        artifacts.addAll(lineChunks);
        lineChunks.clear();
    }
    List<BoundingBox> boundingBoxes = lineArtContainer.getBoundingBoxes(null);
    if (boundingBoxes != null && !boundingBoxes.isEmpty()) {
        for (BoundingBox box : boundingBoxes) {
            LineArtChunk artifact = new LineArtChunk(box);
            for (StreamInfo info : pending) {
                artifact.getStreamInfos().add(new StreamInfo(info));
            }
            artifacts.add(artifact);
        }
        boundingBoxes.clear();
    }
    lineArtContainer.clearStreamInfos(null);
}
```

内存量级：**L + P**（每 line 一条 + 每 box 一次全量），彻底告别 **L × P**。

## 四、我们本 session 手工删的 diff（对齐 3.2，缺 3.1）

本仓库 fork `D:/Code/JavaCode2/veraPDF-validation/wcag-validation/.../ChunkParser.java`，提交 `e782d4e6`（"fix(chunks): stop copying the full StreamInfo list onto every line chunk in processLineArts"）：

```java
List<LineChunk> lineChunks = lineArtContainer.getLineChunks(null);
if (lineChunks != null && !lineChunks.isEmpty()) {
    artifacts.addAll(lineChunks);   // 原样加，不再挂 infos
    lineChunks.clear();
}
// boundingBoxes 循环保持：每个 LineArtChunk 挂一次全量 pending
```

**与官方 `eb9b71f8` 的差异**：没有 `processLineChunk` 中给每条 line 挂自身 operator 那一行。对 opendataloader 无影响（其 tagger 不读 line art stream infos），但**语义比官方弱**：若下游需要"从某条 line 反查它属于哪个 Region 窗口"（`odl-pdfua.SemanticMapper` 就依赖这点），我们的纯删会让该反查失效。

## 五、影响面与遗留风险（来自 `eb9b71f8` commit message）

1. **仅 `StaticContainers.isDataLoader()` 为 true 时受影响**；vera 官方 `validation` 流程默认关闭，不进此路径，validation 侧行为不变。
2. `parseLineArts`（`ignoreMCIDs=false`，即 core 的 `--use-struct-tree` 路径）**这次没修**，仍是 `boxes × operators`。若哪天 opendataloader 切到不 ignore MCIDs，同样有 OOM 风险，届时需按官方 `eb9b71f8` 思路再改一次。
3. `eb9b71f8` 要求 **`odl-pdfua` 侧 `SemanticMapper` 配套改动先落地**（"Land that change before this one ships"），否则从 line 反查窗口的通路断。fork 若要 cherry-pick 该 commit，需评估 odl-pdfua 是否已同步。

## 六、后续建议

- **首选**：把 fork `valueonline-20260722` **cherry-pick 上游 `eb9b71f8`**，回退本仓库的手工删版本 `e782d4e6`，与官方语义一致。opendataloader 端 1.31.100 的 deploy 与 pom 已钉死 parser/pdfmodel/library/wcag.algs，cherry-pick 后重跑 `mvn deploy` 出 `1.31.101-valueonline` 即可，无需再动 opendataloader 引用以外的东西。
- **不首选**：继续维护我们的手工"纯删"。短期能跑，但每次上游 rebase 都可能撞二次冲突，且缺 line→operator 关联，`odl-pdfua.SemanticMapper` 接入时会踩。

## 七、取证用到的命令（复用价值）

- 本地：`git log -S 'lineChunk.getStreamInfos().add(new StreamInfo(info))' --oneline -- <file>` → 定位引入 commit。
- 本地：`git show --no-patch --format="%H %an %ae %ad %s" <sha>` → 拿到作者/日期。
- 判断 commit 是否在**官方仓库**（而非 fork 独有）：`https://api.github.com/repos/veraPDF/veraPDF-validation/commits/<sha>`，200 且能拿到 `files[]` → 属于官方；`{"message":"No commit found for string: ..."}` → 仅 fork。
- 列官方某文件近期历史：`https://api.github.com/repos/veraPDF/veraPDF-validation/commits?path=<file>&per_page=20`，用 PowerShell `ConvertFrom-Json` 抽 `sha / commit.author.date / (message -split '\n')[0]`。
- 拿修复 commit 的 diff：直接读上一步 JSON 的 `files[].patch` 字段。

> 备注：GitHub MCP 本次凭据失效（`Authentication Failed: Bad credentials`），全部通过 `WebFetch` + api.github.com JSON 完成，公开仓库无需 token。
