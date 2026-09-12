package com.ebook.source.analyze

import com.ebook.db.entity.BookShelfEntity
import com.ebook.source.script.ScriptRuleParseException
import com.ebook.source.script.ScriptRequest
import com.ebook.source.script.ScriptTransport
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `ScriptBookParser` 的骨架契约（2d Task 1）、搜索链路（Task 2）、发现链路（Task 3）与详情链路（Task 4）。
 *
 * 骨架面只锁一件事——「脚本行永不 null、不在构造期失败」的 getParserFor 契约
 * 在坏 JSON 上的落点：失败必须等到首次求值、以类型化异常如实报（§12）。
 * 搜索面锁 searchUrl 渲染（`{{key}}` 编码、POST 选项）、条目提取（四类列表结果）、
 * URL 落位（相对地址 + 选项尾段回附）与条目判据。
 * 发现面锁 exploreUrl 条目切分后的逐分类抓取、ruleExplore 回落 ruleSearch、
 * 未配 exploreUrl 的配置形态与单分类失败容忍（口径对齐原生 `JsoupBookParser`）。
 * 详情面锁 ruleBookInfo 逐字段求值、init 的 AllInOne 条目上下文（组引用）、
 * tocUrl 相对落位与为空回落详情页地址（口径对齐原生 `JsoupBookParser.getBookInfo`）。
 */
class ScriptBookParserTest {

    /**
     * 断言「请求长什么样」的假传输：2d 各 Task 共用（与 2c 的 RecordingTransport 同法）。
     * [throwAt] 命中该 URL 时抛 `RuntimeException`，供异常路径用例模拟传输层故障
     * （网络中断等在真实客户端上以异常上抛的形态）；默认 null 即纯录制回放。
     */
    private class RecordingTransport(
        vararg pages: Pair<String, String>,
        private val throwAt: String? = null,
    ) : ScriptTransport {
        val requests = mutableListOf<ScriptRequest>()
        private val table = pages.toMap()
        override suspend fun execute(request: ScriptRequest): String {
            requests += request
            if (request.url == throwAt) throw RuntimeException("boom")
            return table[request.url] ?: ""
        }
    }

    @Test
    fun `构造期不解析原始 JSON 坏行在首次求值才类型化失败`() = runTest {
        val parser = ScriptBookParser("{broken json", RecordingTransport())
        // 构造不抛：getParserFor 的契约是「脚本行永不 null、不在构造期失败」，
        // 坏行必须等到求值才以类型化异常如实报（§12）。searchBook 第一行就摸 rules（惰性装载），
        // 异常原样穿透（不裹 runCatching）——完整搜索行为由 Task 2 锁，本用例只锁装载失败时点与类型
        val error = runCatching { parser.searchBook("凡人", 1) }.exceptionOrNull()
        assertTrue(error is ScriptRuleParseException)
    }

    /** HTML 链式源的搜索夹具：三个 bookbox，第三条 bookUrl 为空（条目判据用） */
    private val htmlSource = """
        {"bookSourceUrl":"https://www.example.com","bookSourceName":"示例",
         "searchUrl":"/search?key={{key}}&page={{page}}",
         "ruleSearch":{"bookList":"class.bookbox","name":"tag.h4@text",
           "bookUrl":"tag.a@href","author":"tag.p@text","coverUrl":"tag.img@src",
           "lastChapter":"tag.span@text","kind":"class.kind@text","intro":"class.intro@text"}}
    """.trimIndent()

    private val searchHtml = """
        <div class="bookbox"><h4><a href="/book/123.html">凡人修仙传</a></h4>
        <p>忘语</p><img src="/cover/123.jpg"/><span>第两千章</span>
        <i class="kind">仙侠</i><i class="intro">一个山村少年的逆袭</i></div>
        <div class="bookbox"><h4><a href="/book/456.html">凡人修仙之仙界篇</a></h4></div>
        <div class="bookbox"><h4><a href="">空链接的书</a></h4></div>
    """.trimIndent()

