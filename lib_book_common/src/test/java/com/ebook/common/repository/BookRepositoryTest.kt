package com.ebook.common.repository

import com.ebook.common.analyze.local.BookFormat
import com.ebook.common.analyze.local.BookLocation
import com.ebook.common.analyze.local.ChapterContent
import com.ebook.common.analyze.local.ChapterEntry
import com.ebook.common.analyze.local.ChapterReader
import com.ebook.common.analyze.source.FakeBookSourceManager
import com.ebook.common.domain.CommentKey
import com.ebook.common.store.BookStore
import com.ebook.common.store.ChapterContentCache
import com.ebook.common.store.WriteTransactionRunner
import com.ebook.db.dao.BookShelfDao
import com.ebook.db.entity.BookGroupEntity
import com.ebook.db.entity.BookInfoEntity
import com.ebook.db.entity.BookShelfEntity
import com.ebook.db.entity.BookShelfFullInfo
import com.ebook.db.entity.ChapterListEntity
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * [BookRepository] 的单元测试（纯 JVM，手写 Fake DAO，不依赖 Robolectric/Room）。
 *
 * 覆盖 `docs/test-coverage-todo.md` 点名的行为：
 * - 书架事件总线：[BookRepository.bookShelfEvents] 的 Added/Removed/ProgressUpdated 发射（见 ADR-0004）
 * - 级联写入/清理：[BookRepository.addToShelf] / [BookRepository.removeFromShelf]
 * - 关联查询与孤立记录清理：[BookRepository.getAllBooksWithDetails] / [BookRepository.observeBookShelf]
 * - 评论聚合键查询：[BookRepository.getCommentKeysForBook]
 * - 导入合并/拆分/切主键：[BookRepository.absorbGroupKeys] / [BookRepository.splitBook] / [BookRepository.updateMatchMeta]
 * - 补章（前缀判定与索引洞）：[BookRepository.mergeTailChapters]
 * - 内容仓库对账：[BookRepository.reconcileContentStore]
 * - 主键与分组行查询：[BookRepository.getPrimaryKeyForBook] / [BookRepository.getBookGroupRows]
 *
 * 说明：[BookRepository] 继承 lib_common 的 `BaseModel`；本测试直接实例化，
 * 若 `BaseModel` 引入 Android 框架依赖会导致初始化失败——该风险由本测试类的可运行性本身兜底验证。
 */
class BookRepositoryTest {

    /** 补章用例会真的写章文件，根目录按用例隔离，避免用例之间互相看见 */
    @get:Rule
    val booksRoot = TemporaryFolder()

    private lateinit var daos: FakeDaos
    private lateinit var store: BookStore
    private lateinit var repository: BookRepository

    @Before
    fun setUp() {
        daos = FakeDaos()
        store = BookStore(booksRoot.root)
        val readers: Map<BookFormat, ChapterReader> = mapOf(
            BookFormat.TXT to FakeChapterReader(),
            BookFormat.NETWORK to FakeChapterReader(),
        )
        repository = BookRepository(
            bookShelfDao = daos.shelf,
            bookInfoDao = daos.info,
            chapterListDao = daos.chapter,
            bookGroupDao = daos.group,
            downloadChapterDao = daos.download,
            chapterReaders = readers,
            // 本测试类的用例不碰换源，空清单的假书源管理器即可（需要 parser 的见 BookRepositorySwitchSourceTest）
            sourceResolver = FakeBookSourceManager(),
            bookStore = store,
            contentCache = ChapterContentCache(),
            transactions = DirectTransactionRunner,
        )
    }

    // ===== 级联写入 / 清理 =====

