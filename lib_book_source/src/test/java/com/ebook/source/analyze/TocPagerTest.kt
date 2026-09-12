package com.ebook.source.analyze

import com.ebook.api.entity.PageRule
import com.ebook.api.entity.TocRule
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [TocPager] 与 [TocPageUrl] 的单元测试（纯 JVM，无 Android 依赖）。
 *
 * 锁住章节索引分页的行为契约：双模式（nextPage 选择器 / pageUrl 模板）、自然终止
 * （零新增 / 无下一页 / 回环 / 软 404）、防御上限触顶截断，以及**无分页配置时与旧
 * 单页实现行为一致**——最后这条是全部存量书源的回归底线。
 */
class TocPagerTest {

    private val sourceRoot = "https://www.test.com"
    private val noteUrl = "$sourceRoot/book/1.html"

    /** 与内置书源同构的最小目录规则：列表、章名、章 URL、下一页选择器 */
    private fun tocRule(nextPage: String = "", pageUrl: String = "") = TocRule(
        list = "#list li",
        name = "a",
        url = "a@href",
        nextPage = nextPage,
        pageUrl = pageUrl,
    )

    /** 构造一节目录页 HTML：[chapters] 为 (章名, 相对链接) 对，nextPage 为「下一页」链接 */
    private fun tocPage(vararg chapters: Pair<String, String>, nextPage: String? = null): String {
        val lis = chapters.joinToString("") { (name, href) -> """<li><a href="$href">$name</a></li>""" }
        val next = nextPage?.let { """<div class="pager"><a class="next" href="$it">下一页</a></div>""" } ?: ""
        return "<html><body><ul id=\"list\">$lis</ul>$next</body></html>"
    }

    /** 假站点：URL → HTML 的映射 + 请求顺序记录，未登记的 URL 直接抛错（暴露意料外的请求） */
    private class FakeSite(
        pages: Map<String, String>,
        private val strict: Boolean = true,
    ) {
        val calls = mutableListOf<String>()
        private val pages = pages.toMutableMap()

        val fetch: suspend (String) -> String = { url ->
            calls.add(url)
            val page = pages[url]
            if (page == null && strict) throw IllegalStateException("意外请求: $url") else page ?: ""
        }

        fun add(url: String, html: String) {
            pages[url] = html
        }
    }

    // ===== 无分页配置：与旧单页实现行为一致 =====

    @Test
    fun `无分页配置时只请求索引页一次`() = runBlocking {
        val site = FakeSite(
            mapOf(
                "$sourceRoot/book/1/" to tocPage(
                    "第一章" to "/book/1/c1.html",
                    "第二章" to "/book/1/c2.html",
                    nextPage = "/book/1/index_2.html",
                )
            )
        )

        val chapters = TocPager.collect(
            entryUrl = "$sourceRoot/book/1/",
            tocRule = tocRule(),
            pageParam = PageRule(),
            sourceRoot = sourceRoot,
            noteUrl = noteUrl,
            tag = sourceRoot,
            fetch = site.fetch,
        )

        assertEquals(listOf("$sourceRoot/book/1/"), site.calls)
        assertEquals(2, chapters.size)
        assertEquals("第一章", chapters[0].durChapterName)
        assertEquals("$sourceRoot/book/1/c1.html", chapters[0].contentRef)
        assertEquals(0, chapters[0].durChapterIndex)
        assertEquals(1, chapters[1].durChapterIndex)
        assertEquals(noteUrl, chapters[0].noteUrl)
        assertEquals(sourceRoot, chapters[0].tag)
    }

    @Test
    fun `选择器为空的索引页返回空列表且只请求一次`() = runBlocking {
        val site = FakeSite(mapOf("$sourceRoot/book/1/" to "<html><body>无章节</body></html>"))

        val chapters = TocPager.collect(
            entryUrl = "$sourceRoot/book/1/",
            tocRule = tocRule(),
            pageParam = PageRule(),
            sourceRoot = sourceRoot,
            noteUrl = noteUrl,
            tag = sourceRoot,
            fetch = site.fetch,
        )

        assertEquals(1, site.calls.size)
        assertEquals(0, chapters.size)
    }

    // ===== nextPage 选择器模式 =====

