package com.ebook.source.analyze

import com.ebook.api.entity.BookSourceRule
import com.ebook.api.entity.SearchRule
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 原生（JSON 规则）书源解析器的搜索面字段口径。
 *
 * 只测解析、不碰网络：直接打 [JsoupBookParser.parseSearchBookWithRule]，
 * 因为它是 `searchBook` 与书城分类（`fetchLibraryData`）共用的一条链，
 * 一次断言同时锁住两个入口。选择器形态按 `docs/book-source-rules.md` 的原生规则口径
 * （文本用纯 CSS，属性用 `@href` / `@src`），不是脚本源那套链式语法。
 */
class JsoupBookParserSearchTest {

    /** 一条完整搜索结果：书名、作者、简介各占一个节点 */
    private val html = """
        <div class="bookbox">
          <h4 class="book-name"><a href="/book/1.html">强化子嗣</a></h4>
          <p class="book-author">笔墨三石</p>
          <p class="book-intro">一枚子嗣面板，让他在万界苟了八万年</p>
        </div>
    """.trimIndent()

    private fun parse(html: String, searchRule: SearchRule) = JsoupBookParser(
        BookSourceRule(name = "示例源", url = "https://www.example.com", ruleSearch = searchRule),
        OkHttpClient(),
    ).parseSearchBookWithRule(html, searchRule)

    private val baseRule = SearchRule(
        list = ".bookbox",
        name = ".book-name",
        author = ".book-author",
        bookUrl = ".book-name a@href",
    )

    @Test
    fun `配了 intro 时搜索条目解析出简介`() {
        val books = parse(html, baseRule.copy(intro = ".book-intro"))

        assertEquals(1, books.size)
        assertEquals("一枚子嗣面板，让他在万界苟了八万年", books.single().desc)
    }

    /**
     * 回归护栏（不是新行为）：规则没写 `intro` 时简介必须留空。
     * 列表项第二行按「简介 → 最新章」降级，靠的就是「没解析出来 = 空串」这个区分——
     * 若哪天这里被误填成书名或作者，页面会永远显示简介那一格而不再回落。
     */
    @Test
    fun `没配 intro 时简介留空而不是拿别的字段顶`() {
        val books = parse(html, baseRule)

        assertEquals("", books.single().desc)
    }

    /**
     * 书城分类行的 item key 因此不能取 `noteUrl`（`BookstorePage` 的 `KindBookSection`）：
     * 解析器只挡空 url，同一页里指向同一详情页的两条（推荐位重复挂出、或规则的选择器
     * 恰好套住了嵌套节点）会照收，去重不在这条链路上发生。
     */
    @Test
    fun `同一份结果里允许出现重复 noteUrl`() {
        val duplicated = """
            <div class="bookbox"><h4 class="book-name"><a href="/book/1.html">强化子嗣</a></h4></div>
            <div class="bookbox"><h4 class="book-name"><a href="/book/1.html">强化子嗣（重复推荐位）</a></h4></div>
        """.trimIndent()

        val books = parse(duplicated, baseRule)

        assertEquals(2, books.size)
        assertEquals(books[0].noteUrl, books[1].noteUrl)
    }
}
