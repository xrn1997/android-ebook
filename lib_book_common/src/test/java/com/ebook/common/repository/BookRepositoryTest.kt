package com.ebook.common.repository

import com.ebook.db.dao.BookContentDao
import com.ebook.db.dao.BookInfoDao
import com.ebook.db.dao.BookShelfDao
import com.ebook.db.dao.ChapterListDao
import com.ebook.db.entity.BookContentEntity
import com.ebook.db.entity.BookInfoEntity
import com.ebook.db.entity.BookShelfEntity
import com.ebook.db.entity.BookShelfFullInfo
import com.ebook.db.entity.ChapterListEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [BookRepository] 的单元测试（纯 JVM，手写 Fake DAO，不依赖 Robolectric/Room）。
 *
 * 覆盖 `docs/test-coverage-todo.md` 点名的行为：
 * - 章节内容缓存：[BookRepository.loadBookContent] / [BookRepository.saveBookContent] /
 *   [BookRepository.updateChapterCache]
 * - 书架事件总线：[BookRepository.bookShelfEvents] 的 Added/Removed/ProgressUpdated 发射（见 ADR-0004）
 * - 级联写入/清理：[BookRepository.addToShelf] / [BookRepository.removeFromShelf]
 * - 已缓存章节查询：[BookRepository.getCachedChapterUrls]（空入参短路）
 *
 * 说明：[BookRepository] 继承 lib_common 的 `BaseModel`；本测试直接实例化，
 * 若 `BaseModel` 引入 Android 框架依赖会导致初始化失败——该风险由本测试类的可运行性本身兜底验证。
 */
class BookRepositoryTest {

    private lateinit var shelfDao: FakeBookShelfDao
    private lateinit var infoDao: FakeBookInfoDao
    private lateinit var chapterDao: FakeChapterListDao
    private lateinit var contentDao: FakeBookContentDao
    private lateinit var repository: BookRepository

    @Before
    fun setUp() {
        shelfDao = FakeBookShelfDao()
        infoDao = FakeBookInfoDao()
        chapterDao = FakeChapterListDao()
        contentDao = FakeBookContentDao()
        repository = BookRepository(shelfDao, infoDao, chapterDao, contentDao)
    }

    // ===== 章节内容缓存 =====

    @Test
    fun `loadBookContent 命中已缓存正文`() = runTest {
        val content = BookContentEntity(durChapterUrl = "http://a/1", durChapterContent = "正文")
        contentDao.insert(content)

        val loaded = repository.loadBookContent("http://a/1")

        assertEquals("正文", loaded?.durChapterContent)
    }

    @Test
    fun `loadBookContent 未命中返回 null`() = runTest {
        assertNull(repository.loadBookContent("http://a/missing"))
    }

    @Test
    fun `saveBookContent 写入后可被加载`() = runTest {
        repository.saveBookContent(BookContentEntity(durChapterUrl = "http://a/2", durChapterContent = "第二章"))

        assertEquals("第二章", repository.loadBookContent("http://a/2")?.durChapterContent)
    }

    @Test
    fun `updateChapterCache 原地改写章节缓存标记`() = runTest {
        val chapter = ChapterListEntity(noteUrl = "http://book", durChapterUrl = "http://a/1")
        chapterDao.insertAll(listOf(chapter))

        repository.updateChapterCache("http://a/1", true)

        assertTrue(chapterDao.getChapterByUrl("http://a/1")?.hasCache == true)
    }

    @Test
    fun `getCachedChapterUrls 空入参短路不查库`() = runTest {
        val cached = repository.getCachedChapterUrls(emptyList())

        assertTrue(cached.isEmpty())
        assertEquals(0, contentDao.queryExistingCount)
    }

    @Test
    fun `getCachedChapterUrls 只返回确有正文缓存的章节`() = runTest {
        contentDao.insert(BookContentEntity(durChapterUrl = "http://a/1"))
        contentDao.insert(BookContentEntity(durChapterUrl = "http://a/3"))

        val cached = repository.getCachedChapterUrls(listOf("http://a/1", "http://a/2", "http://a/3"))

        assertEquals(setOf("http://a/1", "http://a/3"), cached)
    }

    // ===== 级联写入 / 清理 =====

    @Test
    fun `addToShelf 级联写入并回填 noteUrl 关联`() = runTest {
        val shelf = BookShelfEntity(noteUrl = "http://book").apply {
            bookInfo = BookInfoEntity(name = "斗破苍穹") // noteUrl 故意留空，验证回填
            chapterList = listOf(
                ChapterListEntity(durChapterUrl = "http://a/1", durChapterIndex = 0),
                ChapterListEntity(durChapterUrl = "http://a/2", durChapterIndex = 1),
            )
        }

        repository.addToShelf(shelf)

        // bookInfo 落库且 noteUrl 被回填为书架 noteUrl
        assertEquals("斗破苍穹", infoDao.getBookInfoByUrl("http://book")?.name)
        // 书架落库
        assertEquals("http://book", shelfDao.getBookByUrl("http://book")?.noteUrl)
        // 章节落库且每章 noteUrl 被回填
        val chapters = chapterDao.getChaptersForBook("http://book")
        assertEquals(2, chapters.size)
        assertTrue(chapters.all { it.noteUrl == "http://book" })
    }

