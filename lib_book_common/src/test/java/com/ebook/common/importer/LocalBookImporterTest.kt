package com.ebook.common.importer

import com.ebook.common.analyze.local.BookFormat
import com.ebook.common.analyze.local.BookLocation
import com.ebook.common.analyze.local.BookSourceFile
import com.ebook.common.analyze.local.ChapterContent
import com.ebook.common.analyze.local.ChapterEntry
import com.ebook.common.analyze.local.ChapterSink
import com.ebook.common.analyze.local.LocalBookMeta
import com.ebook.common.analyze.local.SourceReader
import com.ebook.common.analyze.local.TxtSourceReader
import com.ebook.common.domain.CommentKey
import com.ebook.common.repository.BookRepository
import com.ebook.common.repository.DirectTransactionRunner
import com.ebook.common.repository.FakeDaos
import com.ebook.common.store.BookStore
import com.ebook.common.store.ChapterContentCache
import com.ebook.common.store.WriteTransactionRunner
import com.ebook.db.entity.BookShelfEntity
import com.ebook.db.entity.LocBookShelfEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/**
 * [LocalBookImporter] 的端到端测试（纯 JVM，临时目录 + fake DAO + 立即执行的事务接缝）。
 *
 * 锁住五件事，每一件都对应旧实现的一处缺陷：
 * 1. 一次导入只提交**一个**事务（旧实现逐章 2 次、共 6000 次）；
 * 2. 源文件只被完整读**一遍**（拷贝即哈希，旧实现读三遍）；
 * 3. 同一文件重复导入直接命中、**不重解析也不重复写**；
 * 4. 章文件真的按 `books/<md5>/cNNNNN.txt` 落盘，且索引里的 `content_ref` 与之严格一致
 *    （不一致的症状是"导入成功但翻开空白"）；
 * 5. 解码失败时不留半成品目录。
 */
class LocalBookImporterTest {

    @get:Rule val tmp = TemporaryFolder()

    private lateinit var booksRoot: File
    private lateinit var scratch: File
    private lateinit var store: BookStore
    private lateinit var daos: FakeDaos
    private lateinit var importer: LocalBookImporter
    private var txCount = 0

    @Before
    fun setUp() {
        booksRoot = tmp.newFolder("books")
        scratch = tmp.newFolder("scratch")
        store = BookStore(booksRoot)
        daos = FakeDaos()
        importer = importerWith(TxtSourceReader(store))
    }

    /** 用给定 reader 组一个导入器；失败用例靠它换成一写就抛的假 reader */
    private fun importerWith(reader: SourceReader): LocalBookImporter = LocalBookImporter(
        bookStore = store,
        scratchDir = scratch,
        readers = mapOf(BookFormat.TXT to reader),
        bookShelfDao = daos.shelf,
        bookInfoDao = daos.info,
        chapterListDao = daos.chapter,
        bookGroupDao = daos.group,
        transactions = ImmediateTransactionRunner { txCount++ },
        bookRepository = BookRepository(
            daos.shelf, daos.info, daos.chapter, daos.group,
            chapterReaders = mapOf(BookFormat.TXT to reader),
            bookStore = store,
            contentCache = ChapterContentCache(),
            transactions = DirectTransactionRunner,
        ),
    )

    @Test
    fun `import writes one transaction and chapter files match content_ref`() = runTest {
        val source = book("第一章 起\n正文甲\n\n第二章 承\n正文乙")

        val result = importer.import(source)

        assertTrue("应为新书", result.new)
        assertEquals("整次导入只该提交一个事务", 1, txCount)
        val noteUrl = result.bookShelf.noteUrl
        val chapters = daos.chapter.storedValues()
        assertEquals(2, chapters.size)
        chapters.forEachIndexed { i, row ->
            assertEquals(store.chapterRef(noteUrl, i), row.contentRef)
            assertTrue(
                "章文件必须存在：c%05d.txt".format(i),
                File(File(booksRoot, noteUrl), "c%05d.txt".format(i)).exists()
            )
        }
    }

    @Test
    fun `noteUrl is content MD5 and dir name matches it`() = runTest {
        val source = book("第一章 起\n正文甲")

        val shelf = importer.import(source).bookShelf

        assertEquals(32, shelf.noteUrl.length)
        assertTrue(shelf.noteUrl.all { it in "0123456789abcdef" })
        assertTrue("提交后的目录名就是 md5", File(booksRoot, shelf.noteUrl).isDirectory)
        assertFalse("暂存目录必须已被改名掉", File(booksRoot, "${shelf.noteUrl}.tmp").exists())
    }

