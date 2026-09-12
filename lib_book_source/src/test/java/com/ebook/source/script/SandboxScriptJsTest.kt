package com.ebook.source.script

import com.ebook.source.sandbox.HostHandler
import com.ebook.source.sandbox.JsCallbackProxy
import com.ebook.source.sandbox.JsInvocation
import com.ebook.source.sandbox.JsMode
import com.ebook.source.sandbox.JsNetworkGuard
import com.ebook.source.sandbox.JsOutcome
import com.ebook.source.sandbox.JsStatus
import com.ebook.source.sandbox.SourceHostAllowlist
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 主进程侧「模式包装 + 结果解包」的锁形，以及沙箱回调代理的挂载时序。
 *
 * `executeTask` 是唯一的接缝：真的接线里它等于 `JsSandboxHost::call`（挂代理 + 走客户端），
 * 这里换成记录器，于是五种模式各自的 wrapper 文本、绑定表、以及 `JsOutcome` 到 `RuleResult`
 * 的映射全在 JVM 上可断言。代理是**实现内部造的**（Task 9 的 `JsCallbackProxy`），
 * 所以记录器顺手把 handler 交出来，用 `handle(...)` 直接驱动它验递归与网络。
 */
class SandboxScriptJsTest {

    private val invocations = mutableListOf<JsInvocation>()
    private val handlers = mutableListOf<HostHandler>()
    private var outcome = JsOutcome(JsStatus.OK, JsonPrimitive("文本"))

    private val transport = object : ScriptTransport {
        val requests = mutableListOf<ScriptRequest>()
        var reply = "响应体"
        override suspend fun execute(request: ScriptRequest): String {
            requests += request
            return reply
        }
    }

    private val guard = JsNetworkGuard(
        SourceHostAllowlist.of("https://example.com/book/1.html", emptyList()),
    )

    private val ctx = EvalContext(baseUrl = "https://example.com/ch/1.html", key = "斗破", page = 3)

    private val bridge = SandboxScriptJs(
        executeTask = { handler, invocation ->
            handlers += handler
            invocations += invocation
            outcome
        },
        ctx = ctx,
        guard = guard,
        transport = transport,
        staticBindings = mapOf("bookJson" to """{"name":"书"}""", "chapterJson" to null),
    )

    private fun segment(text: String) = bridge.runSegment("result", RuleValue.Page(text, "https://example.com/ch/1.html"))

    private fun proxy(): JsCallbackProxy = handlers.last() as JsCallbackProxy

    @Test
    fun `段执行原样送脚本并带上当下页面与量表绑定`() {
        val result = segment("第一章 开局")
        assertEquals(RuleResult.Texts(listOf("文本")), result)
        val inv = invocations.single()
        assertEquals(JsMode.SEGMENT, inv.mode)
        // SEGMENT 不套 wrapper：完成值口径由桥接层负责，加一层反而会吃掉「最后一条语句即结果」
        assertEquals("result", inv.source)
        assertEquals("第一章 开局", inv.bindings["result"])
        assertEquals("https://example.com/ch/1.html", inv.bindings["baseUrl"])
        assertEquals("斗破", inv.bindings["key"])
        assertEquals("3", inv.bindings["page"])
        assertEquals("""{"name":"书"}""", inv.bindings["bookJson"])
        // 明确不提供 src（源规则原文）与 title（章节名）：一个占报文预算、一个要查库才拿得到
        assertNull(inv.bindings["src"])
        assertNull(inv.bindings["title"])
        assertNull(inv.bindings["chapterJson"])
    }

    @Test
    fun `数组完成值展开成多条文本`() {
        outcome = JsOutcome(JsStatus.OK, JsonArray(listOf(JsonPrimitive("a"), JsonPrimitive("b"))))
        assertEquals(RuleResult.Texts(listOf("a", "b")), segment("[]"))
    }

    @Test
    fun `对象完成值按 JSON 文本回填`() {
        outcome = JsOutcome(
            JsStatus.OK,
            JsonObject(mapOf("k" to JsonPrimitive("v"))),
        )
        assertEquals(RuleResult.Texts(listOf("""{"k":"v"}""")), segment("{}"))
    }

    @Test
    fun `脚本跑成 undefined 是没取到值而不是失败`() {
        outcome = JsOutcome(JsStatus.OK, null)
        assertEquals(RuleResult.Miss, segment("undefined"))
    }

    @Test
    fun `数组里全是对象时按没取到值处置而不是空文本`() {
        outcome = JsOutcome(JsStatus.OK, JsonArray(listOf(JsonObject(emptyMap()), JsonNull)))
        assertEquals(RuleResult.Miss, segment("[]"))
    }

