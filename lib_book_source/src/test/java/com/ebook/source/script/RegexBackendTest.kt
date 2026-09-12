package com.ebook.source.script

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 规格 §2.4 的 AllInOne 形态：整源正则切条目，字段用 $n 引用捕获组。 */
class RegexBackendTest {

    private val source = """
        <ul><li><a href="/c/1.html">第一章</a>2024-01-01</li>
        <li><a href="/c/2.html">第二章</a>2024-01-02</li></ul>
    """.trimIndent()

    private fun eval(rule: String, input: RuleValue = RuleValue.Page(source)): RuleResult =
        ScriptRuleEvaluator(EvalContext()).evaluate(rule, input)

    @Test
    fun `整源正则产出条目乘捕获组`() {
        val r = eval(":<li><a href=\"([^\"]+)\">([^<]+)</a>([\\d-]+)") as RuleResult.Matches
        assertEquals(2, r.items.size)
        assertEquals(listOf("/c/1.html", "第一章", "2024-01-01"), r.items[0].drop(1))
        assertTrue("下标 0 是整段匹配", r.items[0].first().startsWith("<li>"))
    }

    @Test
    fun `JSON 输入进正则后端按不支持拒绝`() {
        // §11-18 本仓规定的锁形：与 JsonPathBackend 种子对 Page/Texts 按 Miss 刻意不对称，两侧不得互改
        val doc = Json.parseToJsonElement("""{"a":1}""")
        val e = runCatching { eval(":<a href=\"([^\"]+)\">", RuleValue.Json(doc)) }.exceptionOrNull()
        assertTrue(e is UnsupportedRuleFeatureException)
    }

    @Test
    fun `组引用按字段取值`() {
        // 目录字段的语料形态：chapterList 用 AllInOne 造条目，chapterName 写 "$2" 到条目里取。
        // 这里不带反序前缀，条目按文档序；反序是下一条用例的事（§2.4/§2.5）。
        val items = (eval(":<li><a href=\"([^\"]+)\">([^<]+)") as RuleResult.Matches).items
        assertEquals(2, items.size)
        assertEquals(listOf("第一章", "第二章"), items.map { GroupRef.valueOf("\$2", it) })
    }

    @Test
    fun `反序前缀让条目倒过来`() {
        // `-:` 是 §2.5 的语料实证写法（目录倒序站点）：前缀在 `:` 之前，剥掉后才是正则载荷
        val r = eval("-:<li><a href=\"([^\"]+)\">([^<]+)") as RuleResult.Matches
        assertEquals("第二章", r.items.first()[2])
    }

    @Test
    fun `组号越界返回空串而不是抛`() {
        // 夹具里的 <a> 都带 href，故模式写 <a[^>]*>；越界是逐字段规则的常态（源作者数错组号）
        val items = (eval(":<a[^>]*>([^<]+)") as RuleResult.Matches).items
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

    @Test
    fun `字段规则在条目上按组引用取值`() {
        val ev = ScriptRuleEvaluator(EvalContext())
        val items = (ev.evaluate(":<li><a href=\"([^\"]+)\">([^<]+)", RuleValue.Page(source)) as RuleResult.Matches).items
        val names = items.map { (ev.evaluateOnItem("\$2", it) as RuleResult.Texts).values.first() }
        val urls = items.map { (ev.evaluateOnItem("\$1", it) as RuleResult.Texts).values.first() }
        assertEquals(listOf("第一章", "第二章"), names)
        assertEquals(listOf("/c/1.html", "/c/2.html"), urls)
    }

    @Test
    fun `字段规则不是组引用时按整段匹配继续解`() {
        // 不是 $n 的字段规则进链式后端，输入是「整段匹配当 HTML 解析」的文本集（§9 第 5 步）
        val ev = ScriptRuleEvaluator(EvalContext())
        val items = (ev.evaluate(":(\\d{4}-\\d{2}-\\d{2})", RuleValue.Page(source)) as RuleResult.Matches).items
        val r = ev.evaluateOnItem("text.2024", items.first())
        assertTrue("应能继续按链式解整段，实际：${r::class.simpleName}", r !is RuleResult.Miss)
    }
}