    @Test
    fun `addToShelf 级联写入并回填 noteUrl 关联`() : Unit = runTest {
        val shelf = BookShelfEntity(noteUrl = "http://book").apply {
            bookInfo = BookInfoEntity(name = "斗破苍穹") // noteUrl 故意留空，验证回填
            chapterList = listOf(
                ChapterListEntity(contentRef = "http://a/1", durChapterIndex = 0),
                ChapterListEntity(contentRef = "http://a/2", durChapterIndex = 1),
            )
        }

        repository.addToShelf(shelf)

        // bookInfo 落库且 noteUrl 被回填为书架 noteUrl
        assertEquals("斗破苍穹", daos.info.getBookInfoByUrl("http://book")?.name)
        // 书架落库
        assertEquals("http://book", daos.shelf.getBookByUrl("http://book")?.noteUrl)
        // 章节落库且每章 noteUrl 被回填
        val chapters = daos.chapter.getChaptersForBook("http://book")
        assertEquals(2, chapters.size)
        assertTrue(chapters.all { it.noteUrl == "http://book" })
        // M2：book_group 关联行写入，commentKey 由书名算出
        val groupRows = daos.group.storedValues()
        assertEquals(1, groupRows.size)
        assertEquals(CommentKey.compute("斗破苍穹", ""), groupRows[0].commentKey)
        assertEquals("http://book", groupRows[0].noteUrl)
    }

    /**
     * 「一个书架条目」在四张表里的总行数，供事务边界取样。
     *
     * 取的是假件内存表的即期行数（`storedValues()` 是同步的），因此能在 block 进入与返回的
     * 那一刻各读一次——换成 suspend 查询就得排队到事务之外，测不到窗口内的事实。
     */
    private fun entryRowCount(): Int =
        daos.info.storedValues().size + daos.shelf.storedValues().size +
            daos.chapter.storedValues().size + daos.group.storedValues().size

    /**
     * 加书架的原子性：一个条目的四张表收进**一次**写事务，且 `Added` 事件在事务返回后才发。
     *
     * 事务外顺序写四张表时，读方能在窗口里看到「book_info、book_shelf 都在，chapter_list 还是空的」
     * 这种半截条目。`getAllBooksWithDetails` 正是书架的读路径，于是「刚加的那本 100 章还是 0 章」
     * 取决于那一瞬间有没有人路过 —— `module_book` 的 `ReadingProgressFlowTest` 里
     * 「读至 第100章」偶发为 null，就是轮询者挤进了这个窗口（不是被落点钳制抹掉的）。
     *
     * 断言取样的是 block **进入时**与**返回时**的库内行数：只数「事务跑了几次」证明不了
     * 写落在事务**里面**，而落在外面才是这次要根治的东西。
     */
    @Test
    fun `addToShelf 把整个条目收进一次写事务并在提交后才发事件`() : Unit = runTest {
        val events = mutableListOf<BookShelfEvent>()
        var runs = 0
        var rowsAtEntry = -1
        var rowsAtExit = -1
        var eventsAtExit = -1
        val txRepository = BookRepository(
            bookShelfDao = daos.shelf,
            bookInfoDao = daos.info,
            chapterListDao = daos.chapter,
            bookGroupDao = daos.group,
            downloadChapterDao = daos.download,
            chapterReaders = mapOf(BookFormat.TXT to FakeChapterReader()),
            sourceResolver = FakeBookSourceManager(),
            bookStore = store,
            contentCache = ChapterContentCache(),
            transactions = object : WriteTransactionRunner {
                override suspend fun <R> run(block: suspend () -> R): R {
                    runs++
                    rowsAtEntry = entryRowCount()
                    return block().also {
                        rowsAtExit = entryRowCount()
                        eventsAtExit = events.size
                    }
                }
            },
        )
        backgroundScope.launch { txRepository.bookShelfEvents.toList(events) }
        runCurrent() // SharedFlow 无 replay，先让订阅者就位

        txRepository.addToShelf(
            BookShelfEntity(noteUrl = "http://book").apply {
                bookInfo = BookInfoEntity(name = "斗破苍穹")
                chapterList = listOf(
                    ChapterListEntity(contentRef = "http://a/1", durChapterIndex = 0),
                    ChapterListEntity(contentRef = "http://a/2", durChapterIndex = 1),
                )
            }
        )
        runCurrent()

        assertEquals("整个条目只该提交一次写事务", 1, runs)
        assertEquals("事务开始时库内不得有半截条目可被读到", 0, rowsAtEntry)
        // 1 book_info + 1 book_shelf + 2 chapter_list + 1 book_group
        assertEquals("事务返回时整个条目已就位", 5, rowsAtExit)
        assertEquals("未提交的写不是事实：事务内不该已经广播 Added", 0, eventsAtExit)
        assertTrue("提交后才发事件", events.any { it is BookShelfEvent.Added })
    }

