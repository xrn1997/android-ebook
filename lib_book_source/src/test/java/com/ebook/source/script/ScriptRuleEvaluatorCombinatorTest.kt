package com.ebook.source.script

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
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
        // 选择器写 `.odd a` 而不是链式的 `tag.a`：§2.1 的 CSS 模式把整段交给 CSS 引擎，
        // 在那里 `tag.a` 是「类名为 a 的 <tag> 元素」，这条规则会永远选不中
        assertEquals(RuleResult.Texts(listOf("书一", "书二")), eval("@css:.odd a@text"))
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
        // 自己把「第二支确实会抛」钉在本用例里：哪天 @cache: 改成返回 Miss，
        // 上面那条断言就会失去区分力（不短路也能过），这条会先红
        assertTrue(
            runCatching { eval("@cache:1") }.exceptionOrNull() is UnsupportedRuleFeatureException,
        )
    }

    @Test
    fun `竖线全部支都没值才是 Miss`() {
        assertEquals(RuleResult.Miss, eval("class.no1@text||class.no2@text"))
    }

    @Test
    fun `第一支取值器取空时竖线继续下一支`() {
        // §2.2「空结果（未取到值）才继续下一支」：第一支**选中了元素**但取值器一个值也取不出来
        // （§3.2 属性缺失的元素跳过）——这与「选择器选不到节点」同判为未取到值。
        // 否则 firstOf 会把 Texts(空) 当成有值，兜底支永远不执行，封面取到空串且零报错。
        // 真实形态就是 `A@_src||A@src`（懒加载图片的 src 兜底）；链式与 @css: 两种模式各锁一条。
        assertEquals(
            RuleResult.Texts(listOf("书一", "书二")),
            eval("class.odd@tag.a@data-x||class.odd@tag.a@text"),
        )
        assertEquals(
            RuleResult.Texts(listOf("书一", "书二")),
            eval("@css:.odd a@_src||@css:.odd a@text"),
        )
        // 兜底支也没值时才落到 Miss
        assertEquals(RuleResult.Miss, eval("class.odd@tag.a@data-x||class.none@text"))
    }

    @Test
    fun `双与号合并各支取到的值`() {
        assertEquals(
            RuleResult.Texts(listOf("书一", "书二", "备选标题")),
            eval("class.odd@tag.a@text&&tag.dd@tag.h1@text"),
        )
    }

    @Test
    fun `双与号两支各出一个值时仍是多值，由单值字段收敛取第一个`() {
        // §11-15：合并层不替调用方收敛，多值结果由字段级 firstText 取第一个。
        // 判据是真语料金标准 `ScriptRealSourceGoldenTest` 的手机看书
        // `kind = a.1@text&&span@textNodes`（两支各出一个值：分类「修真」与日期「2026-09-07」）——
        // 站点要的是分类；若在合并层拼成单串会得到「修真2026-09-07」。
        val r = eval("class.odd.0@tag.a@text&&tag.dd@tag.h1@text")
        assertEquals(RuleResult.Texts(listOf("书一", "备选标题")), r)
        assertEquals("书一", r.firstText())
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
        // 带出的规则体就是这一支的原文：证明抛点在链式后端对「中间段」的判定，而不是别处的巧合
        assertEquals("tag.a@text@tag.b@text", (e as RuleSyntaxException).rule)
        // 对照：同一条链把取值器只留在链尾就正常求值（选不中是 Miss，不是抛）——
        // 判据是取值器的**位置**，不是链里出现了 text 这个词，防止判定被写宽
        assertEquals(RuleResult.Miss, eval("tag.a@tag.b@text"))
    }

    @Test
    fun `js 支抛待执行异常且消息含脚本书源字样`() {
        // 本机未装配沙箱（`eval` 用的 EvalContext 没有桥）：有桥时这一支走 `runSegment` 真求值
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
        // §3.3：抛类型化语法错误，可被 || 当成未取到值继续；不得静默返回上一支结果。
        // 第一支是坏链（抛）、第二支才有值：这一条同时钉住两侧——错误没被吞掉就走不到下一支，
        // 「静默返回上一支」则拿到的会是上一支的值而不是本支的书名
        val r = eval("tag.a@text@tag.b@text||class.odd@tag.a@text")
        assertEquals(RuleResult.Texts(listOf("书一", "书二")), r)
    }

    @Test
    fun `全部支都语法错误时把错误抛出而不是返回 Miss`() {
        val e = runCatching { eval("tag.a@text@tag.b@text||tag.c@text@tag.d@text") }.exceptionOrNull()
        assertTrue("实际：${e?.let { it::class.simpleName }}", e is RuleSyntaxException)
        // 抛出的是**最后一支**的错误（firstOf 只留 lastError）：只断言类型的话，
        // 「把第一支的错误提前抛出」也能过，而那样读者无法从消息里对上是哪一支没兜住
        assertEquals("tag.c@text@tag.d@text", (e as RuleSyntaxException).rule)
    }

    private val jsonDoc = """{"books":[{"name":"书一","kind":"玄幻"},{"name":"书二","kind":"都市"}],"total":2}"""

    @Test
    fun `jsonPath 取到值`() {
        val r = ScriptRuleEvaluator(EvalContext()).evaluate("$.total", RuleValue.Json(Json.parseToJsonElement(jsonDoc)))
        // JSONPath 结果保留节点形态（RuleResult.Jsons），字符串化只发生在替换段/单值收敛处——
        // 提前落成字符串列表会压扁列表条目的结构，2d 逐条目取子字段将无从下手
        assertEquals(RuleResult.Jsons(listOf(JsonPrimitive(2))), r)
    }

    @Test
    fun `jsonPath 参与竖线短路`() {
        val r = ScriptRuleEvaluator(EvalContext()).evaluate(
            "$.none||$.books[0].name",
            RuleValue.Json(Json.parseToJsonElement(jsonDoc)),
        )
        assertEquals(RuleResult.Jsons(listOf(JsonPrimitive("书一"))), r)
    }

    @Test
    fun `jsonPath 全支皆错时抛错`() {
        // 两支的路径下标都放了非法内容（后端对残缺下标类型化拒绝），且 || 在深度 0 能正常切分。
        // 不能用 `$.a!` 这种裸键带非法字符的形态：readIdent 把 `!` 吸收成键名后查空是 Miss 不是抛，
        // 全支 Miss 会被 firstOf 原样放行，测不出「全支皆错抛最后那个」的 §3.3 语义
        val e = runCatching {
            ScriptRuleEvaluator(EvalContext()).evaluate("$.a[1x]||$.b[2y]", RuleValue.Json(Json.parseToJsonElement(jsonDoc)))
        }.exceptionOrNull()
        assertTrue("实际：${e?.let { it::class.simpleName }}", e is RuleSyntaxException)
        // 抛出的是**最后一支**的错误（firstOf 只留 lastError），与链式规则的同类用例同口径
        assertEquals("$.b[2y]", (e as RuleSyntaxException).rule)
    }

    @Test
    fun `双与号合并两路 json 结果`() {
        val r = ScriptRuleEvaluator(EvalContext()).evaluate(
            "$.books[0].name&&$.books[1].name",
            RuleValue.Json(Json.parseToJsonElement(jsonDoc)),
        )
        assertEquals(RuleResult.Jsons(listOf(JsonPrimitive("书一"), JsonPrimitive("书二"))), r)
    }

    @Test
    fun `百分号按序交错两路 json 结果`() {
        // 与 HTML 侧同名用例同口径：先各取第 1 个，再各取第 2 个——
        // 钉住 interleave 的 Jsons 分支走的是 round-robin 而不是 flatMap 拼接
        val r = ScriptRuleEvaluator(EvalContext()).evaluate(
            "$.books[*].name%%$.books[*].kind",
            RuleValue.Json(Json.parseToJsonElement(jsonDoc)),
        )
        assertEquals(
            RuleResult.Jsons(listOf(JsonPrimitive("书一"), JsonPrimitive("玄幻"), JsonPrimitive("书二"), JsonPrimitive("都市"))),
            r,
        )
    }

    @Test
    fun `列表条目按子字段逐条求值`() {
        val ev = ScriptRuleEvaluator(EvalContext())
        val books = Json.parseToJsonElement(jsonDoc).jsonObject["books"]!!.jsonArray
        val names = books.map { (ev.evaluateOnJsonItem("$.name", it) as RuleResult.Jsons).items.first().jsonText() }
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

    @Test
    fun `规则级选项尾段回附到结果文本`() {
        val r = ScriptRuleEvaluator(EvalContext()).evaluate(
            """tag.a@href,{"charset":"gbk"}""",
            RuleValue.Page("""<html><body><a href="/x/1.html">章</a></body></html>"""),
        )
        assertEquals(RuleResult.Texts(listOf("""/x/1.html,{"charset":"gbk"}""")), r)
    }

    @Test
    fun `选项尾段内的占位符在使用点回填`() {
        // 尾段回填发生在求值器末段（§9 第 7 步）：选项体里的 {{key}} 必须在回附前替换掉——
        // 取文层拿到的是已回填 URL（ScriptUrlOption 的 KDoc 契约），漏替换就把占位符带进请求层
        val ctx = EvalContext().apply { key = "玄幻" }
        val r = ScriptRuleEvaluator(ctx).evaluate(
            """tag.a@href,{"charset":"gbk","body":"kw={{key}}"}""",
            RuleValue.Page("""<html><body><a href="/x/1.html">章</a></body></html>"""),
        )
        assertEquals(
            RuleResult.Texts(listOf("""/x/1.html,{"charset":"gbk","body":"kw=玄幻"}""")),
            r,
        )
    }
}
