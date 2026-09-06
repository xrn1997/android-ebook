package com.ebook.api.intercepter

import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.charset.StandardCharsets

class EncodingInterceptorTest {

    private fun responseOf(contentType: String?, bytes: ByteArray): Response = Response.Builder()
        .request(Request.Builder().url("https://example.com/book").build())
        .protocol(Protocol.HTTP_1_1)
        .code(200)
        .message("OK")
        .body(bytes.toResponseBody(contentType?.toMediaTypeOrNull()))
        .build()

    @Test
    fun `contentType 被强制为 rss+xml 且正文原样可读`() {
        val response = responseOf("text/html", "<html>第一章</html>".toByteArray())
        val out = response.withForcedContentType(
            "application/rss+xml;charset=UTF-8".toMediaTypeOrNull()
        )
        assertEquals("application/rss+xml;charset=UTF-8".toMediaTypeOrNull(), out.body.contentType())
        assertEquals("<html>第一章</html>", out.body.string())
    }

    @Test
    fun `原响应无 contentType 时同样强制并透传正文`() {
        val response = responseOf(null, "正文内容".toByteArray(StandardCharsets.UTF_8))
        val out = response.withForcedContentType(
            "application/rss+xml;charset=UTF-8".toMediaTypeOrNull()
        )
        assertEquals("application/rss+xml;charset=UTF-8".toMediaTypeOrNull(), out.body.contentType())
        assertEquals("正文内容", out.body.string())
    }

    @Test
    fun `未知 contentLength 保持未知不触发全量缓冲`() {
        val bytes = "长正文".toByteArray(StandardCharsets.UTF_8)
        val response = responseOf("text/html", bytes)
        val out = response.withForcedContentType(
            "application/rss+xml;charset=UTF-8".toMediaTypeOrNull()
        )
        assertEquals(-1L, out.body.contentLength())
        assertEquals("长正文", out.body.string())
    }
}
