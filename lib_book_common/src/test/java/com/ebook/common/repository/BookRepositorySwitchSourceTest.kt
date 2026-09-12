package com.ebook.common.repository

import com.ebook.common.analyze.local.BookFormat
import com.ebook.common.analyze.local.BookLocation
import com.ebook.common.analyze.local.ChapterReader
import com.ebook.common.analyze.source.FakeBookSourceManager
import com.ebook.common.analyze.source.RecordingBookParser
import com.ebook.common.domain.CommentKey
import com.ebook.common.store.BookStore
import com.ebook.common.store.ChapterContentCache
import com.ebook.db.entity.BookGroupEntity
import com.ebook.db.entity.BookInfoEntity
import com.ebook.db.entity.BookShelfEntity
import com.ebook.db.entity.ChapterListEntity
import com.ebook.db.entity.DownloadChapterEntity
import com.ebook.db.entity.LibraryEntity
import com.ebook.db.entity.SearchBookEntity
import com.ebook.db.entity.WebChapterEntity
import com.ebook.db.event.DBCode
import com.ebook.source.analyze.BookParser
import com.ebook.source.analyze.BookSourceNotFoundException
import java.io.IOException
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

/**
 * [BookRepository.switchSource] 的单元测试（纯 JVM，Fake DAO + 假书源管理器，不碰网络与 Robolectric）。
 *
 * 锁住换源事务最容易回归、也最贵的那几件事：
 * 1. **章序号映射**：`min(旧, 新总数-1)`——新源章节更少时截到末章、更多时保持旧序号（有损转换的边界）；
 * 2. 页级进度复位、`finalDate` 与匹配信息（`match_name`/`match_author`）随条目带过去：
 *    换源换的是载体，阅读时间与评论桶都不该被换掉；
 * 3. 新条目整本落库、旧条目**四张表都删净**（`chapter_list` 残留 = 一本看不见的书）；
 * 4. 旧条目的 `book_group` 关联键被新条目吸收（先吸收后删旧，否则评论并集随删旧一起丢）；
 * 5. 解析阶段失败时**一行库都没动**（书架未被改动，用户可另选候选）——含「新源解不出 BookInfo」
 *    这种脏返回：换源紧接着要删旧条目，半本入库会让这本书从书架上凭空消失；
 * 6. 三种被拒的入参直接失败：本地书、「目标就是当前条目本身」、以及**目标那条 noteUrl 本就是书架上
 *    另一个条目**（不挡就会 REPLACE 掉那本书的进度，见 [BookAlreadyOnShelfException]）；
 * 7. 事件顺序：Removed(旧) → Added(新)；
 * 8. **旧章文件刻意保留**（ADR 决策 8 的「可切回」在本仓现行存储下的落点，见 switchSource 的取舍段）——
 *    这条断言是给「顺手照搬 removeFromShelf 那两行删文件」准备的：那样改会让本用例立刻变红；
 * 9. 旧条目名下的**下载任务一行不留**（换源后那本书换了 noteUrl，服务按书架取任务，旧行永远没人取）。
 *
 * 关于「失败后旧行仍在」的诚实交代：假的事务接缝（[DirectTransactionRunner]）直接执行 block、
 * 不回滚，所以第 5 组用例锁的是「失败发生在任何写之前」，事务回滚本身由生产实现的
 * `Room` withTransaction 负责，不在纯 JVM 里假装验证。
 */
class BookRepositorySwitchSourceTest {

    private companion object {
        const val SOURCE_A = "https://a.example"
        const val SOURCE_B = "https://b.example"

        // noteUrl 在本仓里同时是内容仓库的目录名（`filesDir/books/<noteUrl>/cNNNNN.txt`），
        // 带 scheme 的 URL 在 Windows 的 JVM 测试里会被当成盘符而 mkdirs 失败，故取不带 `https://` 的形态。
        // 真机上那一段是合法目录名（Android/Ext4 允许冒号），本用例锁的是「目录留没留」而不是命名合法性。
        const val OLD_URL = "a.example/book/1.html"
        const val NEW_URL = "b.example/book/99.html"
        const val BOOK_NAME = "斗破苍穹"
        const val BOOK_AUTHOR = "天蚕土豆"
    }