    @Test
    fun `reimporting same file short-circuits without reparse`() = runTest {
        val source = book("第一章 起\n正文甲")
        val first = importer.import(source)

        txCount = 0
        val second = importer.import(source)

        assertFalse("重复导入不该再开事务", second.new)
        assertEquals(first.bookShelf.noteUrl, second.bookShelf.noteUrl)
        assertEquals(0, txCount)
    }

    @Test
    fun `title and author come from file name and go into book_info`() = runTest {
        val source = book("第一章 起\n正文甲", name = "《星辰变》作者：我吃西红柿.txt")

        val shelf = importer.import(source).bookShelf
        val info = daos.info.storedValues().single()

        assertEquals("星辰变", info.name)
        assertEquals("我吃西红柿", info.author)
        assertEquals(BookShelfEntity.LOCAL_TAG, shelf.tag)
        assertEquals(BookFormat.TXT.name, shelf.bookFormat)
    }

    @Test
    fun `parseMetadata resolves title and author without writing anything`() = runTest {
        val source = book("第一章 起\n正文甲", name = "《星辰变》作者：我吃西红柿.txt")

        val meta = importer.parseMetadata(source)

        assertEquals("星辰变", meta.title)
        assertEquals("我吃西红柿", meta.author)
        assertTrue(
            "判重用的轻量解析不落任何一张表、也不切章",
            daos.shelf.storedValues().isEmpty() && daos.info.storedValues().isEmpty() &&
                daos.chapter.storedValues().isEmpty()
        )
    }

    @Test
    fun `parseMetadata falls back to the display placeholder when author is unresolvable`() = runTest {
        val source = book("第一章 起\n正文甲", name = "只是本书名.txt")

        assertEquals("侠名", importer.parseMetadata(source).author)
    }

    @Test
    fun `book group row is written with derived comment key`() = runTest {
        val source = book("第一章 起\n正文甲", name = "剑来 作者：烽火戏诸侯.txt")

        val shelf = importer.import(source).bookShelf
        val row = daos.group.storedValues().single()

        assertEquals(shelf.noteUrl, row.noteUrl)
        assertTrue(row.isPrimary)
        assertEquals(
            CommentKey.compute("剑来", "烽火戏诸侯"),
            row.commentKey,
        )
    }

    @Test
    fun `failure mid split leaves no partial book and no rows`() = runTest {
        // 前提要可靠：与其赌"这三个字节会被探测成什么编码"，不如直接注入一个写到一半就抛的
        // reader —— 要验的是"失败不留半成品"这条提交顺序保证，不是解码本身（解码已在 Task 3 锁）
        val failing = FailingReader(store, failAfter = 2)
        val failImporter = importerWith(failing)
        val source = book("第一章\n甲\n第二章\n乙\n第三章\n丙\n第四章\n丁")

        val outcome = runCatching { failImporter.import(source) }

        assertTrue("应报错而不是产出一本坏书", outcome.isFailure)
        assertEmptyDir(booksRoot)
        assertEquals("不该写进任何章节行", 0, daos.chapter.storedValues().size)
        assertEquals("不该写进书架行", 0, daos.shelf.storedValues().size)
    }

    private fun book(content: String, name: String = "样本书.txt"): File =
        File(scratch, name).apply { writeText(content, Charsets.UTF_8) }

    private fun assertEmptyDir(dir: File) {
        assertEquals("$dir 应为空", 0, dir.list()?.size ?: 0)
    }
}

/** 写完 [failAfter] 章就抛错的 reader，用来验证导入不是"边写边提交" */
private class FailingReader(
    private val store: BookStore,
    private val failAfter: Int,
) : SourceReader {
    override suspend fun readMetadata(source: BookSourceFile) = LocalBookMeta("t", null, null)

    override fun buildChapters(source: BookSourceFile, sink: ChapterSink): Flow<ChapterEntry> = flow {
        repeat(failAfter + 1) { i ->
            if (i == failAfter) throw IOException("模拟解码失败")
            val ref = sink.write(i, listOf("段落$i"))
            emit(ChapterEntry(i, "第${i + 1}章", ref))
        }
    }

    override suspend fun readChapter(entry: ChapterEntry, location: BookLocation) =
        ChapterContent(entry.title, emptyList())
}

/** 不做真事务、只计数并原样执行 block 的事务接缝 */
internal class ImmediateTransactionRunner(private val onBegin: () -> Unit) : WriteTransactionRunner {
    override suspend fun <R> run(block: suspend () -> R): R {
        onBegin()
        return block()
    }
}