    @Test
    fun `HTML 链式源搜索出条目并完成相对落位与条目判据`() = runTest {
        val transport = RecordingTransport("https://www.example.com/search?key=%E5%87%A1%E4%BA%BA&page=1" to searchHtml)
        val parser = ScriptBookParser(htmlSource, transport)
        val books = parser.searchBook("凡人", 1)
        assertEquals(2, books.size)  // 第三条 bookUrl 为空：条目判据（§1.2）不收录
        val first = books.first()
        assertEquals("凡人修仙传", first.name)
        assertEquals("忘语", first.author)
        assertEquals("https://www.example.com/book/123.html", first.noteUrl)
        assertEquals("https://www.example.com/cover/123.jpg", first.coverUrl)
        assertEquals("第两千章", first.lastChapter)
        assertEquals("仙侠", first.kind)
        assertEquals("一个山村少年的逆袭", first.desc)
        assertEquals("https://www.example.com", first.tag)
        assertEquals("示例", first.origin)
    }

    @Test
    fun `中文关键词经 URLEncoder 编码进请求地址`() = runTest {
        val transport = RecordingTransport("https://www.example.com/search?key=%E5%87%A1%E4%BA%BA&page=1" to searchHtml)
        ScriptBookParser(htmlSource, transport).searchBook("凡人", 1)
        assertEquals(1, transport.requests.size)
        assertTrue(transport.requests.single().url.contains("key=%E5%87%A1%E4%BA%BA"))
    }

    @Test
    fun `JSONPath API 型源搜索出条目`() = runTest {
        val jsonSource = """{"bookSourceUrl":"https://api.example.com",
            "searchUrl":"https://api.example.com/books?kw={{key}}",
            "ruleSearch":{"bookList":"$.data.books","name":"$.name","author":"$.author","bookUrl":"$.url"}}"""
        val body = """{"data":{"books":[{"name":"凡人修仙传","author":"忘语","url":"https://api.example.com/b/1"}]}}"""
        val parser = ScriptBookParser(jsonSource, RecordingTransport("https://api.example.com/books?kw=%E5%87%A1%E4%BA%BA" to body))
        val books = parser.searchBook("凡人", 1)
        assertEquals(listOf("凡人修仙传"), books.map { it.name })
        assertEquals("https://api.example.com/b/1", books.single().noteUrl)
    }

    @Test
    fun `POST 选项把表单体带进请求`() = runTest {
        val postSource = """{"bookSourceUrl":"https://www.example.com",
            "searchUrl":"/search.php,{\"method\":\"POST\",\"body\":\"key={{key}}&page={{page}}\"}",
            "ruleSearch":{"bookList":"class.bookbox","name":"tag.h4@text","bookUrl":"tag.a@href"}}"""
        val transport = RecordingTransport("https://www.example.com/search.php" to searchHtml)
        ScriptBookParser(postSource, transport).searchBook("凡人", 2)
        val request = transport.requests.single()
        assertEquals("POST", request.method)
        assertEquals("key=%E5%87%A1%E4%BA%BA&page=2", request.body)
    }

    @Test
    fun `bookUrl 携带选项尾段时原样保留在 noteUrl 上`() = runTest {
        // 尾段是「下一次取文的输入」的一部分（§9 第 7 步）：字段落位只处理地址部分，
        // 尾段回附，详情页取文时由 ScriptUrlResolver 消费。
        // 注意夹具：href 值含双引号 JSON，HTML 属性必须用单引号包裹（双引号会截断属性值）——
        // 计划原文用双引号 + \" 是错的（HTML 不处理反斜杠转义），按这里的单引号写法落地
        val source = """{"bookSourceUrl":"https://www.example.com",
            "searchUrl":"/s","ruleSearch":{"bookList":"class.bookbox",
            "name":"tag.h4@text","bookUrl":"tag.a@href"}}"""
        val html = """<div class="bookbox"><h4><a href='/book/123.html,{"charset":"gbk"}'>书</a></h4></div>"""
        val books = ScriptBookParser(source, RecordingTransport("https://www.example.com/s" to html)).searchBook("k", 1)
        assertEquals("https://www.example.com/book/123.html,{\"charset\":\"gbk\"}", books.single().noteUrl)
    }

    @Test
    fun `未配 searchUrl 的源搜索返回空列表而不是报错`() = runTest {
        val source = """{"bookSourceUrl":"https://www.example.com","bookSourceName":"无搜索"}"""
        assertTrue(ScriptBookParser(source, RecordingTransport()).searchBook("凡人", 1).isEmpty())
    }

