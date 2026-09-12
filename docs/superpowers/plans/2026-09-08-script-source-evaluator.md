# 脚本书源 HTML 侧规则求值器（Plan 2b）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) 语法跟踪进度。

**Goal:** 让 `com.ebook.source.script` 从「只会切规则串」变成「能对着真实 HTML 把规则解出值」：链式（默认）模式、`@css:`、正则 AllInOne 三个后端 + 组合符求值 + 取值器与索引消费 + `##` 替换应用 + `@put:`/`@get:` 变量与 `{{}}` 插值的声明式子集。

**Architecture:** 本文件是解释器四段的第二段（2a 词法已落地：`RuleSplitter` 产出 `RuleNode` 树、`ChainLink` 段结构、`IndexSelector` 索引、`RegexReplacement` 替换段、`ScriptRuleSet` 装载）。2b 只吃「HTML 文档 + 规则串」，产出 `RuleResult`，**不发任何网络请求、不碰 Room、不接任何页面**——URL 语义与 JSONPath 归 2c，`BookParser` 实现与翻页链/聚合搜索归 2d。事实源是 `docs/superpowers/specs/2026-09-08-script-source-rule-grammar.md`（下称「规格」，引用写作 §x.y），冲突时以规格为准并回改本文件。

**Tech Stack:** Kotlin、Jsoup（`lib_book_source` 已 `implementation(libs.jsoup)`）、JUnit 4。无新依赖。

**术语红线：** 全仓禁止出现该生态项目名字样，一律写「脚本书源 / 脚本书源格式」。自检命令一律用 `git grep -in "[l]egado"`（方括号形态，否则命令命中自身），恒须零命中。裸 `grep -r` 会被 `build/` 下第三方西语资源误命中，以 `git grep` 为准。

---

## 本段要钉死的规格条款

| # | 规格出处 | 钉成什么 | 落在 |
|---|---|---|---|
| 1 | §3.1 + §11-2 | 求值结果**一律保留全部匹配**，「取第一个还是全部串接」由字段级调用方决定（单值字段收敛口径写成 `RuleResult.firstText()` 一处），词法/求值两层都不私自收敛 | Task 1 |
| 2 | §2.2 | `%%` 交错取数、`\|\|` 短路、`&&` 合并的确切真值语义；空段=未取到值 | Task 2 |
| 3 | §3.3 | 语法错误抛 `RuleSyntaxException`，在 `\|\|` 处**按未取到值继续下一支**，但**不得静默返回上一支的结果**——全部支都失败时把最后一个错误抛出 | Task 2 |
| 4 | §2.5 + §3.2 | 链式段逐级收窄；取值器**只能在链尾**，出现在中间即语法错误（中间取值器后面没法再接选择器，静默放过会得到「永远选不中」的空结果） | Task 3 |
| 5 | §2.4 | AllInOne 的产物是**条目 × 捕获组**二维结构（`$1`/`$2` 逐字段引用），不能塌成字符串列表 | Task 4 |
| 6 | §2.4 | 净化=循环替换全部；OnlyOne=只替第一个；替换文本支持 `$n` 组引用 | Task 5 |
| 7 | §5.2 + §11 | `@put:`/`@get:` 的作用域 = **单次解析任务**（`EvalContext` 携带，任务结束即丢），跨请求存活一律不保证 | Task 6 |
| 8 | §5.1 | `{{}}` 只支持声明式子集（裸标识符 / `book.x` 属性路径 / `@@规则`）；**无标志即 JS** 的原文口径意味着任何表达式形态都抛 `JsEvaluationPendingException`，不许猜 | Task 6 |

## 与既有代码的接缝（先读这些再用）

2a 已落地并各有单测，本段**直接调用、不重实现**：

| 已有 | 签名 | 本段怎么用它 |
|---|---|---|
| `RuleSplitter.parse(rule)` | `(String) -> ParsedRule(root: RuleNode, replacement: RegexReplacement?, raw)` | 每次求值的入口；`replacement` 留到 Task 5 应用 |
| `RuleNode` | `Empty` / `Leaf(mode, body, reverse)` / `AllOf` / `FirstOf` / `Percent` | 求值遍历的就是它 |
| `RuleMode` | `DEFAULT_CHAIN`/`CSS`/`XPATH`/`JSON_PATH`/`REGEX_ALL_IN_ONE`/`VARIABLE_PUT`/`VARIABLE_GET`/`JS`/`UNSUPPORTED` | `Leaf.mode` 分发 |
| `ChainLink.parseChain(body)` | `(String) -> List<ChainLink>`，`ChainLink(kind: ChainKind, name, index: IndexSelector?)` | 链式后端逐级收窄 |
| `SelectorAndAccessor.split(body)` | `(String) -> Pair<String, String?>`（最后一个顶层 `@` 处切） | CSS 后端拿选择器 + 取值器 |
| `IndexSelector` | `parse(text)` + `companion` 内 `List<*>.select(sel)` | 节点裁剪；**Task 1 先把 select 提出来**（见下） |
| `RegexReplacement` | `(pattern, replacement, onlyFirst)` | Task 5 应用 |
| `ScriptRuleSet` / `RuleObjectKind` | `rule(kind, field): String?` | 本段不直接用（2d 用），但测试夹具沿用它的字段名 |
| `ScriptRuleExceptions` | `RuleSyntaxException` / `JsEvaluationPendingException` / `UnsupportedRuleFeatureException` 目前**零生产调用点** | 本段把它们接上：只有 `ScriptRuleParseException`（装载层）已经在抛 |

**已知缺陷必须在本段第一条处理**：`IndexSelector.select` 现在是 `companion object` 里的扩展函数，调用方必须写 `IndexSelector.run { list.select(sel) }`——词法层自己的测试就是这么绕的，求值层要在泛型上反复用，绕不动了。Task 1 把它提成文件级 `internal fun <T> List<T>.selectIndices(sel: IndexSelector): List<T>`。

## 包与文件布局

```
lib_book_source/src/main/java/com/ebook/source/script/
  RuleValue.kt          [新建] 求值输入（Page / Nodes / Texts）
  RuleResult.kt         [新建] 求值输出（Miss / Nodes / Texts / Matches）+ 取值器映射
  ScriptRuleEvaluator.kt [新建] 入口 + 组合符求值 + 模式分发
  ElementBackends.kt    [新建] 链式与 @css: 两个后端（共用选择与收窄逻辑）
  RegexBackend.kt       [新建] 正则 AllInOne + $n 组引用
  ReplacementApplier.kt [新建] ## 替换段应用（净化 / OnlyOne）
  EvalContext.kt        [新建] 单次解析任务的变量与内置量
  Interpolation.kt      [新建] {{}} 声明式子集展开 + @put:/@get:
  IndexSelector.kt      [改] select 提为文件级扩展
lib_book_source/src/test/java/com/ebook/source/script/
  RuleResultTest.kt  ScriptRuleEvaluatorCombinatorTest.kt  ElementBackendsTest.kt
  RegexBackendTest.kt  ReplacementApplierTest.kt  InterpolationTest.kt  EvalContextTest.kt
  IndexSelectorTest.kt [改] 用新扩展名，断言不变
```

一个后端一个文件、求值骨架一个文件，是为了让「切分错了」和「求值错了」在故障时能各自定位——2a 的整套理由在此延续。所有类型保持 `internal`：求值层与测试同在 `:lib_book_source`（Plan 1 的教训：`internal` 对依赖方的测试源集不可见）。

---

### Task 1: 输入/输出模型，与 `IndexSelector.select` 提取

**Files:**
- Create: `lib_book_source/src/main/java/com/ebook/source/script/RuleValue.kt`
- Create: `lib_book_source/src/main/java/com/ebook/source/script/RuleResult.kt`
- Modify: `lib_book_source/src/main/java/com/ebook/source/script/IndexSelector.kt`（`select` 提为文件级扩展）
- Modify: `lib_book_source/src/test/java/com/ebook/source/script/IndexSelectorTest.kt`（改用新扩展名）
- Test: `lib_book_source/src/test/java/com/ebook/source/script/RuleResultTest.kt`

- [x] **Step 1: 写失败的测试**

```kotlin
package com.ebook.source.script

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 结果模型的三条口径都来自规格的「本仓规定」，必须在这里而不是在各后端里被钉住：
 * 多匹配不私自收敛（§3.1/§11-2）、未取到值是独立事实（§3.3）、
 * 节点到文本的映射按取值器（§3.2）。
 */
class RuleResultTest {

    private val html = """
        <html><body>
          <div class="bookbox"><p>第一章</p><p>第二章</p></div>
          <div class="bookbox"><p>第三章</p></div>
        </body></html>
    """.trimIndent()

    private fun doc() = Jsoup.parse(html)

    @Test
    fun `Miss 是独立事实而不是空列表`() {
        // 折叠成空列表会让「没取到」与「取到零条」同形，|| 的短路随之失效
        assertEquals(true, RuleResult.Miss is RuleResult)
    }

    @Test
    fun `节点集按 text 取值器映射成文本`() {
        val nodes = doc().getElementsByClass("bookbox")
        val texts = RuleResult.Nodes(nodes.toList()).mapToTexts(AccessorKind.TEXT)
        assertEquals(listOf("第一章 第二章", "第三章"), texts.values)
    }

    @Test
    fun `节点集按 textNodes 取值器逐子节点取值`() {
        val nodes = doc().getElementsByClass("bookbox")
        val texts = RuleResult.Nodes(nodes.toList()).mapToTexts(AccessorKind.TEXT_NODES)
        assertEquals(listOf("第一章", "第二章", "第三章"), texts.values)
    }

    @Test
    fun `节点集取属性时缺失的属性跳过`() {
        val nodes = doc().getElementsByTag("p")
        val texts = RuleResult.Nodes(nodes.toList()).mapToTexts(AccessorKind.ATTRIBUTE, "data-x")
        assertEquals(emptyList<String>(), texts.values)
    }

    @Test
    fun `单值字段收敛为第一个值`() {
        val texts = RuleResult.Texts(listOf("甲", "乙"))
        assertEquals("甲", texts.firstText())
        assertEquals("", RuleResult.Miss.firstText())
    }

    @Test
    fun `索引扩展可在任意列表上调用`() {
        // select 从 companion 扩展提成文件级扩展后，调用方不再需要 IndexSelector.run {}
        val sel = requireNotNull(IndexSelector.parse("[1:2]"))
        assertEquals(listOf("b", "c"), listOf("a", "b", "c", "d").selectIndices(sel))
    }
}
```

- [x] **Step 2: 运行确认失败**

Run: `./gradlew :lib_book_source:compileDebugUnitTestKotlin`
Expected: FAIL——`RuleResult` / `selectIndices` 未定义。

- [x] **Step 3: 实现 `RuleValue.kt`**

```kotlin
package com.ebook.source.script

import org.jsoup.nodes.Element

/**
 * 一次规则求值的**输入**（规格 §9 第 5 步「每支的输入 = 当前上下文文档/文本」）。
 *
 * 三种形态对应格式里的三类上下文，刻意不做互相隐式转换：从页面起步与从节点集起步
 * 的语义不同（前者要先 `Jsoup.parse`，后者已经在列表内），隐式转换会让「列表字段」
 * 与「字段内的子规则」两条路径混成一条，而那两条在 §1.2 里是明确分层的。
 */
internal sealed interface RuleValue {

    /** 整页源码。AllInOne 正则与「从页面起步」的链式/CSS 规则用它 */
    data class Page(val source: String, val baseUrl: String = "") : RuleValue

    /** 当前节点集。列表字段解出的每一条子规则以此为输入 */
    data class Nodes(val elements: List<Element>) : RuleValue

    /** 当前文本集（如上一步已取到的字段值） */
    data class Texts(val values: List<String>) : RuleValue

    companion object {

        /** 空节点集——「有上下文但没有节点」与「没有上下文」不是一回事，故单独给 */
        fun emptyNodes(): Nodes = Nodes(emptyList())
    }
}
```

- [x] **Step 4: 实现 `RuleResult.kt`**