    /** 章文件按用例隔离：换源不删旧章文件的断言要看真目录 */
    @get:Rule
    val booksRoot = TemporaryFolder()

    private lateinit var daos: FakeDaos
    private lateinit var store: BookStore
    private lateinit var manager: FakeBookSourceManager
    private lateinit var repository: BookRepository

    @Before
    fun setUp() {
        daos = FakeDaos()
        store = BookStore(booksRoot.root)
        manager = FakeBookSourceManager()
        repository = newRepository(manager)
    }

    private fun newRepository(sources: FakeBookSourceManager): BookRepository = BookRepository(
        bookShelfDao = daos.shelf,
        bookInfoDao = daos.info,
        chapterListDao = daos.chapter,
        bookGroupDao = daos.group,
        downloadChapterDao = daos.download,
        // 换源路径不读正文，reader 一律用不上
        chapterReaders = emptyMap<BookFormat, ChapterReader>(),
        bookSourceManager = sources,
        bookStore = store,
        contentCache = ChapterContentCache(capacity = 3),
        transactions = DirectTransactionRunner,
    )

    // ===== 装配 helper =====

    /** 造一个「新源解出来的条目」：noteUrl/tag 属于新源，目录恰好 [chapterCount] 章 */
    private fun parserOf(
        sourceUrl: String,
        noteUrl: String,
        chapterCount: Int,
        carryBookInfo: Boolean = true,
    ): RecordingBookParser {
        val withInfo = BookShelfEntity(noteUrl = noteUrl, tag = sourceUrl).apply {
            bookInfo = BookInfoEntity(name = BOOK_NAME, author = BOOK_AUTHOR, noteUrl = noteUrl)
        }
        val chapters = (0 until chapterCount).map { i ->
            ChapterListEntity(
                noteUrl = noteUrl,
                durChapterIndex = i,
                contentRef = "$sourceUrl/chapter/${i + 1}.html",
                durChapterName = "第${i + 1}章",
                tag = sourceUrl,
            )
        }
        return RecordingBookParser(
            ownedSourceUrl = sourceUrl,
            bookInfoResult = withInfo,
            chapterListData = withInfo.copy(
                chapterList = chapters,
                bookInfo = withInfo.bookInfo.takeIf { carryBookInfo },
            ),
        )
    }

    /** 登记「新源 [SOURCE_B] 能解出这本书，目录 [chapterCount] 章」 */
    private fun givenNewSource(
        chapterCount: Int,
        noteUrl: String = NEW_URL,
        carryBookInfo: Boolean = true,
    ) {
        manager.putParser(SOURCE_B, parserOf(SOURCE_B, noteUrl, chapterCount, carryBookInfo))
    }

    /** 把一本书放进书架（走真实的 addToShelf，连带 book_info / chapter_list / book_group 主键行） */
    private suspend fun seedShelf(
        chapterCount: Int,
        durChapter: Int,
        matchName: String? = null,
        matchAuthor: String? = null,
    ): BookShelfEntity {
        val shelf = BookShelfEntity(
            noteUrl = OLD_URL,
            tag = SOURCE_A,
            durChapter = durChapter,
            durChapterPage = 4,
            finalDate = 1_700_000_000_000,
            matchName = matchName,
            matchAuthor = matchAuthor,
        ).apply {
            bookInfo = BookInfoEntity(name = BOOK_NAME, author = BOOK_AUTHOR, noteUrl = OLD_URL)
            chapterList = (0 until chapterCount).map { i ->
                ChapterListEntity(
                    noteUrl = OLD_URL,
                    durChapterIndex = i,
                    contentRef = "$SOURCE_A/chapter/${i + 1}.html",
                    durChapterName = "第${i + 1}章",
                    tag = SOURCE_A,
                )
            }
        }
        repository.addToShelf(shelf)
        return shelf
    }

