package com.ebook.common.manager

import com.ebook.common.analyze.source.BookSourceManager
import com.ebook.common.repository.BookRepository
import com.ebook.db.entity.BookShelfEntity
import com.ebook.db.entity.SearchBookEntity
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException

/**
 * 书架管理器 - 统一处理加入书架的完整流程
 *
 * 封装：书架查询、搜索结果标记、加入书架等共享逻辑，
 * 消除 SearchViewModel / ChoiceBookViewModel 中的重复代码。
 */
@Singleton
class BookShelfManager @Inject constructor(
    private val bookSourceManager: BookSourceManager,
    private val bookRepository: BookRepository
) {
    /** 加载书架列表（用于搜索结果比对） */
    suspend fun loadBookShelves(): List<BookShelfEntity> = bookRepository.getAllBooks()

    /** 为搜索结果标记已加入书架状态 */
    fun markShelfStatus(
        searchResults: List<SearchBookEntity>,
        bookShelves: List<BookShelfEntity>
    ) {
        val shelfUrls = bookShelves.map { it.noteUrl }.toSet()
        for (book in searchResults) {
            if (book.noteUrl in shelfUrls) {
                book.add = true
            }
        }
    }

    /**
     * 从搜索结果加入书架（完整流程）
     *
     * @param searchBook 搜索结果
     * @return 成功返回包含完整信息的 BookShelfEntity，失败返回异常
     */
    suspend fun addFromSearch(searchBook: SearchBookEntity): Result<BookShelfEntity> {
        return try {
            val shelf = BookShelfEntity().apply {
                noteUrl = searchBook.noteUrl
                tag = searchBook.tag
            }
            val bookInfo = bookSourceManager.requireParser().getBookInfo(shelf)
            val chapterResult = bookSourceManager.requireParser().getChapterList(bookInfo)
            val bookShelf = chapterResult.data
            bookRepository.addToShelf(bookShelf)
            Result.success(bookShelf)
        } catch (e: CancellationException) {
            // 取消不是「加入书架失败」：原样上抛，避免调用方按失败路径弹 Toast
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