```kotlin
package com.ebook.source.script

import org.jsoup.nodes.Element

/**
 * 一次规则求值的**输出**（规格 §3.1~§3.3）。
 *
 * [Miss] 独立存在：把「未取到值」折叠成空列表，`||` 就再也分不清「这一支没值」与
 * 「这一支取到了零个条目」，短路语义整体失效（§2.3 空段行为的依据）。
 *
 * 列表型结果（[Nodes] / [Texts] / [Matches]）**一律保留全部匹配**，本层不替调用方
 * 收敛——规格 §3.1 说「不加位置会获取所有」，却没说单值字段怎么变成一个串，
 * 这条未知项（§11-2）因此留在字段级调用方（2d）用 [firstText] 一处收口，
 * 而不是散落在每个后端里各猜一次。
 */
internal sealed interface RuleResult {

    object Miss : RuleResult

    data class Nodes(val elements: List<Element>) : RuleResult

    data class Texts(val values: List<String>) : RuleResult

    /**
     * 正则 AllInOne 的产物：每个条目是**捕获组列表**（下标 0 是整段匹配），
     * 逐字段规则用 `$1`/`$2` 引用（§2.4）。塌成字符串列表就会丢掉「一条目多字段」
     * 的结构，`chapterName: "$2"` 这类写法将无从落地。
     */
    data class Matches(val items: List<List<String>>) : RuleResult
}

/** 单值字段的收敛口径（规格 §11-2 本仓规定）：取第一个，没有则空串。只允许在这一处收口 */
internal fun RuleResult.firstText(): String = when (this) {
    RuleResult.Miss -> ""
    is RuleResult.Texts -> values.firstOrNull().orEmpty()
    is RuleResult.Nodes -> elements.firstOrNull()?.text().orEmpty()
    is RuleResult.Matches -> items.firstOrNull()?.firstOrNull().orEmpty()
}

/**
 * 节点集 → 文本集（规格 §3.2 取值器）。
 *
 * [attribute] 仅在 [accessor] 为 [AccessorKind.ATTRIBUTE] 时给出属性名。
 * 属性缺失的元素**跳过**而不是补空串：语料里 `@_src` 这类延迟加载属性只在部分标签上存在，
 * 补空串会让「这个元素没有该属性」变成一个看起来合法的候选值。
 */
internal fun RuleResult.Nodes.mapToTexts(accessor: AccessorKind, attribute: String = ""): RuleResult.Texts =
    RuleResult.Texts(
        when (accessor) {
            AccessorKind.TEXT -> elements.map { it.text() }
            AccessorKind.OWN_TEXT -> elements.map { it.ownText() }
            AccessorKind.TEXT_NODES -> elements.flatMap { e -> e.textNodes().map { it.getWholeText().trim() }.filter { it.isNotEmpty() } }
            AccessorKind.HTML -> elements.map { it.html() }
            // all = 含自身标签的整个元素外形态（§3.2「整个元素（含自身标签）」）
            AccessorKind.ALL -> elements.map { it.outerHtml() }
            AccessorKind.HREF -> elements.attrNonNull("href")
            AccessorKind.SRC -> elements.attrNonNull("src")
            AccessorKind.ATTRIBUTE -> elements.attrNonNull(attribute)
        }
    )

/** 取值器 → 属性名（href/src 与任意属性名都要走这条路） */
internal fun RuleResult.Nodes.attrNonNull(name: String): List<String> =
    elements.mapNotNull { it.attr(name).ifBlank { null } }
```

- [x] **Step 5: 把 `select` 提成文件级扩展**

`IndexSelector.kt` 里 `companion object` 内的

```kotlin
        fun List<*>.select(sel: IndexSelector): List<*> = when (sel) { ... }
```

改为**文件级** internal 扩展（其余两个 `private` 辅助 `resolve` / `slice` 留在 companion 内或一并提为文件级 private，保持可见性一致）：

```kotlin
/**
 * 按规格 §4 + 本仓规定裁剪：**逐项过滤、端点按集合边界裁剪、越界的单个索引跳过、
 * 全部被过滤即返回空列表**。任何情况都不抛——越界在语料里是常态（`[2:999]`），
 * 抛出去会让一条写歪了下标的规则毁掉整本书的目录。
 *
 * 之所以是文件级扩展而不是 `IndexSelector` 的 companion 扩展：求值层要在
 * `List<Element>`、`List<String>`、`List<JsonElement>` 三种列表上反复调用，
 * companion 版本要求调用方套一层 `IndexSelector.run { ... }`，那层作用域技巧
 * 对读者是纯噪音。
 */
internal fun <T> List<T>.selectIndices(sel: IndexSelector): List<T> = when (sel) {
    IndexSelector.All -> this
    is IndexSelector.At -> sel.indexes.mapNotNull { resolveIndex(it, size) }.map { this[it] }
    is IndexSelector.Excluding -> {
        val drop = sel.indexes.mapNotNull { resolveIndex(it, size) }.toSet()
        filterIndexed { i, _ -> i !in drop }
    }
    is IndexSelector.Slice -> sliceIndices(sel.start, sel.end, sel.step, size).map { this[it] }
}
```

并把 `resolve`/`slice` 改写成文件级 `private fun resolveIndex(i: Int, n: Int): Int?` 与 `private fun sliceIndices(start: Int?, end: Int?, step: Int?, n: Int): List<Int>`（逻辑逐字照搬现有实现：闭区间、端点 `coerceAtMost(n-1)`/`coerceAtLeast(0)`、省略 `end` 取到末尾、`a > b` 反向遍历、step 取绝对值且 0 视作 1、n 为 0 返回空）。

`IndexSelectorTest` 里所有 `IndexSelector.run { xxx.select(sel) }` 改成 `xxx.selectIndices(sel)`，**断言与用例数一条不改**（21 例）。KDoc 里指向 `[select]` 的句子同步改成 `[selectIndices]`。

- [x] **Step 6: 运行测试通过**

Run: `./gradlew :lib_book_source:testDebugUnitTest --tests "com.ebook.source.script.RuleResultTest" --tests "com.ebook.source.script.IndexSelectorTest"`
Expected: PASS（6 + 21）。

- [x] **Step 7: 全模块回归 + 红线 + 提交**

Run: `./gradlew :lib_book_source:testDebugUnitTest` → 全绿（134 + 6 = 140）。

```bash
git grep -in "[l]egado" || echo CLEAN
git add lib_book_source/src/main/java/com/ebook/source/script/RuleValue.kt lib_book_source/src/main/java/com/ebook/source/script/RuleResult.kt lib_book_source/src/main/java/com/ebook/source/script/IndexSelector.kt lib_book_source/src/test/java/com/ebook/source/script/
git commit -m "$(cat <<'EOF'
feat(lib_book_source): 脚本书源求值的输入输出模型

Miss 独立于空列表：折叠掉它 || 就分不清「这一支没值」和「取到零条」，短路语义整体失效。
多匹配一律保留、由字段级调用方一处收敛：规格没说单值字段怎么变成串，各后端各猜一次
就会长出五种口径。AllInOne 结果保留条目×捕获组二维结构，否则 chapterName: "$2" 无从落地。
IndexSelector 的裁剪提成文件级扩展：companion 版要求调用方套一层 run，对读者是纯噪音。
EOF
)"
```

---

### Task 2: 求值骨架与组合符

**Files:**
- Create: `lib_book_source/src/main/java/com/ebook/source/script/ScriptRuleEvaluator.kt`
- Test: `lib_book_source/src/test/java/com/ebook/source/script/ScriptRuleEvaluatorCombinatorTest.kt`

- [x] **Step 1: 写失败的测试**

```kotlin
package com.ebook.source.script

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 组合符求值（规格 §2.2 真值语义、§3.3 错误传播）。后端用链式与 CSS 两模式即可覆盖合并/短路/交错。 */
class ScriptRuleEvaluatorCombinatorTest {

    private val html = """
        <html><body>
          <div class="odd"><a href="/b1">书一</a></div>
          <div class="odd"><a href="/b2">书二</a></div>
          <dd><h1>备选标题</h1></dd>
        </body></html>
    """.trimIndent()

    private fun page() = RuleValue.Page(html)
    private fun eval(rule: String): RuleResult = ScriptRuleEvaluator(EvalContext()).evaluate(rule, page())

    @Test
    fun `链式取到节点集`() {
        val r = eval("class.odd@tag.a@text")
        assertEquals(RuleResult.Texts(listOf("书一", "书二")), r)
    }

    @Test
    fun `css 取到节点集`() {
        assertEquals(RuleResult.Texts(listOf("书一", "书二")), eval("@css:.odd tag.a@text"))
    }

    @Test
    fun `未取到值是 Miss`() {
        assertEquals(RuleResult.Miss, eval("class.notexist@text"))
    }

    @Test
    fun `竖线取第一个有值的支`() {
        assertEquals(RuleResult.Texts(listOf("备选标题")), eval("class.notexist@text||tag.dd@tag.h1@text"))
    }

    @Test
    fun `竖线第一支有值则不算第二支`() {
        // 用 @cache: 这种必然抛异常的支做证据：它若被求值就会抛，测试就会红
        val r = eval("class.odd@tag.a@text||@cache:1")
        assertEquals(RuleResult.Texts(listOf("书一", "书二")), r)
    }

    @Test
    fun `竖线全部支都没值才是 Miss`() {
        assertEquals(RuleResult.Miss, eval("class.no1@text||class.no2@text"))
    }

    @Test
    fun `双与号合并各支取到的值`() {
        assertEquals(
            RuleResult.Texts(listOf("书一", "书二", "备选标题")),
            eval("class.odd@tag.a@text&&tag.dd@tag.h1@text"),
        )
    }

    @Test
    fun `双与号跳过空支而不是整体失败`() {
        assertEquals(
            RuleResult.Texts(listOf("书一", "书二")),
            eval("class.odd@tag.a@text&&class.none@text"),
        )
    }

    @Test
    fun `百分号按序交错取数`() {
        // §2.2：三路时先各取第 1 个，再各取第 2 个……
        assertEquals(
            RuleResult.Texts(listOf("书一", "备选标题", "书二")),
            eval("class.odd@tag.a@text%%tag.dd@tag.h1@text"),
        )
    }

    @Test
    fun `百分号遇长度不等时短的走完后不再补位`() {
        val h2 = """<html><body><i>A</i><i>B</i><i>C</i><em>X</em></body></html>"""
        val r = ScriptRuleEvaluator(EvalContext())
            .evaluate("tag.i@text%%tag.em@text", RuleValue.Page(h2))
        assertEquals(RuleResult.Texts(listOf("A", "X", "B", "C")), r)
    }

    @Test
    fun `空规则串是 Miss 不是异常`() {
        assertEquals(RuleResult.Miss, eval("   "))
    }

    @Test
    fun `链式取值器出现在链中间判语法错误`() {
        // §2.5「最后一段是取值器」+ 本段口径：中间取值器后面没法再接选择器，
        // 静默放过只会得到永远选不中的空结果
        val e = runCatching { eval("tag.a@text@tag.b@text") }.exceptionOrNull()
        assertTrue("实际：${e?.let { it::class.simpleName }}", e is RuleSyntaxException)
    }

    @Test
    fun `js 支抛待执行异常且消息含脚本书源字样`() {
        val e = runCatching { eval("@js:result") }.exceptionOrNull()
        assertTrue("实际：${e?.let { it::class.simpleName }}", e is JsEvaluationPendingException)
        assertTrue(e!!.message!!.contains("脚本"))
    }

    @Test
    fun `XPath 支抛不支持异常`() {
        assertTrue(runCatching { eval("//div/@text()") }.exceptionOrNull() is UnsupportedRuleFeatureException)
    }

    @Test
    fun `语法错误被竖线当作未取到值继续下一支`() {
        // §3.3：抛类型化语法错误，可被 || 当成未取到值继续；不得静默返回上一支结果
        val r = eval("tag.a@text@tag.b@text||class.odd@tag.a@text")
        assertEquals(RuleResult.Texts(listOf("书一", "书二")), r)
    }

    @Test
    fun `全部支都语法错误时把错误抛出而不是返回 Miss`() {
        val e = runCatching { eval("tag.a@text@tag.b@text||tag.c@text@tag.d@text") }.exceptionOrNull()
        assertTrue("实际：${e?.let { it::class.simpleName }}", e is RuleSyntaxException)
    }
}
```


- [x] **Step 2: 运行确认失败**

Run: `./gradlew :lib_book_source:compileDebugUnitTestKotlin`
Expected: FAIL——`ScriptRuleEvaluator` / `EvalContext` 未定义。

- [x] **Step 3: 实现 `EvalContext.kt`（本任务只需要它存在，插值在 Task 6 用）**