    private fun candidate() = SearchBookEntity(
        noteUrl = NEW_URL,
        name = BOOK_NAME,
        author = BOOK_AUTHOR,
        tag = SOURCE_B,
        origin = "B 站",
    )

    /**
     * 把另一本书也放上书架（[noteUrl] 归属 [sourceUrl]，进度 [durChapter]）。
     *
     * 走真实 [BookRepository.addToShelf] 而不是直接写 DAO：真机上「架上已有的那一本」是整条目
     * （book_info + book_shelf + chapter_list + book_group 都在），换源若把它 REPLACE 掉，
     * 丢的是它的进度与它自己那份目录行——只塞一行 shelf 就用例就断不到这些。
     */
    private suspend fun seedBookOnShelf(
        noteUrl: String,
        sourceUrl: String,
        chapterCount: Int,
        durChapter: Int,
    ): BookShelfEntity {
        val shelf = BookShelfEntity(
            noteUrl = noteUrl,
            tag = sourceUrl,
            durChapter = durChapter,
            finalDate = 1_600_000_000_000,
        ).apply {
            bookInfo = BookInfoEntity(name = BOOK_NAME, author = BOOK_AUTHOR, noteUrl = noteUrl)
            chapterList = (0 until chapterCount).map { i ->
                ChapterListEntity(
                    noteUrl = noteUrl,
                    durChapterIndex = i,
                    contentRef = "$sourceUrl/chapter/${i + 1}.html",
                    durChapterName = "第${i + 1}章",
                    tag = sourceUrl,
                )
            }
        }
        repository.addToShelf(shelf)
        return shelf
    }

    // ===== 1. 章序号映射 =====

    @Test
    fun `新源章节更少时进度截到新源的最后一章`(): Unit = runTest {
        val old = seedShelf(chapterCount = 100, durChapter = 50)
        givenNewSource(chapterCount = 30)

        val result = repository.switchSource(old, candidate())

        val newShelf = result.getOrThrow()
        assertEquals("min(50, 30-1) 落在末章", 29, newShelf.durChapter)
        assertEquals(29, daos.shelf.getBookByUrl(NEW_URL)?.durChapter)
    }

    @Test
    fun `新源章节更多时保持旧章序号`(): Unit = runTest {
        val old = seedShelf(chapterCount = 40, durChapter = 20)
        givenNewSource(chapterCount = 120)

        val newShelf = repository.switchSource(old, candidate()).getOrThrow()

        assertEquals("新目录装得下旧进度，就不该动它", 20, newShelf.durChapter)
    }

    @Test
    fun `新源没解出任何章节时序号取零而不是负数`(): Unit = runTest {
        val old = seedShelf(chapterCount = 10, durChapter = 5)
        givenNewSource(chapterCount = 0)

        val newShelf = repository.switchSource(old, candidate()).getOrThrow()

        assertEquals("min 的减一会给出 -1，阅读器按 index 取章会立刻越界", 0, newShelf.durChapter)
    }

    // ===== 2. 进度与身份字段的保留 =====

    @Test
    fun `页级进度复位而阅读时间保留`(): Unit = runTest {
        val old = seedShelf(chapterCount = 60, durChapter = 30)
        givenNewSource(chapterCount = 60)

        val newShelf = repository.switchSource(old, candidate()).getOrThrow()

        assertEquals(
            "页码不跨源：不同源同一章的分页规则不同，旧页码在新章里会读错位置",
            DBCode.BookContentView.DUR_PAGE_INDEX_BEGIN,
            newShelf.durChapterPage,
        )
        assertEquals("换源不该把「最后阅读」推到最新", old.finalDate, newShelf.finalDate)
        assertEquals(old.finalDate, daos.shelf.getBookByUrl(NEW_URL)?.finalDate)
    }