    @Test
    fun `removeFromShelf 级联清理章节与关联记录`() : Unit = runTest {
        val shelf = BookShelfEntity(noteUrl = "http://book").apply {
            bookInfo = BookInfoEntity(name = "待删除")
            chapterList = listOf(
                ChapterListEntity(contentRef = "http://a/1"),
                ChapterListEntity(contentRef = "http://a/2"),
            )
        }
        repository.addToShelf(shelf)

        repository.removeFromShelf(shelf)

        assertNull(daos.shelf.getBookByUrl("http://book"))
        assertNull(daos.info.getBookInfoByUrl("http://book"))
        assertTrue(daos.chapter.getChaptersForBook("http://book").isEmpty())
        // M2：book_group 行随书删除
        assertTrue(daos.group.storedValues().isEmpty())
    }

    @Test
    fun `getCommentKeysForBook 返回 addToShelf 写入的评论聚合键`() : Unit = runTest {
        val shelf = BookShelfEntity(noteUrl = "http://book").apply {
            bookInfo = BookInfoEntity(name = "斗破苍穹", author = "天蚕土豆")
        }
        repository.addToShelf(shelf)

        val keys = repository.getCommentKeysForBook("http://book")

        assertEquals(1, keys.size)
        assertEquals(CommentKey.compute("斗破苍穹", "天蚕土豆"), keys[0])
    }

    @Test
    fun `getCommentKeysForBook 无匹配行时返回空列表`() : Unit = runTest {
        val keys = repository.getCommentKeysForBook("http://nonexistent")
        assertTrue(keys.isEmpty())
    }

    // ===== 事件总线（ADR-0004：SharedFlow 替代 RxBus） =====

    @Test
    fun `addToShelf 发射 Added 事件`() : Unit = runTest {
        val events = mutableListOf<BookShelfEvent>()
        backgroundScope.launch { repository.bookShelfEvents.toList(events) }
        runCurrent() // 先让订阅者完成订阅（SharedFlow 无 replay，晚订阅会丢事件）

        repository.addToShelf(BookShelfEntity(noteUrl = "http://book"))
        runCurrent()

        assertTrue(events.any { it is BookShelfEvent.Added })
    }

    @Test
    fun `removeFromShelf 发射 Removed 事件`() : Unit = runTest {
        val shelf = BookShelfEntity(noteUrl = "http://book")
        repository.addToShelf(shelf)

        val events = mutableListOf<BookShelfEvent>()
        backgroundScope.launch { repository.bookShelfEvents.toList(events) }
        runCurrent()

        repository.removeFromShelf(shelf)
        runCurrent()

        assertTrue(events.any { it is BookShelfEvent.Removed })
    }

    @Test
    fun `saveProgress 刷新最后阅读时间并发射 ProgressUpdated 事件`() : Unit = runTest {
        val shelf = BookShelfEntity(noteUrl = "http://book", finalDate = 0L)
        repository.addToShelf(shelf)

        val events = mutableListOf<BookShelfEvent>()
        backgroundScope.launch { repository.bookShelfEvents.toList(events) }
        runCurrent()

        repository.saveProgress(shelf)
        runCurrent()

        assertTrue(shelf.finalDate > 0L)
        assertEquals(shelf.finalDate, daos.shelf.getBookByUrl("http://book")?.finalDate)
        assertTrue(events.any { it is BookShelfEvent.ProgressUpdated })
    }

    /**
     * 书架行在、`chapter_list` 为零时不钳：零行没有「最后一行」，钳成 0 会把
     * 「加书架时目录没抓到、之后靠追更补齐」的进度抹回第一章。这类行仍可更新进度，
     * 等章节补齐后再按行数钳。
     */
    @Test
    fun `saveProgress 在书架行存在但章节为零时保留原进度`() : Unit = runTest {
        val shelf = BookShelfEntity(noteUrl = "http://no-chapters", durChapter = 42)
        daos.shelf.insert(shelf)

        repository.saveProgress(shelf)

        assertEquals(
            "零章时没有可钳的上界，进度必须原样保留",
            42,
            daos.shelf.getBookByUrl(shelf.noteUrl)?.durChapter,
        )
    }