    /** 发现链路夹具：exploreUrl 两个文本条目（`名称::URL`），ruleExplore 语料与 ruleSearch 同构 */
    private val exploreSource = """
        {"bookSourceUrl":"https://www.example.com","bookSourceName":"示例",
         "exploreUrl":"男生书库::/shuku/0_1_0_0_0_{{page}}_0_0\n男频连载::/shuku/0_2_0_0_0_{{page}}_0_0",
         "ruleExplore":{"bookList":"class.bookbox","name":"tag.h4@text","bookUrl":"tag.a@href"}}
    """.trimIndent()

    @Test
    fun `fetchLibraryData 逐分类抓首页且空响应分类出空区块`() = runTest {
        val transport = RecordingTransport(
            "https://www.example.com/shuku/0_1_0_0_0_1_0_0" to searchHtml,   // 第 1 页 {{page}}=1
            "https://www.example.com/shuku/0_2_0_0_0_1_0_0" to "",            // 第 2 分类空响应
        )
        val entity = ScriptBookParser(exploreSource, transport).fetchLibraryData()
        val blocks = entity.kindBooks.orEmpty()
        assertEquals(2, blocks.size)
        assertEquals("男生书库", blocks[0].kindName)
        assertEquals(2, blocks[0].books.size)
        // 成功路径 kindUrl 保留模板（可翻页）；失败路径置空——两者互补，见下方异常用例
        assertEquals("/shuku/0_2_0_0_0_{{page}}_0_0", blocks[1].kindUrl)
        assertTrue(blocks[1].books.isEmpty())
    }

    @Test
    fun `分类页抛异常时按空区块计入且不中断整批`() = runTest {
        // 锁的是 catch 分支（异常路径）的消费者可见行为：**失败块 kindUrl 置空**——
        // 传输层对第二分类抛异常时该块仍计入（标题保留）、kindUrl 为空串、无书，
        // 且好分类的结果不受影响。kindUrl 置空让下游能区分「解析成功但没书」与
        // 「本分类抓取失败」（失败块不提供可翻页的 urlRule），与空响应用例（正常解析
        // 出 0 条目、kindUrl 保留）互补，两者不得混同。
        val transport = RecordingTransport(
            "https://www.example.com/shuku/0_1_0_0_0_1_0_0" to searchHtml,             // 第 1 分类正常
            throwAt = "https://www.example.com/shuku/0_2_0_0_0_1_0_0",                  // 第 2 分类传输层抛异常
        )
        val entity = ScriptBookParser(exploreSource, transport).fetchLibraryData()
        val blocks = entity.kindBooks.orEmpty()
        assertEquals(2, blocks.size)                    // 整批不中断：失败块仍计入
        assertEquals(2, blocks[0].books.size)           // 好分类不受影响
        assertEquals("男频连载", blocks[1].kindName)
        assertEquals("", blocks[1].kindUrl)             // 失败块 kindUrl 置空（核心锁定行为）
        assertTrue(blocks[1].books.isEmpty())
    }

    @Test
    fun `getKindBook 按 urlRule 与页码取分类页并支持 ruleExplore 回落 ruleSearch`() = runTest {
        val source = """{"bookSourceUrl":"https://www.example.com",
            "exploreUrl":"书库::/list","ruleSearch":{"bookList":"class.bookbox","name":"tag.h4@text","bookUrl":"tag.a@href"}}"""
        val transport = RecordingTransport("https://www.example.com/list" to searchHtml)
        // ruleExplore 未配：回落 ruleSearch 字段（语料同构，§1.3）
        val books = ScriptBookParser(source, transport).getKindBook("/list", 1)
        assertEquals(2, books.size)
    }

    @Test
    fun `未配 exploreUrl 的源书库为空实体而不是错误`() = runTest {
        val source = """{"bookSourceUrl":"https://www.example.com","bookSourceName":"无发现"}"""
        val entity = ScriptBookParser(source, RecordingTransport()).fetchLibraryData()
        assertNull(entity.kindBooks)
    }

