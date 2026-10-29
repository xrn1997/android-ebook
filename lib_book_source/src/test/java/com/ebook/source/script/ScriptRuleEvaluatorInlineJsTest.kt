package com.ebook.source.script

import com.ebook.source.sandbox.JsStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 内联 `<js>` 分隔符语义（规格 §2.1）的锁形：`前链<js>代码</js>后链` 三段各归各位——
 * 前链产物作为代码里的 `result` 种子、完成值交给后链、纯 `@js:` 不受影响。
 *
 * 回归背景：整串送内核的旧实现让 `tag.a@href<js>…</js>` 这类混写规则必然 `expecting ';'`
 * （真语料「全本小说」coverUrl 当场炸出，2026-09-11 语料回放）。
 * 假桥记录「送进内核的代码」与「result 种子」，并可注入运行失败；
 * 段间编排与失败传播是本类的断言对象。
 */
class ScriptRuleEvaluatorInlineJsTest {

    private val html = """
        <html><body>
        <a class="cover" href="/book/123.html">书名</a>
        <div id="seed">后链落地</div>
        </body></html>
    """.trimIndent()

    private class RecordingBridge : ScriptJsBridge {
        val seen = mutableListOf<Pair<String, String?>>()
        var reply = "JS产出"

        /** 非 null 时 `runSegment` 抛它；用于锁「JS 真跑失败不得回落 Miss」。 */
        var failure: RuntimeException? = null

        override fun runSegment(source: String, input: RuleValue): RuleResult {
            val seed = when (input) {
                is RuleValue.Texts -> input.values.firstOrNull()
                is RuleValue.Nodes -> input.elements.joinToString("") { it.outerHtml() }
                is RuleValue.Page -> input.source
                is RuleValue.Json -> input.element.toString()
            }
            // 先记账再抛：失败用例也要能证明「失败发生在求值器真的进内核这一步」，
            // 而不是前段没取到值/规则写错在更早的步骤就短路了
            seen += source to seed
            failure?.let { throw it }
            return RuleResult.Texts(listOf(reply))
        }

        override fun runExpression(expr: String): String = error("不该走到")
        override fun runUrlJs(script: String, url: String, headers: Map<String, String>): JsRewrittenUrl = error("不该走到")
        override fun runBodyJs(script: String, url: String, body: String): String = error("不该走到")
        override fun runInit(script: String, input: RuleValue): Map<String, String> = error("不该走到")
    }

    private fun eval(rule: String, bridge: ScriptJsBridge): RuleResult {
        val ctx = EvalContext().apply { js = bridge }
        return ScriptRuleEvaluator(ctx).evaluate(rule, RuleValue.Page(html, "https://example.com/"))
    }

    @Test
    fun `内联 js 段的前链产物作为 result 种子`() {
        val bridge = RecordingBridge().apply { reply = "JS(/book/123.html)" }
        val out = eval("tag.a@href<js>var x = result; 'JS(' + result + ')'</js>", bridge)
        assertEquals(RuleResult.Texts(listOf("JS(/book/123.html)")), out)
        assertEquals("进内核的只有代码本体", 1, bridge.seen.size)
        val (code, seed) = bridge.seen.single()
        assertEquals("var x = result; 'JS(' + result + ')'", code)
        assertEquals("result 种子是前链产物", "/book/123.html", seed)
    }

    @Test
    fun `js 产物交给后链继续求值`() {
        val bridge = RecordingBridge().apply { reply = "<div id=\"seed\">后链落地</div>" }
        val out = eval("<js>page</js>#seed@text", bridge)
        assertEquals(RuleResult.Texts(listOf("后链落地")), out)
        // 无前链：result 种子 = 本次输入（整页）
        assertEquals(html, bridge.seen.single().second)
    }

