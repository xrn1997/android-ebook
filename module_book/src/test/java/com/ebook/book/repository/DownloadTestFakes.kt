package com.ebook.book.repository

import com.ebook.db.dao.BookShelfDao
import com.ebook.db.dao.DownloadChapterDao
import com.ebook.db.dao.PausedBookDao
import com.ebook.db.entity.BookShelfEntity
import com.ebook.db.entity.BookShelfFullInfo
import com.ebook.db.entity.DownloadChapterEntity
import com.ebook.db.entity.PausedBookEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf

internal class FakeShelfDao : BookShelfDao {
    private val books = linkedMapOf<String, BookShelfEntity>()
    val fullInfoFlow = MutableStateFlow<List<BookShelfFullInfo>>(emptyList())

    fun seed(book: BookShelfEntity) {
        books[book.noteUrl] = book
    }

    override suspend fun getAllBooksFullInfo(): List<BookShelfFullInfo> = emptyList()
    override fun getAllBooksFullInfoFlow(): Flow<List<BookShelfFullInfo>> = fullInfoFlow
    override suspend fun getBookFullInfoByUrl(noteUrl: String): BookShelfFullInfo? = null
    override fun getAllBooksFlow(): Flow<List<BookShelfEntity>> = flowOf(books.values.toList())
    override suspend fun getAllBooks(): List<BookShelfEntity> = books.values.toList()
    override suspend fun getBookByUrl(noteUrl: String): BookShelfEntity? = books[noteUrl]
    override suspend fun getBooksByUrls(noteUrls: List<String>): List<BookShelfEntity> =
        books.values.filter { it.noteUrl in noteUrls }
    override suspend fun insert(bookShelf: BookShelfEntity) {
        books[bookShelf.noteUrl] = bookShelf
    }
    override suspend fun insertAll(books: List<BookShelfEntity>) = books.forEach { insert(it) }
    override suspend fun update(bookShelf: BookShelfEntity) {
        books[bookShelf.noteUrl] = bookShelf
    }
    override suspend fun delete(bookShelf: BookShelfEntity) {
        books.remove(bookShelf.noteUrl)
    }
    override suspend fun deleteByUrl(noteUrl: String) {
        books.remove(noteUrl)
    }
    override suspend fun getCount(): Int = books.size
}

internal class FakeDownloadChapterDao : DownloadChapterDao {
    private val rows = linkedMapOf<Long, DownloadChapterEntity>()
    private var nextId = 1L
    private val remaining = MutableStateFlow(0)

    override suspend fun getChapterByUrl(chapterUrl: String): DownloadChapterEntity? =
        rows.values.firstOrNull { it.durChapterUrl == chapterUrl }
    override suspend fun getFirstByNoteUrl(noteUrl: String): DownloadChapterEntity? =
        sortedFor(noteUrl).firstOrNull()
    override suspend fun getLastByNoteUrl(noteUrl: String): DownloadChapterEntity? =
        sortedFor(noteUrl).lastOrNull()
    override suspend fun getByNoteUrl(noteUrl: String): List<DownloadChapterEntity> = sortedFor(noteUrl)
    override suspend fun getFirst(): DownloadChapterEntity? = rows.values.minByOrNull { it.durChapterIndex }
    override suspend fun getAllTasks(): List<DownloadChapterEntity> =
        rows.values.sortedWith(compareBy({ it.noteUrl }, { it.durChapterIndex }))
    override suspend fun deleteByNoteUrl(noteUrl: String) {
        rows.entries.removeAll { it.value.noteUrl == noteUrl }
        publish()
    }
    override suspend fun count(): Int = rows.size
    override fun observeRemainingCount(): Flow<Int> = remaining
    override suspend fun insert(chapter: DownloadChapterEntity) {
        rows.entries.removeAll { it.value.durChapterUrl == chapter.durChapterUrl }
        val id = if (chapter.id == 0L) nextId++ else chapter.id.also { nextId = maxOf(nextId, it + 1) }
        rows[id] = chapter.copy(id = id)
        publish()
    }
    override suspend fun insertAll(chapters: List<DownloadChapterEntity>) = chapters.forEach { insert(it) }
    override suspend fun delete(chapter: DownloadChapterEntity) {
        rows.remove(chapter.id)
        publish()
    }
    override suspend fun clearAll() {
        rows.clear()
        publish()
    }

    fun storedValues(): List<DownloadChapterEntity> = rows.values.toList()
    fun countFor(noteUrl: String): Int = rows.values.count { it.noteUrl == noteUrl }

    private fun sortedFor(noteUrl: String) =
        rows.values.filter { it.noteUrl == noteUrl }.sortedBy { it.durChapterIndex }

    private fun publish() {
        remaining.value = rows.size
    }
}

internal class FakePausedBookDao : PausedBookDao {
    private val paused = linkedSetOf<String>()

    override suspend fun insert(entity: PausedBookEntity) {
        paused += entity.noteUrl
    }
    override suspend fun delete(noteUrl: String) {
        paused -= noteUrl
    }
    override suspend fun getAll(): List<String> = paused.toList()
    override suspend fun clearAll() {
        paused.clear()
    }

    fun stored(): Set<String> = paused.toSet()
}