    @Test
    fun `匹配信息随条目带过去，评论桶不因换源漂移`(): Unit = runTest {
        val old = seedShelf(
            chapterCount = 60,
            durChapter = 3,
            matchName = "斗破苍穹（校对）",
            matchAuthor = "天蚕土豆",
        )
        givenNewSource(chapterCount = 60)

        val newShelf = repository.switchSource(old, candidate()).getOrThrow()

        assertEquals("新源书名不同也按用户校准过的写法算键", "斗破苍穹（校对）", newShelf.matchName)
        val primary = repository.getPrimaryKeyForBook(NEW_URL)
        assertEquals(
            CommentKey.compute("斗破苍穹（校对）", "天蚕土豆"),
            primary,
        )
    }

    // ===== 3/4. 落库范围与关联键吸收 =====

    @Test
    fun `新条目整本落库而旧条目四张表都删净`(): Unit = runTest {
        val old = seedShelf(chapterCount = 3, durChapter = 1)
        givenNewSource(chapterCount = 3)

        repository.switchSource(old, candidate()).getOrThrow()

        // 新条目：书架行 + book_info + 3 章目录都在
        assertNotNull(daos.shelf.getBookByUrl(NEW_URL))
        assertEquals(BOOK_NAME, daos.info.getBookInfoByUrl(NEW_URL)?.name)
        assertEquals(3, daos.chapter.getChaptersForBook(NEW_URL).size)
        assertTrue(daos.chapter.getChaptersForBook(NEW_URL).all { it.noteUrl == NEW_URL })
        // 旧条目：一行都不留——留着就是「书架上看不见、又删不掉」的孤儿行
        assertNull(daos.shelf.getBookByUrl(OLD_URL))
        assertNull(daos.info.getBookInfoByUrl(OLD_URL))
        assertEquals(
            "chapter_list 按 note_url 归属，留着等于保留一本不在书架的书",
            emptyList<ChapterListEntity>(),
            daos.chapter.getChaptersForBook(OLD_URL),
        )
        assertEquals(emptyList<BookGroupEntity>(), daos.group.getAllForNoteUrl(OLD_URL))
    }

    @Test
    fun `旧条目身上的关联键被新条目吸收`(): Unit = runTest {
        val old = seedShelf(chapterCount = 5, durChapter = 1)
        // 旧条目历次合并攒下的 secondary 键（评论并集的来源），换源前挂在自己身上
        daos.group.addSecondary(BookGroupEntity(commentKey = "ck1:legacy", noteUrl = OLD_URL, isPrimary = false))
        givenNewSource(chapterCount = 8)

        repository.switchSource(old, candidate()).getOrThrow()

        val keys = repository.getCommentKeysForBook(NEW_URL)
        assertTrue("旧条目的历史键必须并到新条目名下", keys.contains("ck1:legacy"))
        assertEquals("新条目自己也要有主键行", CommentKey.compute(BOOK_NAME, BOOK_AUTHOR), repository.getPrimaryKeyForBook(NEW_URL))
        assertEquals("旧条目名下不再留任何键行", emptyList<BookGroupEntity>(), daos.group.getAllForNoteUrl(OLD_URL))
    }

    // ===== 5. 失败窗口 =====

    @Test
    fun `目标源取不到解析器时失败且书架未被改动`(): Unit = runTest {
        val old = seedShelf(chapterCount = 5, durChapter = 2)
        // 库里没有 SOURCE_B 那一行（用户删了那个源）→ getParserFor 返回 null
        manager.putParser(SOURCE_A, parserOf(SOURCE_A, OLD_URL, chapterCount = 5))

        val result = repository.switchSource(old, candidate())

        val error = result.exceptionOrNull()
        assertTrue("取不到源要报成书源失效，而不是含糊的失败", error is BookSourceNotFoundException)
        assertEquals(SOURCE_B, (error as BookSourceNotFoundException).sourceUrl)
        assertNotNull("旧条目必须还在书架上", daos.shelf.getBookByUrl(OLD_URL))
        assertNull("一行新数据都不该写进去", daos.shelf.getBookByUrl(NEW_URL))
        assertEquals(emptyList<BookGroupEntity>(), daos.group.getAllForNoteUrl(NEW_URL))
    }

