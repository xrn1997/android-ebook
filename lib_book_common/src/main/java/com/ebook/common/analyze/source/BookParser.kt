package com.ebook.common.analyze.source

import android.content.Context
import com.ebook.api.cache.ACache
import com.ebook.db.entity.BookContentEntity
import com.ebook.db.entity.BookShelfEntity
import com.ebook.db.entity.LibraryEntity
import com.ebook.db.entity.SearchBookEntity
import com.ebook.db.entity.WebChapterEntity

/**
 * 书源解析器接口
 * 根据 BookSourceRule 规则解析 HTML，支持搜索、书籍信息、章节列表、内容获取
 */
interface BookParser {
    suspend fun searchBook(content: String, page: Int): List<SearchBookEntity>
    suspend fun getBookInfo(bookShelf: BookShelfEntity): BookShelfEntity
    suspend fun getChapterList(bookShelf: BookShelfEntity): WebChapterEntity<BookShelfEntity>
    suspend fun getBookContent(context: Context, durChapterUrl: String, durChapterIndex: Int): BookContentEntity
    suspend fun getKindBook(url: String, page: Int): List<SearchBookEntity>
    suspend fun getLibraryData(aCache: ACache): LibraryEntity
    fun analyzeLibraryData(data: String): LibraryEntity
}
