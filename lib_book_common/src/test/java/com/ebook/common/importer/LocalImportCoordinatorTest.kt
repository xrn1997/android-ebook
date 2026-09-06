package com.ebook.common.importer

import com.ebook.common.analyze.local.BookFormat
import com.ebook.common.analyze.local.TxtSourceReader
import com.ebook.common.domain.CommentKey
import com.ebook.common.domain.DuplicateBookDetector
import com.ebook.common.repository.BookRepository
import com.ebook.common.repository.DirectTransactionRunner
import com.ebook.common.repository.FakeDaos
import com.ebook.common.store.BookStore
import com.ebook.common.store.ChapterContentCache
import com.ebook.db.entity.BookGroupEntity
import com.ebook.db.entity.BookInfoEntity
import com.ebook.db.entity.BookShelfEntity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.time.Duration.Companion.seconds

/**
 * [LocalImportCoordinator] 的单元测试（纯 JVM，手写 Fake DAO + 真实导入器）。
 *
 * 导入循环从导入页 ViewModel 上移到进程级协调器（spec §6「点完导入即可继续操作」），
 * 这里锁三件搬移后最容易回归的事：
 * 1. 批量在自有作用域里跑完：outcome 收尾、解析中列表清空、书架行落库；
 * 2. 判重命中时批次**停在门上**等处置（处置前零写入），选择后继续推进；
 * 3. 「跳过」不写任何表也不留"解析中"残影。
 *
 * 协调器内部是真实 IO 作用域（非虚拟时间）：等待一律以 SharedFlow/StateFlow 的信号为准
 * （[withTimeout] 兜底防悬挂），不断言时序只断言因果。
 */
class LocalImportCoordinatorTest {

    @get:Rule val tmp = TemporaryFolder()

    private lateinit var booksRoot: File
    private lateinit var scratch: File
    private lateinit var store: BookStore
    private lateinit var daos: FakeDaos
    private lateinit var importer: LocalBookImporter
    private lateinit var coordinator: LocalImportCoordinator

    @Before
    fun setUp() {
        booksRoot = tmp.newFolder("books")
        scratch = tmp.newFolder("scratch")
        store = BookStore(booksRoot)
        daos = FakeDaos()
        val bookRepository = BookRepository(
            daos.shelf, daos.info, daos.chapter, daos.group,
            chapterReaders = mapOf(BookFormat.TXT to TxtSourceReader(store)),
            bookStore = store,
            contentCache = ChapterContentCache(),
            transactions = DirectTransactionRunner,
        )
        importer = LocalBookImporter(
            bookStore = store,
            scratchDir = scratch,
            readers = mapOf(BookFormat.TXT to TxtSourceReader(store)),
            bookShelfDao = daos.shelf,
            bookInfoDao = daos.info,
            chapterListDao = daos.chapter,
            bookGroupDao = daos.group,
            transactions = DirectTransactionRunner,
            bookRepository = bookRepository,
        )
        coordinator = LocalImportCoordinator(
            importer = importer,
            duplicateBookDetector = DuplicateBookDetector(daos.shelf, daos.info, daos.group),
            bookRepository = bookRepository,
        )
    }

    @Test
    fun `submit imports file end to end and publishes batch outcome`() : Unit = runTest {
        val outcome = CompletableDeferred<ImportBatchOutcome>()
        backgroundScope.launch {
            coordinator.batchFinished.take(1).collect { outcome.complete(it) }
        }
        runCurrent()

        coordinator.submit(listOf(book("第一章 起\n正文甲")))

        val result = awaitRealtime { outcome.await() }
        assertEquals(1, result.successCount)
        assertEquals(0, result.failCount)
        assertEquals("批次结束后解析中列表必须清空", emptyList<ParsingBook>(), coordinator.parsingBooks.value)
        assertEquals("书架行已落库", 1, daos.shelf.storedValues().size)
    }

