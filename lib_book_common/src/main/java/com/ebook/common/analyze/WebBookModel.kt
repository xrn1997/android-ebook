package com.ebook.common.analyze

import android.content.Context
import com.ebook.api.cache.ACache
import com.ebook.db.entity.BookContent
import com.ebook.db.entity.BookShelf
import com.ebook.db.entity.Library
import com.ebook.db.entity.SearchBook
import com.ebook.db.entity.WebChapter
import io.reactivex.rxjava3.core.Observable

interface WebBookModel {
    /**
     * 网络请求并解析书籍信息
     */
    fun getBookInfo(bookShelf: BookShelf): Observable<BookShelf>

    /**
     * 网络解析图书目录
     */
    fun getChapterList(bookShelf: BookShelf): Observable<WebChapter<BookShelf>>

    /**
     * 章节缓存
     */
    fun getBookContent(
        context: Context,
        durChapterUrl: String,
        durChapterIndex: Int
    ): Observable<BookContent>

    /**
     * 获取分类书籍
     */
    fun getKindBook(url: String, page: Int): Observable<List<SearchBook>>

    /**
     * 获取主页信息
     */
    fun getLibraryData(aCache: ACache): Observable<Library>

    /**
     * 解析主页数据
     */
    fun analyzeLibraryData(data: String): Observable<Library>

    /**
     * 搜索书籍
     */
    fun searchBook(content: String, page: Int): Observable<List<SearchBook>>
}
