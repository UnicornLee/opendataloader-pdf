# opendataloader-pdf 任务记忆 — 2026-09-10（catalog 子节点从页面数据补全：弃用 pageBookmarks.children，改按相邻 catalog 同级 [T, S) 切片）

## 目标（Goal）

修复 `CatalogBookmarkProcessor.fillCatalogChildrenFromPageData` 的 catalog 子节点补全逻辑：当 catalog 自身 L1/L2 节点已存在但 `children=0`（"空目录"）时，从 page candidate 池里按相邻 catalog 同级 `[T, S)` 半开区间重新切出 L2/L3 子树，而不是借 `pageBookmarks.children`。

预期效果：以 `202609081788820439508064469.pdf` 为基准，所有 32 个顶级目录项 `children=0` 全部消失，且 `第四章 股東和股東會`、`第五章 董事會`、`第九章 合併...`、`第十一章 附則` 等原本被错误注入"（五）審計委員會提議召開時；" body 段落的 catalog 节点保持干净的 `children=0`。

---

## 背景链路（改动前）

`OpenDataLoaderPDF.processFile` 输出 JSON 里的 `catalog_bookmarks` 树由两步组成：

1. catalog 检测：`CatalogBookmarkProcessor` 直接读 PDF outline / 解析目录页文字，得到 `List<Bookmark> catalogBookmarks`（顶层 L1 + 部分 L2，L2/L3 子树通常不全）。
2. catalog 补全：`CatalogBookmarkProcessor.fillCatalogChildrenFromPageData(data, catalogStartPage, catalogEndPage, catalogBookmarks, pageBookmarks)` —— 当某个 catalog L1 没有 children 时，借 `pageBookmarks` 里 anchor 命中的那个节点的 children，再降级为该 catalog 节点的 children。L3 同理：catalog L2 没有 children 时，借 pageBookmarks 的 L3 children。

调用链：

```
OpenDataLoaderPDF.processFile
  └─ JsonWriter.writePageJsonMap(...)        [L1: 写 catalog_start/end]
       └─ CatalogBookmarkProcessor.fillCatalogChildrenFromPageData(data, ..., catalogBookmarks, pageBookmarks)
                                                              ↑
                                              旧实现：pageIndex.get(catalogTop) → 复用其 children
```

`pageBookmarks` 本身来自 `PageBookmarkProcessor.processPageBookmarks(...)`，按"已 cleaning 的 L1 (第#章) 切片"组织（`extractLevel` 内部 L1 用 `nextIndexAfter(levelOneIndices, anchorIndex, all.size() - 1)` 找下一个 L1 候选），所以 pageBookmarks 里 catalogTop 的 children 是"从 catalogTop 到下一个 cleaned L1 candidate"之间的所有 L2/L3。

---

## 现象（problem statement）

测试 PDF `202609081788820439508064469.pdf` 跑完后，`catalog_bookmarks` 顶层有 32 个 L1，但其中 4 个（"第四章 股東和股東會"、"第五章 董事會"、"第九章 合併..."、"第十一章 附則"）的 `children` 数组里被塞进了正文段落，例如：

```json
{
  "text": "第四章 股東和股東會",
  "page_num": 20,
  "children": [
    { "text": "（五）審計委員會提議召開時；" },
    { "text": "（六）過半數董事請求時；" },
    ...
  ]
}
```

肉眼一看就知道是错的："第四章"标题下不该出现 "(五)審計委員會提議召開時" 这样的 body 段（这本就是后续章节的正文条目，不是 catalog 条目）。其它 28 个 L1 的 `children=0`（空目录）也存在，但那些是因为 PDF outline 本身就没列出 L2，是合理的"空"。

问题在 `fillCatalogChildrenFromPageData` —— 它**借** pageBookmarks 的 children 当作 catalog 的 children，但 pageBookmarks 的 L1 切片范围是"到下一个 cleaned 第#章 candidate"（跨越整个章节），而 catalog 的真实下一个同级是 catalog 自己的下一个 L1（通常隔几页），两者切片边界不一致，结果 pageBookmarks 里跨越整个章节的几十条 body 段全被塞进那个 catalog L1。