    @Test
    fun `新源拉取抛异常时失败且旧行仍在`(): Unit = runTest {
        val old = seedShelf(chapterCount = 5, durChapter = 2)
        manager.putParser(SOURCE_B, BrokenBookParser(SOURCE_B))

        val result = repository.switchSource(old, candidate())

        assertTrue("解析异常原样带回调用方", result.exceptionOrNull() is IOException)
        assertNotNull("失败发生在任何写之前，旧条目完好", daos.shelf.getBookByUrl(OLD_URL))
        assertEquals(5, daos.chapter.getChaptersForBook(OLD_URL).size)
        assertNull(daos.shelf.getBookByUrl(NEW_URL))
        assertEquals(
            "解析阶段就失败，连吸收关联键都不该发生",
            emptyList<BookGroupEntity>(),
            daos.group.getAllForNoteUrl(NEW_URL),
        )
    }

    @Test
    fun `新源解不出书籍信息时放弃换源，旧条目仍在`(): Unit = runTest {
        val old = seedShelf(chapterCount = 5, durChapter = 2)
        // 目录能解出来、book_info 却是空的（规则缺项或换了解析器实现）
        givenNewSource(chapterCount = 5, carryBookInfo = false)

        val result = repository.switchSource(old, candidate())

        assertTrue("脏返回要报成异常而不是半本入库", result.exceptionOrNull() is IllegalStateException)
        assertNotNull("换源紧接着会删旧条目，所以这种目标必须在写之前就挡掉", daos.shelf.getBookByUrl(OLD_URL))
        assertNull(daos.shelf.getBookByUrl(NEW_URL))
    }

    // ===== 6. 非法入参 =====

    @Test
    fun `本地书没有书源可换，直接失败而不报成源失效`(): Unit = runTest {
        val local = BookShelfEntity(
            noteUrl = "local-md5",
            tag = BookShelfEntity.LOCAL_TAG,
            bookFormat = "TXT",
            durChapter = 7,
        )

        val result = repository.switchSource(local, candidate())

        val error = result.exceptionOrNull()
        assertTrue("本地书是入参不合法，不是「书源被删了」", error is IllegalArgumentException)
        assertEquals(
            "loc_book 一律不查 book_source 表，不该被问起",
            emptyList<String>(),
            manager.parserForCalls,
        )
    }

    @Test
    fun `换源目标就是当前条目时拒绝，否则会把自己删掉`(): Unit = runTest {
        val old = seedShelf(chapterCount = 5, durChapter = 2)
        givenNewSource(chapterCount = 5, noteUrl = OLD_URL)

        val result = repository.switchSource(old, candidate().copy(noteUrl = OLD_URL, tag = SOURCE_B))

        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
        assertNotNull("先插新后删旧在同一条目上会让这本书消失，必须挡住", daos.shelf.getBookByUrl(OLD_URL))
    }