```kotlin
package com.ebook.source.script

/**
 * 一次解析任务的可变上下文（规格 §5.2 的「本仓规定」）。
 *
 * 作用域刻意是**单次解析任务**（一本书的一轮求值）：公开文档没写清 `@put:`/`java.put`
 * 存的是局部变量还是书源级持久变量（§5.2、§11），按任务级实现最保守——
 * 真依赖跨请求存活的源会在真实运行里取到空，而这条已经在导入报告的能力清单里。
 * 已核实的持久变量是另外三套 API（书源级 / book / chapter 与 `cache`），**不要混用**。
 *
 * [key] 与 [page] 只在搜索/发现的 URL 上有意义（§5.3 限定），这里给出默认值即可，
 * 真正的页码换算与 URL 选项在 2c；[variables] 用 LinkedHashMap 保序，
 * 让 `@put:` 多条写入的顺序在错误日志里可读。
 */
internal class EvalContext(
    val baseUrl: String = "",
    var key: String = "",
    var page: Int = 1,
) {
    val variables: MutableMap<String, String> = linkedMapOf()

    /** 内置量表（§5.3）：只放声明式子集认得的这几个，其余一律按 JS 待执行处理 */
    fun builtin(name: String): String? = when (name) {
        "key" -> key
        "page" -> page.toString()
        "baseUrl" -> baseUrl
        else -> null
    }
}
```

- [x] **Step 4: 实现 `ScriptRuleEvaluator.kt`**

```kotlin
package com.ebook.source.script

/**
 * 规则求值入口：把 2a 的切分树对着 HTML 文档跑出值（规格 §9 的 1~7 步）。
 *
 * 本类只负责**分发与组合**，各模式的取数在各自后端文件里。分工的理由与 2a 一致：
 * 「切分错了」与「求值错了」必须能在故障时各自定位。
 *
 * 尚未落地的能力一律抛类型化异常而不是返回空——空列表在消费链上的语义是
 * 「这个源没有结果」，用它冒充「本项目还不支持这种语法」就是规格 §12 最后一条禁止的事。
 *
 * 替换段（`##`）在 Task 5 才接上，本文件先留 [applyReplacement] 的透传实现。
 */
internal class ScriptRuleEvaluator(private val ctx: EvalContext) {

    fun evaluate(rule: String, input: RuleValue): RuleResult {
        val parsed = RuleSplitter.parse(rule)
        val raw = evaluateNode(parsed.root, input)
        return applyReplacement(raw, parsed.replacement)
    }

    private fun evaluateNode(node: RuleNode, input: RuleValue): RuleResult = when (node) {
        RuleNode.Empty -> RuleResult.Miss
        is RuleNode.Leaf -> evaluateLeaf(node, input)
        is RuleNode.AllOf -> mergeAllOf(node.parts, input)
        is RuleNode.Percent -> interleave(node.streams, input)
        is RuleNode.FirstOf -> firstOf(node.alternatives, input)
    }

    /**
     * §2.2 `||`：以第一个取到值的分支为准（短路）。
     *
     * 语法错误按「未取到值」处理并继续下一支（§3.3），但**全部支都失败时把最后那个错误抛出**：
     * 只返回 Miss 会把「规则写错了」冒充成「这个源没有这条信息」，用户会被支去重导一条本来好的源。
     */
    private fun firstOf(alternatives: List<RuleNode>, input: RuleValue): RuleResult {
        var lastError: RuleSyntaxException? = null
        for (alt in alternatives) {
            val r = try {
                evaluateNode(alt, input)
            } catch (e: RuleSyntaxException) {
                lastError = e
                continue
            }
            if (r !is RuleResult.Miss) return r
        }
        lastError?.let { throw it }
        return RuleResult.Miss
    }

    /** §2.2 `&&`：每支都求值后合并。空支跳过而不是带走整条。 */
    private fun mergeAllOf(parts: List<RuleNode>, input: RuleValue): RuleResult {
        val results = parts.map { evaluateNode(it, input) }.filterNot { it is RuleResult.Miss }
        return when {
            results.isEmpty() -> RuleResult.Miss
            results.size == 1 -> results.first()
            // 节点与文本混着合并时统一成文本：`&&` 的典型用法是把两个字段值拼成一个串，
            // 而拼成节点集没有任何后续语义（再往下取什么？）
            results.all { it is RuleResult.Nodes } ->
                RuleResult.Nodes(results.flatMap { (it as RuleResult.Nodes).elements })
            results.all { it is RuleResult.Matches } ->
                RuleResult.Matches(results.flatMap { (it as RuleResult.Matches).items })
            else -> RuleResult.Texts(results.flatMap { it.toTextList() })
        }
    }

    /**
     * §2.2 `%%`：依次交错取数——三路时先取路 1 的第 1 个、路 2 的第 1 个、路 3 的第 1 个，
     * 再取路 1 的第 2 个……短的走完就不再补位。
     */
    private fun interleave(streams: List<RuleNode>, input: RuleValue): RuleResult {
        val results = streams.map { evaluateNode(it, input) }.filterNot { it is RuleResult.Miss }
        if (results.isEmpty()) return RuleResult.Miss
        if (results.size == 1) return results.first()
        if (results.all { it is RuleResult.Nodes }) {
            val lists = results.map { (it as RuleResult.Nodes).elements }
            return RuleResult.Nodes(interleaved(lists))
        }
        val texts = results.map { it.toTextList() }
        return RuleResult.Texts(interleaved(texts))
    }

    /** 按「轮次」把 N 路列表交错成一个：`[[a1,a2,a3],[b1]]` → `[a1,b1,a2,a3]` */
    private fun <T> interleaved(lists: List<List<T>>): List<T> {
        val out = ArrayList<T>(lists.sumOf { it.size })
        val max = lists.maxOf { it.size }
        for (i in 0 until max) {
            for (l in lists) if (i < l.size) out += l[i]
        }
        return out
    }

    private fun RuleResult.toTextList(): List<String> = when (this) {
        RuleResult.Miss -> emptyList()
        is RuleResult.Texts -> values
        is RuleResult.Nodes -> elements.map { it.text() }
        is RuleResult.Matches -> items.mapNotNull { it.firstOrNull() }
    }

    private fun evaluateLeaf(node: RuleNode.Leaf, input: RuleValue): RuleResult = when (node.mode) {
        RuleMode.JS -> throw JsEvaluationPendingException(node.body)
        RuleMode.XPATH -> throw UnsupportedRuleFeatureException("XPath", node.body)
        RuleMode.UNSUPPORTED -> throw UnsupportedRuleFeatureException("未知的规则标志", node.body)
        RuleMode.JSON_PATH -> throw UnsupportedRuleFeatureException("JSONPath", node.body)
        RuleMode.REGEX_ALL_IN_ONE -> RegexBackend.evaluate(node.body, input, node.reverse)
        RuleMode.CSS -> ElementBackends.evaluateCss(node.body, input, node.reverse)
        RuleMode.DEFAULT_CHAIN -> ElementBackends.evaluateChain(node.body, input, node.reverse)
        // 变量存取在 Task 6 接入（它依赖展开/回填这条链路，见该任务的 Step 4）。
        // 本段先明确抛不支持：抛出来是「还没做」，返回 Miss 会被下游当成「这条源没有这个变量」。
        RuleMode.VARIABLE_PUT, RuleMode.VARIABLE_GET ->
            throw UnsupportedRuleFeatureException("变量存取", node.body)
    }

    /** Task 5 落地前原样返回 */
    private fun applyReplacement(raw: RuleResult, replacement: RegexReplacement?): RuleResult = raw
}
```

本任务还需**最小可用的链式与 CSS 后端**才能让上面 17 例中的大部分绿起来——后端完整实现就是 Task 3，因此**本步先创建 `ElementBackends.kt`、`RegexBackend.kt` 两个桩文件**，内容只有编译得过的最小实现（`RegexBackend.evaluate` 抛 `UnsupportedRuleFeatureException("正则 AllInOne", body)`；`ElementBackends` 两个函数抛同名异常），Task 2 的测试里**只保留** `未取到值是 Miss` 之外的组合符用例所需的 `evaluateChain`/`evaluateCss` 真实现，或（推荐）把本任务的组合符用例推迟到 Task 3 之后一起跑绿：

**采用推荐做法**：Task 2 只提交「骨架 + 桩 + 模型」，测试文件里那些依赖真实取数的用例**在本步允许失败**（Step 5 的运行预期即「编译通过、取数相关用例失败」），Task 3 把后端补齐后**连同本文件用例一起跑绿**。桩文件必须写明「Task 3 替换」，避免留下静默的假实现。

- [x] **Step 5: 编译通过并确认失败模式正确**

Run: `./gradlew :lib_book_source:testDebugUnitTest --tests "com.ebook.source.script.ScriptRuleEvaluatorCombinatorTest"`
Expected: 编译通过；`空规则串是 Miss 不是异常`、`js 支抛待执行异常且消息含脚本书源字样`、`XPath 支抛不支持异常` 等**不依赖后端取数**的用例已绿，取数类用例失败（桩抛异常）。这一步的目的是确认失败都来自桩、而不是骨架写错。

- [x] **Step 6: 提交（骨架）**

```bash
git add lib_book_source/src/main/java/com/ebook/source/script lib_book_source/src/test/java/com/ebook/source/script/ScriptRuleEvaluatorCombinatorTest.kt
git commit -m "$(cat <<'EOF'
feat(lib_book_source): 脚本书源求值骨架与组合符求值

|| 把语法错误当未取到值继续下一支，但全支皆错时仍把错误抛出：
只返回 Miss 等于用「这个源没这条信息」冒充「规则写错了」。
%% 与 && 的取数语义各自独立，交错按轮次而非按长度补齐。
EOF
)"
```

---

### Task 3: 链式与 `@css:` 后端

**Files:**
- Modify: `lib_book_source/src/main/java/com/ebook/source/script/ElementBackends.kt`（把 Task 2 的桩替成真实现）
- Test: `lib_book_source/src/test/java/com/ebook/source/script/ElementBackendsTest.kt`
- Test: `ScriptRuleEvaluatorCombinatorTest.kt` 此时必须全绿

- [x] **Step 1: 写失败的测试**

```kotlin
package com.ebook.source.script

import org.junit.Assert.assertEquals
import org.junit.Test

/** 规格 §2.5 链式段、§3.2 取值器、§4 索引、§2.5 列表反序在真实 HTML 上的行为。 */
class ElementBackendsTest {

    private val html = """
        <html><body>
          <div id="info">
            <span class="name">书名A</span>
            <span class="name">书名B</span>
            <a href="/x/1.html" data-x="7">第一章</a>
            <a href="/x/2.html">第二章</a>
            <p>正文一</p><p>正文二</p><p>正文三</p>
          </div>
        </body></html>
    """.trimIndent()

    private fun eval(rule: String): RuleResult =
        ScriptRuleEvaluator(EvalContext()).evaluate(rule, RuleValue.Page(html))

    @Test
    fun `class 取全部匹配`() {
        assertEquals(RuleResult.Texts(listOf("书名A", "书名B")), eval("class.name@text"))
    }

    @Test
    fun `位置段收窄到一个`() {
        assertEquals(RuleResult.Texts(listOf("书名B")), eval("class.name.1@text"))
    }

    @Test
    fun `负位置从尾数`() {
        assertEquals(RuleResult.Texts(listOf("书名B")), eval("class.name.-1@text"))
    }

    @Test
    fun `id 取元素`() {
        assertEquals(2, (eval("id.info@tag.a") as RuleResult.Nodes).elements.size)
    }

    @Test
    fun `方括号区间索引`() {
        assertEquals(
            RuleResult.Texts(listOf("正文一", "正文二")),
            eval("tag.p[0:1]@text"),
        )
    }

    @Test
    fun `排除式索引`() {
        assertEquals(RuleResult.Texts(listOf("正文二")), eval("tag.p[!0,2]@text"))
    }

    @Test
    fun `children 取直接子节点`() {
        assertEquals(
            RuleResult.Texts(listOf("书名B")),
            eval("id.info@children[1]@text"),
        )
    }

    @Test
    fun `text 段按文本内容定位`() {
        // §2.5「text 按文本内容定位」：名称是文本的一部分；末段无取值器故返回节点集
        val r = eval("text.第一章") as RuleResult.Nodes
        assertEquals(1, r.elements.size)
        assertEquals("第一章", r.elements.single().text())
    }

    @Test
    fun `href 与 src 取属性`() {
        assertEquals(RuleResult.Texts(listOf("/x/1.html", "/x/2.html")), eval("tag.a@href"))
    }

    @Test
    fun `任意属性名取属性且缺该属性的元素被跳过`() {
        assertEquals(RuleResult.Texts(listOf("7")), eval("tag.a@data-x"))
    }

    @Test
    fun `html 取内部 HTML`() {
        val r = eval("class.name@html") as RuleResult.Texts
        assertEquals(listOf("书名A", "书名B"), r.values)
    }

    @Test
    fun `ownText 排除子元素文本`() {
        assertEquals(
            RuleResult.Texts(listOf("正文一", "正文二", "正文三")),
            eval("tag.p@ownText"),
        )
    }