    @Test
    fun `闭合标记缺失按语法错就地拒绝`() {
        val error = runCatching { eval("tag.a@href<js>var x = 1;", RecordingBridge()) }.exceptionOrNull()
        assertTrue("实际：${error?.let { it::class.simpleName }}", error is RuleSyntaxException)
    }

    @Test
    fun `串首的 js 标记未闭合时按整条 js 处置`() {
        // 语料 URL 位实证形态（39 条）：`<js>` 只当「整条是 JS」的标记、不写闭合。
        // 与上一条的分界就在前链在不在：有前链又缺闭合，进内核的注定是解不了的规则文本，仍按语法错拒。
        val bridge = RecordingBridge().apply { reply = "https://x.com/s" }
        val out = eval("<js>var u = 1; 'https://x.com/s'", bridge)
        assertEquals(RuleResult.Texts(listOf("https://x.com/s")), out)
        assertEquals("开标记本身不进内核", "var u = 1; 'https://x.com/s'", bridge.seen.single().first)
    }

    @Test
    fun `纯 js 前缀规则整段进内核不受分隔符影响`() {
        val bridge = RecordingBridge().apply { reply = "纯JS" }
        val out = eval("@js:var y = result; '纯JS'", bridge)
        assertEquals(RuleResult.Texts(listOf("纯JS")), out)
        val (code, seed) = bridge.seen.single()
        assertEquals("var y = result; '纯JS'", code)
        assertEquals("result 种子是本次输入", html, seed)
    }

    /**
     * `前链@js:` 真跑失败必须按 [JsExecutionFailedException] 上抛，不能回落 `Miss`
     * 让 `||` 继续下一支。规格 §9 明令「不得回退或静默截断」：回落会让用户拿到
     * 一支看起来正常、实则错到底的内容，而日志里没有任何失败线索。
     */
    @Test
    fun `js 后位运行失败按类型化异常上抛而不是回落 Miss`() {
        val bridge = RecordingBridge().apply {
            failure = JsExecutionFailedException(JsStatus.RUNTIME, "boom")
        }

        val error = runCatching { eval("tag.a@href@js:throw", bridge) }.exceptionOrNull()

        assertTrue(
            "实际：${error?.let { it::class.simpleName }}",
            error is JsExecutionFailedException,
        )
        // 只断言类型会锁住另一件事：别的步骤炸出的同类异常也算过。带上注入的原文，
        // 钉住「上抛的就是 runSegment 那次失败」
        assertTrue(
            "上抛的必须是注入的那次失败: ${error?.message}",
            error?.message.orEmpty().contains("boom"),
        )
    }

    /**
     * 规格 §9 的**窄例外**：后位 JS 解引用了桥接层从不提供的绑定（`src`/`title` 恒为 undefined）时，
     * QuickJS 报 `cannot read property ... of undefined`。存量源里目录 `nextTocUrl` 有写成
     * `src.match(...)` 的形态；直接上抛会让整本目录解析失败，只能按「这页没有该字段」回落 Miss，
     * 让 `||` 继续兜底。其它运行失败（如 `boom`）仍必须上抛。
     */
    @Test
    fun `js 后位解引用未提供的绑定按 Miss 回落以保留旧源兼容`() {
        val bridge = RecordingBridge().apply {
            failure = JsExecutionFailedException(
                JsStatus.RUNTIME,
                "cannot read property 'match' of undefined",
            )
        }

        assertEquals(
            RuleResult.Miss,
            eval("tag.a@href@js:src.match(/xuanhuan/)[0]", bridge),
        )
        // 失败真的发生在内核里：若前段就没取到值（同样回 Miss），本用例会空转成假绿
        assertEquals("runSegment 必须被调用过一次", 1, bridge.seen.size)
    }

