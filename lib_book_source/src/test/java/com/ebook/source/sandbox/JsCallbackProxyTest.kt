package com.ebook.source.sandbox

import com.ebook.source.script.EvalContext
import com.ebook.source.script.RuleResult
import com.ebook.source.script.RuleSyntaxException
import com.ebook.source.script.RuleValue
import com.ebook.source.script.ScriptRequest
import com.ebook.source.script.ScriptTransport
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.jsoup.Jsoup

/**
 * 回调代理的策略面：准入、限流、参数形态、递归求值、变量作用域、日志出口。
 *
 * 全部跑在 JVM 上：网络与求值都从构造参数进来（`ScriptTransport` 是 2c 就有的假件接缝，
 * `statusProbe` / `evaluateNested` 同理），所以这条链上没有一处需要设备或真 DNS。
 * `GuardedNetwork` 那条用例只锁「守门 DNS 确实挂上了客户端」，真实拦截由 Task 5 的
 * `JsNetworkGuardTest` 与 Task 8 的设备用例负责。
 */
class JsCallbackProxyTest {

    private class FakeTransport(private val reply: String = "<html>第二页</html>") : ScriptTransport {
        val sent = mutableListOf<ScriptRequest>()
        override suspend fun execute(request: ScriptRequest): String {
            sent += request
            return reply
        }
    }

    private fun proxyFor(
        transport: ScriptTransport = FakeTransport(),
        statusProbe: suspend (String) -> Int = { 200 },
        variables: MutableMap<String, String> = linkedMapOf(),
        /** 本轮任务上下文。给了它就不再按 [variables] 现造——源级变量整串要断言的就是这个对象 */
        ctx: EvalContext? = null,
        limits: JsLimits = JsLimits(),
        evaluateNested: (String, RuleValue) -> RuleResult = { _, _ -> RuleResult.Miss },
        logs: MutableList<String> = mutableListOf(),
        /** 源级会话罐：null 表示「这次装配没有罐」，cookie 族必须报可诊断的拒绝 */
        cookies: SourceCookieJar? = null,
    ): JsCallbackProxy = JsCallbackProxy(
        // 白名单只含 novel.example：本文件里所有「被拒」用例靠的就是这条边界
        guard = JsNetworkGuard(SourceHostAllowlist.of("", listOf("https://novel.example/"))),
        transport = transport,
        statusProbe = statusProbe,
        ctx = ctx ?: EvalContext(baseUrl = "https://novel.example/entry", variables = variables),
        evaluateNested = evaluateNested,
        limits = limits,
        logSink = { channel, message -> logs += "$channel:$message" },
        initialPage = "<html>入口页</html>",
        initialUrl = "https://novel.example/entry",
        cookies = cookies,
    )

    private fun data(reply: HostReply): String? = (reply.data as? JsonPrimitive)?.content

    // —— 网络：基准、准入、限流、参数形态 ——

    @Test
    fun `ajax 取回白名单内的页面，并把它当作下一次定位的基准`() {
        val transport = FakeTransport("<html>第二页</html>")
        var seenPage = ""
        var seenUrl = ""
        val proxy = proxyFor(transport = transport, evaluateNested = { _, input ->
            val page = input as RuleValue.Page
            seenPage = page.source
            seenUrl = page.baseUrl
            RuleResult.Texts(listOf("第一章"))
        })
        assertTrue(proxy.handle("ajax", """["https://novel.example/p2"]""").ok)
        // 基准要「被下一次定位消费」才算立住：外呼成功后跑一次 getElements，
        // 嵌套求值拿到的输入必须已经是 ajax 的那一页（计划原文漏了这一行触发调用，
        // 只有 assertTrue 的话 seenPage 恒为空串，用例什么都没锁住）
        assertTrue(proxy.handle("getElements", """[".//a"]""").ok)
        assertEquals("<html>第二页</html>", seenPage)
        assertEquals("https://novel.example/p2", seenUrl)
    }

    @Test
    fun `没有任何外呼时，嵌套求值的基准是任务带进来的入口页`() {
        var seenPage = ""
        val proxy = proxyFor(evaluateNested = { _, input ->
            seenPage = (input as RuleValue.Page).source
            RuleResult.Miss
        })
        proxy.handle("getElements", """[".//a"]""")
        assertEquals("<html>入口页</html>", seenPage)
    }

