package com.ebook.common.repository

import com.ebook.common.analyze.local.BookFormat
import com.ebook.common.analyze.local.ChapterReader
import com.ebook.common.analyze.source.FakeBookSourceManager
import com.ebook.common.analyze.source.RecordingBookParser
import com.ebook.common.store.BookStore
import com.ebook.common.store.ChapterContentCache
import com.ebook.db.entity.BookInfoEntity
import com.ebook.db.entity.LibraryEntity
import com.ebook.db.entity.BookShelfEntity
import com.ebook.db.entity.ChapterListEntity
import com.ebook.db.entity.SearchBookEntity
import com.ebook.db.entity.WebChapterEntity
import com.ebook.source.analyze.BookParser
import java.io.IOException
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * [BookRepository.syncChaptersFromSource] 与目录重抓限频的单元测试（纯 JVM，假 DAO + 假 parser）。
 *
 * 前缀判定本身的边界形态由 [ChapterTocDiffTest] 穷举，本文件锁的是**编排层**那几件
 * 一旦回归就静默毁数据的事：
 * 1. **序号不撞车**：新章从 `max(dur_chapter_index) + 1` 起，且落库后全书序号唯一。
 *    远端行自带的是 parser 按位置写的序号，本地有洞时两套口径不同，
 *    直接沿用会让两行撞同一个 index —— 主键是 `content_ref`，撞号既不报错也不闪退，
 *    只是 `chapterList.getOrNull(index)` 返回错的那一章；
 * 2. **时间戳只在得出结论后写**：取源失败、抓目录异常、远端目录为空都**不写**
 *    （那是暂时性故障，写了就等于把用户永久挡在检查之外），
 *    而 `UpToDate` 与 `Diverged` 都写（都是确定结论，重试无意义）；
 * 3. **失败一律不抛、以类型化结果带回**，且失败时本地目录一行未动；
 * 4. 事件只在追加成功时发（分叉与「已是最新」都不该让消费方去重查没变的东西）。
 *
 * 关于事务回滚：假接缝 [DirectTransactionRunner] 直接执行 block、不回滚，所以本文件锁的是
 * 「失败发生在任何写之前」，回滚本身由生产实现的 Room 写事务负责，不在纯 JVM 里假装验证。
 */
class BookRepositoryChapterSyncTest {

    private companion object {
        const val SOURCE = "https://a.example"

        // noteUrl 在本仓同时是内容仓库的目录名（filesDir/books/<noteUrl>/），
        // 带 scheme 在 Windows 的 JVM 测试里会被当成盘符，故取不带 https:// 的形态
        const val NOTE_URL = "a.example/book/1.html"

        fun chapter(index: Int) = ChapterListEntity(
            noteUrl = NOTE_URL,
            durChapterIndex = index,
            contentRef = "$SOURCE/chapter/${index + 1}.html",
            durChapterName = "第${index + 1}章",
            tag = SOURCE,
        )
    }

    /** 章文件按用例隔离：BookRepository 的构造要求有 BookStore，本测试不读写章文件 */
    @get:Rule
    val booksRoot = TemporaryFolder()

    private lateinit var daos: FakeDaos
    private lateinit var manager: FakeBookSourceManager
    private lateinit var repository: BookRepository

    @Before
    fun setUp() {
        daos = FakeDaos()
        manager = FakeBookSourceManager()
        repository = BookRepository(
            bookShelfDao = daos.shelf,
            bookInfoDao = daos.info,
            chapterListDao = daos.chapter,
            bookGroupDao = daos.group,
            downloadChapterDao = daos.download,
            // 本路径不读正文，reader 一律用不上
            chapterReaders = emptyMap<BookFormat, ChapterReader>(),
            bookSourceManager = manager,
            bookStore = BookStore(booksRoot.root),
            contentCache = ChapterContentCache(capacity = 3),
            transactions = DirectTransactionRunner,
        )
    }

    // ===== 装配 helper =====

    /** 登记一个「解出 [chapterCount] 章」的 parser，并交出实例以便断言被调了几次 */
    private fun givenRemote(chapterCount: Int): RecordingBookParser {
        val shelf = BookShelfEntity(noteUrl = NOTE_URL, tag = SOURCE).apply {
            bookInfo = BookInfoEntity(noteUrl = NOTE_URL, name = "斗破苍穹")
        }
        val parser = RecordingBookParser(
            ownedSourceUrl = SOURCE,
            bookInfoResult = shelf,
            chapterListData = shelf.copy(
                chapterList = (0 until chapterCount).map { chapter(it) },
            ),
        )
        manager.putParser(SOURCE, parser)
        return parser
    }

