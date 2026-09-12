package com.ebook.find.mvvm.viewmodel

import com.ebook.api.entity.BookSourceRule
import com.ebook.api.entity.SourceFormat
import com.ebook.common.analyze.source.BookSourceManager
import com.ebook.common.analyze.source.SourceExploreEntry
import com.ebook.common.manager.BookShelfManager
import com.ebook.common.repository.BookRepository
import com.ebook.common.store.BookStore
import com.ebook.common.store.ChapterContentCache
import com.ebook.common.store.WriteTransactionRunner
import com.ebook.db.dao.BookGroupDao
import com.ebook.db.dao.BookInfoDao
import com.ebook.db.dao.BookShelfDao
import com.ebook.db.dao.ChapterListDao
import com.ebook.db.dao.DownloadChapterDao
import com.ebook.db.dao.SearchHistoryDao
import com.ebook.db.entity.BookShelfEntity
import com.ebook.db.entity.LibraryEntity
import com.ebook.db.entity.SearchBookEntity
import com.ebook.db.entity.SearchHistoryEntity
import com.ebook.db.entity.WebChapterEntity
import com.ebook.find.repository.SearchHistoryRepository
import com.ebook.source.analyze.AggregateSearchEvent
import com.ebook.source.analyze.BookParser
import com.xrn1997.common.mvvm.viewmodel.Overlay
import java.io.File
import java.lang.reflect.Proxy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [SearchViewModel] 的聚合搜索测试（ADR-0016 P3-b）。
 *
 * 锁的是「一次搜全站」在 VM 侧的编排，而不是解析：
 * - 结果**全局按 `noteUrl` 去重**，但同名不同源的两条书都留着（换源备选）；
 * - 翻页按源推进游标，已结束的源下一轮不再被请求（含软 404 那种「整页都是重复」的形态）；
 * - 换关键词重搜会取消上一轮：旧流不得再往新结果里灌；
 * - 进度按「本轮已结束 / 本轮参与」递增，新一轮开始时清零；
 * - 全部源失败才进错误态，部分失败照常展示已有结果；
 * - 加书架传出去的是**该书自己的归属源**（不是当前默认源）；
 * - 既有能力没回归：`markShelfStatus` 与搜索历史。
 *
 * 纯 JVM 跑（本模块不引 Robolectric，与 `BookSourceRepositoryNoSourceTest` 同一口径）：
 * 用到的分支不碰 Context——`reportFailure` 的文案取异常自身消息，`addBookToShelf` 的
 * 成功路径也不需要 string 资源。
 *
 * `BookSourceManager` 用只实现「聚合 + 按 tag 取源」两面的假件（其余成员一调就抛，
 * 免得「其实没走到那条路径」的断言假绿）；`BookShelfManager` / `BookRepository` 用真件配
 * 白名单 DAO 替身（同一写法见 module_book 的 `BookDetailViewModelSourceTest`），
 * 于是「加书架用的是哪个 parser」测的是真链路，而不是两个替身之间的私下约定。
 */
class SearchViewModelTest {

    private val mainDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private companion object {
        const val SOURCE_A = "https://a.example"
        const val SOURCE_B = "https://b.example"

        /** 只由「旧的那一轮」在卡点之后给出的书：它出现在结果里就说明旧流没被取消 */
        const val STALE_URL = "https://stale.example/book/9.html"

        const val KEYWORD_OLD = "旧词"
        const val KEYWORD_NEW = "都市"

        /** 假 Manager 的启用源清单（URL 字典序即断言里的排序序） */
        val SOURCES = listOf(
            BookSourceRule(name = "A 站", url = SOURCE_A),
            BookSourceRule(name = "B 站", url = SOURCE_B),
        )

        fun urlOf(source: String, index: Int) = "$source/book/$index.html"

        fun bookAt(source: String, index: Int, name: String = "书$index") = SearchBookEntity(
            noteUrl = urlOf(source, index),
            name = name,
            author = "作者甲",
        )
    }

    // region 装配

    /** 一次聚合请求：VM 侧的每源游标与「不再请求已结束源」全靠它观测 */
    private data class Request(val keyword: String, val page: Int, val skip: Set<String>)

