package com.ebook.book.mvvm.model

import android.app.Application
import android.util.Log
import com.ebook.db.ObjectBoxManager.bookShelfBox
import com.ebook.db.entity.BookShelf
import com.ebook.db.entity.BookShelf_
import com.xrn1997.common.mvvm.model.BaseModel
import io.objectbox.query.QueryBuilder
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.core.ObservableEmitter
import io.reactivex.rxjava3.schedulers.Schedulers
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BookListModel @Inject constructor(
    application: Application
) : BaseModel(application) {
    fun getBookShelfList(): Observable<List<BookShelf>> {
        return Observable.create { e: ObservableEmitter<List<BookShelf>> ->
            bookShelfBox.query()
                .order(BookShelf_.finalDate, QueryBuilder.DESCENDING)
                .build().use { query ->
                    val bookShelves = query.find()
                    val iterator =
                        bookShelves.iterator()
                    while (iterator.hasNext()) {
                        val bookShelf = iterator.next()
                        Log.e("ttt", "getBookShelfList: " + bookShelf.id)
                        val temp = bookShelf.bookInfo.target
                        if (temp == null) {
                            bookShelfBox.remove(bookShelf)
                            iterator.remove()
                        }
                    }
                    e.onNext(bookShelves)
                }
        }.subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
    }

}
