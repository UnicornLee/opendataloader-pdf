# 2026-08-12 — Parallel page processing failed 根因 + ThreadLocal 传播修复

## 任务背景

线上 Pulsar 消费者打日志：

```
handleReceiveMessage failed, businessId=1679396420670667_1332584650418077696: Parallel page processing failed
```

业务方需要确认：
1. 这个错误实际是什么（不是单一异常）
2. 最可能的根因
3. 落到代码上的修复方案

## 根因（两段式）

### 根因 1：两层 cause chain 嵌套

| 层 | 位置 | 说明 |
|---|---|---|
| 外层 | `java/opendataloader-pdf-server/src/main/java/org/opendataloader/pdf/server/pulsar/PulsarService.java:261` | `log.error("handleReceiveMessage failed, businessId={}: {}", businessId, e.getMessage(), e)` |
| 内层 | `java/opendataloader-pdf-core/src/main/java/org/opendataloader/pdf/processors/DocumentProcessor.java:574`（修复前 563） | `throw new IOException("Parallel page processing failed", e)` |

`PulsarService` 把异常吞掉只记 message；真正抛异常的代码点在 `DocumentProcessor` 第二个 `pool.submit(...).get()` 块被 try-catch 改写为 `"Parallel page processing failed"`。**Kibana 上 message 字段看不到 cause chain**，要看 `error.stack_trace` 完整栈。

### 根因 2：bookmark 特性引入的 ThreadLocal 未传播

近期 commit 在 `StaticLayoutContainers` 新增 4 个 ThreadLocal 字段，但 `DocumentProcessor.propagateState.run()` 没同步：

| 字段 | 来源 commit |
|---|---|
| `catalogBookmarks` | `9fe705b` feat: implement catalog and page bookmark extraction from document contents (2026-07-30) |
| `pageBookmarks` | `9fe705b` |
| `catalogBookmarkStartPage` | `fceeb31` feat: add catalog bookmark page range handling in StaticLayoutContainers (2026-07-31) |
| `catalogBookmarkEndPage` | `fceeb31` |

CLAUDE.md 已明确警告过这条坑：

> Processing uses `ForkJoinPool(availableProcessors)` for per-page parallelism. All `StaticContainers` and `StaticLayoutContainers` ThreadLocal state must be propagated to worker threads via `propagateState.run()` — missing a ThreadLocal causes silent data loss or NPE in parallel mode.

`DocumentProcessor.processDocumentContent` 在 4 个 `pool.submit(...).get()` 块（L361/447/494/507）外层 catch 任何 worker 抛出的异常并改写为静态 message，**丢失页码和子模块信息**。这就是线上同一 message 频繁出现但根因各不相同的根本原因。

## 修复策略

按计划分三步：

1. **补 propagation**（核心修复）：把 4 个新 ThreadLocal 加到 `propagateState` 之外，还为 bookmark list 加 map-level 引用 setter。
2. **改善错误信息**：catch 块把 `ExecutionException.getCause()` 的类名 + 消息附到 IOException message。
3. **回归测试**：3 个单测守护 4 个字段的主线程→worker 引用传递。

## 改动清单

### 1. `java/opendataloader-pdf-core/src/main/java/org/opendataloader/pdf/containers/StaticLayoutContainers.java`

新增 4 个 map-level 访问器，跟既有 `setReplacementCharRatiosMap` / `setEmbeddedImageBytesMap` 模式对齐：

```java
public static List<Bookmark> getCatalogBookmarksMap()      // 引用共享 getter
public static void setCatalogBookmarksMap(List<Bookmark>)  // 直接 ThreadLocal.set（不复制）
public static List<Bookmark> getPageBookmarksMap()
public static void setPageBookmarksMap(List<Bookmark>)
```

**为什么必须加新 setter**：原 `setCatalogBookmarks(List)` / `setPageBookmarks(List)` 是「数据替换」语义：

```java
public static void setCatalogBookmarks(List<Bookmark> bookmarks) {
    List<Bookmark> current = catalogBookmarks.get();  // 拿到 worker 自己 ThreadLocal 的 LinkedList
    current.clear();
    if (bookmarks != null) {
        current.addAll(bookmarks);
    }
}
```