    /**
     * 未加书架先读时，进度不能写出「有 book_shelf、没有 book_info」的半行：
     * 书架行是「在架」的证据，半行会被 `BookShelfManager`/详情页当成已加书架，
     * 随后又被 `getAllBooksWithDetails` 当孤儿清掉，留下 `chapter_list` 孤儿。
     * 进度的持久化入口只更新已在架的行；用户点加书架时由 `ReaderSession.addToShelf`
     * 用实时位置整笔写入。
     */
    @Test
    fun `saveProgress 在 book_shelf 没有对应行时不写半行`() : Unit = runTest {
        val notOnShelf = BookShelfEntity(noteUrl = "http://not-on-shelf", durChapter = 3)
        val events = mutableListOf<BookShelfEvent>()
        backgroundScope.launch { repository.bookShelfEvents.toList(events) }
        runCurrent()

        repository.saveProgress(notOnShelf)
        runCurrent()

        assertNull(
            "未加书架先读不进库：半行会被当成在架证据，随后只剩孤儿章节",
            daos.shelf.getBookByUrl(notOnShelf.noteUrl),
        )
        assertTrue(
            "没有落库就不该发 ProgressUpdated",
            events.none { it is BookShelfEvent.ProgressUpdated },
        )
    }

    /**
     * 「先查后写」之间仍可能被并发移出书架：写回必须用 `@Update`（主键不匹配即 no-op），
     * 不能是 REPLACE——后者会把刚删掉的行重新插一条「有 `book_shelf`、无 `book_info`」的半行。
     * 这里让假 DAO 在存在性检查那一刻删行，锁住写回操作本身不会 upsert。
     */
    @Test
    fun `saveProgress 在检查后行被删时不重建半行`() : Unit = runTest {
        val shelf = BookShelfEntity(noteUrl = "http://deleted-after-check", durChapter = 3)
        daos.shelf.insert(shelf)
        val deletingShelfDao = object : BookShelfDao by daos.shelf {
            override suspend fun getBookByUrl(noteUrl: String): BookShelfEntity? {
                val row = daos.shelf.getBookByUrl(noteUrl)
                // 模拟检查与写回之间的并发移出：本次调用拿到的快照仍然有效
                daos.shelf.deleteByUrl(noteUrl)
                return row
            }
        }
        val racingRepository = BookRepository(
            bookShelfDao = deletingShelfDao,
            bookInfoDao = daos.info,
            chapterListDao = daos.chapter,
            bookGroupDao = daos.group,
            downloadChapterDao = daos.download,
            chapterReaders = mapOf(BookFormat.NETWORK to FakeChapterReader()),
            sourceResolver = FakeBookSourceManager(),
            bookStore = store,
            contentCache = ChapterContentCache(),
            transactions = DirectTransactionRunner,
        )
        val events = mutableListOf<BookShelfEvent>()
        backgroundScope.launch { racingRepository.bookShelfEvents.toList(events) }
        runCurrent()

        racingRepository.saveProgress(shelf)
        runCurrent()

        assertNull(
            "写回必须是不存在的行就 no-op；REPLACE 会把它重新插成半行",
            daos.shelf.getBookByUrl(shelf.noteUrl),
        )
        assertTrue(
            "行已不在，update 返回 0，不能再声称进度更新成功",
            events.none { it is BookShelfEvent.ProgressUpdated },
        )
    }

    // ===== 关联查询与孤立记录清理 =====

