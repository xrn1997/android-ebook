package com.ebook.source.script

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 脚本书源目录翻页链（§7.1）的纯逻辑契约：fetch 闭包喂假页表，无需 transport。
 *
 * 锁的是「与原生 `TocPager` 同一套终止判据」（§12）在脚本驱动方式下的落点：
 * 单页编号、字符串规则逐页跟进（相对链接按当前页落位）、URL 数组固定页序
 * （入口重复元素跳过不终止）、软 404 零新增即到底、反序前缀由既有求值件消费
 * （pager 不再反序）、空 chapterUrl 条目跳过与 contentRef 跨页去重、触顶防御
 * 上限按截断处理且触顶后不再发请求。
 */
class ScriptTocPagerTest {

    private fun rulesOf(tocJson: String): ScriptRuleSet =
        ScriptRuleSet.load("""{"bookSourceUrl":"https://a.com","ruleToc":$tocJson}""")

    private fun pagerOf(set: ScriptRuleSet, pages: Map<String, String>): Pair<ScriptTocPager, EvalContext> {
        val ctx = EvalContext(baseUrl = "https://a.com/toc.html")
        val evaluator = ScriptRuleEvaluator(ctx)
        val extractor = ScriptFieldExtractor(evaluator, "https://a.com")
        val pager = ScriptTocPager(extractor, set) { url ->
            ScriptPage(url, pages[ScriptUrlOption.splitTail(url)?.first ?: url] ?: "")
        }
        return pager to ctx
    }

    private val onePageToc = """
        <ul><li><a href="/c1.html">第一章</a></li><li><a href="/c2.html">第二章</a></li></ul>
    """.trimIndent()

    @Test
    fun `单页目录按出现顺序编号`() = runTest {
        val set = rulesOf("""{"chapterList":"tag.li","chapterName":"tag.a@text","chapterUrl":"tag.a@href"}""")
        val (pager, ctx) = pagerOf(set, mapOf("https://a.com/toc.html" to onePageToc))
        val chapters = pager.collect("https://a.com/toc.html", ctx, noteUrl = "https://a.com/b.html", tag = "https://a.com")
        assertEquals(listOf("第一章", "第二章"), chapters.map { it.durChapterName })
        assertEquals(0, chapters.first().durChapterIndex)
        assertEquals("https://a.com/c2.html", chapters[1].contentRef)
    }

    @Test
    fun `字符串规则 nextTocUrl 逐页跟进且相对链接按当前页落位`() = runTest {
        val set = rulesOf("""{"chapterList":"tag.li","chapterName":"tag.a@text","chapterUrl":"tag.a@href",
            "nextTocUrl":"class.next@href"}""")
        val page1 = onePageToc + """<a class="next" href="toc_2.html">下一页</a>"""
        val page2 = """<ul><li><a href="/c3.html">第三章</a></li></ul>"""  // 无 next：末页
        val (pager, ctx) = pagerOf(
            set,
            mapOf("https://a.com/toc.html" to page1, "https://a.com/toc_2.html" to page2),
        )
        val chapters = pager.collect("https://a.com/toc.html", ctx, noteUrl = "n", tag = "t")
        assertEquals(3, chapters.size)
        assertEquals("https://a.com/c3.html", chapters[2].contentRef)
    }

    @Test
    fun `数组形态 nextTocUrl 按固定页序访问且入口重复元素跳过不终止`() = runTest {
        val set = rulesOf("""{"chapterList":"tag.li","chapterName":"tag.a@text","chapterUrl":"tag.a@href",
            "nextTocUrl":["https://a.com/toc.html","https://a.com/toc_2.html"]}""")
        val (pager, ctx) = pagerOf(
            set,
            mapOf("https://a.com/toc.html" to onePageToc, "https://a.com/toc_2.html" to """<ul><li><a href="/c3.html">第三章</a></li></ul>"""),
        )
        val chapters = pager.collect("https://a.com/toc.html", ctx, noteUrl = "n", tag = "t")
        assertEquals(3, chapters.size)  // 数组首元素=入口：跳过已访问的它，继续第二元素
    }

    @Test
    fun `软404 页零新增即到底不继续翻页`() = runTest {
        val set = rulesOf("""{"chapterList":"tag.li","chapterName":"tag.a@text","chapterUrl":"tag.a@href",
            "nextTocUrl":"class.next@href"}""")
        val page1 = onePageToc + """<a class="next" href="toc_2.html">下一页</a>"""
        val (pager, ctx) = pagerOf(
            set,
            mapOf("https://a.com/toc.html" to page1, "https://a.com/toc_2.html" to onePageToc),  // 重复首页内容
        )
        val chapters = pager.collect("https://a.com/toc.html", ctx, noteUrl = "n", tag = "t")
        assertEquals(2, chapters.size)  // 第二页零新增：到底（§12 判据复用）
    }