    @Test
    fun `all 取含自身标签的外形态`() {
        val r = eval("class.name@all") as RuleResult.Texts
        assertEquals(listOf("<span class=\"name\">书名A</span>", "<span class=\"name\">书名B</span>"), r.values)
    }

    @Test
    fun `反序前缀让列表倒过来`() {
        // 2a 的 RuleMode 只在 `-` 之后紧跟**已知标志**时才剥反序号（语料实证是 `-:`），
        // 所以这里写显式的 `@@`。「`-class.name@text` 这种无标志链式算不算反序」规格没有答案，
        // 留作 §11 未知项：要扩就在 RuleMode 一处扩，并同步补一条用例。
        assertEquals(
            RuleResult.Texts(listOf("书名B", "书名A")),
            eval("-@@class.name@text"),
        )
    }

    @Test
    fun `css 选择器与尾随取值器`() {
        assertEquals(RuleResult.Texts(listOf("书名A", "书名B")), eval("@css:#info .name@text"))
    }

    @Test
    fun `css 属性选择器整体交给 Jsoup`() {
        assertEquals(RuleResult.Texts(listOf("/x/1.html")), eval("@css:a[data-x=\"7\"]@href"))
    }

    @Test
    fun `从节点集起步时在其子树内继续选`() {
        val nodes = (eval("class.name") as RuleResult.Nodes)
        val r = ScriptRuleEvaluator(EvalContext()).evaluate(
            "tag.span@text",
            RuleValue.Nodes(nodes.elements),
        )
        assertEquals(RuleResult.Texts(listOf("书名A", "书名B")), r)
    }

    @Test
    fun `末段没有取值器时返回节点集`() {
        assertEquals(RuleResult.Nodes(eval("class.name").let { (it as RuleResult.Nodes).elements }), eval("class.name"))
    }

    @Test
    fun `多级收窄`() {
        assertEquals(RuleResult.Texts(listOf("第一章")), eval("id.info@tag.a.0@text"))
    }
}
```

Expected: FAIL——Task 2 的桩抛 `UnsupportedRuleFeatureException`。

- [x] **Step 3: 实现 `ElementBackends.kt`**

```kotlin
package com.ebook.source.script

import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/**
 * 默认（链式）模式与 `@css:` 模式两个后端（规格 §2.5、§3.2）。
 *
 * 两个模式共用「在当前节点集上选下一批 + 按索引裁剪 + 末段取值器出文本」这套骨架，
 * 差别只在一步选择器怎么写，因此放在同一文件——分开写会让裁剪与取值器映射长出两份。
 */
internal object ElementBackends {

    /**
     * 链式：`@` 分段，逐段收窄当前节点集。
     *
     * 取值器只允许出现在链尾（§2.5「最后一段是取值器」）。中间出现取值器一律语法错误：
     * 取值器已经把节点变成文本，后面的段没法再作用在它上面，
     * 静默放过得到的是「永远选不中」的空结果——正是本层要防的那类零报错故障。
     */
    fun evaluateChain(body: String, input: RuleValue, reverse: Boolean): RuleResult {
        val links = ChainLink.parseChain(body)
        if (links.isEmpty()) return RuleResult.Miss
        links.dropLast(1).forEach {
            if (it.kind == ChainKind.ACCESSOR) throw RuleSyntaxException(body)
        }
        var current = seed(input)
        for (link in links.dropLast(if (isAccessorTail(links)) 1 else 0)) {
            current = apply(link, current)
            if (current.isEmpty()) return RuleResult.Miss
        }
        if (reverse) current = current.reversed()
        val last = links.last()
        return when {
            isAccessorTail(links) ->
                RuleResult.Nodes(current).mapToTexts(AccessorKind.of(last.name), last.name)
            last.kind == ChainKind.ACCESSOR ->
                RuleResult.Nodes(current).mapToTexts(AccessorKind.of(last.name), last.name)
            else -> RuleResult.Nodes(apply(last, current))
        }
    }

    /** `@css:`：整段选择器交给 Jsoup，尾随取值器决定怎么出值（§2.1/§3.2） */
    fun evaluateCss(body: String, input: RuleValue, reverse: Boolean): RuleResult {
        val (selector, accessorToken) = SelectorAndAccessor.split(body)
        if (selector.isBlank()) return RuleResult.Miss
        var current = seed(input).flatMap { runCatching { it.select(selector) }.getOrDefault(emptyList()) }
        if (current.isEmpty()) return RuleResult.Miss
        if (reverse) current = current.reversed()
        val nodes = RuleResult.Nodes(current)
        return if (accessorToken == null) nodes
        else nodes.mapToTexts(AccessorKind.of(accessorToken), accessorToken)
    }

    private fun isAccessorTail(links: List<ChainLink>): Boolean =
        links.last().kind == ChainKind.ACCESSOR

    /** 起步节点集：页面→文档根；节点集→原样；文本集→按页面解析（§9 第 5 步） */
    private fun seed(input: RuleValue): List<Element> = when (input) {
        is RuleValue.Page -> listOf(Jsoup.parse(input.source))
        is RuleValue.Nodes -> input.elements
        is RuleValue.Texts -> listOf(Jsoup.parse(input.values.firstOrNull().orEmpty()))
    }

    private fun apply(link: ChainLink, from: List<Element>): List<Element> {
        val picked = when (link.kind) {
            ChainKind.CLASS -> from.flatMap { it.getElementsByClass(link.name) }
            ChainKind.ID -> from.mapNotNull { it.getElementById(link.name) }
            ChainKind.TAG -> from.flatMap { it.getElementsByTag(link.name) }
            // §2.5「text 按文本内容定位」：名称是文本的一部分，不是全等
            ChainKind.TEXT -> from.flatMap { root -> root.getAllElements().filter { it != root && it.text().contains(link.name) } }
            ChainKind.CHILDREN -> from.flatMap { it.children().toList() }
            ChainKind.ATTRIBUTE -> from.flatMap { it.getAllElements() }.filter { it.hasAttr(link.name) }
            ChainKind.ACCESSOR -> from
        }
        return link.index?.let { picked.selectIndices(it) } ?: picked
    }
}
```

- [x] **Step 4: 运行两组测试通过**

Run: `./gradlew :lib_book_source:testDebugUnitTest --tests "com.ebook.source.script.ElementBackendsTest" --tests "com.ebook.source.script.ScriptRuleEvaluatorCombinatorTest"`
Expected: PASS（19 + 17）。此时 Task 2 留下的桩必须全部被替成真实现，`ScriptRuleEvaluatorCombinatorTest` 不允许还有失败用例。

- [x] **Step 5: 红线 + 提交**

```bash
git grep -in "[l]egado" || echo CLEAN
git add lib_book_source/src/main/java/com/ebook/source/script/ElementBackends.kt lib_book_source/src/test/java/com/ebook/source/script/
git commit -m "$(cat <<'EOF'
feat(lib_book_source): 脚本书源链式与 css 两个求值后端

取值器只允许在链尾，中间出现即语法错误：取值器已把节点变成文本，
后面的段没法再作用其上，静默放过只会得到永远选不中的空结果。
两后端共用「选择→索引裁剪→取值」骨架，分开写会长出两份裁剪口径。
EOF
)"
```

---

### Task 4: 正则 AllInOne 与捕获组引用

**Files:**
- Modify: `lib_book_source/src/main/java/com/ebook/source/script/RegexBackend.kt`（替掉 Task 2 的桩）
- Test: `lib_book_source/src/test/java/com/ebook/source/script/RegexBackendTest.kt`

- [x] **Step 1: 写失败的测试**

```kotlin
package com.ebook.source.script

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 规格 §2.4 的 AllInOne 形态：整源正则切条目，字段用 $n 引用捕获组。 */
class RegexBackendTest {

    private val source = """
        <ul><li><a href="/c/1.html">第一章</a>2024-01-01</li>
        <li><a href="/c/2.html">第二章</a>2024-01-02</li></ul>
    """.trimIndent()

    private fun eval(rule: String): RuleResult =
        ScriptRuleEvaluator(EvalContext()).evaluate(rule, RuleValue.Page(source))

    @Test
    fun `整源正则产出条目乘捕获组`() {
        val r = eval(":<li><a href=\"([^\"]+)\">([^<]+)</a>([\\d-]+)") as RuleResult.Matches
        assertEquals(2, r.items.size)
        assertEquals(listOf("/c/1.html", "第一章", "2024-01-01"), r.items[0].drop(1))
        assertTrue("下标 0 是整段匹配", r.items[0].first().startsWith("<li>"))
    }

    @Test
    fun `组引用按字段取值`() {
        // 目录反序语料形态：chapterList 用 AllInOne，chapterName 用 $2
        val items = (eval(":-:<li><a href=\"([^\"]+)\">([^<]+)") as RuleResult.Matches).items
        assertEquals(2, items.size)
        assertEquals(listOf("第一章", "第二章"), items.map { GroupRef.valueOf("\$2", it) })
    }

    @Test
    fun `反序前缀让条目倒过来`() {
        val r = eval(":-:<li><a href=\"([^\"]+)\">([^<]+)") as RuleResult.Matches
        assertEquals("第二章", r.items.first()[2])
    }

    @Test
    fun `组号越界返回空串而不是抛`() {
        val items = (eval(":<a>([^<]+)") as RuleResult.Matches).items
        assertEquals("", GroupRef.valueOf("\$9", items.first()))
    }

    @Test
    fun `非组引用文本不是组引用`() {
        assertEquals(null, GroupRef.parseOrNull("tag.a@text"))
        assertEquals(null, GroupRef.parseOrNull("\$"))
        assertEquals(1, GroupRef.parseOrNull("\$1"))
    }

    @Test
    fun `无捕获组时整段匹配是唯一下标`() {
        val r = eval(":第一章") as RuleResult.Matches
        assertEquals(1, r.items.first().size)
        assertEquals("第一章", GroupRef.valueOf("\$0", r.items.first()))
    }

    @Test
    fun `零命中是 Miss`() {
        assertEquals(RuleResult.Miss, eval(":<zzz>"))
    }

    @Test
    fun `非法正则抛语法错误而非运行期异常`() {
        val e = runCatching { eval(":[unclosed") }.exceptionOrNull()
        assertTrue("实际：${e?.let { it::class.simpleName }}", e is RuleSyntaxException)
    }
}
```

- [x] **Step 2: 运行确认失败**

Run: `./gradlew :lib_book_source:testDebugUnitTest --tests "com.ebook.source.script.RegexBackendTest"`
Expected: FAIL（桩抛异常）。

- [x] **Step 3: 实现 `RegexBackend.kt`**

```kotlin
package com.ebook.source.script

/**
 * 正则 AllInOne 后端（规格 §2.4 第一形态）。
 *
 * 「以 `:` 开头、对整个源文本切分、条目字段用 `$n` 引用」这三点合起来决定结果的形状
 * 必须是**条目 × 捕获组**的二维结构：它先把一本书/一章「造出来」，字段规则再去里面取。
 * 用 `List<String>` 表达就会把二维压成一维，`chapterName: "$2"` 无从落地。
 *
 * 组引用（`$2` 这类）由 [GroupRef] 独立表达而不是塞回规则串判定：`$2` 作为字符串看起来
 * 更像一条链式规则，若让 [RuleMode] 去认它，词法层就会为一个特例牺牲自己的判定表。
 */
internal object RegexBackend {

    fun evaluate(pattern: String, input: RuleValue, reverse: Boolean): RuleResult {
        val source = when (input) {
            is RuleValue.Page -> input.source
            is RuleValue.Nodes -> input.elements.joinToString("\n") { it.html() }
            is RuleValue.Texts -> input.values.joinToString("\n")
        }
        if (pattern.isBlank() || source.isBlank()) return RuleResult.Miss
        val regex = try {
            // AllInOne 用多行语义：语料的正文页常按行切条目
            Regex(pattern, RegexOption.MULTILINE)
        } catch (e: Exception) {
            throw RuleSyntaxException(":$pattern")
        }
        val items = regex.findAll(source).map { m -> m.groupValues.toList() }.toList()
        if (items.isEmpty()) return RuleResult.Miss
        return RuleResult.Matches(if (reverse) items.reversed() else items)
    }
}

/** 捕获组引用 `$n`（规格 §2.4）。 */
internal object GroupRef {

    /** 是纯组引用时返回 n，否则 null */
    fun parseOrNull(text: String): Int? {
        val t = text.trim()
        if (!t.startsWith("\$") || t.length < 2) return null
        return t.substring(1).toIntOrNull()?.takeIf { it >= 0 }
    }