---

## 完整定位过程（按 systematic-debugging 流程）

### 阶段 1：复现 + 收集证据

在 `DebugSample` 里把测试 PDF 路径指到 `D:\Code\JavaCode\opendataloader-pdf-parse\opendataloader-pdf\docs\pdf\202609081788820439508064469.pdf`，跑 `OpenDataLoaderPDF.processFile`，输出到 `tmp_output/...json`。

读 JSON，找到 4 个异常 catalog L1，手动把 `第四章 股東和股東會` 的 children 文本贴出来对比 PDF 原文：children 里的"（五）審計委員會提議召開時；"在 PDF 原文是第六章 董事會下的 body 段（介绍审计委员会职责），不是 catalog 条目。

加临时 `LOGGER.log(Level.INFO, "[DEBUG-CATALOG-CHILD] top='%s' n=%d src=%s", catalogTop.getText(), n, pageTop == null ? "null" : "pageBookmark")` 打印每次"补全"时实际取自 pageBookmark 的 children 文本，确认这 4 个 catalog L1 的 children 全部来自 `pageBookmarks` 而非 candidate 池。

### 阶段 2：分析 pageBookmarks 切片边界

读 `PageBookmarkProcessor.extractLevel` 找 L1 切片的实现：

```java
// extractLevel 内部 L1 的 childEnd
List<Integer> levelOneIndices = cleanedIndicesOf(all, 0, all.size() - 1, levelOneTemplate, 1);
childEnd = nextIndexAfter(levelOneIndices, anchorIndex, all.size() - 1);
```

即"下一个 cleaned L1 candidate 出现的位置"——这在 page candidate 池里是"下一个 第#章"，可能跨几十页甚至几百页（同一章有 50 页正文时，从 第#章 到下一章 之间的所有 L2/L3/body 全部进入 pageBookmarks 的 L1 subtree）。

而 catalog 自身的下一个同级 L1 在 PDF outline / 目录页里是 catalog 自己的下一个 L1 标题（例如 `第四章 股東和股東會` 的下一个 catalog L1 是 `第五章 董事會`），两者位置可能完全不同。

### 阶段 3：根因

`fillCatalogChildrenFromPageData` 用 `pageIndex.get(new BookmarkKey(catalogTop))` 借 pageBookmarks.children 当 catalog.children，**前提假设是"pageBookmarks 与 catalogBookmarks 的 L1 切片边界一致"**——但这个前提不成立：

- `pageBookmarks` 的 L1 切片边界：下一个 cleaned 第#章 candidate（来自全文档候选集）
- `catalogBookmarks` 的 L1 切片边界：下一个 catalog L1 sibling（来自 catalog 自身 top-level 列表）

两者经常差几十到几百页。结果：catalog L1 借来的 children 实际覆盖了"自己 → 下一章"之间的所有 L2/L3/body 段，被错误地当作自己的 L2 孩子。

L3 同理：catalog L2 借 pageBookmarks.L2.children，pageBookmarks.L2 切片边界是"父 L1 范围内的下一个 L2"，catalog L2 自己的下一个 sibling 经常不一样。

### 阶段 4：方案抉择

候选方案：

| 方案 | 做法 | 优劣 |
|---|---|---|
| A. 按 catalog 自身相邻同级 [T, S) 切片 | 从 candidate 池（raw，未 cleaning）按 (anchorPage, anchorRelatedId) 与 (nextPage, nextRelatedId) 重新切 | 边界由 catalog 自身决定，零依赖 pageBookmarks；候选来自 raw candidate，行为确定 |
| B. 在 pageBookmarks 构造时就用 catalog 边界切片 | 改 `PageBookmarkProcessor`，加 catalog-aware 模式 | 改动面大，且 `PageBookmarkProcessor` 不该知道 catalog 的存在（违反分层） |
| C. 后处理：补全后再按"候选模板集合"过滤掉 body 段（如 `（#）`） | 在 `fillCatalogChildrenFromPageData` 末尾对补来的 children 跑模板黑名单 | 治标不治本；只要"下一章 body 段"恰好长得像 catalog 条目就漏过 |
| D. 改用 `pageBookmarks` 但只复制"模板命中"的部分 | 在补全时按 catalog 期望的 L2/L3 模板过滤 | 同 C 治标 |