    @Test
    fun `脚本失败抛类型化异常绝不折叠成 Miss`() {
        outcome = JsOutcome(JsStatus.TIMEOUT, error = "超过 4000ms")
        val error = runCatching { segment("while(true);") }.exceptionOrNull()
        assertTrue("实际：${error?.let { it::class.simpleName }}", error is JsExecutionFailedException)
        assertEquals(JsStatus.TIMEOUT, (error as JsExecutionFailedException).status)
        assertTrue(error.message!!.contains("TIMEOUT"))
    }

    @Test
    fun `表达式模式取标量文本且数字也认`() {
        outcome = JsOutcome(JsStatus.OK, JsonPrimitive(40))
        assertEquals("40", bridge.runExpression("(page-1)*20"))
        assertEquals(JsMode.EXPRESSION, invocations.single().mode)
        assertEquals("(page-1)*20", invocations.single().source)
        // 展开发生在切分之前，URL 类调用点此时还没有页面文本：result 显式 null，不猜一个基准
        assertNull(invocations.single().bindings["result"])
    }

    @Test
    fun `表达式完成值为空展开成空串而不是拖垮整条规则`() {
        // 语料实证：`{{java.put('key',key)}}` / `{{java.put("page",page)}}` 这类**只求副作用**的插值
        // 在 URL 规则里成族出现（16 条）。跑成了而完成值是 undefined，按 §9「未取到值 → 空串」
        // 落成空串；旧口径在这里抛失败，后果是整条 searchUrl 作废、整源搜不出东西。
        outcome = JsOutcome(JsStatus.OK, null)
        assertEquals("", bridge.runExpression("book.name"))
        outcome = JsOutcome(JsStatus.OK, JsonNull)
        assertEquals("", bridge.runExpression("java.put('key',key)"))
    }

    @Test
    fun `表达式回对象数组仍按失败报且不说结果为空`() {
        // 空与「回了个结构」是两件事：后者是作者以为插值能序列化对象，静默当成文本会拼出半个 JSON
        outcome = JsOutcome(JsStatus.OK, JsonObject(mapOf("a" to JsonPrimitive("b"))))
        val error = runCatching { bridge.runExpression("({a:'b'})") }.exceptionOrNull() as JsExecutionFailedException
        assertTrue(error.message!!, error.message!!.contains("对象"))
        assertFalse("对象不是「结果为空」", error.message!!.contains("求值结果为空"))
    }

    @Test
    fun `js 选项的 wrapper 读绑定里的 url 与 headers 并回传对象`() {
        outcome = JsOutcome(
            JsStatus.OK,
            JsonObject(
                mapOf(
                    "url" to JsonPrimitive("https://example.com/a?t=1"),
                    "headers" to JsonObject(mapOf("Referer" to JsonPrimitive("https://example.com/"))),
                ),
            ),
        )
        val rewritten = bridge.runUrlJs(
            "url = url + '?t=1'; headers['Referer'] = 'https://example.com/';",
            "https://example.com/a",
            mapOf("User-Agent" to "UA"),
        )
        val inv = invocations.single()
        assertEquals(JsMode.URL_OPTION, inv.mode)
        assertTrue(inv.source, inv.source.startsWith("var url = __bindings.url;"))
        assertTrue(inv.source, inv.source.contains("var headers = JSON.parse(__bindings.headersJson);"))
        assertTrue(inv.source, inv.source.endsWith("result = ({url: url, headers: headers});"))
        // 用户脚本夹在中间：两头都是我们拼的
        assertTrue(inv.source, inv.source.contains("url = url + '?t=1'"))
        assertEquals("https://example.com/a", inv.bindings["url"])
        assertEquals("""{"User-Agent":"UA"}""", inv.bindings["headersJson"])
        assertEquals("https://example.com/a?t=1", rewritten.url)
        assertEquals("https://example.com/", rewritten.headers["Referer"])
        assertEquals("UA", rewritten.headers["User-Agent"])
    }

    @Test
    fun `js 选项没给出 url 时当场失败而不是回退原地址`() {
        outcome = JsOutcome(JsStatus.OK, JsonObject(mapOf("headers" to JsonObject(emptyMap()))))
        val error = runCatching { bridge.runUrlJs("1", "https://example.com/a", emptyMap()) }.exceptionOrNull() as JsExecutionFailedException
        // 回退原 URL = 发出一个作者明确不要的请求，比解析失败更糟
        assertTrue(error.message!!, error.message!!.contains("没有给出 url"))
    }

    @Test
    fun `js 选项回传标量时按运行失败报并说清看到的是什么`() {
        outcome = JsOutcome(JsStatus.OK, JsonPrimitive("https://example.com/a"))
        val error = runCatching { bridge.runUrlJs("1", "https://example.com/a", emptyMap()) }.exceptionOrNull() as JsExecutionFailedException
        assertTrue(error.message!!, error.message!!.contains("实到 标量"))
    }