    @Test
    fun `getAllBooksWithDetails 过滤并清理 info 为空的孤立记录，且章节按序号排序`() : Unit = runTest {
        val okShelf = BookShelfEntity(noteUrl = "http://ok")
        val orphanShelf = BookShelfEntity(noteUrl = "http://orphan")
        // 正常书：章节故意乱序（rowid 错序回归），验证按 durChapterIndex 显式排序
        daos.shelf.fullInfoSeed.add(
            BookShelfFullInfo(
                bookShelf = okShelf,
                info = BookInfoEntity(noteUrl = "http://ok", name = "正常书"),
                chapters = listOf(
                    ChapterListEntity(noteUrl = "http://ok", contentRef = "c2", durChapterIndex = 2),
                    ChapterListEntity(noteUrl = "http://ok", contentRef = "c0", durChapterIndex = 0),
                    ChapterListEntity(noteUrl = "http://ok", contentRef = "c1", durChapterIndex = 1),
                ),
            )
        )
        // 孤立书：info 为 null，应被过滤并触发 deleteByUrl 清理
        daos.shelf.fullInfoSeed.add(BookShelfFullInfo(bookShelf = orphanShelf, info = null, chapters = emptyList()))

        val result = repository.getAllBooksWithDetails()

        assertEquals(1, result.size)
        assertEquals("http://ok", result[0].noteUrl)
        assertEquals(listOf("c0", "c1", "c2"), result[0].chapterList.map { it.contentRef })
        assertTrue(orphanShelf.noteUrl in daos.shelf.deletedUrls)
    }

    /**
     * 孤立条目的清理必须删四表，而不是只删 `book_shelf`。
     *
     * 库里没有外键级联；只删书架行会把 `chapter_list`/`book_info`/`book_group`
     * 留成永久孤儿（`BookRepository` 的换源 KDoc 已承认反向孤儿没有清理路径）。
     * 这个用例同时守住「半行进库后的回收」和「历史遗留孤立数据」两个来源。
     */
    @Test
    fun `getAllBooksWithDetails 清理孤立条目时连章节与关联行一起删`() : Unit = runTest {
        val orphan = BookShelfEntity(noteUrl = "http://orphan-all-tables")
        daos.shelf.fullInfoSeed.add(BookShelfFullInfo(bookShelf = orphan, info = null, chapters = emptyList()))
        daos.chapter.insertAll(
            listOf(ChapterListEntity(noteUrl = orphan.noteUrl, contentRef = "orphan-c1", durChapterIndex = 0))
        )
        daos.group.insert(
            BookGroupEntity(
                commentKey = CommentKey.compute("孤儿书", ""),
                noteUrl = orphan.noteUrl,
                isPrimary = true,
            )
        )

        repository.getAllBooksWithDetails()

        assertTrue(
            "章节行必须随孤立条目一起清掉，否则永久躺在库里",
            daos.chapter.getChaptersForBook(orphan.noteUrl).isEmpty(),
        )
        assertTrue(
            "关联行同理",
            daos.group.getAllForNoteUrl(orphan.noteUrl).isEmpty(),
        )
        assertTrue(orphan.noteUrl in daos.shelf.deletedUrls)
    }

    /**
     * 对账的读面判它孤立之后、删除之前，加书架可能刚把 `book_info` 补齐：清理必须在事务内
     * 复核仍是孤立，否则会把刚落库的整条目连书带目录一起删掉。这里用「读面说孤立、库里已有
     * `book_info`」复现这个时间窗，不靠时序。
     */
    @Test
    fun `getAllBooksWithDetails 不删刚补齐 book_info 的条目`() : Unit = runTest {
        val raced = BookShelfEntity(noteUrl = "http://raced-in")
        // 读面（fullInfoSeed）仍按孤立返回，但库里这一刻已经有了 book_info——竞态收口后的现场
        daos.shelf.fullInfoSeed.add(BookShelfFullInfo(bookShelf = raced, info = null, chapters = emptyList()))
        daos.info.insert(BookInfoEntity(noteUrl = raced.noteUrl, name = "斗破苍穹"))
        daos.chapter.insertAll(
            listOf(ChapterListEntity(noteUrl = raced.noteUrl, contentRef = "raced-c1", durChapterIndex = 0))
        )

        repository.getAllBooksWithDetails()

        assertTrue(
            "book_info 已在的条目不是孤立条目，章节行不能被清掉",
            daos.chapter.getChaptersForBook(raced.noteUrl).isNotEmpty(),
        )
        assertTrue(
            "书架行同理，整条目要留在库里",
            raced.noteUrl !in daos.shelf.deletedUrls,
        )
    }