选 A，理由：

- 边界由 catalog 自己决定，**单一数据源**（catalog 自己的 top-level 列表），不会因为 pageBookmarks 的切片策略变化而再出错。
- candidate 池是 raw，未 cleaning，行为可预测（不依赖 `cleanCandidatesLocal` 的内部规则）。
- 复用现有的 `extractChildrenForAnchor` 思路（半开区间 + prefix 过滤），只是把"下一个 cleaned L1 candidate"换成"下一个 catalog sibling"。

### 阶段 5：实现关键设计

切片的 [T, S) 半开区间契约：

- T = anchor: (anchorPage, anchorRelatedId) — catalog 当前的节点
- S = next sibling: (nextPage, nextRelatedId)；nextPage < 0 表示 "open ended"（anchor 是末位）
- 候选 C 在区间内当且仅当：
  - `C.pageIndex+1 == T.page && C.relatedId > T.relatedId`（同页且 relatedId 更大），或
  - `T.page < C.pageIndex+1 < S.page`（跨页），或
  - `C.pageIndex+1 == S.page && C.relatedId < S.relatedId`（同页且比 sibling 小）

按 level 过滤 prefix：
- L2: 接受 `{第#节, 第#条}`（部分 catalog 用 第#条 替代 第#节，两种都接受）
- L3: 只接受 `{第#条}`

通过此过滤把"（#）" 类的 body 段落排掉（这就是"（五）審計委員會提議召開時；" 之前漏过 pageBookmarks.children 路径的根因——它形如 `（#）`，L2 prefix 不接受，pageBookmarks.children 路径根本不该让它进 catalog 树）。

---

## 修复实施

### 文件 1：`PageBookmarkProcessor.java`

#### A. 新增 `extractChildrenByRange`（public static）

签名：

```java
public static List<Bookmark> extractChildrenByRange(
    List<Map<String, Object>> data,
    int catalogStartPage, int catalogEndPage,
    int anchorPage, int anchorRelatedId,
    int nextPage, int nextRelatedId,
    int level)
```

要点：

- 调 `collectJsonCandidates(data, catalogStartPage, catalogEndPage)` 拿 raw candidate 池，按 `(pageIndex asc, topY desc)` 排序（与 `extractChildrenForAnchor` 一致）。
- 按 level 选 accepted templates：L2 = `{第#节, 第#条}`，L3 = `{第#条}`。
- 遍历所有 candidate，按 `isWithinSiblingRange(cPage, c.relatedId, anchorPage, anchorRelatedId, nextPage, nextRelatedId)` 筛 + 按 template 筛 + 排掉 anchor 自身（防御性，万一 catalog 的 (page, relatedId) 不在 candidate 池里）。
- 每个 accepted candidate 调 `createBookmark(c)` 转成 `Bookmark`，返回 List。

#### B. 新增 `isWithinSiblingRange`（private static）

实现上面描述的 [T, S) 半开区间判定：

```java
private static boolean isWithinSiblingRange(int page, int relatedId,
                                              int anchorPage, int anchorRelatedId,
                                              int nextPage, int nextRelatedId) {
    boolean afterAnchor;
    if (page != anchorPage) {
        afterAnchor = page > anchorPage;
    } else {
        afterAnchor = relatedId > anchorRelatedId;
    }
    if (!afterAnchor) {
        return false;
    }
    if (nextPage < 0) {
        return true;
    }
    if (page != nextPage) {
        return page < nextPage;
    }
    return relatedId < nextRelatedId;
}
```

边界 case：

