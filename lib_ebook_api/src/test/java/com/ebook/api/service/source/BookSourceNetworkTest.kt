package com.ebook.api.service.source

import com.ebook.api.entity.BookSourceRule
import com.ebook.api.intercepter.EncodingInterceptor
import java.nio.charset.Charset
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 原生书源取文的 JVM 回归：非 UTF-8 站点必须按 `rule.charset` 显式字节解码（GET 与 POST 两条分支
 * 同一条口径），且带路径的源地址不能被 Retrofit 的 baseUrl 尾斜杠校验挡下。
 *
 * 客户端顺序与生产一致：先 `EncodingInterceptor("UTF-8")` 强改 Content-Type，
 * 再由假拦截器返回原始字节。若取文层走 `body.string()`，GBK 字节会先按 UTF-8 解坏，
 * 后补的 ISO-8859-1 回解救不回来（docs/architecture.md §6.3）。
 */
class BookSourceNetworkTest {

    /** 假客户端实际发出的请求，用于锁「解析用的地址」与「请求用的地址」同源 */
    private val sent = mutableListOf<okhttp3.Request>()

    private fun clientReturning(body: ByteArray, contentType: String = "text/html"): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor(EncodingInterceptor("UTF-8"))
            .addInterceptor { chain ->
                sent += chain.request()
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(body.toResponseBody(contentType.toMediaTypeOrNull()))
                    .build()
            }
            .build()

    @Test
    fun `非 UTF-8 书源按 rule charset 显式字节解码`() = runTest {
        val expected = "第一章 中文正文"
        val rule = BookSourceRule(url = "https://gbk.example/", charset = "gbk")
        val network = BookSourceNetwork(rule, clientReturning(expected.toByteArray(Charset.forName("GBK"))))

        val actual = network.getPage("https://gbk.example/book/1")

        assertEquals("GBK 原始字节必须在第一次解码时就按 rule.charset 解，不能先走 UTF-8", expected, actual)
    }

    /**
     * POST 分支走的是另一个 Retrofit 方法（`postPage`），返回类型同为 `ResponseBody`；
     * 只在 GET 上锁字节解码会漏掉这条——它的 charset 还额外进了请求体的 mediaType。
     */
    @Test
    fun `POST 分支同样按 rule charset 显式字节解码`() = runTest {
        val expected = "搜索结果 中文正文"
        val rule = BookSourceRule(
            url = "https://gbk.example/",
            charset = "gbk",
            method = "POST",
            body = "q=test",
        )
        val network = BookSourceNetwork(rule, clientReturning(expected.toByteArray(Charset.forName("GBK"))))

        val actual = network.getPage("https://gbk.example/search")

        assertEquals("POST 分支也必须拿原始字节按 rule.charset 解", expected, actual)
        val request = sent.single()
        assertEquals("rule.method 为 POST 时必须走 postPage", "POST", request.method)
        val body = requireNotNull(request.body) { "POST 请求必须带上规则里配的请求体" }
        assertEquals("表单体逐字送出", 6L, body.contentLength())
        assertTrue(
            "mediaType 要带上 rule.charset（buildRequestBody 的口径）",
            body.contentType()?.toString().orEmpty().contains("charset=gbk"),
        )
    }

    @Test
    fun `带路径且无尾斜杠的源地址也能创建服务`() = runTest {
        val html = "<html>ok</html>"
        val rule = BookSourceRule(url = "https://path.example/books", charset = "utf-8")
        val network = BookSourceNetwork(rule, clientReturning(html.toByteArray()))

        val actual = network.getPage("https://path.example/books/1")

        assertEquals("baseUrl 只做 Retrofit 校验，带路径的源地址不能因此炸在构造期", html, actual)
    }

    /**
     * [BookSourceNetwork.absoluteUrl] 是「相对链接写在哪页」的基准，必须与 `getPage`
     * 真正发出的地址同源：这里同时锁相对拼源根、绝对原样，以及两者落到同一个请求上。
     */
    @Test
    fun `absoluteUrl 与 getPage 发出的地址同源`() = runTest {
        val html = "<html>ok</html>"
        val rule = BookSourceRule(url = "https://root.example/books/", charset = "utf-8")
        val network = BookSourceNetwork(rule, clientReturning(html.toByteArray()))

        assertEquals("绝对地址原样", "https://other.example/x", network.absoluteUrl("https://other.example/x"))
        assertEquals(
            "相对地址按书源根拼接（尾斜杠不重复）",
            "https://root.example/books/list/1",
            network.absoluteUrl("/list/1"),
        )

        network.getPage("https://root.example/books/list/1")

        assertEquals("解析基准与实际请求必须是同一个地址", "https://root.example/books/list/1", sent.single().url.toString())
    }

    /**
     * 形态判据要与 `TocPageUrl.join` 的前三条对齐（规格 §6.5）：旧的 `startsWith("http")` 松判据
     * 把 `httpfoo/x` 这种路径当绝对地址原样送出，而协议相对 `//host/x` 会被拼进源根路径得到
     * `https://root.example/books//cdn.example/x`——两条都不报错，只是请求打到不相干的地址。
     */
    @Test
    fun `absoluteUrl 认协议不认前缀且协议相对取源根 scheme`() {
        val rule = BookSourceRule(url = "https://root.example/books/", charset = "utf-8")
        val network = BookSourceNetwork(rule, clientReturning("<html>ok</html>".toByteArray()))

        assertEquals("大写 scheme 也是绝对地址", "HTTP://other.example/x", network.absoluteUrl("HTTP://other.example/x"))
        assertEquals(
            "以 http 开头的路径不是协议，按源根拼接",
            "https://root.example/books/httpfoo/x",
            network.absoluteUrl("httpfoo/x"),
        )
        assertEquals(
            "协议相对只换 scheme，不进源根路径",
            "https://cdn.example/x",
            network.absoluteUrl("//cdn.example/x"),
        )
    }
}