    @Test
    fun `observeBookShelf 过滤 info 为空的条目并填充 bookInfo`() : Unit = runTest {
        val shelf = BookShelfEntity(noteUrl = "http://ok")
        daos.shelf.fullInfoFlow.value = listOf(
            BookShelfFullInfo(shelf, BookInfoEntity(noteUrl = "http://ok", name = "书名"), emptyList()),
            BookShelfFullInfo(BookShelfEntity(noteUrl = "http://orphan"), null, emptyList()),
        )

        val observed = mutableListOf<List<BookShelfEntity>>()
        backgroundScope.launch { repository.observeBookShelf().toList(observed) }
        runCurrent()

        val latest = observed.last()
        assertEquals(1, latest.size)
        assertEquals("书名", latest[0].bookInfo?.name)
    }

    // ===== M2：拆分/修键 =====

    @Test
    fun `absorbGroupKeys 吸收旧条目全部关联键含 secondary 且同名键不重复加行`() : Unit = runTest {
        val ownKey = CommentKey.compute("斗破苍穹", "天蚕土豆")
        val mergedKey = CommentKey.compute("斗破苍穹", "土豆")
        daos.group.insert(BookGroupEntity(ownKey, "old", isPrimary = true))
        daos.group.insert(BookGroupEntity(mergedKey, "old", isPrimary = false))
        daos.group.insert(BookGroupEntity(ownKey, "new", isPrimary = true))

        repository.absorbGroupKeys(targetNoteUrl = "new", sourceNoteUrl = "old")

        val newRows = repository.getBookGroupRows("new")
        assertEquals(setOf(ownKey, mergedKey), newRows.map { it.commentKey }.toSet())
        assertEquals("与主键同名的那行不该被加成第二行", 1, newRows.count { it.commentKey == ownKey })
        assertTrue(newRows.single { it.commentKey == mergedKey }.isPrimary.not())
        assertEquals("吸收不搬行：旧条目自己的行保持原样", 2, repository.getBookGroupRows("old").size)
    }

    @Test
    fun `mergeTailChapters 旧书是新书前缀时只补尾部多出的章`() : Unit = runTest {
        seedLocalBook("old", 0 to "第一章 起", 1 to "第二章 承")
        seedLocalBook("new", 0 to "第一章 起", 1 to "第二章 承", 2 to "第三章 转")

        val outcome = repository.mergeTailChapters(newNoteUrl = "new", oldNoteUrl = "old")

        assertTrue("应判为补章成功: $outcome", outcome is ImportMergeResult.Merged)
        assertEquals(1, (outcome as ImportMergeResult.Merged).appendedChapters)
        assertEquals(
            listOf("第一章 起", "第二章 承", "第三章 转"),
            daos.chapter.getChaptersForBook("old").map { it.durChapterName }
        )
        assertNull("新条目整本退场", daos.shelf.getBookByUrl("new"))
        assertTrue(daos.chapter.getChaptersForBook("new").isEmpty())
    }

    @Test
    fun `mergeTailChapters 章名序列分叉时既不补章也不删新条目`() : Unit = runTest {
        // 阿拉伯数字 vs 汉字：normalize 不折叠数字形态，视为两套切分规则，宁可不补
        seedLocalBook("old", 0 to "第1章 起", 1 to "第2章 承")
        seedLocalBook("new", 0 to "第一章 起", 1 to "第二章 承", 2 to "第三章 转")

        val outcome = repository.mergeTailChapters(newNoteUrl = "new", oldNoteUrl = "old")

        assertEquals(ImportMergeResult.Diverged, outcome)
        assertEquals(2, daos.chapter.getChaptersForBook("old").size)
        assertNotNull("新条目必须留着——放弃补章就等于两本共存", daos.shelf.getBookByUrl("new"))
    }