    /**
     * 目标那条 `noteUrl` 是**书架上另一本已有的书**（不是当前这条）。
     *
     * 不挡的后果不是「白换一次源」而是丢数据：`writeEntry` 走的 [com.ebook.db.dao.BookShelfDao.insert]
     * 是 `OnConflictStrategy.REPLACE`，主键就是自然键 `note_url`——B 那一行被整行换成 A 映射过来的进度
     * （B 自己的阅读进度就此消失），两本书并成一册；而 `chapter_list` 的主键是 `content_ref`（章节 URL），
     * A 的新目录行与 B 原有的目录行主键不冲突，谁也不覆盖谁，于是这一册里混着两个站的目录。
     * 换源面板的候选不做书架过滤（用户该看得见「这本别处也有」），所以这条守卫是仓库侧唯一的拦点。
     */
    @Test
    fun `目标已在书架上时拒绝换源，两本书的进度都不被改动`(): Unit = runTest {
        val old = seedShelf(chapterCount = 5, durChapter = 2)
        // B 书：同一个 noteUrl（= 候选要换过去的那本）早已在架上，且自己有进度与目录
        seedBookOnShelf(NEW_URL, SOURCE_B, chapterCount = 20, durChapter = 12)
        givenNewSource(chapterCount = 8)

        val result = repository.switchSource(old, candidate())

        val error = result.exceptionOrNull()
        assertTrue(
            "要报成「这本书已在书架上」这种说得出人话的失败，而不是含糊的失败",
            error is BookAlreadyOnShelfException,
        )
        assertEquals(NEW_URL, (error as BookAlreadyOnShelfException).noteUrl)
        assertEquals(
            "B 的进度不许被 A 映射过来的章序号覆盖掉（REPLACE 是整行替换）",
            12,
            daos.shelf.getBookByUrl(NEW_URL)?.durChapter,
        )
        assertEquals("A 也还在架上，换源一笔都没做成", 2, daos.shelf.getBookByUrl(OLD_URL)?.durChapter)
        assertEquals(
            "B 原有的目录行不能被 A 的新目录混进来（content_ref 主键不同，谁也不覆盖谁）",
            20,
            daos.chapter.getChaptersForBook(NEW_URL).size,
        )
        assertEquals(
            "既然拒绝了，就不该为目标源发那次详情/目录解析",
            emptyList<String>(),
            manager.parserForCalls,
        )
    }

    // ===== 7. 事件 =====

    @Test
    fun `换源成功先发 Removed 旧条目再发 Added 新条目`(): Unit = runTest {
        val old = seedShelf(chapterCount = 5, durChapter = 1)
        givenNewSource(chapterCount = 8)
        val events = mutableListOf<BookShelfEvent>()
        backgroundScope.launch { repository.bookShelfEvents.toList(events) }
        runCurrent() // 先让订阅者完成订阅（SharedFlow 无 replay，晚订阅会丢事件）

        repository.switchSource(old, candidate())
        runCurrent()

        assertEquals("换源只该发两个事件（seed 在订阅之前，不计入）", 2, events.size)
        assertEquals(listOf(OLD_URL), events.filterIsInstance<BookShelfEvent.Removed>().map { it.bookShelf.noteUrl })
        assertEquals(listOf(NEW_URL), events.filterIsInstance<BookShelfEvent.Added>().map { it.bookShelf.noteUrl })
        assertTrue("顺序是「先撤旧后补新」", events.first() is BookShelfEvent.Removed)
        assertTrue(events.last() is BookShelfEvent.Added)
    }

    // ===== 8. 旧章文件刻意保留 =====

    @Test
    fun `换源后旧源的章文件仍在盘上，供切回时秒开`(): Unit = runTest {
        val old = seedShelf(chapterCount = 5, durChapter = 2)
        val oldLocation = BookLocation(OLD_URL, BookFormat.NETWORK, SOURCE_A)
        store.writeChapter(oldLocation, 2, listOf("第 3 章正文"))
        assertTrue("前置条件：章文件确实落盘了", store.hasChapter(oldLocation, 2))
        givenNewSource(chapterCount = 8)

        repository.switchSource(old, candidate()).getOrThrow()

        assertTrue(
            "换源刻意不走 removeFromShelf：条目删净但章文件留着，切回旧源时正文命中盘上文件（秒开）",
            store.hasChapter(oldLocation, 2),
        )
        assertFalse(
            "新条目名下不该有旧源的内容",
            store.hasChapter(BookLocation(NEW_URL, BookFormat.NETWORK, SOURCE_B), 2),
        )
    }

    // ===== 9. 旧条目的下载任务一并删净（不留孤儿） =====

