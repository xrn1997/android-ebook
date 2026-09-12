package com.ebook.source.sandbox

import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * 源级 cookie 罐的口径测试。
 *
 * 两条背景决定这里逐形态锁死：
 * 1. ** cookie 是会话身份**：把 A 站的会话递给 B 站就是跨站伪造请求的凭据泄漏，所以「哪些条目会
 *    随这次请求发出去」必须是可审计的（域、路径、过期三判都复用 OkHttp 自己的 [Cookie.matches]，
 *    不在这里重新发明一遍公域后缀匹配——那是历史上 cookie 泄漏最常见的来源）。
 * 2. **分区粒度是「一个源实例」**（不是进程级共享 CookieJar，也不是按 host 全局共享）：`ScriptBookParser`
 *    按源建、随 LRU 逐出，所以本类实例的生命周期就是这一条源的生命周期。做成进程级共享罐的症状是
 *    「导入了第二个源之后第一个源开始拿到别人的登录态」，且当场看不出来。
 */
class SourceCookieJarTest {

    private val jar = SourceCookieJar()

    private val novel = "https://novel.example/list".toHttpUrl()
    private val other = "https://other.example/list".toHttpUrl()

    @Test
    fun `写入的 cookie 只回放到同域请求`() {
        jar.setHeader("https://novel.example/login", "sid=abc; token=t1")

        assertEquals("sid=abc; token=t1", jar.headerFor("https://novel.example/search"))
        assertEquals("", jar.headerFor("https://other.example/search"))
    }

    @Test
    fun `Set-Cookie 形态的整串要拆成独立条目，第二段不会被当成属性丢掉`() {
        // Cookie.parse("a=1; b=2") 会把 b=2 当第二个 cookie 的属性解析掉，于是这条 cookie 永远进不了罐
        jar.setHeader("https://novel.example/api", "a=1; b=2")

        val header = jar.headerFor("https://novel.example/api")
        assertTrue("缺 b：$header", header.contains("a=1"))
        assertTrue("缺 b：$header", header.contains("b=2"))
    }

    @Test
    fun `裸 host 写法归一化成 https 后仍然同域可比`() {
        // 语料里 removeCookie("snssdk.com")、getKey("fanqienovel.com", ...) 这类不带协议的写法是主流
        jar.setHeader("snssdk.com", "sessionid=xyz")

        assertEquals("sessionid=xyz", jar.headerFor("https://snssdk.com/api/v1/feed"))
        assertEquals("", jar.headerFor("https://m.snssdk.com/api"))
    }

    @Test
    fun `过期条目不再回放`() {
        jar.saveFromResponse(
            novel,
            listOf(
                Cookie.Builder()
                    .name("dead")
                    .value("1")
                    .hostOnlyDomain("novel.example")
                    .expiresAt(System.currentTimeMillis() - 60_000L)
                    .build(),
            ),
        )
        assertEquals("", jar.headerFor("https://novel.example/list"))
        assertFalse(jar.loadForRequest(novel).any { it.name == "dead" })
    }

    @Test
    fun `同名同域后写覆盖先写，请求头里不会同时出现两个值`() {
        jar.setHeader("https://novel.example/a", "token=old")
        jar.setHeader("https://novel.example/b", "token=new")

        assertEquals("token=new", jar.headerFor("https://novel.example/c"))
    }

    @Test
    fun `removeCookie 只清这一处作用域，别域的条目留下`() {
        jar.setHeader("https://novel.example/login", "sid=abc")
        jar.setHeader("https://other.example/login", "sid=other")

        jar.removeFor("novel.example")

        assertEquals("", jar.headerFor("https://novel.example/x"))
        assertEquals("sid=other", jar.headerFor("https://other.example/x"))
    }

    @Test
    fun `写入空串是清域而不是存一条空值 cookie`() {
        // 语料里登录前的写法就是 cookie.setCookie(url, "")，它的语义是「把这一域的会话丢掉重来」
        jar.setHeader("https://novel.example/login", "sid=abc")
        jar.setHeader("https://novel.example/login", "")

        assertEquals("", jar.headerFor("https://novel.example/login"))
    }

    @Test
    fun `派生的守门客户端确实带上这只罐，取体与探测共用同一份`() {
        val guard = JsNetworkGuard(SourceHostAllowlist.of("", listOf("https://novel.example/")))
        val client = GuardedNetwork.clientFor(OkHttpClient(), guard, jar)

        assertSame(jar, client.cookieJar)
    }

    @Test
    fun `真实 OkHttp 客户端自动收下 Set-Cookie 并在下一次请求带回`() {
        // 走真 socket 而不是合成响应：OkHttp 4/5 的网络拦截器在连接建立**之后**，合成响应也要先过
        // DNS 与握手（这里要测的恰恰是 BridgeInterceptor 与罐的配合，与守门器无关，所以用回环端口的裸客户端）
        val requests = ConcurrentLinkedQueue<String>()
        val server = ServerSocket(0, 4, InetAddress.getLoopbackAddress())
        val stop = AtomicBoolean(false)
        thread(start = true, isDaemon = true) {
            while (!stop.get()) {
                val accepted = runCatching { server.accept() }.getOrNull() ?: return@thread
                try {
                    val reader = accepted.inputStream.bufferedReader()
                    var cookie = ""
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                        if (line.startsWith("Cookie:", ignoreCase = true)) cookie = line.substringAfter(':').trim()
                    }
                    requests.add(cookie)
                    val response = "HTTP/1.1 200 OK\r\nSet-Cookie: sid=from-server; Path=/" +
                        "\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok"
                    accepted.outputStream.use { it.write(response.toByteArray()) }
                } finally {
                    runCatching { accepted.close() }
                }
            }
        }
        try {
            val client = OkHttpClient.Builder().cookieJar(jar).build()
            val url = "http://127.0.0.1:${server.localPort}/login"
            get(client, url)
            get(client, url)
            assertEquals(listOf("", "sid=from-server"), requests.toList())
            assertEquals("sid=from-server", jar.headerFor(url))
        } finally {
            stop.set(true)
            runCatching { server.close() }
        }
    }

    private fun get(client: OkHttpClient, url: String) {
        client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            response.body.string()
        }
    }
}
