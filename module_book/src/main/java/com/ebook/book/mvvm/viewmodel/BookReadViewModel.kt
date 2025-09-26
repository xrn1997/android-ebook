package com.ebook.book.mvvm.viewmodel

import android.app.Application
import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import com.ebook.book.mvvm.model.BookReadModel
import com.ebook.common.event.RxBusTag
import com.ebook.db.ObjectBoxManager.bookShelfBox
import com.ebook.db.entity.BookShelf
import com.ebook.db.entity.BookShelf_
import com.hwangjr.rxbus.RxBus
import com.xrn1997.common.event.SimpleObserver
import com.xrn1997.common.event.SingleLiveEvent
import com.xrn1997.common.mvvm.viewmodel.BaseViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.core.ObservableEmitter
import io.reactivex.rxjava3.schedulers.Schedulers
import javax.inject.Inject

@HiltViewModel
class BookReadViewModel @Inject constructor(
    application: Application,
    model: BookReadModel
) : BaseViewModel<BookReadModel>(application, model) {
    var isAdd = false //判断是否已经添加进书架
    var bookShelf: BookShelf? = null

    var pageLineCount = 5 //假设5行一页
    val nextInShelfEvent by lazy { SingleLiveEvent<Unit>() }

    fun updateProgress(chapterIndex: Int, pageIndex: Int) {
        bookShelf!!.durChapter = chapterIndex
        bookShelf!!.durChapterPage = pageIndex
    }

    fun saveProgress() {
        bookShelf?.let {
            Observable.create { e: ObservableEmitter<BookShelf> ->
                it.finalDate = System.currentTimeMillis()
                bookShelfBox.query(BookShelf_.noteUrl.equal(it.noteUrl))
                    .build().use { query ->
                        val temp = query.findFirst()
                        if (temp != null) {
                            it.id = temp.id
                        }
                    }
                bookShelfBox.put(it)
                e.onNext(it)
                e.onComplete()
            }.subscribeOn(Schedulers.io())
                .subscribe(object : SimpleObserver<BookShelf>() {
                    override fun onNext(value: BookShelf) {
                        RxBus.get().post(RxBusTag.UPDATE_BOOK_PROGRESS, it)
                    }

                    override fun onError(e: Throwable) {
                        Log.e(TAG, "onError: ", e)
                    }
                })
        }
    }

    fun getChapterTitle(chapterIndex: Int): String {
        return if (bookShelf!!.bookInfo.target.chapterList.isEmpty()) {
            "无章节"
        } else bookShelf!!.bookInfo.target.chapterList[chapterIndex].durChapterName
    }

    fun checkInShelf() {
        Observable.create { e: ObservableEmitter<Boolean> ->
            try {
                bookShelfBox
                    .query(BookShelf_.noteUrl.equal(bookShelf!!.noteUrl)).build().use { query ->
                        val temp = query.find()
                        isAdd = temp.isNotEmpty()
                        e.onNext(isAdd)
                        e.onComplete()
                    }
            } catch (ex: Exception) {
                e.onError(ex)
            }
        }.subscribeOn(Schedulers.io())
            .doOnSubscribe(this)
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe(object : SimpleObserver<Boolean>() {
                override fun onNext(value: Boolean) {
                    nextInShelfEvent.call()
                }

                override fun onError(e: Throwable) {
                    Log.e(TAG, "onError: ", e)
                }
            })
    }

    fun addToShelf(addListener: OnAddListener?) {
        bookShelf?.let {
            Observable.create { e: ObservableEmitter<Boolean> ->
                bookShelfBox.query(
                    BookShelf_.noteUrl.equal(
                        it.noteUrl
                    )
                ).build().use { query ->
                    val temp = query.findFirst()
                    if (temp != null) {
                        it.id = temp.id
                    }
                }
                //网络数据获取成功  存入BookShelf表数据库
                bookShelfBox.put(it)
                RxBus.get().post(RxBusTag.HAD_ADD_BOOK, it)
                isAdd = true
                e.onNext(true)
                e.onComplete()
            }.subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(object : SimpleObserver<Any>() {
                    override fun onNext(value: Any) {
                        addListener?.addSuccess()
                    }

                    override fun onError(e: Throwable) {
                    }
                })
        }
    }

    fun getRealFilePath(context: Context, uri: Uri): Observable<String> {
        return Observable.create { e: ObservableEmitter<String> ->
            var data = ""
            val scheme = uri.scheme
            if (scheme == null) data = uri.path.toString()
            else if (ContentResolver.SCHEME_FILE == scheme) {
                data = uri.path.toString()
            } else if (ContentResolver.SCHEME_CONTENT == scheme) {
                val cursor = context.contentResolver.query(
                    uri,
                    arrayOf(MediaStore.Images.ImageColumns.DATA),
                    null,
                    null,
                    null
                )
                if (null != cursor) {
                    if (cursor.moveToFirst()) {
                        val index =
                            cursor.getColumnIndex(MediaStore.Images.ImageColumns.DATA)
                        if (index > -1) {
                            data = cursor.getString(index)
                        }
                    }
                    cursor.close()
                }

                if ((data.isEmpty()) && uri.path != null && uri.path!!.contains(
                        "/storage/emulated/"
                    )
                ) {
                    data = uri.path!!.substring(uri.path!!.indexOf("/storage/emulated/"))
                }
            }
            e.onNext(data)
            e.onComplete()
        }
    }

    interface OnAddListener {
        fun addSuccess()
    }

    companion object {
        const val OPEN_FROM_OTHER: Int = 0
        const val OPEN_FROM_APP: Int = 1
        private const val TAG = "ReadBookPresenterImpl"
    }
}