    @Test
    fun `mergeTailChapters 旧书索引有洞时接在末位之后不覆写既有章`() : Unit = runTest {
        seedLocalBook("old", 0 to "第一章", 1 to "第二章", 5 to "第六章")
        seedLocalBook("new", 0 to "第一章", 1 to "第二章", 5 to "第六章", 6 to "第七章")

        val outcome = repository.mergeTailChapters(newNoteUrl = "new", oldNoteUrl = "old")

        assertTrue("$outcome", outcome is ImportMergeResult.Merged)
        val rows = daos.chapter.getChaptersForBook("old")
        assertEquals(listOf(0, 1, 5, 6), rows.map { it.durChapterIndex })
        assertEquals("索引不撞车，content_ref 也不该重复", rows.size, rows.map { it.contentRef }.toSet().size)
    }

    @Test
    fun `mergeTailChapters 目标是网络书时拒绝补章`() : Unit = runTest {
        daos.shelf.insert(BookShelfEntity(noteUrl = "old", tag = "书源A"))
        seedLocalBook("new", 0 to "第一章", 1 to "第二章")

        assertEquals(ImportMergeResult.TargetNotLocal, repository.mergeTailChapters("new", "old"))
        assertNotNull("拒绝补章不得顺带把新导入的那本删掉", daos.shelf.getBookByUrl("new"))
    }

    /** 种一本本地书：书架行 + 按 (索引 to 章名) 播种章节行 */
    private suspend fun seedLocalBook(noteUrl: String, vararg chapters: Pair<Int, String>) {
        daos.shelf.insert(
            BookShelfEntity(
                noteUrl = noteUrl,
                tag = BookShelfEntity.LOCAL_TAG,
                bookFormat = BookFormat.TXT.name,
            )
        )
        daos.chapter.insertAll(
            chapters.map { (index, name) ->
                ChapterListEntity(
                    noteUrl = noteUrl,
                    durChapterIndex = index,
                    contentRef = store.chapterRef(noteUrl, index),
                    durChapterName = name,
                    tag = BookShelfEntity.LOCAL_TAG,
                )
            }
        )
    }

    @Test
    fun `reconcileContentStore 按书架活书集合回收无主目录与 tmp 残留`() : Unit = runTest {
        val liveId = "a".repeat(32)
        val orphanId = "b".repeat(32)
        daos.shelf.insert(BookShelfEntity(noteUrl = liveId))
        assertTrue(File(booksRoot.root, liveId).mkdirs())
        assertTrue(File(booksRoot.root, orphanId).mkdirs())
        assertTrue(File(booksRoot.root, "$orphanId.tmp").mkdirs())

        repository.reconcileContentStore()

        assertTrue("书架上有的书目录必须留着", File(booksRoot.root, liveId).exists())
        assertFalse("DB 已无书的目录要回收", File(booksRoot.root, orphanId).exists())
        assertFalse("导入中断留下的 .tmp 暂存目录要回收", File(booksRoot.root, "$orphanId.tmp").exists())
    }

    @Test
    fun `splitBook removes specific key row without affecting others`() : Unit = runTest {
        val shelf = BookShelfEntity(noteUrl = "http://book").apply {
            bookInfo = BookInfoEntity(name = "斗破苍穹")
        }
        repository.addToShelf(shelf)
        // 手动加一行 secondary
        val secondaryKey = CommentKey.compute("斗破苍穹", "未知作者")
        daos.group.insert(BookGroupEntity(secondaryKey, "http://book", isPrimary = false))

        repository.splitBook("http://book", secondaryKey)

        val rows = repository.getBookGroupRows("http://book")
        assertEquals(1, rows.size)
        assertEquals(CommentKey.compute("斗破苍穹", ""), rows[0].commentKey)
    }

    @Test
    fun `updateMatchMeta recalculates key and switches primary`() : Unit = runTest {
        val shelf = BookShelfEntity(noteUrl = "http://book").apply {
            bookInfo = BookInfoEntity(name = "斗破苍穹", author = "天蚕土豆")
        }
        repository.addToShelf(shelf)
        val oldKey = CommentKey.compute("斗破苍穹", "天蚕土豆")

        repository.updateMatchMeta("http://book", "斗破苍穹", "土豆")

        val rows = repository.getBookGroupRows("http://book")
        // 旧键保留（降级为非主键），新键成为主键
        assertEquals(2, rows.size)
        val newPrimary = rows.single { it.isPrimary }
        assertEquals(CommentKey.compute("斗破苍穹", "土豆"), newPrimary.commentKey)
        assertTrue(rows.any { it.commentKey == oldKey && !it.isPrimary })
    }

