package com.ebook.source.script

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 规格 §6.2 选项键的实现集与拒绝集、§6.3 body 形态。 */
class ScriptUrlOptionTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun parse(tail: String) = ScriptUrlOption.parse(json.parseToJsonElement(tail).jsonObject, "rule")

    @Test
    fun `完整选项解析`() {
        val o = parse("""{"method":"POST","charset":"gbk","body":"bid=1","timeout":5000,"retry":2}""")
        assertEquals("POST", o.method)
        assertEquals("gbk", o.charset)
        assertEquals("bid=1", o.body)
        assertEquals(5000L, o.timeoutMs)
        assertEquals(2, o.retry)
    }

    @Test
    fun `缺省值与空尾段`() {
        val o = parse("{}")
        assertEquals("GET", o.method)
        assertEquals("UTF-8", o.charset)
        assertNull(o.body)
        assertNull(o.timeoutMs)
        assertEquals(0, o.retry)
        assertEquals(emptyMap<String, String>(), o.headers)
    }

    @Test
    fun `headers 嵌套对象与字符串化 JSON 两种形态`() {
        val nested = parse("""{"headers":{"User-Agent":"UA/1","Accept":"text/html"}}""")
        assertEquals(mapOf("User-Agent" to "UA/1", "Accept" to "text/html"), nested.headers)
        val stringified = parse("""{"headers":"{\"User-Agent\":\"UA/2\"}"}""")
        assertEquals(mapOf("User-Agent" to "UA/2"), stringified.headers)
    }

    @Test
    fun `headers 字符串化形态不是合法 JSON 对象时判语法错误`() {
        val e = runCatching { parse("""{"headers":"not-json"}""") }.exceptionOrNull()
        assertTrue(e is RuleSyntaxException)
    }

    @Test
    fun `body 对象按 JSON 文本序列化`() {
        // §6.3 本仓规定：body 只接受字符串；给到对象/数组按 JSON 文本序列化（§11-6 跟踪）
        val o = parse("""{"body":{"a":1,"b":["x"]}}""")
        assertEquals("""{"a":1,"b":["x"]}""", o.body)
    }

    @Test
    fun `webView 与代理一族类型化拒绝`() {
        assertTrue(runCatching { parse("""{"webView":true}""") }.exceptionOrNull() is UnsupportedRuleFeatureException)
        assertTrue(runCatching { parse("""{"proxy":"socks5://127.0.0.1:1080"}""") }.exceptionOrNull() is UnsupportedRuleFeatureException)
        assertTrue(runCatching { parse("""{"dnsIp":"1.1.1.1"}""") }.exceptionOrNull() is UnsupportedRuleFeatureException)
        assertTrue(runCatching { parse("""{"followRedirects":false}""") }.exceptionOrNull() is UnsupportedRuleFeatureException)
        // 非空原始值一律算声明：对象/数组形态的值也是作者写下的意图（declared 的 else 分支）
        assertTrue(runCatching { parse("""{"webView":{}}""") }.exceptionOrNull() is UnsupportedRuleFeatureException)
    }

    @Test
    fun `headers 数组形态按语法错误拒绝`() {
        // 字符串化形态解析出 JsonArray 时不能静默当空 headers——映射目标不是数组形态
        assertTrue(runCatching { parse("""{"headers":"[]"}""") }.exceptionOrNull() is RuleSyntaxException)
    }

    @Test
    fun `零与负数 timeout 按语法错误拒绝`() {
        // 0 在底层超时语义里是「永不超时」、负数会让传输层抛非类型化异常——
        // 与 charset 同口径：作者写错就是规则值错误，如实报而不是静默翻转语义
        assertTrue(runCatching { parse("""{"timeout":0}""") }.exceptionOrNull() is RuleSyntaxException)
        assertTrue(runCatching { parse("""{"timeout":-5000}""") }.exceptionOrNull() is RuleSyntaxException)
    }

    @Test
    fun `负数 retry 按语法错误拒绝`() {
        // 与 timeout 同口径：可解析但为负就是规则值写错了，如实报。不在解析层挡下的话，
        // 它会一路走到 OkHttpScriptTransport 的 coerceAtLeast(0) 被静默纠正成 0，
        // 作者永远不知道自己的重试次数没生效。
        assertTrue(runCatching { parse("""{"retry":-1}""") }.exceptionOrNull() is RuleSyntaxException)
        // 缺省与非数值仍按「未给」读作 0（与 timeout 的 toLongOrNull 同一容错读法）
        assertEquals(0, parse("""{"retry":"many"}""").retry)
    }

    @Test
    fun `选项内 js 与 bodyJs 只携带不执行`() {
        // 选项层只识别不执行（关键事实 5）：解析后带在身上，执行在取文层
        assertEquals("java.ajax('x')", parse("""{"js":"java.ajax('x')"}""").js)
        assertEquals("result", parse("""{"bodyJs":"result"}""").bodyJs)
        assertNull(parse("""{"js":""}""").js)      // 空串=未声明（§1.1 默认列口径）
        assertNull(parse("""{"js":""}""").bodyJs)
    }

    @Test
    fun `空串与缺省的拒绝键不算声明`() {
        // 空值 = 未配置（§1.1 默认列口径）：报「不支持」只该发生在作者真配了能力的源上
        val o = parse("""{"webView":"","js":"","type":"epub"}""")
        assertEquals("GET", o.method)
    }

    @Test
    fun `未知键忽略`() {
        // 格式允许未知键存在（原始入库零翻译），选项对象同理：不认识的键不是错误
        val o = parse("""{"someFutureKey":1,"method":"POST"}""")
        assertEquals("POST", o.method)
    }

    @Test
    fun `splitTail 在已回填 URL 上定位尾段`() {
        val (url, tail) = ScriptUrlOption.splitTail("""https://x.com,{"charset":"gbk"}""")!!
        assertEquals("https://x.com", url)
        assertEquals(""",{"charset":"gbk"}""", tail)
        assertNull(ScriptUrlOption.splitTail("https://x.com/plain"))
        // 回填值里的转义引号与花括号也兜得住：引号感知的扫描器不让值里的结构干扰定位
        val (u2, t2) = ScriptUrlOption.splitTail("""https://x.com,{"body":"a\",\"b}"}""")!!
        assertEquals("https://x.com", u2)
        assertTrue(t2.startsWith(","))
    }

    /**
     * 把选项尾段拼到结果后面的惯用法是 `##$##,{...}`——**第二个 `##` 之后带逗号**。
     *
     * 本用例走端到端（替换 → 尾段定位），因为两段分开各自都有测试、合起来却没人验：
     * 单看 [ReplacementApplier] 只知「替换成了什么」，单看 [ScriptUrlOption.splitTail] 只知
     * 「什么形态认得出」，而这条惯用法成不成立恰恰取决于「替换产出的形态正好是后者认得的那个」。
     */
    @Test
    fun `选项尾段拼到结果后面的惯用法靠替换文本带出逗号，取文层才认得出`() {
        val filled = applyIdiom("""##$##,{"charset":"gbk"}""")
        assertEquals("""https://x.com/1,{"charset":"gbk"}""", filled)
        val (url, tail) = ScriptUrlOption.splitTail(filled)!!
        assertEquals("https://x.com/1", url)
        assertEquals(""",{"charset":"gbk"}""", tail)

        // 不带逗号时那个 `{"..."}` 会被当作 URL 正文留在地址里，尾段定位认不出来。
        // **本断言固定的是现状、不是期望**：若将来决定支持无逗号形态（前提是拿到上游文档核对该写法
        // 究竟有无逗号），这里连同实现一起改；在那之前它的作用是让「静默产出带花括号的坏地址」
        // 这件事在任何人动这条链路时立刻可见——而不是跟着一次无心的重构悄悄漂移。
        val noComma = applyIdiom("""##$##{"charset":"gbk"}""")
        assertEquals("""https://x.com/1{"charset":"gbk"}""", noComma)
        assertNull(ScriptUrlOption.splitTail(noComma))
    }

    /** 走一次真实链路：解析 `##` 替换段 → 对一条 URL 文本施加替换，返回替换后的值 */
    private fun applyIdiom(ruleTail: String): String {
        val replacement = requireNotNull(RegexReplacement.parse(ruleTail)) { "替换段解析失败：$ruleTail" }
        val applied = ReplacementApplier.apply(
            RuleResult.Texts(listOf("https://x.com/1")),
            replacement,
            "x@href$ruleTail",
        )
        return (applied as RuleResult.Texts).values.single()
    }
}
