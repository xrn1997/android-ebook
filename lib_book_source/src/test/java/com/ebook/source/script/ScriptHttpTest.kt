package com.ebook.source.script

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 取文链路的组装：URL 规则串 → ScriptRequest；传输接缝用假件断言字段。 */
class ScriptHttpTest {

    private class RecordingTransport : ScriptTransport {
        val requests = mutableListOf<ScriptRequest>()
        var response: String = "<html>ok</html>"
        override suspend fun execute(request: ScriptRequest): String {
            requests += request
            return response
        }
    }

    private fun fetcher(transport: RecordingTransport) =
        ScriptPageFetcher(okHttpClient = null, sourceRoot = "https://root.com", transport = transport)

    @Test
    fun `无尾段 URL 走默认 GET`() = runTest {
        val transport = RecordingTransport()
        fetcher(transport).fetch("https://x.com/search", EvalContext()) { RuleResult.Miss }
        assertEquals(listOf("GET"), transport.requests.map { it.method })
        assertEquals("UTF-8", transport.requests.single().charset)
        assertEquals("https://x.com/search", transport.requests.single().url)
    }

    @Test
    fun `POST 选项完整进入请求`() = runTest {
        val transport = RecordingTransport()
        fetcher(transport).fetch(
            """https://x.com,{"method":"POST","body":"k={{key}}","headers":{"Content-Type":"application/x-www-form-urlencoded"},"timeout":4000,"retry":2}""",
            EvalContext(key = "凡人"),
        ) { RuleResult.Miss }
        val r = transport.requests.single()
        assertEquals("POST", r.method)
        assertEquals("k=凡人", r.body)
        assertEquals("application/x-www-form-urlencoded", r.headers["Content-Type"])
        assertEquals(4000L, r.timeoutMs)
        assertEquals(2, r.retry)
    }

    @Test
    fun `不支持选项在组装期就类型化拒绝`() = runTest {
        val e = runCatching {
            fetcher(RecordingTransport()).fetch("""https://x.com,{"webView":true}""", EvalContext()) { RuleResult.Miss }
        }.exceptionOrNull()
        assertTrue(e is UnsupportedRuleFeatureException)
    }

    @Test
    fun `返回值即响应文本`() = runTest {
        val transport = RecordingTransport().apply { response = "正文内容" }
        val text = fetcher(transport).fetch("https://x.com/book/1", EvalContext()) { RuleResult.Miss }
        assertEquals("正文内容", text)
    }

    @Test
    fun `fetchPage 回传请求落点地址供相对落位使用`() = runTest {
        val transport = RecordingTransport()
        val page = fetcher(transport).fetchPage("/dir/page.html", EvalContext(baseUrl = "https://root.com")) { RuleResult.Miss }
        assertEquals("https://root.com/dir/page.html", page.url)
    }

    @Test
    fun `js 选项先改写 URL 再请求`() = runTest {
        val transport = RecordingTransport()
        val ctx = EvalContext(baseUrl = "https://root.com/")
        val bridge = RecordingBridge(urlJs = "https://root.com/t?a=1")
        ctx.js = bridge
        val page = fetcher(transport).fetchPage("""https://root.com/t,{"js":"url = url + '?a=1'"}""", ctx) { RuleResult.Miss }
        assertEquals(listOf("https://root.com/t?a=1"), transport.requests.map { it.url })
        assertEquals("https://root.com/t?a=1", page.url)
        // 次序锁：改写发生在请求之前，且送进去的是解析后的绝对地址
        assertEquals(listOf("js"), bridge.calls)
    }

    @Test
    fun `bodyJs 拿到的是响应体原文、回的是新文本`() = runTest {
        val transport = RecordingTransport().apply { response = "原始正文<广告>" }
        val ctx = EvalContext(baseUrl = "https://root.com/")
        val bridge = RecordingBridge(bodyJsResult = "净化后")
        ctx.js = bridge
        val page = fetcher(transport)
            .fetchPage("""https://root.com/ch,{"bodyJs":"result = '净化后'"}""", ctx) { RuleResult.Miss }
        assertEquals("净化后", page.text)
        assertTrue(bridge.calls.single(), bridge.calls.single().contains("原始正文<广告>"))
    }

