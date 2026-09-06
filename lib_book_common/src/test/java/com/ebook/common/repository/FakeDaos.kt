package com.ebook.common.repository

import com.ebook.common.store.WriteTransactionRunner
import com.ebook.db.dao.BookGroupDao
import com.ebook.db.dao.BookInfoDao
import com.ebook.db.dao.BookShelfDao
import com.ebook.db.dao.ChapterListDao
import com.ebook.db.entity.BookGroupEntity
import com.ebook.db.entity.BookInfoEntity
import com.ebook.db.entity.BookShelfEntity
import com.ebook.db.entity.BookShelfFullInfo
import com.ebook.db.entity.ChapterListEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 手写 Fake DAO 工厂：把 4 个 Fake DAO 实例绑在一起，测试 setUp 里一行 `val daos = FakeDaos()` 即可。
 *
 * 从 `BookRepositoryTest` 抽出是因为 `LocalContentReadTest` 也需要同一套 Fake——
 * 多个测试文件共享时，`private class` 不可见，改成 `internal class` 放在独立文件里最干净。
 */
internal class FakeDaos {
    val shelf = FakeBookShelfDao()
    val info = FakeBookInfoDao()
    val chapter = FakeChapterListDao()
    val group = FakeBookGroupDao()
}

internal class FakeBookShelfDao : BookShelfDao {
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

    /** 返回当前内存中的所有行，供测试断言 */
    fun storedValues(): List<BookShelfEntity> = shelfByNoteUrl.values.toList()
}

internal class FakeBookInfoDao : BookInfoDao {
    private val infoByNoteUrl = linkedMapOf<String, BookInfoEntity>()

    override suspend fun getBookInfoByUrl(noteUrl: String): BookInfoEntity? = infoByNoteUrl[noteUrl]
    override suspend fun insert(bookInfo: BookInfoEntity) {
        infoByNoteUrl[bookInfo.noteUrl] = bookInfo
    }

    override suspend fun deleteByUrl(noteUrl: String) {
        infoByNoteUrl.remove(noteUrl)
    }

    fun storedValues(): List<BookInfoEntity> = infoByNoteUrl.values.toList()
}

internal class FakeChapterListDao : ChapterListDao {
    private val chapterByUrl = linkedMapOf<String, ChapterListEntity>() // key = content_ref（主键）

    override suspend fun getChaptersForBook(bookNoteUrl: String): List<ChapterListEntity> =
        chapterByUrl.values.filter { it.noteUrl == bookNoteUrl }.sortedBy { it.durChapterIndex }

    override suspend fun getChapterByUrl(chapterUrl: String): ChapterListEntity? = chapterByUrl[chapterUrl]

    override suspend fun insertAll(chapters: List<ChapterListEntity>) {
        chapters.forEach { chapterByUrl[it.contentRef] = it }
    }

    override suspend fun countChaptersForBook(bookNoteUrl: String): Int =
        chapterByUrl.values.count { it.noteUrl == bookNoteUrl }

    override suspend fun deleteChaptersForBook(bookNoteUrl: String) {
        chapterByUrl.entries.removeAll { it.value.noteUrl == bookNoteUrl }
    }

    fun storedValues(): List<ChapterListEntity> = chapterByUrl.values.toList()
}

/**
 * [BookGroupDao] 的内存实现。M2 扩展了合并/拆分/切主键方法。
 */
internal class FakeBookGroupDao : BookGroupDao {
    private val rows = linkedMapOf<String, BookGroupEntity>() // key = "commentKey|noteUrl"

    override suspend fun insert(row: BookGroupEntity) {
        rows["${row.commentKey}|${row.noteUrl}"] = row
    }

    override suspend fun deleteFor(noteUrl: String) {
        rows.entries.removeAll { it.value.noteUrl == noteUrl }
    }

    override suspend fun getKeysForNoteUrl(noteUrl: String): List<String> =
        rows.values.filter { it.noteUrl == noteUrl }.map { it.commentKey }

    override suspend fun getPrimaryForNoteUrl(noteUrl: String): String? =
        rows.values.firstOrNull { it.noteUrl == noteUrl && it.isPrimary }?.commentKey

    override suspend fun getPrimaryRows(): List<BookGroupEntity> =
        rows.values.filter { it.isPrimary }

    override suspend fun getAllForNoteUrl(noteUrl: String): List<BookGroupEntity> =
        rows.values.filter { it.noteUrl == noteUrl }

    override suspend fun deleteSpecific(noteUrl: String, commentKey: String) {
        rows.remove("$commentKey|$noteUrl")
    }

    override suspend fun clearPrimary(noteUrl: String) {
        rows.entries.filter { it.value.noteUrl == noteUrl }.forEach {
            it.value.isPrimary = false
        }
    }

    override suspend fun promotePrimary(noteUrl: String, commentKey: String) {
        rows["$commentKey|$noteUrl"]?.isPrimary = true
    }

    override suspend fun addSecondary(row: BookGroupEntity) {
        val key = "${row.commentKey}|${row.noteUrl}"
        if (key !in rows) {
            rows[key] = row.copy(isPrimary = false)
        }
    }

    fun storedValues(): List<BookGroupEntity> = rows.values.toList()
}

/**
 * 直接执行 block 的 [WriteTransactionRunner]：纯 JVM 测试里没有 Room 事务，
 * 只需要接缝能跑通（需要计数的用例另用 `ImmediateTransactionRunner`）。
 */
internal object DirectTransactionRunner : WriteTransactionRunner {
    override suspend fun <R> run(block: suspend () -> R): R = block()
}
