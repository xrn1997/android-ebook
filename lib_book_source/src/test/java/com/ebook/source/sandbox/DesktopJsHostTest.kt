package com.ebook.source.sandbox

import com.ebook.source.analyze.ScriptBookParser
import com.ebook.source.script.EvalContext
import com.ebook.source.script.JsExecutionFailedException
import com.ebook.source.script.RuleResult
import com.ebook.source.script.RuleValue
import com.ebook.source.script.SandboxScriptJs
import com.ebook.source.script.ScriptRequest
import com.ebook.source.script.ScriptRuleEvaluator
import com.ebook.source.script.ScriptTransport
import com.ebook.source.script.firstText
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * 桌面（开发机）真内核用例：QuickJsNative + js_bridge.cpp + QuickJsPrelude + SandboxScriptJs
 * 全部是真件，只有「binder + `:js` 隔离进程」被 [DirectJsRuntime] 换成进程内直连。
 *
 * 价值：含 JS 的脚本书源从此能在 JVM 单测里跑**真求值器**（此前含 JS 的重源归真机临时验证，
 * 登记见 docs/test-coverage-todo.md）。跑的正是生产同款能力边界——白名单、
 * `java.*` 回调、超时、描述符解包都原样。
 *
 * **库缺席时按跳过处置**：`QuickJsNative.loaded=false`（没装 MinGW、没跑 buildDesktopJsLib）
 * 时这里全部 skip——「没跑」与「跑过且通过」必须在报告里可区分，绝不假绿。
 */
class DesktopJsHostTest {

    private val pageUrl = "https://example.com/ch/1.html"

    private val transport = object : ScriptTransport {
        val requests = mutableListOf<ScriptRequest>()
        var reply = "<html><body><div id=\"content\"><p>第一章 开局</p><p>正文第二段</p></div></body></html>"
        override suspend fun execute(request: ScriptRequest): String {
            requests += request
            return reply
        }
    }

    /**
     * 与「一条源」同生命周期的会话罐：桥每次调用新建（与生产同形），罐不跟着新建。
     * 生产里这份罐的持有者是 [ScriptBookParser]，它同时把这一份交给守门客户端与沙箱桥。
     */
    private val cookieJar = SourceCookieJar()

    private fun bridgeFor(ctx: EvalContext): SandboxScriptJs {
        assumeTrue(
            "libebook_js 未加载，桌面真内核用例跳过（先跑 :lib_book_source:buildDesktopJsLib）。" +
                "原因：${QuickJsNative.loadError}",
            QuickJsNative.loaded,
        )
        return SandboxScriptJs(
            executeTask = { handler, invocation -> DirectJsRuntime.host.call(handler, invocation) },
            ctx = ctx,
            guard = JsNetworkGuard(SourceHostAllowlist.of("https://example.com/book/1.html", emptyList())),
            transport = transport,
            staticBindings = mapOf(
                "bookJson" to """{"name":"书"}""",
                "chapterJson" to null,
                // 生产由 `ScriptBookParser.newContext` 从装载好的规则灌进来，这里给一份同形态的最小值
                "sourceJson" to """{"bookSourceUrl":"https://example.com/book/1.html","bookSourceName":"测试源"}""",
            ),
            cookies = cookieJar,
        )
    }

    private fun newCtx(): EvalContext =
        EvalContext(baseUrl = pageUrl, key = "斗破", page = 3)

    private val page = RuleValue.Page("页", pageUrl)

    @Test
    fun `真内核跑通规则的 JS 段`() = runTest {
        val out = bridgeFor(newCtx()).runSegment(
            "var m = String(result).match(/id=\"content\"[^>]*>([\\s\\S]*?)<\\/div>/i);" +
                "(m ? m[1].replace(/<[^>]+>/g, '') : '');",
            RuleValue.Page("<div id=\"content\"><p>第一章 开局</p><p>正文第二段</p></div>", pageUrl),
        )
        assertEquals(RuleResult.Texts(listOf("第一章 开局正文第二段")), out)
    }

    @Test
    fun `插值表达式由真内核求值`() = runTest {
        assertEquals("40", bridgeFor(newCtx()).runExpression("(page-1)*20"))
    }

    @Test
    fun `真内核下副作用表达式的完成值落成空串`() = runTest {
        // 「undefined 到 Kotlin 侧是什么」只能由真内核证：桥把 undefined 与 null 一并序列化成 JSON null，
        // 于是 runExpression 看到的空态不是 Kotlin null 而是 JsonNull —— 假件测的是同一分支的另一半。
        assertEquals("", bridgeFor(newCtx()).runExpression("java.put('key',key)"))
    }