    /**
     * 回落判据读的是失败原文（`JsExecutionFailedException.detail`），不是面向用户的拼装消息：
     * 那句按 120 字截断，属性名一长就把尾部的 `of undefined` 切掉，该回落的不回落——
     * 存量源的窄例外兼容会静默失效，而报错形态看起来与用例里的短属性名完全一样。
     */
    @Test
    fun `js 后位解引用未提供的绑定时长属性名不影响回落`() {
        val longKey = "k".repeat(200)
        val bridge = RecordingBridge().apply {
            failure = JsExecutionFailedException(
                JsStatus.RUNTIME,
                "cannot read property '$longKey' of undefined",
            )
        }

        assertEquals(
            RuleResult.Miss,
            eval("tag.a@href@js:src.match(/xuanhuan/)[0]", bridge),
        )
        assertEquals("runSegment 必须被调用过一次", 1, bridge.seen.size)
    }

    /**
     * 回落判据的后一半：**这段 JS 必须真的把缺失绑定当对象解引用**（`src.` / `title.`）。
     *
     * 只看报错文案会把「页面改版让脚本自己取到 undefined」也吞成 Miss——同一条
     * `cannot read property ... of undefined`，成因却完全不同，被吞掉的那一支会由 `||`
     * 的下一支伪装成正常结果，正是规格 §9 禁止的静默截断。
     */
    @Test
    fun `js 后位没解引用缺失绑定时的 undefined 取属性错仍按类型化异常上抛`() {
        val bridge = RecordingBridge().apply {
            failure = JsExecutionFailedException(
                JsStatus.RUNTIME,
                "cannot read property 'match' of undefined",
            )
        }

        val error = runCatching {
            eval("tag.a@href@js:result.split('/')[1].match(/x/)[0]", bridge)
        }.exceptionOrNull()

        assertTrue(
            "实际：${error?.let { it::class.simpleName }}",
            error is JsExecutionFailedException,
        )
        assertTrue(
            "上抛的必须是注入的那次失败: ${error?.message}",
            error?.message.orEmpty().contains("cannot read property 'match' of undefined"),
        )
    }

    /**
     * 「引用」不能宽到**文本里出现过这两个名字**：`attr("src")`、`@src` 这类字面量与选择器片段
     * 不是把 `src` 当对象用，它炸的是脚本自己取到的 undefined（页面改版形态），必须照 §9 上抛。
     * 判据只认 `src.`/`title.` 的解引用形态。
     */
    @Test
    fun `js 后位只在字面量里出现 src 时仍按类型化异常上抛`() {
        val bridge = RecordingBridge().apply {
            failure = JsExecutionFailedException(
                JsStatus.RUNTIME,
                "cannot read property 'match' of undefined",
            )
        }

        val error = runCatching {
            eval("""tag.a@href@js:result.attr("src").match(/x/)[0]""", bridge)
        }.exceptionOrNull()

        assertTrue(
            "实际：${error?.let { it::class.simpleName }}",
            error is JsExecutionFailedException,
        )
        assertEquals("runSegment 必须被调用过一次", 1, bridge.seen.size)
    }

    /**
     * 回落判据不能宽到「消息里出现 null/undefined」：脚本自己抛 `Error("null")`
     * 同样是 RUNTIME 失败，若被吞成 Miss，调用方会拿 `||` 下一支伪装成正常结果，
     * 正是规格 §9 禁止的静默截断。
     */
    @Test
    fun `js 后位其它运行失败带 null 字样时仍按类型化异常上抛`() {
        val bridge = RecordingBridge().apply {
            failure = JsExecutionFailedException(JsStatus.RUNTIME, "自定义失败：null")
        }

        val error = runCatching { eval("tag.a@href@js:throw", bridge) }.exceptionOrNull()

        assertTrue(
            "实际：${error?.let { it::class.simpleName }}",
            error is JsExecutionFailedException,
        )
        // 同上：钉住失败发生在 runSegment，防止别的步骤的同类异常冒充
        assertTrue(
            "上抛的必须是注入的那次失败: ${error?.message}",
            error?.message.orEmpty().contains("自定义失败：null"),
        )
    }
}
