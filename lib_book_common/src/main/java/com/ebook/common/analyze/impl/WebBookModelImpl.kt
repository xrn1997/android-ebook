package com.ebook.common.analyze.impl

import android.content.Context
import com.ebook.api.cache.ACache
import com.ebook.common.analyze.StationBookModel
import com.ebook.common.analyze.WebBookModel
import com.ebook.db.entity.BookContent
import com.ebook.db.entity.BookShelf
import com.ebook.db.entity.Library
import com.ebook.db.entity.SearchBook
import com.ebook.db.entity.WebChapter
import io.reactivex.rxjava3.core.Observable


object WebBookModelImpl : WebBookModel {
    private val stationBookModel: StationBookModel = TXTDownloadBookModelImpl
    override fun getBookInfo(bookShelf: BookShelf): Observable<BookShelf> {
        return stationBookModel.getBookInfo(bookShelf)
    }

    override fun getChapterList(bookShelf: BookShelf): Observable<WebChapter<BookShelf>> {
        return stationBookModel.getChapterList(bookShelf)
    }

    override fun getBookContent(
        context: Context,
        durChapterUrl: String,
        durChapterIndex: Int
    ): Observable<BookContent> {
        return stationBookModel.getBookContent(context, durChapterUrl, durChapterIndex)
    }

    override fun getKindBook(url: String, page: Int): Observable<List<SearchBook>> {
        return stationBookModel.getKindBook(url, page)
    }

    override fun getLibraryData(aCache: ACache): Observable<Library> {
        return stationBookModel.getLibraryData(aCache)
    }

    override fun analyzeLibraryData(data: String): Observable<Library> {
        return stationBookModel.analyzeLibraryData(data)
    }

    override fun searchBook(content: String, page: Int): Observable<List<SearchBook>> {
        return stationBookModel.searchBook(content, page)
    }
}