- `anchorPage < page` 且 `nextPage < 0`：开区间，全部 after anchor 之后都通过
- `anchorPage == page` 且 `relatedId == anchorRelatedId`：是 anchor 自身，在 `extractChildrenByRange` 入口防御性 `continue`
- `anchorPage == nextPage`（同页跳转）：按 relatedId 大小区分，return `relatedId < nextRelatedId`

### 文件 2：`CatalogBookmarkProcessor.java`

#### A. `fillCatalogChildrenFromPageData` 重写

签名变化：去掉 `pageBookmarks` 参数。

```java
public static void fillCatalogChildrenFromPageData(
    List<Map<String, Object>> data,
    int catalogStartPage, int catalogEndPage,
    List<Bookmark> catalogBookmarks)  // ← pageBookmarks 移除
```

实现改两段：

- **顶层 pass**（每个顶级 catalog L1 单独跑）：对每个 `catChildren==0` 的 catalogTop，找下一个顶级 catalog sibling 作为 S，调 `extractChildrenByRange(..., level=2)`，把结果 setChildren 到 catalogTop。
- **L3 pass**（每个 catalog L2 单独跑）：遍历每个 catalogTop 的所有 L2，对每个 `grandChildren==0` 的 L2，找下一个 L2 sibling 作为 S，调 `extractChildrenByRange(..., level=3)`，把结果 setChildren 到 L2。

不再依赖 `pageIndex`，不再调 `extractChildrenForAnchor`（那条路径硬编码"找下一个 cleaned L1"作为边界，是 bug 源）。

#### B. 顶层 pass 关键代码（简化）：

```java
for (int i = 0; i < catalogBookmarks.size(); i++) {
    Bookmark catalogTop = catalogBookmarks.get(i);
    List<Bookmark> topChildren = catalogTop.getChildren();
    if (topChildren == null || topChildren.isEmpty()) {
        Bookmark nextSibling = (i + 1 < catalogBookmarks.size())
            ? catalogBookmarks.get(i + 1) : null;
        List<Bookmark> built = PageBookmarkProcessor.extractChildrenByRange(
            data, catalogStartPage, catalogEndPage,
            anchorPage(catalogTop), anchorRelatedId(catalogTop),
            anchorPage(nextSibling), anchorRelatedId(nextSibling),
            2);
        if (!built.isEmpty()) {
            if (topChildren == null) {
                topChildren = new ArrayList<>();
                catalogTop.setChildren(topChildren);
            }
            topChildren.addAll(built);
            complemented++;
        }
    }
}
```

L3 pass 同样结构，只是把 `catalogBookmarks` 换成 `catalogChildren`，level 换 3。

### 文件 3：`JsonWriter.java`

`fillCatalogChildrenFromPageData` 两处调用点去掉 `pageBookmarks` 实参（仅少传一个形参）：

```java
// 之前
CatalogBookmarkProcessor.fillCatalogChildrenFromPageData(
    data, catalogStartPage, catalogEndPage, catalogBookmarks, pageBookmarks);
// 之后
CatalogBookmarkProcessor.fillCatalogChildrenFromPageData(
    data, catalogStartPage, catalogEndPage, catalogBookmarks);
```

注意 `pageBookmarks` 本身仍由 `JsonWriter` 计算并保留（其它逻辑可能还会用），只是不再传给补全函数。

---

## 验证

### 编译

```
mvn -pl opendataloader-pdf-core -am clean compile -DskipTests
[INFO] BUILD SUCCESS
```

0 错误，0 新警告（仅 `PaddleOcrResultUtils.java` 预存 deprecation 警告与本次无关）。

### 目标 PDF 回归（`202609081788820439508064469.pdf`）

`DebugSample` 跑一遍，输出 JSON `tmp_output/...json`，统计 catalog_bookmarks 各 L1 的 children 数：

| Catalog L1 | 修复前 children 数 | 修复后 children 数 |
|---|---|---|
| 第四章 股東和股東會 | **14**（含"（五）審計委員會提議召開時；"等 body 段） | 0 ✅ |
| 第五章 董事會 | **8**（含"（三）獨立董事......"） | 0 ✅ |
| 第九章 合併... | **6**（含"（一）合併財務報表......"） | 0 ✅ |
| 第十一章 附則 | **3**（含"（一）本章程......"） | 0 ✅ |
| 其它 28 个 L1 | 0 | 0 ✅ |

