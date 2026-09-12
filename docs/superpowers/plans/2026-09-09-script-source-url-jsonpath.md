# 脚本书源 URL 语义与 JSONPath（Plan 2c）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) 语法跟踪进度。

**Goal:** 让 `com.ebook.source.script` 拿下 JSONPath 模式（`@json:`/`$.`/`{}`）与 URL 语义：`URL,{...}` 选项尾段、`{{page}}`/`<,{{page}}>` 页码形态、相对地址落位，以及按选项发起真实请求（POST/charset/headers/timeout/retry）的取文层——2d 的 `BookParser` 用它们串出搜索/详情/目录/正文。

**Architecture:** 本文件是解释器四段的第三段。2a 词法、2b HTML 侧求值已落地；2c 只补两块能力：① JSONPath 后端（`RuleMode.JSON_PATH` 目前抛 `UnsupportedRuleFeatureException`，本段换成真实现，`RuleValue` 加 JSON 输入形态、`RuleResult` 加 `Jsons` 结果形态）；② URL 规则串 → 绝对地址 + 选项 → 请求 → 响应文本 的取文链路（`ScriptUrlOption`/`ScriptUrlResolver`/`ScriptHttp`/`ScriptPageFetcher`）。**不发页面接线、不碰 Room、不做翻页链循环**——`ScriptSourcePendingParser` 原位不动，翻页链/聚合搜索/`BookParser` 实现归 2d。事实源是 `docs/superpowers/specs/2026-09-08-script-source-rule-grammar.md`（下称「规格」，引用写作 §x.y），冲突时以规格为准并回改本文件。

**Tech Stack:** Kotlin、OkHttp（经 lib_ebook_api 的 `api(libs.okhttp.logging)` 传递可见，不新声明）、kotlinx-serialization-json（已在依赖里）、JUnit 4。**无新依赖**——JSONPath 在 `JsonElement` 上手写子集（语料证据缺失，子集边界见 Task 2 与规格 §11 新增条目；引入第三方 JSONPath 库与否留给 2d 语料验证后裁决）。

**术语红线（全程有效）：** 全仓禁止出现该生态项目名字样，一律写「脚本书源 / 脚本书源格式」。自检命令一律用 `git grep -in "[l]egado"`（方括号形态，否则命令命中自身），恒须零命中。

---

## 语料扫描的缺口（写计划时的既知假设）

规格 §10 的语料频度数字全部出自 ADR-0029 同源的 642 条语料统计；本计划落稿时想对语料补扫三件事（JSONPath 实际子语法、`<,…>` 真实形态、URL 选项键频度），**但语料文件 `测试源.json` 已不在桌面原路径**，扫描未做成。后果与处置：

1. **JSONPath 子集按「规格 + 标准语义」写**（Task 2），过滤器 `[?(…)]` 与脚本表达式 `[(…)]` 按不支持类型化拒绝——真实语料若高频使用过滤器，2d 金标准 fixtures 会暴露，届时扩集并把规格 §11-17 的边界改掉。
2. **`<,{{page}}>` 按规格 §6.4 的本仓规定实现**（页码 1 整段不进 URL），真实源验证本来就是该条明文要求，后置到 2d 装机验证不新增义务。
3. 2d 的 5 条金标准 fixtures 挑选时**必须覆盖**：JSONPath API 型源、POST 搜索源、gbk 源、`<>` 分页源各至少一条——这是本段欠下的语料证据债的偿还点。

## 本段要钉死的规格条款

| # | 规格出处 | 钉成什么 | 落在 |
|---|---|---|---|
| 1 | §10 + §2.1 | JSONPath 三形态（`@json:`/`$.`/单花括号 `{…}`）统一走 `JsonPathBackend`；单花括号在 2a 已被剥净（`RuleMode.flagOf`） | Task 1-3 |
| 2 | §3.3 | JSONPath 语法错误抛 `RuleSyntaxException`，参与 `\|\|` 短路；路径解不到值是 `Miss` 不是错误 | Task 2 |
| 3 | §2.4/§6.1 | `,{…}` 选项尾段在**切分层**剥出（§2.3 步骤 3）、在**结果层**回附（§9 第 7 步）、在**取文层**消费——三处各司其职 | Task 4-7 |
| 4 | §6.2 + §10 | 选项实现集 = `method`/`charset`/`body`/`headers`/`timeout`/`retry`；`webView`/`proxy`/`dnsIp`/`followRedirects` 类型化拒绝；`js`/`bodyJs` 抛 JS 待执行（Plan 3）；`type` 语义未核实、忽略 | Task 5 |
| 5 | §6.3 | `body` 只接受字符串；对象/数组按 JSON 文本序列化后使用（本仓规定，§11-6 跟踪） | Task 5 |
| 6 | §6.4 | `{{page}}` 初值 1 经既有 `Interpolation`/`EvalContext.builtin` 展开；**算术形态 `{{(page-1)*20}}` 维持 JS 待执行**，2c 不得自己算；`<,内容>` 按本仓规定：页码 1 整段（含分隔符）不进 URL | Task 6 |
| 7 | §6.5 + §12 | 相对地址落位复用 `TocPageUrl.join` 的三形态语义，**不另立口径、不复用 `ListPageUrl` 的首页裁剪**（§6.4 明令：脚本 URL 由作者写全形态，再裁就是裁真实页码） | Task 6 |
| 8 | §6.2 + §10 | charset 按选项**显式解码**：响应按字节取回再 `String(bytes, charset)`，不走 `body.string()`——`@Named("source")` 客户端挂着 `EncodingInterceptor("UTF-8")`，照 Content-Type 解码会让 gbk 站全部乱码 | Task 7 |
| 9 | §8.1/§8.3 + §11-13 | `exploreUrl` 文本格式一：`名称::URL` 条目、分隔**行优先**（先 `\n` 再单行内 `&&`）、`{{}}`/`{}` 内容对 `&&` 不透明；JSON 格式二只取 `{title,url}`，控件项忽略 | Task 8 |

## 与既有代码的接缝（先读这些再用）

| 已有 | 2c 怎么用 |
|---|---|
| `RuleMode.of` | `@json:`/`$.`/`{…}` 已判成 `JSON_PATH` 并剥净前缀（`RuleModeTest` 三条锁形）；`-@json:` 的反序前缀已收（裸 `$.` 不收）→ 后端要接 `reverse` |
| `RuleSplitter.parse` → `ParsedRule(root, replacement, raw)` | 本段加 `optionTail` 字段与 §2.3 步骤 3 的剥离；前置例外（JS/AllInOne 整条成叶）**保持豁免**——选项不挂在正则/JS 上（§6.1） |
| `Interpolation.expand(rule, ctx, evaluateInner)` | URL 的 `{{}}` 展开复用它（`key`/`page`/`baseUrl`/变量，残缺字面量保留）；`@@规则` 递归求值由调用方闭包注入 |
| `EvalContext.builtin` | `page` 初值 1、`key`、`baseUrl` 已就位，零改动 |
| `TocPageUrl.join(currentUrl, raw, sourceRoot)` | 相对落位直接复用（同模块 `internal`）；`ListPageUrl.build` **不许**碰（§6.4） |
| `ScriptRuleEvaluator.evaluateOnItem` | `evaluateOnJsonItem` 与之对称，供 2d 的 JSON bookList 逐条目取字段 |
| `OkHttpClient`（`@Named("source")`，由 Manager 注入解析器） | `OkHttpScriptTransport` 只收实例，2d 接线时传同一实例；本段不接 DI |
| `RegexReplacement` / `ReplacementApplier.apply` | `Jsons` 结果进替换段先字符串化成文本（JSON 字段净化是文本级操作） |

**JSON 输入从哪来**：2c 的 `JsonPathBackend` 只吃 `RuleValue.Json`（测试直接构造）；真实响应文本 → `JsonElement` 的解析放在**输入种子**里（`Page`/`Texts` → lenient 解析，失败按 `Miss`——响应不是规则，解析失败是「没取到值」而不是「规则写错」）。2d 取文后按 `RuleValue.Json` 喂求值器。

## 包与文件布局

```
lib_book_source/src/main/java/com/ebook/source/script/
  RuleValue.kt            [改] +Json(element) 输入形态
  RuleResult.kt           [改] +Jsons(items) 结果形态 + jsonText() + firstText 扩展
  JsonPathBackend.kt      [新建] JSONPath 子集：路径词法 + 求值（过滤器/脚本表达式类型化拒绝）
  ScriptRuleEvaluator.kt  [改] JSON_PATH 分支接线、Jsons 合并/交错/文本化、evaluateOnJsonItem
  ReplacementApplier.kt   [改] Jsons 分支（先字符串化再替换）
  RuleSplitter.kt         [改] §2.3 步骤 3：剥 URL 选项尾段 → ParsedRule.optionTail
  ScriptRuleEvaluator.kt  [改] 结果层回附选项尾段（§9 第 7 步）
  ScriptUrlOption.kt      [新建] 选项尾段切分 + 选项模型 + 不支持键类型化拒绝
  ScriptUrlResolver.kt    [新建] URL 规则串 → 绝对地址 + 选项（{{}} 展开、<> 形态、落位）
  ScriptHttp.kt           [新建] ScriptRequest/ScriptTransport 接缝 + OkHttp 实现 + ScriptPageFetcher
  ExploreUrlFormat.kt     [新建] 发现页文本格式一切分 + JSON 格式二（仅 {title,url}）
lib_book_source/src/test/java/com/ebook/source/script/
  JsonPathBackendTest.kt  ScriptUrlOptionTest.kt  ScriptUrlResolverTest.kt
  ScriptHttpTest.kt  ExploreUrlFormatTest.kt  [既有测试文件追加用例]
```

一个后端一个文件的分工理由与 2a/2b 一致：故障时「路径解错了」「选项解错了」「请求发错了」「发现页切错了」各自定位。所有类型保持 `internal`（求值层与测试同在 `:lib_book_source`）。

---

### Task 1: JSON 输入与结果形态（`RuleValue.Json` / `RuleResult.Jsons`）

**Files:**
- Modify: `lib_book_source/src/main/java/com/ebook/source/script/RuleValue.kt`
- Modify: `lib_book_source/src/main/java/com/ebook/source/script/RuleResult.kt`
- Test: `lib_book_source/src/test/java/com/ebook/source/script/RuleResultTest.kt`（追加用例）

- [x] **Step 1: 写失败的测试**

在 `RuleResultTest` 追加：

```kotlin
    @Test
    fun `JSON 结果按原始内容字符串化`() {
        val items = listOf(
            JsonPrimitive("书名A"),
            JsonPrimitive(42),
            buildJsonObject { put("name", "对象") },
        )
        val texts = RuleResult.Jsons(items).mapToTexts()
        assertEquals(listOf("书名A", "42", """{"name":"对象"}"""), texts.values)
    }

    @Test
    fun `JSON 单值字段收敛为第一个条目`() {
        assertEquals("甲", RuleResult.Jsons(listOf(JsonPrimitive("甲"), JsonPrimitive("乙"))).firstText())
        assertEquals("", RuleResult.Jsons(emptyList()).firstText())
        // JSON null 字符串化成空串而不是字面 "null"——它是「这个键没值」
        assertEquals("", RuleResult.Jsons(listOf(JsonNull)).firstText())
    }
```

文件顶部补 import：`kotlinx.serialization.json.JsonNull`、`kotlinx.serialization.json.JsonPrimitive`、`kotlinx.serialization.json.buildJsonObject`。

- [x] **Step 2: 运行确认失败**

Run: `./gradlew :lib_book_source:compileDebugUnitTestKotlin`
Expected: FAIL——`RuleValue.Json` / `RuleResult.Jsons` / `mapToTexts()` 无参重载未定义。