    @Test
    fun `chapterList 前导负号把条目集整体反序`() = runTest {
        // 反序由既有件（RuleMode.of/evaluateChain）消费，pager 不再反序——本用例锁的就是这条分工。
        // 夹具用显式 `@@` 形态：2a 锁定的口径是「减号后面没有已知标志就不是反序」
        // （RuleModeTest 有 assertFalse 锁 `-tag.a@text` 无标志形态），无标志链式必须写 `@@` 才成反序
        val set = rulesOf("""{"chapterList":"-@@tag.li","chapterName":"tag.a@text","chapterUrl":"tag.a@href"}""")
        val (pager, ctx) = pagerOf(set, mapOf("https://a.com/toc.html" to onePageToc))
        val chapters = pager.collect("https://a.com/toc.html", ctx, noteUrl = "n", tag = "t")
        assertEquals(listOf("第二章", "第一章"), chapters.map { it.durChapterName })
    }

    @Test
    fun `chapterUrl 为空的条目跳过且 contentRef 跨页去重`() = runTest {
        val set = rulesOf("""{"chapterList":"tag.li","chapterName":"tag.a@text","chapterUrl":"tag.a@href"}""")
        // 同页重复条目（同一 href 出现两次）与跨页去重共用 seenRefs——软 404 用例已锁跨页形态，这里锁同页形态
        val html = """<ul><li><a>无链接章</a></li>""" +
            """<li><a href="/c1.html">第一章</a></li>""" +
            """<li><a href="/c1.html">第一章</a></li></ul>"""
        val (pager, ctx) = pagerOf(set, mapOf("https://a.com/toc.html" to html))
        val chapters = pager.collect("https://a.com/toc.html", ctx, noteUrl = "n", tag = "t")
        assertEquals(1, chapters.size)  // 同页重复也只解出 1 章
    }

    // ===== 防御上限 =====

    @Test
    fun `触顶防御上限按截断处理且不再发请求`() = runTest {
        // 30 页 × 每页 1000 章共 30000 章，经数组形态 nextTocUrl 驱动（不依赖字符串规则求值）：
        // 触顶 20000 后停止追加、停止翻页（与原生 TocPagerTest 的触顶用例同形）
        val urls = (1..30).map { "https://a.com/toc_$it.html" }
        val pages = urls.mapIndexed { index, url ->
            val html = StringBuilder("<ul>")
            for (n in (index * 1000 + 1)..(index + 1) * 1000) {
                html.append("""<li><a href="/c${n}.html">第${n}章</a></li>""")
            }
            url to html.append("</ul>").toString()
        }.toMap()
        val nextArray = urls.joinToString(",", "[", "]") { "\"$it\"" }
        val set = rulesOf("""{"chapterList":"tag.li","chapterName":"tag.a@text","chapterUrl":"tag.a@href",
            "nextTocUrl":$nextArray}""")
        // 计数闭包：30 页可用、只应发 20 次请求——触顶后 fetch 不再被调用
        var fetchCalls = 0
        val ctx = EvalContext(baseUrl = "https://a.com/toc_1.html")
        val extractor = ScriptFieldExtractor(ScriptRuleEvaluator(ctx), "https://a.com")
        val pager = ScriptTocPager(extractor, set) { url ->
            fetchCalls++
            ScriptPage(url, pages[ScriptUrlOption.splitTail(url)?.first ?: url] ?: "")
        }

        val chapters = pager.collect("https://a.com/toc_1.html", ctx, noteUrl = "n", tag = "t")

        assertEquals(ScriptTocPager.MAX_TOC_CHAPTERS, chapters.size)  // 精确截断值 20000
        assertEquals(ScriptTocPager.MAX_TOC_CHAPTERS / 1000, fetchCalls)  // 20 页后不再发请求
        assertEquals(0, chapters.first().durChapterIndex)
        assertEquals(ScriptTocPager.MAX_TOC_CHAPTERS - 1, chapters.last().durChapterIndex)
        assertEquals("https://a.com/c${ScriptTocPager.MAX_TOC_CHAPTERS}.html", chapters.last().contentRef)
    }
}
