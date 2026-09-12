package com.ebook.find.repository

import com.ebook.api.entity.BookSourceRule
import com.ebook.api.entity.SourceFormat
import com.ebook.common.analyze.source.BookSourceManager
import com.ebook.common.analyze.source.LibraryDiskCache
import com.ebook.common.analyze.source.SourceExploreEntry
import com.ebook.db.entity.BookShelfEntity
import com.ebook.db.entity.LibraryEntity
import com.ebook.db.entity.SearchBookEntity
import com.ebook.find.entity.BookType
import com.ebook.source.analyze.BookParser
import com.ebook.source.analyze.BookSourceNotFoundException
import java.nio.file.Files
import kotlinx.coroutines.flow.single
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [BookSourceRepository] 取源口径的测试（ADR-0016 P3-a 的书城一支，P3-c 起源由调用方给）。
 *
 * 锁四条：
 * 1. 本仓库**不再自己猜源**：三个方法的源都来自入参 `sourceUrl`，问的是
 *    `getParserFor(sourceUrl)` / `getExploreEntries(sourceUrl)`。「当前默认源是谁」由书城页经
 *    [BookSourceManager.observeDefaultSource] 订阅后自己递进来——仓库再读一次全局就是第二个事实源
 *    （默认源也不再有任何同步读面，见 [BookSourceManager]）；
 * 2. 「无源」（`sourceUrl` 空白）拿到的是**空数据**而不是异常——用户在书源管理页把源全禁用了，
 *    这里没有值得报错的根因，页面据此走引导态。旧实现按全局默认源取 parser，那里直接抛
 *    IllegalStateException，页面只留下一行看不出根因的日志；
 * 3. 「有源但那个源坏了」（非空白却取不到 parser）走另一条路：抛 [BookSourceNotFoundException]。
 *    它与第 2 条必须由上层分开处置，所以本组用例把这条分界钉住；
 * 4. 无源时分类入口也是空列表（这条本来就在，一并钉住，防止改动把它变成抛异常）。
 *
 * 书库方法已改为 Flow（SWR 双发射），无源分支用 `single()` 收集——顺带锁住「只发一个」，
 * 多发一个空实体同样会让这条变红。缓存策略（TTL/SWR/强刷）的行为由同包的
 * `BookSourceRepositoryLibraryCacheTest` 锁，本组只钉「源有没有被正确解读」。
 *
 * 纯 JVM 跑：仓库构造不再碰 `Context`（书库缓存换成 [LibraryDiskCache] 文件版，DI 层做
 * `Context → File` 转换），每个用例给一个独立临时目录即可。
 */
class BookSourceRepositoryNoSourceTest {

    private companion object {
        const val SOURCE_A = "https://a.example"
        const val SOURCE_B = "https://b.example"
    }

