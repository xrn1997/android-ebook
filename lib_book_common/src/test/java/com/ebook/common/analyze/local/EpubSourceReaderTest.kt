package com.ebook.common.analyze.local

import com.ebook.common.store.BookStore
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipOutputStream

/**
 * [EpubSourceReader] 的往返测试：构造最小 EPUB ZIP → 元数据/目录/封面/正文全链路。
 *
 * 用 [ZipOutputStream] 手工拼 EPUB 容器（container.xml → OPF → XHTML），
 * 不依赖真实 EPUB 文件——测试自包含、不引入 assets 依赖。
 */
class EpubSourceReaderTest {

    @get:Rule val tmp = TemporaryFolder()

    private lateinit var booksRoot: File
    private lateinit var store: BookStore
    private lateinit var reader: EpubSourceReader

    private val bookId = "b".repeat(32)
    private val location = BookLocation(bookId, BookFormat.EPUB)

    @Before
    fun setUp() {
        booksRoot = tmp.newFolder("books")
        store = BookStore(booksRoot)
        reader = EpubSourceReader(store)
    }

    // ===== 元数据 =====

    @Test
    fun `readMetadata extracts title and author from OPF`() = runTest {
        val epub = minimalEpub(
            opfTitle = "星辰变",
            opfAuthor = "我吃西红柿",
            chapters = listOf("第一章" to "正文甲"),
        )

        val meta = reader.readMetadata(BookSourceFile(epub, "UTF-8"))

        assertEquals("星辰变", meta.title)
        assertEquals("我吃西红柿", meta.author)
        assertNull(meta.coverFile)
    }

    @Test
    fun `readMetadata falls back to filename when OPF metadata is empty`() = runTest {
        val epub = File(tmp.root, "《斗破苍穹》作者：天蚕土豆.epub").apply {
            writeMinimalEpub(this, opfTitle = null, opfAuthor = null,
                chapters = listOf("第一章" to "正文"))
        }

        val meta = reader.readMetadata(BookSourceFile(epub, "UTF-8"))

        assertEquals("斗破苍穹", meta.title)
        assertEquals("天蚕土豆", meta.author)
    }

    // ===== 章节提取 =====

    @Test
    fun `buildChapters emits entries in spine order with correct content refs`() = runTest {
        val epub = minimalEpub(
            opfTitle = "测试书",
            opfAuthor = "作者",
            chapters = listOf(
                "第一章 起" to "正文甲\n正文乙",
                "第二章 承" to "正文丙",
                "第三章 转" to "正文丁",
            ),
        )

        val entries = reader.buildChapters(BookSourceFile(epub, "UTF-8"), sink(bookId)).toList()

        assertEquals(3, entries.size)
        assertEquals(listOf(0, 1, 2), entries.map { it.index })
        assertEquals(listOf("第一章 起", "第二章 承", "第三章 转"), entries.map { it.title })
        assertEquals(
            listOf(
                store.chapterRef(bookId, 0),
                store.chapterRef(bookId, 1),
                store.chapterRef(bookId, 2),
            ),
            entries.map { it.contentRef },
        )
    }

    @Test
    fun `buildChapters writes paragraphs readable via readChapter`() = runTest {
        val epub = minimalEpub(
            opfTitle = "测试书",
            opfAuthor = "作者",
            chapters = listOf("第一章" to "段落一\n段落二"),
        )
        val entries = reader.buildChapters(BookSourceFile(epub, "UTF-8"), sink(bookId)).toList()

        val content = reader.readChapter(entries.first(), location)

        assertEquals("第一章", content.title)
        assertEquals(listOf("段落一", "段落二"), content.paragraphs)
    }

    @Test
    fun `buildChapters skips empty chapters`() = runTest {
        val epub = minimalEpub(
            opfTitle = "测试书",
            opfAuthor = "作者",
            chapters = listOf(
                "第一章" to "有内容",
                "空章" to "<body></body>",
                "第三章" to "也有内容",
            ),
        )

        val entries = reader.buildChapters(BookSourceFile(epub, "UTF-8"), sink(bookId)).toList()

        assertEquals(2, entries.size)
        assertEquals(listOf("第一章", "第三章"), entries.map { it.title })
    }

    @Test
    fun `buildChapters uses heading for chapter title when present`() = runTest {
        val epub = minimalEpub(
            opfTitle = "测试书",
            opfAuthor = "作者",
            chapters = listOf("第一章" to "<h2>真正的标题</h2><p>正文</p>"),
        )

        val entries = reader.buildChapters(BookSourceFile(epub, "UTF-8"), sink(bookId)).toList()

        assertEquals("真正的标题", entries.first().title)
    }

    // ===== 封面提取 =====

    @Test
    fun `extractCover extracts EPUB3 cover-image`() {
        val staging = store.beginImport(bookId)
        val coverBytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
        val epub = minimalEpubWithCover(
            opfTitle = "测试书",
            coverBytes = coverBytes,
            coverMethod = CoverMethod.EPUB3,
            chapters = listOf("第一章" to "正文"),
        )

        reader.extractCover(BookSourceFile(epub, "UTF-8"), staging)

        val coverFile = File(staging, "cover.jpg")
        assertTrue("封面文件应存在", coverFile.exists())
        assertArrayEquals(coverBytes, coverFile.readBytes())
        staging.deleteRecursively()
    }

