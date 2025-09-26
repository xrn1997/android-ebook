package com.ebook.find.mvvm.viewmodel

import android.app.Application
import android.util.Log
import com.ebook.common.analyze.impl.WebBookModelImpl
import com.ebook.common.event.RxBusTag
import com.ebook.db.ObjectBoxManager.bookShelfBox
import com.ebook.db.entity.BookShelf
import com.ebook.db.entity.BookShelf_
import com.ebook.db.entity.SearchBook
import com.ebook.db.entity.WebChapter
import com.ebook.find.mvvm.model.SearchModel
import com.hwangjr.rxbus.RxBus
import com.xrn1997.common.event.SimpleObserver
import com.xrn1997.common.mvvm.viewmodel.BaseRefreshViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.core.ObservableEmitter
import io.reactivex.rxjava3.schedulers.Schedulers
import javax.inject.Inject

@HiltViewModel
class ChoiceBookViewModel @Inject constructor(
    application: Application,
    model: SearchModel
) : BaseRefreshViewModel<SearchBook, SearchModel>(application, model) {
    var url: String = ""

    var page: Int = 1
        private set
    val bookShelves: MutableList<BookShelf> = ArrayList() //用来比对搜索的书籍是否已经添加进书架

    init {
        Observable.create { e: ObservableEmitter<List<BookShelf>> ->
            try {
            bookShelfBox.query().build().use { query ->
                val temp = query.find()
                e.onNext(temp)
            }
            } catch (ex: Exception) {
                e.onError(ex)
            }
            e.onComplete()
        }.subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .doOnSubscribe(this)
            .subscribe(object : SimpleObserver<List<BookShelf>>() {
                override fun onNext(value: List<BookShelf>) {
                    bookShelves.addAll(value)
                    page = 1
                    postAutoRefreshEvent()
                }

                override fun onError(e: Throwable) {
                    Log.e(TAG, "onError: ", e)
                }
            })
    }

    private fun searchBook() {
        if (url.isEmpty()) {
            return
        }
        WebBookModelImpl.getKindBook(url, page)
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .doOnSubscribe(this)
            .subscribe(object : SimpleObserver<List<SearchBook>>() {
                override fun onNext(value: List<SearchBook>) {
                        for (temp in value) {
                            for ((noteUrl) in bookShelves) {
                                if (temp.noteUrl == noteUrl) {
                                    temp.add = true
                                    break
                                }
                            }
                        }
                        if (page == 1) {
                            mList.value = value
                        } else {
                            //todo 可能有性能问题
                            val list = mList.value ?: emptyList()
                            mList.value = list + value
                        }
                    page++
                    postStopRefreshEvent(true)
                    postStopLoadMoreEvent(true)
                }

                override fun onError(e: Throwable) {
                    Log.e(TAG, "onError: ", e)
                    postStopRefreshEvent(false)
                    postStopLoadMoreEvent(false)
                }
            })
    }

    ////////////////////////////////////////////////////////////////////////////////////////////////
    fun addBookToShelf(searchBook: SearchBook) {
        postShowLoadingViewEvent(true)
        val bookShelfResult = BookShelf().apply {
            noteUrl = searchBook.noteUrl
            finalDate = 0
            durChapter = 0
            durChapterPage = 0
            tag = searchBook.tag
        }
        WebBookModelImpl.getBookInfo(bookShelfResult)
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .doOnSubscribe(this)
            .flatMap { fetchedBookShelf ->
                // 获取章节列表
                WebBookModelImpl.getChapterList(fetchedBookShelf)
            }
            .subscribe(object : SimpleObserver<WebChapter<BookShelf>>() {
                override fun onNext(bookShelfWebChapter: WebChapter<BookShelf>) {
                    // 保存书架数据
                    saveBookToShelf(bookShelfWebChapter.data)
                    postShowLoadingViewEvent(false)
                }

                override fun onError(e: Throwable) {
                    mToastLiveEvent.setValue(e.message ?: "网络请求超时")
                    postShowLoadingViewEvent(false)
                }
            })
    }

    private fun saveBookToShelf(bookShelf: BookShelf) {
        Observable.create { e: ObservableEmitter<BookShelf> ->
            bookShelfBox.query(BookShelf_.noteUrl.equal(bookShelf.noteUrl)).build().use { query ->
                    val temp = query.findFirst()
                    if (temp != null) {
                        bookShelf.id = temp.id
                    }
                }
            //网络数据获取成功  存入BookShelf表数据库
            bookShelfBox.put(bookShelf)
            e.onNext(bookShelf)
            e.onComplete()
        }.subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .doOnSubscribe(this)
            .subscribe(object : SimpleObserver<BookShelf>() {
                override fun onNext(value: BookShelf) {
                    //成功   //发送RxBus
                    RxBus.get().post(RxBusTag.HAD_ADD_BOOK, value)
                }

                override fun onError(e: Throwable) {
                    mToastLiveEvent.setValue(e.message ?: "网络请求超时")
                }
            })
    }

    companion object {
        private val TAG = ChoiceBookViewModel::class.java.simpleName
    }

    override fun refreshData() {
        page = 1
        searchBook()
    }

    override fun loadMore() {
        searchBook()
    }
}