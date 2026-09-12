package com.ebook.source.script

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `ScriptFieldExtractor` 的独立契约：URL 落位（剥尾段 → join 三形态 → 回附）、
 * 四类列表结果 → 条目上下文的分派、JSON 数组节点的条目展开。搜索/发现链路的
 * 端到端行为在 `com.ebook.source.analyze.ScriptBookParserTest` 锁，这里只锁装配件本身。
 */
class ScriptFieldExtractorTest {

    @Test
    fun `resolveUrl 剥尾段落位后回附`() {
        val extractor = ScriptFieldExtractor(ScriptRuleEvaluator(EvalContext()), "https://root.com")
        assertEquals("https://root.com/book/1", extractor.resolveUrl("/book/1", "https://root.com/dir/page.html"))
        assertEquals("https://root.com/book/1,{\"charset\":\"gbk\"}",
            extractor.resolveUrl("/book/1,{\"charset\":\"gbk\"}", "https://root.com/dir/page.html"))
        assertEquals("https://other.com/x", extractor.resolveUrl("https://other.com/x", "https://root.com/dir/page.html"))
    }

    @Test
    fun `listItems 把四类列表结果各分成条目上下文`() {
        val extractor = ScriptFieldExtractor(ScriptRuleEvaluator(EvalContext()), "https://root.com")
        val doc = Jsoup.parse("<ul><li>a</li><li>b</li></ul>")
        assertEquals(2, extractor.listItems(RuleResult.Nodes(doc.select("li"))).size)
        assertEquals(2, extractor.listItems(RuleResult.Matches(listOf(listOf("x", "1"), listOf("y", "2")))).size)
        assertEquals(1, extractor.listItems(RuleResult.Texts(listOf("t"))).size)
        assertEquals(0, extractor.listItems(RuleResult.Miss).size)
    }

    @Test
    fun `listItems 把 JSON 数组节点展开成元素条目`() {
        // `$.data.books` 的惯用写法解出的是数组节点本身：条目 = 数组元素（展开口径见
        // listItems 的 KDoc——后端保留节点形态是单值字段的行为，锁形见 JsonPathBackendTest）
        val extractor = ScriptFieldExtractor(ScriptRuleEvaluator(EvalContext()), "https://root.com")
        val array = JsonArray(listOf(JsonPrimitive("a"), JsonPrimitive("b")))
        assertEquals(2, extractor.listItems(RuleResult.Jsons(listOf(array))).size)
        // 非数组节点（对象/标量）不展开：单条目，字段规则直接作用其上
        assertEquals(1, extractor.listItems(RuleResult.Jsons(listOf(JsonPrimitive("x")))).size)
        // 嵌套数组只展开一层：内层数组原样作为条目（node 仍是 JsonArray，不做递归二次展开），
        // 第二个条目是标量 "c"——区分「一层展开」与「递归展开」两种实现
        val inner = buildJsonArray { add(JsonPrimitive("a")); add(JsonPrimitive("b")) }
        val outer = buildJsonArray {
            add(inner)
            add(JsonPrimitive("c"))
        }
        val nestedItems = extractor.listItems(RuleResult.Jsons(listOf(outer)))
        assertEquals(2, nestedItems.size)
        assertEquals(inner, (nestedItems[0] as ScriptFieldExtractor.ItemContext.JsonCtx).node)
        assertEquals(JsonPrimitive("c"), (nestedItems[1] as ScriptFieldExtractor.ItemContext.JsonCtx).node)
    }

    private val html = """
        <div class="box"><ul>
          <li><a href="/b/1">书一</a></li>
          <li><a href="/b/2">书二</a></li>
        </ul></div>
    """.trimIndent()

    @Test
    fun `evaluateListField 用列表口径让裸 CSS 末段选出节点`() = runTest {
        val extractor = ScriptFieldExtractor(
            ScriptRuleEvaluator(EvalContext()),
            sourceRoot = "https://x.com",
        )
        val result = extractor.evaluateListField(".box@ul@li", RuleValue.Page(html))
        val items = extractor.listItems(result)
        assertEquals(2, items.size)
    }
}