    @Test
    fun `数组完成值展开成多条文本`() = runTest {
        val out = bridgeFor(newCtx()).runSegment("['甲','乙']", page)
        assertEquals(RuleResult.Texts(listOf("甲", "乙")), out)
    }

    @Test
    fun `完成值为 null 按没取到值处置`() = runTest {
        assertEquals(RuleResult.Miss, bridgeFor(newCtx()).runSegment("null", page))
    }

    @Test
    fun `完成值为 undefined 时桥改取全局 result 而不是 Miss`() = runTest {
        // js_bridge 的兜底：语料里多数规则写成 `result = '正文' + xxx`，完成值是 undefined，
        // 此时改取全局 result（= 本次输入）。这不是 Miss，真内核下与假件语义的差别就在这里。
        assertEquals(RuleResult.Texts(listOf("页")), bridgeFor(newCtx()).runSegment("undefined", page))
    }

    @Test
    fun `java_ajax 经主进程代理打到 transport`() = runTest {
        val t = transport
        val out = bridgeFor(newCtx()).runSegment("java.ajax('https://example.com/api')", page)
        assertEquals(RuleResult.Texts(listOf(t.reply)), out)
        assertEquals("https://example.com/api", t.requests.single().url)
    }

    @Test
    fun `白名单外的 host 被拒且不发出请求`() = runTest {
        val t = transport
        val error = runCatching { bridgeFor(newCtx()).runSegment("java.ajax('https://evil.com/x')", page) }
            .exceptionOrNull()
        assertTrue("实际：${error?.let { it::class.simpleName }}", error is JsExecutionFailedException)
        assertTrue((error as JsExecutionFailedException).message!!.contains("白名单"))
        assertTrue("被拒的请求不得出网", t.requests.isEmpty())
    }

    @Test
    fun `白名单外能力报 UNSUPPORTED_API 而不是 ReferenceError`() = runTest {
        val error = runCatching { bridgeFor(newCtx()).runSegment("java.connect('https://x')", page) }
            .exceptionOrNull()
        assertTrue(error is JsExecutionFailedException)
        assertEquals(JsStatus.UNSUPPORTED_API, (error as JsExecutionFailedException).status)
    }

    @Test
    fun `死循环在 wallClockMs 内被打断`() = runTest {
        val error = runCatching { bridgeFor(newCtx()).runSegment("var i=0; while(true){i++;} i;", page) }
            .exceptionOrNull()
        assertTrue(error is JsExecutionFailedException)
        assertEquals(JsStatus.TIMEOUT, (error as JsExecutionFailedException).status)
    }

    @Test
    fun `java_put 写的量外层经变量表读得到`() = runTest {
        val ctx = newCtx()
        val bridge = bridgeFor(ctx)
        bridge.runSegment("java.put('token','abc123');", page)
        assertEquals("abc123", ScriptRuleEvaluator(ctx).evaluate("@get:token", page).firstText())
    }

    @Test
    fun `js 选项改写 url 且未动过的头原样保留`() = runTest {
        val rewritten = bridgeFor(newCtx()).runUrlJs(
            "url = url + '?t=1'; headers['Referer'] = 'https://example.com/';",
            "https://example.com/a",
            mapOf("User-Agent" to "UA"),
        )
        assertEquals("https://example.com/a?t=1", rewritten.url)
        assertEquals("https://example.com/", rewritten.headers["Referer"])
        assertEquals("UA", rewritten.headers["User-Agent"])
    }

    @Test
    fun `init 的 JS 分支回对象按字段名取`() = runTest {
        val fields = bridgeFor(newCtx()).runInit(
            "({name: book.name, author: '天蚕土豆'})",
            RuleValue.Page("详情页", "https://example.com/b/1.html"),
        )
        assertEquals(mapOf("name" to "书", "author" to "天蚕土豆"), fields)
    }

    @Test
    fun `bodyJs 以 result 变量进出回新文本`() = runTest {
        assertEquals("净化后", bridgeFor(newCtx()).runBodyJs("result = '净化后';", "https://example.com/x", "原始正文"))
    }

    // —— 书源对象面（语料 248 条源引用 `source`，此前一律撞 ReferenceError）——

    @Test
    fun `真内核下 source 的地址三个名字同值`() = runTest {
        // `source.key` / `getKey()` / `bookSourceUrl` 在上游是同一个值的三张脸（语料合计 482 次），
        // 垫片里由 key 与 getKey 都取 bookSourceUrl：这里锁的就是「没有哪张脸自己编了个值」
        val out = bridgeFor(newCtx()).runSegment("[source.key, source.getKey(), source.bookSourceUrl].join('|')", page)
        val url = "https://example.com/book/1.html"
        assertEquals(RuleResult.Texts(listOf("$url|$url|$url")), out)
    }