    @Test
    fun `extractCover extracts EPUB2 meta cover`() {
        val staging = store.beginImport(bookId)
        val coverBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)
        val epub = minimalEpubWithCover(
            opfTitle = "测试书",
            coverBytes = coverBytes,
            coverMethod = CoverMethod.EPUB2,
            chapters = listOf("第一章" to "正文"),
        )

        reader.extractCover(BookSourceFile(epub, "UTF-8"), staging)

        val coverFile = File(staging, "cover.jpg")
        assertTrue("封面文件应存在", coverFile.exists())
        assertArrayEquals(coverBytes, coverFile.readBytes())
        staging.deleteRecursively()
    }

    @Test
    fun `extractCover writes nothing when no cover exists`() {
        val staging = store.beginImport(bookId)
        val epub = minimalEpub(
            opfTitle = "测试书",
            opfAuthor = "作者",
            chapters = listOf("第一章" to "正文"),
        )

        reader.extractCover(BookSourceFile(epub, "UTF-8"), staging)

        val coverFiles = staging.listFiles { f -> f.name.startsWith("cover.") }
        assertNotNull(coverFiles)
        assertEquals(0, coverFiles!!.size)
        staging.deleteRecursively()
    }

    // ===== 辅助方法 =====

    private fun sink(bookId: String) = object : ChapterSink {
        override suspend fun write(index: Int, paragraphs: List<String>): String {
            store.writeChapter(BookLocation(bookId, BookFormat.EPUB), index, paragraphs)
            return store.chapterRef(bookId, index)
        }
    }

    private fun minimalEpub(
        opfTitle: String,
        opfAuthor: String?,
        chapters: List<Pair<String, String>>,
    ): File = File(tmp.root, "test.epub").apply {
        writeMinimalEpub(this, opfTitle, opfAuthor, chapters)
    }

    private enum class CoverMethod { EPUB3, EPUB2 }

    private fun minimalEpubWithCover(
        opfTitle: String,
        coverBytes: ByteArray,
        coverMethod: CoverMethod,
        chapters: List<Pair<String, String>>,
    ): File = File(tmp.root, "test_cover.epub").apply {
        writeMinimalEpub(this, opfTitle, null, chapters, coverBytes, coverMethod)
    }

    private fun writeMinimalEpub(
        target: File,
        opfTitle: String?,
        opfAuthor: String?,
        chapters: List<Pair<String, String>>,
        coverBytes: ByteArray? = null,
        coverMethod: CoverMethod? = null,
    ) {
        ZipOutputStream(FileOutputStream(target)).use { zip ->
            zip.putNextEntry(java.util.zip.ZipEntry("META-INF/container.xml"))
            zip.write(CONTAINER_XML.toByteArray())
            zip.closeEntry()

            val manifestItems = buildString {
                chapters.forEachIndexed { idx, _ ->
                    val id = "ch${idx + 1}"
                    append("""<item id="$id" href="chapter${idx + 1}.xhtml" media-type="application/xhtml+xml"/>""")
                    append('\n')
                }
                if (coverBytes != null) {
                    append("""<item id="cover" href="cover.jpg" media-type="image/jpeg"/>""")
                    append('\n')
                }
            }

            val spineItems = chapters.indices.joinToString("\n") { """<itemref idref="ch${it + 1}"/>""" }

            val coverMeta = when (coverMethod) {
                CoverMethod.EPUB2 -> """<meta name="cover" content="cover"/>"""
                else -> ""
            }

            val coverProperties = if (coverMethod == CoverMethod.EPUB3)
                """properties="cover-image" """ else ""
            val coverManifest = if (coverBytes != null)
                """<item id="cover" href="cover.jpg" media-type="image/jpeg" $coverProperties/>"""
            else ""

            val titleXml = if (opfTitle != null) "<title>$opfTitle</title>" else ""
            val authorXml = if (opfAuthor != null) "<creator>$opfAuthor</creator>" else ""

            val opf = """<?xml version="1.0" encoding="UTF-8"?>
<package version="3.0" xmlns="http://www.idpf.org/2007/opf">
<metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
$titleXml
$authorXml
$coverMeta
</metadata>
<manifest>
$manifestItems
$coverManifest
</manifest>
<spine>
$spineItems
</spine>
</package>"""

            zip.putNextEntry(java.util.zip.ZipEntry("content.opf"))
            zip.write(opf.toByteArray())
            zip.closeEntry()

            chapters.forEachIndexed { idx, (title, body) ->
                val bodyHtml = if ('<' in body) body
                else "<h1>$title</h1>\n" + body.split('\n').joinToString("\n") { "<p>$it</p>" }
                val xhtml = """<?xml version="1.0" encoding="UTF-8"?>
<html xmlns="http://www.w3.org/1999/xhtml">
<head><title>$title</title></head>
<body>
$bodyHtml
</body>
</html>"""
                zip.putNextEntry(java.util.zip.ZipEntry("chapter${idx + 1}.xhtml"))
                zip.write(xhtml.toByteArray())
                zip.closeEntry()
            }

            if (coverBytes != null) {
                zip.putNextEntry(java.util.zip.ZipEntry("cover.jpg"))
                zip.write(coverBytes)
                zip.closeEntry()
            }
        }
    }

    private companion object {
        const val CONTAINER_XML = """<?xml version="1.0" encoding="UTF-8"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
<rootfiles>
<rootfile full-path="content.opf" media-type="application/oebps-package+xml"/>
</rootfiles>
</container>"""
    }
}