如果直接拿这个 API 传播，worker 拿到的是**自己 ThreadLocal-initial 的 LinkedList**，而主线程数据被 `clear+addAll` 拷进去——`Object#equals` 通过但**引用不是同一个**。新一轮的 worker 启动时拿到的又是各自 ThreadLocal 内的孤立 `LinkedList`，跟 `setHeadings(headings)` 的 `headings.set(headings)` 引用替换语义不一致，也跟既有 `setReplacementCharRatiosMap` 模式不一致。修改后的 map setter 直接 `catalogBookmarks.set(bookmarks)`，worker 拿到的是主线程同一引用。

> 这条 bug 是新写的回归测试在第一轮跑时直接 assertSame 失败的，证据见「验证结果」一节。

### 2. `java/opendataloader-pdf-core/src/main/java/org/opendataloader/pdf/processors/DocumentProcessor.java`

#### 2.1 新增 import
- `org.opendataloader.pdf.custom.entities.Bookmark`
- `java.util.concurrent.ExecutionException`

#### 2.2 捕获段追加（行 316-319 附近）

```java
final List<Bookmark> catalogBookmarks = StaticLayoutContainers.getCatalogBookmarks();
final List<Bookmark> pageBookmarks = StaticLayoutContainers.getPageBookmarks();
final int catalogBookmarkStartPage = StaticLayoutContainers.getCatalogBookmarkStartPage();
final int catalogBookmarkEndPage = StaticLayoutContainers.getCatalogBookmarkEndPage();
```

#### 2.3 `propagateState.run()` 末尾追加（用 map setter 而非复制 setter）

```java
StaticLayoutContainers.setCatalogBookmarksMap(catalogBookmarks);
StaticLayoutContainers.setPageBookmarksMap(pageBookmarks);
StaticLayoutContainers.setCatalogBookmarkPageRange(catalogBookmarkStartPage, catalogBookmarkEndPage);
```

#### 2.4 catch 块改善

把：

```java
} catch (Exception e) {
    throw new IOException("Parallel page processing failed", e);
}
```

改为：

```java
} catch (Exception e) {
    // Unwrap ForkJoinPool's ExecutionException so the IO message names the real cause
    // (NPE from a missed ThreadLocal propagation, UncheckedIOException from
    // ContentFilterProcessor, RuntimeException from StreamTableProcessor, etc.).
    Throwable cause = (e instanceof ExecutionException && e.getCause() != null) ? e.getCause() : e;
    throw new IOException("Parallel page processing failed ("
            + cause.getClass().getSimpleName() + ": " + cause.getMessage() + ")", e);
}
```

`PulsarService.java:261` 的 `e.getMessage()` 现在能拿到具体子异常名（`NullPointerException` / `UncheckedIOException` / `RuntimeException`），Kibana message-only 查询可一次定位。

### 3. `java/opendataloader-pdf-core/src/test/java/org/opendataloader/pdf/processors/DocumentProcessorPropagationTest.java`（新增）

3 个 JUnit 5 测试，模拟 worker 线程跑 `propagateState` 同样的 setter 链：

- `catalogBookmarks_propagateToWorkerThread` — `assertSame` 主线程 ArrayList 与 worker 读到的是同一引用
- `pageBookmarks_propagateToWorkerThread` — 同上
- `catalogBookmarkPageRange_propagateToWorkerThread` — `assertEquals(7/9)` 验证 start/end page 都正确传递

测试主体用 `CountDownLatch` + `AtomicReference<T>` 同步 worker 结果，超时 5s 防死锁。

## 关键决策

### map-level setter vs 复制 setter 的选择
直接复用既有命名模式（`setEmbeddedImageBytesMap` / `setReplacementCharRatiosMap`）。虽然 Map 后缀在 list 上有点 awkward，但项目本来就在用。**没改名也没改原 setter 签名** → 不破坏其它调用方。

### 复制 setter 没有删
`setCatalogBookmarks(List)` / `setPageBookmarks(List)` 留作「数据替换」API，仍用于 BookmarkUtils/JsonWriter 这些「载入外部数据」场景。propagateState 改用新的 `*Map` setter。

### catch 改善的 message 形态
选了「类名 + message」格式而不是「类名 + 详细 JSON」：
- `Parallel page processing failed (NullPointerException: ...)` —— 一行能扫
- 仍然 `throw new IOException(..., e)` 保留 cause 实例，让 `error.stack_trace` 字段包含完整栈
- `Throwable` 而不是 `Exception`，覆盖 InterruptedException 等非 Exception 异常（ForkJoinPool 实际只抛 Exception/Error，但防御性写法）