    @Test
    fun `白名单外的 host 被拒且根本不打网络，被拒的请求不烧配额`() {
        val transport = FakeTransport()
        val proxy = proxyFor(transport = transport, limits = JsLimits(maxRequestsPerTask = 1))
        val rejected = proxy.handle("ajax", """["https://evil.example/x"]""")
        assertFalse(rejected.ok)
        assertTrue(rejected.error!!.contains("白名单"))
        assertEquals(0, transport.sent.size)
        // 配额一点没动：一条写坏的规则不该把这一轮的外呼机会吃光
        assertTrue(proxy.handle("ajax", """["https://novel.example/ok"]""").ok)
        assertEquals(1, transport.sent.size)
    }

    @Test
    fun `外呼次数用满上限后拒绝，且消息说得出上限与目标 host`() {
        val transport = FakeTransport()
        val proxy = proxyFor(transport = transport, limits = JsLimits(maxRequestsPerTask = 2))
        repeat(2) { assertTrue(proxy.handle("ajax", """["https://novel.example/p$it"]""").ok) }
        val third = proxy.handle("ajax", """["https://novel.example/p3"]""")
        assertFalse(third.ok)
        assertTrue(third.error!!.contains("2 次外呼上限"))
        // 上一行的 !! 已把 error 智能判成非空，这里再写 !! 会报 Unnecessary non-null assertion
        assertTrue(third.error.contains("novel.example"))
        assertEquals("拒绝必须发生在发请求之前，否则上限只是报账而不是拦截", 2, transport.sent.size)
    }

    @Test
    fun `ajax 的第 2 个参数是对象时按 URL 选项解析，与规则尾段共用一套键`() {
        val transport = FakeTransport()
        val proxy = proxyFor(transport = transport)
        val reply = proxy.handle(
            "ajax",
            """["https://novel.example/x",{"method":"GET","headers":{"X-T":"1"},"charset":"gbk","timeout":3000,"retry":2}]""",
        )
        assertTrue(reply.ok)
        val request = transport.sent.single()
        assertEquals("GET", request.method)
        assertEquals(mapOf("X-T" to "1"), request.headers)
        assertEquals("gbk", request.charset)
        assertEquals(3_000L, request.timeoutMs)
        assertEquals(2, request.retry)
    }

    @Test
    fun `声明了 POST 却不给 body 时当场拒绝，不让传输层抛未类型化的 IllegalArgumentException`() {
        val transport = FakeTransport()
        val proxy = proxyFor(transport = transport)
        val reply = proxy.handle("ajax", """["https://novel.example/x",{"method":"POST"}]""")
        assertFalse(reply.ok)
        assertTrue(reply.error!!.contains("没给 body"))
        assertEquals(0, transport.sent.size)
    }

    @Test
    fun `ajax 的第 2 个参数是裸字符串时按 charset 处理（本仓规定）`() {
        val transport = FakeTransport()
        val proxy = proxyFor(transport = transport)
        assertTrue(proxy.handle("load", """["https://novel.example/gbk","gbk"]""").ok)
        assertEquals("gbk", transport.sent.single().charset)
        assertEquals("GET", transport.sent.single().method)
    }

    @Test
    fun `选项里的 webView 照规则尾段同口径拒绝，且发生在外呼之前`() {
        val transport = FakeTransport()
        val proxy = proxyFor(transport = transport)
        val reply = proxy.handle("ajax", """["https://novel.example/x",{"webView":true}]""")
        assertFalse(reply.ok)
        assertEquals("带 webView 的请求不会发出去", 0, transport.sent.size)
    }

    @Test
    fun `post 的对象体按表单体发送，键排序让同一脚本调用永远发同一份请求体`() {
        val transport = FakeTransport()
        val proxy = proxyFor(transport = transport)
        assertTrue(proxy.handle("post", """["https://novel.example/s",{"b":"2","a":"斗破"}]""").ok)
        val request = transport.sent.single()
        assertEquals("POST", request.method)
        assertEquals("a=%E6%96%97%E7%A0%B4&b=2", request.body)
        assertEquals("application/x-www-form-urlencoded; charset=utf-8", request.headers["Content-Type"])
    }

    @Test
    fun `post 的字符串体原样发出，不猜 Content-Type`() {
        val transport = FakeTransport()
        val proxy = proxyFor(transport = transport)
        assertTrue(proxy.handle("post", """["https://novel.example/s","raw=payload"]""").ok)
        val request = transport.sent.single()
        assertEquals("raw=payload", request.body)
        assertTrue(request.headers.isEmpty())
    }