    /** 取某条目的第 n 组；组号越界返回空串（§3.3 未取到值不抛） */
    fun valueOf(reference: String, item: List<String>): String {
        val n = parseOrNull(reference) ?: return reference
        return item.getOrNull(n).orEmpty()
    }
}
```

链式后端还要消费组引用：在 `ScriptRuleEvaluator.evaluateLeaf` 的 `DEFAULT_CHAIN` 分支之前插入一条

```kotlin
            RuleMode.DEFAULT_CHAIN -> {
                val g = GroupRef.parseOrNull(node.body)
                if (input is RuleResult.Matches ...) ...
            }
```

**正确做法**（写死在这，别让实现者猜）：把组引用判定放进 `evaluateNode` 之前无从做（`input` 是 `RuleValue` 而结果是 `Matches`），因此在 `evaluate` 入口加一个 `Matches` 上下文的下钻：

```kotlin
    /** 列表条目求值：AllInOne 造出的条目逐条按字段规则取值（§2.4 的 $n） */
    fun evaluateOnItem(fieldRule: String, item: List<String>): RuleResult {
        val g = GroupRef.parseOrNull(fieldRule)
        if (g != null) return RuleResult.Texts(listOf(item.getOrNull(g).orEmpty()))
        return evaluate(fieldRule, RuleValue.Texts(listOf(item.firstOrNull().orEmpty())))
    }
```

并在 `ElementBackends.evaluateChain` 的 `seed` 里让 `RuleValue.Texts` 走「把文本当 HTML 解析」这一支（已实现），组引用则完全走上面的新方法、**不进链式后端**。本任务的测试只覆盖 `RegexBackend` + `GroupRef` + `evaluateOnItem` 三件，2d 才用 `evaluateOnItem` 串字段。

- [x] **Step 4: 追加 `evaluateOnItem` 的用例并跑绿**

在 `RegexBackendTest` 末尾追加：

```kotlin
    @Test
    fun `字段规则在条目上按组引用取值`() {
        val ev = ScriptRuleEvaluator(EvalContext())
        val items = (ev.evaluate(":<li><a href=\"([^\"]+)\">([^<]+)", RuleValue.Page(source)) as RuleResult.Matches).items
        assertEquals(listOf("第一章", "第二章"), items.map { ev.evaluateOnItem("\$2", it).let { r -> (r as RuleResult.Texts).values.first() } })
        assertEquals(listOf("/c/1.html", "/c/2.html"), items.map { (ev.evaluateOnItem("\$1", it) as RuleResult.Texts).values.first() })
    }

    @Test
    fun `字段规则不是组引用时按整段匹配继续解`() {
        val ev = ScriptRuleEvaluator(EvalContext())
        val items = (ev.evaluate(":(\\d{4}-\\d{2}-\\d{2})", RuleValue.Page(source)) as RuleResult.Matches).items
        val r = ev.evaluateOnItem("text.2024", items.first())
        assertTrue("应能继续按链式解整段", r !is RuleResult.Miss)
    }
```

Run: `./gradlew :lib_book_source:testDebugUnitTest --tests "com.ebook.source.script.RegexBackendTest"` → PASS（10）。

- [x] **Step 5: 红线 + 提交**

```bash
git grep -in "[l]egado" || echo CLEAN
git add lib_book_source/src/main/java/com/ebook/source/script/RegexBackend.kt lib_book_source/src/main/java/com/ebook/source/script/ScriptRuleEvaluator.kt lib_book_source/src/test/java/com/ebook/source/script/RegexBackendTest.kt
git commit -m "$(cat <<'EOF'
feat(lib_book_source): 脚本书源正则 AllInOne 后端与组引用

AllInOne 先造条目、字段再用 $n 到条目里取，结果形状必须是条目×捕获组二维：
压成一维列表后 chapterName: "$2" 就无从落地。
组引用另立 GroupRef，不让词法层为它破坏模式判定表。
EOF
)"
```

---

### Task 5: `##` 替换段的执行

**Files:**
- Create: `lib_book_source/src/main/java/com/ebook/source/script/ReplacementApplier.kt`
- Modify: `lib_book_source/src/main/java/com/ebook/source/script/ScriptRuleEvaluator.kt`（`applyReplacement` 的透传桩换成真实现）
- Test: `lib_book_source/src/test/java/com/ebook/source/script/ReplacementApplierTest.kt`

- [x] **Step 1: 写失败的测试**

替换的两形态是**值级**行为，直接对 `ReplacementApplier` 断言最清楚；端到端只留一条，证明「切分→取数→替换」这条链接上了。

```kotlin
package com.ebook.source.script

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 规格 §2.4：净化循环替换全部，OnlyOne 只替第一个；替换文本支持 `$n` 组引用。 */
class ReplacementApplierTest {

    private val page = RuleValue.Page(
        """<html><body><p id="x">第一章 全文阅读手机访问</p><p>甲</p><p>乙</p></body></html>"""
    )

    private fun applyAll(texts: List<String>, pattern: String, replacement: String, onlyFirst: Boolean): List<String> {
        val r = ReplacementApplier.apply(
            RuleResult.Texts(texts),
            RegexReplacement(pattern, replacement, onlyFirst),
            "x##$pattern",
        )
        return (r as RuleResult.Texts).values
    }

    @Test
    fun `净化形态循环替换全部命中`() {
        assertEquals(listOf("b1b"), applyAll(listOf("ab1ab"), "a", "", false))
    }

    @Test
    fun `OnlyOne 只替第一个命中`() {
        assertEquals(listOf("bAnana"), applyAll(listOf("banana"), "a", "A", true))
    }

    @Test
    fun `替换作用在每一个已取到的值上而不是只第一个值`() {
        assertEquals(listOf("XXX", "bbb"), applyAll(listOf("aaa", "bbb"), "a", "X", false))
    }

    @Test
    fun `替换文本里的组引用生效`() {
        assertEquals(listOf("x12"), applyAll(listOf("no-12"), ".*(\\d+)", "x\$1", false))
    }

    @Test
    fun `端到端_取值后净化站点噪声`() {
        val r = ScriptRuleEvaluator(EvalContext()).evaluate("id.x@text##\\s*全文阅读", page)
        assertEquals(RuleResult.Texts(listOf("第一章 手机访问")), r)
    }

    @Test
    fun `Miss 不被替换段改写`() {
        assertEquals(
            RuleResult.Miss,
            ScriptRuleEvaluator(EvalContext()).evaluate("class.nope@text##全文", page),
        )
    }

    @Test
    fun `非法替换正则抛语法错误而不是运行期异常`() {
        val e = runCatching {
            ScriptRuleEvaluator(EvalContext()).evaluate("id.x@text##([", page)
        }.exceptionOrNull()
        assertTrue("实际：${e?.let { it::class.simpleName }}", e is RuleSyntaxException)
    }

    @Test
    fun `节点集结果遇到替换段先收敛成文本`() {
        // 写了替换却没带取值器，说明作者要的是清洗后的文本；返回节点集会让更多替换无处可施
        val r = ScriptRuleEvaluator(EvalContext()).evaluate("tag.p##<[^>]+>##", page)
        assertEquals(RuleResult.Texts(listOf("第一章 全文阅读手机访问", "甲", "乙")), r)
    }
}
```

- [x] **Step 2: 运行确认失败**

Run: `./gradlew :lib_book_source:testDebugUnitTest --tests "com.ebook.source.script.ReplacementApplierTest"`
Expected: FAIL——`ReplacementApplier` 未定义（端到端两例因透传桩而不等）。

- [x] **Step 3: 实现 `ReplacementApplier.kt`**

```kotlin
package com.ebook.source.script

/**
 * 把 `##` 尾段应用到已取到的值上（规格 §2.4 的净化与 OnlyOne）。
 *
 * 替换是**后置**的一步（§9 第 6 步）：它作用于取到的文本，不改变选择器行为。
 * 节点集遇到替换段时先按 `text()` 收敛成文本——写了替换却没带取值器，说明作者要的是
 * 清洗后的文本，返回节点集只会让替换无处可施。
 *
 * [expansion] 在 Task 6 才存在，本任务先给 `null` 语义（不回填）：Task 6 会把
 * `{{}}` 的占位符回填接到这里（替换的 pattern 与 replacement 两处都可能是插值目标）。
 */
internal object ReplacementApplier {

    fun apply(result: RuleResult, replacement: RegexReplacement?, rule: String): RuleResult {
        if (replacement == null) return result
        val regex = try {
            Regex(replacement.pattern, RegexOption.MULTILINE)
        } catch (e: Exception) {
            throw RuleSyntaxException(rule)
        }
        val texts = when (result) {
            RuleResult.Miss -> return RuleResult.Miss
            is RuleResult.Texts -> result.values
            is RuleResult.Nodes -> result.elements.map { it.text() }
            is RuleResult.Matches -> result.items.mapNotNull { it.firstOrNull() }
        }
        return RuleResult.Texts(texts.map { replaceOne(it, regex, replacement) })
    }

    /**
     * 净化（[RegexReplacement.onlyFirst] = false）循环替换**全部**命中；
     * OnlyOne 只替**第一个**。这一点是两种正则形态的唯一区别，混了就会把
     * 「只去掉一个前缀」的详情页字段洗成整串消失。
     *
     * `Regex.replace` 的替换串模板天然支持 `$n` 组引用（§2.4 的替换文本用法），不需要自己解析。
     */
    private fun replaceOne(text: String, regex: Regex, replacement: RegexReplacement): String =
        if (replacement.onlyFirst) {
            regex.replaceFirst(text, replacement.replacement)
        } else {
            regex.replace(text, replacement.replacement)
        }
}
```

把 `ScriptRuleEvaluator` 的透传桩换成真调用：

```kotlin
    private fun applyReplacement(raw: RuleResult, replacement: RegexReplacement?, rule: String): RuleResult =
        ReplacementApplier.apply(raw, replacement, rule)
```

调用点随之改为 `applyReplacement(raw, parsed.replacement, parsed.raw)`（`ParsedRule.raw` 是原始规则串，错误消息要带它才能让用户对上是哪条规则）。

- [x] **Step 4: 运行测试通过**

Run: `./gradlew :lib_book_source:testDebugUnitTest --tests "com.ebook.source.script.ReplacementApplierTest"`
Expected: PASS（8）。

- [x] **Step 5: 红线 + 提交**

```bash
git grep -in "[l]egado" || echo CLEAN
git add lib_book_source/src/main/java/com/ebook/source/script/ lib_book_source/src/test/java/com/ebook/source/script/ReplacementApplierTest.kt
git commit -m "$(cat <<'EOF'
feat(lib_book_source): 脚本书源正则替换段执行

净化循环替换全部、OnlyOne 只替第一个，这是两种正则形态的唯一区别：
混了会把「只去掉一个前缀」的详情字段洗成整串消失。
写了替换却没带取值器说明作者要的是文本，节点集在此先收敛成 text()。
EOF
)"
```

---

### Task 6: 变量系统与 `{{}}` 插值的声明式子集

**Files:**
- Create: `lib_book_source/src/main/java/com/ebook/source/script/Interpolation.kt`
- Modify: `lib_book_source/src/main/java/com/ebook/source/script/ScriptRuleEvaluator.kt`（入口先展开、leaf 与替换段回填）
- Modify: `lib_book_source/src/main/java/com/ebook/source/script/ReplacementApplier.kt`（pattern 与 replacement 两处回填）
- Modify: `lib_book_source/src/main/java/com/ebook/source/script/RuleValue.kt`（加 `asResult()`）
- Test: `lib_book_source/src/test/java/com/ebook/source/script/InterpolationTest.kt`
- Test: `lib_book_source/src/test/java/com/ebook/source/script/EvalContextTest.kt`

- [x] **Step 1: 先定设计（读这段，再写测试）**

规格 §9 把「`{{}}` 展开」列为**第 1 步**，在剥尾段与切分之前；而 §5.1 的本仓规定紧接着要求「展开出的文本**不再参与**分隔符切分（把展开结果当不透明字符串占位，切分结束后回填）」。两条合起来的唯一实现形态是**占位符**：

```
1. Interpolation.expand(rule) → Expansion(text = 规则原文里每个 {{…}} 换成 \u0001<序号>\u0001,
                                          values = 序号 → 展开出的值)
