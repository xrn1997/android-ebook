package com.ebook.common.analyze.local

import com.ebook.common.domain.FileNameMetadata
import com.ebook.common.store.BookStore
import com.ebook.common.text.TextNormalizer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.File
import java.util.zip.ZipFile
import javax.inject.Inject

/**
 * EPUB 格式的 [SourceReader]（spec §7，M3）。
 *
 * 与 [TxtSourceReader] 并列，由 `Map<BookFormat, SourceReader>` 路由（仅导入链路）。
 * EPUB 是 ZIP 容器：`META-INF/container.xml` 定位 OPF → OPF 的 `metadata` 提供书名/作者、
 * `manifest` + `spine` 提供章节线性顺序与 XHTML 路径 → jsoup 从每个 XHTML 提取正文段落。
 *
 * 不使用 [ChapterSplitter]——EPUB 章节边界由 spine 显式声明，无需正则猜测。
 * 封面提取走 [extractCover]（在 `beginImport` 之后、`commitImport` 之前调用），
 * 与 [buildChapters] 分离是因为后者通过 [ChapterSink] 写章文件、不暴露暂存目录。
 */
class EpubSourceReader @Inject constructor(
    private val store: BookStore,
) : SourceReader {

    override suspend fun readMetadata(source: BookSourceFile): LocalBookMeta {
        val (title, author) = parseOpfMetadata(source.file)
        val resolved = resolveMetadata(title, author, source.file.name)
        return LocalBookMeta(title = resolved.first, author = resolved.second, coverFile = null)
    }

    override fun buildChapters(source: BookSourceFile, sink: ChapterSink): Flow<ChapterEntry> = flow {
        val zip = ZipFile(source.file)
        try {
            val opfPath = parseContainerXml(zip)
            val opfBytes = zip.readEntryBytes(opfPath)
                ?: throw IllegalStateException("EPUB 缺少 OPF 文件：$opfPath")
            val opfDoc = Jsoup.parse(opfBytes.toString(Charsets.UTF_8))
            val (manifest, spineIds) = parseManifestAndSpine(opfDoc, opfPath)

            var index = 0
            for (itemId in spineIds) {
                val item = manifest[itemId] ?: continue
                if (!isHtmlMediaType(item.mediaType)) continue

                val xhtmlBytes = zip.readEntryBytes(item.resolvedPath) ?: continue
                val xhtml = xhtmlBytes.toString(Charsets.UTF_8)
                val doc = Jsoup.parse(xhtml)

                val title = extractChapterTitle(doc, item.id)
                val paragraphs = extractParagraphs(doc)
                if (paragraphs.isEmpty()) continue

                emit(
                    ChapterEntry(
                        index = index,
                        title = title,
                        contentRef = sink.write(index, paragraphs),
                    ),
                )
                index++
            }
        } finally {
            zip.close()
        }
    }

    override suspend fun readChapter(entry: ChapterEntry, location: BookLocation): ChapterContent {
        val paragraphs = store.readParagraphs(location, entry.index)
        return ChapterContent(title = entry.title, paragraphs = paragraphs)
    }

    /**
     * 从 EPUB ZIP 提取封面图片写入暂存目录。
     *
     * 优先 EPUB3 的 `properties="cover-image"`，回落 EPUB2 的 `<meta name="cover" content="...">`。
     * 两种都找不到则不写任何文件——调用方据此判"无封面"。
     */
    override fun extractCover(source: BookSourceFile, targetDir: File) {
        val zip = ZipFile(source.file)
        try {
            val opfPath = parseContainerXml(zip)
            val opfBytes = zip.readEntryBytes(opfPath) ?: return
            val opfDoc = Jsoup.parse(opfBytes.toString(Charsets.UTF_8))
            val (manifest, _) = parseManifestAndSpine(opfDoc, opfPath)

            val coverItemId = opfDoc.selectFirst("manifest item[properties~=cover-image]")?.attr("id")
                ?: opfDoc.selectFirst("meta[name=cover]")?.attr("content")
                ?: return

            val item = manifest[coverItemId] ?: return
            val bytes = zip.readEntryBytes(item.resolvedPath) ?: return

            val ext = item.resolvedPath.substringAfterLast('.', "jpg")
            store.writeCover(targetDir, ext, bytes)
        } finally {
            zip.close()
        }
    }

    // ===== EPUB 容器解析 =====

    /**
     * 解析 `META-INF/container.xml`，取 OPF 文件的容器内路径。
     *
     * EPUB 规范要求 `container.xml` 存在且含 `<rootfile full-path="...">` 指向 OPF。
     */
    private fun parseContainerXml(zip: ZipFile): String {
        val entry = zip.getEntry("META-INF/container.xml")
            ?: throw IllegalStateException("非法 EPUB：缺少 META-INF/container.xml")
        val xml = zip.getInputStream(entry).bufferedReader().use { it.readText() }
        val doc = Jsoup.parse(xml, org.jsoup.parser.Parser.xmlParser())
        return doc.select("rootfile").attr("full-path")
            .ifEmpty { throw IllegalStateException("container.xml 缺少 rootfile full-path") }
    }

    /**
     * 从 OPF `<metadata>` 提取 dc:title 与 dc:creator。
     *
     * 返回 `(title?, author?)`，两者均可能为 null——此时由 [resolveMetadata] 回落到文件名解析。
     */
    private fun parseOpfMetadata(file: File): Pair<String?, String?> {
        val zip = ZipFile(file)
        try {
            val opfPath = parseContainerXml(zip)
            val opfBytes = zip.readEntryBytes(opfPath)
                ?: return null to null
            val opfDoc = Jsoup.parse(opfBytes.toString(Charsets.UTF_8))
            val title = opfDoc.selectFirst("metadata title")?.text()?.ifBlank { null }
            val author = opfDoc.selectFirst("metadata creator")?.text()?.ifBlank { null }
            return title to author
        } finally {
            zip.close()
        }
    }

    /**
     * 解析 OPF 的 `<manifest>` 与 `<spine>`。
     *
     * @return `(manifest map: id → ManifestItem, spine 线性顺序的 item id 列表)`
     */
    private fun parseManifestAndSpine(
        opfDoc: Document,
        opfPath: String,
    ): Pair<Map<String, ManifestItem>, List<String>> {
        val opfDir = opfPath.substringBeforeLast('/', "")
        val manifest = opfDoc.select("manifest item").associate { el ->
            val id = el.attr("id")
            val href = el.attr("href")
            val mediaType = el.attr("media-type")
            val resolved = if (opfDir.isNotEmpty()) "$opfDir/$href" else href
            id to ManifestItem(id, resolved, mediaType)
        }
        val spineIds = opfDoc.select("spine itemref").map { it.attr("idref") }
        return manifest to spineIds
    }

    // ===== XHTML 正文提取 =====

    /**
     * 章节标题：优先取 XHTML 内第一个 `<h1>`–`<h6>`，缺省用 item id（最终由阅读器显示层兜底）。
     */
    private fun extractChapterTitle(doc: Document, itemId: String): String {
        for (tag in HEADING_TAGS) {
            val heading = doc.selectFirst(tag)?.text()?.trim()
            if (!heading.isNullOrBlank()) return heading
        }
        return itemId
    }

    /**
     * 提取正文段落：按 `<p>` 标签拆分，每段经 [TextNormalizer.cleanParagraph] 规范化。
     *
     * 无 `<p>` 标签时回落 `body.wholeText()`，按换行切段。
     * 空段落丢弃（与 [TextNormalizer.cleanParagraphs] 语义一致）。
     */
    private fun extractParagraphs(doc: Document): List<String> {
        val body = doc.body()
        val pTags = body.select("p")
        val rawLines = if (pTags.isNotEmpty()) {
            pTags.map { it.text() }
        } else {
            body.wholeText().split('\n')
        }
        return TextNormalizer.cleanParagraphs(rawLines)
    }

    // ===== 元数据回落 =====

    /**
     * OPF 元数据可能为空（劣质 EPUB），此时回落到文件名解析（与 TXT 同一路径）。
     */
    private fun resolveMetadata(
        opfTitle: String?,
        opfAuthor: String?,
        fileName: String,
    ): Pair<String, String?> {
        val title = opfTitle ?: run {
            val parsed = FileNameMetadata.parse(fileName.removeSuffix(".epub"))
            parsed.title
        }
        val author = opfAuthor ?: run {
            val parsed = FileNameMetadata.parse(fileName.removeSuffix(".epub"))
            parsed.author
        }
        return title to author
    }

    // ===== 工具 =====

    private data class ManifestItem(val id: String, val resolvedPath: String, val mediaType: String)

    private fun ZipFile.readEntryBytes(entryPath: String): ByteArray? {
        val entry = getEntry(entryPath) ?: return null
        return getInputStream(entry).use { it.readBytes() }
    }

    private fun isHtmlMediaType(mediaType: String): Boolean =
        mediaType in HTML_MEDIA_TYPES

    companion object {
        private val HEADING_TAGS = listOf("h1", "h2", "h3", "h4", "h5", "h6")
        private val HTML_MEDIA_TYPES = setOf(
            "application/xhtml+xml",
            "text/html",
        )
    }
}
