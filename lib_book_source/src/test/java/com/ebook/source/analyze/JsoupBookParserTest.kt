package com.ebook.source.analyze

import com.ebook.api.entity.BookSourceRule
import com.ebook.api.entity.ContentRule
import com.ebook.api.entity.ReplaceRule
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 原生书源正文抓取的 JVM 回归：单页、同章分页、清理规则、空正文与失败裹层。
 *
 * 这段逻辑原先长在 `lib_book_common` 的 `JsoupSourceReader.fetchAndStoreNative` 里，
 * 靠 `instanceof JsoupBookParser` 分岔、再掏 `parser.rule` 跨模块拼请求——因此只能配真网络
 * 与真 Context 才跑得起来，长期零测试。下沉到 `JsoupBookParser.fetchChapterText` 之后，
 * 用带拦截器的 OkHttpClient 就能在纯 JVM 下喂固定 HTML（不引 MockWebServer）。
 *
 * 断言的是**文本契约**：段落以 `\n` 分隔、未加缩进（缩进归读取层的 TextNormalizer）、
 * 空正文返回空串而不是异常（调用方按失败处置、不落盘）。
 */
class JsoupBookParserTest {

    private var status = 200
    private val pages = mutableMapOf<String, String>()

    private fun client(): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor { chain ->
            val url = chain.request().url.toString()
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(status)
                .message(if (status == 200) "OK" else "ERR")
                .body((pages[url] ?: "").toResponseBody("text/html; charset=utf-8".toMediaType()))
                .build()
        }
        .build()

    private fun parser(
        content: String = ".content",
        nextPage: String = "",
        replace: List<ReplaceRule> = emptyList(),
    ): JsoupBookParser = JsoupBookParser(
        BookSourceRule(
            url = BASE,
            ruleContent = ContentRule(
                content = content,
                nextPage = nextPage,
                replaceRules = replace,
            ),
        ),
        client(),
    )

    @Test
    fun `单页正文按段落取出且不补缩进`() = runTest {
        pages["$BASE/ch/100.html"] =
            """<html><body><div class="content"><p>第一段</p><p>第二段</p></div></body></html>"""

        val text = parser().fetchChapterText("$BASE/ch/100.html")

        assertEquals("第一段\n第二段", text)
    }

    @Test
    fun `同章分页按 nextPage 跟进并拼接`() = runTest {
        pages["$BASE/ch/100.html"] =
            """<html><body><div class="content"><p>第一段</p></div>
               <a class="next" href="/ch/100_2.html">下一页</a></body></html>"""
        pages["$BASE/ch/100_2.html"] =
            """<html><body><div class="content"><p>第二段</p></div></body></html>"""

        val text = parser(nextPage = "a.next@href").fetchChapterText("$BASE/ch/100.html")

        assertEquals("第一段\n第二段", text)
    }

    @Test
    fun `清理规则跑在拼接后的整章串上`() = runTest {
        pages["$BASE/ch/100.html"] =
            """<html><body><div class="content"><p>正文广告正文</p></div></body></html>"""

        val text = parser(replace = listOf(ReplaceRule(pattern = "广告", replacement = "")))
            .fetchChapterText("$BASE/ch/100.html")

        assertEquals("正文正文", text)
    }

    @Test
    fun `正文选择器失配时返回空串而不是抛异常`() = runTest {
        pages["$BASE/ch/100.html"] = """<html><body><div class="other"><p>不是正文</p></div></body></html>"""

        val text = parser().fetchChapterText("$BASE/ch/100.html")

        assertTrue("空正文是合法返回：调用方按失败处置、不落盘（得到 '$text'）", text.isEmpty())
    }

    @Test
    fun `抓取失败裹成章节内容解析失败`() = runTest {
        status = 500

        try {
            parser().fetchChapterText("$BASE/ch/100.html")
            fail("HTTP 失败应抛异常")
        } catch (e: IllegalStateException) {
            assertTrue(
                "消息要保留排查线索（实际：${e.message}）",
                e.message?.contains("章节内容解析失败") == true,
            )
        }
    }

    private companion object {
        const val BASE = "https://a.example"
    }
}