2. RuleSplitter.parse(expansion.text)      ← 切分只看占位符，值里的 || ## && 都不参与
3. 求值：leaf 的 body 在使用点 fill(...)（选择器、属性名里都可能带占位符）
4. 替换段：pattern 与 replacement 在使用点 fill(...)
5. 最终结果里残留的占位符再 fill 一次（防御：值本身被当成结果文本时）
```

三条必须守住的推论：
- **占位符用控制字符** `\u0001` 包序号：真实规则串不会出现，且不含任何分隔符。禁止用 `{{…}}` 自身当包装（那会让值里的 `}}` 提前收尾）。
- **模式判定在展开之前、载荷使用在展开之后**：`{{…}}` 整体不携带模式标志，标志恒在展开前后的字符串头部同处；但 leaf body 要 fill 后才能交给 Jsoup。这条次序对应 §5.1 那条「展开发生在切分之前还是之后」的未核实项，必须在 KDoc 里写明本仓选了哪一侧。
- **展开失败/不认识的一律抛 `JsEvaluationPendingException`**，绝不原样保留：`{{(page-1)*20}}` 若原样进 URL，拿到的是字面量表达式而不是页码——「看着正常、实则错到底」（§9 末段）。

`{{…}}` 内容支持的声明式子集（§5.1、§5.3）：

| 形态 | 处理 |
|---|---|
| `@@<规则>` | 递归求值（同一 input），取单值（`firstText()`） |
| `key` / `page` / `baseUrl` | `EvalContext` 的内置量 |
| `<已 put 的变量名>` | `ctx.variables` |
| 其它一切（`book.name`、`(page-1)*20`、`java.*`、`$.x`） | 抛待执行：`book`/`chapter`/`result` 是 JS 侧绑定，`$.` 属 2c 的 JSONPath，算术与函数调用是 JS |

> `book.name` 这一档 2b 有意不接：它的值来自 Room 里的书籍实体，而本段禁止碰持久层（§9 的分工）。2d 装 `BookParser` 时把这些量灌进 `EvalContext`，届时只改 `builtin()` 一处。

- [x] **Step 2: 写失败的测试**

```kotlin
package com.ebook.source.script

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 规格 §5.1/§5.3 的声明式子集 + 「展开结果不参与切分」这条本仓规定。 */
class InterpolationTest {

    private val html = """
        <html><body>
          <div class="name">书名A</div>
          <p>凡人 3 章</p>
        </body></html>
    """.trimIndent()

    private fun eval(rule: String, ctx: EvalContext = EvalContext()) =
        ScriptRuleEvaluator(ctx).evaluate(rule, RuleValue.Page(html))

    @Test
    fun `插值可以出现在选择器位置`() {
        val ctx = EvalContext()
        ctx.variables["cls"] = "name"
        assertEquals(RuleResult.Texts(listOf("书名A")), eval("class.{{cls}}@text", ctx))
    }

    @Test
    fun `插值可以出现在替换段的模式与文本位置`() {
        val ctx = EvalContext(page = 9)
        assertEquals(RuleResult.Texts(listOf("凡人 9 章")), eval("tag.p@text##章##{{page}}章", ctx))
    }

    @Test
    fun `内置量 key 就地展开`() {
        val ctx = EvalContext(key = "读者")
        assertEquals(RuleResult.Texts(listOf("凡人 读者 章")), eval("tag.p@text##3##{{key}}", ctx))
    }

    @Test
    fun `@@ 前缀的规则插值递归求值`() {
        assertEquals(RuleResult.Texts(listOf("凡人 书名A 章")), eval("tag.p@text##3##{{@@class.name@text}}"))
    }

    @Test
    fun `展开出的值不参与分隔符切分`() {
        // §5.1 本仓规定的回归锁：值里含 || 与 ## 时，若在切分前就地回填，这条规则会被腰斩
        val ctx = EvalContext()
        ctx.variables["v"] = "a||b##c"
        val r = eval("tag.p@text##3##{{v}}", ctx)
        assertEquals(RuleResult.Texts(listOf("凡人 a||b##c 章")), r)
    }

    @Test
    fun `算术表达式按 JS 待执行而不是自己算`() {
        val e = runCatching { eval("tag.p@text##3##{{(page-1)*20}}", EvalContext(page = 3)) }.exceptionOrNull()
        assertTrue("实际：${e?.let { it::class.simpleName }}", e is JsEvaluationPendingException)
    }

    @Test
    fun `java 函数调用按 JS 待执行`() {
        val e = runCatching { eval("tag.p@text##3##{{java.base64Encode(key)}}") }.exceptionOrNull()
        assertTrue(e is JsEvaluationPendingException)
    }

    @Test
    fun `book 属性路径留到 2d 灌入而不是现在猜`() {
        val e = runCatching { eval("tag.p@text##3##{{book.name}}") }.exceptionOrNull()
        assertTrue(e is JsEvaluationPendingException)
    }

    @Test
    fun `未闭合的双花括号原样保留`() {
        // 残缺形态不该抛，也不该吞掉后半串：留着它最坏是选不中，抛出去会让整条规则作废
        assertEquals(RuleResult.Miss, eval("class.{{x@text"))
    }
}
```

```kotlin
package com.ebook.source.script

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 规格 §5.2：`@put:` / `@get:` 的作用域是单次解析任务；引号规则按 §5.2 原文。 */
class EvalContextTest {

    private val html = """<html><body><div id="wrap"><a href="/t/9.html">目标</a></div></body></html>"""

    @Test
    fun `put 写入的变量可被 get 读出`() {
        val ev = ScriptRuleEvaluator(EvalContext())
        ev.evaluate("@put:{link:\"tag.a@href\"}", RuleValue.Page(html))
        assertEquals(RuleResult.Texts(listOf("/t/9.html")), ev.evaluate("@get:link", RuleValue.Page(html)))
    }

    @Test
    fun `变量表按任务隔离`() {
        val a = ScriptRuleEvaluator(EvalContext())
        val b = ScriptRuleEvaluator(EvalContext())
        a.evaluate("@put:{k:\"tag.a@text\"}", RuleValue.Page(html))
        assertEquals(RuleResult.Miss, b.evaluate("@get:k", RuleValue.Page(html)))
    }

    @Test
    fun `未写入的 get 是 Miss`() {
        assertEquals(RuleResult.Miss, ScriptRuleEvaluator(EvalContext()).evaluate("@get:none", RuleValue.Page(html)))
    }

    @Test
    fun `同一条 put 里的多条写入按序生效`() {
        val ev = ScriptRuleEvaluator(EvalContext())
        ev.evaluate("@put:{t:\"tag.a@text\", h:\"tag.a@href\"}", RuleValue.Page(html))
        assertEquals(RuleResult.Texts(listOf("目标")), ev.evaluate("@get:t", RuleValue.Page(html)))
        assertEquals(RuleResult.Texts(listOf("/t/9.html")), ev.evaluate("@get:h", RuleValue.Page(html)))
    }

    @Test
    fun `JSONPath 值不带引号而其他模式的规则带引号`() {
        // §5.2 原文：@put: 内使用 JSONPath 时不需要引号，其他模式的规则要加引号
        assertEquals("link" to "$.data[0].id", parsePutEntry("link:$.data[0].id"))
        assertEquals("link" to "tag.a@href", parsePutEntry("link:\"tag.a@href\""))
        assertNull(parsePutEntry("link:"))
        assertNull(parsePutEntry("nocolon"))
    }

    @Test
    fun `条目切分跳过引号与括号内部`() {
        // `{"a":"x,y"}` 这类值里的逗号不得把一条写入切成两条——切错会静默少存一个变量
        assertEquals(listOf("a:\"x,y\"", " b:2"), topLevelEntries("a:\"x,y\", b:2"))
        assertEquals(listOf("a:{nested:1}", "b:2"), topLevelEntries("a:{nested:1},b:2"))
        assertEquals(listOf("only"), topLevelEntries("only"))
    }

    @Test
    fun `put 全部条目都不可用时抛语法错误而不是静默不写`() {
        val e = runCatching {
            ScriptRuleEvaluator(EvalContext()).evaluate("@put:{\"", RuleValue.Page(html))
        }.exceptionOrNull()
        assertTrue("实际：${e?.let { it::class.simpleName }}", e is RuleSyntaxException)
    }
}
```

- [x] **Step 3: 实现 `Interpolation.kt`**

```kotlin
package com.ebook.source.script

/**
 * 一次展开的产物：占位符化的规则文本 + 序号到值的映射。
 *
 * 为什么占位而不是就地替换（规格 §5.1 本仓规定）：展开出的值常含 `||`/`##`/`&&`
 * （关键词、带查询串的 URL、HTML 片段），若在切分前回填，一条规则会被自己的数据腰斩——
 * 症状是「换个关键词就解不出东西」，根因完全看不出来。
 */
internal class Expansion(private val values: Map<Int, String>) {

    /** 回填：把 `\u0001<序号>\u0001` 换回展开值 */
    fun fill(text: String): String {
        if (values.isEmpty() || !text.contains(HEAD)) return text
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            if (text[i] == HEAD) {
                val end = text.indexOf(HEAD, i + 1)
                val num = if (end > i + 1) text.substring(i + 1, end).toIntOrNull() else null
                if (num != null && values.containsKey(num) && end > 0) {
                    out.append(values.getValue(num))
                    i = end + 1
                    continue
                }
            }
            out.append(text[i]); i++
        }
        return out.toString()
    }

    /** 结果侧的兜底回填：值本身成为结果文本时也不许把占位符漏给用户 */
    fun fillResult(result: RuleResult): RuleResult = when (result) {
        RuleResult.Miss -> result
        is RuleResult.Texts -> RuleResult.Texts(result.values.map { fill(it) })
        is RuleResult.Matches -> RuleResult.Matches(result.items.map { item -> item.map { fill(it) } })
        is RuleResult.Nodes -> result
    }

    companion object {
        const val HEAD = '\u0001'
        val EMPTY = Expansion(emptyMap())
    }
}

/**
 * 变量与插值（规格 §5.1、§5.2）的**声明式子集**。
 *
 * 不支持的形态一律抛 `JsEvaluationPendingException` 而不是原样保留或回退成空：
 * 原样保留会把 `{{(page-1)*20}}` 当字面量拼进 URL，解出的是看着正常、实则错到底的内容。
 */
internal object Interpolation {

    /** 第 1 步：切分之前展开。`evaluateInner` 递归跑 `@@规则`，input 用同一次求值的输入。 */
    fun expand(
        rule: String,
        ctx: EvalContext,
        evaluateInner: (String) -> RuleResult,
    ): Pair<Expansion, String> {
        val values = LinkedHashMap<Int, String>()
        val out = StringBuilder(rule.length)
        var slot = 0
        var i = 0
        while (i < rule.length) {
            if (rule.startsWith("{{", i)) {
                val end = rule.indexOf("}}", i + 2)
                if (end < 0) {
                    // 未闭合：原样留着。残缺规则串是真实输入，抛出去会让整条字段作废，
                    // 而原样保留最坏只是选不中
                    out.append(rule.substring(i))
                    break
                }
                val value = resolve(rule.substring(i + 2, end).trim(), ctx, evaluateInner)
                values[slot] = value
                out.append(Expansion.HEAD).append(slot).append(Expansion.HEAD)
                slot++
                i = end + 2
            } else {
                out.append(rule[i]); i++
            }
        }
        return Expansion(values) to out.toString()
    }

    /** 单个 `{{…}}`：`@@规则` 递归；裸名查内置量与变量表；其余按 JS 待执行 */
    private fun resolve(expr: String, ctx: EvalContext, evaluateInner: (String) -> RuleResult): String = when {
        expr.startsWith("@@") -> evaluateInner(expr).firstText()
        expr.startsWith("\$") -> throw JsEvaluationPendingException(expr)   // JSONPath 属 2c
        expr.startsWith("java.") || expr.startsWith("cache.") || expr.contains('(') ||
            expr.contains('+') || expr.contains('-') && expr.isNotEmpty() && !expr.first().isLetter() ->
            throw JsEvaluationPendingException(expr)
        else -> when (expr.lowercase()) {
            "key" -> ctx.key
            "page" -> ctx.page.toString()
            "baseurl" -> ctx.baseUrl
            else -> ctx.variables[expr] ?: throw JsEvaluationPendingException(expr)
        }
    }

    /** `@put:` 的一条目：键与规则原文（引号已剥）。见 §5.2 的引号口径 */
    internal fun parsePutEntry(entry: String): Pair<String, String>? {
        val t = entry.trim()
        val colon = t.indexOf(':')
        if (colon <= 0) return null
        val key = t.substring(0, colon).trim()
        var value = t.substring(colon + 1).trim()
        if (value.startsWith("\"") && value.endsWith("\"") && value.length >= 2) {
            value = value.substring(1, value.length - 1)
        }
        return if (key.isEmpty() || value.isEmpty()) null else key to value
    }

