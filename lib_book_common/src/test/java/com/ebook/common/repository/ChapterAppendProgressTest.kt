package com.ebook.common.repository

import android.content.Context
import androidx.room3.Room
import androidx.room3.withWriteTransaction
import androidx.test.core.app.ApplicationProvider
import com.ebook.common.analyze.local.BookFormat
import com.ebook.common.analyze.local.ChapterReader
import com.ebook.common.analyze.source.FakeBookSourceManager
import com.ebook.common.analyze.source.RecordingBookParser
import com.ebook.common.store.BookStore
import com.ebook.common.store.ChapterContentCache
import com.ebook.common.store.WriteTransactionRunner
import com.ebook.db.AppDatabase
import com.ebook.db.entity.BookInfoEntity
import com.ebook.db.entity.BookShelfEntity
import com.ebook.db.entity.ChapterListEntity
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 「追更 → 跳最新章节 → 回书架」这条用户序列的端到端回归（Robolectric + Room 内存库）。
 *
 * 锁的症状是书架条目「读至：」后面空白、再点进去提示章节加载失败。两处读的是同一个量
 * （`chapterList.getOrNull(durChapter)`，见 BookShelfPage 与 ReadBookViewModel.getChapter），
 * 所以症状等价于一句话：**`durChapter` 落在了书架目录的行数之外**。
 *
 * 两层各锁一件事：
 * - **根治**：页面与阅读器的目录一律回读 `getStoredChapters`（库里那份），追加走
 *   `appendRemoteChapters`。于是「内存那份比库里长」这个前提不再成立，跳最新的落点本就在库内
 *   —— 相关用例专门断言 `durChapter` **没被钳动**：钳制一出手就说明分叉复发。
 * - **防线**：`saveProgress` 仍会把越界落点钳回库内行数（见该方法 KDoc）。它是第二道，
 *   兜住任何新出现的「调用方自己拼目录」，不让它再长成用户可见的数据损坏。
 *
 * 为什么必须真 Room 而不是手写假 DAO：这条链的成败正落在 SQL 语义上——
 * `chapter_list` 主键是自然键 `content_ref`，`insertAll` 是整行 REPLACE（先删后插），
 * 追加的行会不会吃掉既有行、`@Relation` 回带几行，假件都不建模。
 * 编排层（时间戳、事件、序号从 max+1 起）由 [BookRepositoryChapterSyncTest] 用假件锁，
 * 两边不重复。
 *
 * 阅读器与详情页那一侧在本文件里按生产的写法逐字镜像（`BookReadViewModel.appendChaptersIfAny`
 * 与 `BookDetailViewModel.getBookShelfInfo` 各自的「落库 → 回读库里那份」+ 目录抽屉按列表位置跳章
 * + 退出时 `saveProgress`）：跨模块拿不到那些 ViewModel，而这几步的口径正是本用例要钉的东西。
 * 详情页侧另有一条真跑 VM 的锁，见 module_book 的 `BookDetailViewModelSourceTest`。
 * 改动这些 ViewModel 时必须同步这里。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChapterAppendProgressTest {

    private companion object {
        const val SOURCE = "https://a.example"

        // 不带 scheme：noteUrl 同时是 BookStore 的目录名，带 https:// 在 Windows 上会被当盘符
        const val NOTE_URL = "a.example/book/1.html"

        fun chapter(index: Int) = ChapterListEntity(
            noteUrl = NOTE_URL,
            durChapterIndex = index,
            contentRef = "$SOURCE/chapter/${index + 1}.html",
            durChapterName = "第${index + 1}章",
            tag = SOURCE,
        )
    }

    private lateinit var db: AppDatabase
    private lateinit var manager: FakeBookSourceManager
    private lateinit var repository: BookRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        manager = FakeBookSourceManager()
        repository = BookRepository(
            bookShelfDao = db.bookShelfDao(),
            bookInfoDao = db.bookInfoDao(),
            chapterListDao = db.chapterListDao(),
            bookGroupDao = db.bookGroupDao(),
            downloadChapterDao = db.downloadChapterDao(),
            chapterReaders = emptyMap<BookFormat, ChapterReader>(),
            bookSourceManager = manager,
            bookStore = BookStore(File(System.getProperty("java.io.tmpdir"), "ebook-append-progress")),
            contentCache = ChapterContentCache(capacity = 3),
            // 与生产 TransactionModule 同一实现：假事务不回滚会把「半笔写」测成正常
            transactions = object : WriteTransactionRunner {
                override suspend fun <R> run(block: suspend () -> R): R =
                    db.withWriteTransaction { block() }
            },
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    /** 按本地目录播种一本网络书（[indices] 即 `dur_chapter_index`，可含洞） */
    private suspend fun seedLocal(indices: List<Int>) {
        repository.addToShelf(
            BookShelfEntity(noteUrl = NOTE_URL, tag = SOURCE).apply {
                bookInfo = BookInfoEntity(noteUrl = NOTE_URL, name = "斗破苍穹")
                chapterList = indices.map { chapter(it) }
            }
        )
    }

    /** 让这本书的归属源解出 [remoteCount] 章（parser 按位置写序号，即连续 0 until n） */
    private fun givenRemote(remoteCount: Int) {
        val shelf = BookShelfEntity(noteUrl = NOTE_URL, tag = SOURCE).apply {
            bookInfo = BookInfoEntity(noteUrl = NOTE_URL, name = "斗破苍穹")
        }
        manager.putParser(
            SOURCE,
            RecordingBookParser(
                ownedSourceUrl = SOURCE,
                bookInfoResult = shelf,
                chapterListData = shelf.copy(chapterList = (0 until remoteCount).map { chapter(it) }),
            ),
        )
    }

    /** 用户在阅读器里「跳到最新章节」并退出：写回的是**内存列表里的位置** */
    private suspend fun jumpToTailAndSave(inMemoryChapterList: List<ChapterListEntity>): BookShelfEntity {
        val entry = repository.getAllBooksWithDetails().single()
        val readerShelf = entry.copy(chapterList = inMemoryChapterList)
        readerShelf.durChapter = inMemoryChapterList.lastIndex
        repository.saveProgress(readerShelf)
        return repository.getAllBooksWithDetails().single()
    }

    /** 书架条目上「读至」那一章（null 即用户看到的空白 + 点进去加载失败） */
    private fun BookShelfEntity.readToChapter() = chapterList.getOrNull(durChapter)

    @Test
    fun `连续目录追更后跳最新，书架仍认得那一章`() = runBlocking {
        seedLocal(indices = listOf(0, 1, 2))
        givenRemote(remoteCount = 5)

        val entry = repository.getAllBooksWithDetails().single()
        val appended = (repository.syncChaptersFromSource(entry, force = true) as ChapterSyncResult.Appended)
            .appended
        val shelf = jumpToTailAndSave(entry.chapterList + appended)

        assertEquals("库里应正好是 5 行", 5, shelf.chapterList.size)
        assertNotNull(
            "durChapter=${shelf.durChapter} 落在 ${shelf.chapterList.size} 行之外",
            shelf.readToChapter(),
        )
        assertEquals("第5章", shelf.readToChapter()?.durChapterName)
    }

    @Test
    fun `本地有洞的书追更后跳最新，书架仍认得那一章`() = runBlocking {
        // 用户删过第 3、4 章：本地 4 行、最大序号 5 —— size 与 max+1 不等，两套口径在此分叉
        seedLocal(indices = listOf(0, 1, 4, 5))
        givenRemote(remoteCount = 7)

        val entry = repository.getAllBooksWithDetails().single()
        val appended = (repository.syncChaptersFromSource(entry, force = true) as ChapterSyncResult.Appended)
            .appended
        val shelf = jumpToTailAndSave(entry.chapterList + appended)

        assertEquals(
            "内存目录行数必须与库里行数相等，否则阅读器写回的落点在书架侧越界",
            shelf.chapterList.size,
            (entry.chapterList + appended).size,
        )
        assertNotNull(
            "durChapter=${shelf.durChapter} 落在 ${shelf.chapterList.size} 行之外",
            shelf.readToChapter(),
        )
        assertEquals("第7章", shelf.readToChapter()?.durChapterName)
    }

    @Test
    fun `追更后停在原章不动，读至仍是原来那一章`() = runBlocking {
        // 纯追加不该改任何人已有的落点：这条是上面两条的对照组
        seedLocal(indices = listOf(0, 1, 4, 5))
        givenRemote(remoteCount = 7)
        repository.getAllBooksWithDetails().single().let { before ->
            repository.saveProgress(before.copy(durChapter = 2))
        }

        val entry = repository.getAllBooksWithDetails().single()
        repository.syncChaptersFromSource(entry, force = true)

        val shelf = repository.getAllBooksWithDetails().single()
        assertEquals("追加不该动既有行，落点指向的章名也不该变", "第5章", shelf.readToChapter()?.durChapterName)
    }

    @Test
    fun `本地目录为空的书补全目录后跳最新，书架认得那一章`() = runBlocking {
        // 加书架时目录抓取失败的那本，靠追更自愈；自愈后跳最新同样要能读
        seedLocal(indices = emptyList())
        givenRemote(remoteCount = 3)

        val entry = repository.getAllBooksWithDetails().single()
        assertTrue(entry.chapterList.isEmpty())
        val appended = (repository.syncChaptersFromSource(entry, force = true) as ChapterSyncResult.Appended)
            .appended
        val shelf = jumpToTailAndSave(entry.chapterList + appended)

        assertNotNull("durChapter=${shelf.durChapter} / ${shelf.chapterList.size} 行", shelf.readToChapter())
        assertEquals("第3章", shelf.readToChapter()?.durChapterName)
    }

    /**
     * 取「这本书在源上的目录」——与 `BookDetailViewModel.fetchChapterList` 同一条调用
     * （经归属源拿 parser，再 `getChapterList(...).data`）。
     *
     * 详情页把它直接放进 state 的 `bookShelf.chapterList`，而**没有任何一步把它的行数落到
     * `chapter_list` 表**：库内行数由 [BookRepository.syncChaptersFromSource] 的 diff 决定
     * （分叉/限频/失败三种结局都不追加）。于是「内存目录比库里长」是一个可达状态。
     */
    private suspend fun remoteToc(): List<ChapterListEntity> {
        val entry = repository.getAllBooksWithDetails().single()
        val parser = manager.getParserFor(SOURCE) ?: error("假件里没有这条源")
        return parser.getChapterList(entry).data.chapterList
    }

    @Test
    fun `详情页把抓到的目录落库后，页面那份与库里那份是同一份`() = runBlocking {
        // 生产的写法（BookDetailViewModel.getBookShelfInfo）：inShelf 时先把这份远端目录
        // 按纯追加落库，再把「库里那份」放进状态并交给阅读器。
        seedLocal(indices = listOf(0, 1, 2))
        givenRemote(remoteCount = 5)

        val entry = repository.getAllBooksWithDetails().single()
        val remote = remoteToc()
        assertEquals("前提：库里 3 行、源上 5 章", 3, entry.chapterList.size)
        assertTrue(repository.appendRemoteChapters(entry, remote) is ChapterSyncResult.Appended)

        val stored = repository.getStoredChapters(NOTE_URL)
        assertEquals("落库后库里正是 5 行", 5, stored.size)
        assertEquals(
            "落库后的目录不许有重复定位符",
            stored.size,
            stored.map { it.contentRef }.distinct().size,
        )
        assertEquals("页面用的那份必须等于库里那份", stored.map { it.contentRef }, remote.map { it.contentRef })

        val shelf = jumpToTailAndSave(stored)
        assertEquals("落点在库里，不该被 saveProgress 钳动", 4, shelf.durChapter)
        assertEquals("第5章", shelf.readToChapter()?.durChapterName)
    }

    @Test
    fun `阅读器末章追更后回读库里那份，跳最新不再需要钳制`() = runBlocking {
        // 生产的写法（BookReadViewModel.appendChaptersIfAny）：追加成功后目录回读库里那份，
        // 不再用「传进来的那份 + appended」拼基数。
        seedLocal(indices = listOf(0, 1, 2))
        givenRemote(remoteCount = 5)

        val entry = repository.getAllBooksWithDetails().single()
        val appended = (repository.syncChaptersFromSource(entry, force = true)
            as ChapterSyncResult.Appended).appended
        val inMemory = repository.getStoredChapters(NOTE_URL)

        assertEquals("追加的那几行不该在内存里长出两遍", 5, inMemory.size)
        assertEquals(
            "内存与库必须同一份目录",
            inMemory.map { it.contentRef },
            repository.getAllBooksWithDetails().single().chapterList.map { it.contentRef },
        )
        assertEquals("appended 就是新增的那两行", 2, appended.size)

        val shelf = jumpToTailAndSave(inMemory)
        assertEquals("落点本就在库内，钳制不该出手", 4, shelf.durChapter)
        assertEquals("第5章", shelf.readToChapter()?.durChapterName)
    }

    @Test
    fun `同一份远端目录交两次只落一遍`() = runBlocking {
        // 判定基数是「库里那份」而不是「上一次交进来的那份」，所以详情页反复进入、
        // 或落库后重新进一次，都不会把同一批新章再追加一遍。
        seedLocal(indices = listOf(0, 1, 2))
        givenRemote(remoteCount = 5)
        val remote = remoteToc()

        val entry = repository.getAllBooksWithDetails().single()
        assertTrue(repository.appendRemoteChapters(entry, remote) is ChapterSyncResult.Appended)
        val afterFirst = repository.getStoredChapters(NOTE_URL)

        assertTrue(
            repository.appendRemoteChapters(entry, remote) is ChapterSyncResult.UpToDate,
        )
        assertEquals(
            "第二次不该动任何行",
            afterFirst.map { it.durChapterIndex to it.contentRef },
            repository.getStoredChapters(NOTE_URL).map { it.durChapterIndex to it.contentRef },
        )
    }

    @Test
    fun `远端重复挂了同一定位符时整笔放弃，本地一行都不动`() = runBlocking {
        // content_ref 是整表主键、insertAll 是整行 REPLACE：撞键不报错，只会把既有那一行搬到表尾
        // （序号静默错位）并让「本地 + tail」比库里多出一行。这种自相矛盾的清单整体不可信，
        // 守卫在 ChapterTocDiff 就把整笔判成分叉 —— parser 侧本有 seenRefs 去重，
        // 这里注入未去重的行，锁住仓库这道防线。
        seedLocal(indices = listOf(0, 1, 2))
        val tocCarrier = BookShelfEntity(noteUrl = NOTE_URL, tag = SOURCE).apply {
            bookInfo = BookInfoEntity(noteUrl = NOTE_URL, name = "斗破苍穹")
        }
        manager.putParser(
            SOURCE,
            RecordingBookParser(
                ownedSourceUrl = SOURCE,
                bookInfoResult = tocCarrier,
                chapterListData = tocCarrier.copy(
                    // 远端第 5 项与本地第 3 章同定位符（站点把同一章在目录里写了两遍）
                    chapterList = listOf(
                        chapter(0), chapter(1), chapter(2), chapter(3),
                        chapter(2).copy(durChapterIndex = 4),
                    ),
                ),
            ),
        )

        val entry = repository.getAllBooksWithDetails().single()
        val result = repository.syncChaptersFromSource(entry, force = true)
        val stored = db.chapterListDao().getChaptersForBook(NOTE_URL)

        assertTrue("实际 $result", result is ChapterSyncResult.Diverged)
        assertEquals("一行都不该被写进库", listOf(0, 1, 2), stored.map { it.durChapterIndex })
        assertEquals(
            "既有章的先后顺序必须逐字不变（撞键的旧写法会把第3章搬到表尾）",
            listOf("第1章", "第2章", "第3章"),
            stored.map { it.durChapterName },
        )

        // 用户仍按本地这份目录读到末章再退出：落点本就在库内，钳制不该出手
        val shelf = jumpToTailAndSave(stored)
        assertEquals(2, shelf.durChapter)
        assertNotNull("书架「读至」不该空白", shelf.readToChapter())
    }
}