- [x] **Step 3: 实现 `RuleValue.Json`**

`RuleValue.kt` 的 sealed interface 内、`Texts` 之后追加：

```kotlin
    /**
     * 当前 JSON 文档（规格 §2.1 的 JSONPath 模式上下文）。
     *
     * 真实来源是取文层拿回的响应文本经 lenient 解析（解析失败按 [RuleResult.Miss] 处理，
     * 见 [JsonPathBackend] 的种子逻辑）——响应不是规则串，坏响应属「未取到值」而非「规则写错」。
     */
    data class Json(val element: JsonElement) : RuleValue
```

文件顶部补 import：`kotlinx.serialization.json.JsonElement`。

- [x] **Step 4: 实现 `RuleResult.Jsons` 与 `jsonText()`**

`RuleResult.kt` 的 sealed interface 内、`Matches` 之后追加：

```kotlin
    /**
     * JSONPath 的产物：命中的**全部** JSON 节点（§3.1 一律保留全部匹配的口径同样适用）。
     *
     * 保留节点形态而不是落成字符串列表：JSON 源的列表字段（`$.data.books`）解出的每个
     * 条目还要作为子字段规则（`$.name`/`$.author`）的上下文，字符串化会把结构提前压扁，
     * 与 AllInOne「条目×捕获组二维」同一理由。单值字段的收敛仍归 [firstText] 一处。
     */
    data class Jsons(val items: List<JsonElement>) : RuleResult
```

`mapToTexts` 附近追加文件级函数与无参重载：

```kotlin
/**
 * JSON 节点 → 文本（§3.2 取值语义在 JSON 上的对应物）。
 *
 * 字符串去引号取原值、数字/布尔取字面量；JSON null 字符串化成**空串**而不是字面 "null"
 * ——「这个键没值」与「值为字符串 null」在本格式里都不该产出 "null" 三个字符；
 * 对象/数组给紧凑 JSON 文本（ kotlinx 的 `toString()`），供整块取出再处理的字段用。
 */
internal fun JsonElement.jsonText(): String = when (this) {
    is JsonNull -> ""
    is JsonPrimitive -> content
    else -> toString()
}

/** JSON 结果 → 文本结果（字段净化、单值收敛前的统一出口） */
internal fun RuleResult.Jsons.mapToTexts(): RuleResult.Texts = RuleResult.Texts(items.map { it.jsonText() })
```

`firstText()` 的 `when` 加一个分支：

```kotlin
    is RuleResult.Jsons -> items.firstOrNull()?.jsonText().orEmpty()
```

`fillResult` 的 `Jsons` 分支**不加**（与 `Nodes` 同理：JSON 节点来自被解析的文档，不含占位符）——但 `when` 是穷尽的，编译器会逼着处理；在 `fillResult` 里显式补：

```kotlin
        is RuleResult.Jsons -> result
```

文件顶部补 import：`kotlinx.serialization.json.JsonElement`、`kotlinx.serialization.json.JsonNull`、`kotlinx.serialization.json.JsonPrimitive`。

- [x] **Step 5: 运行测试通过**

Run: `./gradlew :lib_book_source:testDebugUnitTest --tests "com.ebook.source.script.RuleResultTest"`
Expected: PASS（6 + 3 = 9）。

- [x] **Step 6: 提交**

```bash
git grep -in "[l]egado" || echo CLEAN
git add lib_book_source/src/main/java/com/ebook/source/script/RuleValue.kt lib_book_source/src/main/java/com/ebook/source/script/RuleResult.kt lib_book_source/src/test/java/com/ebook/source/script/RuleResultTest.kt
git commit -m "$(cat <<'EOF'
feat(lib_book_source): 脚本书源求值的 JSON 输入与结果形态

JSONPath 结果保留节点形态不落字符串列表：JSON 源的列表条目还要当子字段规则的
上下文，提前字符串化与 AllInOne 压平二维是同一类错。JSON null 字符串化成空串，
"null" 三个字符不是任何书源想要的字段值。
EOF
)"
```

---

### Task 2: `JsonPathBackend`（路径词法 + 求值）

**Files:**
- Create: `lib_book_source/src/main/java/com/ebook/source/script/JsonPathBackend.kt`
- Test: `lib_book_source/src/test/java/com/ebook/source/script/JsonPathBackendTest.kt`

**子集边界（写入规格 §11-17，Task 9）**：实现 `$` 根、`.name`/`['name']`/`["name"]` 子节点、`[n]`/`[-n]` 数组下标（越界逐项跳过，同 §4 口径）、`[*]`/`.*` 通配、`..name` 递归下探、`[a:b(:c)]` 切片、`[a,b]` 联合（索引与键混用）。**不实现**：过滤器 `[?(…)]` 与脚本表达式 `[(…)]`（类型化拒绝）；未覆盖形态一律 `RuleSyntaxException`。**切片按标准 JSONPath 半开方言**（`end` 排他、负数从尾数）——它来自 JSONPath 自身语法域，与规格 §4 链式索引的闭区间（本仓规定）**分属两个方言**，不得互相推广；这条必须写进两处 KDoc。

- [x] **Step 1: 写失败的测试**

```kotlin
package com.ebook.source.script

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 规格 §2.1 JSONPath 模式 + §3.3 错误语义。子集边界见 JsonPathBackend 的 KDoc 与规格 §11-17。 */
class JsonPathBackendTest {

    private val doc = Json.parseToJsonElement(
        """{"data":{"books":[
            {"name":"书一","author":"作者一","tags":["玄幻","完结"],"price":0},
            {"name":"书二","author":"作者二","tags":["都市"],"price":12.5},
            {"name":"书三","author":"作者三","tags":[],"price":-1}
        ]},"total":3,"list":[1,2,3,4,5]}""",
    )

    private fun eval(path: String, reverse: Boolean = false): List<String> =
        (JsonPathBackend.evaluate(path, RuleValue.Json(doc), reverse) as RuleResult.Jsons)
            .items.map { it.jsonText() }

    private fun evalJson(path: String): RuleResult =
        JsonPathBackend.evaluate(path, RuleValue.Json(doc), reverse = false)

    @Test
    fun `点分路径取叶子`() {
        assertEquals(listOf("3"), eval("$.total"))
        assertEquals(listOf("书二"), eval("$.data.books[1].name"))
    }

    @Test
    fun `方括号引用与带引号键`() {
        assertEquals(listOf("3"), eval("$['total']"))
        assertEquals(listOf("书一", "书二", "书三"), eval("""$["data"]['books'][*]['name']"""))
    }

    @Test
    fun `通配符打平数组与对象`() {
        assertEquals(listOf("1", "2", "3", "4", "5"), eval("$.list[*]"))
        assertEquals(listOf("书一", "书二", "书三"), eval("$.data.books[*].name"))
    }

    @Test
    fun `负数下标从尾数`() {
        assertEquals(listOf("书三"), eval("$.data.books[-1].name"))
    }

    @Test
    fun `切片按标准半开方言`() {
        // JSONPath 切片 end 排他——与 §4 链式索引的闭区间分属两个方言，这条用例钉住差异
        assertEquals(listOf("1", "2"), eval("$.list[0:2]"))
        assertEquals(listOf("4", "5"), eval("$.list[-2:]"))
    }

    @Test
    fun `递归下探收集全部同名值`() {
        assertEquals(listOf("玄幻", "完结", "都市"), eval("$..tags[*]"))
    }

    @Test
    fun `联合取多个键`() {
        val r = evalJson("""$['data']['books'][0]['name','author']""")
        assertEquals(listOf("书一", "作者一"), (r as RuleResult.Jsons).items.map { it.jsonText() })
    }

    @Test
    fun `数字与对象结果字符串化`() {
        assertEquals(listOf("12.5"), eval("$.data.books[1].price"))
    }

    @Test
    fun `路径解不到值是 Miss 不是错误`() {
        assertEquals(RuleResult.Miss, evalJson("$.data.notexist"))
        assertEquals(RuleResult.Miss, evalJson("$.data.books[99].name"))
        assertEquals(RuleResult.Miss, evalJson("$.total.name"))
    }

    @Test
    fun `反序标志倒转条目`() {
        assertEquals(listOf("书三", "书二", "书一"), evalReverse())
    }

    private fun evalReverse(): List<String> =
        (JsonPathBackend.evaluate("$.data.books[*].name", RuleValue.Json(doc), reverse = true) as RuleResult.Jsons)
            .items.map { it.jsonText() }

    @Test
    fun `过滤器与脚本表达式按不支持拒绝`() {
        assertTrue(runCatching { eval("$.data.books[?(@.price=0)]") }.exceptionOrNull() is UnsupportedRuleFeatureException)
        assertTrue(runCatching { eval("$.data.books[(1+2)]") }.exceptionOrNull() is UnsupportedRuleFeatureException)
    }

    @Test
    fun `语法错误抛类型化异常`() {
        assertTrue(runCatching { eval("data.books") }.exceptionOrNull() is RuleSyntaxException)
        assertTrue(runCatching { eval("$.data[") }.exceptionOrNull() is RuleSyntaxException)
        assertTrue(runCatching { eval("$.list[a]") }.exceptionOrNull() is RuleSyntaxException)
    }

    @Test
    fun `文本输入不是合法 JSON 时按 Miss 处理`() {
        // 响应不是规则串：坏响应是「没取到值」，交给 || 兜底，而不是把整条规则判死
        assertEquals(RuleResult.Miss, JsonPathBackend.evaluate("$.total", RuleValue.Texts(listOf("<html>404</html>")), reverse = false))
    }
}
```

- [x] **Step 2: 运行确认失败**

Run: `./gradlew :lib_book_source:compileDebugUnitTestKotlin`
Expected: FAIL——`JsonPathBackend` 未定义。

- [x] **Step 3: 实现 `JsonPathBackend.kt`**