    @Test
    fun `updateMatchMeta 把元数据与切主键收进同一次写事务`() : Unit = runTest {
        var runs = 0
        val txRepository = BookRepository(
            bookShelfDao = daos.shelf,
            bookInfoDao = daos.info,
            chapterListDao = daos.chapter,
            bookGroupDao = daos.group,
            downloadChapterDao = daos.download,
            chapterReaders = mapOf(BookFormat.TXT to FakeChapterReader()),
            sourceResolver = FakeBookSourceManager(),
            bookStore = store,
            contentCache = ChapterContentCache(),
            transactions = ImmediateTransactionRunner { runs++ },
        )
        val shelf = BookShelfEntity(noteUrl = "http://book").apply {
            bookInfo = BookInfoEntity(name = "斗破苍穹", author = "天蚕土豆")
        }
        txRepository.addToShelf(shelf)

        // 只数「被测这一次调用」提交了几笔：加书架自己也是一笔写事务，混进总数就锁不住本用例
        val runsBeforeSwitchKey = runs
        txRepository.updateMatchMeta("http://book", "斗破苍穹", "土豆")

        // 键行写入 + matchName/matchAuthor 更新必须在同一个事务里：
        // 分开提交会留下「书名已改、主键仍是旧键」或「零行 primary」两种半截状态
        assertEquals(1, runs - runsBeforeSwitchKey)
    }

    @Test
    fun `updateMatchMeta 键等价时只落库元数据不动键行`() : Unit = runTest {
        val shelf = BookShelfEntity(noteUrl = "http://book").apply {
            bookInfo = BookInfoEntity(name = "斗破苍穹", author = "天蚕土豆")
        }
        repository.addToShelf(shelf)

        // 归一化会去掉书名号：《斗破苍穹》与 斗破苍穹 算出同一个键。键行不必动，
        // 但用户输入的写法必须落库——修键面板的回显来自这两列，不写回来「改了却显示旧值」
        // 看起来就像保存失败
        repository.updateMatchMeta("http://book", "《斗破苍穹》", "天蚕土豆")

        assertEquals("《斗破苍穹》", daos.shelf.getBookByUrl("http://book")?.matchName)
        assertEquals("天蚕土豆", daos.shelf.getBookByUrl("http://book")?.matchAuthor)
        val rows = repository.getBookGroupRows("http://book")
        assertEquals(1, rows.size)
        assertEquals(CommentKey.compute("斗破苍穹", "天蚕土豆"), rows.single().commentKey)
        assertTrue(rows.single().isPrimary)
    }

    @Test
    fun `getPrimaryKeyForBook 返回主键行而非并集首元素`() : Unit = runTest {
        val oldKey = CommentKey.compute("斗破苍穹", "天蚕土豆")
        val newKey = CommentKey.compute("斗破苍穹", "土豆")
        // 修键后的真实数据形状：旧键行先落库（仍是列表首元素），新主键行后插入
        daos.group.insert(BookGroupEntity(oldKey, "http://book", isPrimary = false))
        daos.group.insert(BookGroupEntity(newKey, "http://book", isPrimary = true))

        assertEquals(newKey, repository.getPrimaryKeyForBook("http://book"))
        assertEquals("并集首元素是旧键——写键绝不能取它", oldKey, repository.getCommentKeysForBook("http://book").first())
    }

    @Test
    fun `getBookGroupRows returns empty for unknown book`() : Unit = runTest {
        val rows = repository.getBookGroupRows("http://nonexistent")
        assertTrue(rows.isEmpty())
    }
}

private class FakeChapterReader : ChapterReader {
    override suspend fun readChapter(entry: ChapterEntry, location: BookLocation): ChapterContent =
        ChapterContent(title = entry.title, paragraphs = listOf("fake content for ${entry.contentRef}"))
}