    /**
     * 只实现聚合搜索与按 tag 取源两面的 Manager 假件。
     *
     * 事件按源**顺序**发出（真实现会交错，见 [AggregateSearchEvent] 的类注释）：本组用例锁的是
     * VM 对事件的处置，顺序化只为让断言写得出来；交错路径由 `BookSourceManagerImplTest` 锁真实现。
     *
     * @param gateFor 「哪个关键词的那一轮」要在发完所有源之后先等这个门——用来把一轮卡在半途，
     *   好让下一轮去取消它；[tailAfterGate] 是卡点之后才会追加发出的结果，
     *   它（或 [tailEmitted]）出现在测试视野里就等于「旧流没被取消」
     */
    private class FakeAggregateManager(
        private val results: Map<String, Map<Int, List<SearchBookEntity>>>,
        private val failures: Map<String, Exception> = emptyMap(),
        private val parsers: Map<String, BookParser> = emptyMap(),
        /**
         * 只发 [AggregateSearchEvent.SourceStarted] 就消失的源：模拟真实现里某一路协程被取消后
         * `flatMapMerge` 丢弃整路的形态（连 SourceFinished 都不发，故进度只能靠 AllFinished 收口）。
         */
        private val vanishUrls: Set<String> = emptySet(),
    ) : BookSourceManager {
        val requests = mutableListOf<Request>()
        val parserForCalls = mutableListOf<String>()
        var gateFor: Pair<String, CompletableDeferred<Unit>>? = null
        var tailAfterGate: List<Pair<String, SearchBookEntity>> = emptyList()
        var tailEmitted = false
            private set

        /** 每发完一条源的全部事件后回调（用于观测进度递增） */
        var onSourceEmitted: (String) -> Unit = {}

        /**
         * 只在第 N 次 `searchAcross`（0 基）上设 [gateFor] 那道门，null = 不看第几次（按关键词命中）。
         *
         * 有了它才能测「同一关键词的**旧轮**卡在半途、期间又起了一轮」这种交错：只按关键词匹配时
         * 两轮都会被卡住，就观测不到「两轮同时在跑」这件事本身（见
         * `首轮搜索在途时加载更多不并起第二轮`）。
         */
        var gateRequestIndex: Int? = null

        override fun searchAcross(
            keyword: String,
            page: Int,
            skipSourceUrls: Set<String>,
        ): Flow<AggregateSearchEvent> = flow {
            requests += Request(keyword, page, skipSourceUrls)
            SOURCES.filterNot { it.url in skipSourceUrls }.forEach { rule ->
                emit(AggregateSearchEvent.SourceStarted(rule.url, rule.name))
                if (rule.url in vanishUrls) return@forEach // 整路被丢弃：后续一个事件都不发
                val failure = failures[rule.url]
                if (failure != null) {
                    emit(AggregateSearchEvent.SourceFailed(rule.url, failure))
                    emit(AggregateSearchEvent.SourceFinished(rule.url, hasMore = false))
                } else {
                    val books = results[rule.url]?.get(page).orEmpty()
                        .map { it.copy(tag = rule.url, origin = rule.name) }
                    emit(AggregateSearchEvent.SourceResult(rule.url, books))
                    emit(AggregateSearchEvent.SourceFinished(rule.url, books.isNotEmpty()))
                }
                onSourceEmitted(rule.url)
            }
            val gated = gateFor?.takeIf { gateRequestIndex == null || requests.lastIndex == gateRequestIndex }
            if (gated != null && gated.first == keyword) {
                gated.second.await()
                tailAfterGate.forEach { (url, book) ->
                    emit(AggregateSearchEvent.SourceResult(url, listOf(book)))
                    emit(AggregateSearchEvent.SourceFinished(url, hasMore = false))
                    tailEmitted = true
                }
            }
            emit(AggregateSearchEvent.AllFinished)
        }

        override suspend fun getParserFor(sourceUrl: String): BookParser? {
            parserForCalls += sourceUrl
            if (sourceUrl.isBlank() || sourceUrl == BookShelfEntity.LOCAL_TAG) return null
            return parsers[sourceUrl]
        }

        private fun unsupported(who: String): Nothing =
            throw UnsupportedOperationException("FakeAggregateManager 未实现 $who")

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
        override fun observeSources() = unsupported("observeSources")
        override fun observeDefaultSource() = unsupported("observeDefaultSource")
        override fun importFromJson(jsonStr: String): BookSourceRule? = unsupported("importFromJson")
        override fun exportToJson(rule: BookSourceRule): String = unsupported("exportToJson")
    }