```kotlin
package com.ebook.source.script

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * JSONPath 后端（规格 §2.1 的 JSONPath 模式，§10 能力矩阵「API 型站点」）。
 *
 * 手写子集而不是引第三方库：本项目对本格式整体持清洁室姿态、依赖收得极紧，而语料扫描
 * 缺位时无法论证第三方全量实现的必要性——先用类型化拒绝把「写了更复杂语法」的源如实挡下
 * （不猜语义、不静默空结果），2d 金标准 fixtures 提供语料证据后再决定是否扩集或引库。
 *
 * **子集边界**（规格 §11-17）：`$` 根、`.name`/`['name']`/`["name"]`、`[n]`/`[-n]`（越界跳过）、
 * `[*]`/`.*` 通配、`..name` 递归、`[a:b(:c)]` 切片、`[a,b]` 联合。
 * 切片按**标准 JSONPath 半开方言**（end 排他、负数从尾数）——与规格 §4 链式索引的闭区间
 * 是两个语法域的两种方言，谁也别推广到谁那边。
 *
 * 结果一律是 [RuleResult.Jsons]（保留节点形态，理由见该类 KDoc）；命中零条是 [RuleResult.Miss]，
 * 路径语法错误抛 [RuleSyntaxException]（§3.3，参与 `||` 短路）。
 */
internal object JsonPathBackend {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun evaluate(path: String, input: RuleValue, reverse: Boolean): RuleResult {
        val root = seed(input) ?: return RuleResult.Miss
        val items = parsePath(path).fold(listOf(root)) { current, segment -> segment(current) }
        if (items.isEmpty()) return RuleResult.Miss
        return RuleResult.Jsons(if (reverse) items.reversed() else items)
    }

    /** 输入种子：JSON 原样；页面/文本按 lenient 解析；Nodes 无 JSON 语义。解析失败 = 没取到值 */
    private fun seed(input: RuleValue): JsonElement? = when (input) {
        is RuleValue.Json -> input.element
        is RuleValue.Page -> input.source.parseJsonOrNull()
        is RuleValue.Texts -> input.values.firstOrNull()?.parseJsonOrNull()
        is RuleValue.Nodes -> null
    }

    private fun String.parseJsonOrNull(): JsonElement? =
        runCatching { json.parseToJsonElement(this) }.getOrNull()

    // region 路径词法

    private fun parsePath(path: String): List<(List<JsonElement>) -> List<JsonElement>> {
        val trimmed = path.trim()
        if (trimmed.isEmpty() || trimmed[0] != '$') throw RuleSyntaxException(path)
        val out = mutableListOf<(List<JsonElement>) -> List<JsonElement>>()
        var i = 1
        while (i < trimmed.length) {
            when {
                trimmed.startsWith("..", i) -> {
                    val name = readIdent(trimmed, i + 2) ?: throw RuleSyntaxException(path)
                    out += { current -> current.flatMap { recursiveByName(it, name) } }
                    i += 2 + name.length
                }
                trimmed[i] == '.' -> {
                    if (i + 1 < trimmed.length && trimmed[i + 1] == '*') {
                        out += ::wildcard
                        i += 2
                    } else {
                        val name = readIdent(trimmed, i + 1) ?: throw RuleSyntaxException(path)
                        out += { current -> current.flatMap { child(it, name) } }
                        i += 1 + name.length
                    }
                }
                trimmed[i] == '[' -> {
                    val close = closeBracket(trimmed, i) ?: throw RuleSyntaxException(path)
                    out += parseBracket(trimmed.substring(i + 1, close), path)
                    i = close + 1
                }
                else -> throw RuleSyntaxException(path)
            }
        }
        return out
    }

    /** 合法标识符（点号路径的键名），读到下一个 `. `[` 前为止；为空返回 null */
    private fun readIdent(s: String, from: Int): String? {
        var i = from
        while (i < s.length && s[i] != '.' && s[i] != '[') i++
        val name = s.substring(from, i)
        return name.ifEmpty { null }
    }

    /** `[` 的配对 `]` 下标；引号内的 `]` 不参与配对 */
    private fun closeBracket(s: String, open: Int): Int? {
        var i = open + 1
        while (i < s.length) {
            val c = s[i]
            when {
                c == '"' || c == '\'' -> {
                    val end = s.indexOf(c, i + 1)
                    if (end < 0) return null
                    i = end + 1
                }
                c == ']' -> return i
                else -> i++
            }
        }
        return null
    }

    private fun parseBracket(inner: String, path: String): (List<JsonElement>) -> List<JsonElement> = when {
        inner == "*" -> ::wildcard
        inner.startsWith("?") -> throw UnsupportedRuleFeatureException("JSONPath 过滤器", path)
        inner.startsWith("(") -> throw UnsupportedRuleFeatureException("JSONPath 脚本表达式", path)
        inner.length >= 2 && (inner.first() == '\'' || inner.first() == '"') ->
            { current -> current.flatMap { child(it, inner.substring(1, inner.length - 1)) } }
        else -> {
            val commaParts = splitTopLevel(inner, ',')
            if (commaParts.size > 1) parseUnion(commaParts, path)
            else parseSliceOrIndex(inner.trim(), path)
        }
    }

    /** 引号感知的顶层单字符切分（联合 `[0,'x']` / 切片里的逗号不可能是顶层） */
    private fun splitTopLevel(s: String, sep: Char): List<String> {
        val out = mutableListOf<String>()
        val buf = StringBuilder()
        var inQuote: Char? = null
        for (c in s) {
            val q = inQuote
            when {
                q != null -> {
                    buf.append(c)
                    if (c == q) inQuote = null
                }
                c == '"' || c == '\'' -> {
                    inQuote = c
                    buf.append(c)
                }
                c == sep -> {
                    out += buf.toString()
                    buf.clear()
                }
                else -> buf.append(c)
            }
        }
        out += buf.toString()
        return out
    }

    private fun parseUnion(parts: List<String>, path: String): (List<JsonElement>) -> List<JsonElement> {
        data class UnionPart(val index: Int?, val name: String?)

        val parsed = parts.map { raw ->
            val t = raw.trim()
            val quoted = t.length >= 2 && (t.first() == '\'' || t.first() == '"')
            if (quoted) UnionPart(null, t.substring(1, t.length - 1))
            else UnionPart(t.toIntOrNull() ?: throw RuleSyntaxException(path), null)
        }
        return { current ->
            current.flatMap { e ->
                parsed.flatMap { p ->
                    when {
                        p.index != null -> index(e, p.index)
                        else -> child(e, p.name!!)
                    }
                }
            }
        }
    }

    /** 标准半开切片：`[start:end(:step)]`，end 排他、负数从尾数；两段都可省略 */
    private fun parseSliceOrIndex(inner: String, path: String): (List<JsonElement>) -> List<JsonElement> {
        val m = Regex("""^(-?\d+)?\s*:\s*(-?\d+)?(?:\s*:\s*(\d+))?$""").matchEntire(inner)
        if (m == null) {
            val i = inner.toIntOrNull() ?: throw RuleSyntaxException(path)
            return { current -> current.flatMap { index(it, i) } }
        }
        val start = m.groupValues[1].toIntOrNull()
        val end = m.groupValues[2].toIntOrNull()
        val step = (m.groupValues[3].toIntOrNull() ?: 1).also { if (it == 0) throw RuleSyntaxException(path) }
        return { current ->
            current.flatMap { e ->
                if (e is JsonArray) {
                    val n = e.size
                    var i = start ?: 0
                    if (i < 0) i += n
                    var stop = end ?: n
                    if (stop < 0) stop += n
                    val out = mutableListOf<JsonElement>()
                    while (if (step > 0) i < stop else i > stop) {
                        if (i in 0 until n) out += e[i]
                        i += step
                    }
                    out
                } else emptyList()
            }
        }
    }

    // endregion

    // region 求值

    private fun child(e: JsonElement, name: String): List<JsonElement> =
        listOfNotNull((e as? JsonObject)?.get(name))

    private fun index(e: JsonElement, i: Int): List<JsonElement> {
        if (e !is JsonArray) return emptyList()
        val resolved = if (i < 0) e.size + i else i
        return listOfNotNull(e.getOrNull(resolved))
    }

    private fun wildcard(e: JsonElement): List<JsonElement> = when (e) {
        is JsonArray -> e.toList()
        is JsonObject -> e.values.toList()
        else -> emptyList()
    }

    /** 递归下探：先收本层同名值、再向全部子节点下探——文档序，先父后子 */
    private fun recursiveByName(e: JsonElement, name: String, out: MutableList<JsonElement> = mutableListOf()): List<JsonElement> {
        if (e is JsonObject) {
            e[name]?.let { out += it }
            e.values.forEach { recursiveByName(it, name, out) }
        } else if (e is JsonArray) {
            e.forEach { recursiveByName(it, name, out) }
        }
        return out
    }

    // endregion
}
```

- [x] **Step 4: 运行测试通过**

Run: `./gradlew :lib_book_source:testDebugUnitTest --tests "com.ebook.source.script.JsonPathBackendTest"`
Expected: PASS（12）。

- [x] **Step 5: 提交**

```bash
git grep -in "[l]egado" || echo CLEAN
git add lib_book_source/src/main/java/com/ebook/source/script/JsonPathBackend.kt lib_book_source/src/test/java/com/ebook/source/script/JsonPathBackendTest.kt
git commit -m "$(cat <<'EOF'
feat(lib_book_source): 脚本书源 JSONPath 后端与子集边界

手写子集不引第三方库：语料扫描缺位时论证不了全量实现的必要性，复杂语法按
类型化拒绝如实挡下（过滤器/脚本表达式），2d 金标准 fixtures 给出证据后再裁决扩集。
切片按标准半开方言：它与 §4 链式索引的闭区间分属两个语法域，谁也别推广到谁。
EOF
)"
```

---

### Task 3: 求值器接线（`JSON_PATH` 分支 + `Jsons` 组合语义 + 逐条目求值）

**Files:**
- Modify: `lib_book_source/src/main/java/com/ebook/source/script/ScriptRuleEvaluator.kt`
- Modify: `lib_book_source/src/main/java/com/ebook/source/script/ReplacementApplier.kt`
- Test: `lib_book_source/src/test/java/com/ebook/source/script/ScriptRuleEvaluatorCombinatorTest.kt`（追加用例）

- [x] **Step 1: 写失败的测试**

在 `ScriptRuleEvaluatorCombinatorTest` 追加（该类现有 JSON 输入用例为零；fixture 直接内联）：

```kotlin
    private val jsonDoc = """{"books":[{"name":"书一","kind":"玄幻"},{"name":"书二","kind":"都市"}],"total":2}"""

    @Test
    fun `jsonPath 取到值`() {
        val r = ScriptRuleEvaluator(EvalContext()).evaluate("$.total", RuleValue.Json(Json.parseToJsonElement(jsonDoc)))
        assertEquals(RuleResult.Texts(listOf("2")), r)
    }

    @Test
    fun `jsonPath 参与竖线短路`() {
        val r = ScriptRuleEvaluator(EvalContext()).evaluate(
            "$.none||$.books[0].name",
            RuleValue.Json(Json.parseToJsonElement(jsonDoc)),
        )
        assertEquals(RuleResult.Texts(listOf("书一")), r)
    }

    @Test
    fun `jsonPath 全支皆错时抛错`() {
        // 两支都在路径里放了非法字符（`!`），且 || 在深度 0 能正常切分
        val e = runCatching {
            ScriptRuleEvaluator(EvalContext()).evaluate("$.a!||$.b!", RuleValue.Json(Json.parseToJsonElement(jsonDoc)))
        }.exceptionOrNull()
        assertTrue(e is RuleSyntaxException)
    }

    @Test
    fun `双与号合并两路 json 结果`() {
        val r = ScriptRuleEvaluator(EvalContext()).evaluate(
            "$.books[0].name&&$.books[1].name",
            RuleValue.Json(Json.parseToJsonElement(jsonDoc)),
        )
        assertEquals(RuleResult.Texts(listOf("书一", "书二")), r)
    }

    @Test
    fun `列表条目按子字段逐条求值`() {
        val ev = ScriptRuleEvaluator(EvalContext())
        val books = Json.parseToJsonElement(jsonDoc).jsonObject["books"]!!.jsonArray
        val names = books.map { (ev.evaluateOnJsonItem("$.name", it) as RuleResult.Texts).values.first() }
        assertEquals(listOf("书一", "书二"), names)
    }

    @Test
    fun `jsonPath 结果可带净化替换段`() {
        val r = ScriptRuleEvaluator(EvalContext()).evaluate(
            """$.books[0].name##书""",
            RuleValue.Json(Json.parseToJsonElement(jsonDoc)),
        )
        assertEquals(RuleResult.Texts(listOf("一")), r)
    }
```

文件顶部补 import：`kotlinx.serialization.json.Json`、`kotlinx.serialization.json.jsonArray`、`kotlinx.serialization.json.jsonObject`。

- [x] **Step 2: 运行确认失败**

Run: `./gradlew :lib_book_source:compileDebugUnitTestKotlin`
Expected: FAIL——`evaluateOnJsonItem` 未定义；`JSON_PATH` 分支现抛 `UnsupportedRuleFeatureException`。

- [x] **Step 3: 接线 `ScriptRuleEvaluator`**

`evaluateLeaf` 的 `JSON_PATH` 分支替换：

```kotlin
            RuleMode.JSON_PATH -> JsonPathBackend.evaluate(expansion.fill(node.body), input, node.reverse)
