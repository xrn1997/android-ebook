package com.ebook.source.analyze

import com.ebook.api.entity.BookInfoRule
import com.ebook.api.entity.BookSourceRule
import com.ebook.api.entity.ContentRule
import com.ebook.api.entity.FindRule
import com.ebook.api.entity.KindItem
import com.ebook.api.entity.ReplaceRule
import com.ebook.api.entity.SearchRule
import com.ebook.api.entity.TocRule
import com.ebook.db.entity.BookShelfEntity
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 原生书源解析链路的 JVM 回归：正文抓取（单页、同章分页、清理规则、空正文与失败裹层）、
 * 页内相对地址落位（搜索页与分类页的书目/封面、详情页的封面与目录链接、目录入口回落、
 * 正文下一页），以及 `enabledExplore` 的书城收口。
 *
 * 正文那段逻辑原先长在 `lib_book_common` 的 `JsoupSourceReader.fetchAndStoreNative` 里，
 * 靠 `instanceof JsoupBookParser` 分岔、再掏 `parser.rule` 跨模块拼请求——因此只能配真网络
 * 与真 Context 才跑得起来，长期零测试。下沉到 `JsoupBookParser.fetchChapterText` 之后，
 * 用带拦截器的 OkHttpClient 就能在纯 JVM 下喂固定 HTML（不引 MockWebServer）；
 * 同一套假客户端也够喂搜索页/详情页/分类页，故各面的落位回归一并住在这里。
 *
 * 正文断言的是**文本契约**：段落以 `\n` 分隔、未加缩进（缩进归读取层的 TextNormalizer）、
 * 空正文返回空串而不是异常（调用方按失败处置、不落盘）。
 * 落位断言的是**基准契约**：相对链接按「写在哪页、就相对哪页」落位（口径见 `TocPageUrl.join`），
 * 不是相对源根。
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

    /**
     * 正文下一页的相对链接必须相对**当前页目录**落位（与 `TocPageUrl.join` 同一语义）。
     *
     * 旧实现用 `JsoupHelper.parseUrl(rule.url, ...)`（相对源根）：页面上的
     * `ch-15-2.html` 被落成源根下的 `/ch-15-2.html`，`ChapterPageMatcher` 再剥后缀
     * 也与章节基准对不上，表现为「本章第二页永远漏掉」。
     */
    @Test
    fun `正文下一页的相对地址按当前页目录落位`() = runTest {
        pages["$BASE/book/123/ch-15.html"] =
            """<html><body><div class="content"><p>第一段</p></div>
               <a class="next" href="ch-15-2.html">下一页</a></body></html>"""
        pages["$BASE/book/123/ch-15-2.html"] =
            """<html><body><div class="content"><p>第二段</p></div></body></html>"""

        val text = parser(nextPage = "a.next@href")
            .fetchChapterText("$BASE/book/123/ch-15.html")

        assertEquals("第一段\n第二段", text)
    }

    /**
     * 详情页的封面/目录链接同样要按 `TocPageUrl.join` 的四种形态落位：
     * 协议相对 `//cdn...` 不能被拼成 `https://源站//cdn...`，相对链接要相对详情页目录。
     */
    @Test
    fun `详情封面与目录的相对地址按当前页落位`() = runTest {
        pages["$BASE/book/123.html"] = """
            <html><head></head><body>
              <img class="cover" src="//cdn.example/cover.jpg">
              <a class="toc" href="toc.html">目录</a>
            </body></html>
        """.trimIndent()
        val parser = JsoupBookParser(
            BookSourceRule(
                url = BASE,
                ruleBookInfo = BookInfoRule(
                    coverUrl = "img.cover@src",
                    tocUrl = "a.toc@href",
                ),
            ),
            client(),
        )

        val out = parser.getBookInfo(BookShelfEntity(noteUrl = "$BASE/book/123.html", tag = BASE))

        assertEquals("https://cdn.example/cover.jpg", out.bookInfo?.coverUrl)
        assertEquals("$BASE/book/toc.html", out.bookInfo?.chapterUrl)
    }

    /**
     * `tocUrl` 选择器失配时 `chapterUrl` 是空串；目录抓取必须回详情页，而不是把空串当 entryUrl
     * 交给 TocPager（`getPage("")` 会落到源根首页，按目录选择器解出一批无关链接）。
     */
    @Test
    fun `目录链接选择器失配时回退详情页`() = runTest {
        pages["$BASE/book/123.html"] = """
            <html><body>
              <ul id="list"><li><a href="/ch/1.html">第一章</a></li></ul>
            </body></html>
        """.trimIndent()
        val parser = JsoupBookParser(
            BookSourceRule(
                url = BASE,
                ruleBookInfo = BookInfoRule(tocUrl = "a.missing@href"),
                ruleToc = TocRule(list = "#list li", name = "a", url = "a@href"),
            ),
            client(),
        )

        val shelf = parser.getBookInfo(BookShelfEntity(noteUrl = "$BASE/book/123.html", tag = BASE))
        assertEquals("前置条件：选择器失配，chapterUrl 是空串", "", shelf.bookInfo?.chapterUrl)
        val chapters = parser.getChapterList(shelf).data.chapterList

        assertEquals("$BASE/ch/1.html", chapters.single().contentRef)
        assertEquals("第一章", chapters.single().durChapterName)
    }

    /**
     * `enabledExplore=false` 的作者意图是「不展示发现入口」：书城首屏的各分类区块与顶部分类胶囊
     * 是同一份发现数据的两个读面，必须一起收口。只挡胶囊会让页面仍显示分类区块，等于只关一半。
     */
    @Test
    fun `enabledExplore=false 时书城首屏给空实体`() = runTest {
        val parser = JsoupBookParser(
            BookSourceRule(
                url = BASE,
                enabledExplore = false,
                ruleFind = FindRule(
                    url = "/kind/{{kind}}/{{page}}",
                    kinds = listOf(KindItem("玄幻", "xuanhuan")),
                ),
            ),
            client(),
        )

        val library = parser.fetchLibraryData()

        assertNull("关闭发现入口后不该产出分类区块", library.kindBooks)
    }

    /**
     * 搜索结果里的相对书目/封面按**当前搜索页**落位。搜索页常带查询串且路径可能不是源根：
     * 旧实现用相对源根的 `JsoupHelper.parseUrl`，会把 `book/1.html` 拼到站点根，并丢掉
     * 搜索页所在目录；这里同时锁住「传当前页」与 `TocPageUrl.join` 的查询串处理。
     */
    @Test
    fun `搜索页的相对书目与封面按当前页落位`() = runTest {
        val keyword = java.net.URLEncoder.encode("斗破", "utf-8")
        pages["$BASE/search/list?q=$keyword"] = """
            <html><body>
              <div class="bookbox">
                <a class="name" href="books/1.html">斗破</a>
                <img class="cover" src="covers/1.jpg">
              </div>
            </body></html>
        """.trimIndent()
        val parser = JsoupBookParser(
            BookSourceRule(
                url = BASE,
                searchUrl = "$BASE/search/list?q={{keyword}}",
                ruleSearch = SearchRule(
                    list = ".bookbox",
                    name = ".name",
                    bookUrl = ".name@href",
                    coverUrl = ".cover@src",
                ),
            ),
            client(),
        )

        val book = parser.searchBook("斗破", 1).single()

        assertEquals("$BASE/search/books/1.html", book.noteUrl)
        assertEquals("$BASE/search/covers/1.jpg", book.coverUrl)
    }

    /**
     * 书城分类页同一条解析链也要用**当前分类页**做基准：分类页在 `/kind/list`，
     * 相对书目必须落成 `/kind/books/1.html`，不能用源根。
     */
    @Test
    fun `书城分类页的相对书目按当前分类页落位`() = runTest {
        pages["$BASE/kind/list"] = """
            <html><body>
              <div class="bookbox">
                <a class="name" href="books/1.html">斗破</a>
                <img class="cover" src="covers/1.jpg">
              </div>
            </body></html>
        """.trimIndent()
        val parser = JsoupBookParser(
            BookSourceRule(
                url = BASE,
                ruleFind = FindRule(
                    url = "/kind/list/{{page}}",
                    kinds = listOf(KindItem("玄幻", "xuanhuan")),
                    ruleSearch = SearchRule(
                        list = ".bookbox",
                        name = ".name",
                        bookUrl = ".name@href",
                        coverUrl = ".cover@src",
                    ),
                ),
            ),
            client(),
        )

        val block = parser.fetchLibraryData().kindBooks!!.single()

        assertEquals("$BASE/kind/books/1.html", block.books.single().noteUrl)
        assertEquals("$BASE/kind/covers/1.jpg", block.books.single().coverUrl)
    }

    /**
     * 分类页「加载更多」是另一个入口（`getKindBook`），基准同样必须是**本次请求的那一页**：
     * 第 2 页的书目要相对 `/kind/xuanhuan/list/2` 的目录落位。只锁首屏会让这条路径的落位
     * 退回源根而无人报红——它在页面上表现为「翻到第二页就搜不到的书」。
     */
    @Test
    fun `分类页加载更多的相对书目按当页落位`() = runTest {
        pages["$BASE/kind/xuanhuan/list/2"] = """
            <html><body>
              <div class="bookbox">
                <a class="name" href="books/2.html">斗破</a>
                <img class="cover" src="covers/2.jpg">
              </div>
            </body></html>
        """.trimIndent()
        val parser = JsoupBookParser(
            BookSourceRule(
                url = BASE,
                ruleFind = FindRule(
                    url = "/kind/{{kind}}/list/{{page}}",
                    kinds = listOf(KindItem("玄幻", "xuanhuan")),
                    ruleSearch = SearchRule(
                        list = ".bookbox",
                        name = ".name",
                        bookUrl = ".name@href",
                        coverUrl = ".cover@src",
                    ),
                ),
            ),
            client(),
        )

        val book = parser.getKindBook("xuanhuan", 2).single()

        assertEquals("$BASE/kind/xuanhuan/list/books/2.html", book.noteUrl)
        assertEquals("$BASE/kind/xuanhuan/list/covers/2.jpg", book.coverUrl)
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
