package com.ebook.source.script

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 规格 §5.2：`@put:` / `@get:` 的作用域是单次解析任务；引号规则按 §5.2 原文。
 *
 * `parsePutEntry` 与 `topLevelEntries` 是 `@put:` 的两个内部判据，单独直测：它们在书源里
 * 只有写对了才看得出对，出错时静默少存一个变量，端到端反而看不出来。
 */
class EvalContextTest {

    private val html = """<html><body><div id="wrap"><a href="/t/9.html">目标</a></div></body></html>"""

    private fun page() = RuleValue.Page(html)

    @Test
    fun `put 写入的变量可被 get 读出`() {
        val ev = ScriptRuleEvaluator(EvalContext())
        val put = ev.evaluate("@put:{link:\"tag.a@href\"}", page())
        // `@put:` 自身回吐写入值：它常挂在 `&&` 里当一个字段用，返回 Miss 会被当成没取到值
        assertEquals(RuleResult.Texts(listOf("/t/9.html")), put)
        assertEquals(RuleResult.Texts(listOf("/t/9.html")), ev.evaluate("@get:link", page()))
    }

    @Test
    fun `变量表按任务隔离`() {
        val a = ScriptRuleEvaluator(EvalContext())
        val b = ScriptRuleEvaluator(EvalContext())
        a.evaluate("@put:{k:\"tag.a@text\"}", page())
        assertEquals(RuleResult.Miss, b.evaluate("@get:k", page()))
        // 对照：同一台 evaluator 读得到，证明上面那条 Miss 来自隔离而不是 get 根本没接线
        assertEquals(RuleResult.Texts(listOf("目标")), a.evaluate("@get:k", page()))
    }

    @Test
    fun `未写入的 get 是 Miss`() {
        assertEquals(RuleResult.Miss, ScriptRuleEvaluator(EvalContext()).evaluate("@get:none", page()))
    }

    @Test
    fun `同一条 put 里的多条写入按序生效`() {
        val ev = ScriptRuleEvaluator(EvalContext())
        ev.evaluate("@put:{t:\"tag.a@text\", h:\"tag.a@href\"}", page())
        assertEquals(RuleResult.Texts(listOf("目标")), ev.evaluate("@get:t", page()))
        assertEquals(RuleResult.Texts(listOf("/t/9.html")), ev.evaluate("@get:h", page()))
        // 同名后写覆盖先写：这条才是「按序」的证据。倒着遍历得到 "目标"，
        // 把重复条目整个丢掉也得到 "目标"，两种错法都会被这条红掉（上一行存的就是 "目标"）
        ev.evaluate("@put:{t:\"tag.a@text\", t:\"tag.a@href\"}", page())
        assertEquals(RuleResult.Texts(listOf("/t/9.html")), ev.evaluate("@get:t", page()))
    }

    @Test
    fun `JSONPath 值不带引号而其他模式的规则带引号`() {
        // §5.2 原文：@put: 内使用 JSONPath 时不需要引号，其他模式的规则要加引号
        assertEquals("link" to "$.data[0].id", Interpolation.parsePutEntry("link:$.data[0].id"))
        assertEquals("link" to "tag.a@href", Interpolation.parsePutEntry("link:\"tag.a@href\""))
        assertNull(Interpolation.parsePutEntry("link:"))
        assertNull(Interpolation.parsePutEntry("nocolon"))
    }

    @Test
    fun `条目切分跳过引号与括号内部`() {
        // `{"a":"x,y"}` 这类值里的逗号不得把一条写入切成两条——切错会静默少存一个变量
        assertEquals(listOf("a:\"x,y\"", "b:2"), Interpolation.topLevelEntries("a:\"x,y\", b:2"))
        assertEquals(listOf("a:{nested:1}", "b:2"), Interpolation.topLevelEntries("a:{nested:1},b:2"))
        assertEquals(listOf("only"), Interpolation.topLevelEntries("only"))
    }

    @Test
    fun `put 全部条目都不可用时抛语法错误而不是静默不写`() {
        val e = runCatching { ScriptRuleEvaluator(EvalContext()).evaluate("@put:{\"", page()) }.exceptionOrNull()
        assertTrue("实际：${e?.let { it::class.simpleName }}", e is RuleSyntaxException)
    }
}