```

（`expansion.fill` 与其他后端同口径：载荷在使用点回填。`node.reverse` 只在 `-@json:` 显式标志下为真——2a 的 `hasKnownFlag` 不认裸 `$.`，见 `RuleModeTest`。）

`evaluateOnItem` 之后追加：

```kotlin
    /**
     * 逐条目求值（JSON 版）：JSONPath 列表字段（如 `$.data.books`）解出的**一个条目**
     * 作为上下文，按子字段规则（`$.name`/`$.author`）取值。与 [evaluateOnItem] 对称——
     * 组引用是正则条目的专属取值形态，JSON 条目整条就是一条规则、不需要组引用判定。
     */
    fun evaluateOnJsonItem(fieldRule: String, item: JsonElement): RuleResult =
        evaluate(fieldRule, RuleValue.Json(item))
```

`mergeAllOf` 的合并分支、`interleave` 的交错分支、`toTextList` 各加 `Jsons` 一支：

```kotlin
            results.all { it is RuleResult.Jsons } ->
                RuleResult.Jsons(results.flatMap { (it as RuleResult.Jsons).items })
```

```kotlin
        if (results.all { it is RuleResult.Jsons }) {
            return RuleResult.Jsons(interleaved(results.map { (it as RuleResult.Jsons).items }))
        }
```

```kotlin
        is RuleResult.Jsons -> items.map { it.jsonText() }
```

文件顶部补 import：`kotlinx.serialization.json.JsonElement`。

- [x] **Step 4: `ReplacementApplier` 加 `Jsons` 分支**

`apply` 的 `texts` `when` 里 `Matches` 分支旁追加：

```kotlin
            is RuleResult.Jsons -> result.items.map { it.jsonText() }
```

（KDoc 补一句：JSON 结果进替换段先字符串化——净化是文本级操作，字段值取出来就该是文本。）

- [x] **Step 5: 运行测试通过**

Run: `./gradlew :lib_book_source:testDebugUnitTest --tests "com.ebook.source.script.ScriptRuleEvaluatorCombinatorTest" --tests "com.ebook.source.script.ReplacementApplierTest"`
Expected: PASS（ScriptRuleEvaluatorCombinatorTest 22；ReplacementApplierTest 9）。

- [x] **Step 6: 全模块回归 + 红线 + 提交**

```bash
./gradlew :lib_book_source:testDebugUnitTest
git grep -in "[l]egado" || echo CLEAN
git add lib_book_source/src/main/java/com/ebook/source/script/ lib_book_source/src/test/java/com/ebook/source/script/
git commit -m "$(cat <<'EOF'
feat(lib_book_source): 脚本书源求值器接入 JSONPath 模式

三种形态（@json:/$./单花括号）统一进 JsonPathBackend，载荷使用点回填与反序消费
和其他后端同口径。Jsons 参与组合符：&& 合并、%% 交错、|| 短路里的语法错误照 §3.3
处理；逐条目求值 evaluateOnJsonItem 供 2d 的 JSON 列表字段取子字段。
EOF
)"
```

---

### Task 4: `RuleSplitter` 步骤 3——URL 选项尾段的剥出与回附

**Files:**
- Modify: `lib_book_source/src/main/java/com/ebook/source/script/RuleSplitter.kt`
- Modify: `lib_book_source/src/main/java/com/ebook/source/script/ScriptRuleEvaluator.kt`
- Modify: `lib_book_source/src/main/java/com/ebook/source/script/ScriptUrlOption.kt`（本任务先建，只放共用的尾段定位扫描器）
- Test: `lib_book_source/src/test/java/com/ebook/source/script/RuleSplitterTest.kt`（追加）
- Test: `lib_book_source/src/test/java/com/ebook/source/script/ScriptRuleEvaluatorCombinatorTest.kt`（追加 1 例）

**三处分工（规格 §2.3 步骤 3 + §9 第 7 步）**：切分层剥出（防止 `,{...}` 流进组合符/链式切分变成垃圾段）→ 结果层回附（URL 类字段的结果文本统一带尾段）→ 取文层消费（Task 6/7 解析选项发请求）。

- [x] **Step 1: 写失败的测试**

`RuleSplitterTest` 追加：

```kotlin
    @Test
    fun `URL 选项尾段从取值段剥出`() {
        val parsed = RuleSplitter.parse("""tag.a@href,{"charset":"gbk"}""")
        assertEquals(""",{"charset":"gbk"}""", parsed.optionTail)
        assertEquals("tag.a@href", (parsed.root as RuleNode.Leaf).body)
    }

    @Test
    fun `引号内的逗号与花括号不构成尾段`() {
        val parsed = RuleSplitter.parse("""tag.a@text&&x,"a},{b"""")
        // 逗号不在顶层（引号内），整条按原样切分
        assertNull(parsed.optionTail)
    }

    @Test
    fun `单花括号 JSONPath 不误剥选项尾段`() {
        val parsed = RuleSplitter.parse("{$.a}")
        assertNull(parsed.optionTail)
    }

    @Test
    fun `AllInOne 整条不剥选项尾段`() {
        // §6.1：选项不挂在选择器/正则语法后面——正确写法是 ##$##{...} 惯用法（替换文本带尾段）
        val parsed = RuleSplitter.parse(""":<li>([^<]+),{"a":1}""")
        assertNull(parsed.optionTail)
    }

    @Test
    fun `替换段与选项尾段并存时各归各`() {
        val parsed = RuleSplitter.parse("""tag.a@text,{"charset":"gbk"}##全文""")
        assertEquals(""",{"charset":"gbk"}""", parsed.optionTail)
        assertEquals("全文", parsed.replacement?.pattern)
    }
```

（`assertNull` 若该测试类未导入则补 `org.junit.Assert.assertNull`。）

`ScriptRuleEvaluatorCombinatorTest` 追加（端到端：剥出→求值→回附）：

```kotlin
    @Test
    fun `规则级选项尾段回附到结果文本`() {
        val r = ScriptRuleEvaluator(EvalContext()).evaluate(
            """tag.a@href,{"charset":"gbk"}""",
            RuleValue.Page("""<html><body><a href="/x/1.html">章</a></body></html>"""),
        )
        assertEquals(RuleResult.Texts(listOf("""/x/1.html,{"charset":"gbk"}""")), r)
    }
```

- [x] **Step 2: 运行确认失败**

Run: `./gradlew :lib_book_source:compileDebugUnitTestKotlin`
Expected: FAIL——`ParsedRule.optionTail` 未定义。

- [x] **Step 3: 新建 `ScriptUrlOption.kt`（先只有共用扫描器）**

```kotlin
package com.ebook.source.script

/**
 * URL 选项尾段（规格 §6.1 `URL,{option}`）的定位扫描器与选项模型。
 *
 * 切分依据是「引号与花括号配对」而不是「按逗号裸切」：选项整体是一个 JSON 对象，
 * 值里的逗号、花括号必须由 JSON 自己表达（§6.1 原文）。扫描器找出**最后一个**
 * 深度 0 的 `,{`，且要求从那里到串尾恰是一个配平对象——中途断掉就当没有尾段。
 *
 * [commaDepth] 供切分层（[RuleSplitter]）传入 2a 的括号深度表（把 `{{}}`/`{}`/`[]`
 * 也算作深度，防止规则载荷里的花括号干扰）；取文层在**已回填**的 URL 上调用，
 * 回填值可能含引号与花括号，由本扫描器自己的引号感知兜住，默认深度恒 0。
 */
internal object ScriptUrlOption {

    /** 返回尾段起点（`,` 的下标）；无尾段返回 null */
    internal fun optionTailCut(s: String, commaDepth: (Int) -> Int = { 0 }): Int? {
        var candidate = -1
        var depth = 0
        var inString = false
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (inString) {
                if (c == '\\') i++          // 跳过转义对
                else if (c == '"') inString = false
            } else when (c) {
                '"' -> inString = true
                '{' -> {
                    depth++
                    // 深度归零后的第一个 { 才可能是尾段开头；逗号必须在它紧前且在规则层深度 0
                    if (depth == 1 && i > 0 && s[i - 1] == ',' && commaDepth(i - 1) == 0) candidate = i - 1
                }
                '}' -> depth--
            }
            i++
        }
        return if (candidate >= 0 && depth == 0 && s.endsWith("}")) candidate else null
    }

    /** 尾段切分：返回（取值段, 尾段）；无尾段返回 null。尾段以 `,` 开头（起点即逗号） */
    internal fun splitTail(s: String): Pair<String, String>? =
        optionTailCut(s)?.let { s.substring(0, it) to s.substring(it) }
}
```

- [x] **Step 4: `ParsedRule` 加字段、`parse` 接入步骤 3**

`RuleSplitter.kt`：

```kotlin
internal data class ParsedRule(
    val root: RuleNode,
    val replacement: RegexReplacement?,
    /** §2.3 步骤 3 剥出的 URL 选项尾段（含前导逗号，如 `,{"charset":"gbk"}`）；无则 null */
    val optionTail: String?,
    val raw: String,
)
```

`parse` 的主路径改为（前置例外路径补 `optionTail = null`）：

```kotlin
        val whole = RuleMode.of(rule).mode
        if (whole == RuleMode.JS || whole == RuleMode.REGEX_ALL_IN_ONE) {
            // §6.1：选项尾段不挂在 JS/正则上，前置例外整条豁免（语料实证的附带方式是 ##$##{...}）
            return ParsedRule(node(rule, d, 0, rule.length), null, null, rule)
        }
        val cut = RuleScanner.topLevelOf(rule, d, "##").firstOrNull()
        val beforeReplacement = cut ?: rule.length
        val tailCut = ScriptUrlOption.optionTailCut(rule.substring(0, beforeReplacement)) { d[it] }
        val valueEnd = tailCut ?: beforeReplacement
        val replacement = cut?.let { RegexReplacement.parse(rule.substring(it)) }
        return ParsedRule(node(rule, d, 0, valueEnd), replacement, tailCut?.let { rule.substring(it) }, rule)
```

KDoc 补一句：步骤 3 在步骤 2 之后——替换文本里的 `,{...}`（`##$##{...}` 惯用法）属替换段，不在此剥。

- [x] **Step 5: 结果层回附**

`ScriptRuleEvaluator.evaluate` 末段改为：

```kotlin
        val replaced = ReplacementApplier.apply(raw, parsed.replacement, parsed.raw, expansion)
        val filled = expansion.fillResult(replaced)
        // §9 第 7 步：URL 类字段的结果统一带尾段，取文层据此解析选项——
        // 剥出是为了切分不被尾段干扰，回附是为了让「##$##{...}」与「规则级尾段」两种写法
        // 在取文层是同一形态
        val tail = parsed.optionTail?.let { expansion.fill(it) }
        return if (tail == null) filled else filled.withOptionTail(tail)
```

`ScriptRuleEvaluator` 内追加私有扩展：

```kotlin
    /** 选项尾段只附在文本结果上：Miss 没有可请求的 URL，节点/条目/JSON 结果不是 URL 字段形态 */
    private fun RuleResult.withOptionTail(tail: String): RuleResult = when (this) {
        is RuleResult.Texts -> RuleResult.Texts(values.map { it + tail })
        else -> this
    }
```

- [x] **Step 6: 运行测试通过**

Run: `./gradlew :lib_book_source:testDebugUnitTest --tests "com.ebook.source.script.RuleSplitterTest" --tests "com.ebook.source.script.ScriptRuleEvaluatorCombinatorTest"`
Expected: PASS（RuleSplitterTest 25；ScriptRuleEvaluatorCombinatorTest 23）。

- [x] **Step 7: 全模块回归 + 红线 + 提交**