### 没动内容
- 没碰 `imagesDirectory` / `imageIndex` / `embedImages` / `imageFormat` —— 它们的 propagate 需求不在本次报错栈里
- 没碰 `StaticContainers`（veraPDF）—— 那是外部 jar，本次改动已验证无误

## 验证结果

| 测试 | 结果 |
|---|---|
| `DocumentProcessorPropagationTest`（新增）第一轮 | ❌ 2/3 失败（`pageBookmarks` 和 `catalogBookmarks` 的 `assertSame`）—— 这正是发现「setter 是 copy 语义 bug」的证据 |
| 加 `setCatalogBookmarksMap` / `setPageBookmarksMap` + `propagateState` 改用新 setter | — |
| `DocumentProcessorPropagationTest`（新增）第二轮 | ✅ 3/3 通过 |
| `StaticLayoutContainersTest` | ✅ 通过 |
| `PageBookmarkProcessorTest` | ✅ 通过（exit 0，17 个 case） |
| `BookmarkQualitySelectorTest` | ✅ 通过 |
| 整库 `rebuild` | ✅ success，遗留 deprecation warning 跟本改动无关 |

### 第一轮测试失败的具体输出

```
expected: java.util.ArrayList@... <[Bookmark@...]>
but was:  java.util.LinkedList@... <[Bookmark@...]>
```

确认了 worker 拿到的是 ThreadLocal 初始的 LinkedList 而不是主线程的 ArrayList 引用。这一行失败信息直接驱动了 map-level setter 的加入。

## 修复后 Kibana 上能看到的变化

修复前：
```
handleReceiveMessage failed, businessId=...: Parallel page processing failed
```

修复后（带子异常类型）：
```
handleReceiveMessage failed, businessId=...: Parallel page processing failed (NullPointerException: ...)
```
或：
```
handleReceiveMessage failed, businessId=...: Parallel page processing failed (UncheckedIOException: ...)
```

## 待跟进

- **生产部署验证**：拿线上 `businessId=1679396420670667_1332584650418077696` 对应的源 PDF，到 `java/opendataloader-pdf-core/src/main/java/org/opendataloader/pdf/DebugSample.java`（git status 显示最近被修改过）跑 threads=4 复现，确认不再抛 `Parallel page processing failed`。
- **部署到 prepub-sz**：等用户拍板 commit + 推送。
- **现有 `ThreadLocal` 静态扫描**：可以加一个反射式守护测试 —— 枚举 `StaticLayoutContainers` 所有 `ThreadLocal` 字段，对比 `DocumentProcessor.propagateState` 实际调用过的 setter 集合，把漏掉的字段编译期/测试期 fail。本次没做，因为：
  - 需要反射枚举 private 字段，测试代码复杂度上一个台阶
  - 当前 fix 已经在三条核心路径上加了 `assertSame` 约束，新增 bookmark 字段时这套测试会被人看到
  - 把维护成本压在「人」的层面已经可行
- **`PageBookmarkProcessor.java:942-943`** —— `collectCandidates` 内部读 `getCatalogBookmarkStartPage()` 和 `getCatalogBookmarkEndPage()`，目前只在 main thread 路径调用。如未来改成 worker 调用，propagateState 已经包了；如不放心，把这两个读也改成 `getCatalogBookmarkStartPage()` 的 page-range getter（已有 null-safe 默认值 -1）即可。

## 相关文件

- `java/opendataloader-pdf-core/src/main/java/org/opendataloader/pdf/containers/StaticLayoutContainers.java` —— 新增 4 个 map-level setter/getter
- `java/opendataloader-pdf-core/src/main/java/org/opendataloader/pdf/processors/DocumentProcessor.java` —— propagateState 补 4 个字段；catch 块改善
- `java/opendataloader-pdf-core/src/test/java/org/opendataloader/pdf/processors/DocumentProcessorPropagationTest.java` —— 新增回归守护
- `java/opendataloader-pdf-server/src/main/java/org/opendataloader/pdf/server/pulsar/PulsarService.java:261` —— 外层日志点（未改）
- `docs/CLAUDE.md` —— 「Gotchas」段关于 ThreadLocal 传播的警告是核心依据
