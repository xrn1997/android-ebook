package com.ebook.source.script

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    private fun eval(rule: String, input: RuleValue = RuleValue.Page(html)): RuleResult =
        ScriptRuleEvaluator(EvalContext()).evaluate(rule, input)

    /** 列表口径求值（末段裸词按选择器，§3.2）：与 [eval] 的 AUTO 口径对照 */
    private fun evalSelector(rule: String, input: RuleValue = RuleValue.Page(html)): RuleResult =
        ScriptRuleEvaluator(EvalContext()).evaluate(rule, input, ChainTail.SELECTOR)

    @Test
    fun `class 取全部匹配`() {
        assertEquals(RuleResult.Texts(listOf("书名A", "书名B")), eval("class.name@text"))
    }

    @Test
    fun `JSON 输入进链式后端按不支持拒绝`() {
        // §11-18 本仓规定的锁形：选择器跑在它跑不了的东西上要如实报能力缺口，不是静默空结果
        val doc = Json.parseToJsonElement("""{"a":1}""")
        val e = runCatching { eval("class.name@text", RuleValue.Json(doc)) }.exceptionOrNull()
        assertTrue(e is UnsupportedRuleFeatureException)
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
        // §2.5「children 取所有子标签」：children 不带名称，[1] 是 children 上的索引。
        // #info 的直接子元素依次是 span/span/a/a/p/p/p，故 1 号是第二个 span。
        assertEquals(
            RuleResult.Texts(listOf("书名B")),
            eval("id.info@children[1]@text"),
        )
    }

    @Test
    fun `text 段按文本内容定位`() {
        // §2.5「text 按文本内容定位」：名称是文本的一部分；末段无取值器故返回节点集。
        // 只有承载这段文本的最内层元素算命中——外层元素的 text() 天然涵盖子孙文本。
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
    fun `属性在全部命中元素上都缺失时判未取到值`() {
        // §3.3：选择器选中了元素、取值器却一个值也取不出来，与「选择器选不到节点」同义。
        // 留在 Texts(空) 会让 firstOf 判成「有值」，`A@_src||A@src` 这类兜底支永远不执行（§2.2），
        // 症状是封面取到空串且零报错。两种模式各锁一条，避免只修一边。
        assertEquals(RuleResult.Miss, eval("tag.p@data-x"))
        assertEquals(RuleResult.Miss, eval("@css:p@_src"))
    }

    @Test
    fun `textNodes 全是空白节点时判未取到值`() {
        // 取值器映射后为空即未取到值：Jsoup 把源码里的缩进留成文本节点，
        // 整片只有空白的节点经 trim 过滤后一个值也不剩（§3.2）
        val blank = RuleValue.Page("""<html><body><div class="blank">   </div></body></html>""")
        assertEquals(RuleResult.Miss, eval("@css:.blank@textNodes", blank))
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
        // 不与「另一次求值」比节点：两次 Jsoup.parse 产出的是不同实例，而 Jsoup 的 Element
        // 是引用相等，那样的断言恒假。这里要钉住的是形态（Nodes 而非 Texts）与选中的是哪两个节点。
        val r = eval("class.name")
        assertTrue("实际：${r::class.simpleName}", r is RuleResult.Nodes)
        assertEquals(listOf("书名A", "书名B"), (r as RuleResult.Nodes).elements.map { it.text() })
    }

    @Test
    fun `多级收窄`() {
        assertEquals(RuleResult.Texts(listOf("第一章")), eval("id.info@tag.a.0@text"))
    }

    @Test
    fun `空选择器名判语法错误而不是运行期异常`() {
        // Jsoup 对空选择器名抛 IllegalArgumentException（Validate.notEmpty），那是未类型化的崩溃：
        // firstOf 只认 [RuleSyntaxException]，让它穿透等于一整条带 || 兜底的规则作废（§3.3）
        val e = runCatching { eval("class.@text") }.exceptionOrNull()
        assertTrue("实际：${e?.let { it::class.simpleName }}", e is RuleSyntaxException)
    }

    @Test
    fun `css 选择器解析失败判语法错误`() {
        // §3.3：CSS 语法错误抛类型化语法错误（可被 || 当未取到值继续），
        // 既不把 Jsoup 的 SelectorParseException 原样抛给调用方，也不吞成 Miss 冒充「这个源没这条信息」
        val e = runCatching { eval("@css:.foo[") }.exceptionOrNull()
        assertTrue("实际：${e?.let { it::class.simpleName }}", e is RuleSyntaxException)
    }

    @Test
    fun `节点集里有嵌套元素时重复命中被去掉`() {
        // seed 同时装着 div 与它的子 span：从 div 子树选 span 命中两个，从每个 span 自身又各命中一次。
        // 不去重会让「同一节点出现两遍」流进列表字段（同一本书解出两条），且让 [0] 取到重复项。
        // 必须取同一次解析出的节点：Jsoup 的 Element 是引用相等，两次 parse 出的同名节点不属重复。
        val div = (eval("id.info") as RuleResult.Nodes).elements.single()
        val r = ScriptRuleEvaluator(EvalContext()).evaluate(
            "tag.span@text",
            RuleValue.Nodes(listOf(div) + div.children()),
        )
        assertEquals(RuleResult.Texts(listOf("书名A", "书名B")), r)
    }

    @Test
    fun `裸 CSS 链段逐级收窄并返回节点集`() {
        // 列表口径：#info 下选 a，末段是选择器 → Nodes
        val r = evalSelector("#info@a") as RuleResult.Nodes
        assertEquals(listOf("第一章", "第二章"), r.elements.map { it.text() })
    }

    @Test
    fun `裸 CSS 类与标签复合选择`() {
        assertEquals(RuleResult.Texts(listOf("书名A", "书名B")), eval("#info .name@text"))
    }

    @Test
    fun `裸 CSS 属性选择器交给 Jsoup`() {
        assertEquals(RuleResult.Texts(listOf("/x/1.html")), eval("#info@a[data-x=\"7\"]@href"))
    }

    @Test
    fun `tag 点位置序号在链里收窄`() {
        assertEquals(RuleResult.Texts(listOf("第一章")), eval("#info@a.0@text"))
    }

    @Test
    fun `单值口径末段裸词取属性、列表口径末段裸词选节点`() {
        // AUTO：a@data-x 末段 data-x 是属性名 → 取属性值
        assertEquals(RuleResult.Texts(listOf("7")), eval("#info@a@data-x"))
        // SELECTOR：同形规则末段当选择器 → 选 <data-x> 元素，无 → Miss
        assertTrue(evalSelector("#info@a@data-x") is RuleResult.Miss)
    }

    @Test
    fun `非法裸 CSS 选择器判类型化语法错误`() {
        // §3.3：CSS 语法错误抛 RuleSyntaxException（可被 || 当未取到值），不静默空、不裸抛 Jsoup 异常。
        // 必须用 SELECTOR 口径：单段规则的末段只有在该口径下才走 CSS 路径（AUTO 下会被当属性名）
        val e = runCatching { evalSelector(".foo[") }.exceptionOrNull()
        assertTrue("实际：${e?.let { it::class.simpleName }}", e is RuleSyntaxException)
    }

    @Test
    fun `整条规则取值器作用于当前节点`() {
        // `@href` / `@text` 这类整条即取值器的规则：作用在传入的当前节点（节点集）上，不是它的子元素
        val a = (eval("id.info@tag.a.0") as RuleResult.Nodes).elements.single()
        assertEquals(RuleResult.Texts(listOf("/x/1.html")), eval("@href", RuleValue.Nodes(listOf(a))))
        assertEquals(RuleResult.Texts(listOf("第一章")), eval("@text", RuleValue.Nodes(listOf(a))))
    }
}
