package com.ebook.common.analyze.source

import com.ebook.api.cache.ACache
import com.ebook.db.entity.BookShelfEntity
import com.ebook.db.entity.LibraryEntity
import com.ebook.db.entity.SearchBookEntity
import com.ebook.db.entity.WebChapterEntity

/**
 * 书源解析器接口
 * 根据 BookSourceRule 规则解析 HTML，支持搜索、书籍信息、章节列表、分类书籍、书库数据
 */
interface BookParser {
    suspend fun searchBook(content: String, page: Int): List<SearchBookEntity>
    suspend fun getBookInfo(bookShelf: BookShelfEntity): BookShelfEntity
    suspend fun getChapterList(bookShelf: BookShelfEntity): WebChapterEntity<BookShelfEntity>
    suspend fun getKindBook(url: String, page: Int): List<SearchBookEntity>
    suspend fun getLibraryData(aCache: ACache): LibraryEntity
    fun analyzeLibraryData(data: String): LibraryEntity
}
