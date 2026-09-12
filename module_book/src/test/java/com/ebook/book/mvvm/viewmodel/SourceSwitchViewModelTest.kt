package com.ebook.book.mvvm.viewmodel

import androidx.test.core.app.ApplicationProvider
import com.ebook.api.entity.BookSourceRule
import com.ebook.api.entity.SourceFormat
import com.ebook.api.entity.SourceDefinition
import com.ebook.common.analyze.source.BookSourceItem
import com.ebook.common.analyze.source.BookSourceManager
import com.ebook.common.analyze.source.SourceExploreEntry
import com.ebook.common.repository.BookRepository
import com.ebook.common.store.BookStore
import com.ebook.common.store.ChapterContentCache
import com.ebook.common.store.WriteTransactionRunner
import com.ebook.db.dao.BookGroupDao
import com.ebook.db.dao.BookInfoDao
import com.ebook.db.dao.BookShelfDao
import com.ebook.db.dao.ChapterListDao
import com.ebook.db.dao.DownloadChapterDao
import com.ebook.db.entity.BookInfoEntity
import com.ebook.db.entity.BookShelfEntity
import com.ebook.db.entity.ChapterListEntity
import com.ebook.db.entity.LibraryEntity
import com.ebook.db.entity.SearchBookEntity
import com.ebook.db.entity.WebChapterEntity
import com.ebook.source.analyze.AggregateSearchEvent
import com.ebook.source.analyze.BookParser
import com.ebook.source.analyze.BookSourceNotFoundException
import com.xrn1997.common.BaseApplication
import java.io.File
import java.lang.reflect.Proxy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [SourceSwitchViewModel] 的单元测试（Robolectric + 测试调度器）。
 *
 * 锁七件事：
 * 1. **匹配度打分五档**（100/80/50/20/0）与各档的边界（空作者不算「作者相同」、带《》仍算同名）；
 * 2. 排除当前书源：`excludeSourceUrl` 真的经 `skipSourceUrls` 交给 `searchAcross`；
 * 3. 候选按分数降序，**同分保持到达序**（排序稳定，UI 行序才确定可复现）；
 * 4. 候选按 `noteUrl` 去重（与聚合搜索同口径，重复 key 会让 Compose 直接抛异常）；
 * 5. 进度只以 [AggregateSearchEvent.AllFinished] 收尾：某一路中途消失（连 Finished 都没发）
 *    也要在 AllFinished 时收满（P3-b 已确立的契约）；
 * 6. `switchSource` 把仓库的 Result 原样交回调用方，并把「旧进度 / 新目录长度」包进 [SourceSwitchOutcome]；
 * 7. **整条聚合流出问题**时加载态照收、进度按已登记的源收满，失败原因留在 `searchFailure` 里
 *    供面板内联渲染（本 VM 的命令通道无人收集，不进状态就等于什么也没说）。
 *
 * Robolectric 只承担一件事：setUp 要把真的 `Application` 注入 `BaseApplication.context`
 * （纯 JVM 下 `android.*` 是抛「Stub!」的桩）。**本 VM 已不取字符串资源**——失败提示的出口在面板，
 * 见 [SourceSwitchViewModel] 的类 KDoc。
 * 仓库用真 [BookRepository] 配白名单 DAO 替身：本组用例锁 VM 的编排与交回，
 * 换源事务本身的落库范围由 `BookRepositorySwitchSourceTest` 锁，不在这里重做。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SourceSwitchViewModelTest {

    private companion object {
        const val SOURCE_A = "https://a.example"
        const val SOURCE_B = "https://b.example"
        const val SOURCE_C = "https://c.example"
        const val NAME = "斗破苍穹"
        const val AUTHOR = "天蚕土豆"
        const val NEW_URL = "https://b.example/book/99.html"
    }

    private val mainDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
        BaseApplication.context = ApplicationProvider.getApplicationContext()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ===== 装配 =====

    private fun viewModelWith(manager: ScriptedManager): SourceSwitchViewModel =
        SourceSwitchViewModel(manager, repository(manager))

    /** 黑四张表的「白名单」仓库：VM 用例只关心 Result 与候选编排，落库范围另有仓库用例 */
    private fun repository(manager: BookSourceManager): BookRepository = BookRepository(
        // getBookByUrl：换源前仓库要问一句「目标那条 noteUrl 是否本就在架上」（一律答 null = 不在）
        bookShelfDao = daoStub(
            BookShelfDao::class.java,
            "insert" to Unit,
            "deleteByUrl" to Unit,
            "getBookByUrl" to null,
        ),
        bookInfoDao = daoStub(BookInfoDao::class.java, "insert" to Unit, "deleteByUrl" to Unit),
        chapterListDao = daoStub(
            ChapterListDao::class.java,
            "insertAll" to Unit,
            "deleteChaptersForBook" to Unit,
        ),
        bookGroupDao = daoStub(
            BookGroupDao::class.java,
            "insert" to Unit,
            "addSecondary" to Unit,
            "deleteFor" to Unit,
            "getAllForNoteUrl" to emptyList<Any>(),
        ),
        // 换源收尾会清掉旧条目名下的在队任务（见 BookRepository.commitSwitch）
        downloadChapterDao = daoStub(DownloadChapterDao::class.java, "deleteByNoteUrl" to Unit),
        chapterReaders = emptyMap(),
        bookSourceManager = manager,
        bookStore = BookStore(File(System.getProperty("java.io.tmpdir"), "source-switch-test-books")),
        contentCache = ChapterContentCache(capacity = 3),
        transactions = DirectRunner,
    )

    /**
     * 白名单 DAO 替身：名单内的方法返回配好的值，名单外一律抛。
     *
     * 静默返回 null 会让「其实走错了路径」的断言假绿（同一写法见 `BookDetailViewModelSourceTest`）。
     */
    @Suppress("UNCHECKED_CAST")
    private fun <T : Any> daoStub(clazz: Class<T>, vararg allowed: Pair<String, Any?>): T {
        val table = allowed.toMap()
        return Proxy.newProxyInstance(clazz.classLoader, arrayOf(clazz)) { _, method, _ ->
            when {
                table.containsKey(method.name) -> table[method.name]
                method.name == "equals" -> false
                method.name == "hashCode" -> System.identityHashCode(clazz)
                method.name == "toString" -> "DaoStub<${clazz.simpleName}>"
                else -> throw UnsupportedOperationException(
                    "${clazz.simpleName}.${method.name} 不该被本用例调用（确需调用请加进白名单）"
                )
            }
        } as T
    }

    private object DirectRunner : WriteTransactionRunner {
        override suspend fun <R> run(block: suspend () -> R): R = block()
    }

    /**
     * 轮询等到 [cond] 成立：交替推进测试调度器与真实时间。
     *
     * 换源那条路径里的库内写走 `BookRepository` 的 `withContext(Dispatchers.IO)`，跑在**真实 IO
     * 线程**上、不受虚拟时钟控制，只推进调度器就会与之竞态（同一写法见 `BookDetailViewModelSourceTest`）。
     */
    private suspend fun TestScope.awaitUntil(message: String, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!cond()) {
            advanceUntilIdle()
            if (cond()) break
            assertTrue("$message（5s 内未达成）", System.currentTimeMillis() < deadline)
            withContext(Dispatchers.IO) { Thread.sleep(10) }
        }
        advanceUntilIdle()
    }

    // ===== 1. 打分 =====

    @Test
    fun `书名与作者都相同记满分，其次同名、再次包含`() {
        val viewModel = viewModelWith(ScriptedManager(emptyMap()))

        assertEquals(100, viewModel.matchScore(book(NAME, AUTHOR), NAME, AUTHOR))
        assertEquals(80, viewModel.matchScore(book(NAME, "另一位"), NAME, AUTHOR))
        assertEquals(50, viewModel.matchScore(book("${NAME}·番外", "另一位"), NAME, AUTHOR))
        assertEquals(20, viewModel.matchScore(book("别的书", AUTHOR), NAME, AUTHOR))
        assertEquals(0, viewModel.matchScore(book("别的书", "另一位"), NAME, AUTHOR))
    }

    @Test
    fun `打分比的是归一化形态，且空作者不算作者相同`() {
        val viewModel = viewModelWith(ScriptedManager(emptyMap()))

        assertEquals(
            "站点常在书名两侧加书名号与空格，逐字比会让最像的那本掉分",
            100,
            viewModel.matchScore(book("《 $NAME 　》", AUTHOR), " $NAME ", AUTHOR),
        )
        assertEquals(
            "两边都不知道作者时，「作者相同」只是「两边都是空」，不该给分",
            80,
            viewModel.matchScore(book(NAME, ""), NAME, ""),
        )
        assertEquals(
            "作者占位词按归一化原文比：佚名 ≠ 天蚕土豆，所以只剩书名一档",
            80,
            viewModel.matchScore(book(NAME, "佚名"), NAME, AUTHOR),
        )
    }

    // ===== 2. 排除当前源 =====

    @Test
    fun `候选搜索把当前书源经 skipSourceUrls 交给聚合搜索`(): Unit = runTest(mainDispatcher) {
        val manager = ScriptedManager(
            scriptBySource = mapOf(
                SOURCE_A to listOf(started(SOURCE_A, "A 站")),
                SOURCE_B to listOf(started(SOURCE_B, "B 站"), result(SOURCE_B, book(NAME, AUTHOR, "$NEW_URL")), finished(SOURCE_B)),
            ),
        )
        val viewModel = viewModelWith(manager)

        viewModel.searchCandidates(NAME, AUTHOR, excludeSourceUrl = SOURCE_A)
        advanceUntilIdle()

        val request = manager.requests.single()
        assertEquals("当前源不该被再请求一次", setOf(SOURCE_A), request.skip)
        assertEquals(NAME, request.keyword)
        assertEquals("换源面板只要最像的几条，固定第 1 页", 1, request.page)
        assertEquals("当前源的一条候选都不该出现在结果里", listOf("$NEW_URL"), viewModel.candidates.value.map { it.noteUrl })
    }

    // ===== 3. 排序 =====

    @Test
    fun `候选按匹配度降序，同分保持到达序`(): Unit = runTest(mainDispatcher) {
        val manager = ScriptedManager(
            scriptBySource = mapOf(
                // 先到的一批是 50 分（书名包含）与 80 分（同名不同作者）
                SOURCE_A to listOf(
                    started(SOURCE_A, "A 站"),
                    result(
                        SOURCE_A,
                        book("${NAME}全集", "甲", "https://a.example/1"),
                        book(NAME, "乙", "https://a.example/2"),
                    ),
                    finished(SOURCE_A),
                ),
                // 后到的一条是 100 分：必须插到队首，而不是排在已到的 80 分之后
                SOURCE_B to listOf(
                    started(SOURCE_B, "B 站"),
                    result(SOURCE_B, book(NAME, AUTHOR, "https://b.example/3")),
                    finished(SOURCE_B),
                ),
                // 同为 80 分的第二条：与 A 站那条同分，次序按到达先后（稳定排序）
                SOURCE_C to listOf(
                    started(SOURCE_C, "C 站"),
                    result(SOURCE_C, book(NAME, "丙", "https://c.example/4")),
                    finished(SOURCE_C),
                ),
            ),
        )
        val viewModel = viewModelWith(manager)

        viewModel.searchCandidates(NAME, AUTHOR, excludeSourceUrl = "")
        advanceUntilIdle()

        assertEquals(
            listOf("https://b.example/3", "https://a.example/2", "https://c.example/4", "https://a.example/1"),
            viewModel.candidates.value.map { it.noteUrl },
        )
    }

    // ===== 4. 去重 =====

    @Test
    fun `两个源解出同一条 noteUrl 时候选只留一条`(): Unit = runTest(mainDispatcher) {
        val shared = "https://a.example/1"
        val manager = ScriptedManager(
            scriptBySource = mapOf(
                SOURCE_A to listOf(started(SOURCE_A, "A 站"), result(SOURCE_A, book(NAME, AUTHOR, shared)), finished(SOURCE_A)),
                // B 站抄了 A 站的书页（noteUrl 相同），不该再占一行
                SOURCE_B to listOf(started(SOURCE_B, "B 站"), result(SOURCE_B, book(NAME, AUTHOR, shared)), finished(SOURCE_B)),
            ),
        )
        val viewModel = viewModelWith(manager)

        viewModel.searchCandidates(NAME, AUTHOR, excludeSourceUrl = "")
        advanceUntilIdle()

        assertEquals(1, viewModel.candidates.value.size)
        assertEquals(shared, viewModel.candidates.value.single().noteUrl)
    }

    // ===== 5. 进度收尾 =====

    @Test
    fun `某一路中途消失时进度仍由 AllFinished 一次收满`(): Unit = runTest(mainDispatcher) {
        val manager = ScriptedManager(
            scriptBySource = mapOf(
                SOURCE_A to listOf(started(SOURCE_A, "A 站"), result(SOURCE_A, book(NAME, AUTHOR, "https://a.example/1")), finished(SOURCE_A)),
                // 真实现里这一路协程被取消时（flatMapMerge 丢弃整路）连 SourceFinished 都不发
                SOURCE_B to listOf(started(SOURCE_B, "B 站")),
            ),
        )
        val viewModel = viewModelWith(manager)

        viewModel.searchCandidates(NAME, AUTHOR, excludeSourceUrl = "")
        advanceUntilIdle()

        val progress = viewModel.progress.value
        assertEquals("本轮参与的两条源都要登记进分母", 2, progress.total)
        assertEquals("收尾以 AllFinished 为准，不能数 Finished", 2, progress.finished)
        assertFalse("本轮已结束，进度不该还在跑", progress.isRunning)
        assertFalse("发起的搜索已结束，加载态必须收掉", viewModel.isSearching.value)
    }

    @Test
    fun `没有候选时也是正常收尾而不是错误态`(): Unit = runTest(mainDispatcher) {
        val manager = ScriptedManager(
            scriptBySource = mapOf(
                SOURCE_A to listOf(started(SOURCE_A, "A 站"), result(SOURCE_A), finished(SOURCE_A)),
            ),
        )
        val viewModel = viewModelWith(manager)

        viewModel.searchCandidates(NAME, AUTHOR, excludeSourceUrl = "")
        advanceUntilIdle()

        assertEquals(emptyList<SearchBookEntity>(), viewModel.candidates.value)
        assertEquals(1, viewModel.progress.value.finished)
        assertFalse(viewModel.isSearching.value)
    }

    @Test
    fun `整条聚合流异常终止时收掉加载态并把失败留在状态里`(): Unit = runTest(mainDispatcher) {
        val failure = IllegalStateException("书源清单查崩了")
        val manager = ScriptedManager(
            scriptBySource = mapOf(SOURCE_A to listOf(started(SOURCE_A, "A 站"))),
            failFlowWith = failure,
        )
        val viewModel = viewModelWith(manager)

        viewModel.searchCandidates(NAME, AUTHOR, excludeSourceUrl = "")
        advanceUntilIdle()

        val progress = viewModel.progress.value
        assertFalse("流出问题也要收掉加载态，否则面板永远转圈", viewModel.isSearching.value)
        assertEquals("AllFinished 根本不会发出，进度仍要按已登记的源收满", progress.total, progress.finished)
        assertSame(
            "失败原因留在状态里供面板内联渲染——本 VM 的命令通道无人收集，不写进状态就等于什么也没说",
            failure,
            viewModel.searchFailure.value,
        )
    }

    // ===== 6. 换源结果原样交回 =====

    @Test
    fun `换源成功后把映射结果与旧进度一起交回`(): Unit = runTest(mainDispatcher) {
        val manager = ScriptedManager(
            scriptBySource = emptyMap(),
            parsers = mapOf(SOURCE_B to FakeNewSourceParser(SOURCE_B, chapterCount = 3)),
        )
        val viewModel = viewModelWith(manager)
        val old = BookShelfEntity(noteUrl = "https://a.example/book/1.html", tag = SOURCE_A, durChapter = 10)
        val results = mutableListOf<Result<SourceSwitchOutcome>>()

        viewModel.switchSource(old, book(NAME, AUTHOR, NEW_URL, tag = SOURCE_B)) { results += it }
        awaitUntil("换源已回到调用方") { results.isNotEmpty() }

        val outcome = results.single().getOrThrow()
        assertEquals("新源只有 3 章，旧进度 10 被截到末章", 2, outcome.targetChapter)
        assertEquals(3, outcome.chapterCount)
        assertEquals(10, outcome.previousChapter)
        assertTrue("被截断这一事实要能被 UI 说出来", outcome.clamped)
        assertEquals(NEW_URL, outcome.newShelf.noteUrl)
    }

    @Test
    fun `换源失败时仓库的异常原样交回而不被吞掉`(): Unit = runTest(mainDispatcher) {
        // 目标源在清单里没有行 → getParserFor 返回 null → 仓库抛 BookSourceNotFoundException
        val manager = ScriptedManager(scriptBySource = emptyMap(), parsers = emptyMap())
        val viewModel = viewModelWith(manager)
        val old = BookShelfEntity(noteUrl = "https://a.example/book/1.html", tag = SOURCE_A, durChapter = 4)
        val results = mutableListOf<Result<SourceSwitchOutcome>>()

        viewModel.switchSource(old, book(NAME, AUTHOR, NEW_URL, tag = SOURCE_B)) { results += it }
        awaitUntil("失败已回到调用方") { results.isNotEmpty() }

        val error = results.single().exceptionOrNull()
        assertTrue("VM 不该改写仓库的失败类型", error is BookSourceNotFoundException)
        assertEquals(SOURCE_B, (error as BookSourceNotFoundException).sourceUrl)
        assertEquals("只问过目标源，没有回落到别的源", listOf(SOURCE_B), manager.parserForCalls)
    }

    // ===== 替身 =====

    private fun book(name: String, author: String, noteUrl: String = "https://x/1", tag: String = SOURCE_B) =
        SearchBookEntity(noteUrl = noteUrl, name = name, author = author, tag = tag, origin = tag.substringAfter("//").substringBefore("/"))

    private fun started(url: String, name: String) = AggregateSearchEvent.SourceStarted(url, name)

    private fun result(url: String, vararg books: SearchBookEntity) =
        AggregateSearchEvent.SourceResult(url, books.toList())

    private fun finished(url: String) = AggregateSearchEvent.SourceFinished(url, hasMore = false)

    private data class Request(val keyword: String, val page: Int, val skip: Set<String>)

    /**
     * 按脚本发事件的 Manager 假件：只实现换源面板用得到的两面（聚合搜索 + 按 tag 取源）。
     *
     * 事件按源**顺序**发出（真实现会交错，见 [AggregateSearchEvent] 类注释）：本组用例锁的是
     * VM 对事件的处置，顺序化只为让断言写得出来；交错与并发由 `BookSourceManagerImplTest` 锁真实现。
     * 某一条源的事件表里没有 Finished，就是在模拟「整路被丢弃」。
     */
    private class ScriptedManager(
        private val scriptBySource: Map<String, List<AggregateSearchEvent>>,
        private val parsers: Map<String, BookParser> = emptyMap(),
        /**
         * 非空则整条流在发完脚本后异常终止（**不发** [AggregateSearchEvent.AllFinished]）：
         * 模拟「查书源清单失败」这类脚本之外的流出问题——真实现里此刻 AllFinished 根本不会发出。
         */
        private val failFlowWith: Throwable? = null,
    ) : BookSourceManager {
        val requests = mutableListOf<Request>()
        val parserForCalls = mutableListOf<String>()

        override fun searchAcross(
            keyword: String,
            page: Int,
            skipSourceUrls: Set<String>,
        ): Flow<AggregateSearchEvent> = flow {
            requests += Request(keyword, page, skipSourceUrls)
            scriptBySource.filterNot { it.key in skipSourceUrls }.forEach { (_, events) ->
                events.forEach { emit(it) }
            }
            failFlowWith?.let { throw it }
            emit(AggregateSearchEvent.AllFinished)
        }

        override suspend fun getParserFor(sourceUrl: String): BookParser? {
            parserForCalls += sourceUrl
            if (sourceUrl.isBlank() || sourceUrl == BookShelfEntity.LOCAL_TAG) return null
            return parsers[sourceUrl]
        }

        /** 书源清单与默认源流都不属于换源面板的路径，取到即抛：静默返回空值会让断言假绿 */
        private fun unsupported(who: String): Nothing =
            throw UnsupportedOperationException("ScriptedManager 未实现 $who")

        override suspend fun getAllSources(): List<BookSourceRule> = unsupported("getAllSources")
        override suspend fun getEnabledSources(): List<BookSourceRule> = unsupported("getEnabledSources")
        override suspend fun getSourceByUrl(url: String): BookSourceRule? = unsupported("getSourceByUrl")
        override suspend fun addSource(rule: BookSourceRule): Result<Unit> = unsupported("addSource")
        override suspend fun addScriptSource(rawJson: String): Result<Unit> = unsupported("addScriptSource")
        override suspend fun getFormatByUrl(url: String): SourceFormat? = unsupported("getFormatByUrl")
        override suspend fun getExploreEntries(sourceUrl: String): List<SourceExploreEntry> =
            unsupported("getExploreEntries")
        override suspend fun removeSource(url: String): Result<Unit> = unsupported("removeSource")
        override suspend fun setEnabled(url: String, enabled: Boolean) = unsupported("setEnabled")
        override suspend fun setDefaultSource(url: String) = unsupported("setDefaultSource")
        override fun observeSources(): Flow<List<BookSourceItem>> = unsupported("observeSources")
        override fun observeDefaultSource(): Flow<SourceDefinition?> = unsupported("observeDefaultSource")
        override fun importFromJson(jsonStr: String): BookSourceRule? = unsupported("importFromJson")
        override fun exportToJson(rule: BookSourceRule): String = unsupported("exportToJson")
    }

    /** 只回答「新源能解出这本书」的 parser：详情与目录都是固定内容，[chapterCount] 决定目录长度 */
    private class FakeNewSourceParser(
        val ownedSourceUrl: String,
        private val chapterCount: Int,
    ) : BookParser {
        override suspend fun getBookInfo(bookShelf: BookShelfEntity): BookShelfEntity =
            bookShelf.apply {
                bookInfo = BookInfoEntity(
                    name = NAME,
                    author = AUTHOR,
                    noteUrl = bookShelf.noteUrl,
                    tag = ownedSourceUrl,
                )
            }

        override suspend fun getChapterList(bookShelf: BookShelfEntity): WebChapterEntity<BookShelfEntity> {
            val chapters = (0 until chapterCount).map { i ->
                ChapterListEntity(
                    noteUrl = bookShelf.noteUrl,
                    durChapterIndex = i,
                    contentRef = "$ownedSourceUrl/chapter/${i + 1}.html",
                    durChapterName = "第${i + 1}章",
                    tag = ownedSourceUrl,
                )
            }
            return WebChapterEntity(data = bookShelf.copy(chapterList = chapters), next = false)
        }

        private fun unsupported(who: String): Nothing =
            throw UnsupportedOperationException("FakeNewSourceParser 未实现 $who")

        override suspend fun searchBook(content: String, page: Int): List<SearchBookEntity> =
            unsupported("searchBook")

        override suspend fun getKindBook(url: String, page: Int): List<SearchBookEntity> =
            unsupported("getKindBook")

        override suspend fun fetchLibraryData(): LibraryEntity = unsupported("fetchLibraryData")
    }
}
