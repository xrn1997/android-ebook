package com.ebook.source.script

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 规格 §6.4 页码形态、§6.5 落位、§5.1 的 URL 插值与「展开值不参与切分」。 */
class ScriptUrlResolverTest {

    private fun resolve(
        rule: String,
        ctx: EvalContext = EvalContext(baseUrl = "https://root.com/dir/page.html", page = 1),
        sourceRoot: String = "https://root.com",
    ): ResolvedScriptUrl = ScriptUrlResolver.resolve(rule, ctx, sourceRoot) { RuleResult.Miss }

    @Test
    fun `裸 URL 原样落位为默认选项`() {
        val r = resolve("https://x.com/search")
        assertEquals("https://x.com/search", r.url)
        assertEquals(ScriptUrlOptions.DEFAULT, r.options)
    }

    @Test
    fun `内置量与变量展开进 URL`() {
        val ctx = EvalContext(baseUrl = "https://root.com", key = "凡人", page = 3)
        ctx.variables["enc"] = "gbk"
        val r = resolve("https://x.com/s?key={{key}}&page={{page}}&enc={{enc}}", ctx)
        assertEquals("https://x.com/s?key=凡人&page=3&enc=gbk", r.url)
    }

    @Test
    fun `展开值含分隔符不炸 URL 组装`() {
        // §5.1 本仓规定的回归锁：关键词含 & 与 || 时，占位符机制保证它不被当结构切掉
        val ctx = EvalContext(baseUrl = "https://root.com", key = "a&b||c")
        val r = resolve("https://x.com/s?key={{key}}&page={{page}}", ctx)
        assertEquals("https://x.com/s?key=a&b||c&page=1", r.url)
    }

    @Test
    fun `尾段里的插值一并展开`() {
        // §5.1：语料实证 "body":"page={{page}}&key={{key}}"
        val ctx = EvalContext(baseUrl = "https://root.com", key = "凡人", page = 2)
        val r = resolve("""https://x.com/s,{"method":"POST","body":"page={{page}}&key={{key}}"}""", ctx)
        assertEquals("POST", r.options.method)
        assertEquals("page=2&key=凡人", r.options.body)
    }

    @Test
    fun `算术页码表达式维持 JS 待执行`() {
        // {{(page-1)*20}} 是 JS（Plan 3）；2c 不得绕过 Interpolation 自己算页码（§6.4/2b 边界）
        // Plan 3 接线后这条仍然成立：本用例的 ctx 未装配沙箱（`ctx.js` 为 null 是唯一判据）
        val e = runCatching { resolve("https://x.com/list/{{(page-1)*20}}") }.exceptionOrNull()
        assertTrue(e is JsEvaluationPendingException)
    }

    @Test
    fun `尖括号形态页码1整段不进URL`() {
        val r = resolve("https://x.com/list<,{{page}}>.html", EvalContext(baseUrl = "https://root.com", page = 1))
        assertEquals("https://x.com/list.html", r.url)
    }

    @Test
    fun `尖括号形态页码大于1输出分隔符与内容`() {
        val r = resolve("https://x.com/list<,{{page}}>.html", EvalContext(baseUrl = "https://root.com", page = 2))
        assertEquals("https://x.com/list2.html", r.url)
        val r2 = resolve("https://x.com/search<,&page={{page}}>", EvalContext(baseUrl = "https://root.com", page = 3))
        assertEquals("https://x.com/search&page=3", r2.url)
    }

    @Test
    fun `尖括号形态配选项尾段时两段各归各`() {
        val r = resolve(
            """https://x.com/list<,{{page}}>.html,{"charset":"gbk"}""",
            EvalContext(baseUrl = "https://root.com", page = 1),
        )
        assertEquals("https://x.com/list.html", r.url)
        assertEquals("gbk", r.options.charset)
    }

    @Test
    fun `不带逗号的尖括号段不是页码形态原样保留`() {
        // §6.4：不得自行发明 <...> 的其它用法——没有「分隔符,内容」结构就不当页码段处理
        val r = resolve("https://x.com/a<b>c")
        assertEquals("https://x.com/a<b>c", r.url)
    }

    @Test
    fun `展开值里的尖括号形态不被当页码段`() {
        // 页码段在占位符态判形（§5.1 同口径）：关键词展开值里的 <,b> 是数据不是结构
        val ctx = EvalContext(baseUrl = "https://root.com", key = "a<,b>", page = 1)
        assertEquals("https://x.com/a<,b>.html", resolve("https://x.com/{{key}}<,{{page}}>.html", ctx).url)
    }

    @Test
    fun `展开值完整存活时页码段照常取舍`() {
        // <,{{page}}> 的分隔符是段内首逗号之前的空串，页 2 只输出回填后的内容
        val ctx = EvalContext(baseUrl = "https://root.com", key = "a<,b>", page = 2)
        assertEquals("https://x.com/a<,b>2.html", resolve("https://x.com/{{key}}<,{{page}}>.html", ctx).url)
    }

    @Test
    fun `未闭合的尖括号整段原样保留`() {
        // 残缺形态是真实输入：找不到 > 就不判页码段，占位符照常回填
        val r = resolve("https://x.com/list<,{{page}}.html", EvalContext(baseUrl = "https://root.com", page = 2))
        assertEquals("https://x.com/list<,2.html", r.url)
    }

    @Test
    fun `多个页码段逐段取舍`() {
        val rule = "https://x.com/list<,&p={{page}}><,&s=1>"
        assertEquals("https://x.com/list", resolve(rule, EvalContext(baseUrl = "https://root.com", page = 1)).url)
        assertEquals("https://x.com/list&p=3&s=1", resolve(rule, EvalContext(baseUrl = "https://root.com", page = 3)).url)
    }