```bash
./gradlew :lib_book_source:testDebugUnitTest
git grep -in "[l]egado" || echo CLEAN
git add lib_book_source/src/main/java/com/ebook/source/script/ lib_book_source/src/test/java/com/ebook/source/script/
git commit -m "$(cat <<'EOF'
feat(lib_book_source): 脚本书源 URL 选项尾段的剥出与回附

切分层剥出防止 ,{...} 流进组合符切分变成垃圾段（静默解错内容），
结果层回附让「规则级尾段」与「##$##{...} 惯用法」在取文层是同一形态。
尾段定位按引号与花括号配对，不做按逗号裸切——值里的逗号由 JSON 自己表达。
EOF
)"
```

---

### Task 5: 选项模型与不支持键的类型化拒绝

**Files:**
- Modify: `lib_book_source/src/main/java/com/ebook/source/script/ScriptUrlOption.kt`
- Test: `lib_book_source/src/test/java/com/ebook/source/script/ScriptUrlOptionTest.kt`（新建）

- [x] **Step 1: 写失败的测试**

```kotlin
package com.ebook.source.script

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 规格 §6.2 选项键的实现集与拒绝集、§6.3 body 形态。 */
class ScriptUrlOptionTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun parse(tail: String) = ScriptUrlOption.parse(json.parseToJsonElement(tail).jsonObject, "rule")

    @Test
    fun `完整选项解析`() {
        val o = parse("""{"method":"POST","charset":"gbk","body":"bid=1","timeout":5000,"retry":2}""")
        assertEquals("POST", o.method)
        assertEquals("gbk", o.charset)
        assertEquals("bid=1", o.body)
        assertEquals(5000L, o.timeoutMs)
        assertEquals(2, o.retry)
    }

    @Test
    fun `缺省值与空尾段`() {
        val o = parse("{}")
        assertEquals("GET", o.method)
        assertEquals("UTF-8", o.charset)
        assertNull(o.body)
        assertNull(o.timeoutMs)
        assertEquals(0, o.retry)
        assertEquals(emptyMap<String, String>(), o.headers)
    }

    @Test
    fun `headers 嵌套对象与字符串化 JSON 两种形态`() {
        val nested = parse("""{"headers":{"User-Agent":"UA/1","Accept":"text/html"}}""")
        assertEquals(mapOf("User-Agent" to "UA/1", "Accept" to "text/html"), nested.headers)
        val stringified = parse("""{"headers":"{\"User-Agent\":\"UA/2\"}"}""")
        assertEquals(mapOf("User-Agent" to "UA/2"), stringified.headers)
    }

    @Test
    fun `headers 字符串化形态不是合法 JSON 对象时判语法错误`() {
        val e = runCatching { parse("""{"headers":"not-json"}""") }.exceptionOrNull()
        assertTrue(e is RuleSyntaxException)
    }

    @Test
    fun `body 对象按 JSON 文本序列化`() {
        // §6.3 本仓规定：body 只接受字符串；给到对象/数组按 JSON 文本序列化（§11-6 跟踪）
        val o = parse("""{"body":{"a":1,"b":["x"]}}""")
        assertEquals("""{"a":1,"b":["x"]}""", o.body)
    }

    @Test
    fun `webView 与代理一族类型化拒绝`() {
        assertTrue(runCatching { parse("""{"webView":true}""") }.exceptionOrNull() is UnsupportedRuleFeatureException)
        assertTrue(runCatching { parse("""{"proxy":"socks5://127.0.0.1:1080"}""") }.exceptionOrNull() is UnsupportedRuleFeatureException)
        assertTrue(runCatching { parse("""{"dnsIp":"1.1.1.1"}""") }.exceptionOrNull() is UnsupportedRuleFeatureException)
        assertTrue(runCatching { parse("""{"followRedirects":false}""") }.exceptionOrNull() is UnsupportedRuleFeatureException)
    }

    @Test
    fun `选项内 js 与 bodyJs 按 JS 待执行拒绝`() {
        assertTrue(runCatching { parse("""{"js":"java.ajax('x')"}""") }.exceptionOrNull() is JsEvaluationPendingException)
        assertTrue(runCatching { parse("""{"bodyJs":"result"}""") }.exceptionOrNull() is JsEvaluationPendingException)
    }

    @Test
    fun `空串与缺省的拒绝键不算声明`() {
        // 空值 = 未配置（§1.1 默认列口径）：报「不支持」只该发生在作者真配了能力的源上
        val o = parse("""{"webView":"","js":"","type":"epub"}""")
        assertEquals("GET", o.method)
    }

    @Test
    fun `未知键忽略`() {
        // 格式允许未知键存在（原始入库零翻译），选项对象同理：不认识的键不是错误
        val o = parse("""{"someFutureKey":1,"method":"POST"}""")
        assertEquals("POST", o.method)
    }

    @Test
    fun `splitTail 在已回填 URL 上定位尾段`() {
        val (url, tail) = ScriptUrlOption.splitTail("""https://x.com,{"charset":"gbk"}""")!!
        assertEquals("https://x.com", url)
        assertEquals(""",{"charset":"gbk"}""", tail)
        assertNull(ScriptUrlOption.splitTail("https://x.com/plain"))
        // 回填值里的转义引号与花括号也兜得住：引号感知的扫描器不让值里的结构干扰定位
        val (u2, t2) = ScriptUrlOption.splitTail("""https://x.com,{"body":"a\",\"b}"}""")!!
        assertEquals("https://x.com", u2)
        assertTrue(t2.startsWith(","))
    }
}
```

- [x] **Step 2: 运行确认失败**

Run: `./gradlew :lib_book_source:compileDebugUnitTestKotlin`
Expected: FAIL——`ScriptUrlOption.parse` 未定义。

- [x] **Step 3: 实现选项模型**

`ScriptUrlOption.kt` 在扫描器之后追加：

```kotlin
/**
 * 解析后的请求选项（规格 §6.2 实现集；`proxy`/`webView`/`dnsIp`/`followRedirects` 见 [parse] 的拒绝）。
 *
 * [headers] 的键**保持原样大小写**（§6.2：key 区分大小写，`User-Agent` 正确、`user-agent` 无效）
 * ——HTTP 头本身大小写不敏感，但作者写小写 UA 说明按某个站点的怪癖在配，原样透传最诚实。
 */
internal data class ScriptUrlOptions(
    val method: String = "GET",
    val charset: String = "UTF-8",
    val body: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val timeoutMs: Long? = null,
    val retry: Int = 0,
) {
    companion object {
        val DEFAULT = ScriptUrlOptions()
    }
}
```

`ScriptUrlOption` object 内追加（import 补 `kotlinx.serialization.json.*`）：

```kotlin
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * 解析选项对象（[rule] 只作错误消息定位用）。
     *
     * 拒绝集与理由（§10 能力矩阵）：`webView` 无 WebView 桥；`proxy` 安全口径（书源请求
     * 不得出到任意代理）；`dnsIp`/`followRedirects` 是客户端配置口径非规则语义；`js`/`bodyJs`
     * 是 JS，抛待执行等 Plan 3。`type` 语义未核实（§6.2）、连同一切未知键**忽略**——
     * 格式允许未知键存在，把不认识的键当错误会让带新兴键的源整条不可用。
     * 「声明了」才拒绝：空串在 §1.1 默认列就是未配置。
     */
    fun parse(obj: JsonObject, rule: String): ScriptUrlOptions {
        fun declared(key: String): Boolean = when (val v = obj[key]) {
            null, is JsonNull -> false
            is JsonPrimitive -> v.contentOrNull?.isNotBlank() == true
            else -> true
        }
        if (declared("webView")) throw UnsupportedRuleFeatureException("webView 渲染", rule)
        if (declared("proxy")) throw UnsupportedRuleFeatureException("请求代理", rule)
        if (declared("dnsIp")) throw UnsupportedRuleFeatureException("dnsIp 解析", rule)
        if (declared("followRedirects")) throw UnsupportedRuleFeatureException("followRedirects", rule)
        if (declared("js")) throw JsEvaluationPendingException(rule)
        if (declared("bodyJs")) throw JsEvaluationPendingException(rule)

        val headers = when (val h = obj["headers"]) {
            null, is JsonNull -> emptyMap()
            is JsonObject -> h.entries.associate { (k, v) -> k to primitiveText(v) }
            is JsonPrimitive -> (runCatching { json.parseToJsonElement(h.content) }.getOrNull() as? JsonObject)
                ?.let { parsed -> parsed.entries.associate { (k, v) -> k to primitiveText(v) } }
                ?: throw RuleSyntaxException(rule)
            else -> throw RuleSyntaxException(rule)
        }
        val body = when (val b = obj["body"]) {
            null, is JsonNull -> null
            is JsonPrimitive -> b.content
            // §6.3 本仓规定：对象/数组按 JSON 文本序列化后使用（§11-6 跟踪）
            else -> b.toString()
        }
        return ScriptUrlOptions(
            method = (obj["method"] as? JsonPrimitive)?.contentOrNull?.trim()?.uppercase().takeUnless { it.isNullOrEmpty() } ?: "GET",
            charset = (obj["charset"] as? JsonPrimitive)?.contentOrNull?.trim().takeUnless { it.isNullOrEmpty() } ?: "UTF-8",
            body = body,
            headers = headers,
            timeoutMs = (obj["timeout"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull(),
            retry = (obj["retry"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: 0,
        )
    }

    private fun primitiveText(v: JsonElement): String = when (v) {
        is JsonPrimitive -> v.content
        else -> v.toString()
    }
```

- [x] **Step 4: 运行测试通过**

Run: `./gradlew :lib_book_source:testDebugUnitTest --tests "com.ebook.source.script.ScriptUrlOptionTest"`
Expected: PASS（10）。

- [x] **Step 5: 红线 + 提交**

```bash
git grep -in "[l]egado" || echo CLEAN
git add lib_book_source/src/main/java/com/ebook/source/script/ScriptUrlOption.kt lib_book_source/src/test/java/com/ebook/source/script/ScriptUrlOptionTest.kt
git commit -m "$(cat <<'EOF'
feat(lib_book_source): 脚本书源 URL 选项模型与不支持键拒绝

headers 双形态（嵌套对象/字符串化 JSON）与 body 字符串口径都按规格原文，
拒绝集每个键都带能力矩阵的理由：webView 无桥、proxy 是安全口径、js 待 Plan 3。
空值等于未配置，对着一堆正常源喊不支持会让用户不再相信报告。
EOF
)"
```

---

### Task 6: `ScriptUrlResolver`（`{{}}` 展开、`<>` 页码形态、相对落位）

**Files:**
- Create: `lib_book_source/src/main/java/com/ebook/source/script/ScriptUrlResolver.kt`
- Test: `lib_book_source/src/test/java/com/ebook/source/script/ScriptUrlResolverTest.kt`（新建）

- [x] **Step 1: 写失败的测试**