    /** 逗号切段，但跳过引号与 `{{}}`/`{}`/`[]` 内部——值里含逗号是常态 */
    internal fun topLevelEntries(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        val out = mutableListOf<String>()
        var cursor = 0
        var depth = 0
        var inQuotes = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == '"' && (i == 0 || text[i - 1] != '\\') -> inQuotes = !inQuotes
                !inQuotes && (c == '{' || c == '[') -> depth++
                !inQuotes && (c == '}' || c == ']') -> depth = (depth - 1).coerceAtLeast(0)
                !inQuotes && c == ',' && depth == 0 -> {
                    out += text.substring(cursor, i)
                    cursor = i + 1
                }
            }
            i++
        }
        out += text.substring(cursor)
        return out.map { it.trim() }.filter { it.isNotEmpty() }
    }
}

/** `@put:` 的 `{{…}}` 值里也可能有插值，`EvalContext` 因此需要写入出口 */
internal class PutResult(val written: List<Pair<String, String>>)
```

> `resolve` 里那一长串 `when` 条件读起来像凑数——**实现时请把它写成两条明确的判定**：先判「是不是合法标识符（可含 `.` 的字母数字下划线序列）」，合法才查内置量与变量表，不合法一律待执行。上面给的组合条件只是意图草稿，别照抄。

- [x] **Step 4: 把展开接进求值器**

`ScriptRuleEvaluator.evaluate` 改成先展开、把 `Expansion` 一路带到使用点（leaf body、替换段、结果），**不要**存成类的可变字段（同一个 evaluator 会被并发用于多个字段）：

```kotlin
    fun evaluate(rule: String, input: RuleValue): RuleResult {
        val (expansion, masked) = Interpolation.expand(rule, ctx) { inner ->
            val nested = RuleSplitter.parse(inner)
            evaluateNode(nested.root, input, Expansion.EMPTY)
        }
        val parsed = RuleSplitter.parse(masked)
        val raw = evaluateNode(parsed.root, input, expansion)
        val replaced = ReplacementApplier.apply(raw, parsed.replacement, parsed.raw, expansion)
        return expansion.fillResult(replaced)
    }
```

因此 `evaluateNode` / `evaluateLeaf` / `ElementBackends.evaluateChain` / `evaluateCss` / `RegexBackend.evaluate` 各多一个 `expansion: Expansion` 形参，在**使用载荷之前** `expansion.fill(body)`；`ReplacementApplier.apply` 在编译 pattern 与取 replacement 之前各 `fill` 一次。`VARIABLE_PUT` 分支：

```kotlin
        RuleMode.VARIABLE_PUT -> {
            val inner = expansion.fill(node.body).trim().removePrefix("{").removeSuffix("}")
            val written = Interpolation.topLevelEntries(inner).mapNotNull { entry ->
                Interpolation.parsePutEntry(entry)?.let { (k, ruleString) ->
                    val nested = RuleSplitter.parse(expansion.fill(ruleString))
                    k to evaluateNode(nested.root, input, expansion).firstText()
                }
            }
            if (written.isEmpty()) throw RuleSyntaxException(node.body)
            written.forEach { (k, v) -> ctx.variables[k] = v }
            RuleResult.Texts(written.map { it.second })
        }
        RuleMode.VARIABLE_GET ->
            ctx.variables[expansion.fill(node.body).trim()]?.let { RuleResult.Texts(listOf(it)) } ?: RuleResult.Miss
```

`RuleValue.asResult()` 补在 `RuleValue.kt`：`Page -> Texts(listOf(source))`、`Nodes -> Nodes(elements)`、`Texts -> Texts(values)`，并给一条单测。`EvalContextTest` 里 `parsePutEntry` 与 `topLevelEntries` 直接引用 `Interpolation` 的成员（同模块 `internal` 可见），必要时在测试文件顶部加 `import com.ebook.source.script.Interpolation.parsePutEntry` 之类的导入——`Interpolation.parsePutEntry` 也可写成 `internal fun` 便于直接调用。

- [x] **Step 5: 运行测试通过**

Run: `./gradlew :lib_book_source:testDebugUnitTest --tests "com.ebook.source.script.InterpolationTest" --tests "com.ebook.source.script.EvalContextTest"`
Expected: PASS（9 + 7）。

- [x] **Step 6: 全模块回归 + 红线 + 提交**

Run: `./gradlew :lib_book_source:testDebugUnitTest` → 全绿。

```bash
git grep -in "[l]egado" || echo CLEAN
git add lib_book_source/src/main/java/com/ebook/source/script/ lib_book_source/src/test/java/com/ebook/source/script/
git commit -m "$(cat <<'EOF'
feat(lib_book_source): 脚本书源变量系统与插值的声明式子集

{{}} 展开按 §9 是切分之前的第一步，而展开值常含 || ## && ——就地回填会让规则
被自己的数据腰斩，症状是「换个关键词就解不出东西」。改成占位符参与切分、使用点回填。
不支持的形态一律抛待执行：{{(page-1)*20}} 原样进 URL 拿到的是字面量而非页码，
属看着正常实则错到底的内容。@put/@get 作用域取最保守的单次解析任务。
EOF
)"
```

---

### Task 7: 文档同步与全段验证

**Files:**
- Modify: `AGENTS.md`（书源实战建议段的 2a 措辞改为 2a+2b）
- Modify: `docs/superpowers/specs/2026-09-08-script-source-rule-grammar.md`（§11 标注本段钉死项、补新未知项）
- Modify: `docs/superpowers/plans/2026-09-08-script-book-source-foundation.md`（路线图 2b 状态）
- Modify: 本计划文件（勾选、执行期修正记录）

- [x] **Step 1: 规格就地更新**

§11 里本段钉死的条目就地标注落点：§11-2 单值收敛（`RuleResult.firstText` 一处收口 + 后端保留全部）、§11-3 `@textNodes` 与 `@textNode`（本仓只认复数、单数落 `ATTRIBUTE` 由报告标出）、§11-9 错误传播（`firstOf` 捕获 `RuleSyntaxException`、全支皆错则抛）。
新增两条未知项：`&&` 合并两个文本时用什么连接符（本仓按空串拼接，须真实源验证）；`@put:` 值的引号规则在 JSONPath 之外的其它模式上是否一律要求引号。

- [x] **Step 2: AGENTS.md 更新**

把 2a 那段末尾「本段没有任何求值能力：**2b 尚未落地**…」改写为当前实况：**HTML 侧求值已落地**（链式 / `@css:` / 正则 AllInOne / 组合符 / 取值器 / 索引 / 替换 / 变量与插值子集），**JSONPath 与 URL 语义与网络仍在 2c 之后**，脚本行整体仍不接入页面（2d 才替换 `ScriptSourcePendingParser`）。并写明三条容易踩的口径：(a) 未取到值必须是 `RuleResult.Miss`，用空列表冒充会让 `||` 短路失效；(b) AllInOne 的产物是条目×捕获组二维，压平它就废了 `$n` 字段；(c) 任何还不支持的语法都要抛类型化异常，返回空列表等于对用户撒谎。

- [x] **Step 3: 全量验证**

```bash
./gradlew test
./gradlew :module_app:assembleDebug
git grep -in "[l]egado"      # 零命中
```

已知既有 flake：`module_find` 的 `LibraryViewModelTest` 偶发 `Dispatchers.Main is used concurrently with setting it`，命中时重跑确认，不要顺手改。

- [x] **Step 4: 本段仍无人工装机项**

2b 不改任何页面、启动、网络与持久化路径（`ScriptSourcePendingParser` 仍在原位），因此**不新增**人工清单条目；Plan 1 文末 7 条不变。报告时必须明确「本段无设备验证，因为没有任何用户可达路径变化」，不得用「测试全绿」暗示功能已可给用户使用。

- [x] **Step 5: 提交**

```bash
git add AGENTS.md docs/superpowers/
git commit -m "$(cat <<'EOF'
docs: 登记脚本书源 HTML 侧求值器落地口径