    @Test
    fun `未装配沙箱时 js 选项仍按待执行如实报且不发请求`() = runTest {
        val transport = RecordingTransport()
        val error = runCatching {
            fetcher(transport).fetchPage("""https://root.com/t,{"js":"url"}""", EvalContext()) { RuleResult.Miss }
        }.exceptionOrNull()
        assertTrue("实际：${error?.let { it::class.simpleName }}", error is JsEvaluationPendingException)
        // 关键事实 5 的副作用口径：没有沙箱就一个字节也不该发出去，与 2d 同形
        assertTrue(transport.requests.isEmpty())
    }

    @Test
    fun `整条 js 的 url 规则经求值器算出地址再发请求`() = runTest {
        // 上面 ScriptUrlResolverTest 锁的是本层判据，这里锁的是**接线**：求值器、桥、取文层
        // 三方串起来后 `@js:` 真的能算出一个地址并发出去（假件若漏接一环就是发一个垃圾地址）
        val transport = RecordingTransport()
        val ctx = EvalContext(baseUrl = "https://root.com/", key = "凡人")
        val bridge = SegmentBridge("https://x.com/s?key=%E5%87%A1%E4%BA%BA")
        ctx.js = bridge
        val evaluator = ScriptRuleEvaluator(ctx)
        val page = fetcher(transport).fetchPage("@js:\n'https://x.com/s?key=' + enc", ctx) { inner ->
            evaluator.evaluate(inner, RuleValue.Page(""))
        }
        assertEquals(listOf("https://x.com/s?key=%E5%87%A1%E4%BA%BA"), transport.requests.map { it.url })
        assertEquals("落点地址回传的是 js 算出的那个", page.url, transport.requests.single().url)
        assertEquals(listOf("@js: 段"), bridge.calls)
    }

    @Test
    fun `js 拼出的 webView 选项照旧在组装期拒绝且不发请求`() = runTest {
        // 语料实证：霹雳书屋的 URL 规则产出 `url + ',{"webView":true,"webJs":…}'`。
        // 选项由 JS 拼出来不代表它能被满足——拒绝必须发生在发请求之前，否则又是一次垃圾外呼
        val transport = RecordingTransport()
        val error = runCatching {
            fetcher(transport).fetchPage(
                "@js:\nurl + ',{\"webView\":true}'",
                EvalContext(baseUrl = "https://root.com/"),
            ) { RuleResult.Texts(listOf("""https://x.com/s,{"webView":true}""")) }
        }.exceptionOrNull()
        assertTrue("实际：${error?.let { it::class.simpleName }}", error is UnsupportedRuleFeatureException)
        assertTrue(transport.requests.isEmpty())
    }
}

/** 只答 `@js:` 段一问的桥，用来验取文层的接线。 */
private class SegmentBridge(private val produced: String) : ScriptJsBridge {
    val calls = mutableListOf<String>()

    override fun runSegment(source: String, input: RuleValue): RuleResult {
        calls += "@js: 段"
        return RuleResult.Texts(listOf(produced))
    }

    override fun runExpression(expr: String): String = error("取文层不该跑表达式")
    override fun runUrlJs(script: String, url: String, headers: Map<String, String>): JsRewrittenUrl = error("不该跑 js 选项")
    override fun runBodyJs(script: String, url: String, body: String): String = error("不该跑 bodyJs")
    override fun runInit(script: String, input: RuleValue): Map<String, String> = error("不该跑 init")
}

/**
 * 只答 `js`/`bodyJs` 两问的桥。其余三个入口 `error(...)` 而不是返回空值——
 * 本类锁的是「URL 规则本身没有 JS、只有选项带 js/bodyJs」这条普通路径，
 * 于是取文层若多调一个入口就当场炸出来，而不是悄悄拿一个默认值跑绿。
 * 「整条 URL 写成 JS」那条路径要跑 `runSegment`，用上面那个 [SegmentBridge]。
 */
private class RecordingBridge(
    val urlJs: String? = null,
    val bodyJsResult: String? = null,
) : ScriptJsBridge {
    val calls = mutableListOf<String>()

    override fun runUrlJs(script: String, url: String, headers: Map<String, String>): JsRewrittenUrl {
        calls += "js"
        return JsRewrittenUrl(requireNotNull(urlJs), headers)
    }

    override fun runBodyJs(script: String, url: String, body: String): String {
        calls += "bodyJs:$url:$body"
        return requireNotNull(bodyJsResult)
    }

    override fun runSegment(source: String, input: RuleValue): RuleResult = error("取文层不该跑 @js: 段")
    override fun runExpression(expr: String): String = error("取文层不该跑表达式")
    override fun runInit(script: String, input: RuleValue): Map<String, String> = error("取文层不该跑 init")
}