```kotlin
package com.ebook.source.script

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 规格 §6.4 页码形态、§6.5 落位、§5.1 的 URL 插值与「展开值不参与切分」。 */
class ScriptUrlResolverTest {

    private fun resolve(
        rule: String,
        ctx: EvalContext = EvalContext(baseUrl = "https://root.com/dir/page.html", page = 1),
        sourceRoot: String = "https://root.com",
    ): ResolvedScriptUrl = ScriptUrlResolver.resolve(rule, ctx, sourceRoot) { RuleResult.Miss }

    @Test
    fun `裸 URL 原样落位为默认选项`() {
        val r = resolve("https://x.com/search")
        assertEquals("https://x.com/search", r.url)
        assertEquals(ScriptUrlOptions.DEFAULT, r.options)
    }

    @Test
    fun `内置量与变量展开进 URL`() {
        val ctx = EvalContext(baseUrl = "https://root.com", key = "凡人", page = 3)
        ctx.variables["enc"] = "gbk"
        val r = resolve("https://x.com/s?key={{key}}&page={{page}}&enc={{enc}}", ctx)
        assertEquals("https://x.com/s?key=凡人&page=3&enc=gbk", r.url)
    }

    @Test
    fun `展开值含分隔符不炸 URL 组装`() {
        // §5.1 本仓规定的回归锁：关键词含 & 与 || 时，占位符机制保证它不被当结构切掉
        val ctx = EvalContext(baseUrl = "https://root.com", key = "a&b||c")
        val r = resolve("https://x.com/s?key={{key}}&page={{page}}", ctx)
        assertEquals("https://x.com/s?key=a&b||c&page=1", r.url)
    }

    @Test
    fun `尾段里的插值一并展开`() {
        // §5.1：语料实证 "body":"page={{page}}&key={{key}}"
        val ctx = EvalContext(baseUrl = "https://root.com", key = "凡人", page = 2)
        val r = resolve("""https://x.com/s,{"method":"POST","body":"page={{page}}&key={{key}}"}""", ctx)
        assertEquals("POST", r.options.method)
        assertEquals("page=2&key=凡人", r.options.body)
    }

    @Test
    fun `算术页码表达式维持 JS 待执行`() {
        // {{(page-1)*20}} 是 JS（Plan 3）；2c 不得绕过 Interpolation 自己算页码（§6.4/2b 边界）
        val e = runCatching { resolve("https://x.com/list/{{(page-1)*20}}") }.exceptionOrNull()
        assertTrue(e is JsEvaluationPendingException)
    }

    @Test
    fun `尖括号形态页码1整段不进URL`() {
        val r = resolve("https://x.com/list<,{{page}}>.html", EvalContext(baseUrl = "https://root.com", page = 1))
        assertEquals("https://x.com/list.html", r.url)
    }

    @Test
    fun `尖括号形态页码大于1输出分隔符与内容`() {
        val r = resolve("https://x.com/list<,{{page}}>.html", EvalContext(baseUrl = "https://root.com", page = 2))
        assertEquals("https://x.com/list2.html", r.url)
        val r2 = resolve("https://x.com/search<,&page={{page}}>", EvalContext(baseUrl = "https://root.com", page = 3))
        assertEquals("https://x.com/search&page=3", r2.url)
    }

    @Test
    fun `尖括号形态配选项尾段时两段各归各`() {
        val r = resolve(
            """https://x.com/list<,{{page}}>.html,{"charset":"gbk"}""",
            EvalContext(baseUrl = "https://root.com", page = 1),
        )
        assertEquals("https://x.com/list.html", r.url)
        assertEquals("gbk", r.options.charset)
    }

    @Test
    fun `不带逗号的尖括号段不是页码形态原样保留`() {
        // §6.4：不得自行发明 <...> 的其它用法——没有「分隔符,内容」结构就不当页码段处理
        val r = resolve("https://x.com/a<b>c")
        assertEquals("https://x.com/a<b>c", r.url)
    }

    @Test
    fun `相对地址按三形态落位`() {
        // §6.5 与 §12：复用 TocPageUrl.join 的语义，绝对原样 / / 相对源根 / 其余相对当前页目录
        assertEquals("https://root.com/x/1", resolve("/x/1", EvalContext(baseUrl = "https://root.com")).url)
        assertEquals(
            "https://root.com/dir/index_2.html",
            resolve("index_2.html", EvalContext(baseUrl = "https://root.com/dir/page.html")).url,
        )
    }
}
```

- [x] **Step 2: 运行确认失败**

Run: `./gradlew :lib_book_source:compileDebugUnitTestKotlin`
Expected: FAIL——`ScriptUrlResolver` / `ResolvedScriptUrl` 未定义。

- [x] **Step 3: 实现 `ScriptUrlResolver.kt`**

```kotlin
package com.ebook.source.script

/**
 * URL 规则串 → 绝对地址 + 选项（规格 §6）。
 *
 * 处理次序（每一步的理由）：
 * 1. `{{}}` 展开复用 [Interpolation] 的占位符机制——展开值常含 `&`/`||`/`{}`，
 *    就地回填会让 URL 被自己的数据腰斩（§5.1）；`key`/`page`/`baseUrl`/变量经
 *    `EvalContext.builtin` 就地可解，`@@规则` 递归求值由调用方注入闭包。
 * 2. 尾段在**占位符态**定位（花括号结构完整）、在**回填后**解析（值进 JSON 字符串
 *    才有意义）；回填值破坏 JSON 结构（关键词带引号）时按语法错误如实报。
 * 3. `<,内容>` 页码形态（§6.4 本仓规定）：页码 1 整段（含分隔符）不进 URL，
 *    其余页输出「分隔符 + 内容」。没有 `,` 结构的 `<...>` 不是页码段，原样保留——
 *    本仓不得自行发明 `<...>` 的其它用法。
 * 4. 相对落位复用 `TocPageUrl.join` 三形态语义（§6.5/§12：不另立口径）。
 *    **绝不复用 `ListPageUrl.build` 的首页裁剪**：脚本 URL 由作者写全形态，
 *    对已算好的 URL 再裁一次会裁掉真实页码段（§6.4 明令）。
 */
internal object ScriptUrlResolver {

    fun resolve(
        ruleUrl: String,
        ctx: EvalContext,
        sourceRoot: String,
        evaluateInner: (String) -> RuleResult,
    ): ResolvedScriptUrl {
        val (expansion, masked) = Interpolation.expand(ruleUrl, ctx, evaluateInner)
        val (urlMasked, tailMasked) = ScriptUrlOption.splitTail(masked)
        val urlFilled = anglePages(expansion.fill(urlMasked), ctx.page)
        val absolute = com.ebook.source.analyze.TocPageUrl.join(ctx.baseUrl, urlFilled, sourceRoot)
        val options = tailMasked
            ?.let { ScriptUrlOption.parse(parseObject(expansion.fill(it), ruleUrl), ruleUrl) }
            ?: ScriptUrlOptions.DEFAULT
        return ResolvedScriptUrl(absolute, options)
    }

    /** 已回填的尾段文本 → JSON 对象；回填值破坏 JSON 结构时按语法错误如实报（§3.3） */
    private fun parseObject(tail: String, rule: String): JsonObject =
        runCatching { Json { ignoreUnknownKeys = true; isLenient = true }.parseToJsonElement(tail) }
            .getOrNull() as? JsonObject
            ?: throw RuleSyntaxException(rule)

    /**
     * `<分隔符,内容>` 形态（§6.4）。内容里的 `{{}}` 已在上一步回填成实际页码文本。
     * 未闭合的 `<` 原样保留：残缺形态是真实输入，抛出去会让整条 URL 作废。
     */
    private fun anglePages(url: String, page: Int): String {
        if (!url.contains('<')) return url
        val out = StringBuilder(url.length)
        var i = 0
        while (i < url.length) {
            if (url[i] == '<') {
                val close = url.indexOf('>', i + 1)
                val inner = if (close > i) url.substring(i + 1, close) else null
                val comma = inner?.indexOf(',')
                if (inner != null && comma >= 0) {
                    if (page > 1) {
                        out.append(inner, 0, comma).append(inner.substring(comma + 1))
                    }
                    i = close + 1
                    continue
                }
            }
            out.append(url[i])
            i++
        }
        return out.toString()
    }
}

/** 一次 URL 解析的产物：绝对地址 + 请求选项（无尾段时为 [ScriptUrlOptions.DEFAULT]） */
internal data class ResolvedScriptUrl(val url: String, val options: ScriptUrlOptions)
```

文件顶部补 import：`kotlinx.serialization.json.Json`、`kotlinx.serialization.json.JsonObject`。（`ScriptUrlOption.splitTail` 已在 Task 4 落位。）

- [x] **Step 4: 运行测试通过**

Run: `./gradlew :lib_book_source:testDebugUnitTest --tests "com.ebook.source.script.ScriptUrlResolverTest"`
Expected: PASS（9）。

- [x] **Step 5: 红线 + 提交**

```bash
git grep -in "[l]egado" || echo CLEAN
git add lib_book_source/src/main/java/com/ebook/source/script/ScriptUrlResolver.kt lib_book_source/src/main/java/com/ebook/source/script/ScriptUrlOption.kt lib_book_source/src/test/java/com/ebook/source/script/ScriptUrlResolverTest.kt
git commit -m "$(cat <<'EOF'
feat(lib_book_source): 脚本书源 URL 解析——插值、页码形态与落位

<> 形态按 §6.4 本仓规定：页码 1 整段不进 URL；没有逗号结构的 <...> 不是页码段，
本仓不自行发明其它用法。相对落位复用 TocPageUrl 三形态语义不另立口径，
绝不复用 ListPageUrl 首页裁剪——对算好的 URL 再裁一次会裁掉真实页码。
EOF
)"
```

---

### Task 7: 取文层（`ScriptRequest`/传输接缝/OkHttp 实现/`ScriptPageFetcher`）

**Files:**
- Create: `lib_book_source/src/main/java/com/ebook/source/script/ScriptHttp.kt`
- Test: `lib_book_source/src/test/java/com/ebook/source/script/ScriptHttpTest.kt`（新建）

**测试策略**：单测打**接缝**（假 `ScriptTransport` 断言 `ScriptRequest` 字段），OkHttp 实现保持薄——`mockwebserver` 不在版本目录、不为它新增依赖；OkHttp 侧的真实行为（headers/重试/charset）由 2d 金标准 fixtures + 装机验证兜住（本段照旧无人工装机项：没有任何调用方）。

- [x] **Step 1: 写失败的测试**

```kotlin
package com.ebook.source.script

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 取文链路的组装：URL 规则串 → ScriptRequest；传输接缝用假件断言字段。 */
class ScriptHttpTest {

    private class RecordingTransport : ScriptTransport {
        val requests = mutableListOf<ScriptRequest>()
        var response: String = "<html>ok</html>"
        override suspend fun execute(request: ScriptRequest): String {
            requests += request
            return response
        }
    }

    private fun fetcher(transport: RecordingTransport) =
        ScriptPageFetcher(okHttpClient = null, sourceRoot = "https://root.com", transport = transport)

    @Test
    fun `无尾段 URL 走默认 GET`() = runTest {
        val transport = RecordingTransport()
        fetcher(transport).fetch("https://x.com/search", EvalContext()) { RuleResult.Miss }
        assertEquals(listOf("GET"), transport.requests.map { it.method })
        assertEquals("UTF-8", transport.requests.single().charset)
        assertEquals("https://x.com/search", transport.requests.single().url)
    }

    @Test
    fun `POST 选项完整进入请求`() = runTest {
        val transport = RecordingTransport()
        fetcher(transport).fetch(
            """https://x.com,{"method":"POST","body":"k={{key}}","headers":{"Content-Type":"application/x-www-form-urlencoded"},"timeout":4000,"retry":2}""",
            EvalContext(key = "凡人"),
        ) { RuleResult.Miss }
        val r = transport.requests.single()
        assertEquals("POST", r.method)
        assertEquals("k=凡人", r.body)
        assertEquals("application/x-www-form-urlencoded", r.headers["Content-Type"])
        assertEquals(4000L, r.timeoutMs)
        assertEquals(2, r.retry)
    }

    @Test
    fun `不支持选项在组装期就类型化拒绝`() = runTest {
        val e = runCatching {
            fetcher(RecordingTransport()).fetch("""https://x.com,{"webView":true}""", EvalContext()) { RuleResult.Miss }
        }.exceptionOrNull()
        assertTrue(e is UnsupportedRuleFeatureException)
    }

    @Test
    fun `返回值即响应文本`() = runTest {
        val transport = RecordingTransport().apply { response = "正文内容" }
        val text = fetcher(transport).fetch("https://x.com/book/1", EvalContext()) { RuleResult.Miss }
        assertEquals("正文内容", text)
    }
}
```

