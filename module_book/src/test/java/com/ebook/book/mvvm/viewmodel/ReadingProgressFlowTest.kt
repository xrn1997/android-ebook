package com.ebook.book.mvvm.viewmodel

import android.content.Context
import android.os.Looper
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import com.ebook.common.analyze.local.BookFormat
import com.ebook.common.analyze.local.ChapterReader
import com.ebook.common.analyze.source.BookSourceManager
import com.ebook.common.repository.BookRepository
import com.ebook.common.store.BookStore
import com.ebook.common.store.ChapterContentCache
import com.ebook.common.store.WriteTransactionRunner
import com.ebook.db.AppDatabase
import com.ebook.db.entity.BookInfoEntity
import com.ebook.db.entity.BookShelfEntity
import com.ebook.db.entity.ChapterListEntity
import com.ebook.db.entity.SearchBookEntity
import com.ebook.db.entity.WebChapterEntity
import com.ebook.source.analyze.BookParser
import com.xrn1997.common.BaseApplication
import java.io.File
import java.lang.reflect.Proxy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 「读至XXX变空 → 点进去提示章节加载失败」这条用户序列的**端到端**回归
 * （真 ViewModel + 真仓库 + Room 内存库跑真 SQL）。
 *
 * 与另两份锁的分工（为什么要三份）：
 * - `lib_book_common` 的 `ChapterAppendProgressTest` 锁仓库与 SQL 语义，但它对阅读器/详情页是
 *   **照生产写法镜像**出来的，生产代码改一行它不会红；
 * - `BookDetailViewModelSourceTest` 真跑详情页 VM，但 DAO 是空库替身，看不见落库与回读的互作；
 * - 本文件把两个真 VM 串起来：详情页交出去的那份目录 → 阅读器持有它 → 末章追更 → 跳最新 →
 *   退出保存 → 书架回读。症状的两处读点（书架 `chapterList.getOrNull(durChapter)`、阅读器
 *   `getChapter(index)`）都由生产代码本身跑出来，所以任何一环把「列表位置」写歪这里就红。
 *
 * `BookSourceManager` 与 `BookParser` 用 [Proxy] 桩而不是手写实现：两个接口成员多，本流程只用到
 * `getParserFor` / `getBookInfo` / `getChapterList`，其余成员一旦被问就抛，「其实没走到那条路径」
 * 不会假绿。lib_book_common 里那两份同名假件是 internal，跨模块看不到，只能各留一份。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReadingProgressFlowTest {

    private companion object {
        const val SOURCE_A = "https://a.example"
        const val SOURCE_B = "https://b.example"
        const val NOTE_URL = "a.example/book/1.html"

        fun chapter(source: String, index: Int) = ChapterListEntity(
            noteUrl = NOTE_URL,
            durChapterIndex = index,
            contentRef = "$source/chapter/${index + 1}.html",
            durChapterName = "第${index + 1}章",
            tag = source,
        )
    }

    private val mainDispatcher = StandardTestDispatcher()
    private lateinit var db: AppDatabase
    private lateinit var repository: BookRepository

    /** 「哪条源用哪个 parser」的注册表；仓库与详情页 VM 共用同一份假清单 */
    private val parsers = mutableMapOf<String, BookParser>()

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        // 失败提示要取 string 资源（与 BookDetailViewModelSourceTest 同一套跑法）
        BaseApplication.context = context
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = BookRepository(
            bookShelfDao = db.bookShelfDao(),
            bookInfoDao = db.bookInfoDao(),
            chapterListDao = db.chapterListDao(),
            bookGroupDao = db.bookGroupDao(),
            downloadChapterDao = db.downloadChapterDao(),
            chapterReaders = emptyMap<BookFormat, ChapterReader>(),
            bookSourceManager = managerStub(parsers),
            bookStore = BookStore(File(System.getProperty("java.io.tmpdir"), "reading-progress-flow")),
            contentCache = ChapterContentCache(capacity = 3),
            // 就地执行事务：Room 的 withWriteTransaction 在 runTest 的 Main 调度器下等不到
            // Robolectric 的主 looper，会把走写事务的用例整条僵死（本文件最初三条用例全卡在
            // awaitUntil，唯一不写事务的那条正常跑到断言）。写库与回读仍是真 SQL，
            // 而「半笔写必须回滚」由 lib_book_common 那份真事务的 ChapterAppendProgressTest 锁
            transactions = object : WriteTransactionRunner {
                override suspend fun <R> run(block: suspend () -> R): R = block()
            },
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun managerStub(parsers: Map<String, BookParser> = emptyMap()): BookSourceManager =
        Proxy.newProxyInstance(
            BookSourceManager::class.java.classLoader,
            arrayOf(BookSourceManager::class.java),
        ) { _, method, args ->
            when (method.name) {
                // 照抄真实现的两条短路：空白与 loc_book 不查库直接 null，否则「本地书不会被报成
                // 书源失效」这类断言就成了假命题
                "getParserFor" -> (args[0] as String)
                    .takeUnless { it.isBlank() || it == BookShelfEntity.LOCAL_TAG }
                    ?.let { parsers[it] }

                "equals" -> false
                "hashCode" -> System.identityHashCode(parsers)
                "toString" -> "ManagerStub"
                else -> throw UnsupportedOperationException(
                    "BookSourceManager.${method.name} 不该被本用例调用",
                )
            }
        } as BookSourceManager

    /**
     * 只回「源上这份目录」的 parser 桩，形为与真解析器一致：详情原样回传入参、
     * 目录把 [remote] 挂到入参实体上再回出去（笔记/进度都由流程自己带，桩不塞额外信息）。
     * [chapterListCalls] 用来钉「不为落库再翻一遍目录页」。
     */
    private fun parserStub(
        remote: List<ChapterListEntity>,
        chapterListCalls: () -> Unit = {},
    ): BookParser = Proxy.newProxyInstance(
        BookParser::class.java.classLoader,
        arrayOf(BookParser::class.java),
    ) { _, method, args ->
        when (method.name) {
            "getBookInfo" -> args[0]
            "getChapterList" -> {
                chapterListCalls()
                WebChapterEntity(
                    data = (args[0] as BookShelfEntity).copy(chapterList = remote),
                    next = false,
                )
            }

            "equals" -> false
            "hashCode" -> System.identityHashCode(remote)
            "toString" -> "ParserStub"
            else -> throw UnsupportedOperationException("BookParser.${method.name} 不该被本用例调用")
        }
    } as BookParser

    /** 播种一本在架的网络书：库里正好是 [localIndices] 这些序号 */
    private fun seedLocal(
        source: String = SOURCE_A,
        localIndices: List<Int>,
        durChapter: Int = 0,
    ) = runBlocking {
        repository.addToShelf(
            BookShelfEntity(noteUrl = NOTE_URL, tag = source, durChapter = durChapter).apply {
                bookInfo = BookInfoEntity(noteUrl = NOTE_URL, name = "斗破苍穹", origin = source)
                chapterList = localIndices.map { chapter(source, it) }
            }
        )
    }

    /**
     * 把「上次得出目录结论」的时间戳清零，等价于生产上的"距上次检查已超过限频窗口"。
     *
     * 需要它是因为 `addToShelf` 那一刻就写过一次非零时间戳（规格 §限频：刚加的书进详情页
     * 不该立刻重爬多页目录），不清零的话末章追更会被限频挡掉，测不到要测的那一步。
     */
    private fun markTocCheckDue() = runBlocking {
        db.bookInfoDao().setFinalRefreshData(NOTE_URL, 0L)
    }

    /** 书架条目上「读至」那一章（与 BookShelfPage 同一个式子；null 即用户看到的空白） */
    private fun readToChapter(): ChapterListEntity? = runBlocking {
        repository.getAllBooksWithDetails().single().let { it.chapterList.getOrNull(it.durChapter) }
    }

    private fun shelfRow(): BookShelfEntity? = runBlocking { db.bookShelfDao().getBookByUrl(NOTE_URL) }

    /**
     * 轮询等到 [cond] 成立，交替推进测试调度器、Robolectric 主 looper 与真实时间。
     *
     * 与 `BookDetailViewModelSourceTest` 那份同名 helper 的差别只在中间那一行
     * [shadowOf]`.idle()`：本文件的仓库接的是**真 Room**，它的后台执行器由
     * `ArchTaskExecutor` 提供，在 Robolectric 下落在**被暂停的主 looper**上。只
     * `advanceUntilIdle()` 的话，`viewModelScope` 里第一个 Room 查询就永远回不来
     * —— 症状是流程一步都不推进（连 parser 都没被问过一次），而同一批仓库调用放在
     * 纯 `runBlocking` 里却全部正常，很容易误判成产品代码死锁。
     */
    private suspend fun TestScope.awaitUntil(message: String, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!cond()) {
            advanceUntilIdle()
            shadowOf(Looper.getMainLooper()).idle()
            if (cond()) break
            assertTrue("$message（5s 内未达成）", System.currentTimeMillis() < deadline)
            withContext(Dispatchers.IO) { Thread.sleep(10) }
        }
        advanceUntilIdle()
    }

    @Test
    fun `从搜索进详情读到末章跳最新，书架读至仍指得到那一章`(): Unit = runTest(mainDispatcher) {
        // 本地 3 行、源上 5 章 —— 用户报的那本追更书就是这个形状
        seedLocal(localIndices = listOf(0, 1, 2))
        var chapterListCalls = 0
        parsers[SOURCE_A] = parserStub((0 until 5).map { chapter(SOURCE_A, it) }) { chapterListCalls++ }
        val detail = BookDetailViewModel(repository, managerStub(parsers))
        detail.initFromSearch(SearchBookEntity(noteUrl = NOTE_URL, tag = SOURCE_A))

        detail.getBookShelfInfo()
        awaitUntil("详情流程结束") { !detail.detailState.value.loading }
        assertEquals("详情页目录该是落库后回读的那份", 5, detail.mBookShelf?.chapterList?.size)

        // BookDetailActivity.onReadClick 交出去的就是详情页这份
        val reader = BookReadViewModel(repository).apply { bookShelf = detail.mBookShelf!!.copy() }
        assertNull(
            "末章再查一次该被限频挡下（详情页刚为这本书落过库、写过结论）",
            reader.appendChaptersIfAny(),
        )
        assertEquals("为落库不该再翻一遍目录页", 1, chapterListCalls)
        assertEquals("库里已长到 5 行", 5, runBlocking { repository.getStoredChapters(NOTE_URL).size })

        reader.updateProgress(reader.getChapterListSize() - 1, 0)
        reader.saveProgress()
        awaitUntil("进度已落库") { readToChapter()?.durChapterName == "第5章" }

        val shelf = runBlocking { repository.getAllBooksWithDetails().single() }
        assertEquals("落点本就在库内，钳制不该出手", 4, shelf.durChapter)
        assertNotNull("书架「读至」不该空白", readToChapter())
        assertNotNull("点进去要能解析出这一章（loadPage 的第一道判据）", reader.getChapter(shelf.durChapter))
    }

    @Test
    fun `从书架直接进阅读器读到末章追更后跳最新，读至同样有效`(): Unit = runTest(mainDispatcher) {
        seedLocal(localIndices = listOf(0, 1, 2), durChapter = 2)
        markTocCheckDue()
        parsers[SOURCE_A] = parserStub((0 until 5).map { chapter(SOURCE_A, it) })
        // 这条路径没有详情页参与：书架页把库内条目直接交给阅读器
        val reader = BookReadViewModel(repository).apply {
            bookShelf = runBlocking { repository.getAllBooksWithDetails().single() }
        }

        val appended = reader.appendChaptersIfAny()
        assertNotNull("末章该追到新章", appended)
        assertEquals(2, appended?.size)
        assertEquals("追更后内存与库同一份目录", 5, reader.getChapterListSize())

        reader.updateProgress(reader.getChapterListSize() - 1, 0)
        reader.saveProgress()
        awaitUntil("进度已落库") { readToChapter()?.durChapterName == "第5章" }
        assertEquals(4, runBlocking { repository.getAllBooksWithDetails().single().durChapter })
        assertNotNull(reader.getChapter(runBlocking { reader.bookShelf!!.durChapter }))
    }

    @Test
    fun `未加书架时中途加书架，进度不该被落点钳制抹掉`(): Unit = runTest(mainDispatcher) {
        // 从搜索直接开读（库里一行都没有），读到第 100 章时点「加入书架」
        // 未加书架时，阅读器那份实体来自详情页的 fetchBookInfo（带 bookInfo，加书架才写得出
        // book_info 行；不带的话 getAllBooksWithDetails 会把它当孤立记录删掉）
        val carrier = BookShelfEntity(noteUrl = NOTE_URL, tag = SOURCE_A).apply {
            bookInfo = BookInfoEntity(noteUrl = NOTE_URL, name = "斗破苍穹", origin = SOURCE_A)
        }
        val reader = BookReadViewModel(repository).apply {
            bookShelf = carrier.copy(chapterList = (0 until 100).map { chapter(SOURCE_A, it) })
        }
        reader.updateProgress(99, 0)
        reader.saveProgress()
        awaitUntil("进度行已写过一次（此时还没有 book_info，是条孤立记录）") { shelfRow() != null }

        reader.addToShelf(null)
        // 等的是「整个条目到位」，不是「书架上先长出一行」：生产的 `addToShelf` 把四张表收进一笔
        // 写事务，读方看不到半截条目；本文件的假事务接缝刻意就地执行 block（真 Room 事务在 runTest
        // 的 Main 下等不到 Robolectric 主 looper，见 setUp 里那条注释），所以「书架行已在、100 章
        // 还没写完」这个窗口在测试里仍然存在。只等 `size == 1` 就会挤进窗口读到空目录，
        // 「读至 第100章」偶发为 null（症状像落点钳制出手，实际是读落在了半截条目上）。
        // 「半截不可见」这条不变量由 `lib_book_common` 的
        // `addToShelf 把整个条目收进一次写事务并在提交后才发事件` 在纯 JVM 侧锁住。
        awaitUntil("已加入书架") {
            runBlocking {
                repository.getAllBooksWithDetails().size == 1 &&
                    repository.getStoredChapters(NOTE_URL).size == 100
            }
        }

        assertEquals(
            "加书架写进去的进度必须还是用户在读的那一章",
            "第100章",
            readToChapter()?.durChapterName,
        )
    }

    @Test
    fun `从另一条源的搜索结果进详情，不该动这本书的目录也不该算作检查结论`(): Unit = runTest(mainDispatcher) {
        // 书架上这本绑 A 源；用户从 B 源的搜索结果点进同一 noteUrl 的详情页
        seedLocal(localIndices = listOf(0, 1, 2))
        // 基准：加书架那一刻就写过一次结论（不是 0），要锁的是"跨源浏览没把它推后"
        val stampBefore = runBlocking { db.bookInfoDao().getBookInfoByUrl(NOTE_URL)?.finalRefreshData }
        assertTrue("播种后应已有一次目录结论的时间戳", (stampBefore ?: 0L) > 0L)
        parsers[SOURCE_B] = parserStub((0 until 5).map { chapter(SOURCE_B, it) })
        val detail = BookDetailViewModel(repository, managerStub(parsers))
        detail.initFromSearch(SearchBookEntity(noteUrl = NOTE_URL, tag = SOURCE_B))

        detail.getBookShelfInfo()
        awaitUntil("详情流程结束") { !detail.detailState.value.loading }

        val stored = runBlocking { repository.getStoredChapters(NOTE_URL) }
        assertEquals("别处的目录不该落进这本书", 3, stored.size)
        assertEquals("一行都不该被改动", listOf(0, 1, 2), stored.map { it.durChapterIndex })
        assertEquals(
            "页面显示的也该是本地实际可读的那几章",
            3,
            detail.mBookShelf?.chapterList?.size,
        )
        assertNull(
            "跨源的结论不该被记成本地目录分叉、更不该占掉这本书自己的检查窗口",
            runBlocking { db.bookInfoDao().getBookInfoByUrl(NOTE_URL)?.finalRefreshData }
                ?.takeIf { it != stampBefore },
        )
        assertTrue(
            "跨源浏览不该弹出分叉提示",
            !detail.detailState.value.tocDiverged,
        )
    }

    @Test
    fun `跨源打开的书在阅读器里读到末章，追更该被归属门静默跳过`(): Unit = runTest(mainDispatcher) {
        // 书架上这本绑 A 源；用户从 B 源的搜索结果进详情再点开阅读 —— 详情页的归属门跳过了落库，
        // 但把 tag=B、目录=库内 A 源行的实体交给了阅读器（生产上 BookDetailActivity 传的就是这份）
        seedLocal(localIndices = listOf(0, 1, 2))
        val stampBefore = runBlocking { db.bookInfoDao().getBookInfoByUrl(NOTE_URL)?.finalRefreshData }
        var chapterListCalls = 0
        parsers[SOURCE_B] = parserStub((0 until 5).map { chapter(SOURCE_B, it) }) { chapterListCalls++ }
        val reader = BookReadViewModel(repository).apply {
            bookShelf = BookShelfEntity(noteUrl = NOTE_URL, tag = SOURCE_B).copy(
                chapterList = runBlocking { repository.getStoredChapters(NOTE_URL) },
            )
        }

        assertNull(
            "tag 与书架行不符，追更必须整次跳过",
            reader.appendChaptersIfAny(),
        )
        assertEquals("归属不符时连目录页都不该翻", 0, chapterListCalls)
        val stored = runBlocking { repository.getStoredChapters(NOTE_URL) }
        assertEquals("别家源的目录不该落进这本书", 3, stored.size)
        assertEquals("一行都不该被改动", listOf(0, 1, 2), stored.map { it.durChapterIndex })
        assertEquals(
            "跨源追更不该占掉这本书自己的检查窗口",
            stampBefore,
            runBlocking { db.bookInfoDao().getBookInfoByUrl(NOTE_URL)?.finalRefreshData },
        )
    }

    @Test
    fun `未加书架读到末章，追更该被归属门跳过而不是写出孤行`(): Unit = runTest(mainDispatcher) {
        // 从搜索直接开读（库里零行）：追更写进去的是永远没人清理的 chapter_list 孤行 ——
        // book_shelf 没有对应行，书架读不到它，而「移出书架」清理章节又是从书架行发起的
        var chapterListCalls = 0
        parsers[SOURCE_A] = parserStub((0 until 5).map { chapter(SOURCE_A, it) }) { chapterListCalls++ }
        val reader = BookReadViewModel(repository).apply {
            bookShelf = BookShelfEntity(noteUrl = NOTE_URL, tag = SOURCE_A)
                .copy(chapterList = (0 until 3).map { chapter(SOURCE_A, it) })
        }

        assertNull("不在书架上的书追更必须整次跳过", reader.appendChaptersIfAny())
        assertEquals("跳过时连目录页都不该翻", 0, chapterListCalls)
        assertEquals(
            "chapter_list 里不该出现无书架行配对的孤行",
            0,
            runBlocking { repository.getStoredChapters(NOTE_URL).size },
        )
        assertNull("也不该写出书架行", shelfRow())
    }
}
