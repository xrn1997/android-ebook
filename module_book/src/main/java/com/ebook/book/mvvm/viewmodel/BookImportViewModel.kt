package com.ebook.book.mvvm.viewmodel

import android.app.Application
import android.os.Environment
import android.util.Log
import androidx.lifecycle.MutableLiveData
import com.ebook.book.mvvm.model.BookImportModel
import com.ebook.common.event.RxBusTag
import com.ebook.db.entity.LocBookShelf
import com.hwangjr.rxbus.RxBus
import com.xrn1997.common.event.SimpleObserver
import com.xrn1997.common.event.SingleLiveEvent
import com.xrn1997.common.mvvm.viewmodel.BaseViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.core.ObservableEmitter
import io.reactivex.rxjava3.schedulers.Schedulers
import java.io.File
import javax.inject.Inject

@HiltViewModel
class BookImportViewModel @Inject constructor(
    application: Application,
    model: BookImportModel
) : BaseViewModel<BookImportModel>(application, model) {
    val mImportBookList = MutableLiveData<List<File>>()
    val searchFinishEvent by lazy { SingleLiveEvent<Unit>() }
    val addSuccessEvent by lazy { SingleLiveEvent<Unit>() }
    val addErrorEvent by lazy { SingleLiveEvent<Unit>() }

    //停止扫描
    private var isCancel: Boolean = false

    fun searchLocationBook() {
        isCancel = false
        Observable.create { e: ObservableEmitter<File> ->
            if (Environment.getExternalStorageState() == Environment.MEDIA_MOUNTED) {
                searchBook(e, File(Environment.getExternalStorageDirectory().absolutePath))
                e.onComplete()
            }
        }.subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe(object : SimpleObserver<File>() {
                override fun onNext(value: File) {
                    val list = mImportBookList.value ?: emptyList()
                    mImportBookList.value = list + value
                }

                override fun onComplete() {
                    searchFinishEvent.call()
                }

                override fun onError(e: Throwable) {
                    Log.e(TAG, "onError: ", e)
                }
            })
    }

    private fun searchBook(e: ObservableEmitter<File>, parentFile: File) {
        if (isCancel) {
            e.onComplete()
            return
        }
        if (!parentFile.listFiles().isNullOrEmpty()) {
            val childFiles = parentFile.listFiles()
            if (childFiles != null) {
                for (childFile in childFiles) {
                    if (childFile.isFile && childFile.name.substring(childFile.name.lastIndexOf(".") + 1)
                            .equals("txt", ignoreCase = true)
                        && childFile.length() > 100 * 1024
                    ) {   //100kb
                        e.onNext(childFile)
                        continue
                    }
                    if (childFile.absolutePath == "/storage/emulated/0/Android/data"
                        || childFile.absolutePath == "/storage/emulated/0/Android/obb"
                    ) {
                        //这个两个路径没有权限，不扫
                        continue
                    }
                    if (childFile.isDirectory && !childFile.listFiles().isNullOrEmpty()) {
                        //进入文件夹中继续扫
                        searchBook(e, childFile)
                    }
                }
            }
        }
    }

    fun importBooks(books: List<File>) {
        Observable.fromIterable(books).flatMap { file: File ->
            mModel.importBook(file)
        }
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe(object : SimpleObserver<LocBookShelf>() {
                override fun onNext(value: LocBookShelf) {
                    Log.e(TAG, "onNext: " + value.new)
                    if (value.new) {
                        RxBus.get().post(RxBusTag.HAD_ADD_BOOK, value.bookShelf)
                    }
                }

                override fun onError(e: Throwable) {
                    Log.e(TAG, "onError: ", e)
                    addErrorEvent.call()
                }

                override fun onComplete() {
                    addSuccessEvent.call()
                }
            })
    }

    fun scanCancel() {
        isCancel = true
    }

    companion object {
        const val TAG: String = "ImportBookPresenterImpl"
    }
}