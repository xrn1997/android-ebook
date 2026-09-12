package com.ebook.source.script

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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
        // 折叠成空列表会让「没取到」与「取到零条」同形，|| 的短路随之失效（§2.3/§3.3）。
        // 断言的是两者「不相等」：一旦哪天把 Miss 做成空 Texts 的别名，或让 mapToTexts
        // 在零节点时返回 Miss，下面三条就会红。
        val emptyTexts = RuleResult.Texts(emptyList())
        val zeroNodesAsTexts = RuleResult.Nodes(emptyList()).mapToTexts(AccessorKind.TEXT)
        assertNotEquals(RuleResult.Miss, emptyTexts)
        assertNotEquals(RuleResult.Miss, zeroNodesAsTexts)
        // 零个节点映射出来是「取到零条的空 Texts」，与 Miss 只差在身份，firstText 却同为空串：
        // 收敛口径（§11-2）刻意看不出区别，区别只有 || 的短路判据用得到，故两个事实都要钉
        assertEquals(zeroNodesAsTexts, emptyTexts)
        assertEquals("", RuleResult.Miss.firstText())
        assertEquals("", emptyTexts.firstText())
    }

    @Test
    fun `节点集按 text 取值器映射成文本`() {
        val nodes = doc().getElementsByClass("bookbox")
        val texts = RuleResult.Nodes(nodes.toList()).mapToTexts(AccessorKind.TEXT)
        assertEquals(listOf("第一章 第二章", "第三章"), texts.values)
    }

    @Test
    fun `节点集按 textNodes 取值器逐子节点取值`() {
        // §3.2「子文本节点列表（逐段返回）」取的是**命中元素自己的**文本子节点，不递归进子元素：
        // 语料形态 `@css:.articleDiv p@textNodes` 正是先选中 p、再取 p 内被 <br> 切开的各段。
        // 若在实现里改成递归，这条写法就会把同一段文字既按 div 又按 p 各取一遍。
        val boxes = doc().getElementsByClass("bookbox")
        assertEquals(emptyList<String>(), RuleResult.Nodes(boxes.toList()).mapToTexts(AccessorKind.TEXT_NODES).values)
        val paragraphs = doc().getElementsByTag("p")
        val texts = RuleResult.Nodes(paragraphs.toList()).mapToTexts(AccessorKind.TEXT_NODES)
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
}
