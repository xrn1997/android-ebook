package com.ebook.common.store

import com.ebook.common.analyze.local.BookLocation
import com.ebook.common.util.treeSize
import java.io.File

/**
 * 内容仓库的占用统计，由 [BookStore.storageUsage] 一次遍历得出。
 *
 * 两个量必须成对取（缓存页同一行同时显示它们），所以是一个类型而不是 `Pair`。
 *
 * @param bytes 章文件字节总数，含 `.tmp` 暂存与散落文件——它们确实占着磁盘
 * @param bookCount 书目目录数，不含 `.tmp` 半成品
 */
data class StorageUsage(val bytes: Long, val bookCount: Int)

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
     * 删一章正文文件（强制重抓单章用）。
     *
     * 与 [deleteBook] 的区别就是这一章与整本书：重下第 N 章只需让第 N 章的缓存失效，
     * 拿 deleteBook 会把该书其余已缓存章节一并清掉。
     */
    fun deleteChapter(location: BookLocation, index: Int) {
        chapterFile(location, index).delete()
    }

    /**
     * 内容仓库占用统计（字节 + 册数），**一次遍历**得到。
     *
     * 由本类回答而不是让调用方自行遍历——「一本书一个子目录、章文件怎么命名」是内容仓库的内部知识。
     * 合成一个查询的理由是纯粹的性能：缓存管理页每次刷新都要这两个数，分成两个方法就会把
     * 整棵章文件树走两遍（几十本书 × 数千章文件时是能感知的 IO）。
     *
     * 口径：[StorageUsage.bytes] 是全部章文件之和，含导入中断的 `.tmp` 暂存与散落文件（它们确实占着磁盘）；
     * [StorageUsage.bookCount] 只数书目目录——半成品不是一本「看得见的书」，算进去会让缓存页报出
     * 书架上并不存在的册数。设置页据此**单列一行**呈现，不在该页删书（清理缓存从不碰这里）。
     */
    fun storageUsage(): StorageUsage {
        var bytes = 0L
        var books = 0
        for (entry in booksRoot.listFiles().orEmpty()) {
            if (entry.isDirectory) {
                if (!entry.name.endsWith(TMP_SUFFIX)) books++
                bytes += entry.treeSize()
            } else {
                bytes += entry.length()
            }
        }
        return StorageUsage(bytes, books)
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

        /**
         * 缓存失效用的书级片段：`content_ref` 形如 `books/<bookId>/cNNNNN.txt`，
         * 按 `/<bookId>/` 剔除即覆盖一本书的全部条目。
         *
         * 只有正文缓存 [ChapterContentCache] 用它——该缓存的键由 `BookRepository.loadChapter`
         * 统一取 `chapterRef(noteUrl, index)`，本地书与网络书都是上面这个形状，故按书剔除两者都命中。
         *
         * **排版缓存不要照搬这条规则**：`ChapterLayoutCache` 的键取自 `chapter_list.content_ref`，
         * 网络书那一列存的是章节 URL，不含 `/<bookId>/` 片段，按书剔除对网络书永远匹配不上
         * （而「强制刷新缓存」恰恰只发生在网络书上）。它的失效改由键内的内容指纹承担。
         */
        fun cacheMarker(bookId: String): String = "/$bookId/"
    }
}