    /**
     * DAO 替身：白名单（[results] 的键）内的方法返回配好的值，名单外一律抛。
     *
     * 用 [Proxy] 而不是手写 Fake：Room DAO 接口方法多，本组用例只用到三四条，
     * 手写会把「哪些方法真的被测到」这件事糊掉。suspend 方法在字节码里返回 Object，
     * 故按方法名而非返回类型分派。
     */
    @Suppress("UNCHECKED_CAST")
    private fun <T : Any> daoStub(clazz: Class<T>, results: Map<String, Any?>): T =
        Proxy.newProxyInstance(clazz.classLoader, arrayOf(clazz)) { _, method, _ ->
            when {
                results.containsKey(method.name) -> results[method.name]
                method.name == "equals" -> false
                method.name == "hashCode" -> System.identityHashCode(clazz)
                method.name == "toString" -> "DaoStub<${clazz.simpleName}>"
                else -> throw UnsupportedOperationException(
                    "${clazz.simpleName}.${method.name} 不该被本用例调用（确需调用请加进白名单）"
                )
            }
        } as T

    private object DirectRunner : WriteTransactionRunner {
        override suspend fun <R> run(block: suspend () -> R): R = block()
    }

    /** 记录「被谁解析过」的 parser：`ownedSourceUrl` 就是「我是哪个源的 parser」 */
    private class RecordingParser(val ownedSourceUrl: String) : BookParser {
        val bookInfoCalls = mutableListOf<BookShelfEntity>()

        override suspend fun getBookInfo(bookShelf: BookShelfEntity): BookShelfEntity {
            bookInfoCalls += bookShelf
            return bookShelf
        }

        override suspend fun getChapterList(bookShelf: BookShelfEntity) =
            WebChapterEntity(data = bookShelf, next = false)

        private fun unsupported(who: String): Nothing =
            throw UnsupportedOperationException("RecordingParser 未实现 $who")

        override suspend fun searchBook(content: String, page: Int): List<SearchBookEntity> =
            unsupported("searchBook")

        override suspend fun getKindBook(url: String, page: Int): List<SearchBookEntity> =
            unsupported("getKindBook")

        override suspend fun fetchLibraryData(): LibraryEntity = unsupported("fetchLibraryData")
    }

    /**
     * 起一个 VM。
     *
     * @param shelf 书架快照（VM 的 init 会经 `Dispatchers.IO` 真实线程去取，见 [awaitUntil]）
     * @param histories 搜索历史表内容（`querySearchHistory` 的返回值）
     */
    private fun viewModelWith(
        manager: FakeAggregateManager,
        shelf: List<BookShelfEntity> = emptyList(),
        histories: List<SearchHistoryEntity> = emptyList(),
    ): SearchViewModel {
        val bookRepository = BookRepository(
            bookShelfDao = daoStub(
                BookShelfDao::class.java,
                mapOf("getAllBooks" to shelf, "insert" to Unit),
            ),
            bookInfoDao = daoStub(BookInfoDao::class.java, mapOf("insert" to Unit)),
            chapterListDao = daoStub(ChapterListDao::class.java, emptyMap()),
            bookGroupDao = daoStub(BookGroupDao::class.java, mapOf("insert" to Unit)),
            // 搜索页不换源：空白名单即「这张表一次都不该被碰」的断言
            downloadChapterDao = daoStub(DownloadChapterDao::class.java, emptyMap()),
            chapterReaders = emptyMap(),
            // BookRepository 新增的构造参数只服务换源事务（本用例不走）；与 VM 共用同一份假清单
            bookSourceManager = manager,
            bookStore = BookStore(File(System.getProperty("java.io.tmpdir"), "search-vm-test-books")),
            contentCache = ChapterContentCache(capacity = 3),
            transactions = DirectRunner,
        )
        val historyDao = daoStub(
            SearchHistoryDao::class.java,
            mapOf(
                "getByType" to histories,
                "findByTypeAndContent" to null,
                "insert" to Unit,
                "clearByType" to 1,
            ),
        )
        return SearchViewModel(
            searchHistoryRepository = SearchHistoryRepository(historyDao),
            bookSourceManager = manager,
            bookShelfManager = BookShelfManager(manager, bookRepository),
            bookRepository = bookRepository,
        )
    }