    /**
     * 只关心「按 URL 取源」这一面的最小假件。
     *
     * `getParserFor` 照抄真实现对空白 URL 的短路（不查库直接 null），
     * 否则「无默认源」这条断言测的就是假件而不是被测代码。
     *
     * @param rules 按 URL 可查到的规则（[BookSourceManager.getSourceByUrl] 的返回来源）
     * @param parsers 按 URL 可取到的 parser；缺项即「这一行取不到解析器」
     * @param exploreEntries 按 URL 可查到的分类条目（[BookSourceManager.getExploreEntries]
     *   的返回来源）：条目照真读面原样递出、不在此滤空白标题（过滤归仓库），缺项即该源没有条目
     */
    private class FakeSourceManager(
        private val rules: Map<String, BookSourceRule> = emptyMap(),
        private val parsers: Map<String, BookParser> = emptyMap(),
        private val exploreEntries: Map<String, List<SourceExploreEntry>> = emptyMap(),
    ) : BookSourceManager {
        val parserForCalls = mutableListOf<String>()
        val exploreCalls = mutableListOf<String>()

        override suspend fun getParserFor(sourceUrl: String): BookParser? {
            parserForCalls += sourceUrl
            if (sourceUrl.isBlank()) return null
            return parsers[sourceUrl]
        }

        override suspend fun getSourceByUrl(url: String): BookSourceRule? {
            if (url.isBlank()) return null
            return rules[url]
        }

        /** 分类条目读面照真实现建模：按入参 URL 返回，空白/缺失行给空列表 */
        override suspend fun getExploreEntries(sourceUrl: String): List<SourceExploreEntry> {
            exploreCalls += sourceUrl
            if (sourceUrl.isBlank()) return emptyList()
            return exploreEntries[sourceUrl].orEmpty()
        }

        /** 本假件只服务书城取源；聚合搜索与清单订阅由各自用例分别锁 */
        override fun searchAcross(keyword: String, page: Int, skipSourceUrls: Set<String>) =
            unsupported("searchAcross")

        private fun unsupported(who: String): Nothing =
            throw UnsupportedOperationException("FakeSourceManager 未实现 $who")

        override suspend fun getAllSources(): List<BookSourceRule> = unsupported("getAllSources")
        override suspend fun getEnabledSources(): List<BookSourceRule> = unsupported("getEnabledSources")
        override suspend fun addSource(rule: BookSourceRule): Result<Unit> = unsupported("addSource")
        override suspend fun addScriptSource(rawJson: String): Result<Unit> = unsupported("addScriptSource")
        override suspend fun getFormatByUrl(url: String): SourceFormat? = unsupported("getFormatByUrl")
        override suspend fun removeSource(url: String): Result<Unit> = unsupported("removeSource")
        override suspend fun setEnabled(url: String, enabled: Boolean) = unsupported("setEnabled")
        override suspend fun setDefaultSource(url: String) = unsupported("setDefaultSource")
        override fun observeSources() = unsupported("observeSources")
        override fun observeDefaultSource() = unsupported("observeDefaultSource")
        override fun importFromJson(jsonStr: String): BookSourceRule? = unsupported("importFromJson")
        override fun exportToJson(rule: BookSourceRule): String = unsupported("exportToJson")
    }

    private fun repositoryWith(
        manager: BookSourceManager,
        // 每个用例独立临时目录：本组用例不关心缓存内容，但构造被测对象需要一个真缓存载体
        cache: LibraryDiskCache = LibraryDiskCache(
            Files.createTempDirectory("repo-no-source").toFile().apply { deleteOnExit() }
        ),
    ) = BookSourceRepository(manager, cache)

    @Test
    fun `全禁用时分类页返回空列表而不是抛无源异常`() {
        val manager = FakeSourceManager()

        val books = runBlocking {
            repositoryWith(manager).getKindBook("", "https://site/xuanhuan", 1)
        }

        assertEquals(emptyList<SearchBookEntity>(), books)
        assertEquals(
            "无源时该拿空串去问 getParserFor（它对空白 URL 短路，不查库也不抛）",
            listOf(""),
            manager.parserForCalls,
        )
    }

    @Test
    fun `全禁用时书库流只发一个空实体而不是抛无源异常`() {
        val manager = FakeSourceManager()

        // single() 同时锁「至少一个」与「恰好一个」：无源分支多发一个空实体会让页面多闪一次
        val library = runBlocking {
            repositoryWith(manager).getLibraryData("", LibraryLoadPolicy.StaleWhileRevalidate).single()
        }

        assertTrue("书库该是空的", library.kindBooks.isNullOrEmpty())
        assertTrue(
            "书库路径对空白源连 manager 都不问：无源是入参层面的事实，不必拿空串去查库"
                + "（分类页那条链仍会问一次，由上面的用例分别锁住）",
            manager.parserForCalls.isEmpty(),
        )
    }

    @Test
    fun `入参指定的源有 parser 时由该源解析分类页`() {
        val parser = RecordingKindParser()
        // 只给 SOURCE_A 配 parser：若仓库改回自己猜一个源（而非用入参），这里就拿不到解析器
        val manager = FakeSourceManager(parsers = mapOf(SOURCE_A to parser))

        val books = runBlocking { repositoryWith(manager).getKindBook(SOURCE_A, "/xuanhuan", 2) }

        assertEquals(listOf(SOURCE_A), manager.parserForCalls)
        assertEquals(listOf("/xuanhuan" to 2), parser.kindCalls)
        assertEquals(3, books.size)
        assertEquals("解析出的条目归属来自入参源", SOURCE_A, books.first().tag)
    }