    @Test
    fun `详情字段逐条求值并完成 tocUrl 相对落位`() = runTest {
        val source = """{"bookSourceUrl":"https://www.example.com",
            "ruleBookInfo":{"name":"tag.h1@text","author":"class.info@text",
              "intro":"class.intro@text","coverUrl":"tag.img@src","tocUrl":"class.catalog@href"}}"""
        val detailHtml = """
            <h1>凡人修仙传</h1><i class="info">忘语</i>
            <i class="intro">仙侠巨著</i><img src="/cover/123.jpg"/>
            <a class="catalog" href="/book/123/toc.html">目录</a>
        """.trimIndent()
        val parser = ScriptBookParser(source, RecordingTransport("https://www.example.com/book/123.html" to detailHtml))
        val shelf = BookShelfEntity().apply { noteUrl = "https://www.example.com/book/123.html" }
        val result = parser.getBookInfo(shelf)
        val info = result.bookInfo!!
        assertEquals("凡人修仙传", info.name)
        assertEquals("忘语", info.author)
        assertEquals("仙侠巨著", info.introduce)
        assertEquals("https://www.example.com/cover/123.jpg", info.coverUrl)
        assertEquals("https://www.example.com/book/123/toc.html", info.chapterUrl)
        assertEquals("https://www.example.com", info.tag)
    }

    @Test
    fun `tocUrl 未配置时回落详情页地址`() = runTest {
        val source = """{"bookSourceUrl":"https://www.example.com",
            "ruleBookInfo":{"name":"tag.h1@text"}}"""
        val parser = ScriptBookParser(source, RecordingTransport("https://www.example.com/book/123.html" to "<h1>x</h1>"))
        val result = parser.getBookInfo(BookShelfEntity().apply { noteUrl = "https://www.example.com/book/123.html" })
        assertEquals("https://www.example.com/book/123.html", result.bookInfo!!.chapterUrl)
        // coverUrl 未配置时给空串而不是拼出 baseUrl 假地址（fieldUrl 的判空守卫）：
        // 空 raw 走落位会把「没取到值」伪装成一个看似合法的地址
        assertEquals("", result.bookInfo!!.coverUrl)
    }

    @Test
    fun `intro 为空时与原生同形填暂无简介`() = runTest {
        val source = """{"bookSourceUrl":"https://www.example.com","ruleBookInfo":{"name":"tag.h1@text"}}"""
        val parser = ScriptBookParser(source, RecordingTransport("https://www.example.com/b.html" to "<h1>x</h1>"))
        assertEquals("暂无简介", parser.getBookInfo(BookShelfEntity().apply { noteUrl = "https://www.example.com/b.html" }).bookInfo!!.introduce)
    }

    @Test
    fun `init 配置了但解不出 Matches 时字段回落整页求值`() = runTest {
        // init 配成了链式规则（求值结果是 Nodes/Miss——非 Matches）时视为无预处理（§11-21 口径）：
        // 字段回落整页求值而不是拿链式结果当条目上下文，且不抛。
        // class.nosuch 在页面上无命中 → Miss，锁「非 Matches 一律回落」的最常见形态
        val source = """{"bookSourceUrl":"https://www.example.com",
            "ruleBookInfo":{"init":"class.nosuch@text","name":"tag.h1@text"}}"""
        val parser = ScriptBookParser(source, RecordingTransport("https://www.example.com/b.html" to "<h1>x</h1>"))
        val info = parser.getBookInfo(BookShelfEntity().apply { noteUrl = "https://www.example.com/b.html" }).bookInfo!!
        assertEquals("x", info.name)
    }

    @Test
    fun `init 的 AllInOne 产物作为条目上下文供字段组引用`() = runTest {
        // TXT 流详情页：init 用 AllInOne 正则从整页切出字段，后续字段规则以 $n 组引用（§2.4 组引用）。
        // `[^作]*` 截住第一组防贪婪吞掉第二组——合成语料的取舍，锁的是「组引用走 init 条目上下文」这个机制
        val source = """{"bookSourceUrl":"https://www.example.com",
            "ruleBookInfo":{"init":":(书名[^作]*)(作者.*)","name":"$1","author":"$2"}}"""
        val html = "书名凡人修仙传作者忘语"
        val parser = ScriptBookParser(source, RecordingTransport("https://www.example.com/b.html" to html))
        val info = parser.getBookInfo(BookShelfEntity().apply { noteUrl = "https://www.example.com/b.html" }).bookInfo!!
        assertEquals("书名凡人修仙传", info.name)
        assertEquals("作者忘语", info.author)
    }
}