    @Test
    fun `选择器模式跨页拼接且索引连续`() = runBlocking {
        val site = FakeSite(
            mapOf(
                "$sourceRoot/book/1/" to tocPage(
                    "第一章" to "/book/1/c1.html",
                    nextPage = "/book/1/index_2.html",
                ),
                "$sourceRoot/book/1/index_2.html" to tocPage(
                    "第二章" to "/book/1/c2.html",
                    nextPage = "index_3.html",
                ),
                "$sourceRoot/book/1/index_3.html" to tocPage(
                    "第三章" to "/book/1/c3.html",
                ),
            )
        )

        val chapters = TocPager.collect(
            entryUrl = "$sourceRoot/book/1/",
            tocRule = tocRule(nextPage = "a.next@href"),
            pageParam = PageRule(),
            sourceRoot = sourceRoot,
            noteUrl = noteUrl,
            tag = sourceRoot,
            fetch = site.fetch,
        )

        assertEquals(
            listOf("$sourceRoot/book/1/", "$sourceRoot/book/1/index_2.html", "$sourceRoot/book/1/index_3.html"),
            site.calls,
        )
        assertEquals(listOf("第一章", "第二章", "第三章"), chapters.map { it.durChapterName })
        assertEquals((0..2).toList(), chapters.map { it.durChapterIndex })
    }

    @Test
    fun `选择器模式回环链接被已访问集合拦下`() = runBlocking {
        val site = FakeSite(
            mapOf(
                "$sourceRoot/book/1/" to tocPage("第一章" to "/book/1/c1.html", nextPage = "/book/1/index_2.html"),
                // 第 2 页的「下一页」指回首页：回环
                "$sourceRoot/book/1/index_2.html" to tocPage("第二章" to "/book/1/c2.html", nextPage = "/book/1/"),
            )
        )

        val chapters = TocPager.collect(
            entryUrl = "$sourceRoot/book/1/",
            tocRule = tocRule(nextPage = "a.next@href"),
            pageParam = PageRule(),
            sourceRoot = sourceRoot,
            noteUrl = noteUrl,
            tag = sourceRoot,
            fetch = site.fetch,
        )

        assertEquals(2, site.calls.size)
        assertEquals(2, chapters.size)
    }

    @Test
    fun `软404页（新地址重复首页内容）零新增即终止`() = runBlocking {
        val entryPage = tocPage(
            "第一章" to "/book/1/c1.html",
            "第二章" to "/book/1/c2.html",
            nextPage = "/book/1/index_2.html",
        )
        val site = FakeSite(
            mapOf(
                "$sourceRoot/book/1/" to entryPage,
                // 越界页：HTTP 200 重复返回首页内容（软 404），还带着下一页链接
                "$sourceRoot/book/1/index_2.html" to entryPage,
            )
        )

        val chapters = TocPager.collect(
            entryUrl = "$sourceRoot/book/1/",
            tocRule = tocRule(nextPage = "a.next@href"),
            pageParam = PageRule(),
            sourceRoot = sourceRoot,
            noteUrl = noteUrl,
            tag = sourceRoot,
            fetch = site.fetch,
        )

        assertEquals(2, site.calls.size)
        assertEquals(2, chapters.size)
    }

    @Test
    fun `同页重复条目去重只保留首个`() = runBlocking {
        val site = FakeSite(
            mapOf(
                "$sourceRoot/book/1/" to tocPage(
                    "第一章" to "/book/1/c1.html",
                    "第一章" to "/book/1/c1.html",
                )
            )
        )

        val chapters = TocPager.collect(
            entryUrl = "$sourceRoot/book/1/",
            tocRule = tocRule(),
            pageParam = PageRule(),
            sourceRoot = sourceRoot,
            noteUrl = noteUrl,
            tag = sourceRoot,
            fetch = site.fetch,
        )

        assertEquals(1, chapters.size)
        assertEquals(0, chapters[0].durChapterIndex)
    }

    @Test
    fun `粘性重复条目（部分重叠页）不误判终止`() = runBlocking {
        // 每页重复上一页最后一章 + 新章：部分重叠不算零新增，必须继续
        val site = FakeSite(
            mapOf(
                "$sourceRoot/book/1/" to tocPage("第一章" to "/book/1/c1.html", nextPage = "/book/1/index_2.html"),
                "$sourceRoot/book/1/index_2.html" to tocPage(
                    "第一章" to "/book/1/c1.html",
                    "第二章" to "/book/1/c2.html",
                    nextPage = "/book/1/index_3.html",
                ),
                "$sourceRoot/book/1/index_3.html" to tocPage(
                    "第二章" to "/book/1/c2.html",
                    "第三章" to "/book/1/c3.html",
                ),
            )
        )

        val chapters = TocPager.collect(
            entryUrl = "$sourceRoot/book/1/",
            tocRule = tocRule(nextPage = "a.next@href"),
            pageParam = PageRule(),
            sourceRoot = sourceRoot,
            noteUrl = noteUrl,
            tag = sourceRoot,
            fetch = site.fetch,
        )

        assertEquals(3, site.calls.size)
        assertEquals(3, chapters.size)
        assertEquals(listOf("第一章", "第二章", "第三章"), chapters.map { it.durChapterName })
    }