    @Test
    fun `removeFromShelf 级联清理章节正文与关联记录`() = runTest {
        // 预置一本书：书架 + 信息 + 两章 + 两段正文缓存
        val shelf = BookShelfEntity(noteUrl = "http://book").apply {
            bookInfo = BookInfoEntity(name = "待删除")
            chapterList = listOf(
                ChapterListEntity(durChapterUrl = "http://a/1"),
                ChapterListEntity(durChapterUrl = "http://a/2"),
            )
        }
        repository.addToShelf(shelf)
        contentDao.insert(BookContentEntity(durChapterUrl = "http://a/1"))
        contentDao.insert(BookContentEntity(durChapterUrl = "http://a/2"))

        repository.removeFromShelf(shelf)

        assertNull(shelfDao.getBookByUrl("http://book"))
        assertNull(infoDao.getBookInfoByUrl("http://book"))
        assertTrue(chapterDao.getChaptersForBook("http://book").isEmpty())
        assertNull(contentDao.getContentByChapterUrl("http://a/1"))
        assertNull(contentDao.getContentByChapterUrl("http://a/2"))
    }

    // ===== 事件总线（ADR-0004：SharedFlow 替代 RxBus） =====

    @Test
    fun `addToShelf 发射 Added 事件`() = runTest {
        val events = mutableListOf<BookShelfEvent>()
        backgroundScope.launch { repository.bookShelfEvents.toList(events) }
        runCurrent() // 先让订阅者完成订阅（SharedFlow 无 replay，晚订阅会丢事件）

        repository.addToShelf(BookShelfEntity(noteUrl = "http://book"))
        runCurrent()

        assertTrue(events.any { it is BookShelfEvent.Added })
    }

