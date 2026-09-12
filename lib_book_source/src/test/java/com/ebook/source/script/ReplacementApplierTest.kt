package com.ebook.source.script

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 规格 §2.4 与 §9 第 6 步：净化循环替换全部，OnlyOne 只替第一个；替换文本支持 `$n` 组引用。
 *
 * 替换的两形态是**值级**行为（作用于已取到的文本，不参与选择器求值），所以绝大多数用例
 * 直接对 `ReplacementApplier` 断言，端到端只留一条证明「切分→取数→替换」这条链接上了。
 *
 * [applyAll] 传的 `rule` 是为错误消息准备的占位串（真实调用点给的是原始规则串），
 * 因此**非法正则那条用例刻意走端到端**，断言的是真实规则串出现在消息里。
 */
class ReplacementApplierTest {

    // 噪声词前后都留空格：`\s*` 若没吃掉前导空白，替换结果会多出双空格，断言就能看出来
    private val page = RuleValue.Page(
        """<html><body><p id="x">第一章 全文阅读 手机访问</p><p>甲</p><p>乙</p></body></html>""",
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
        // 同一组入参只差 onlyFirst：这条对照把「两种形态的唯一区别就是命中个数」钉住，
        // 否则把 true 改成 false（或反过来）本用例照样能过
        assertEquals(listOf("bAnAnA"), applyAll(listOf("banana"), "a", "A", false))
    }

    @Test
    fun `替换作用在每一个已取到的值上而不是只第一个值`() {
        assertEquals(listOf("XXX", "bbb"), applyAll(listOf("aaa", "bbb"), "a", "X", false))
    }

    @Test
    fun `替换文本里的组引用生效`() {
        // 模式刻意写惰性 `.*?`：计划的 `.*(\d+)` 在 "no-12" 上按贪婪匹配把组让给最后一个数字，
        // 解出来是 "x2" 而不是 "x12"（§2.4 的正则方言明示含惰性量词 `.*?`，两种写法都合法，
        // 但只有惰性这条锁得住「组引用被真的展开」而不是碰巧剩个数字）
        assertEquals(listOf("x12"), applyAll(listOf("no-12"), ".*?(\\d+)", "x\$1", false))
        // 语料实证形态 `##/book/(\d+)##https://img.x.com/bookpic/s$1.jpg###`（§2.4）：
        // OnlyOne 这一支同样要能引用捕获组，否则详情页封面图 URL 的惯用写法就废了
        assertEquals(listOf("x12-34"), applyAll(listOf("12-34"), "(\\d+)", "x\$1", true))
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
        // 消息带出**原始规则串**（不是切出来的模式段），用户才对得上是哪条规则写坏了
        assertTrue("实际消息：${e?.message}", e!!.message!!.contains("id.x@text##(["))
    }

    @Test
    fun `节点集结果遇到替换段先收敛成文本`() {
        // 写了替换却没带取值器，说明作者要的是清洗后的文本；返回节点集会让更多替换无处可施
        val r = ScriptRuleEvaluator(EvalContext()).evaluate("tag.p##<[^>]+>##", page)
        assertEquals(RuleResult.Texts(listOf("第一章 全文阅读 手机访问", "甲", "乙")), r)
    }

    @Test
    fun `AllInOne 条目的组结构不被替换段压平`() {
        // §2.4 把 AllInOne 排除在 OnlyOne/净化的适用场景之外，§2.3 又说明「正则段内的 #
        // 不参与 ## 判定」，所以两者同串是写坏了的规则，不是可以猜的形态：给出类型化失败，
        // 而不是把条目×捕获组压成一维文本（那正是 Task 4 立起来的结构）
        val e = runCatching {
            ReplacementApplier.apply(
                RuleResult.Matches(listOf(listOf("<li>甲</li>", "甲"))),
                RegexReplacement("甲", "乙", false),
                ":<li>(甲)</li>##甲##乙",
            )
        }.exceptionOrNull()
        assertTrue("实际：${e?.let { it::class.simpleName }}", e is UnsupportedRuleFeatureException)
    }
}