规格 §11 标注本段钉死的三条（单值收敛位置、textNodes 单复数、错误传播），
并补两条新的未知项（&& 合并文本的连接符、@put: 值的引号规则）。
AGENTS.md 更正求值能力边界：HTML 侧已能解值，JSONPath 与 URL/网络未落地，
脚本行仍不接入任何页面。
EOF
)"
```

---

## 退出判据

1. `./gradlew test` 全绿、`:module_app:assembleDebug` 成功；`:lib_book_source` 新增 7 个测试类，用例数在 2a 的 134 之上按各任务预期增加。
2. 规格 §2~§5 中属于 HTML 侧的每条判定都能在代码或单测里指到落点；不支持的能力（JS、XPath、JSONPath、`@cache:`）都有**类型化异常**路径而不是静默空结果。
3. Task 2 留下的两个桩（`RegexBackend`、`ElementBackends`）必须已被真实现替换，`applyReplacement` 的透传桩同样——全段跑完后包内不允许残留「抛 Unsupported 的占位实现」（`ScriptRuleExceptions` 里三个异常类型此时应全部有生产调用点）。
4. `git grep -in "[l]egado"` 零命中；无新增编译警告。
5. 脚本书源在应用内仍**不可求值**（仍命中 `ScriptSourcePendingParser`），故无人工装机项。

## 留给 2c / 2d 的接口

- **2c**：`RuleMode.JSON_PATH` 目前抛 `UnsupportedRuleFeatureException`，2c 补 `JsonPathBackend` 并把它接进 `evaluateLeaf`；`RuleValue` 需要加一个 JSON 输入形态。URL 语义（`,{...}` 选项、POST、charset、`{{page}}`/`<>`）全部在 2c，`Interpolation` 已把「算术型页码表达式抛待执行」这条边界画好，2c 不要绕过它自己算。
- **2d**：消费 `evaluateOnItem(fieldRule, item)` 把 AllInOne 的条目 × 组结构落成逐字段取值；消费 `RuleResult.firstText()` 做单值字段收敛；用 `ScriptRuleSet.rule(kind, field)` + `RuleObjectKind` 装出 `BookParser` 实现，替换 `ScriptSourcePendingParser`，并把 `ScriptRuleSet.unsupported` 接到导入报告（挂载点是 `module_me` 的 `BookSourceValidator.validateScript`）。

## 执行期修正记录（2026-09-08，Task 1-2 落地时）

1. **计划让 Task 2 以 13 条红测试收尾是错的**。本仓门禁是**每次提交测试全绿**（AGENTS.md「提交前验证」跑的就是 `./gradlew test`）：红提交毁掉 bisect，还把「提交前检查」变成一句空话。口径改为「Task 3 把后端补齐、连同 Task 2 的用例一起转绿」，后续任务不得再以任何形式留下红提交——「桩先抛异常、本任务的失败是预期的」这类写法一律不再采用。

2. **Task 1 的两处笔误**：① `textNodes` 用例的 fixture 把 receiver 写成 `.bookbox`——`div` 的直接子节点是 `p` **元素**而不是文本节点，`textNodes()` 恒空。已改成 receiver 为 `p`，并保留一条 `boxes` 的断言锁住 §3.2「子文本节点列表」只及直接子节点、不递归（改成递归会把同一段文字既按 div 又按 p 各取一遍）。② `attrNonNull` 的接收者写成 `RuleResult.Nodes` 而函数体用的是 `elements.mapNotNull {}`；落地为 `private fun List<Element>.attrNonNull(name: String)`，由 `mapToTexts` 以 `elements.` 调用。

3. **`EvalContext` KDoc 一句失实**：原文「真依赖跨请求存活的源会在真实运行里取到空，而这条已经在导入报告的能力清单里」——`ScriptRuleSet` 的不支持清单里**没有**这一条（Task 6 才接变量存取、2d 才接导入报告）。已改成「§5.2 要求导入侧提示，该条尚未接」。

4. **用例数**：Task 2 的「17 例」实为 16 例（`ScriptRuleEvaluatorCombinatorTest` 逐条点数即 16）。

5. **`RuleValue.emptyNodes()` 是计划给的死代码**（全仓零调用点）。Task 5/6 收尾时删掉，不要留在包里冒充有人在用；届时若仍无调用方，也别为它补一条单测。

## 执行期修正记录（2026-09-08，Task 3 落地时）

实现骨架照计划，但计划 Step 3 的代码有下列偏差，一律以规格为准（后续任务复用这套骨架时按此为准，别回改回去）：

1. **中间取值器的检测写法是死的**：`links.dropLast(1).forEach { it.kind == ChainKind.ACCESSOR }` 永远不命中——词法层只在**末段**才产出 `ACCESSOR`（`ChainLink.link` 的 `isLast` 判定）。中间位置上的取值器会以两种别的形态漏过来：`text` 既是取值器又是类型关键字（解成名称为空的 `TEXT` 段），其余六个取值器不在类型表里（解成 `ATTRIBUTE` 段）。落地为 `requireAccessorOnlyAtTail` 按这三种形态一起判（§2.5「最后一段是取值器」+ §3.3），不去改 2a 的段结构——模式判定表是词法层的契约。

2. **末段是属性名时也要出文本**：`tag.a@data-x` 的尾段落成 `ChainKind.ATTRIBUTE` 而非 `ACCESSOR`（§3.2 把「@任意属性名」与 text/html 列在同一张表里，两者同为取值器）。计划的 `when` 只有 `isAccessorTail` 会命中，属性名尾段因此走进 `else -> Nodes(apply(last, current))`：既把末段**应用两遍**（`links.dropLast(0)` 已含它，结果永远是选空 → Miss），又把该出文本的形态错报成节点集。判据改为 `valueTail = ACCESSOR || ATTRIBUTE`，尾段一律不进选择循环。

3. **`text.名称` 只留最内层命中**：外层元素的 `text()` 天然涵盖子孙文本，计划的 `getAllElements().filter { it.text().contains(name) }` 对 `text.第一章` 会返回 html/body/div/a 四个节点（用例期望 1 个）。改为「命中集里去掉自己子孙里还有命中的那些」——按文本定位要的是承载这段文本的元素。

4. **跨根遍历之后要去重，且去重在索引裁剪之前**：seed 里的节点可能互为祖先，逐根遍历子树会把同一节点命中多遍。重复条目会流进列表字段（同一本书解出两条），并让 `[0]` 取到「第一个两次」。计划代码没有这一步。

5. **CSS 语法错误按 §3.3 抛类型化错误**：计划的 `runCatching { it.select(selector) }.getOrDefault(emptyList())` 把非法选择器吞成 Miss，等于拿「这个源没有这条信息」冒充「规则写错了」。落地为捕获 `SelectorParseException` 与 `IllegalArgumentException` 换成 `RuleSyntaxException`。同一处置给**空选择器名**（`class.@text`）：Jsoup 的 `Validate.notEmpty` 抛的是未类型化 `IllegalArgumentException`，会穿透 `||` 的短路（`firstOf` 只认 `RuleSyntaxException`），一整条带兜底的规则直接崩。

6. **`getElementById` 保留、没有换成 `selectFirst("#id")`**：Jsoup 1.22.2 里它不带 `@Deprecated`（`--rerun-tasks` 编译零 `w:` 已证），且其语义正是「including or under this element」= 子树内查找，与 §2.5「逐级收窄」相合。换 `selectFirst("#id")` 反而要自己处理 id 里的 CSS 特殊字符转义（`.`/`:`/`[` 等都能合法出现在 HTML id 上），还丢掉子树限定。

7. **三条按计划原文跑不绿/无区分力的用例已就地改写（断言意图不变）**：
   - `末段没有取值器时返回节点集`：计划拿**两次独立求值**的 `Element` 相比，而 Jsoup 的 Element 是引用相等 → 恒假。改为断言形态是 `Nodes`、且节点文本为 `书名A/书名B`。
   - Task 2 的 `css 取到节点集`：规则串 `@css:.odd tag.a@text` 里的 `tag.a` 是**链式**段写法，CSS 里它是「类名为 a 的 `<tag>` 元素」，夹具里恒零命中（实际得 Miss）。§2.1 说 `@css:` 把整段交给 CSS 引擎，故选择器写作 `.odd a`，两条文本的断言不变。
   - Task 2 的三条 `RuleSyntaxException` 用例补了抛点证据（异常带出的规则体就是出错那一支、且取到的是**最后一支**的错误），并加一条对照「取值器只留在链尾时正常求值」，防判定被写宽；`竖线第一支有值则不算第二支` 把「第二支单独求值确实抛异常」钉进同一用例，短路证据不再依赖 `@cache:` 恰好会抛。
   - `ElementBackendsTest` 另加 3 条（19 → 22 例）：空选择器名判语法错误、非法 CSS 选择器判语法错误、嵌套 seed 去重——都是上面第 4/5 条实现出来的行为，没有用例就是无主行为。

8. **Task 4 的两条规则串按计划写法是坏的**（落地时按此修正，否则那两支都得到 Miss，`as RuleResult.Matches` 直接抛 ClassCastException）：
   - `组引用按字段取值` 与 `反序前缀让条目倒过来` 计划里同写 `":-:<li>…"`：`:` 之后的 `-:` 已落进**正则载荷**，字面量 `-:<li>` 在夹具里不存在。前者改为不带反序前缀的 `:<li><a href="([^"]+)">([^<]+)`（条目按文档序，与它断言的 `["第一章","第二章"]` 相合）；反序形态 `-:<li><a href="([^"]+)">([^<]+)` 归给后者那条用例（其断言本就以反序为准，也正是 §2.5 语料实证 `-:<li>…` 的写法）。
   - `组号越界返回空串而不是抛` 的 `:<a>([^<]+)`：夹具里两个 `<a>` 都带 `href` 属性，字面量 `<a>` 匹配不到，改为 `:<a[^>]*>([^<]+)`。

## 执行期修正记录（2026-09-08，Task 5 落地时）

计划 Step 3 的代码与 Step 1 的用例照跑，有下列偏差，一律以规格为准：

1. **`Matches` 分支会把 Task 4 立起来的二维结构压平**（`items.mapNotNull { it.firstOrNull() }` → `Texts`）。
   §2.4 把 OnlyOne 与净化的适用场景明确写成「除四种列表场景之外」，AllInOne 恰在那四种里；
   所以「Matches + 替换段」不是等待定义的形态，而是写坏了的规则。落地为
   `UnsupportedRuleFeatureException`（§12「不得用看起来合理的结果冒充支持」），并补一条直接
   对 `ReplacementApplier` 断言的用例锁住它（ReplacementApplierTest 因此 8 → 9 例）。

2. **`##` 的剥离漏在 2a 的前置例外之外**（规格 §2.3：步骤 1/1b 先于步骤 2，且「正则段内的 `#`
   不参与 `##` 判定」）：`RuleSplitter.parse` 原先无条件在第一个顶层 `##` 处切，
   于是 `:<li>([^<]+)</li>##<dd>` 的正则载荷被削成 `<li>([^<]+)</li>`、多出一个替换段，
   正好触发上面第 1 条。落地为进 `##` 切分之前先按整串探一次模式，JS 与 AllInOne 直接成叶且
   `replacement = null`（与 `RuleSplitter.node` 里对组合符的那道豁免同一个根因、同一个判据）。
   `RuleSplitterTest` 补 2 例（18 → 20）。

3. **`替换文本里的组引用生效` 按计划写法是错的**：模式 `.*(\d+)` 配文本 `"no-12"` 走的是贪婪匹配，
   `.*` 吃掉 `no-1`、组里只剩 `2`，替换结果是 `x2` 而非计划期望的 `x12`。改为惰性
   `.*?(\d+)`（§2.4 明示方言含惰性量词），并追加一条 **OnlyOne + `$1`** 的断言——语料实证形态
   `##/book/(\d+)##https://img.x.com/bookpic/s$1.jpg###` 走的正是 OnlyOne 那一支，
   只测净化一支的话 `replaceFirst` 忘了展开 `$n` 也不会红。

4. **端到端夹具与期望值不自洽**：`<p id="x">第一章 全文阅读手机访问</p>` 配
   `##\s*全文阅读` 只能得到「第一章手机访问」（`手机访问` 前本就没有空白），计划写的是
   「第一章 手机访问」。夹具补上那个空格（真实噪声词本就夹在正文之间），期望值原样保留；
   这条同时把「`\s*` 没吃掉前导空白就会留双空格」也钉住了。该夹具被
   `节点集结果遇到替换段先收敛成文本` 共用，其期望值同步补空格。

5. **`applyReplacement` 的一行透传不保留**：桩删掉、`evaluate` 直接调
   `ReplacementApplier.apply(raw, parsed.replacement, parsed.raw)`。留着私有包装只多一层
   什么都不做的跳转，而退出判据 3 要求这个桩被真实现替换。

6. **两条按计划只有类型断言、区分力不足的用例已就地强化**（用例数不变）：
   `OnlyOne 只替第一个命中` 加同参 `onlyFirst = false` 的对照（否则把实现里的 `true` 改成
   `false` 也照样绿），`非法替换正则抛语法错误而不是运行期异常` 加「消息含**原始规则串**」的断言。
   另注：`applyAll` helper 传的 `rule` 是拼出来的 `"x##$pattern"`， pattern 自身含 `##` 时那条
   串看着误导但无害——它只作错误消息用，所以错误消息那条断言刻意走端到端拿真实规则串。

## 执行期修正记录（2026-09-09，Task 6 落地时）

实现按 Step 3/4 的骨架，偏差如下，一律以规格为准：

1. **计划的两处载荷未落地（零调用点，按本文件修正记录第 5 条的死代码口径跳过）**：
   `RuleValue.asResult()`（Step 4 末尾要求「补在 `RuleValue.kt` 并给一条单测」）与 `PutResult`
   （Step 3 末尾的类）在全段没有任何使用点——变量表是 `String→String`，`firstText()` 已覆盖
   计划里 `asResult()` 想做的事；为它们补单测只会给死代码配一个「有人在用」的假象。
   同时按同一条第 5 项删掉了 `RuleValue.emptyNodes()`（全仓零调用点）。

2. **`resolve` 的内置量查表收在 `EvalContext.builtin` 一处**，未照抄计划里
   `expr.lowercase()` 再逐个匹配 `"key"/"page"/"baseurl"` 的第二张表——那会让「哪些量认得」
   有两处事实源，且 `lowercase()` 会把 `{{KEY}}` 也认成内置量（规格只给 `key`/`page`/`baseUrl`）。
   落地为「先判是不是合法标识符（字母/下划线开头、可含点分路径），是就查 `builtin` 再查变量表，
   否则抛待执行」；`book.name` 因此走「查不到 → 抛」这条，与用例断言一致。

3. **`expand` 多了一道「内层又开 `{{` 则外层按字面量放行」的判定**：`{{x {{key}}` 里
   外层的 `}}` 与内层的 `}}` 是同一个，按计划的 `indexOf("}}")` 会把 `x {{key` 整段当表达式，
   直接抛待执行——而用例要求它「前一段按字面量留着、后一段照常展开」。判定依据是
   「内层出现 `{{` 说明外层没有合法的 `}}` 与之配对」，故只前进一个字符让后面的合法插值仍被扫到。

4. **回填点按「每处载荷只有一个」落地**：`evaluateLeaf` 不预先 fill，把 `node.body` 连同
   `expansion` 交给各后端，由 `ElementBackends`/`RegexBackend` 在入口 `fill` 一次；
   `ReplacementApplier.apply` 的 `expansion` 形参带默认 `Expansion.EMPTY`——`ReplacementApplierTest`
   有两处直接对替换段断言（不经过求值器），默认值让它们无需改动即编译。变量分支的键与
   异常消息仍在 `evaluateLeaf` 就地 fill。

5. **`putVariables` 不对值规则二次 `fill`**：`body` 进来时已回填，从中切出的 `ruleString`
   不可能再含占位符，二次回填是空转（计划的 Step 4 样本里多写了一次）。

6. **验证口径**：`:lib_book_source:testDebugUnitTest --rerun-tasks` 全绿（215 例，含本段新增
   `InterpolationTest` 9 + `EvalContextTest` 7），编译零 `w:` 警告，`git grep -in "[l]egado"` 零命中。
   Task 7（文档同步与全段验证）尚未开始，本段**无人工装机项**（脚本行求值仍命中
   `ScriptSourcePendingParser`，没有任何用户可达路径变化）。

## 执行期修正记录（2026-09-09，Task 7 落地时）

1. **AGENTS.md 除计划要求的末段改写外，一并校正了两句随 2b 落地而失实的旧文**：①「反序眼下只落到
   `RuleNode.Leaf.reverse` 等着被消费」——2b 已由各后端在产出前消费；②「另三个异常类型是为 2b/2d
   预留的尚未接线类型」——三者均已接线。留着会诱导下一位读者按旧边界接线，文档同步铁律要求一并改。
2. **§11-2 标注补了「连接符问题转移」一句**：「取第一个」钉死后，「用什么连接符」的串接形态不再出现，
   该半问只余 `&&` 合并场景，故指向新增的第 15 项而不是在原条目内收尾。
3. **验证口径**：`./gradlew test :module_app:assembleDebug` 一次跑绿（BUILD SUCCESSFUL in 5m 1s，
   real + mock 两个 flavor 均产出；`LibraryViewModelTest` 的既有 flake 未命中），红线
   `git grep -in "[l]egado"` 零命中。本段**无人工装机项**（Step 4）：脚本行求值仍命中
   `ScriptSourcePendingParser`，任何用户可达路径均无变化。
