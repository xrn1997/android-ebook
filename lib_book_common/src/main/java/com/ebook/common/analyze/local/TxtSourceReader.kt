package com.ebook.common.analyze.local

import com.ebook.common.domain.FileNameMetadata
import com.ebook.common.store.BookStore
import com.ebook.common.text.EncodingProbe
import com.ebook.common.text.StrictTextReader
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.File
import javax.inject.Inject

/**
 * TXT 格式的 [SourceReader]（spec §7）。M3 的 `EpubSourceReader` 与它并列，
 * 由 `Map<BookFormat, SourceReader>` 路由（仅导入链路）。网络书走 `JsoupSourceReader`，
 * 它实现 [ChapterReader] 而非 `SourceReader`，由 `Map<BookFormat, ChapterReader>` 路由。
 *
 * 源文件按 `charset` **严格解码**（[StrictTextReader]）后逐行喂给 [ChapterSplitter]，
 * 章文件则以 UTF-8 落盘——于是读取路径与源编码彻底解耦。
 */
class TxtSourceReader @Inject constructor(
    private val store: BookStore,
    private val splitter: ChapterSplitter = ChapterSplitter(),
) : SourceReader {

    override suspend fun readMetadata(source: BookSourceFile): LocalBookMeta =
        readMetadataOf(source.file)

    override fun buildChapters(source: BookSourceFile, sink: ChapterSink): Flow<ChapterEntry> = flow {
        // 原文行直接喂切分器：清洗属于读取层（spec §4 §8），此处落盘的必须是"切分后、清洗前"
        val lines = StrictTextReader.lines(source.file, source.charset)
        splitter.split(lines).collect { raw ->
            emit(ChapterEntry(index = raw.index, title = raw.title, contentRef = sink.write(raw.index, raw.paragraphs)))
        }
    }

    override suspend fun readChapter(entry: ChapterEntry, location: BookLocation): ChapterContent {
        val paragraphs = store.readParagraphs(location, entry.index)
        return ChapterContent(title = entry.title, paragraphs = paragraphs)
    }

    /** 探测源文件编码；导入器在拷贝那一次读里调用它，结果固化进 `book_shelf.text_charset` */
    override fun probeCharset(file: File): String {
        val headSize = minOf(EncodingProbe.HEAD_BYTES.toLong(), file.length()).toInt()
        val head = ByteArray(headSize)
        file.inputStream().use { it.read(head) }
        return EncodingProbe.detect(head, headSize)
    }

    companion object {
        /**
         * 元数据只能来自文件名——TXT 没有内嵌元数据，旧格式连作者都没有。
         * 作者解不出时返回 null，由显示层填「侠名」，占位词不参与 `comment_key`（spec §8 §9.1）。
         */
        fun readMetadataOf(file: File): LocalBookMeta {
            val parsed = FileNameMetadata.parse(file.name)
            return LocalBookMeta(title = parsed.title, author = parsed.author, coverFile = null)
        }
    }
}
