package com.ebook.find.mvvm.viewmodel

import android.app.Application
import android.util.Log
import com.ebook.api.cache.ACache
import com.ebook.common.analyze.impl.WebBookModelImpl
import com.ebook.db.entity.Library
import com.ebook.db.entity.LibraryKindBookList
import com.ebook.find.mvvm.model.LibraryModel
import com.ebook.find.mvvm.model.LibraryModel.Companion.getLibraryData
import com.xrn1997.common.event.SimpleObserver
import com.xrn1997.common.mvvm.viewmodel.BaseRefreshViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.schedulers.Schedulers
import javax.inject.Inject

@HiltViewModel
class LibraryViewModel @Inject constructor(
    application: Application,
    model: LibraryModel
) : BaseRefreshViewModel<LibraryKindBookList, LibraryModel>(application, model) {
    val bookTypeList = mModel.getBookTypeList()
    private val mCache: ACache = ACache.get(application.applicationContext)
    private var isFirst = true

    override fun refreshData() {
        //   Log.d(TAG, "refreshData: start");
        if (isFirst) {
            isFirst = false
            getLibraryData(mCache)
                .doOnSubscribe(this)
                .subscribe(object : SimpleObserver<Library>() {
                    override fun onNext(value: Library) {
                        value.kindBooks?.let {
                            mList.value = it
                        }
                        //       Log.d(TAG, "refreshData onNext: " + value.toString());
                        getLibraryNewData()
                    }

                    override fun onError(e: Throwable) {
                        getLibraryNewData()
                    }
                })
        } else {
            getLibraryNewData()
        }
    }


    override fun loadMore() {
        postStopLoadMoreEvent(false)
    }

    private fun getLibraryNewData() {
        //   Log.d(TAG, "getLibraryNewData: start");
        WebBookModelImpl.getLibraryData(mCache)
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .doOnSubscribe(this)
            .subscribe(object : SimpleObserver<Library>() {
                override fun onNext(value: Library) {
                    //     Log.d(TAG, "refreshData onNext: " + value.getKindBooks().get(0).getKindName());
                    value.kindBooks?.let {
                        mList.value = it
                    }
                    postStopRefreshEvent(true)
                    //   Log.d(TAG, "refreshData onNext: finish");
                }

                override fun onError(e: Throwable) {
                    postStopRefreshEvent(false)
                    Log.e(TAG, "onError: ", e)
                }
            })
    }

    companion object {
        val TAG: String = LibraryViewModel::class.java.simpleName
        const val LIBRARY_CACHE_KEY: String = "cache_library"
    }
}
