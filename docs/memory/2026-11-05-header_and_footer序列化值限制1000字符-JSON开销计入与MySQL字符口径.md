# 2026-11-05 — `header_and_footer` 序列化值限制 1000 字符（JSON 开销计入 + 整行裁剪 + MySQL 字符口径）

> 任务：在已有文档级 `header_and_footer` 字段上追加约束——"value 转成字符串后长度 < 1000"，最终用于写入 MySQL。
> 涉及文件（生产 1 个 + 测试 1 个）：
>
> - `java/opendataloader-pdf-core/src/main/java/org/opendataloader/pdf/json/JsonWriter.java`
> - `java/opendataloader-pdf-core/src/test/java/org/opendataloader/pdf/json/JsonWriterHeaderAndFooterLimitTest.java`

---

## 1. 需求与澄清

原始需求："最后生成的 json 还需要要求 `header_and_footer` 字段的 value 转成字符串长度小于 1000。"

澄清结论（用户拍板）：

| 议题 | 结论 |
|---|---|
| value 层级 | 整个 `{"header":[...],"footer":[...]}` 对象序列化后的字符串（含 key 名、引号、逗号） |
| 计量口径 | JSON 序列化字符数（紧凑形式，不含文档缩进） |
| 超限处理 | 整行丢弃尾部：先 footer 末尾，再 header 末尾，绝不截半行 |
| 与旧上限关系 | 保留 header ≤ 500 / footer ≤ 400，另加 1000 总闸门 |
| 落库口径（追加确认） | MySQL 列限制的 1000 是**字符**（`CHAR_LENGTH`）而非字节；普通字符串列存储**不需要转义** |

---

## 2. 关键认识：文本上限 ≠ 序列化长度

原有收集逻辑只按"原始文本字符数"累计（`HEADER_MAX_TOTAL_LENGTH = 500`、`FOOTER_MAX_TOTAL_LENGTH = 400`），但落到 JSON 里还有第二类开销：

- 每条一个 `"..."`（+2）与一个逗号（+1）；
- 两个 key 名 `"header":` / `"footer":` 与外层花括号；
- 文本内引号、反斜杠、控制字符的转义。

所以**短行越多越危险**：900 条 1 字符的行文本才 900，序列化后可达约 3600。500+400 的文本上限并不能保证 value < 1000，这正是本需求不能靠"把 500/400 调小"来满足的原因。

---

## 3. 实现

### 3.1 常量（`JsonWriter`）

```java
private static final int HEADER_AND_FOOTER_MAX_VALUE_LENGTH = 1000;
```

注释中固化口径依据：按字符计量，对应 MySQL `VARCHAR(1000)` / `CHAR_LENGTH`；`String.length()` 按 UTF-16 code unit 计，BMP 外字符记 2，比 MySQL 码点口径**更保守**。

### 3.2 写出前裁剪

```java
if (header.isEmpty() && footer.isEmpty()) return;
trimToMaxValueLength(header, footer);
if (header.isEmpty() && footer.isEmpty()) return;   // 削空则整个字段省略
jsonGenerator.writeObjectFieldStart(JsonName.HEADER_AND_FOOTER);
```

### 3.3 测长：复用最终输出的转义配置

```java
private static int serializedValueLength(List<String> header, List<String> footer) throws IOException {
    StringWriter writer = new StringWriter();
    try (JsonGenerator generator = new JsonFactory().createGenerator(writer)) {
        generator.setCharacterEscapes(JSON_ESCAPES);     // 与写文件时同一套
        generator.writeStartObject();
        if (!header.isEmpty()) writeStringArrayField(generator, JsonName.HEADER_AND_FOOTER_HEADER, header);
        if (!footer.isEmpty()) writeStringArrayField(generator, JsonName.HEADER_AND_FOOTER_FOOTER, footer);
        generator.writeEndObject();
    }
    return writer.toString().length();
}
```

> 不复用 `ObjectMapper.writeValueAsString`：文件写出用的是 `JSON_ESCAPES`（NUL 转空串、非 ASCII 不转义），测长必须同口径。空数组不写，与最终输出形态一致。