    @Test
    fun `duplicate hit pauses batch until disposition then keeps both`() : Unit = runTest {
        val file = book("第一章 起\n正文甲", name = "样本书 作者：某作者.txt")
        val meta = importer.parseMetadata(file)
        val key = CommentKey.compute(meta.title, meta.author)
        // 播种一个同键条目（判重比的是当前主键）
        daos.shelf.insert(BookShelfEntity(noteUrl = "existing", tag = BookShelfEntity.LOCAL_TAG))
        daos.info.insert(BookInfoEntity(name = "样本书", noteUrl = "existing", author = "某作者"))
        daos.group.insert(BookGroupEntity(key, "existing", isPrimary = true))

        val detected = CompletableDeferred<ImportDuplicateState.Detected>()
        val outcome = CompletableDeferred<ImportBatchOutcome>()
        backgroundScope.launch {
            coordinator.duplicateState.first { it is ImportDuplicateState.Detected }
                .let { detected.complete(it as ImportDuplicateState.Detected) }
        }
        backgroundScope.launch {
            coordinator.batchFinished.take(1).collect { outcome.complete(it) }
        }
        runCurrent()

        coordinator.submit(listOf(file))

        val hit = awaitRealtime { detected.await() }
        assertEquals("处置框携带解析出的元数据", meta.title, hit.meta.title)
        assertEquals("处置前不得写新行（只有播种的那条）", 1, daos.shelf.storedValues().size)

        coordinator.resolveKeepBoth()
        val result = awaitRealtime { outcome.await() }
        assertEquals(1, result.successCount)
        assertEquals("同键共存：书架上两条", 2, daos.shelf.storedValues().size)
        assertTrue(coordinator.parsingBooks.value.isEmpty())
    }

    @Test
    fun `resolveCancel skips file without any write`() : Unit = runTest {
        val file = book("第一章 起\n正文甲", name = "样本书 作者：某作者.txt")
        val meta = importer.parseMetadata(file)
        val key = CommentKey.compute(meta.title, meta.author)
        daos.shelf.insert(BookShelfEntity(noteUrl = "existing", tag = BookShelfEntity.LOCAL_TAG))
        daos.info.insert(BookInfoEntity(name = "样本书", noteUrl = "existing", author = "某作者"))
        daos.group.insert(BookGroupEntity(key, "existing", isPrimary = true))

        val detected = CompletableDeferred<ImportDuplicateState.Detected>()
        val outcome = CompletableDeferred<ImportBatchOutcome>()
        backgroundScope.launch {
            coordinator.duplicateState.first { it is ImportDuplicateState.Detected }
                .let { detected.complete(it as ImportDuplicateState.Detected) }
        }
        backgroundScope.launch {
            coordinator.batchFinished.take(1).collect { outcome.complete(it) }
        }
        runCurrent()

        coordinator.submit(listOf(file))
        awaitRealtime { detected.await() }
        coordinator.resolveCancel()

        val result = awaitRealtime { outcome.await() }
        assertEquals("跳过不算成功", 0, result.successCount)
        assertEquals("跳过也不算失败", 0, result.failCount)
        assertTrue("被跳过的文件不得产生任何书架行", daos.shelf.storedValues().size == 1)
        assertTrue(coordinator.parsingBooks.value.isEmpty())
    }

    /**
     * 在**真实时间**上等待协调器的真实 IO 作用域发来的信号。
     *
     * 不能直接用 `withTimeout`：`runTest` 的虚拟时钟会在空闲时瞬间走完超时额度，
     * 而被测协程跑在真实 `Dispatchers.IO` 上——挂到 `Dispatchers.Default` 才是墙钟语义。
     */
    private suspend fun <T> awaitRealtime(seconds: Int = 10, block: suspend () -> T): T =
        withContext(Dispatchers.Default) {
            withTimeout(seconds.seconds) { block() }
        }

    private fun book(content: String, name: String = "样本书.txt"): File =
        File(scratch, name).apply { writeText(content, Charsets.UTF_8) }
}