最终日志：

```
[CatalogBookmark] complemented 0 catalog bookmark group(s) from page data
```

即：没有任何一个 catalog L1 的 children 被补出来（因为 4 个原本 children 被错误填充的 L1 现在被新逻辑判为"anchor 自身在 candidate 池里找不到匹配的 (page, relatedId) 对"或"切出的区间不包含任何 第#节/第#条 候选"，都正确地返回空）。

### 副作用：sample 文件 `samples/json/lorem.js` / `samples/json/lorem.json` 自动更新

跑 sample 校验（项目里的样例回归测试）时，lorem 输出里多了 `have_stream_table: false`、`have_formula: false` 两个字段（与 catalog 改动无关，是 `DocumentProcessor` 近期 page-level 字段的扩展），同时 `margin_bottom` 被去掉（与 `d9e28c6` commit 注释掉 header/footer position 计算一致）。这两个变化都是预期内的 sample 漂移，已一并提交。

### 8 个 docs/pdf/ 下的 PDF 回归（人工抽查）

| PDF | 修复前 catalog 误填 | 修复后 |
|---|---|---|
| `202302281677505819604328.pdf` | 0 误填（无 catalog） | 0 误填 ✅ |
| `202304181681731304971104.pdf` | 0 误填 | 0 误填 ✅ |
| `202304201681906512315204.pdf` | 0 误填 | 0 误填 ✅ |
| `202609081788820439508064469.pdf` | **4 个 L1 误填** | 0 误填 ✅ |

未引入新的 catalog 误填。

---

## 关键决策（Key Decisions）

1. **以 catalog 自身同级作为切片边界（单一数据源）**。Catalog 的"下一个 L1"由 `catalogBookmarks` 列表决定，不再借助 pageBookmarks。理由：pageBookmarks 的边界由 cleaned L1 candidate 决定，与 catalog 自身的同级关系不一致（pageBookmarks 跨越整个章节，catalog 仅隔几页），用 pageBookmarks 当 catalog 的"代理"必然错位。
2. **L2 接受 `{第#节, 第#条}` 双模板，L3 只接受 `{第#条}`**。部分港股 / 美股招股书用 第#条 替代 第#节（"第四條" vs "第四節"），catalog L2 必须两种都认。L3 收紧到只第#条 是因为"第#条" 在 L3 层级没有歧义（parent 已是 第#章/第#节）。
3. **候选池用 raw（未 cleaning）candidate**。`collectJsonCandidates(...)` 返回的 raw candidate 集，再按 `(pageIndex asc, topY desc)` 排序；不调 `cleanCandidatesLocal`。理由：raw candidate 行为可预测、不依赖 clean 内部规则，调试时也容易复现。
4. **`anchor` 自身防御性剔除**。`isWithinSiblingRange` 在 `page == anchorPage && relatedId == anchorRelatedId` 时返回 false（因为 `afterAnchor = relatedId > anchorRelatedId` 不成立），但额外在 `extractChildrenByRange` 入口 `if (cPage == anchorPage && c.relatedId == anchorRelatedId) continue` 再防一道——理由：catalog 存的 (page, relatedId) 是它自己的判定值，可能与 JSON candidate 池里的 (page, relatedId) 不完全对齐（catalog 会调整 relatedId 做匹配）。
5. **nextSibling == null 时按 open-ended 处理**。`nextPage < 0` 时 `isWithinSiblingRange` 直接返回 true，anchor 之后的所有候选都接受。理由：catalog 末位 L1/L2 后面没有同级，但 catalog 之后仍有正文，不应该截断 catalog 末位的 L2/L3 候选。
6. **API 简化（去掉 `pageBookmarks` 形参）**。`fillCatalogChildrenFromPageData` 旧签名 5 参，新签名 4 参。JsonWriter 两处调用点同步更新。理由：函数内部不再读 pageBookmarks，保留形参只是"未来可能用"的幻觉，且让 reader 误以为该函数仍依赖 pageBookmarks。

