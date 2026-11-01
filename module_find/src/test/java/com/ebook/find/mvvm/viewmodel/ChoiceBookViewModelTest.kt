package com.ebook.find.mvvm.viewmodel

import androidx.lifecycle.SavedStateHandle
import com.ebook.common.analyze.source.LibraryDiskCache
import com.ebook.common.analyze.source.SourceBinding
import com.ebook.common.analyze.source.SourceExploreEntry
import com.ebook.common.analyze.source.SourceResolver
import com.ebook.common.manager.BookShelfManager
import com.ebook.common.repository.BookShelfEvent
import com.ebook.common.repository.ImportMergeResult
import com.ebook.common.repository.ShelfRepository
import com.ebook.db.entity.BookShelfEntity
import com.ebook.db.entity.LibraryEntity
import com.ebook.db.entity.SearchBookEntity
import com.ebook.db.entity.WebChapterEntity
import com.ebook.find.repository.BookSourceRepository
import com.ebook.source.analyze.AggregateSearchEvent
import com.ebook.source.analyze.BookParser
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [ChoiceBookViewModel] 的翻页接线测试（ADR-0057：游标与信号时序收进 `PageSession` 单主）。
 *
 * 锁的不是判据（`mergeBookPage` 由 `BookPageMergeTest` 锁、会话本身由 `PageSessionTest` 锁），
 * 而是**这个宿主把会话的在途闸门接对了**：触底连发两下只向仓库发一笔请求。
 *
 * 仓里没有 `ChoiceBookViewModelTest`，这同时是本 VM 的第一条用例：原先它只靠
 * 「基类只在加载更多时才调 `loadMore()`」这条隐式约定挡并发，会话把闸门收成显式可测的事实。
 *
 * 跑法沿用仓内 VM 用例口径（`BookDetailViewModelSourceTest` 同款）：真 [BookSourceRepository]
 * 配假 [SourceResolver]/[BookParser]、真 [BookShelfManager] 配假 [ShelfRepository]，只把调度器挂
 * [mainDispatcher]，轮询推进（`getKindBook`/`getAllBooks` 都跑在真实 `Dispatchers.IO` 上）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChoiceBookViewModelTest {

    private val mainDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `触底连发两下只向仓库发一笔加载更多请求`() = runTest(mainDispatcher) {
        val parser = PagingKindParser()
        val viewModel = viewModelWith(parser)

        viewModel.refreshData()
        awaitUntil("首屏已回填") { viewModel.list.value.isNotEmpty() }

        viewModel.loadMore()
        viewModel.loadMore()
        awaitUntil("第 2 页已回填") { viewModel.list.value.size == 4 }

        assertEquals(
            "第二下触底被会话闸门拦下：只该有一笔第 2 页请求在飞",
            listOf(KIND_URL to 1, KIND_URL to 2),
            parser.kindBookCalls,
        )
        assertEquals("去重后两页各 2 条、共 4 条", 4, viewModel.list.value.size)
    }

    /**
     * 轮询等到 [cond] 成立：交替推进测试调度器与真实时间。
     *
     * [BookSourceRepository.getKindBook] 与 [BookShelfManager.refresh] 都切
     * `withContext(Dispatchers.IO)`，跑在**真实 IO 线程**上、不受虚拟时钟控制，
     * 只推进调度器就会与之竞态（同一写法见 `BookDetailViewModelSourceTest`）。
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

    private fun TestScope.viewModelWith(parser: BookParser): ChoiceBookViewModel {
        val resolver = ParserResolver(parser)
        val repository = BookSourceRepository(
            sourceResolver = resolver,
            libraryDiskCache = LibraryDiskCache(
                Files.createTempDirectory("choice-vm").toFile().apply { deleteOnExit() },
            ),
        )
        val shelfManager = BookShelfManager(resolver, NoOpShelfRepository())
        return ChoiceBookViewModel(
            bookSourceRepository = repository,
            bookShelfManager = shelfManager,
            savedStateHandle = SavedStateHandle(
                mapOf("url" to KIND_URL, "source_url" to SOURCE_URL),
            ),
            bookShelfRepository = NoOpShelfRepository(),
        )
    }
}

/** 被测分类页归属的书源 URL（经导航参数传入，见 `ChoiceBookViewModel.sourceUrl`） */
private const val SOURCE_URL = "https://source.example"

/** 分类列表页地址（经 `SavedStateHandle` 的 "url" 键传入） */
private const val KIND_URL = "/xuanhuan"

/**
 * 记录每笔 `getKindBook` 的 `(url, page)`、按页返回不重叠书目的假 parser。
 *
 * 第 1 页回 1/2、第 2 页回 3/4：两页都有新条目，`mergeBookPage` 不会判「到底」，
 * 于是「只发一笔」这个断言纯粹反映会话闸门，不掺判到底的干扰。
 * 本页只触达 `getKindBook`，其余成员一律 `error` 兜底——静默返回空值会让
 * 「其实没走到那条路径」的断言假绿。
 */
private class PagingKindParser : BookParser {

    val kindBookCalls = mutableListOf<Pair<String, Int>>()

    override suspend fun getKindBook(url: String, page: Int): List<SearchBookEntity> {
        kindBookCalls += url to page
        return when (page) {
            1 -> listOf(kindBook(1), kindBook(2))
            2 -> listOf(kindBook(3), kindBook(4))
            else -> emptyList()
        }
    }

    /** 两页书目互不重叠（noteUrl 各异），`mergeBookPage` 才不会把第 2 页判成「无新条目」 */
    private fun kindBook(index: Int) = SearchBookEntity(
        noteUrl = "https://source.example/book/$index.html",
        name = "书$index",
    )

    private fun unsupported(who: String): Nothing =
        throw UnsupportedOperationException("PagingKindParser 不该被触达 $who")

    override suspend fun searchBook(content: String, page: Int): List<SearchBookEntity> =
        unsupported("searchBook")

    override suspend fun getBookInfo(bookShelf: BookShelfEntity): BookShelfEntity =
        unsupported("getBookInfo")

    override suspend fun getChapterList(bookShelf: BookShelfEntity): WebChapterEntity<BookShelfEntity> =
        unsupported("getChapterList")

    override suspend fun fetchLibraryData(): LibraryEntity = unsupported("fetchLibraryData")
}

/**
 * 只实现取源面的最小假件：把 `SOURCE_URL` 解析成 [PagingKindParser]，其余成员 `error` 兜底。
 *
 * 本页只经 `getKindBook` 触达书源，清单/管理面不在被测路径上；静默返回空值会让
 * 「其实没走到那条路径」的断言假绿（口径同 `LibraryViewModelTest` 的假件）。
 */
private class ParserResolver(private val parser: BookParser) : SourceResolver {

    override suspend fun resolveSource(sourceUrl: String): SourceBinding =
        if (sourceUrl == SOURCE_URL) SourceBinding.Found(parser)
        else SourceBinding.Missing(sourceUrl)

    override fun searchAcross(
        keyword: String,
        page: Int,
        skipSourceUrls: Set<String>,
    ): Flow<AggregateSearchEvent> = throw UnsupportedOperationException("ParserResolver 不该被触达 searchAcross")

    override suspend fun getExploreEntries(sourceUrl: String): List<SourceExploreEntry> =
        throw UnsupportedOperationException("ParserResolver 不该被触达 getExploreEntries")
}

/** 分类选书页不触达书架读写：空事件流 + 空书架即可，其余成员 `error` 兜底 */
private class NoOpShelfRepository : ShelfRepository() {

    override val bookShelfEvents: MutableSharedFlow<BookShelfEvent> =
        MutableSharedFlow(extraBufferCapacity = 1)

    override suspend fun getAllBooks(): List<BookShelfEntity> = emptyList()

    override fun observeBookShelf(): Flow<List<BookShelfEntity>> = flow { emit(emptyList()) }

    override suspend fun getAllBooksWithDetails(): List<BookShelfEntity> = emptyList()

    override suspend fun getBookByUrl(noteUrl: String): BookShelfEntity? = null

    override suspend fun getBookWithDetails(noteUrl: String): BookShelfEntity? = null

    override suspend fun saveProgress(bookShelf: BookShelfEntity): Unit =
        unsupported("saveProgress")

    override suspend fun addToShelf(bookShelf: BookShelfEntity): Unit =
        unsupported("addToShelf")

    override suspend fun removeFromShelf(bookShelf: BookShelfEntity): Unit =
        unsupported("removeFromShelf")

    override suspend fun switchSource(
        oldShelf: BookShelfEntity,
        newBook: SearchBookEntity,
    ): Result<BookShelfEntity> = unsupported("switchSource")

    override suspend fun mergeTailChapters(
        newNoteUrl: String,
        oldNoteUrl: String,
    ): ImportMergeResult = unsupported("mergeTailChapters")

    private fun unsupported(who: String): Nothing =
        throw UnsupportedOperationException("NoOpShelfRepository 不该被触达 $who")
}
