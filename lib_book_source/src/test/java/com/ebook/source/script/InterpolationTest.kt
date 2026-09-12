package com.ebook.source.script

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 规格 §5.1/§5.3 的声明式子集，与 §5.1 的本仓规定「展开出的文本不再参与分隔符切分」。
 *
 * 每条用例都端到端跑（而不是单测 `Interpolation.expand`）：占位符这条链路的价值恰恰在
 * 「切分只看占位符、载荷在使用点回填」这个**次序**上，只测展开函数本身会把次序测没。
 */
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
        // 不展开时 `class.{{cls}}` 会按类名 `{{cls}}` 去选，永远选不中 → Miss
        assertEquals(RuleResult.Texts(listOf("书名A")), eval("class.{{cls}}@text", ctx))
    }

    @Test
    fun `插值可以出现在替换段的模式与文本位置`() {
        // 模式段与替换文本段各放一个内置量：只在其中一处回填的话，另一处会留字面量 `{{…}}`
        val ctx = EvalContext(key = "3", page = 9)
        assertEquals(RuleResult.Texts(listOf("凡人 9 章")), eval("tag.p@text##{{key}}##{{page}}", ctx))
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
        assertTrue("实际：${e?.let { it::class.simpleName }}", e is JsEvaluationPendingException)
    }

    @Test
    fun `book 属性路径留到 2d 灌入而不是现在猜`() {
        // 形态上是合法标识符，但它的值住在 Room 里，本段禁止碰持久层（§5.3 的 book 绑定属 JS 侧）
        val e = runCatching { eval("tag.p@text##3##{{book.name}}") }.exceptionOrNull()
        assertTrue("实际：${e?.let { it::class.simpleName }}", e is JsEvaluationPendingException)
    }

    @Test
    fun `装上桥之后声明式子集解不了的交给桥而不是抛待执行`() {
        // 上面三条锁的是「本机没装配沙箱」的路径；这一条锁对偶：有桥时同一条表达式真求值
        val seen = mutableListOf<String>()
        val bridge = object : ScriptJsBridge {
            override fun runExpression(expr: String): String {
                seen += expr
                return "40"
            }

            override fun runSegment(source: String, input: RuleValue): RuleResult = error("不该走到")
            override fun runUrlJs(script: String, url: String, headers: Map<String, String>): JsRewrittenUrl = error("不该走到")
            override fun runBodyJs(script: String, url: String, body: String): String = error("不该走到")
            override fun runInit(script: String, input: RuleValue): Map<String, String> = error("不该走到")
        }
        val ctx = EvalContext(key = "读者", page = 3).apply { js = bridge }
        assertEquals(RuleResult.Texts(listOf("凡人 40 章")), eval("tag.p@text##3##{{(page-1)*20}}", ctx))
        assertEquals(RuleResult.Texts(listOf("凡人 40 章")), eval("tag.p@text##3##{{book.name}}", ctx))
        assertEquals(listOf("(page-1)*20", "book.name"), seen)
        // 内置量与变量表照旧就地解决：`{{key}}` 没有理由为了一个字符串去趟沙箱
        assertEquals(RuleResult.Texts(listOf("凡人 读者 章")), eval("tag.p@text##3##{{key}}", ctx))
        assertEquals("内置量不该惊动沙箱", 2, seen.size)
    }

    @Test
    fun `大写标志的规则插值同样不惊动沙箱`() {
        // §5.1「可写任意规则，但必须带标志头」× §2.1「标志键不区分大小写」：
        // 插值层的标志表若比 RuleMode 少认大写形态，`{{@CSS:…}}` 就会被当 JS 送进内核，
        // 拿到的是内核的 `expecting ';'` 语法错——报出来像「脚本写坏了」，实则词法漏判。
        val bridge = object : ScriptJsBridge {
            override fun runExpression(expr: String): String = error("带标志头的插值不该交给沙箱：$expr")
            override fun runSegment(source: String, input: RuleValue): RuleResult = error("不该走到")
            override fun runUrlJs(script: String, url: String, headers: Map<String, String>): JsRewrittenUrl = error("不该走到")
            override fun runBodyJs(script: String, url: String, body: String): String = error("不该走到")
            override fun runInit(script: String, input: RuleValue): Map<String, String> = error("不该走到")
        }
        val ctx = EvalContext().apply { js = bridge }
        assertEquals(RuleResult.Texts(listOf("凡人 书名A 章")), eval("tag.p@text##3##{{@CSS:.name@text}}", ctx))
    }

    @Test
    fun `未闭合的双花括号原样保留`() {
        // 残缺形态不该抛，也不该吞掉后半串：留着它最坏是选不中，抛出去会让整条规则作废
        assertEquals(RuleResult.Miss, eval("class.{{x@text"))
        // 同串里「先残缺后合法」时也要各归各：前一段按字面量留着，后一段照常展开。
        // 少了这道判定，前一个未闭合的 `{{` 会把后一条合法插值一并拖成待执行异常
        val ctx = EvalContext(key = "读者")
        assertEquals(
            RuleResult.Texts(listOf("凡人 {{x 读者 章")),
            eval("tag.p@text##3##{{x {{key}}", ctx),
        )
    }
}
