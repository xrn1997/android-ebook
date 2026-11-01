package com.ebook.book.reader

import com.ebook.common.analyze.local.ChapterContent
import com.ebook.common.repository.ChapterSyncResult
import com.ebook.common.repository.ChapterTocSync
import com.ebook.common.repository.ContentReader
import com.ebook.common.repository.ShelfRepository
import com.ebook.common.store.ReaderResumeStore
import com.ebook.db.entity.BookShelfEntity
import com.ebook.db.entity.ChapterListEntity

/**
 * [ReaderSessionGateway] 的仓库适配器：会话与仓库窄面（[ShelfRepository] / [ContentReader] /
 * [ChapterTocSync]，实现同为 `BookRepository`）之间唯一的接线处。
 *
 * 只转译会话需要的八个动作；换源、评论键、下载这些不归会话所有的仓库能力不经这里。
 * 正文读取按仓库的定位口径把 `(index, title, contentRef)` 一起下传——章文件命名与网络解析
 * 都跟这本书自己的 `tag` 走，适配器不做任何取舍。
 */
internal class RepositoryReaderSessionGateway(
    private val bookShelfRepository: ShelfRepository,
    private val contentReader: ContentReader,
    private val chapterTocSync: ChapterTocSync,
) : ReaderSessionGateway {

    override suspend fun getBookWithDetails(noteUrl: String): BookShelfEntity? =
        bookShelfRepository.getBookWithDetails(noteUrl)

    override suspend fun getBookByUrl(noteUrl: String): BookShelfEntity? =
        bookShelfRepository.getBookByUrl(noteUrl)

    override suspend fun saveProgress(shelf: BookShelfEntity) {
        bookShelfRepository.saveProgress(shelf)
    }

    override suspend fun loadChapter(
        shelf: BookShelfEntity,
        chapter: ChapterListEntity,
    ): ChapterContent? = contentReader.loadChapter(
        shelf,
        chapter.durChapterIndex,
        chapter.durChapterName,
        chapter.contentRef,
    )

    override suspend fun refreshChapter(shelf: BookShelfEntity, index: Int) {
        contentReader.refreshChapter(shelf, index)
    }

    override suspend fun syncChapters(shelf: BookShelfEntity): ChapterSyncResult =
        chapterTocSync.syncChaptersFromSource(shelf)

    override suspend fun getStoredChapters(noteUrl: String): List<ChapterListEntity> =
        chapterTocSync.getStoredChapters(noteUrl)

    override suspend fun addToShelf(shelf: BookShelfEntity) {
        bookShelfRepository.addToShelf(shelf)
    }

    override fun markReading(noteUrl: String) {
        ReaderResumeStore.markReading(noteUrl)
    }
}