    /** 走真实写入把一本有 [localCount] 章的网络书放进书架（连带 book_info 的时间戳） */
    private suspend fun seedShelfWithChapters(localCount: Int) {
        repository.addToShelf(
            BookShelfEntity(noteUrl = NOTE_URL, tag = SOURCE).apply {
                bookInfo = BookInfoEntity(noteUrl = NOTE_URL, name = "斗破苍穹")
                chapterList = (0 until localCount).map { chapter(it) }
            }
        )
    }

    /** 播种指定序号的本地目录（用于造「历史删章留下的洞」与分叉形态） */
    private suspend fun seedChapters(chapters: List<ChapterListEntity>) {
        repository.addToShelf(
            BookShelfEntity(noteUrl = NOTE_URL, tag = SOURCE).apply {
                bookInfo = BookInfoEntity(noteUrl = NOTE_URL, name = "斗破苍穹")
                chapterList = chapters
            }
        )
    }

    /** 从库里现有行造一个条目，模拟「调用方手里那份 BookShelfEntity」 */
    private suspend fun entryForSync(): BookShelfEntity = BookShelfEntity(
        noteUrl = NOTE_URL,
        tag = SOURCE,
    ).apply {
        bookInfo = daos.info.getBookInfoByUrl(NOTE_URL)
        chapterList = daos.chapter.getChaptersForBook(NOTE_URL)
    }

    private suspend fun storedTimestamp(): Long =
        daos.info.getBookInfoByUrl(NOTE_URL)?.finalRefreshData ?: 0L

    // ===== 限频判窗（纯函数） =====

    @Test
    fun `从未检查过时视为到期`() {
        // 存量网络书的 final_refresh_data 全是 0，升级后第一次进详情页必须放行检查
        assertTrue(isTocCheckDue(lastMillis = 0L, nowMillis = 9_000_000L))
    }

    @Test
    fun `窗口内不再检查`() {
        val now = 1_700_000_000_000L
        assertEquals(
            false,
            isTocCheckDue(lastMillis = now - BookShelfEntity.REFRESH_TIME + 1, nowMillis = now),
        )
    }

    @Test
    fun `恰好到期的那一刻算到期，边界是大于等于`() {
        val now = 1_700_000_000_000L
        assertTrue(isTocCheckDue(lastMillis = now - BookShelfEntity.REFRESH_TIME, nowMillis = now))
    }

    // ===== 加书架即视为已检查 =====

    @Test
    fun `addToShelf 把章节时间戳写成当前时间`() = runTest {
        // 加书架那一刻已经抓过一次目录（BookShelfManager.addFromSearch 调了 getChapterList）。
        // 不写时间戳，用户紧接着进详情页就会白爬一遍多页目录。
        val shelf = BookShelfEntity(noteUrl = NOTE_URL, tag = SOURCE).apply {
            bookInfo = BookInfoEntity(noteUrl = NOTE_URL, name = "斗破苍穹", finalRefreshData = 0L)
            chapterList = listOf(chapter(0))
        }

        repository.addToShelf(shelf)

        assertTrue(
            "加书架必须把 finalRefreshData 写成非零，否则刚加的书进详情页立刻重抓一次目录",
            storedTimestamp() > 0L,
        )
    }

    @Test
    fun `加书架不覆盖解析器带回的既有时间戳`() = runTest {
        val shelf = BookShelfEntity(noteUrl = NOTE_URL, tag = SOURCE).apply {
            bookInfo = BookInfoEntity(noteUrl = NOTE_URL, finalRefreshData = 12345L)
        }

        repository.addToShelf(shelf)

        assertEquals(12345L, storedTimestamp())
    }

    // ===== 三种不触网的出口 =====

    @Test
    fun `本地书不参与目录检查，也不取 parser`() = runTest {
        val parser = givenRemote(3)

        val result = repository.syncChaptersFromSource(
            BookShelfEntity(noteUrl = NOTE_URL, tag = BookShelfEntity.LOCAL_TAG)
        )

        assertEquals(ChapterSyncResult.NotNetworkBook, result)
        assertTrue("本地书不该被拿去查 parser：loc_book 在 book_source 表永远查不到行",
            manager.parserForCalls.isEmpty())
        assertEquals(0, parser.chapterListCalls.size)
    }

    @Test
    fun `限频窗口内直接返回，不发任何网络请求`() = runTest {
        val parser = givenRemote(4)
        seedShelfWithChapters(localCount = 3)

        val result = repository.syncChaptersFromSource(entryForSync())

        assertEquals(ChapterSyncResult.Throttled, result)
        assertEquals("被限频挡下时不该抓目录", 0, parser.chapterListCalls.size)
    }