    // ===== pageUrl 模板模式 =====

    @Test
    fun `模板模式按索引页目录逐页推算`() = runBlocking {
        val site = FakeSite(
            mapOf(
                "$sourceRoot/book/1/" to tocPage("第一章" to "/book/1/c1.html"),
                "$sourceRoot/book/1/index_2.html" to tocPage("第二章" to "/book/1/c2.html"),
                "$sourceRoot/book/1/index_3.html" to tocPage("第三章" to "/book/1/c3.html"),
                // 末页：空章节列表 → 零新增终止
                "$sourceRoot/book/1/index_4.html" to tocPage(),
            )
        )

        val chapters = TocPager.collect(
            entryUrl = "$sourceRoot/book/1/",
            tocRule = tocRule(pageUrl = "index_{{page}}.html"),
            pageParam = PageRule(),
            sourceRoot = sourceRoot,
            noteUrl = noteUrl,
            tag = sourceRoot,
            fetch = site.fetch,
        )

        assertEquals(
            listOf(
                "$sourceRoot/book/1/",
                "$sourceRoot/book/1/index_2.html",
                "$sourceRoot/book/1/index_3.html",
                "$sourceRoot/book/1/index_4.html",
            ),
            site.calls,
        )
        assertEquals(3, chapters.size)
        assertEquals(2, chapters[2].durChapterIndex)
    }

    @Test
    fun `文件式索引页的模板落在同目录`() = runBlocking {
        val site = FakeSite(
            mapOf(
                "$sourceRoot/book/1/index.html" to tocPage("第一章" to "/book/1/c1.html"),
                "$sourceRoot/book/1/index_2.html" to tocPage("第二章" to "/book/1/c2.html"),
                "$sourceRoot/book/1/index_3.html" to tocPage(),
            )
        )

        val chapters = TocPager.collect(
            entryUrl = "$sourceRoot/book/1/index.html",
            tocRule = tocRule(pageUrl = "index_{{page}}.html"),
            pageParam = PageRule(),
            sourceRoot = sourceRoot,
            noteUrl = noteUrl,
            tag = sourceRoot,
            fetch = site.fetch,
        )

        assertEquals(
            listOf(
                "$sourceRoot/book/1/index.html",
                "$sourceRoot/book/1/index_2.html",
                "$sourceRoot/book/1/index_3.html",
            ),
            site.calls,
        )
        assertEquals(2, chapters.size)
    }

    @Test
    fun `模板软404（越界页重复首页）零新增即终止`() = runBlocking {
        val entryPage = tocPage("第一章" to "/book/1/c1.html")
        val site = FakeSite(
            mapOf(
                "$sourceRoot/book/1/" to entryPage,
                "$sourceRoot/book/1/index_2.html" to entryPage,
                "$sourceRoot/book/1/index_3.html" to entryPage,
            )
        )

        val chapters = TocPager.collect(
            entryUrl = "$sourceRoot/book/1/",
            tocRule = tocRule(pageUrl = "index_{{page}}.html"),
            pageParam = PageRule(),
            sourceRoot = sourceRoot,
            noteUrl = noteUrl,
            tag = sourceRoot,
            fetch = site.fetch,
        )

        // 第 2 页零新增即终止，第 3 页不会被请求
        assertEquals(2, site.calls.size)
        assertEquals(1, chapters.size)
    }

    @Test
    fun `nextPage 优先于 pageUrl 模板`() = runBlocking {
        val site = FakeSite(
            mapOf(
                "$sourceRoot/book/1/" to tocPage("第一章" to "/book/1/c1.html", nextPage = "/book/1/p2.html"),
                "$sourceRoot/book/1/p2.html" to tocPage("第二章" to "/book/1/c2.html"),
            )
        )

        val chapters = TocPager.collect(
            entryUrl = "$sourceRoot/book/1/",
            tocRule = tocRule(nextPage = "a.next@href", pageUrl = "index_{{page}}.html"),
            pageParam = PageRule(),
            sourceRoot = sourceRoot,
            noteUrl = noteUrl,
            tag = sourceRoot,
            fetch = site.fetch,
        )

        // 走的是选择器给出的 p2.html，不是模板推算的 index_2.html
        assertEquals(
            listOf("$sourceRoot/book/1/", "$sourceRoot/book/1/p2.html"),
            site.calls,
        )
        assertEquals(2, chapters.size)
    }