- [x] **Step 2: 运行确认失败**

Run: `./gradlew :lib_book_source:compileDebugUnitTestKotlin`
Expected: FAIL——`ScriptRequest`/`ScriptTransport`/`ScriptPageFetcher` 未定义。

- [x] **Step 3: 实现 `ScriptHttp.kt`**

```kotlin
package com.ebook.source.script

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.nio.charset.Charset
import java.util.concurrent.TimeUnit

/** 一次取文请求的纯数据形态：与传输实现解耦，测试与 2d 的假件都只打它 */
internal data class ScriptRequest(
    val url: String,
    val method: String = "GET",
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null,
    val charset: String = "UTF-8",
    val timeoutMs: Long? = null,
    val retry: Int = 0,
)

/**
 * 传输接缝：执行请求并返回**已按选项 charset 解码**的响应文本。
 *
 * 独立成接口的理由：`mockwebserver` 不在版本目录，OkHttp 侧行为留给 2d 金标准
 * fixtures + 装机验证；单测（本段）与 2d 的解析器测试都用假件打这个接缝，
 * 断言「请求长什么样」而不是「HTTP 栈怎么发」。
 */
internal fun interface ScriptTransport {
    suspend fun execute(request: ScriptRequest): String
}

/**
 * OkHttp 实现。三个刻意的细节：
 * 1. **显式字节解码**：响应按 `bytes()` 取回再 `String(bytes, charset)`——绝不走
 *    `body.string()`。`@Named("source")` 客户端挂着 `EncodingInterceptor("UTF-8")`
 *    把 Content-Type 强改成 UTF-8，`string()` 会照它解码，gbk 站点全部乱码（§6.2/§10）。
 * 2. **timeout 是每请求派生**：`newBuilder()` 共享连接池，只有配了 `timeout` 的请求
 *    才付出派生客户端的代价。
 * 3. **非 2xx 按 IOException 参与重试**（§6.2 `retry` 是重试次数，总尝试 = retry + 1）。
 */
internal class OkHttpScriptTransport(private val client: OkHttpClient) : ScriptTransport {

    override suspend fun execute(request: ScriptRequest): String = withContext(Dispatchers.IO) {
        val target = request.timeoutMs
            ?.let { client.newBuilder().readTimeout(it, TimeUnit.MILLISECONDS).build() }
            ?: client
        var lastError: IOException? = null
        repeat(request.retry.coerceAtLeast(0) + 1) {
            try {
                return@withContext executeOnce(target, request)
            } catch (e: IOException) {
                lastError = e
            }
        }
        throw lastError ?: IOException("请求失败：${request.url}")
    }

    private fun executeOnce(client: OkHttpClient, request: ScriptRequest): String {
        val charset = charsetOf(request.charset)
        val http = try {
            Request.Builder()
                .url(request.url)
                .apply { request.headers.forEach { (k, v) -> header(k, v) } }
                // body 的 Content-Type 由 headers 显式给出（§6.3：文档未规定默认头），
                // 这里不传 MediaType，避免与 headers 里的声明打架
                .method(request.method.uppercase(), request.body?.toRequestBody())
                .build()
        } catch (e: IllegalArgumentException) {
            // 非法 URL、GET+body 一类写坏：OkHttp 抛未类型化的 IllegalArgumentException，
            // 换成类型化错误如实报（§12「失败要如实报」）
            throw RuleSyntaxException("${request.method} ${request.url}")
        }
        client.newCall(http).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}：${request.url}")
            String(response.body.bytes(), charset)
        }
    }

    private fun charsetOf(name: String): Charset =
        runCatching { Charset.forName(name) }.getOrElse {
            // 作者写错的 charset 是规则值错误，不是「本仓不支持」——按语法错误报
            throw RuleSyntaxException("URL 选项 charset 不可识别：$name")
        }
}

/**
 * 取文门面：URL 规则串 → 解析（插值/页码/落位/选项）→ 请求 → 响应文本。
 *
 * 2d 的 `BookParser` 实现按字段调它；`evaluateInner` 把 `@@规则` 插值接到求值器
 * （与 [ScriptRuleEvaluator] 的递归口同形）。`okHttpClient` 允许 null 仅因为
 * 测试总以假 transport 构造——生产接线（2d 的 DI）必须传真客户端。
 */
internal class ScriptPageFetcher(
    okHttpClient: OkHttpClient?,
    private val sourceRoot: String,
    private val transport: ScriptTransport = OkHttpScriptTransport(okHttpClient ?: error("okHttpClient 未注入")),
) {

    suspend fun fetch(
        ruleUrl: String,
        ctx: EvalContext,
        evaluateInner: (String) -> RuleResult,
    ): String {
        val resolved = ScriptUrlResolver.resolve(ruleUrl, ctx, sourceRoot, evaluateInner)
        return transport.execute(
            ScriptRequest(
                url = resolved.url,
                method = resolved.options.method,
                headers = resolved.options.headers,
                body = resolved.options.body,
                charset = resolved.options.charset,
                timeoutMs = resolved.options.timeoutMs,
                retry = resolved.options.retry,
            ),
        )
    }
}
```

- [x] **Step 4: 运行测试通过**

Run: `./gradlew :lib_book_source:testDebugUnitTest --tests "com.ebook.source.script.ScriptHttpTest"`
Expected: PASS（4）。

- [x] **Step 5: 全模块回归 + 红线 + 提交**

```bash
./gradlew :lib_book_source:testDebugUnitTest
git grep -in "[l]egado" || echo CLEAN
git add lib_book_source/src/main/java/com/ebook/source/script/ScriptHttp.kt lib_book_source/src/test/java/com/ebook/source/script/ScriptHttpTest.kt
git commit -m "$(cat <<'EOF'
feat(lib_book_source): 脚本书源取文层与 OkHttp 传输

响应按字节取回再按选项 charset 显式解码：@Named("source") 客户端的编码拦截器
把 Content-Type 强改成 UTF-8，走 body.string() 会让 gbk 站全部乱码。
传输收成接口接缝：单测与 2d 假件只断言请求形态，HTTP 栈行为留给金标准 fixtures。
EOF
)"
```

---

### Task 8: `ExploreUrlFormat`（发现页两种格式的切分）

**Files:**
- Create: `lib_book_source/src/main/java/com/ebook/source/script/ExploreUrlFormat.kt`
- Test: `lib_book_source/src/test/java/com/ebook/source/script/ExploreUrlFormatTest.kt`（新建）

- [x] **Step 1: 写失败的测试**

```kotlin
package com.ebook.source.script

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 规格 §8.1 两种格式、§8.3 花括号不透明、§11-13 行优先分隔。 */
class ExploreUrlFormatTest {

    @Test
    fun `文本格式按行与双与号切条目`() {
        val entries = ExploreUrlFormat.split("男生::/a/{{page}}&&女生::/b/{{page}}\n出版::/c")
        assertEquals(listOf("男生", "女生", "出版"), entries.map { it.title })
        assertEquals(listOf("/a/{{page}}", "/b/{{page}}", "/c"), entries.map { it.urlRule })
    }

    @Test
    fun `插值内容里的双与号不切条目`() {
        // §8.3：{{}}/{}} 内的 && 是数据不是分隔符——腰斩的症状是「一条发现入口变两条坏的」
        val entries = ExploreUrlFormat.split("""分类::/x?k={{java.getString("a&&b")}}&&其他::/y""")
        assertEquals(2, entries.size)
        assertEquals("""/x?k={{java.getString("a&&b")}}""", entries[0].urlRule)
    }

    @Test
    fun `缺名称的条目标题为空`() {
        val entries = ExploreUrlFormat.split("/only-url")
        assertEquals(listOf(""), entries.map { it.title })
        assertEquals(listOf("/only-url"), entries.map { it.urlRule })
    }

    @Test
    fun `JSON 格式二只取 title 与 url`() {
        val raw = """[{"title":"玄幻","url":"/x/{{page}}","style":{"layout_flexGrow":1}},{"title":"控件","type":"toggle"},{"url":"/y"}]"""
        val entries = ExploreUrlFormat.split(raw)
        assertEquals(2, entries.size)
        assertEquals("玄幻", entries[0].title)
        assertEquals("/x/{{page}}", entries[0].urlRule)
        assertEquals("", entries[1].title)
        assertEquals("/y", entries[1].urlRule)
    }

    @Test
    fun `空串与坏 JSON 都返回空清单`() {
        assertTrue(ExploreUrlFormat.split("").isEmpty())
        assertTrue(ExploreUrlFormat.split("   ").isEmpty())
        assertTrue(ExploreUrlFormat.split("[{broken").isEmpty())
    }
}
```

- [x] **Step 2: 运行确认失败**

Run: `./gradlew :lib_book_source:compileDebugUnitTestKotlin`
Expected: FAIL——`ExploreUrlFormat` 未定义。

- [x] **Step 3: 实现 `ExploreUrlFormat.kt`**

```kotlin
package com.ebook.source.script

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 发现页 `exploreUrl` 的条目切分（规格 §8；2d 的发现链路消费）。
 *
 * 文本格式一：`名称::URL`，条目分隔**行优先**（先按 `\n` 切、再在单行内按 `&&` 切，
 * §11-13 本仓规定——URL 查询串天然含 `&&`，条目级只认行边界能把误切面收到最小）；
 * `{{}}`/`{}` 内容对分隔符**不透明**（§8.3：JS 表达式里的 `&&` 是数据）。
 * 缺 `名称::` 的条目标题为空——它可能是合法的单入口写法，丢弃比保留更难发现。
 *
 * JSON 格式二：只取 `{title,url}`；`style` 与交互控件不是本仓能力（§10），含控件项忽略。
 */
internal object ExploreUrlFormat {

    data class ExploreEntry(val title: String, val urlRule: String)

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun split(raw: String): List<ExploreEntry> {
        val text = raw.trim()
        if (text.isEmpty()) return emptyList()
        return if (text.startsWith("[")) splitJson(text) else splitText(text)
    }

    private fun splitJson(text: String): List<ExploreEntry> =
        (runCatching { json.parseToJsonElement(text) }.getOrNull() as? JsonArray)
            ?.mapNotNull { el ->
                val obj = el as? JsonObject ?: return@mapNotNull null
                val url = (obj["url"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
                if (url.isEmpty()) null
                else ExploreEntry((obj["title"] as? JsonPrimitive)?.contentOrNull.orEmpty(), url)
            }
            ?: emptyList()

    private fun splitText(text: String): List<ExploreEntry> {
        val out = mutableListOf<ExploreEntry>()
        for (line in text.split('\n')) {
            for (part in splitTopLevel(line, "&&")) {
                val entry = part.trim()
                if (entry.isEmpty()) continue
                val sep = topLevelIndexOf(entry, "::")
                if (sep < 0) out += ExploreEntry("", entry)
                else out += ExploreEntry(entry.substring(0, sep).trim(), entry.substring(sep + 2).trim())
            }
        }
        return out
    }

    /** 引号与 `{}` 深度感知的顶层切分；`sep` 在引号内或花括号深度 > 0 时不切 */
    private fun splitTopLevel(s: String, sep: String): List<String> {
        val out = mutableListOf<String>()
        var depth = 0
        var inQuote = false
        var cursor = 0
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '"' && (i == 0 || s[i - 1] != '\\') -> inQuote = !inQuote
                !inQuote && c == '{' -> depth++
                !inQuote && c == '}' -> depth = (depth - 1).coerceAtLeast(0)
                !inQuote && depth == 0 && s.startsWith(sep, i) -> {
                    out += s.substring(cursor, i)
                    cursor = i + sep.length
                    i += sep.length
                    continue
                }
            }
            i++
        }
        out += s.substring(cursor)
        return out
    }

    /** 同深度口径找 `sep` 的首个顶层出现位置；找不到返回 -1 */
    private fun topLevelIndexOf(s: String, sep: String): Int {
        var depth = 0
        var inQuote = false
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '"' && (i == 0 || s[i - 1] != '\\') -> inQuote = !inQuote
                !inQuote && c == '{' -> depth++
                !inQuote && c == '}' -> depth = (depth - 1).coerceAtLeast(0)
                !inQuote && depth == 0 && s.startsWith(sep, i) -> return i
            }
            i++
        }
        return -1
    }
}
```