    @Test
    fun `相对地址按三形态落位`() {
        // §6.5 与 §12：复用 TocPageUrl.join 的语义，绝对原样 / / 相对源根 / 其余相对当前页目录
        assertEquals("https://root.com/x/1", resolve("/x/1", EvalContext(baseUrl = "https://root.com")).url)
        assertEquals(
            "https://root.com/dir/index_2.html",
            resolve("index_2.html", EvalContext(baseUrl = "https://root.com/dir/page.html")).url,
        )
    }

    // ── 整条写成 JS 的 URL 规则（§6.1「复杂 URL 可整条写成 JS」）──────────────────────────
    // 语料实证：1168 条源里 282 条 URL 位规则以 `@js:` 开头、70 条以 `<js>` 开头。
    // 未接线前的症状不是报错，而是把脚本原文当相对地址拼到源根后**真的发出去**
    // （回放日志里全是 `https://host/@js: var k = …` 的 403/404），比崩溃更难发现。

    private val jsRule = "@js:\nvar u = \"https://x.com/s?key=\" + encodeURIComponent(key);\nu"

    private fun resolveJsProducing(
        produced: String,
        rule: String = jsRule,
        page: Int = 1,
    ): ResolvedScriptUrl = ScriptUrlResolver.resolve(
        rule,
        EvalContext(baseUrl = "https://root.com/dir/page.html", key = "凡人", page = page),
        "https://root.com",
    ) { RuleResult.Texts(listOf(produced)) }

    @Test
    fun `整条 js 的 url 规则交求值器而不是当地址拼出去`() {
        val seen = mutableListOf<String>()
        val ctx = EvalContext(baseUrl = "https://root.com/dir/page.html", key = "凡人")
        val r = ScriptUrlResolver.resolve(jsRule, ctx, "https://root.com") { inner ->
            seen += inner
            RuleResult.Texts(listOf("https://x.com/s?key=%E5%87%A1%E4%BA%BA"))
        }
        assertEquals("https://x.com/s?key=%E5%87%A1%E4%BA%BA", r.url)
        // 交回求值器的是**原样规则串**：剥标志、`{{}}` 展开与真求值都归求值器与沙箱。
        // 本层重走一遍就是两套事实源，而且 double-expand 会让 `{{java.put(…)}}` 这类副作用跑两次。
        assertEquals(listOf(jsRule), seen)
    }

    @Test
    fun `串首尖括号 js 标记同样算整条 js`() {
        val r = resolveJsProducing(
            "https://x.com/s",
            rule = "<js> java.ajax(source.key); 'https://x.com/s' </js>",
        )
        assertEquals("https://x.com/s", r.url)
    }

    @Test
    fun `js 产出的选项尾段照常解析`() {
        // 语料实证：JS 自己拼出 `url + ',{"headers":{…}}'`（霹雳书屋、哔哩轻小说），选项在产出之后才存在
        val r = resolveJsProducing("""https://x.com/s,{"method":"POST","body":"k=1","charset":"gbk"}""")
        assertEquals("https://x.com/s", r.url)
        assertEquals("POST", r.options.method)
        assertEquals("k=1", r.options.body)
        assertEquals("gbk", r.options.charset)
    }

    @Test
    fun `js 产出的相对地址按同一套三形态落位`() {
        assertEquals("https://root.com/search", resolveJsProducing("/search").url)
        assertEquals("https://root.com/dir/a.html", resolveJsProducing("a.html").url)
    }

    @Test
    fun `js 产出里的尖括号页码形态不再二次取舍`() {
        // §6.4 的取舍只在占位符态做：JS 产出是**数据**，里面的 `<,2>` 不是作者写的页码段
        assertEquals("https://x.com/list<,2>", resolveJsProducing("https://x.com/list<,2>", page = 3).url)
    }

    @Test
    fun `js 没给出地址按执行失败报而不是退回源根`() {
        // 空产出若一路走到 join，请求会落在源首页：那是「看着正常、实则错到底」的内容（§9 末段）
        val error = runCatching { resolveJsProducing("  ") }.exceptionOrNull()
        assertTrue("实际：${error?.let { it::class.simpleName }}", error is JsExecutionFailedException)
    }

    @Test
    fun `未装配沙箱时 js 的 url 规则仍抛待执行而不是发请求`() {
        // 口径（b）：「本机没有沙箱」与「脚本跑坏了」不得合并成一句提示（§9）
        val ctx = EvalContext(baseUrl = "https://root.com", key = "凡人")
        val error = runCatching {
            ScriptUrlResolver.resolve(jsRule, ctx, "https://root.com") { inner ->
                ScriptRuleEvaluator(ctx).evaluate(inner, RuleValue.Page(""))
            }
        }.exceptionOrNull()
        assertTrue("实际：${error?.let { it::class.simpleName }}", error is JsEvaluationPendingException)
    }

    @Test
    fun `js 标志不在串首时不算整条 js 规则`() {
        // 判据只看串首，不用 contains：`@js:`/`<js>` 作为查询参数值出现是合法 URL，
        // 拿 contains 判会把一条普通搜索地址换成 JS 分支，症状是「搜索永远没结果」而不报错
        var called = false
        val r = ScriptUrlResolver.resolve(
            "https://x.com/s?u=a@js:b",
            EvalContext(baseUrl = "https://root.com"),
            "https://root.com",
        ) { called = true; RuleResult.Texts(listOf("https://evil.com/")) }
        assertTrue("不该走 JS 分支", !called)
        assertEquals("https://x.com/s?u=a@js:b", r.url)
    }
}
