package com.ebook.find.mvvm.model

import android.app.Application
import com.ebook.api.cache.ACache
import com.ebook.api.config.Url
import com.ebook.common.analyze.impl.WebBookModelImpl
import com.ebook.db.ObjectBoxManager.bookShelfBox
import com.ebook.db.entity.BookShelf
import com.ebook.db.entity.BookShelf_
import com.ebook.db.entity.Library
import com.ebook.find.entity.BookType
import com.xrn1997.common.mvvm.model.BaseModel
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.core.ObservableEmitter
import io.reactivex.rxjava3.schedulers.Schedulers
import javax.inject.Inject
import javax.inject.Singleton
@Suppress("unused")
@Singleton
class LibraryModel @Inject constructor(
    application: Application
) : BaseModel(application) {
    //获取书籍类型信息，此处用本地数据。
    fun getBookTypeList(): List<BookType> {
        return mutableListOf(
            BookType("玄幻小说", Url.xh),
            BookType("修真小说", Url.xz),
            BookType("都市小说", Url.ds),
            BookType("历史小说", Url.ls),
            BookType("网游小说", Url.wy),
            BookType("科幻小说", Url.kh),
            BookType("其他小说", Url.qt)
        )
        }

    companion object {
        private const val LIBRARY_CACHE_KEY: String = "cache_library"

        //获得书库信息
        @JvmStatic
        fun getLibraryData(mCache: ACache): Observable<Library> {
            return Observable.create { e: ObservableEmitter<String> ->
                val cache = mCache.getAsString(LIBRARY_CACHE_KEY)
                e.onNext(cache)
                e.onComplete()
            }.flatMap { s: String ->
                WebBookModelImpl.analyzeLibraryData(s)
            }
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
        }

        //获取书架书籍列表信息
        @JvmStatic
        fun getBookShelfList(): Observable<List<BookShelf>> {
            return Observable.create { e: ObservableEmitter<List<BookShelf>> ->
                try {
                    bookShelfBox.query().build().use { query ->
                        val temp = query.find()
                        e.onNext(temp)
                        e.onComplete()
                    }
                } catch (ex: Exception) {
                    e.onError(ex)
                }
            }.subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
        }

        //将书籍信息存入书架书籍列表
        @JvmStatic
        fun saveBookToShelf(bookShelf: BookShelf): Observable<BookShelf> {
            return Observable.create { e: ObservableEmitter<BookShelf> ->
                bookShelfBox.query(BookShelf_.noteUrl.equal(bookShelf.noteUrl))
                    .build().use { query ->
                        val temp = query.findFirst()
                        if (temp != null) {
                            bookShelf.id = temp.id
                        } else {
                            bookShelf.id = 0L
                        }
                    }
                //网络数据获取成功  存入BookShelf表数据库
                bookShelfBox.put(bookShelf)
                e.onNext(bookShelf)
                e.onComplete()
            }.subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
        }
    }
}
