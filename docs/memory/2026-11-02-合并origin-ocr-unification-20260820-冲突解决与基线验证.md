# 2026-11-02 合并 `origin/ocr-unification-20260820`：冲突解决与测试基线验证

## 0. 背景

本地分支 `ocr-unification-20260820` 与远端分叉：**领先 185 个提交、落后 268 个提交**。
用户要求「更新代码，有冲突就解决」，本次执行 `git fetch` + `git merge`。

- 合并前安全点：分支 `backup/ocr-unification-20261102-premerge`（指向 `fc08748a`）
- 工作区未提交改动（`in-/out-` 临时目录清理那一批）先 `git stash push`（只 stash 已跟踪文件，
  未跟踪的 `docs/pdf`、`Tmp*.java` 等保持在原地），合并完成后再 `git stash pop`（干净，无冲突）
- 合并结果提交：`b4ca0836`，合并后 `ahead 186 / behind 0`

## 1. 冲突清单与解决决策

冲突文件 7 个（`git merge-tree --write-tree` 可预先干跑得到同一列表，不必先改工作区）：

| 文件 | 冲突性质 | 决策 |
| --- | --- | --- |
| `java/pom.xml` | veraPDF 依赖版本 | 取远端 `1.31.101-valueonline` + `verapdf.parser.version=1.31.68`；本机仓库已存在这两个 artifact，离线可解析 |
| `options.json` | 双端都新增 CLI 选项 | 取远端（新增 `sanitize-rules`，并保留 `catalog-bookmark-min-lines/min-ratio`），缩进统一为 4 空格 |
| `api/cli/CLIOptions.java` | 远端新增 `sanitize-rules` 常量 | 取远端；**另发现双端各自新增了同名 `applyConvertHalfWidthOption`，自动合并成两份 → 手工删一份** |
| `json/JsonWriter.java` | 远端新增 NUL 转义（`CharacterEscapes`） | 取远端 |
| `processors/ExtractionResult.java` | 本地加了 `pageHaveStreamTables/pageHaveFormulas`，远端加了 `PDDocument document` + `AutoCloseable.close()` | **两者并存**：字段/构造链保留远端 7 参版本，本地的 6/5/4 参便捷构造按新签名补 `null` |
| `processors/DocumentProcessor.java` | 本地在 `new ExtractionResult(...)` 后没有 document 参数；远端新增 `pruneArtifactsToLiveChunks/pruneLineChunksFromArtifacts` | 取远端（构造调用补 `StaticResources.getDocument()`，保留两个 prune 方法） |
| `test/.../ContentSanitizerTest.java` | 远端新增 stream-info 相关测试与 import | 取远端（89 个用例全绿） |

> 结论：7 个冲突里只有 `ExtractionResult` 是真正的「双端各加一个特性」，其余都是「一端新增」。

## 2. 验证

编译（离线）：

```
cd java && mvn -o compile -DskipTests     → BUILD SUCCESS（core 160 源文件 / cli / server）
```

测试：

| 范围 | 结果 |
| --- | --- |
| `ContentSanitizerTest`（冲突文件） | 89 passed |
| server 模块全量 | 31 passed（含 lorem.pdf 真样本端到端临时目录清理用例） |
| core 模块全量 | `Tests run: 1040, Failures: 52, Errors: 30` |

**core 约 80 个失败是不是合并引入的？不是。** 用
`git worktree add tmp_output/remote-check origin/ocr-unification-20260820` 在纯远端提交
（`f41bf504`）上跑同一套测试作为基线：

| 代码基线 | 结果 |
| --- | --- |
| 纯远端 `f41bf504` | `Tests run: 1040, Failures: 54, Errors: 30` |
| 本次合并 `b4ca0836` | `Tests run: 1040, Failures: 52, Errors: 30` |

即：合并后失败数比远端基线**少 2**，用例总数一致 → 合并本身是忠实的，没有引入新红。
典型存量失败原因（与本次改动无关）：

- `AutoTaggerTest` / `Issue779ExtractionCloseTest` / `DocumentProcessorArtifactPruneTest`：
  `DocumentProcessor.java:751` 的 `new File(config.getOutputFolder()).mkdirs()` 在
  `Config.outputFolder == null` 时抛 NPE（`AutoTagger.tag` 走的就是这条路径）。该行在
  **合并基、本地、远端三份代码里完全一致**，属于存量缺陷，不是冲突解决引入。
- `DocumentProcessorParallelFailureTest`：断言 `exactly "Parallel page processing failed"`，
  实际消息带 `(IllegalStateException: ...)` 后缀，断言过期。

验证完清理：`git worktree remove --force tmp_output/remote-check`。

## 3. 踩坑

- **`.gitignore` 第 78 行 `**/temp/`**：如果新包命名为 `server/temp`，`git status` 里完全看不到文件，
  提交时极易漏掉。本次临时目录清理代码最终落在 `server/workspace`。自查用 `git check-ignore -v <path>`。
- **双端各自新增同名私有方法**：git 只在「同一区域文本不同」时冲突，两个不相邻位置的同名方法
  会安静地合并成两份，直到 `javac: 已在类中定义了方法` 才暴露。合并后必须先编译。
- **veraPDF 版本与 parser 版本必须同步**（远端注释已写明）：1.31.100 分支的 validation 调用
  `PDNameTreeNode.getObject(COSString)`，只在 parser ≥ 1.31.68 存在；单升一个会变成
  `NoSuchMethodError`。本次取远端 `1.31.101-valueonline` + parser `1.31.68`。
- 合并前 `git stash push`（不加 `-u`）比 `-u` 安全：仓库里有上百个未跟踪的 `docs/pdf/*.pdf`
  跑测样本，`-u` 会把它们一起搬走，一旦中断很难恢复。

## 4. 回滚方式

```
git reset --hard backup/ocr-unification-20261102-premerge   # 丢弃这次合并
git stash pop                                               # 如需，恢复未提交改动
```

## 5. 相关历史文档

- `2026-09-15-Pulsar消费停摆根因-单条超大扫描件占满消费槽位与超时看门狗实现.md`
- `2026-09-10-Pulsar消费者ackTimeout兜底防止卡死消息.md`
- `2026-11-02-生产in-out临时目录残留-根因定位与四层清理兜底.md`（本次合并期间被 stash/restore 的未提交改动）