    @Test
    fun `真内核下 source 变量族与 java 族同表`() = runTest {
        val out = bridgeFor(newCtx()).runSegment(
            "source.put('token','t1'); [java.get('token'), source.get('token')].join('|')",
            page,
        )
        assertEquals(RuleResult.Texts(listOf("t1|t1")), out)
    }

    @Test
    fun `真内核下 setVariable 的整串跨调用存活`() = runTest {
        val ctx = newCtx()
        // 语料的主写法是「读整串 → JSON.parse → 改字段 → 写回整串」，读的那一半是零参调用，
        // 所以「写完再读」必须拿到新值，且换一个桥（= 下一次沙箱调用）还拿得到：整串住在任务上下文里
        assertEquals("", bridgeFor(ctx).runSegment("source.getVariable()", page).firstText())
        bridgeFor(ctx).runSegment("source.setVariable('{\"api\":\"https://b\"}'); 1", page)
        assertEquals(
            "https://b",
            bridgeFor(ctx).runSegment("JSON.parse(source.getVariable()).api", page).firstText(),
        )
        assertEquals("""{"api":"https://b"}""", ctx.sourceVariable)
    }

    @Test
    fun `真内核下 source 的登录族报 UNSUPPORTED_API 而不是类型错误`() = runTest {
        val error = runCatching { bridgeFor(newCtx()).runSegment("source.getLoginInfoMap()", page) }.exceptionOrNull()
        assertTrue(error is JsExecutionFailedException)
        assertEquals(JsStatus.UNSUPPORTED_API, (error as JsExecutionFailedException).status)
        assertTrue(error.message!!, error.message!!.contains("getLoginInfoMap"))
    }

    @Test
    fun `真内核下 book 的持久变量族落进同一张任务变量表`() = runTest {
        val ctx = newCtx()
        bridgeFor(ctx).runSegment("book.putVariable('sign','s1'); 1", page)
        assertEquals("s1", bridgeFor(ctx).runSegment("book.getVariable('sign')", page).firstText())
        assertEquals("s1", ctx.variables["sign"])
    }

    @Test
    fun `解析器把源级字段与变量整串灌进沙箱`() = runTest {
        assumeTrue(
            "libebook_js 未加载，桌面真内核用例跳过（先跑 :lib_book_source:buildDesktopJsLib）。" +
                "原因：${QuickJsNative.loadError}",
            QuickJsNative.loaded,
        )
        // 本类其余用例各造各的 staticBindings，把 `ScriptBookParser.newContext` 里那两处接线删掉
        // 它们照样绿；真源上的症状是「凡是引用 source 的规则都 ReferenceError」。
        // 这条从解析器入口一路打通到沙箱：URL 位读源地址（走 getKey）、字段位读变量整串（走 getVariable）。
        val wiringSource = """
            {"bookSourceUrl":"https://example.com","bookSourceName":"接线源",
             "variable":"{\"api\":\"https://v.example\"}",
             "searchUrl":"@js:source.getKey() + '/search'",
             "ruleSearch":{"bookList":"tag.li","name":"tag.a@text","bookUrl":"tag.a@href",
               "kind":"@js:JSON.parse(source.getVariable()).api"}}
        """.trimIndent()
        transport.reply = "<li><a href=\"/book/1.html\">凡人修仙传</a></li>"
        val parser = ScriptBookParser(wiringSource, transport, jsHost = DirectJsRuntime.host)
        val books = parser.searchBook("凡人", 1)
        assertEquals("https://example.com/search", transport.requests.single().url)
        assertEquals("凡人修仙传", books.single().name)
        assertEquals("https://v.example", books.single().kind)
    }

    // ==== 第 2 批：`java.*` 名字面（上游别名、加解密对象面、页面基准改写） ====
    //
    // 走真内核的理由：这一批的产物**几乎全在垫片里**（别名、对象面、字节标记的进出形态），
    // JVM 侧的 HostCompute 用例只证明「边界那边算得对」，证明不了「脚本写 java.md5Encode 打得开通话」。

    private fun js(script: String, pageText: String = "<p>开局</p>"): String {
        val out = bridgeFor(newCtx()).runSegment(script, RuleValue.Page(pageText, pageUrl))
        return out.firstText()
    }

