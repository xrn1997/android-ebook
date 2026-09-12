package com.ebook.source.script

import com.ebook.db.entity.BookShelfEntity
import com.ebook.source.analyze.ScriptBookParser
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 脚本书源的端到端合成金标准（Plan 2d Task 9）。
 *
 * 与 2a~2c 各层单测的分工：那些用例锁「一条规则怎么解」，本类锁「五面装配起来后，
 * 一本书从搜到读走不走得通」——`ScriptBookParser` 把 URL 渲染、取文、字段提取、目录链、
 * 正文链和净化串成一条链，链上的每一环单测都绿、串起来仍可能断（落位基准没推进、
 * 翻页 URL 用错基准、正文拼接丢了段落），这类缺陷只在整链上显形。
 *
 * **为什么是合成源而不是真实语料**：ADR-0029 决策 8 要的「真实源 fixtures 锁语义」已由
 * [ScriptRealSourceGoldenTest] + `src/test/resources/scripted_real/` 偿还——四条声明式源
 * （无极书院/手机看书/阅读书屋/网阅小说）的真规则串配真响应快照，直接锁「上游真实规则在真实 HTML
 * 上解得对不对」。真实源语义不再靠本类顶位，本类留下的价值是**编排层补充**：合成源字段最小、
 * 假页表可控，能独立于真站点把装配链的每一环逼出来。含 JS 的重源仍归真机临时验证（登记见
 * `docs/test-coverage-todo.md`），不转永久夹具。
 *
 * 假件打的是 [ScriptTransport]（取文接缝），不发真请求：断言「请求长什么样、回什么文本」
 * 就够覆盖装配层，OkHttp 侧行为归传输层测试与装机验证。
 */
class ScriptSourceEndToEndTest {

    /**
     * 假页表：URL → 响应文本。
     *
     * 键就是 [ScriptRequest.url]——选项尾段（`,{...}`）在 URL 解析层已被剥掉，
     * 到传输接缝时是干净的落点地址，所以这里不再自己 splitTail（做了就是第二次剥，
     * 会把带真实尾段语义的地址键错）。未命中回空串，与真传输「拿到空响应体」同形，
     * 让选择器失配自然显形为「没取到值」而不是假件替它编内容。
     */
    private class TableTransport(private val table: Map<String, String>) : ScriptTransport {
        val requests = mutableListOf<ScriptRequest>()

        override suspend fun execute(request: ScriptRequest): String {
            requests += request
            return table[request.url] ?: ""
        }
    }

    @Test
    fun `HTML 链式型源走通搜索详情目录正文全链`() = runTest {
        val transport = TableTransport(
            mapOf(
                "https://www.example.com/search?key=%E5%87%A1%E4%BA%BA&page=1" to """
                    <div class="bookbox"><h4><a href="/book/1.html">凡人修仙传</a></h4>
                    <p>忘语</p><img src="/c/1.jpg"/></div>""",
                "https://www.example.com/book/1.html" to """
                    <h1>凡人修仙传</h1><i class="info">忘语</i><i class="intro">仙侠</i>
                    <img src="/c/1.jpg"/><a class="catalog" href="/book/1/toc.html">目录</a>""",
                "https://www.example.com/book/1/toc.html" to """
                    <ul><li><a href="/c1.html">第一章</a></li></ul><a class="next" href="toc_2.html">下一页</a>""",
                "https://www.example.com/book/1/toc_2.html" to """
                    <ul><li><a href="/c2.html">第二章</a></li></ul>""",
                "https://www.example.com/c1.html" to """
                    <div id="content">第一章正文<br/>本章未完，点击继续阅读</div><a class="next" href="c1_2.html">下一页</a>""",
                "https://www.example.com/c1_2.html" to """<div id="content">第一章后半</div>""",
            )
        )
        val parser = ScriptBookParser(ScriptFixtureSources.htmlChain, transport)

        val books = parser.searchBook("凡人", 1)
        assertEquals("凡人修仙传", books.single().name)
        assertEquals("https://www.example.com/book/1.html", books.single().noteUrl)
        assertEquals("搜索条目的归属必须是本源", "https://www.example.com", books.single().tag)

        val shelf = BookShelfEntity().apply {
            noteUrl = books.single().noteUrl
            tag = books.single().tag
        }
        assertEquals(
            "详情页解出的 tocUrl 必须落位成绝对地址并挂进 bookInfo.chapterUrl",
            "https://www.example.com/book/1/toc.html",
            parser.getBookInfo(shelf).bookInfo!!.chapterUrl,
        )

        val chapters = parser.getChapterList(shelf).data.chapterList
        assertEquals(listOf("第一章", "第二章"), chapters.map { it.durChapterName })
        assertEquals(
            "目录第二页的章节也要按入口页的基准落位",
            "https://www.example.com/c2.html",
            chapters[1].contentRef,
        )

        val text = parser.fetchChapterText(chapters.first().contentRef)
        // 净化正则含前导 \n：把删段连同它前面的换行一起吃掉，段落之间不残留空行
        assertEquals("第一章正文\n第一章后半", text)
    }