    @Test
    fun `responseCode 走探测且同样过守门与限流`() {
        var probed = 0
        val proxy = proxyFor(
            statusProbe = { probed++; 404 },
            limits = JsLimits(maxRequestsPerTask = 1),
        )
        val reply = proxy.handle("responseCode", """["https://novel.example/x"]""")
        assertTrue(reply.ok)
        assertEquals("404", data(reply))
        assertEquals(1, probed)
        assertFalse(proxy.handle("responseCode", """["https://novel.example/y"]""").ok)
        assertFalse(proxy.handle("responseCode", """["https://evil.example/x"]""").ok)
        assertEquals("白名单外的探测不该发出去", 1, probed)
    }

    @Test
    fun `响应文本超单次回调上限时报告过大，且不推进页面基准`() {
        val transport = FakeTransport("<html>这一页超大</html>")
        var seenPage = ""
        val proxy = proxyFor(
            transport = transport,
            limits = JsLimits(maxHostReplyBytes = 8),
            evaluateNested = { _, input ->
                seenPage = (input as RuleValue.Page).source
                RuleResult.Miss
            },
        )
        val reply = proxy.handle("ajax", """["https://novel.example/big"]""")
        assertFalse(reply.ok)
        assertTrue(reply.error!!.contains("超单次回调上限"))
        proxy.handle("getElements", """[".//a"]""")
        assertEquals("过大的一页不能成为当前页", "<html>入口页</html>", seenPage)
    }

    @Test
    fun `请求超时回的是请求失败的话术，异常不穿出代理边界`() {
        val proxy = proxyFor(
            transport = ScriptTransport { delay(200); "body" },
            limits = JsLimits(wallClockMs = 50L),
        )
        val reply = proxy.handle("ajax", """["https://novel.example/slow"]""")
        assertFalse(reply.ok)
        assertTrue(reply.error!!.contains("没回结果"))
    }

    @Test
    fun `代理用的客户端把 DNS 挂在守门判定点上，且不污染注入的源客户端`() {
        val base = okhttp3.OkHttpClient()
        val guarded = GuardedNetwork.clientFor(base, JsNetworkGuard(SourceHostAllowlist.of("", emptyList())))
        assertTrue(guarded.dns is GuardedDns)
        assertNotSame(base.dns, guarded.dns)
    }

    // —— 变量：与 `@put:` / `{{}}` 同一张表 ——

    @Test
    fun `putVar getVar rmVar 操作的就是传进来的那张变量表，写完插值层立刻读得到`() {
        val variables = linkedMapOf<String, String>()
        val proxy = proxyFor(variables = variables)
        assertTrue(proxy.handle("putVar", """["k","v"]""").ok)
        assertEquals("v", variables["k"])
        assertEquals("v", data(proxy.handle("getVar", """["k"]""")))
        assertTrue(proxy.handle("rmVar", """["k"]""").ok)
        assertFalse(variables.containsKey("k"))
    }

    @Test
    fun `读未设置的变量是「没值」而不是失败`() {
        val reply = proxyFor().handle("getVar", """["never-set"]""")
        assertTrue(reply.ok)
        assertEquals(JsonNull, reply.data)
    }

    @Test
    fun `putToPage 与 putVar 同落任务变量表（阶段一没有页缓存持久层）`() {
        val variables = linkedMapOf<String, String>()
        val proxy = proxyFor(variables = variables)
        assertTrue(proxy.handle("putToPage", """["tocHtml","<ul></ul>"]""").ok)
        assertEquals("<ul></ul>", variables["tocHtml"])
    }

    @Test
    fun `源级变量没写过时读回空串而不是没值`() {
        // 语料里的判据是 `var v = source.getVariable(); if (v == "")` —— 回 null 时 `null == ""` 为假，
        // 脚本会以为「已经有值」并去 JSON.parse 它，症状从「首次运行按空配置走」变成「一进去就抛」
        assertEquals("", data(proxyFor().handle("sourceGetVariable", "[]")))
    }

    @Test
    fun `源级变量初值来自规则声明，setVariable 就地覆盖`() {
        val ctx = EvalContext(sourceVariable = """{"api":"https://a"}""")
        val proxy = proxyFor(ctx = ctx)
        assertEquals("""{"api":"https://a"}""", data(proxy.handle("sourceGetVariable", "[]")))
        assertTrue(proxy.handle("sourceSetVariable", """["{\"api\":\"https://b\"}"]""").ok)
        assertEquals("""{"api":"https://b"}""", data(proxy.handle("sourceGetVariable", "[]")))
        assertEquals("""{"api":"https://b"}""", ctx.sourceVariable)
    }

