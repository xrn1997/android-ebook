package com.ebook.find.mvvm.viewmodel

import androidx.lifecycle.viewModelScope
import com.ebook.db.entity.LibraryKindBookListEntity
import com.ebook.find.repository.BookSourceRepository
import com.xrn1997.common.mvvm.viewmodel.BaseRefreshViewModel
import com.xrn1997.common.util.Logger
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 书城主 Tab VM。
 *
 * 分类入口列表（[bookTypeList]）同步读取当前书源规则（内存数据，无 IO）；
 * 书库数据（含缓存读取/失效重抓，由解析器内部处理）经刷新流程加载。
 */
@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val bookSourceRepository: BookSourceRepository
) : BaseRefreshViewModel<LibraryKindBookListEntity, BookSourceRepository>(bookSourceRepository) {
    /** 书籍分类入口列表（同步读取当前书源规则，内存数据，无 IO） */
    val bookTypeList = bookSourceRepository.getBookTypeList()

    override fun refreshData() {
        // 缓存读取、校验与失效重抓由 JsoupBookParser.getLibraryData 内部完成
        viewModelScope.launch {
            try {
                val value = bookSourceRepository.getLibraryData()
                value.kindBooks?.let {
                    if (it.isNotEmpty()) {
                        updateList(it)
                        updateStopRefresh()
                        return@launch
                    }
                }
                // 书库无数据（书源无分类）按成功处理
                updateStopRefresh()
            } catch (e: Exception) {
                updateStopRefresh()
                Logger.e(TAG, "onError: ", e)
            }
        }
    }

    override fun loadMore() {
        updateStopLoadMore(false)
    }
}