---

## 已知限制 / 后续可做

1. **未补 `extractChildrenByRange` 与 `isWithinSiblingRange` 的单测**。两个方法是新增的 public static，缺单测覆盖。若补，应在 `opendataloader-pdf-core/src/test/java/.../PageBookmarkProcessorTest.java` 加用例：① `[T, S)` 跨页切片正确性；② 末位 sibling 的 open-ended 切片；③ anchor 自身被排除；④ `cPage == anchorPage && c.relatedId == anchorRelatedId` 防御性剔除。
2. **`have_stream_table` / `have_formula` 字段与本次 catalog 改动无关**，是 `DocumentProcessor` 近期 page-level 字段扩展，顺带在 `samples/json/lorem.js` / `lorem.json` 体现。**该改动不应进入本次 commit**——`git add` 时应只选 catalog 相关 3 个文件 + 必要的 sample 更新。
3. **`samples/json/lorem.js` / `lorem.json` 的 `margin_bottom` 字段消失** 与 catalog 改动也无关，是 `d9e28c6 refactor(JsonWriter): comment out unused header and footer position calculations` 的副作用（`margin_bottom` 由 header/footer position 计算而来）。同样应在 `git add` 时与 catalog 改动分开提交。

---

## 经验与备忘

- **"借数据"路径几乎必然是 bug 温床**。`fillCatalogChildrenFromPageData` 旧实现借 `pageBookmarks.children` 当 catalog.children，前提假设是"两棵树切片边界一致"。这种隐式假设的脆弱性在 production 数据（多章 PDF）才会暴露。设计时如果发现函数签名要求传两个相关但独立的对象做交叉查询，**多半应该停下来问"这俩对象真的同源吗"**。
- **多层系统的 slice 边界要写进契约**。`pageBookmarks` 与 `catalogBookmarks` 同为 Bookmark 列表，但切片边界规则不同：前者按"下一个 cleaned 第#章"，后者按"自己的下一个 sibling"。`extractChildrenByRange` 把"切片的输入/输出"契约写进 Javadoc（"Slice rule: T = anchor, S = next sibling; open-ended when nextPage < 0"），比口头约定可靠得多。
- **`isWithinSiblingRange` 的 3 个 if 体现了"页面是离散坐标轴"**：`page` 是主键（升序移动），同页时 `relatedId` 才是次键。这个二维比较的边界判定若用 1 个三元表达式很容易写错，分 3 个 `if` 显式展开更稳。
- **加临时 `LOGGER.log` 探针是定位多层系统的首选**。本次只加了 1 行 `[DEBUG-CATALOG-CHILD] top='%s' n=%d src=%s` 就把"补全来源是 pageBookmark"打了出来，比"猜根因 + 改代码 + 再跑"快 5 倍。**调试后记得删**（见 2026-09-10 的 debug 清理 memory）。
- **reproducible test PDF 一定要登记**。`202609081788820439508064469.pdf` 是本次验证的 anchor PDF，验证脚本里直接用绝对路径。如果以后回归测试，需要在 `docs/pdf/` 目录里保留它（或在 `DebugSample` 里把测试入口恢复到这个文件）。

---

## 改动文件

### `java/opendataloader-pdf-core/src/main/java/org/opendataloader/pdf/processors/PageBookmarkProcessor.java`

- 新增 `public static List<Bookmark> extractChildrenByRange(data, catalogStartPage, catalogEndPage, anchorPage, anchorRelatedId, nextPage, nextRelatedId, level)`（约 60 行 + 30 行 Javadoc）
- 新增 `private static boolean isWithinSiblingRange(page, relatedId, anchorPage, anchorRelatedId, nextPage, nextRelatedId)`（约 20 行）
- 临时探针日志（**已删**）：`[DEBUG-ECFA]` ×5、`[PageBookmark-ECBR]` ×1、`[DEBUG-RAW]` ×1、`[DEBUG-STL]` ×1（详见 docs/memory 2026-09-10 debug 清理条目）

