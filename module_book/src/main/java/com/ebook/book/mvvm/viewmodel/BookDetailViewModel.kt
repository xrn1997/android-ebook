package com.ebook.book.mvvm.viewmodel

import android.app.Application
import android.util.Log
import android.widget.Toast
import com.ebook.book.mvvm.model.BookDetailModel
import com.ebook.common.analyze.impl.WebBookModelImpl
import com.ebook.common.event.RxBusTag
import com.ebook.db.ObjectBoxManager.bookContentBox
import com.ebook.db.ObjectBoxManager.bookInfoBox
import com.ebook.db.ObjectBoxManager.bookShelfBox
import com.ebook.db.ObjectBoxManager.chapterListBox
import com.ebook.db.entity.BookShelf
import com.ebook.db.entity.BookShelf_
import com.ebook.db.entity.SearchBook
import com.ebook.db.entity.WebChapter
import com.hwangjr.rxbus.RxBus
import com.xrn1997.common.BaseApplication.Companion.context
import com.xrn1997.common.event.SimpleObserver
import com.xrn1997.common.event.SingleLiveEvent
import com.xrn1997.common.mvvm.viewmodel.BaseViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.core.ObservableEmitter
import io.reactivex.rxjava3.schedulers.Schedulers
import java.util.Collections
import javax.inject.Inject

@HiltViewModel
class BookDetailViewModel @Inject constructor(
    application: Application,
    model: BookDetailModel
) : BaseViewModel<BookDetailModel>(application, model) {
    val bookShelfList: MutableList<BookShelf> =
        Collections.synchronizedList(ArrayList()) //用来比对搜索的书籍是否已经添加进书架
    var searchBook: SearchBook? = null
    var mBookShelf: BookShelf? = null
    var inBookShelf: Boolean = false
    val updateViewEvent by lazy { SingleLiveEvent<Unit>() }
    val bookShelfErrorEvent by lazy { SingleLiveEvent<Unit>() }

    fun getBookShelfInfo() {
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
        }.flatMap { bookShelves: List<BookShelf> ->
            synchronized(bookShelfList) {
                bookShelfList.clear()
                bookShelfList.addAll(bookShelves) // 确保线程安全
            }
            val bookShelfResult = BookShelf()
            bookShelfResult.noteUrl = searchBook!!.noteUrl
            bookShelfResult.finalDate = System.currentTimeMillis()
            bookShelfResult.durChapter = 0
            bookShelfResult.durChapterPage = 0
            bookShelfResult.tag = searchBook!!.tag
            WebBookModelImpl.getBookInfo(bookShelfResult)
                .onErrorResumeNext { throwable: Throwable ->
                    Observable.error(throwable)
                }
        }.map { bookShelf: BookShelf ->
            bookShelfList.stream()
                .filter { shelf: BookShelf -> shelf.noteUrl == bookShelf.noteUrl }
                .findFirst()
                .ifPresent { shelf: BookShelf ->
                    inBookShelf = true
                    bookShelf.durChapter = shelf.durChapter
                    bookShelf.durChapterPage = shelf.durChapterPage
                }
            bookShelf
        }.subscribeOn(Schedulers.io())
            .doOnSubscribe(this)
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe(object : SimpleObserver<BookShelf>() {
                override fun onNext(value: BookShelf) {
                    WebBookModelImpl.getChapterList(value)
                        .subscribe(object : SimpleObserver<WebChapter<BookShelf>>() {
                            override fun onNext(bookShelfWebChapter: WebChapter<BookShelf>) {
                                mBookShelf = bookShelfWebChapter.data
                                updateViewEvent.call()
                            }

                            override fun onError(e: Throwable) {
                                mBookShelf = null
                                Log.e("错误信息", "getChapterList onError: ", e)
                                bookShelfErrorEvent.call()
                            }
                        })
                }

                override fun onError(e: Throwable) {
                    mBookShelf = null
                    Log.e("错误信息", "subscribe onError: ", e)
                    bookShelfErrorEvent.call()
                }
            })
    }

    fun addToBookShelf() {
        if (mBookShelf != null) {
            Observable.create { e: ObservableEmitter<Boolean> ->
                try {
                    bookShelfBox.query(BookShelf_.noteUrl.equal(mBookShelf!!.noteUrl)).build()
                        .use { query ->
                            val temp = query.findFirst()
                            if (temp != null) {
                                mBookShelf!!.id = temp.id
                            }
                            //网络数据获取成功  存入BookShelf表数据库
                            val id = bookShelfBox.put(mBookShelf!!)
                            Log.e(TAG, "addToBookShelf: $id")
                            e.onNext(true)
                            e.onComplete()
                        }
                } catch (ex: Exception) {
                    e.onError(ex)
                }
            }.subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .doOnSubscribe(this)
                .subscribe(object : SimpleObserver<Boolean>() {
                    override fun onNext(value: Boolean) {
                        if (value) {
                            RxBus.get().post(RxBusTag.HAD_ADD_BOOK, mBookShelf)
                        } else {
                            Toast.makeText(
                                context,
                                "放入书架失败!",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }

                    override fun onError(e: Throwable) {
                        Log.e(TAG, "onError: ", e)
                        Toast.makeText(context, "放入书架失败!", Toast.LENGTH_SHORT)
                            .show()
                    }
                })
        }
    }

    fun removeFromBookShelf() {
        mBookShelf?.let {
            Observable.create { e: ObservableEmitter<Boolean> ->
                bookShelfBox.query(BookShelf_.noteUrl.equal(it.noteUrl)).build()
                    .use { query ->
                        val bookShelf = query.findFirst()
                        if (bookShelf != null) {
                            val bookInfo = bookShelf.bookInfo.target
                            if (bookInfo != null) {
                                val chapterList = bookInfo.chapterList
                                for (chapter in chapterList) {
                                    val bookContent = chapter.bookContent.target
                                    if (bookContent != null && bookContent.id != 0L) {
                                        bookContentBox.remove(bookContent.id)
                                    }
                                }
                                chapterListBox.remove(chapterList)
                                bookInfoBox.remove(bookInfo.id)
                            }
                            bookShelfBox.remove(bookShelf.id)
                        }
                    }
                e.onNext(true)
                e.onComplete()
            }.subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .doOnSubscribe(this)
                .subscribe(object : SimpleObserver<Boolean>() {
                    override fun onNext(value: Boolean) {
                        if (value) {
                            RxBus.get().post(RxBusTag.HAD_REMOVE_BOOK, mBookShelf)
                        } else {
                            Toast.makeText(
                                context,
                                "移出书架失败!",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }

                    override fun onError(e: Throwable) {
                        Log.e(TAG, "onError: ", e)
                        Toast.makeText(context, "移出书架失败!", Toast.LENGTH_SHORT)
                            .show()
                    }
                })
        }
    }
}