    /**
     * 换源必须把旧 `noteUrl` 名下的在队任务一起删掉。
     *
     * 不删就是三处现象同时坏：
     * - 服务取任务是**按书架遍历**的（`DownloadRepository.getNextDownloadTask` → 逐本
     *   `getFirstByNoteUrl`），旧 noteUrl 已从 `book_shelf` 消失，这些行永远不会再被取到——
     *   队列里从此躺着几个谁也碰不到的任务；
     * - 下载管理页按 `note_url` 分组展示「全部待下载任务」，旧那一组还在，而它的章节总数要按
     *   `chapter_list` 里那本的目录算（行已被删净）→ 一个 `totalChapters = 0` 的幽灵分组；
     * - 书架的下载角标看的是全表 `COUNT(*)`（不分书），这些行不清零就永远不归零。
     *
     * 为什么是**删**而不是「改成新 noteUrl 续跑」：任务里的 `durChapterUrl` 是旧站的章节地址，
     * 换个归属等于拿旧 URL 去打新站（`JsoupSourceReader` 按任务的 `tag` 取 parser、按 `contentRef`
     * 抓正文），最坏解回一章不相干的内容。用户想让新源离线，在阅读器里对新的那一本重新发起下载即可
     * （面板本来就在）。
     */
    @Test
    fun `换源后旧条目名下的下载任务一行不留`(): Unit = runTest {
        val old = seedShelf(chapterCount = 5, durChapter = 2)
        daos.download.insertAll(
            listOf(
                DownloadChapterEntity(
                    noteUrl = OLD_URL,
                    durChapterIndex = 3,
                    durChapterUrl = "$SOURCE_A/chapter/4.html",
                    durChapterName = "第4章",
                    tag = SOURCE_A,
                    bookName = BOOK_NAME,
                ),
                DownloadChapterEntity(
                    noteUrl = OLD_URL,
                    durChapterIndex = 4,
                    durChapterUrl = "$SOURCE_A/chapter/5.html",
                    durChapterName = "第5章",
                    tag = SOURCE_A,
                    bookName = BOOK_NAME,
                ),
            )
        )
        // 另一本无关的书也在下载：换源不该顺手清掉别人的队列
        daos.download.insert(
            DownloadChapterEntity(
                noteUrl = "other.example/book/1.html",
                durChapterIndex = 0,
                durChapterUrl = "https://c.example/chapter/1.html",
                tag = "https://c.example",
                bookName = "另一本",
            )
        )
        givenNewSource(chapterCount = 8)

        repository.switchSource(old, candidate()).getOrThrow()

        assertEquals(
            "旧 noteUrl 的任务服务再也取不到，留着只会成为幽灵分组与不归零的角标",
            0,
            daos.download.countFor(OLD_URL),
        )
        assertEquals("别的书的队列不受影响", 1, daos.download.countFor("other.example/book/1.html"))
    }

    /**
     * 只服务失败路径的 parser：`getBookInfo` 抛 IOException，模拟网络断与解析异常。
     *
     * 其余成员一调就抛——假件把未实现成员静默返回空值，会让「其实没走到那条路径」的断言假绿。
     */
    private class BrokenBookParser(val ownedSourceUrl: String) : BookParser {
        override suspend fun getBookInfo(bookShelf: BookShelfEntity): BookShelfEntity =
            throw IOException("拉取详情失败：$ownedSourceUrl")

        override suspend fun getChapterList(bookShelf: BookShelfEntity): WebChapterEntity<BookShelfEntity> =
            throw UnsupportedOperationException("详情都没拉到，不该问目录")

        private fun unsupported(who: String): Nothing =
            throw UnsupportedOperationException("BrokenBookParser 未实现 $who")

        override suspend fun searchBook(content: String, page: Int): List<SearchBookEntity> =
            unsupported("searchBook")

        override suspend fun getKindBook(url: String, page: Int): List<SearchBookEntity> =
            unsupported("getKindBook")

        override suspend fun fetchLibraryData(): LibraryEntity = unsupported("fetchLibraryData")
    }
}