    /**
     * 轮询等到 [cond] 成立：交替推进测试调度器与真实时间。
     *
     * **为什么需要它**：VM 的 init 里那趟 `loadBookShelves()` 走 `withContext(Dispatchers.IO)`，
     * 跑在**真实 IO 线程**上、不受虚拟时钟控制，只推进调度器就会与之竞态
     * （同一写法见 module_book 的 `BookDetailViewModelSourceTest`）。
     */
    private suspend fun TestScope.awaitUntil(message: String, cond: suspend () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!cond()) {
            advanceUntilIdle()
            if (cond()) break
            assertTrue("$message（5s 内未达成）", System.currentTimeMillis() < deadline)
            withContext(Dispatchers.IO) { Thread.sleep(10) }
        }
        advanceUntilIdle()
    }

    // endregion

    @Test
    fun `两源返回同一 noteUrl 时结果只留一条`(): Unit = runTest(mainDispatcher) {
        val shared = urlOf(SOURCE_A, 1)
        val manager = FakeAggregateManager(
            results = mapOf(
                SOURCE_A to mapOf(1 to listOf(bookAt(SOURCE_A, 1))),
                // B 站解出了同一个 noteUrl（站间互相抄书），去重该把它吃掉
                SOURCE_B to mapOf(1 to listOf(SearchBookEntity(noteUrl = shared, name = "同书"))),
            ),
        )
        val viewModel = viewModelWith(manager)

        viewModel.toSearchBooks(KEYWORD_NEW)
        advanceUntilIdle()

        val books = viewModel.list.value
        assertEquals("同一 noteUrl 只留一条（列表以它作 item key，重复即崩）", 1, books.size)
        assertEquals(shared, books.single().noteUrl)
    }

    @Test
    fun `同名不同源的两条书都保留作为换源备选`(): Unit = runTest(mainDispatcher) {
        val manager = FakeAggregateManager(
            results = mapOf(
                SOURCE_A to mapOf(1 to listOf(bookAt(SOURCE_A, 1, name = "都市之巅"))),
                SOURCE_B to mapOf(1 to listOf(bookAt(SOURCE_B, 1, name = "都市之巅"))),
            ),
        )
        val viewModel = viewModelWith(manager)

        viewModel.toSearchBooks(KEYWORD_NEW)
        advanceUntilIdle()

        val books = viewModel.list.value
        assertEquals("书名作者相同但归属不同，故意不并成一条", 2, books.size)
        assertEquals(
            "两条各带自己的归属（tag=源 URL、origin=源名）",
            listOf(SOURCE_A to "A 站", SOURCE_B to "B 站"),
            books.map { it.tag to it.origin },
        )
    }

    @Test
    fun `加载更多只对未结束的源翻页且各源游标独立递增`(): Unit = runTest(mainDispatcher) {
        val manager = FakeAggregateManager(
            results = mapOf(
                // A 站到第 2 页就没货了；B 站一路有
                SOURCE_A to mapOf(1 to listOf(bookAt(SOURCE_A, 1)), 2 to emptyList()),
                SOURCE_B to mapOf(
                    1 to listOf(bookAt(SOURCE_B, 1)),
                    2 to listOf(bookAt(SOURCE_B, 2)),
                    3 to listOf(bookAt(SOURCE_B, 3)),
                ),
            ),
        )
        val viewModel = viewModelWith(manager)

        viewModel.toSearchBooks(KEYWORD_NEW)
        advanceUntilIdle()
        viewModel.loadMore()
        advanceUntilIdle()
        viewModel.loadMore()
        advanceUntilIdle()

        assertEquals(
            "第 3 轮起 A 站已被判到底，不再被请求",
            listOf(
                Request(KEYWORD_NEW, 1, emptySet()),
                Request(KEYWORD_NEW, 2, emptySet()),
                Request(KEYWORD_NEW, 3, setOf(SOURCE_A)),
            ),
            manager.requests,
        )
        // 第 1 轮 2 条 + 第 2 轮 B 的第 2 页 1 条 + 第 3 轮 B 的第 3 页 1 条
        assertEquals("结果按源各自翻页累计", 4, viewModel.list.value.size)
    }

    /**
     * 首轮搜索**还在途中**时触底加载更多，不许并起第二轮聚合。
     *
     * 可达路径（不是「用户连滑两下」——那种被基类挡住了）：首轮由搜索按钮的 `toSearchBooks` 发起，
     * 它不经刷新状态机，所以此刻 lib_common 的 `RefreshStateMachine` 仍在 Idle，
     * `beginLoadMore` 的「已在 Loading 就 return」那道闸门形同不存在；而 `RefreshableList` 的
     * 触底判据只看 `!isLoadingMore`，于是第一轮结果正一条条往外蹦、列表刚长过一屏时，
     * 用户停在底部的这一次滑动就会真的走进 [SearchViewModel.loadMore]。
     *
     * 并起的后果是**共享集合被两轮流交错改写**：本轮的源集合、结束集合与新增计数表
     * （VM 里的 `AggregationBookkeeping.round`）是本轮共享状态，新一轮 `startRound` 一上来就把它整块换新，
     * 旧轮随后的 Finished 于是记在新一轮的账上（「已收到 X/Y」算重），游标也被两轮的 `putIfAbsent` 互相覆写。
     *
     * 处方取「在途即忽略」而不是「取消旧轮再开新一轮」：旧轮那些**已经到手的结果**不该被丢掉，
     * 且旧轮的收尾本身就会发 `updateStopLoadMore` 把状态机从 Loading 放出来，
     * 界面仍停在底部时会再触发一次触底 → 下一轮带正确游标继续翻。
     * 反过来取消旧轮会让 `pageBySource` 停在旧值、新轮重问一遍已问过的页，
     * 那一页去重后「零新条目」→ 每条源都被判「到底」→ footer 直接亮出假的「没有更多」。
     */
    @Test
    fun `首轮搜索在途时加载更多不并起第二轮聚合`(): Unit = runTest(mainDispatcher) {
        val gate = CompletableDeferred<Unit>()
        val manager = FakeAggregateManager(
            results = mapOf(
                SOURCE_A to mapOf(1 to listOf(bookAt(SOURCE_A, 1)), 2 to listOf(bookAt(SOURCE_A, 2))),
                SOURCE_B to mapOf(1 to listOf(bookAt(SOURCE_B, 1)), 2 to listOf(bookAt(SOURCE_B, 2))),
            ),
        )
        // 只卡第一次 searchAcross（即首轮）：第二轮若真的起了，它不设门、会一路跑完
        manager.gateFor = KEYWORD_NEW to gate
        manager.gateRequestIndex = 0
        manager.tailAfterGate = listOf(
            SOURCE_A to SearchBookEntity(noteUrl = STALE_URL, name = "首轮卡点之后才到的书"),
        )
        val viewModel = viewModelWith(manager)

        viewModel.toSearchBooks(KEYWORD_NEW)
        advanceUntilIdle()
        assertEquals("前置条件：首轮已发出且卡在门后", 1, manager.requests.size)

        // 首轮未完即触底
        viewModel.loadMore()
        advanceUntilIdle()

        assertEquals(
            "在途期间不得再起一轮：只该有首轮那一次 searchAcross（实际请求 = ${manager.requests}）",
            listOf(Request(KEYWORD_NEW, 1, emptySet())),
            manager.requests,
        )

        // 放行首轮：它照常收尾
        gate.complete(Unit)
        advanceUntilIdle()

        val books = viewModel.list.value.map { it.noteUrl }
        assertTrue("首轮卡点后的结果仍应到手（那一轮没被误取消）", books.contains(STALE_URL))
        assertEquals("结果按 noteUrl 唯一（重复会让 LazyColumn 直接抛异常）", books.distinct(), books)
        assertEquals(
            "一轮的进度落定为 2/2，不被第二轮的清零与登记算重",
            SearchProgress(finished = 2, total = 2),
            viewModel.searchProgress.value,
        )
    }

    @Test
    fun `某源整页都是重复条目时被判到底`(): Unit = runTest(mainDispatcher) {
        val manager = FakeAggregateManager(
            results = mapOf(
                SOURCE_A to mapOf(
                    1 to listOf(bookAt(SOURCE_A, 1)),
                    // A 站第 2 页仍有新书，故它活到第 3 轮：只有第 3 轮真的发生，
                    // 「B 站已被跳过」这件事才可断言（两条源同时结束时 loadMore 会短路不发请求）
                    2 to listOf(bookAt(SOURCE_A, 2)),
                ),
                SOURCE_B to mapOf(
                    1 to listOf(bookAt(SOURCE_B, 1)),
                    // 第 2 页原样重复第 1 页的书目：越界页会以 HTTP 200 返回首页书目（软 404）
                    2 to listOf(bookAt(SOURCE_B, 1)),
                ),
            ),
        )
        val viewModel = viewModelWith(manager)

        viewModel.toSearchBooks(KEYWORD_NEW)
        advanceUntilIdle()
        viewModel.loadMore()
        advanceUntilIdle()
        viewModel.loadMore()
        advanceUntilIdle()

        assertEquals(
            "重复页之后该源不再被请求（判到底不能只看空页）",
            listOf(
                Request(KEYWORD_NEW, 1, emptySet()),
                Request(KEYWORD_NEW, 2, emptySet()),
                Request(KEYWORD_NEW, 3, setOf(SOURCE_B)),
            ),
            manager.requests,
        )
        assertEquals(
            "B 站第 2 页的重复条目没被追加，A 站第 2 页的新条目照常进来",
            listOf(urlOf(SOURCE_A, 1), urlOf(SOURCE_B, 1), urlOf(SOURCE_A, 2)),
            viewModel.list.value.map { it.noteUrl },
        )
    }

    @Test
    fun `换关键词重搜时旧流不再往新结果里灌`(): Unit = runTest(mainDispatcher) {
        val gate = CompletableDeferred<Unit>()
        val manager = FakeAggregateManager(
            results = mapOf(
                SOURCE_A to mapOf(1 to listOf(bookAt(SOURCE_A, 1))),
                SOURCE_B to mapOf(1 to listOf(bookAt(SOURCE_B, 1))),
            ),
        )
        // 旧关键词那一轮：发完两源的正常结果后卡在门口，卡点后面还带着一本「只有旧词才会给」的书
        manager.gateFor = KEYWORD_OLD to gate
        manager.tailAfterGate = listOf(
            SOURCE_A to SearchBookEntity(noteUrl = STALE_URL, name = "旧词的残留"),
        )
        val viewModel = viewModelWith(manager)

        viewModel.toSearchBooks(KEYWORD_OLD)
        advanceUntilIdle()
        assertEquals("旧轮卡点之前的正常结果已到手", 2, viewModel.list.value.size)

        // 新轮开始即应取消旧轮；随后放行门，旧流若还活着就会把残留灌进新结果
        viewModel.toSearchBooks(KEYWORD_NEW)
        gate.complete(Unit)
        advanceUntilIdle()

        assertFalse("旧流必须被取消，不该走到卡点之后", manager.tailEmitted)
        assertTrue("新结果里不该有旧轮的残留", viewModel.list.value.none { it.noteUrl == STALE_URL })
        assertEquals(
            "新一轮重新从第 1 页搜起",
            listOf(Request(KEYWORD_OLD, 1, emptySet()), Request(KEYWORD_NEW, 1, emptySet())),
            manager.requests,
        )
        assertEquals("新结果只有本轮的两条", 2, viewModel.list.value.size)
        assertEquals(
            "上一轮残留的结束标记不该污染新一轮",
            SearchProgress(finished = 2, total = 2),
            viewModel.searchProgress.value,
        )
    }

    @Test
    fun `进度按本轮已结束的源递增并在新一轮清零`(): Unit = runTest(mainDispatcher) {
        val manager = FakeAggregateManager(
            results = mapOf(
                // A 站第 1 页就没货：它第一轮即被判结束，于是第二轮只剩 B 站参与
                SOURCE_A to mapOf(1 to emptyList()),
                SOURCE_B to mapOf(1 to listOf(bookAt(SOURCE_B, 1))),
            ),
        )
        val viewModel = viewModelWith(manager)
        val seen = mutableListOf<SearchProgress>()
        manager.onSourceEmitted = { seen += viewModel.searchProgress.value }

        assertEquals("没搜过时不该有进度", SearchProgress(0, 0), viewModel.searchProgress.value)

        viewModel.toSearchBooks(KEYWORD_NEW)
        advanceUntilIdle()

        assertEquals("两条源各结束时各观测一次", 2, seen.size)
        assertEquals("第一条源结束时：本轮只登记了一条源", SearchProgress(1, 1), seen[0])
        assertEquals(SearchProgress(2, 2), seen[1])
        assertEquals(SearchProgress(2, 2), viewModel.searchProgress.value)

        // 只让 B 站还能翻页的那一轮：分母随本轮参与的源数收敛，分子从 0 重走
        viewModel.loadMore()
        advanceUntilIdle()

        assertEquals("第二轮只有 B 站参与，进度观测也只有一次", 3, seen.size)
        assertEquals(
            "上一轮的两个结束标记不许带进新一轮（否则进度直接落成 2/2）",
            SearchProgress(1, 1),
            seen[2],
        )
        assertEquals(SearchProgress(1, 1), viewModel.searchProgress.value)
    }

    @Test
    fun `某一路中途消失时进度仍随 AllFinished 收满`(): Unit = runTest(mainDispatcher) {
        val manager = FakeAggregateManager(
            results = mapOf(
                SOURCE_A to mapOf(1 to listOf(bookAt(SOURCE_A, 1))),
                SOURCE_B to mapOf(1 to listOf(bookAt(SOURCE_B, 1))),
            ),
            // B 站这一路只登记了开始就消失（真实现里是被取消后 flatMapMerge 丢弃整路，
            // 连 SourceFinished 都不会发）：只数 Finished 的话进度会永远停在 1/2
            vanishUrls = setOf(SOURCE_B),
        )
        val viewModel = viewModelWith(manager)

        viewModel.toSearchBooks(KEYWORD_NEW)
        advanceUntilIdle()

        assertEquals("消失的那一路不贡献任何结果", listOf(urlOf(SOURCE_A, 1)), viewModel.list.value.map { it.noteUrl })
        assertEquals(
            "本轮已结束就该把进度落定，否则「已收到 X/Y」永远差一格",
            SearchProgress(2, 2),
            viewModel.searchProgress.value,
        )
        assertFalse("进度落定后 UI 据此让进度行消失", viewModel.searchProgress.value.isRunning)
    }

    @Test
    fun `全部书源都失败时进入错误态而不是停在空列表`(): Unit = runTest(mainDispatcher) {
        val manager = FakeAggregateManager(
            results = emptyMap(),
            failures = mapOf(
                SOURCE_A to IllegalStateException("A 站挂了"),
                SOURCE_B to IllegalStateException("B 站挂了"),
            ),
        )
        val viewModel = viewModelWith(manager)

        viewModel.toSearchBooks(KEYWORD_NEW)
        advanceUntilIdle()

        assertEquals("一条源都没成功，必须给错误态", Overlay.NetworkError, viewModel.uiState.value.overlay)
        assertTrue(viewModel.list.value.isEmpty())
        assertEquals(
            "失败的源同样计入进度：进度条不能永远差一格",
            SearchProgress(2, 2),
            viewModel.searchProgress.value,
        )
    }

    @Test
    fun `部分书源失败时照常展示已收到的结果`(): Unit = runTest(mainDispatcher) {
        val manager = FakeAggregateManager(
            results = mapOf(SOURCE_B to mapOf(1 to listOf(bookAt(SOURCE_B, 1)))),
            failures = mapOf(SOURCE_A to IllegalStateException("A 站挂了")),
        )
        val viewModel = viewModelWith(manager)

        viewModel.toSearchBooks(KEYWORD_NEW)
        advanceUntilIdle()

        assertEquals(
            "一个站点挂了不该把整页打成错误态",
            Overlay.None,
            viewModel.uiState.value.overlay,
        )
        assertEquals(listOf(urlOf(SOURCE_B, 1)), viewModel.list.value.map { it.noteUrl })
        assertEquals(SearchProgress(2, 2), viewModel.searchProgress.value)
    }

    @Test
    fun `加入书架传的是该书所属书源的 parser`(): Unit = runTest(mainDispatcher) {
        val parserA = RecordingParser(SOURCE_A)
        val parserB = RecordingParser(SOURCE_B)
        val manager = FakeAggregateManager(
            results = mapOf(
                SOURCE_A to mapOf(1 to listOf(bookAt(SOURCE_A, 1))),
                SOURCE_B to mapOf(1 to listOf(bookAt(SOURCE_B, 1))),
            ),
            parsers = mapOf(SOURCE_A to parserA, SOURCE_B to parserB),
        )
        val viewModel = viewModelWith(manager)

        viewModel.toSearchBooks(KEYWORD_NEW)
        advanceUntilIdle()
        val target = viewModel.list.value.first { it.tag == SOURCE_B }
        viewModel.addBookToShelf(target)
        // addToShelf 整段挂在真实 Dispatchers.IO 上、不吃虚拟时钟（见 awaitUntil）：
        // 等书架事件真的回流到列表项，那才是整条写库链路走完的证据
        awaitUntil("加入书架已回流到列表项") {
            viewModel.list.value.first { it.noteUrl == target.noteUrl }.add
        }

        assertEquals("取源问的是这本书的归属", listOf(SOURCE_B), manager.parserForCalls)
        assertEquals(
            "交给 parser 的书架行带着该书自己的书源",
            SOURCE_B,
            parserB.bookInfoCalls.single().tag,
        )
        assertEquals("另一个源的 parser 一次都不该被调用", 0, parserA.bookInfoCalls.size)
        assertEquals(
            "加入书架后覆盖层要收掉，不能停在 Loading",
            Overlay.None,
            viewModel.uiState.value.overlay,
        )
    }

    @Test
    fun `已在书架的结果项被标成已添加`(): Unit = runTest(mainDispatcher) {
        val onShelf = urlOf(SOURCE_B, 1)
        val manager = FakeAggregateManager(
            results = mapOf(
                SOURCE_A to mapOf(1 to listOf(bookAt(SOURCE_A, 1))),
                SOURCE_B to mapOf(1 to listOf(bookAt(SOURCE_B, 1))),
            ),
        )
        val viewModel = viewModelWith(manager, shelf = listOf(BookShelfEntity(noteUrl = onShelf)))

        // 书架快照那趟读走真实 IO 线程（见 awaitUntil）：快照到位后重搜一轮，标记才该出现
        awaitUntil("已在书架的书被标出") {
            viewModel.toSearchBooks(KEYWORD_NEW)
            advanceUntilIdle()
            viewModel.list.value.any { it.add }
        }

        val marked = viewModel.list.value.single { it.add }
        assertEquals("标出来的正是书架上那本", onShelf, marked.noteUrl)
        assertEquals("另一本不该被标", 1, viewModel.list.value.count { !it.add })
    }

    @Test
    fun `插入搜索历史后向页面发出全量历史`(): Unit = runTest(mainDispatcher) {
        val histories = listOf(SearchHistoryEntity(type = 2, content = KEYWORD_NEW, date = 1L))
        val manager = FakeAggregateManager(results = emptyMap())
        val viewModel = viewModelWith(manager, histories = histories)
        val emitted = mutableListOf<List<SearchHistoryEntity>>()
        // 必须挂 backgroundScope：successEvent 是永不结束的 SharedFlow，挂在测试体自身的
        // scope 上会让 runTest 收尾时还剩一个 Active 子协程（UncompletedCoroutinesError）
        backgroundScope.launch { viewModel.successEvent.collect { emitted += it } }

        viewModel.insertSearchHistory(KEYWORD_NEW)

        // 仓库的两次读写都挂在 Dispatchers.IO 上，与虚拟时钟不同源：等 emit 真到达
        awaitUntil("历史列表已发出") { emitted.isNotEmpty() }
        assertEquals("插入后自动回查并 emit 一次", listOf(histories), emitted)
    }
}
