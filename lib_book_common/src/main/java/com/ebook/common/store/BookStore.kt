package com.ebook.common.store

import com.ebook.common.analyze.local.BookLocation
import java.io.File

/**
 * 本地书籍内容仓库（spec §4）：`filesDir/books/<bookId>/cNNNNN.txt`，一章一个文件。
 *
 * 只收一个 [booksRoot] 目录参数、不碰 `Context`，因此整本书内容基座可在纯 JVM 下测试。
 * 生产环境由 `ContentStoreModule` 传入 `File(context.filesDir, "books")`。
 *
 * **章文件存 UTF-8 重编码的规范化前文本**：无损指文本层（字符不缺），字节层统一 UTF-8，
 * 于是读取侧不必再管源编码。段落之间以单个 LF 分隔，不写缩进（缩进是表现层，见 spec §8）。
 */
class BookStore(private val booksRoot: File) {

    /** content_ref 是自包含的 filesDir 相对路径，读取方拿到即用、不回查书级字段（spec §4） */
    fun chapterRef(bookId: String, index: Int): String =
        "$DIR_NAME/$bookId/${chapterFileName(index)}"

    fun bookDir(location: BookLocation): File = File(booksRoot, location.bookId)

    fun chapterFile(location: BookLocation, index: Int): File =
        File(bookDir(location), chapterFileName(index))

    fun hasChapter(location: BookLocation, index: Int): Boolean = chapterFile(location, index).exists()

    /** 写一章正文：段落以单个 LF 连接，UTF-8 */
    fun writeChapter(location: BookLocation, index: Int, paragraphs: List<String>) {
        writeChapterRaw(bookDir(location), index, paragraphs.joinToString("\n"))
    }

    /** 读一章正文并切回段落；文件不存在返回空表（调用方据此判"内容缺失"而非抛异常） */
    fun readParagraphs(location: BookLocation, index: Int): List<String> {
        val file = chapterFile(location, index)
        if (!file.exists()) return emptyList()
        return file.readText(Charsets.UTF_8).split('\n').filter { it.isNotEmpty() }
    }

    /** 导入暂存目录：带 `.tmp` 后缀，未改名的目录在对账时一律作废（spec §4 原子提交点） */
    fun beginImport(bookId: String): File =
        File(booksRoot, "$bookId$TMP_SUFFIX").apply {
            if (exists()) deleteRecursively()
            mkdirs()
        }

    /** 往暂存目录直接写一章（导入期还没有 BookLocation） */
    fun writeChapterRaw(dir: File, index: Int, text: String) {
        val target = File(dir, chapterFileName(index))
        target.parentFile?.mkdirs()
        target.writeText(text, Charsets.UTF_8)
    }

    /**
     * 写封面图片到指定目录（spec §4：`books/<bookId>/cover.<ext>`）。
     *
     * 导入期 [targetDir] 是暂存目录（`commitImport` 后随目录改名到正式位置）；
     * 调用方只需在 `commitImport` 之后按 [coverRef] 定位封面文件。
     */
    fun writeCover(targetDir: File, ext: String, bytes: ByteArray) {
        val target = File(targetDir, "cover.$ext")
        target.parentFile?.mkdirs()
        target.writeBytes(bytes)
    }

    fun commitImport(staging: File, bookId: String) {
        val final = File(booksRoot, bookId)
        if (final.exists()) final.deleteRecursively()
        // renameTo 同分区是原子操作：要么整本可见，要么完全不存在，不会有半本被读到
        if (!staging.renameTo(final)) {
            staging.copyRecursively(final, overwrite = true)
            staging.deleteRecursively()
        }
    }

    fun abortImport(staging: File) {
        staging.deleteRecursively()
    }

    fun deleteBook(location: BookLocation) {
        bookDir(location).deleteRecursively()
    }

    /**
     * 对账：删除"DB 里已不存在的书"的目录、所有 `.tmp` 残留目录，以及散落在仓库根的文件。
     *
     * 存在理由是删书与导入中断都会留下无主文件，而没有对账就没人再发现它们（用户侧表现为
     * "占了空间却看不见书"）。 loose 文件一并清掉是因为正常路径不会在 `books/` 根下产生文件。
     */
    fun reconcile(liveBookIds: Set<String>) {
        booksRoot.listFiles()?.forEach { entry ->
            when {
                entry.isDirectory && entry.name.endsWith(TMP_SUFFIX) -> entry.deleteRecursively()
                entry.isDirectory && entry.name !in liveBookIds -> entry.deleteRecursively()
                entry.isFile -> entry.delete()
            }
        }
    }

    companion object {
        /** 仓库目录名，同时是 content_ref 的首段 */
        const val DIR_NAME = "books"
        private const val TMP_SUFFIX = ".tmp"

        /** 序号零填充到 5 位，保证字典序等于数值序；上限 99999 章足够 */
        fun chapterFileName(index: Int): String = "c%05d.txt".format(index)
    }
}
