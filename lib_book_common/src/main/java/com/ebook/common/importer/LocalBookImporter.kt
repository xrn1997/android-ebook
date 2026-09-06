package com.ebook.common.importer

import com.ebook.common.analyze.local.BookFormat
import com.ebook.common.analyze.local.BookLocation
import com.ebook.common.analyze.local.BookSourceFile
import com.ebook.common.analyze.local.ChapterEntry
import com.ebook.common.analyze.local.ChapterSink
import com.ebook.common.analyze.local.SourceReader
import com.ebook.common.di.ImportScratch
import com.ebook.common.domain.CommentKey
import com.ebook.common.domain.ParsedBookMeta
import com.ebook.common.repository.BookRepository
import com.ebook.common.store.BookStore
import com.ebook.common.store.WriteTransactionRunner
import com.ebook.db.dao.BookGroupDao
import com.ebook.db.dao.BookInfoDao
import com.ebook.db.dao.BookShelfDao
import com.ebook.db.dao.ChapterListDao
import com.ebook.db.entity.BookGroupEntity
import com.ebook.db.entity.BookInfoEntity
import com.ebook.db.entity.BookShelfEntity
import com.ebook.db.entity.ChapterListEntity
import com.ebook.db.entity.LocBookShelfEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withContext
import java.io.File
import java.security.DigestInputStream
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 本地书籍导入流水线（spec §6）。
 *
 * 三步一次成型：**拷贝即哈希**（源文件只完整读一遍，旧实现读三遍）→ 后台切分落章文件 →
 * **一个事务**批量写索引（旧实现逐章 2 次、2000 章就是 4000 次提交）。
 *
 * 提交顺序是"先改名章文件目录、后写数据库"，反过来的话一旦数据库写入失败就会留下指向
 * 不存在目录的索引行——用户看到的是"书架上有本书但翻开空白"，比反过来那种"孤儿目录"
 * 更难解释也更难回收（孤儿目录由 [BookStore.reconcile] 静默清掉即可）。
 */