    @Test
    fun `源级变量的元数在两侧各判一次`() {
        // 零参的读口被传了参、单参的写口没传参，都在受理侧拒绝：垫片那道检查只在走
        // `source.xxx()` 时生效，`__host_call` 是 globalThis 上的普通属性，脚本可以直接调它
        assertFalse(proxyFor().handle("sourceGetVariable", """["x"]""").ok)
        assertFalse(proxyFor().handle("sourceSetVariable", "[]").ok)
    }

    // —— 递归规则求值 ——

    @Test
    fun `getElements 回的是外形态 HTML，脚本可以再把它喂回去定位`() {
        val nodes = RuleResult.Nodes(Jsoup.parse("<div><p>A</p><p>B</p></div>").select("p"))
        val proxy = proxyFor(evaluateNested = { _, _ -> nodes })
        val reply = proxy.handle("getElements", """[".//p"]""")
        assertTrue(reply.ok)
        val items = (reply.data as JsonArray).map { (it as JsonPrimitive).content }
        assertEquals(listOf("<p>A</p>", "<p>B</p>"), items)
    }

    @Test
    fun `queryString 回纯文本，与 getElement 的外形态 HTML 各管一件事`() {
        val nodes = RuleResult.Nodes(Jsoup.parse("<div><p>A</p><p>B</p></div>").select("p"))
        val proxy = proxyFor(evaluateNested = { _, _ -> nodes })
        assertEquals("A", data(proxy.handle("queryString", """[".//p"]""")))
        assertEquals("<p>A</p>", data(proxy.handle("getElement", """[".//p"]""")))
    }

    @Test
    fun `嵌套求值没命中：列表回空数组、单值回 null，都不算失败`() {
        val proxy = proxyFor(evaluateNested = { _, _ -> RuleResult.Miss })
        val list = proxy.handle("getElements", """[".//x"]""")
        assertTrue(list.ok)
        assertEquals(0, (list.data as JsonArray).size)
        val single = proxy.handle("getElement", """[".//x"]""")
        assertTrue(single.ok)
        assertEquals(JsonNull, single.data)
    }

    @Test
    fun `嵌套求值抛类型化错误时把原文回给脚本，不折叠成空`() {
        val proxy = proxyFor(evaluateNested = { _, _ -> throw RuleSyntaxException(".//p[text()]") })
        val reply = proxy.handle("getElements", """[".//p"]""")
        assertFalse(reply.ok)
        assertTrue(reply.error!!.contains(".//p[text()]"))
    }

    @Test
    fun `嵌套求值里再要一次嵌套：绊线当场拒绝而不是撑到超时`() {
        lateinit var proxy: JsCallbackProxy
        var inner: HostReply? = null
        proxy = proxyFor(
            limits = JsLimits(maxCallbackDepth = 1),
            evaluateNested = { _, _ ->
                inner = proxy.handle("getElements", """[".//p"]""")
                RuleResult.Miss
            },
        )
        assertTrue("外层自己该照常跑完", proxy.handle("getElements", """[".//a"]""").ok)
        assertFalse(inner!!.ok)
        // 同上：inner 已被上一行的 !! 判成非空，只对可空的 error 保留断言
        assertTrue(inner.error!!.contains("深度上限"))
    }

    // —— cookie：源级会话罐 ——

    @Test
    fun `cookieSet 写进去的会话 cookieGet 读得到`() {
        val jar = SourceCookieJar()
        val proxy = proxyFor(cookies = jar)

        assertTrue(proxy.handle("cookieSet", """["https://novel.example/login","sid=abc"]""").ok)
        assertEquals("sid=abc", data(proxy.handle("cookieGet", """["https://novel.example/search"]""")))
    }

    @Test
    fun `cookieRemove 清掉这一域的会话`() {
        val jar = SourceCookieJar()
        val proxy = proxyFor(cookies = jar)
        proxy.handle("cookieSet", """["snssdk.com","sessionid=xyz"]""")

        assertTrue(proxy.handle("cookieRemove", """["snssdk.com"]""").ok)
        assertEquals("", data(proxy.handle("cookieGet", """["https://snssdk.com/api"]""")))
    }

    @Test
    fun `没有会话时 cookieGet 回空串而不是 null`() {
        // 脚本侧的判空写法是 `if (cookie.getCookie(url) == '')`，回 null 会让这一判为假、
        // 接着去 match 一个 null
        val reply = proxyFor(cookies = SourceCookieJar()).handle("cookieGet", """["https://novel.example/x"]""")

        assertTrue(reply.ok)
        assertEquals("", data(reply))
    }