    @Test
    fun `force 绕开限频`() = runTest {
        val parser = givenRemote(4)
        seedShelfWithChapters(localCount = 3)

        repository.syncChaptersFromSource(entryForSync(), force = true)

        assertEquals(1, parser.chapterListCalls.size)
    }

    // ===== 失败：一律不写时间戳、不动本地目录 =====

    @Test
    fun `取不到 parser 时以类型化失败带回，不写时间戳也不动目录`() = runTest {
        seedShelfWithChapters(localCount = 3)
        val chaptersBefore = daos.chapter.storedValues().size
        val timestampBefore = storedTimestamp()

        val result = repository.syncChaptersFromSource(entryForSync(), force = true)

        assertTrue("实际 $result", result is ChapterSyncResult.Failed)
        assertEquals(chaptersBefore, daos.chapter.storedValues().size)
        assertEquals("失败不该写时间戳：那是暂时性故障，写了就把用户挡在检查之外",
            timestampBefore, storedTimestamp())
    }

    @Test
    fun `抓目录抛异常时包成 Failed，不吞成 UpToDate`() = runTest {
        seedShelfWithChapters(localCount = 3)
        val timestampBefore = storedTimestamp()
        manager.putParser(SOURCE, ThrowingBookParser(IOException("网络断了")))

        val result = repository.syncChaptersFromSource(entryForSync(), force = true)

        assertTrue("实际 $result", result is ChapterSyncResult.Failed)
        assertEquals("网络失败不该写时间戳，否则下次进页面整个窗口内都不再重试",
            timestampBefore, storedTimestamp())
    }

    @Test
    fun `远端目录为空按失败处置而非分叉，且不写时间戳`() = runTest {
        // 「一本书零章」不是合法状态。判 Diverged 会连带写时间戳，
        // 于是解析规则修好之后整个窗口内都不再试。
        givenRemote(0)
        seedShelfWithChapters(localCount = 3)
        val timestampBefore = storedTimestamp()

        val result = repository.syncChaptersFromSource(entryForSync(), force = true)

        assertTrue("实际 $result", result is ChapterSyncResult.Failed)
        assertEquals(timestampBefore, storedTimestamp())
        assertEquals(3, daos.chapter.storedValues().size)
    }

    // ===== 三种结论 =====

    @Test
    fun `远端与本地一致时写时间戳、不加行、不发事件`() = runTest {
        givenRemote(3)
        seedShelfWithChapters(localCount = 3)
        val events = mutableListOf<BookShelfEvent>()
        backgroundScope.launch { repository.bookShelfEvents.toList(events) }
        runCurrent()

        val result = repository.syncChaptersFromSource(entryForSync(), force = true)
        runCurrent()

        assertEquals(ChapterSyncResult.UpToDate, result)
        assertEquals(3, daos.chapter.storedValues().size)
        assertTrue("UpToDate 不发事件：数据库没有任何变化", events.isEmpty())
    }

    @Test
    fun `分叉时写时间戳但一行目录都不动、也不发事件`() = runTest {
        // 本地第 2 章的 contentRef 与远端对不上 → 整笔放弃
        seedChapters(
            listOf(
                chapter(0),
                chapter(1).copy(contentRef = "$SOURCE/old/2.html"),
                chapter(2),
            )
        )
        givenRemote(4)
        val chaptersBefore = daos.chapter.storedValues()
        val events = mutableListOf<BookShelfEvent>()
        backgroundScope.launch { repository.bookShelfEvents.toList(events) }
        runCurrent()

        val result = repository.syncChaptersFromSource(entryForSync(), force = true)
        runCurrent()

        assertEquals(ChapterSyncResult.Diverged, result)
        assertEquals("分叉时本地目录必须逐字不变", chaptersBefore, daos.chapter.storedValues())
        assertTrue("分叉不发事件", events.isEmpty())
        assertTrue("分叉是确定结论，要写时间戳，否则每次进详情页都重爬一遍目录",
            storedTimestamp() > 0L)
    }

    @Test
    fun `前缀成立时追加新章、写时间戳并发 ChaptersUpdated`() = runTest {
        givenRemote(5)
        seedShelfWithChapters(localCount = 3)
        val events = mutableListOf<BookShelfEvent>()
        backgroundScope.launch { repository.bookShelfEvents.toList(events) }
        runCurrent()

        val result = repository.syncChaptersFromSource(entryForSync(), force = true)
        runCurrent()

        val appended = (result as ChapterSyncResult.Appended).appended
        assertEquals(2, appended.size)
        assertEquals(5, daos.chapter.getChaptersForBook(NOTE_URL).size)
        assertEquals(listOf(3, 4), appended.map { it.durChapterIndex })
        assertEquals(
            listOf(chapter(3).contentRef, chapter(4).contentRef),
            appended.map { it.contentRef },
        )
        assertEquals(1, events.size)
        val event = events.single()
        assertTrue("实际 $event", event is BookShelfEvent.ChaptersUpdated)
        assertEquals(
            "事件里的实体要带完整目录，消费方不该再查一次库",
            5,
            (event as BookShelfEvent.ChaptersUpdated).bookShelf.chapterList.size,
        )
    }