@Singleton
class LocalBookImporter @Inject constructor(
    private val bookStore: BookStore,
    /** 源文件暂存目录；生产注入 `cacheDir/import`，测试注入临时目录 */
    @ImportScratch private val scratchDir: File,
    private val readers: @JvmSuppressWildcards Map<BookFormat, SourceReader>,
    private val bookShelfDao: BookShelfDao,
    private val bookInfoDao: BookInfoDao,
    private val chapterListDao: ChapterListDao,
    private val bookGroupDao: BookGroupDao,
    private val transactions: WriteTransactionRunner,
    private val bookRepository: BookRepository,
) {

    /**
     * @param onChapter 每落一章回调一次，供 UI 显示进度（spec §6 要求逐章进度而非布尔遮罩）
     * @return `new = false` 表示同一份内容已在书架上，此时不产生任何写入
     */
    suspend fun import(source: File, onChapter: (chaptersDone: Int) -> Unit = {}): LocBookShelfEntity =
        withContext(Dispatchers.IO) {
            val (format, reader) = readerFor(source)

            val staged = File(scratchDir, "src-${System.nanoTime()}.${format.extension}")
            staged.parentFile?.mkdirs()
            try {
                val md5 = copyAndHash(source, staged)
                val existing = bookShelfDao.getBookByUrl(md5)
                if (existing != null) {
                    // chapterList/bookInfo 是 @Ignore 字段，不回填会让阅读器算出 0 页（旧实现同样的理由）
                    existing.chapterList = chapterListDao.getChaptersForBook(existing.noteUrl)
                    existing.bookInfo = bookInfoDao.getBookInfoByUrl(existing.noteUrl)
                    return@withContext LocBookShelfEntity(false, existing)
                }

                val charset = reader.probeCharset(staged)
                val meta = reader.readMetadata(BookSourceFile(source, charset))
                val staging = bookStore.beginImport(md5)
                reader.extractCover(BookSourceFile(staged, charset), staging)
                val chapters = try {
                    reader.buildChapters(BookSourceFile(staged, charset), sink(staging, md5))
                        .onEach { onChapter(it.index + 1) }
                        .toList()
                        .map { it.toRow(md5) }
                } catch (t: Throwable) {
                    bookStore.abortImport(staging)
                    throw t
                }
                if (chapters.isEmpty()) {
                    bookStore.abortImport(staging)
                    throw IllegalStateException("未能从 ${source.name} 切出任何章节")
                }

                bookStore.commitImport(staging, md5)
                val coverUrl = bookStore.bookDir(BookLocation(md5, format))
                    .listFiles { f -> f.isFile && f.name.startsWith("cover.") }
                    ?.firstOrNull()?.absolutePath ?: String()
                val shelf = BookShelfEntity(
                    noteUrl = md5,
                    tag = BookShelfEntity.LOCAL_TAG,
                    finalDate = System.currentTimeMillis(),
                    bookFormat = format.name,
                    textCharset = charset,
                )
                val info = BookInfoEntity(
                    name = meta.title,
                    tag = BookShelfEntity.LOCAL_TAG,
                    noteUrl = md5,
                    chapterUrl = String(),
                    finalRefreshData = System.currentTimeMillis(),
                    coverUrl = coverUrl,
                    author = meta.author ?: DEFAULT_AUTHOR,
                    introduce = String(),
                    origin = String(),
                    status = String(),
                )
                val group = BookGroupEntity(
                    commentKey = CommentKey.compute(info.name, info.author),
                    noteUrl = md5,
                    isPrimary = true,
                )
                try {
                    transactions.run {
                        bookShelfDao.insert(shelf)
                        bookInfoDao.insert(info)
                        chapterListDao.insertAll(chapters)
                        bookGroupDao.insert(group)
                    }
                } catch (t: Throwable) {
                    // 库写不进去就把章文件一并撤掉，别在磁盘上留一本"库里不存在"的书
                    bookStore.deleteBook(BookLocation(md5, format))
                    throw t
                }

                shelf.chapterList = chapters
                shelf.bookInfo = info
                bookRepository.publishAdded(shelf)
                LocBookShelfEntity(true, shelf)
            } finally {
                staged.delete()
            }
        }

    /**
     * 轻量解析源文件的书名/作者，不做章节切分、不写数据库。
     *
     * 供导入前判重使用：拿到标题与作者即可算 `comment_key`，与书架各条目的当前主键比对
     * （见 `DuplicateBookDetector.findMatchesFor`），命中时让用户处置，避免盲目产生重复条目。
     *
     * 直读源文件、不做暂存拷贝：[SourceReader.probeCharset] 只吃文件头部若干字节，
     * [SourceReader.readMetadata] 也只读元数据，整本拷贝（6MB 量级）换不来任何一致性，
     * 还违背 spec §6「源文件只完整读一遍」。
     */
    suspend fun parseMetadata(source: File): ParsedBookMeta = withContext(Dispatchers.IO) {
        val (_, reader) = readerFor(source)
        val charset = reader.probeCharset(source)
        val meta = reader.readMetadata(BookSourceFile(source, charset))
        ParsedBookMeta(title = meta.title, author = meta.author ?: DEFAULT_AUTHOR)
    }

    /** 按扩展名定格式并取对应 reader；导入与轻量解析共用同一份路由规则 */
    private fun readerFor(source: File): Pair<BookFormat, SourceReader> {
        val format = BookFormat.fromExtension(source.extension)
            ?: throw IllegalArgumentException("不支持的本地书格式：${source.extension}")
        val reader = readers[format]
            ?: throw IllegalStateException("没有 $format 的解析器，可用格式：${readers.keys}")
        return format to reader
    }

    /** 往暂存目录写章文件，返回的是**改名后**的 content_ref（暂存目录只是物理位置） */
    private fun sink(staging: File, bookId: String) = object : ChapterSink {
        override suspend fun write(index: Int, paragraphs: List<String>): String {
            bookStore.writeChapterRaw(staging, index, paragraphs.joinToString("\n"))
            return bookStore.chapterRef(bookId, index)
        }
    }

    private fun ChapterEntry.toRow(noteUrl: String) = ChapterListEntity(
        noteUrl = noteUrl,
        durChapterIndex = index,
        contentRef = contentRef,
        durChapterName = title,
        tag = BookShelfEntity.LOCAL_TAG,
    )

    /** 单遍流式拷贝并顺手算 MD5：省掉旧实现那次独立的全文件读 */
    private fun copyAndHash(from: File, to: File): String {
        val digest = MessageDigest.getInstance("MD5")
        from.inputStream().buffered(BUFFER_BYTES).use { input ->
            DigestInputStream(input, digest).use { digested ->
                to.outputStream().buffered(BUFFER_BYTES).use(digested::copyTo)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val BUFFER_BYTES = 64 * 1024
        /** 仅用于显示；不参与 comment_key 计算（见 CommentKey 的占位词处理） */
        const val DEFAULT_AUTHOR = "侠名"
    }
}