### 3.4 裁剪策略

`while (serializedValueLength(...) >= 1000)` → 先从 **footer 末尾** remove，footer 空后再从 **header 末尾** remove：

- 用 `>=` 落实"严格小于"（最终最多 999）；
- 整条移除，保证不出现半行；
- footer 先丢：header 通常承载文档标识（公司名、证券代码），业务价值更高。

### 3.5 性能

每次裁剪重新序列化，条目数受 500/400 文本上限约束（极短行最坏约 900 条），单文档耗时在亚毫秒级，可忽略。

---

## 4. 测试与验证

### 4.1 单测（新增 3 个用例）

`JsonWriterHeaderAndFooterLimitTest` 通过反射调用私有 `trimToMaxValueLength`，用**独立** `ObjectMapper` 再序列化测长（不复用被测方法），断言：

1. 仅 footer 超限 → header 原样保留，footer 只剩原顺序前缀；
2. footer 削空后继续削 header → header 只剩原顺序前缀；
3. 短列表不被动。

结果：`Tests run: 3, Failures: 0`；同包 `JsonWriter*Test` 合计 19 个用例全绿。

### 4.2 真实样本 `docs/pdf/201111141781890770740012219.pdf`（469 页）

| 项 | 值 |
|---|---|
| header / footer 条数 | 114 / 30 |
| 文本总长 | 304 / 约 210（均未触及 500 / 400） |
| 紧凑 JSON 字符数 | **990** |
| UTF-8 字节数 | 1110 |
| 作为 JSON 字符串再转义一次 | 1284 |
| 是否触发裁剪 | **否** |

判定"未触发裁剪"的依据：裁剪必先从 footer 尾部开始，而结果里 footer 仍以 `— F-30 —` 结尾、header 仍以 `188` 结尾，说明两条列表都是完整的；990 是收集完成后的自然长度，再增一行即会开始丢 footer 尾部。

> 附带观察：该文档 header 收集到的是**纯页码** 62–188（页码过滤只作用于 footer），占用约 650 的预算，使 1000 闸门余量很小。是否对 header 也套用 `isPageNumber` 属于产品取舍，本次未改。

---

## 5. MySQL 落库结论

- 普通 `VARCHAR(1000)` / `TEXT`（utf8mb4）：**直接把紧凑 JSON 字符串原样写入，不需要转义**——引号是字段内容里的普通字符；用 `PreparedStatement` 即可。
- 只有"再包一层 JSON 字符串（双重编码）"或手工拼 SQL 才需要转义；前者会膨胀到 1284 字符，已超 1000，故不适用于本需求。
- 字符口径安全性：Java `String.length()`（UTF-16 code unit）恒 ≥ MySQL `CHAR_LENGTH`（码点），因此"Java 侧 < 1000"必然推出"MySQL 侧 < 1000"。
- 若未来改成按字节限制，必须把 `serializedValueLength` 换成 UTF-8 字节计量（本样本 1110 字节会超限）。

---

## 6. 经验教训

1. **"长度上限"必须问清四件事**：量哪一层（对象/数组/元素）、含不含 JSON 语法、字符还是字节、超限怎么削。任一项不同，实现完全不同。
2. **文本上限挡不住序列化膨胀**：短行数量是隐性放大因子，估算预算时要按"每条 +3 字符"预留。
3. **测长必须复用最终写出的转义/编码配置**，否则会出现"算出来 990、写出来 1005"的口径漂移。
4. **裁剪要保行完整**，并按业务语义决定丢弃顺序；header 优先保留比按出现顺序 FIFO 更合理。
5. **字符 vs 字节的安全方向要算清**：Java UTF-16 ≥ MySQL 码点，故"Java 侧严格小于"是安全的；反过来按字节上限则不成立。

---

## 7. 关联

- `docs/memory/2026-10-10-PDF每页JSON新增文档级header_and_footer字段-页眉页脚文本收集与页码忽略-实现与回归.md`——本字段的收集、去重、页码忽略与 500/400 文本上限来源。