- [x] **Step 4: 运行测试通过**

Run: `./gradlew :lib_book_source:testDebugUnitTest --tests "com.ebook.source.script.ExploreUrlFormatTest"`
Expected: PASS（5）。

- [x] **Step 5: 红线 + 提交**

```bash
git grep -in "[l]egado" || echo CLEAN
git add lib_book_source/src/main/java/com/ebook/source/script/ExploreUrlFormat.kt lib_book_source/src/test/java/com/ebook/source/script/ExploreUrlFormatTest.kt
git commit -m "$(cat <<'EOF'
feat(lib_book_source): 脚本书源发现页条目切分

条目分隔行优先（先换行再单行内 &&）把误切面收到最小；插值与引号内容对
分隔符不透明，腰斩一条带 JS 表达式的发现 URL 不会报错、只会静默变两条坏的。
JSON 格式二只认 {title,url}，控件项按能力矩阵忽略。
EOF
)"
```

---

### Task 9: 文档同步与全段验证

**Files:**
- Modify: `docs/superpowers/specs/2026-09-08-script-source-rule-grammar.md`（§11-6/§11-7 落地注记 + 新增 §11-17）
- Modify: `AGENTS.md`（书源实战建议段 2c 边界改写）
- Modify: `docs/superpowers/plans/2026-09-08-script-book-source-foundation.md`（路线图 2c 状态）
- Modify: 本计划文件（勾选、执行期修正记录）

- [x] **Step 1: 规格就地更新**

- §11-6 追加落地注记：`body` 对象/数组按 JSON 文本序列化的本仓规定已在 2c 落地（`ScriptUrlOption.parse`）；`Content-Type` 默认值仍未核实（`headers` 必须显式给，实现不注入默认头）；`type` 键语义未核实、实现选择忽略。
- §11-7 追加落地注记：`<,内容>` 已按本仓规定实现（`ScriptUrlResolver.anglePages`：页码 1 整段不进 URL、无逗号结构的 `<...>` 原样保留）；真实源验证仍未做（2d 装机）。
- 新增 §11-17：**JSONPath 子集边界**。2c 实现 `$`/`.name`/`['name']`/`[n]`/`[-n]`/`[*]`/`.*`/`..name`/`[a:b(:c)]`/`[a,b]`；过滤器 `[?(…)]` 与脚本表达式 `[(…)]` 类型化拒绝；切片按标准 JSONPath 半开方言（end 排他、负数从尾数）——与 §4 链式索引的闭区间（本仓规定）分属两个语法域，**不得互相推广**。扩集以 2d 金标准 fixtures 的语料证据为准。
- 新增 §11-18：**JSON 输入与 HTML/正则后端的域不匹配**。`RuleValue.Json` 进链式/CSS/正则 AllInOne 后端一律抛 `UnsupportedRuleFeatureException`（Task 1 落地的本仓规定）——它**不参与** `||` 短路（`firstOf` 只捕获 `RuleSyntaxException`），与 `JsonPathBackend` 种子对 Page/Texts「解析失败按 Miss」**刻意不对称**：前者是「选择器跑在它跑不了的东西上」，如实报能力缺口；后者是「响应不是规则串」，坏响应按未取到值交给 `||` 兜底。两侧不得为「对称好看」互相改。

- [x] **Step 2: AGENTS.md 更新**

把书源实战建议段里「**尚未落地的**：JSONPath 后端与 URL 语义、网络请求归 2c……」改写为当前实况：**JSONPath 与 URL 语义、取文层已在 2c 落地**（三形态进 `JsonPathBackend`；`ScriptUrlResolver`+`ScriptPageFetcher` 串起「URL 规则串 → 请求 → 响应文本」），**仍未接页面**（`ScriptSourcePendingParser` 原位，2d 才替换）。并写明三条口径：(a) 取文按选项 charset **显式字节解码**，走 `body.string()` 会吃掉 `EncodingInterceptor` 强改的 UTF-8 让 gbk 站乱码；(b) 相对落位复用 `TocPageUrl.join`，`ListPageUrl` 首页裁剪**绝不**用于脚本 URL；(c) JSONPath 切片是标准半开方言、与链式索引闭区间分属两个语法域；过滤器按不支持拒绝。

- [x] **Step 3: 全量验证**

```bash
./gradlew test
./gradlew :module_app:assembleDebug
git grep -in "[l]egado"      # 零命中
```

已知既有 flake：`module_find` 的 `LibraryViewModelTest` 偶发 `Dispatchers.Main is used concurrently with setting it`，命中时重跑确认，不要顺手改。

- [x] **Step 4: 本段仍无人工装机项**

2c 不改任何页面、启动与持久化路径（`ScriptSourcePendingParser` 原位、取文层无调用方），**不新增**人工清单条目；报告时明确「本段无设备验证，因为没有任何用户可达路径变化」，并说明欠下的语料证据债（JSONPath 子集、`<>` 形态、选项键频度）由 2d 金标准 fixtures 偿还。

- [x] **Step 5: 提交**

```bash
git add AGENTS.md docs/superpowers/
git commit -m "$(cat <<'EOF'
docs: 登记脚本书源 URL 语义与 JSONPath 落地口径

规格 §11 标注 2c 落地项（body 序列化、<> 页码形态）并新增 §11-17
JSONPath 子集边界（过滤器拒绝、切片半开方言与 §4 闭区间分域）。
AGENTS.md 更正能力边界：JSONPath 与取文层已落地，页面接线仍归 2d。
EOF
)"
```

---

## 退出判据

1. `./gradlew test` 全绿、`:module_app:assembleDebug` 成功；`:lib_book_source` 用例数在 2b 的 215 之上按各任务预期增加（Task 1: +2、Task 2: +12、Task 3: +6、Task 4: +6、Task 5: +10、Task 6: +9、Task 7: +4、Task 8: +5，预期 ≥269）。
2. 规格 §2.1/§6/§8 中属于 2c 的每条判定都能在代码或单测里指到落点；不支持的能力（过滤器、`webView`、`proxy`、`js`/`bodyJs` 选项、算术页码）都有**类型化异常**路径而不是静默空结果。
3. `RuleMode.JSON_PATH` 不再抛 `UnsupportedRuleFeatureException`；`ScriptSourcePendingParser` 与全部页面/DI 接线**零改动**。
4. `git grep -in "[l]egado"` 零命中；无新增编译警告。
5. 规格与 AGENTS.md 与实现三者口径一致（Task 9 完成）。

## 留给 2d 的接口

- **`ScriptPageFetcher.fetch(ruleUrl, ctx, evaluateInner)`**：搜索/详情/目录/正文的每次取文都走它；`ctx.key`/`ctx.page`/`ctx.baseUrl` 由调用方按场景装（§5.3 的作用域表）。
- **`ScriptRuleEvaluator.evaluateOnJsonItem` / `evaluateOnItem`**：JSON 列表与 AllInOne 条目 × 子字段的取值入口。
- **`ExploreUrlFormat.split`**：发现页条目 → `(title, urlRule)`，URL 规则串再进 `ScriptPageFetcher`。
- **`ScriptRuleSet.rule(kind, field)`**：字段规则来源（2a 已就位）；`nextTocUrl`/`nextContentUrl` 的 URL 数组形态（`irregularFields` 记账的数组字段）在 2d 展开。
- **`ScriptUrlOptions`**：请求选项已类型化；2d 若需把「不支持选项」写进导入报告，`ScriptUnsupported` 枚举按需扩员（本段拒绝即抛，不留静默）。

## 执行期修正记录（2026-09-09 执行完毕后登记）

执行全部走「实现者子代理 → 规格评审 → 质量评审 → 修正」循环，以下为对计划原文的偏离与评审驱动的修正（均已提交，hash 可查）：

1. **Task 3（评审修正 da9aa4b）**：JSONPath 种子对 Page/Texts 输入按 Miss 处理与 JSON 输入类型化拒绝的边界补锁形测试。
2. **Task 4（计划缺陷，实现者发现）**：`optionTailCut` 命中点取尾段时须以替换段边界为右界（`rule.substring(it, beforeReplacement)`），计划原文 `substring(it)` 会把替换文本卷进选项尾段，其自带的测试 5 无法通过。
3. **Task 4（与 2a 既有用例冲突）**：2a 已有用例「URL 选项尾段内的双与号不切」断言叶体含尾段；Task 4 落地后改为钉 root 取值段 + optionTail 两字段，原「不切」意图保持且更强。
4. **Task 4（评审修正 d63f756）**：`withOptionTail` 的 KDoc 原稿含不实不变量，按实际行为重写（尾段只附文本结果，JSON 结果直挂尾段被静默放过 → §11-19 登记）。
5. **Task 5（评审修正 a99e5c6）**：计划未覆盖 `timeout <= 0`——负数会在传输层以未类型化 IAE 崩、零会把语义翻成「永不超时」，按 charset 先例以 `RuleSyntaxException` 如实报 + 两条锁形测试（含 headers 数组形态拒绝）。
6. **Task 6（计划缺陷，实现者红灯发现）**：`splitTail` 产出的尾段**带前导定位逗号**，kotlinx 对 `,{...}` 即使 lenient 也按语法错误拒绝；`parseObject` 解析前 `removePrefix(",")`。
7. **Task 6（计划缺陷，评审发现，d0141cd）**：页码段取舍原计划在**回填后**判形——展开值里形如 `<...,...>` 的内容会被误当页码段吃掉；改为**占位符态**判形取舍、回填移到取舍之后（与尾段「占位符态定位、回填后解析」及 §5.1 同口径）。顺带补未闭合 `<` 与多页码段锁形测试。
8. **Task 7（工具链适配 2dcd265 + 注释修正 4253d8f）**：块体函数声明返回类型须显式 `return`（标准 Kotlin 语义），计划尾表达式写法编译不过；门面 KDoc 补「transport 优先于 okHttpClient」的构造优先级。**登记给 2d**：`OkHttpScriptTransport.execute` 未接协程取消（`call.cancel()`），取消后仍会跑满全部重试。
9. **Task 8（9a5c986 + 锁形 a7dab33）**：计划测试注释笔误 `{{}}/{}}`→`{{}}/{}`；补未闭合花括号从宽、顶层双冒号取首个、`[` 开头文本误判 JSON 返回空清单三条锁形测试；KDoc 写明与选项尾段扫描器「整段不配平从严」的口径分工。**登记给 2d 语料验证**：`[` 开头误判（修法：解析成 JsonArray 才采用、否则回落文本切分）与朴素引号判定（`\` 转义对不跳）。
10. **Task 9（评审决议 2294f81）**：包内四处同配置 `Json { ignoreUnknownKeys; isLenient }` 私有副本收敛为包级 `ScriptJson` 单一实例。
11. **计划用例数勘误**：Task 3 实为 24（计划写 23）、Task 6 实为 10（计划写 9）；终审补 §11-18 锁形 2 例后共 **290** 例，全绿。
