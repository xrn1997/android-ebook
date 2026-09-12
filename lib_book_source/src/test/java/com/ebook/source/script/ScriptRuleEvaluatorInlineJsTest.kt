package com.ebook.source.script

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 内联 `<js>` 分隔符语义（规格 §2.1）的锁形：`前链<js>代码</js>后链` 三段各归各位——
 * 前链产物作为代码里的 `result` 种子、完成值交给后链、纯 `@js:` 不受影响。
 *
 * 回归背景：整串送内核的旧实现让 `tag.a@href<js>…</js>` 这类混写规则必然 `expecting ';'`
 * （真语料「全本小说」coverUrl 当场炸出，2026-09-11 语料回放）。
 * 假桥只记录「送进内核的代码」与「result 种子」，段间编排是本类唯一断言对象。
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

        override fun runSegment(source: String, input: RuleValue): RuleResult {
            val seed = when (input) {
                is RuleValue.Texts -> input.values.firstOrNull()
                is RuleValue.Nodes -> input.elements.joinToString("") { it.outerHtml() }
                is RuleValue.Page -> input.source
                is RuleValue.Json -> input.element.toString()
            }
            seen += source to seed
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
}
