package com.ebook.find.mvvm.model

import android.app.Application
import com.ebook.db.ObjectBoxManager.searchHistoryBox
import com.ebook.db.entity.SearchHistory
import com.ebook.db.entity.SearchHistory_
import com.xrn1997.common.mvvm.model.BaseModel
import io.objectbox.query.QueryBuilder
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.core.ObservableEmitter
import io.reactivex.rxjava3.schedulers.Schedulers
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SearchModel @Inject constructor(
    application: Application
) : BaseModel(application) {
    //保存查询记录
    fun insertSearchHistory(type: Int, content: String): Observable<SearchHistory> {
        return Observable.create { e: ObservableEmitter<SearchHistory> ->
            val boxStore = searchHistoryBox
            boxStore
                .query(
                    SearchHistory_.type.equal(type).and(SearchHistory_.content.equal(content))
                )
                .build().use { query ->
                    val searchHistories = query.find(0, 1)
                    val searchHistory: SearchHistory
                    if (searchHistories.isNotEmpty()) {
                        searchHistory = searchHistories[0]
                        searchHistory.date = System.currentTimeMillis()
                        boxStore.put(searchHistory)
                    } else {
                        searchHistory =
                            SearchHistory(type, content, System.currentTimeMillis())
                        boxStore.put(searchHistory)
                    }
                    e.onNext(searchHistory)
                }
        }.subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
    }

    //删除查询记录
    fun cleanSearchHistory(type: Int, content: String): Observable<Int> {
        return Observable.create { e: ObservableEmitter<Int> ->
            val boxStore = searchHistoryBox
            boxStore
                .query(SearchHistory_.type.equal(type))
                .contains(
                    SearchHistory_.content,
                    content,
                    QueryBuilder.StringOrder.CASE_INSENSITIVE
                ) // 等同于 SQL 中的 "content LIKE ?"
                .build().use { query ->
                    val histories = query.find()
                    boxStore.remove(histories)
                    e.onNext(histories.size)
                }
        }.subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
    }

    //获得查询记录
    fun querySearchHistory(type: Int, content: String): Observable<List<SearchHistory>> {
        return Observable.create { e: ObservableEmitter<List<SearchHistory>> ->
            searchHistoryBox
                .query(SearchHistory_.type.equal(type))
                .contains(
                    SearchHistory_.content,
                    content,
                    QueryBuilder.StringOrder.CASE_INSENSITIVE
                )
                .order(SearchHistory_.date, QueryBuilder.DESCENDING)
                .build().use { query ->
                    val histories = query.find(0, 20)
                    e.onNext(histories)
                }
        }.subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
    }
}