    @Test
    fun `removeFromShelf 发射 Removed 事件`() = runTest {
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
    fun `saveProgress 刷新最后阅读时间并发射 ProgressUpdated 事件`() = runTest {
        val shelf = BookShelfEntity(noteUrl = "http://book", finalDate = 0L)
        repository.addToShelf(shelf)

        val events = mutableListOf<BookShelfEvent>()
        backgroundScope.launch { repository.bookShelfEvents.toList(events) }
        runCurrent()

        repository.saveProgress(shelf)
        runCurrent()

        assertTrue(shelf.finalDate > 0L)
        assertEquals(shelf.finalDate, shelfDao.getBookByUrl("http://book")?.finalDate)
        assertTrue(events.any { it is BookShelfEvent.ProgressUpdated })
    }

    // ===== 关联查询与孤立记录清理 =====

    @Test
    fun `getAllBooksWithDetails 过滤并清理 info 为空的孤立记录，且章节按序号排序`() = runTest {
        val okShelf = BookShelfEntity(noteUrl = "http://ok")
        val orphanShelf = BookShelfEntity(noteUrl = "http://orphan")
        // 正常书：章节故意乱序（rowid 错序回归），验证按 durChapterIndex 显式排序
        shelfDao.fullInfoSeed.add(
            BookShelfFullInfo(
                bookShelf = okShelf,
                info = BookInfoEntity(noteUrl = "http://ok", name = "正常书"),
                chapters = listOf(
                    ChapterListEntity(noteUrl = "http://ok", durChapterUrl = "c2", durChapterIndex = 2),
                    ChapterListEntity(noteUrl = "http://ok", durChapterUrl = "c0", durChapterIndex = 0),
                    ChapterListEntity(noteUrl = "http://ok", durChapterUrl = "c1", durChapterIndex = 1),
                ),
            )
        )
        // 孤立书：info 为 null，应被过滤并触发 deleteByUrl 清理
        shelfDao.fullInfoSeed.add(BookShelfFullInfo(bookShelf = orphanShelf, info = null, chapters = emptyList()))

        val result = repository.getAllBooksWithDetails()

        assertEquals(1, result.size)
        assertEquals("http://ok", result[0].noteUrl)
        assertEquals(listOf("c0", "c1", "c2"), result[0].chapterList.map { it.durChapterUrl })
        assertTrue(orphanShelf.noteUrl in shelfDao.deletedUrls)
    }

    @Test
    fun `observeBookShelf 过滤 info 为空的条目并填充 bookInfo`() = runTest {
        val shelf = BookShelfEntity(noteUrl = "http://ok")
        shelfDao.fullInfoFlow.value = listOf(
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
}

// ===== 手写 Fake DAO（内存实现，对齐项目 FakeUserSessionManager 的手写 Fake 风格） =====

private class FakeBookShelfDao : BookShelfDao {
    private val shelfByNoteUrl = linkedMapOf<String, BookShelfEntity>()

    /** 测试直接播种 getAllBooksFullInfo 的返回，用于构造关联/孤立场景 */
    val fullInfoSeed = mutableListOf<BookShelfFullInfo>()

    /** getAllBooksFullInfoFlow 的可控数据源 */
    val fullInfoFlow = MutableStateFlow<List<BookShelfFullInfo>>(emptyList())

    /** 记录被 deleteByUrl 删除的 noteUrl，供断言孤立清理 */
    val deletedUrls = mutableListOf<String>()

    override suspend fun getAllBooksFullInfo(): List<BookShelfFullInfo> = fullInfoSeed.toList()
    override fun getAllBooksFullInfoFlow(): Flow<List<BookShelfFullInfo>> = fullInfoFlow
    override suspend fun getBookFullInfoByUrl(noteUrl: String): BookShelfFullInfo? =
        fullInfoSeed.firstOrNull { it.bookShelf.noteUrl == noteUrl }

    override fun getAllBooksFlow(): Flow<List<BookShelfEntity>> =
        MutableStateFlow(shelfByNoteUrl.values.sortedByDescending { it.finalDate })

    override suspend fun getAllBooks(): List<BookShelfEntity> =
        shelfByNoteUrl.values.sortedByDescending { it.finalDate }

    override suspend fun getBookByUrl(noteUrl: String): BookShelfEntity? = shelfByNoteUrl[noteUrl]
    override suspend fun getBooksByUrls(noteUrls: List<String>): List<BookShelfEntity> =
        shelfByNoteUrl.values.filter { it.noteUrl in noteUrls }

    override suspend fun insert(bookShelf: BookShelfEntity) {
        shelfByNoteUrl[bookShelf.noteUrl] = bookShelf
    }

    override suspend fun insertAll(books: List<BookShelfEntity>) = books.forEach { insert(it) }
    override suspend fun update(bookShelf: BookShelfEntity) {
        shelfByNoteUrl[bookShelf.noteUrl] = bookShelf
    }

    override suspend fun delete(bookShelf: BookShelfEntity) {
        shelfByNoteUrl.remove(bookShelf.noteUrl)
    }

    override suspend fun deleteByUrl(noteUrl: String) {
        shelfByNoteUrl.remove(noteUrl)
        deletedUrls.add(noteUrl)
    }

    override suspend fun getCount(): Int = shelfByNoteUrl.size
}

private class FakeBookInfoDao : BookInfoDao {
    private val infoByNoteUrl = linkedMapOf<String, BookInfoEntity>()

    override suspend fun getBookInfoByUrl(noteUrl: String): BookInfoEntity? = infoByNoteUrl[noteUrl]
    override suspend fun insert(bookInfo: BookInfoEntity) {
        infoByNoteUrl[bookInfo.noteUrl] = bookInfo
    }

    override suspend fun deleteByUrl(noteUrl: String) {
        infoByNoteUrl.remove(noteUrl)
    }
}

private class FakeChapterListDao : ChapterListDao {
    private val chapterByUrl = linkedMapOf<String, ChapterListEntity>() // key = durChapterUrl（主键）

    override suspend fun getChaptersForBook(bookNoteUrl: String): List<ChapterListEntity> =
        chapterByUrl.values.filter { it.noteUrl == bookNoteUrl }.sortedBy { it.durChapterIndex }

    override suspend fun getChapterByUrl(chapterUrl: String): ChapterListEntity? = chapterByUrl[chapterUrl]

    override suspend fun insertAll(chapters: List<ChapterListEntity>) {
        chapters.forEach { chapterByUrl[it.durChapterUrl] = it }
    }

    override suspend fun updateHasCache(chapterUrl: String, hasCache: Boolean) {
        chapterByUrl[chapterUrl]?.hasCache = hasCache
    }

    override suspend fun countChaptersForBook(bookNoteUrl: String): Int =
        chapterByUrl.values.count { it.noteUrl == bookNoteUrl }

    override suspend fun countCachedChaptersForBook(bookNoteUrl: String): Int =
        chapterByUrl.values.count { it.noteUrl == bookNoteUrl && it.hasCache }

    override suspend fun deleteChaptersForBook(bookNoteUrl: String) {
        chapterByUrl.entries.removeAll { it.value.noteUrl == bookNoteUrl }
    }
}

private class FakeBookContentDao : BookContentDao {
    private val contents = linkedMapOf<String, BookContentEntity>() // key = durChapterUrl（主键）

    /** 统计 getExistingChapterUrls 被调用次数，用于验证空入参短路 */
    var queryExistingCount = 0
        private set

    override suspend fun getContentByChapterUrl(chapterUrl: String): BookContentEntity? = contents[chapterUrl]

    override suspend fun getExistingChapterUrls(chapterUrls: List<String>): List<String> {
        queryExistingCount++
        return contents.keys.filter { it in chapterUrls }
    }

    override suspend fun insert(content: BookContentEntity) {
        contents[content.durChapterUrl] = content
    }

    override suspend fun deleteByChapterUrl(chapterUrl: String) {
        contents.remove(chapterUrl)
    }

    override suspend fun deleteByChapterUrls(chapterUrls: List<String>) {
        chapterUrls.forEach { contents.remove(it) }
    }
}