    @Test
    fun `无源时分类入口也是空列表`() {
        val manager = FakeSourceManager()

        assertEquals(
            emptyList<BookType>(),
            runBlocking { repositoryWith(manager).getBookTypeList("") },
        )
    }

    @Test
    fun `分类入口按入参源的规则给出并滤掉空白标题`() {
        val manager = FakeSourceManager(
            exploreEntries = mapOf(
                SOURCE_B to listOf(
                    SourceExploreEntry("科幻", "/kehuan"),
                    // 标题空白 = 书源规则漏写字段的形态（条目字段非空带默认值），
                    // 不过滤就会渲染出空白胶囊、并把空 url 传给分类选书页去请求
                    SourceExploreEntry("", "/ghost"),
                )
            ),
        )

        val types = runBlocking { repositoryWith(manager).getBookTypeList(SOURCE_B) }

        assertEquals(listOf(BookType("科幻", "/kehuan")), types)
        assertEquals("分类入口问的也是入参那个源", listOf(SOURCE_B), manager.exploreCalls)
    }

    @Test
    fun `脚本源的分类条目经同一读面给出 url 承载规则串`() {
        val manager = FakeSourceManager(
            exploreEntries = mapOf(
                SOURCE_B to listOf(
                    SourceExploreEntry("玄幻", "/xuanhuan/{{page}}"),
                    SourceExploreEntry("", "/ghost"),
                )
            ),
        )

        val types = runBlocking { repositoryWith(manager).getBookTypeList(SOURCE_B) }

        assertEquals(
            "url 原样透传规则串：渲染归解析器；空白标题被滤掉",
            listOf(BookType("玄幻", "/xuanhuan/{{page}}")),
            types,
        )
    }

    @Test
    fun `源已被删掉时书库与分类页抛书源失效异常而不是给空数据`() {
        // 假件里没有任何 parser 可取：这模拟「有 URL、但那一行的规则解不出来」
        val manager = FakeSourceManager()

        // 空白与「有 URL 却取不到」在 getParserFor 那里同为 null，仓库必须把两者分开：
        // 前者是「没源可用」（页面进引导态），后者是「这条源坏了」（提示重新导入或换源）。
        // 都返回空数据的话，这两种截然不同的处境会长成同一个空页面。
        assertThrows(BookSourceNotFoundException::class.java) {
            runBlocking { repositoryWith(manager).getKindBook(SOURCE_A, "/xuanhuan", 1) }
        }
        assertThrows(BookSourceNotFoundException::class.java) {
            runBlocking {
                repositoryWith(manager).getLibraryData(SOURCE_A, LibraryLoadPolicy.StaleWhileRevalidate).single()
            }
        }
    }

    /** 只实现 getKindBook 的假 parser：记录入参并回固定条数，用于断言「确实按入参源解析」 */
    private class RecordingKindParser : BookParser {
        val kindCalls = mutableListOf<Pair<String, Int>>()

        override suspend fun getKindBook(url: String, page: Int): List<SearchBookEntity> {
            kindCalls += url to page
            return List(3) { SearchBookEntity(noteUrl = "https://site/book$it", tag = SOURCE_A) }
        }

        private fun unsupported(who: String): Nothing =
            throw UnsupportedOperationException("RecordingKindParser 未实现 $who")

        override suspend fun searchBook(content: String, page: Int): List<SearchBookEntity> =
            unsupported("searchBook")

        override suspend fun getBookInfo(bookShelf: BookShelfEntity): BookShelfEntity =
            unsupported("getBookInfo")

        override suspend fun getChapterList(bookShelf: BookShelfEntity) = unsupported("getChapterList")

        override suspend fun fetchLibraryData(): LibraryEntity = unsupported("fetchLibraryData")
    }
}