    @Test
    fun `JSONPath API 型源走通全链且 POST 体带编码关键词`() = runTest {
        val transport = TableTransport(
            mapOf(
                "https://api.example.com/v1/books" to """
                    {"data":{"books":[{"name":"凡人修仙传","author":"忘语",
                    "url":"https://api.example.com/v1/book/1","cover":"https://api.example.com/c/1.jpg",
                    "summary":"仙侠"}]}}""",
                "https://api.example.com/v1/book/1" to """
                    {"name":"凡人修仙传","author":"忘语","summary":"仙侠巨著",
                    "tocUrl":"https://api.example.com/v1/book/1/toc"}""",
                "https://api.example.com/v1/book/1/toc" to """
                    {"chapters":[{"title":"第一章","url":"https://api.example.com/v1/c/1"}]}""",
                "https://api.example.com/v1/c/1" to """{"content":"第一章的 JSON 正文"}""",
            )
        )
        val parser = ScriptBookParser(ScriptFixtureSources.jsonApi, transport)

        assertEquals(1, parser.searchBook("凡人", 1).size)
        // 中文关键词必须已百分号编码、页码必须已回填——两者都在 URL 渲染层做完才交给选项体
        assertEquals("POST", transport.requests.first().method)
        assertEquals("kw=%E5%87%A1%E4%BA%BA&pn=1", transport.requests.first().body)

        val shelf = BookShelfEntity().apply { noteUrl = "https://api.example.com/v1/book/1" }
        val chapters = parser.getChapterList(parser.getBookInfo(shelf)).data.chapterList
        assertEquals(listOf("第一章"), chapters.map { it.durChapterName })
        assertEquals("第一章的 JSON 正文", parser.fetchChapterText(chapters.single().contentRef))
    }

    @Test
    fun `尖括号分页型搜索首页不带页码段`() = runTest {
        val transport = TableTransport(
            mapOf(
                "https://www.example.com/s" to
                    """<div class="bookbox"><h4><a href="/book/1.html">书</a></h4></div>""",
            )
        )
        val parser = ScriptBookParser(ScriptFixtureSources.anglePage, transport)

        assertEquals(1, parser.searchBook("k", 1).size)
        // 页码 1：`<,1>` 整段（含分隔符）不进 URL（§6.4 本仓规定，2c 已锁形——此处锁装配层不回退）
        assertEquals("https://www.example.com/s", transport.requests.single().url)
    }

    @Test
    fun `尖括号分页型第二页只输出页码内容`() = runTest {
        val transport = TableTransport(
            mapOf(
                "https://www.example.com/s2" to
                    """<div class="bookbox"><h4><a href="/book/2.html">另一本</a></h4></div>""",
            )
        )
        val parser = ScriptBookParser(ScriptFixtureSources.anglePage, transport)

        val books = parser.searchBook("k", 2)
        // §6.4 本仓规定：`<分隔符,内容>` 里逗号是**语法分隔符**，不是要发出去的字面量；
        // 本源的分隔符是首逗号之前的空串，所以页 2 得到 `/s2` 而不是 `/s,2`（2c 的
        // ScriptUrlResolverTest 已锁同一读法，本例锁装配层不改口）
        assertEquals("https://www.example.com/s2", transport.requests.single().url)
        assertEquals(listOf("另一本"), books.map { it.name })
    }
}
