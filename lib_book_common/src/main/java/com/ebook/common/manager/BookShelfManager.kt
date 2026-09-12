package com.ebook.common.manager

import com.ebook.common.analyze.source.BookSourceManager
import com.ebook.common.repository.BookRepository
import com.ebook.db.entity.BookShelfEntity
import com.ebook.db.entity.SearchBookEntity
import com.ebook.source.analyze.BookSourceNotFoundException
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
     * **按 `tag` 取 parser，不按「当前默认源」取**（见 ADR-0016）：结果来自哪个站，`getBookInfo` /
     * `getChapterList` 就必须用那个站的规则去解——多书源共存后一条搜索列表里可能混着多个源的条目，
     * 用默认源规则解另一源的 `noteUrl` 不会闪退，只会拉回一本不相干的书或空目录（错数据比崩溃难查得多）。
     * 归属查不到（源被删 / tag 是脏数据）时抛 [BookSourceNotFoundException]，经下面的 catch 变成
     * [Result.failure] 带回调用方，由 ViewModel 统一提示「书源已失效」。
     *
     * 本地书不会走到这里：`loc_book` 的条目不经搜索结果加入书架。
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
            // 两次解析共用同一个 parser 实例：中途用户删了源也不至于一半新一半旧
            val parser = bookSourceManager.getParserFor(searchBook.tag)
                ?: throw BookSourceNotFoundException(searchBook.tag)
            val bookInfo = parser.getBookInfo(shelf)
            val chapterResult = parser.getChapterList(bookInfo)
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