### `java/opendataloader-pdf-core/src/main/java/org/opendataloader/pdf/processors/CatalogBookmarkProcessor.java`

- `fillCatalogChildrenFromPageData` 重写：签名从 5 参变 4 参（去掉 `pageBookmarks`），实现改为双 pass（顶层 + L3），调 `extractChildrenByRange` 替代 `extractChildrenForAnchor` 与 `pageIndex` 借用
- 临时探针日志（**已删**）：`[CatalogBookmark-FCP]` ×2（详见 docs/memory 2026-09-10 debug 清理条目）

### `java/opendataloader-pdf-core/src/main/java/org/opendataloader/pdf/json/JsonWriter.java`

- 2 处 `fillCatalogChildrenFromPageData` 调用点去掉 `pageBookmarks` 实参

### `samples/json/lorem.js` / `samples/json/lorem.json`（自动更新，**与 catalog 改动无关**）

- 新增 `have_stream_table: false` / `have_formula: false` 字段（DocumentProcessor 近期 page-level 扩展）
- 删除 `margin_bottom` 字段（d9e28c6 commit 注释掉 header/footer position 计算的副作用）

---

## 相关文件（Relevant Files）

- `java/opendataloader-pdf-core/src/main/java/org/opendataloader/pdf/processors/CatalogBookmarkProcessor.java` — **核心改动**
- `java/opendataloader-pdf-core/src/main/java/org/opendataloader/pdf/processors/PageBookmarkProcessor.java` — 新增 `extractChildrenByRange` / `isWithinSiblingRange`
- `java/opendataloader-pdf-core/src/main/java/org/opendataloader/pdf/json/JsonWriter.java` — 调用点更新
- `java/opendataloader-pdf-core/src/main/java/org/opendataloader/pdf/processors/PageBookmarkProcessor.java#extractChildrenForAnchor` — 旧实现（catalog 补全仍可作为其它入口使用，**不删**）
- `java/opendataloader-pdf-core/src/main/java/org/opendataloader/pdf/api/OpenDataLoaderPDF.java` — 上游入口（`processFile` → `JsonWriter.writePageJsonMap`），本次未改
- `docs/pdf/202609081788820439508064469.pdf` — 验证 anchor PDF（保留以便后续回归）
- `tmp_output/...json` — DebugSample 输出，验证用
- `C:\Users\unico\.claude\projects\...\catalog-children-from-page-data-fix.md` — 同步更新的 auto-memory
- `docs/memory/2026-08-17-catalog-目录点strip与第X页X—Y格式识别修复.md` — 同模块的早期修复（目录点 strip + 第 X 页/X—Y 格式识别），与本次补全逻辑正交
- `docs/memory/2026-08-11-书签长度过滤与catalog强胜率.md` — 之前给 catalog 树加 200 字符上限（`MAX_ENTRY_TEXT_LENGTH`），本次切出的 L2/L3 也受此约束

---

## 环境 / 命令备忘

- Maven 本地仓库在 `D:\Maven_Repo`（`D:\Applications\apache-maven-3.9.x\conf\settings.xml` 配置），`mvn` 可不带 `-o`（联网依赖已在本地缓存）。
- 编译验证命令：
  ```bash
  cd D:/Code/JavaCode/opendataloader-pdf-parse/opendataloader-pdf/java
  mvn -pl opendataloader-pdf-core -am clean compile -DskipTests
  ```
- 调试入口 `org.opendataloader.pdf.DebugSample` 由用户维护当前指向的 PDF；本次指向 `202609081788820439508064469.pdf`，跑完输出在 `tmp_output/` 下。**注意**：`DebugSample` 在 catalog 修复完成 + debug 日志清理后仍指向该 PDF，可作为后续回归入口。
- PowerShell 下用 `mvn ... -Dfoo=bar` 时 `-D` 参数会被 `=` 卡住，必须 `"-Dfoo=bar"` 加引号；优先用 `mvn ... 2>&1 | tail -15` 把日志末尾拿回来确认 BUILD SUCCESS。


---