    @Test
    fun `上游别名与本地名字在真内核实算同值`() {
        assertEquals(
            "900150983cd24fb0d6963f7d28e17f72|abc|900150983cd24fb0d6963f7d28e17f72",
            js(
                """
                var a = String(java.md5Encode('abc'));
                var b = String(java.hexDecodeToString('616263'));
                var c = String(java.digestHex('abc','md5'));
                a + '|' + b + '|' + c;
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `真内核下 longToast 挂在 java 面上而不是 not a function`() {
        // 语料实测（build/survey-longtoast-arity.js + survey-longtoast-per-source2.js）：`java.longToast` 全量 248 次
        // 调用、按 URL 去重 34 条源，其中 196 次写在 loginUrl/jsLib/loginCheckJs/payAction 这些本仓不执行的字段里，
        // 真正落在会求值的规则上的是 52 次 / 16 条源；一律 1 参，而表里只有 `toast` 一个 api。
        // 这里只证名字挂着，不去调它：调用会走进 `JsSandboxHost` 内部装配的默认日志出口（JVM 上打的是
        // lib_common 的 Logger），「转发真的落到 host」是 `JsCallbackProxyTest` 用注入收集器管的事。
        assertEquals("function", js("typeof java.longToast"))
    }

    @Test
    fun `timeFormat 单参走默认格式而不报元数不符`() {
        // 语料 32/49 处只给时间戳：上游 timeFormat(millis) 自带默认格式，缺这一条就是 not a function
        val defaulted = js("String(java.timeFormat(0))")
        assertTrue("timeFormat 单参没给出默认形状的日期：" + defaulted, defaulted.matches(Regex("....-..-.. ..:..:..")))
        assertEquals("1970/01/01", js("String(java.timeFormat(0,'yyyy/MM/dd'))"))
    }

    @Test
    fun `java 面补上随机数与 HMAC 且实参顺序照语料`() {
        val uuid = js("String(java.randomUUID()).toLowerCase()")
        assertTrue("randomUUID 不是 8-4-4-4-12 形状：$uuid", uuid.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")))
        // 语料形态是 (数据, 算法, 密钥)，与 hmacBase64 的表内顺序一致；值由 OpenSSL 独立复算
        assertEquals(
            "h252YE1oF96/yse9qX9halZBF4lBIH6dVXvqyeRLwlo=",
            js("String(java.HMacBase64('hello','HmacSHA256','k3y'))"),
        )
    }

    @Test
    fun `getString 就是对当前结果做规则求值并取文本`() {
        // 语料 531 次调用，实参一律是规则串（'$.bid'、'@CSS:td…'），与 queryString 同义
        assertEquals(
            "42",
            js("String(java.getString('$.bid'))", """{"bid":"42"}"""),
        )
    }

    @Test
    fun `createSymmetricCrypto 面把密钥与 IV 绑在对象上`() {
        // 语料形态：createSymmetricCrypto(变换, 密钥, IV) 之后 .decryptStr(密文)，
        // 而密文常来自 base64DecodeToByteArray —— 字节值必须能跨过只认文本的边界
        assertEquals(
            "hello",
            js(
                """
                var key = java.base64DecodeToByteArray('MDEyMzQ1Njc4OWFiY2RlZg==');
                var iv = java.base64DecodeToByteArray('YWJjZGVmMDEyMzQ1Njc4OQ==');
                var data = java.base64DecodeToByteArray('/8uAvsJDf8LjCw7wqOxbZA==');
                String(java.createSymmetricCrypto('AES/CBC/PKCS5Padding', key, iv).decryptStr(data));
                """.trimIndent(),
            ),
        )
        assertEquals(
            "/8uAvsJDf8LjCw7wqOxbZA==",
            js(
                """
                var c = java.createSymmetricCrypto('AES/CBC/PKCS5Padding','0123456789abcdef','abcdef0123456789');
                String(c.encryptBase64('hello'));
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `strToBytes 与 bytesToStr 往返且保留字符集口径`() {
        assertEquals(
            "hello",
            js("String(java.bytesToStr(java.strToBytes('hello'),'UTF-8'))"),
        )
        // ISO-8859-1 是语料里密钥字节的主要来源（一字符一字节）。同一串 UTF-8 字节按它读会得到两个字符：
        // 这一比对照的不是往返，是「strToBytes 记的确实是 UTF-8 字节」
        val latin1 = "ÿabc".toByteArray(Charsets.UTF_8).toString(Charsets.ISO_8859_1)
        assertEquals(latin1, js("String(java.bytesToStr(java.strToBytes('ÿabc'),'ISO-8859-1'))"))
    }

    @Test
    fun `encodeURI 只挂 java 面而不覆盖 JS 标准内建`() {
        // 两个名字在语料里都存在：java.encodeURI 要的是 URLEncoder（空格成 +），
        // 顶层 encodeURI 是 ECMA 内建（空格成 %20）。把前者挂到 globalThis 会静默改掉后者的语义
        assertEquals("a+b|a%20b", js("String(java.encodeURI('a b')) + '|' + String(encodeURI('a b'))"))
    }

    @Test
    fun `aesBase64DecodeToString 四参一步解出文本`() {
        // 语料 19 次调用全是 (密文, 密钥, 变换, IV) 的固定顺序
        assertEquals(
            "hello",
            js(
                """
                String(java.aesBase64DecodeToString('/8uAvsJDf8LjCw7wqOxbZA==','0123456789abcdef',
                  'AES/CBC/PKCS5Padding','abcdef0123456789'));
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `setContent 改写后续规则求值的基准页`() {
        // 语料 31 次调用全在「先 ajax 取一页、再让 getElements 定位到那一页」的写法里
        assertEquals(
            "换了",
            js(
                """
                java.setContent('<p id="x">换了</p>', 'https://example.com/other');
                String(java.getString('#x@text'));
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `字节令牌能接着喂给下一次调用而不是在半路坏掉`() {
        // decrypt / encrypt 的产物是字节不是文本：宿主带 "base64:" 标记回，垫片就地重新包成令牌。
        // 锁的是这条链整体——密文一旦在半路被当成文本读，症状是「一部分书解得开、一部分永远报错」
        assertEquals(
            "hello",
            js(
                """
                var c = java.createSymmetricCrypto('AES/CBC/PKCS5Padding','0123456789abcdef','abcdef0123456789');
                String(c.decryptStr(c.encrypt(java.strToBytes('hello'))));
                """.trimIndent(),
            ),
        )
        // 同一份明文，hex 与 base64 两种出口必须解出同一个东西；hexDecodeToByteArray 走的是另一条前缀
        assertEquals(
            "hello",
            js(
                """
                var c = java.createSymmetricCrypto('AES/CBC/PKCS5Padding','0123456789abcdef','abcdef0123456789');
                String(c.decryptStr(java.hexDecodeToByteArray(c.encryptHex('hello'))));
                """.trimIndent(),
            ),
        )
    }

    // —— cookie 面：源级会话罐（语料 getCookie/setCookie/removeCookie = 27/18/104 次调用）——
    //
    // 走真内核的理由：`js()` 每次新建一个桥（与生产同形），于是这一族用例顺带锁住了「罐不在桥上、
    // 在装配方身上」——挂在一次调用上，第二次调用就读到空串，而「先 ajax 登录、后拼签名头」的源
    // 全靠跨调用存活这条链。垫片侧的装配（对象面名字、`fwdVar` 的求值次序）也只有真内核看得见。

    @Test
    fun `真内核下 cookie 面写读清三步跨调用存活`() {
        val url = "https://example.com/book/1.html"
        assertEquals("", js("String(cookie.getCookie('$url'))"))
        js("cookie.setCookie('$url', 'sid=abc; t=1'); 1")
        assertEquals("sid=abc; t=1", js("String(cookie.getCookie('$url'))"))
        // 清掉之后读到空串而不是 undefined：脚本普遍拿它当「有没有会话」的判据
        js("cookie.removeCookie('$url'); 1")
        assertEquals("", js("String(cookie.getCookie('$url'))"))
    }

    @Test
    fun `真内核下 java 面的 cookie 别名与 cookie 对象面是同一份罐`() {
        val url = "https://example.com/book/1.html"
        js("java.setCookie('$url', 'tk=9'); 1")
        assertEquals("tk=9", js("String(cookie.getCookie('$url'))"))
        assertEquals("tk=9", js("String(java.getCookie('$url'))"))
        js("java.removeCookie('$url'); 1")
        assertEquals("", js("String(cookie.getCookie('$url'))"))
    }

    @Test
    fun `真内核下 cookie 按 host 归位且裸 host 读得到`() {
        js("cookie.setCookie('https://example.com/book/1.html', 'sid=abc'); 1")
        // 语料一半的实参是裸 host（`removeCookie('snssdk.com')`）：地址归一化必须在边界这一侧做掉
        assertEquals("sid=abc", js("String(cookie.getCookie('example.com'))"))
        // hostOnly 的会话不外溢到子域——罐里只存一条，靠 Cookie.matches 判回去
        assertEquals("", js("String(cookie.getCookie('https://m.example.com/'))"))
    }
}