    @Test
    fun `bodyJs 以 result 变量进出并回新文本`() {
        outcome = JsOutcome(JsStatus.OK, JsonPrimitive("净化后"))
        assertEquals("净化后", bridge.runBodyJs("result = result + ''", "https://example.com/x", "原始正文"))
        val inv = invocations.single()
        assertEquals(JsMode.BODY_JS, inv.mode)
        assertTrue(inv.source, inv.source.endsWith("\n;result"))
        assertEquals("原始正文", inv.bindings["result"])
        // `baseUrl` 绑定恒等于 ctx 的页面基准；本次请求的地址在 `url` 绑定里（两者不是一回事）
        assertEquals("https://example.com/ch/1.html", inv.bindings["baseUrl"])
        assertEquals("https://example.com/x", inv.bindings["url"])
    }

    @Test
    fun `init 的 JS 分支回对象时字段按键取`() {
        outcome = JsOutcome(
            JsStatus.OK,
            JsonObject(
                mapOf(
                    "name" to JsonPrimitive("斗破"),
                    "author" to JsonPrimitive("天蚕土豆"),
                    "intro" to JsonNull,
                    "tags" to JsonArray(listOf(JsonPrimitive("玄幻"))),
                ),
            ),
        )
        val fields = bridge.runInit("({name:'斗破'})", RuleValue.Page("详情页", "https://example.com/b/1.html"))
        assertEquals(mapOf("name" to "斗破", "author" to "天蚕土豆", "intro" to "", "tags" to """["玄幻"]"""), fields)
        assertEquals(JsMode.INIT, invocations.single().mode)
        assertEquals("详情页", invocations.single().bindings["result"])
    }

    @Test
    fun `init 回传标量按运行失败报而不是当成没有预处理`() {
        outcome = JsOutcome(JsStatus.OK, JsonPrimitive("x"))
        val error = runCatching { bridge.runInit("@js:x", RuleValue.Page("页")) }.exceptionOrNull() as JsExecutionFailedException
        assertTrue(error.message!!, error.message!!.contains("必须回传对象"))
    }

    @Test
    fun `每次调用挂一个新代理且执行完就摘掉`() {
        // 第一次调用的代理只看得到第一页；第二次的基准跟着第二次的输入走（关键事实 4）
        segment("<p>第一页</p>")
        segment("<p>第二页</p>")
        assertEquals(2, invocations.size)
        assertNotSame(handlers[0], handlers[1])
        val first = handlers[0].handle("getElement", """["tag.p"]""")
        assertTrue(first.error.toString(), first.ok)
        assertEquals("<p>第一页</p>", (first.data as JsonPrimitive).content)
        val second = handlers[1].handle("getElement", """["tag.p"]""")
        assertEquals("<p>第二页</p>", (second.data as JsonPrimitive).content)
    }

    @Test
    fun `沙箱里 java_put 写的变量外层 get 读得到`() {
        segment("页")
        assertTrue(proxy().handle("putVar", """["token","abc123"]""").ok)
        assertEquals(
            "abc123",
            ScriptRuleEvaluator(ctx).evaluate("@get:token", RuleValue.Page("页")).firstText(),
        )
    }

    @Test
    fun `代理内的递归求值不再要 JS`() {
        segment("页")
        val reply = proxy().handle("getElements", """["@js:1+1"]""")
        assertFalse(reply.ok)
        // 这就是 Task 9 深度绊线之外的那道闸：嵌套求值器是「没有桥的上下文」
        assertTrue(reply.error.toString(), reply.error!!.contains("需脚本沙箱执行器"))
    }

    @Test
    fun `ajax 经注入的传输代发并过守门`() {
        segment("页")
        val reply = proxy().handle("ajax", """["https://example.com/x"]""")
        assertTrue(reply.error.toString(), reply.ok)
        assertEquals("响应体", (reply.data as JsonPrimitive).content)
        assertEquals("https://example.com/x", transport.requests.single().url)
    }

    @Test
    fun `白名单外的 host 被拒且不发出请求`() {
        segment("页")
        val reply = proxy().handle("ajax", """["https://evil.com/x"]""")
        assertFalse(reply.ok)
        assertTrue(reply.error.toString(), reply.error!!.contains("不在本源可解析白名单"))
        assertTrue(transport.requests.isEmpty())
    }

    @Test
    fun `嵌套规则的语法错误原文回传不改写成泛泛失败`() {
        segment("页")
        // 选择器里括号不配对是 2a 定的 RuleSyntaxException 形态（CSS 后端把 Jsoup 的
        // SelectorParseException 换成类型化语法错误，见 ElementBackendsTest 的同形用例；
        // 不是 XPath——XPath 会走 Unsupported）
        val reply = proxy().handle("getElement", """["@css:.foo["]""")
        assertFalse(reply.ok)
        // 语法错误原文回传（Task 9 口径），代理不把它改写成一句泛泛的失败
        assertTrue(reply.error.toString(), reply.error!!.contains("规则语法无法解释"))
    }
}