    @Test
    fun `没装罐时 cookie 族报出可诊断的一句话而不是静默空转`() {
        val reply = proxyFor().handle("cookieGet", """["https://novel.example/x"]""")

        assertFalse(reply.ok)
        assertTrue("实际：${reply.error}", reply.error?.contains("cookie") == true)
    }

    @Test
    fun `cookie 读写的元数按表校验，少一个参数不当成空操作`() {
        val proxy = proxyFor(cookies = SourceCookieJar())

        assertFalse(proxy.handle("cookieGet", "[]").ok)
        assertFalse(proxy.handle("cookieSet", """["https://novel.example/x"]""").ok)
        assertFalse(proxy.handle("cookieRemove", "[]").ok)
    }

    @Test
    fun `地址写坏时 cookie 族如实报参数错误`() {
        val proxy = proxyFor(cookies = SourceCookieJar())

        val reply = proxy.handle("cookieGet", """["not a url at all!!"]""")
        assertFalse(reply.ok)
        assertTrue("实际：${reply.error}", reply.error?.contains("无法解析") == true)
    }

    @Test
    fun `cookie 读写不烧外呼配额，取页配额用满后照样能清会话`() {
        // 语料里「配额用满 → 清 cookie 重来」是真实存在的处置路径：清会话不发请求，
        // 把它算进配额会让这条自救手段在最需要它的时候失效
        val transport = FakeTransport()
        val proxy = proxyFor(
            transport = transport,
            cookies = SourceCookieJar(),
            limits = JsLimits(maxRequestsPerTask = 1),
        )
        proxy.handle("cookieSet", """["https://novel.example/login","sid=abc"]""")
        assertTrue(proxy.handle("ajax", """["https://novel.example/one"]""").ok)

        val refused = proxy.handle("ajax", """["https://novel.example/two"]""")
        assertFalse(refused.ok)
        assertTrue(refused.error?.contains("外呼上限") == true)
        assertTrue(proxy.handle("cookieRemove", """["https://novel.example/login"]""").ok)
        assertEquals(1, transport.sent.size)
    }

    // —— 日志与边界 ——

    @Test
    fun `toast 与 log 只落注入的日志出口，不弹 UI、不回值`() {
        val logs = mutableListOf<String>()
        val proxy = proxyFor(logs = logs)
        val toast = proxy.handle("toast", """["正文里有广告"]""")
        assertTrue(toast.ok)
        assertEquals(JsonNull, toast.data)
        assertTrue(proxy.handle("log", """["debug info"]""").ok)
        assertEquals(listOf("toast:正文里有广告", "log:debug info"), logs)
    }

    @Test
    fun `表外的能力名一律拒绝，消息与执行器侧同一条`() {
        val reply = proxyFor().handle("files", "[]")
        assertFalse(reply.ok)
        assertEquals("沙箱里没有「files」这个能力", reply.error)
    }

    @Test
    fun `纯计算能力不该出现在主进程：拒绝而不是自己再算一遍`() {
        val reply = proxyFor().handle("md5", """["x"]""")
        assertFalse(reply.ok)
        assertTrue(reply.error!!.contains("纯计算"))
    }

    @Test
    fun `实参不是 JSON 数组时拒绝，异常绝不穿出 handle`() {
        val reply = proxyFor().handle("ajax", """{"url":"https://novel.example/x"}""")
        assertFalse(reply.ok)
        assertTrue(reply.error!!.contains("JSON 数组"))
    }

    @Test
    fun `元数越界报的是参数个数不符，不是下标越界`() {
        val reply = proxyFor().handle("post", """["https://novel.example/x"]""")
        assertFalse(reply.ok)
        assertTrue(reply.error!!.contains("参数个数不符"))
    }

    // —— 换挡器 ——

    @Test
    fun `换挡器没挂上任务代理时回一句可诊断的话`() {
        val reply = HostCallbackRouter().handle("ajax", """["https://novel.example/x"]""")
        assertFalse(reply.ok)
        assertTrue(reply.error!!.contains("没有正在进行的脚本任务"))
    }

    @Test
    fun `任务代理只在 withProxy 期间生效，返回即摘掉`() {
        val router = HostCallbackRouter()
        val variables = linkedMapOf<String, String>()
        assertFalse(router.handle("putVar", """["k","v"]""").ok)
        val inside = router.withProxy(proxyFor(variables = variables)) {
            router.handle("putVar", """["k","v"]""")
        }
        assertTrue(inside.ok)
        assertEquals("v", variables["k"])
        assertFalse(
            "任务结束后必须摘掉代理，否则下一个任务的回调会写进上一个任务的变量表",
            router.handle("getVar", """["k"]""").ok,
        )
    }
}
