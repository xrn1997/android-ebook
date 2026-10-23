package com.ebook.book.reader

import com.ebook.common.analyze.local.ChapterContent
import com.ebook.common.repository.BookRepository
import com.ebook.common.repository.ChapterSyncResult
import com.ebook.db.entity.BookShelfEntity
import com.ebook.db.entity.ChapterListEntity

/**
 * [ReaderSessionGateway] 的仓库适配器：会话与 [BookRepository] 之间唯一的接线处。
 *
 * 只转译会话需要的八个动作；换源、评论键、下载这些不归会话所有的仓库能力不经这里。
 * 正文读取按仓库的定位口径把 `(index, title, contentRef)` 一起下传——章文件命名与网络解析
 * 都跟这本书自己的 `tag` 走，适配器不做任何取舍。
 */
internal class RepositoryReaderSessionGateway(
    private val bookRepository: BookRepository,
) : ReaderSessionGateway {

    override suspend fun getBookWithDetails(noteUrl: String): BookShelfEntity? =
        bookRepository.getBookWithDetails(noteUrl)

    override suspend fun getBookByUrl(noteUrl: String): BookShelfEntity? =
        bookRepository.getBookByUrl(noteUrl)

    override suspend fun saveProgress(shelf: BookShelfEntity) {
        bookRepository.saveProgress(shelf)
    }

    override suspend fun loadChapter(
        shelf: BookShelfEntity,
        chapter: ChapterListEntity,
    ): ChapterContent? = bookRepository.loadChapter(
        shelf,
        chapter.durChapterIndex,
        chapter.durChapterName,
        chapter.contentRef,
    )

    override suspend fun refreshChapter(shelf: BookShelfEntity, index: Int) {
        bookRepository.refreshChapter(shelf, index)
    }

    override suspend fun syncChapters(shelf: BookShelfEntity): ChapterSyncResult =
        bookRepository.syncChaptersFromSource(shelf)

    override suspend fun getStoredChapters(noteUrl: String): List<ChapterListEntity> =
        bookRepository.getStoredChapters(noteUrl)

    override suspend fun addToShelf(shelf: BookShelfEntity) {
        bookRepository.addToShelf(shelf)
    }
}