    @Test
    fun `本地序号有洞时新章从 max 加一起，全书序号不得重复`() = runTest {
        // 本文件最要紧的一条回归锁。远端行的 durChapterIndex 是 parser 按位置写的 chapters.size，
        // 本地有洞时两套口径不同：直接沿用会让新行与既有行撞同一个 index。
        // 撞号既不报错也不闪退（主键是 content_ref，两行都插得进去），
        // 只是 chapterList.getOrNull(index) 返回错的那一章。
        seedChapters(listOf(chapter(0), chapter(1), chapter(2), chapter(5), chapter(6)))
        givenRemote(9)

        val result = repository.syncChaptersFromSource(entryForSync(), force = true)

        val all = daos.chapter.getChaptersForBook(NOTE_URL)
        val appended = (result as ChapterSyncResult.Appended).appended
        assertEquals(
            "有洞时 size 与 max+1 不等，用 size 会覆写既有章文件",
            listOf(7, 8),
            appended.map { it.durChapterIndex },
        )
        assertEquals(
            "全书序号必须唯一，否则就是静默读错章",
            all.size,
            all.map { it.durChapterIndex }.distinct().size,
        )
    }

    @Test
    fun `用户删过中间章节的书仍能正常追更，且不会把缺的章补回来`() = runTest {
        // 用户删过站点第 3、4 章：本地剩 0,1,4,5 四行，contentRef 仍是第 1、2、5、6 章。
        // 按位置逐位比会把这本书判成一世分叉、再也追不了更 —— 本用例锁住那个回归。
        seedChapters(listOf(chapter(0), chapter(1), chapter(4), chapter(5)))
        givenRemote(7)

        val result = repository.syncChaptersFromSource(entryForSync(), force = true)

        val appended = (result as ChapterSyncResult.Appended).appended
        assertEquals(1, appended.size)
        assertEquals(listOf(6), appended.map { it.durChapterIndex })
        assertEquals(chapter(6).contentRef, appended.single().contentRef)
    }

    @Test
    fun `本地目录为空时把远端整本写进去`() = runTest {
        // 加书架时目录抓取失败的书，靠这条自愈
        givenRemote(3)
        repository.addToShelf(
            BookShelfEntity(noteUrl = NOTE_URL, tag = SOURCE).apply {
                bookInfo = BookInfoEntity(noteUrl = NOTE_URL, name = "斗破苍穹")
            }
        )
        assertTrue(daos.chapter.storedValues().isEmpty())

        val result = repository.syncChaptersFromSource(entryForSync(), force = true)

        assertEquals(3, (result as ChapterSyncResult.Appended).appended.size)
        assertEquals(3, daos.chapter.getChaptersForBook(NOTE_URL).size)
    }

    @Test
    fun `追加行的归属取自入参条目`() = runTest {
        givenRemote(4)
        seedShelfWithChapters(localCount = 2)

        val appended = repository.syncChaptersFromSource(entryForSync(), force = true)
            .let { (it as ChapterSyncResult.Appended).appended }

        assertTrue(appended.all { it.noteUrl == NOTE_URL && it.tag == SOURCE })
    }
}

/**
 * 抓目录必然失败的 parser，用来锁「异常不吞成 UpToDate」。
 *
 * 不复用 [RecordingBookParser] 再想办法让它抛：那边是 final（同模块内共享的记录型替身），
 * 为一个抛异常的用例去改共享假件不值当。
 */
private class ThrowingBookParser(
    private val failure: Throwable,
) : BookParser {
    override suspend fun getChapterList(
        bookShelf: BookShelfEntity,
    ): WebChapterEntity<BookShelfEntity> = throw failure

    override suspend fun searchBook(content: String, page: Int): List<SearchBookEntity> =
        unsupported("searchBook")

    override suspend fun getBookInfo(bookShelf: BookShelfEntity): BookShelfEntity =
        unsupported("getBookInfo")

    override suspend fun getKindBook(url: String, page: Int): List<SearchBookEntity> =
        unsupported("getKindBook")

    override suspend fun fetchLibraryData(): LibraryEntity = unsupported("fetchLibraryData")

    private fun unsupported(who: String): Nothing =
        throw UnsupportedOperationException("ThrowingBookParser 未实现 $who")
}