    // ===== 防御上限 =====

    @Test
    fun `触顶防御上限按截断处理且不再发请求`() = runBlocking {
        // 30 页 × 每页 1000 章共 30000 章：触顶 20000 后停止追加、停止翻页
        val pages = mutableMapOf(
            "$sourceRoot/book/1/" to tocPage(*genChapters(0, 1000).toTypedArray(), nextPage = "/book/1/p2.html")
        )
        for (p in 2..30) {
            pages["$sourceRoot/book/1/p$p.html"] =
                tocPage(*genChapters((p - 1) * 1000, p * 1000).toTypedArray(), nextPage = "/book/1/p${p + 1}.html")
        }
        val site = FakeSite(pages)

        val chapters = TocPager.collect(
            entryUrl = "$sourceRoot/book/1/",
            tocRule = tocRule(nextPage = "a.next@href"),
            pageParam = PageRule(),
            sourceRoot = sourceRoot,
            noteUrl = noteUrl,
            tag = sourceRoot,
            fetch = site.fetch,
        )

        assertEquals(TocPager.MAX_TOC_CHAPTERS, chapters.size)
        assertEquals(TocPager.MAX_TOC_CHAPTERS / 1000, site.calls.size)
        assertEquals(0, chapters.first().durChapterIndex)
        assertEquals(TocPager.MAX_TOC_CHAPTERS - 1, chapters.last().durChapterIndex)
    }

    /** 生成 [from] 直到 [to]（不含）的章名-链接对 */
    private fun genChapters(from: Int, to: Int): List<Pair<String, String>> =
        (from until to).map { "第${it + 1}章" to "/book/1/c${it + 1}.html" }

    // ===== TocPageUrl：模板地址推算 =====

    @Test
    fun `目录式索引页的相对模板落在同目录`() {
        assertEquals(
            "$sourceRoot/book/1/index_2.html",
            TocPageUrl.resolve("$sourceRoot/book/1/", "index_{{page}}.html", 2, PageRule(), sourceRoot),
        )
    }

    @Test
    fun `文件式索引页的相对模板替换末段`() {
        assertEquals(
            "$sourceRoot/book/1/list_2.html",
            TocPageUrl.resolve("$sourceRoot/book/1/index.html", "list_{{page}}.html", 2, PageRule(), sourceRoot),
        )
    }

    @Test
    fun `源根相对模板拼上源根`() {
        assertEquals(
            "$sourceRoot/book/1/toc_2.html",
            TocPageUrl.resolve("$sourceRoot/book/1/", "/book/1/toc_{{page}}.html", 2, PageRule(), sourceRoot),
        )
    }

    @Test
    fun `绝对地址模板原样透传`() {
        assertEquals(
            "https://cdn.test.com/toc/2.html",
            TocPageUrl.resolve("$sourceRoot/book/1/", "https://cdn.test.com/toc/{{page}}.html", 2, PageRule(), sourceRoot),
        )
    }

    @Test
    fun `协议相对地址沿用基准 scheme 且优先于源根相对形态`() {
        // 真语料「小说屋」：og:novel:read_url 的 meta 产出 //host/path（站点双协议服务的网页惯用法）
        assertEquals(
            "https://cdn.test.com/toc/2.html",
            TocPageUrl.resolve("$sourceRoot/book/1/", "//cdn.test.com/toc/{{page}}.html", 2, PageRule(), sourceRoot),
        )
        // scheme 来自源根：http 源根下沿用 http
        assertEquals(
            "http://cdn.test.com/toc/2.html",
            TocPageUrl.join("http://www.test.com/book/1/", "//cdn.test.com/toc/2.html", "http://www.test.com"),
        )
        // 必须排在 / 形态之前：顺序反了会落成 https://www.test.com//cdn.test.com/toc/2.html
        assertTrue(
            TocPageUrl.join("$sourceRoot/book/1/", "//cdn.test.com/toc/2.html", sourceRoot)
                .startsWith("https://cdn.test.com"),
        )
    }

    @Test
    fun `页码按 PageRule 起始页与步长换算`() {
        val pageRule = PageRule(start = 2, step = 2)
        assertEquals(
            "$sourceRoot/book/1/index_4.html",
            TocPageUrl.resolve("$sourceRoot/book/1/", "index_{{page}}.html", 2, pageRule, sourceRoot),
        )
        assertEquals(
            "$sourceRoot/book/1/index_6.html",
            TocPageUrl.resolve("$sourceRoot/book/1/", "index_{{page}}.html", 3, pageRule, sourceRoot),
        )
    }
}
