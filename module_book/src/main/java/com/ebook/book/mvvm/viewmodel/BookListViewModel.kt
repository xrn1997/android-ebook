package com.ebook.book.mvvm.viewmodel

import androidx.lifecycle.viewModelScope
import com.ebook.common.repository.BookRepository
import com.ebook.common.repository.BookShelfEvent
import com.ebook.db.entity.BookShelfEntity
import com.xrn1997.common.mvvm.viewmodel.BaseRefreshViewModel
import com.xrn1997.common.util.Logger
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 书架页 ViewModel。
 *
 * 职责：
 * - 刷新书架列表（下拉刷新 + 事件驱动刷新，共用 [refreshData]）
 * - 收集 [BookRepository.bookShelfEvents]（Added/Removed/ProgressUpdated → 自动刷新）
 *
 * 曾经还有「书架加载后扫重复对 + 推合并建议 + 执行合并」三项职责，已随 ADR-0023 移除：
 * 重复处置前移到导入时点拦截（见 `BookImportViewModel`），书架侧不再事后 nag。
 */
@HiltViewModel
class BookListViewModel @Inject constructor(
    bookRepository: BookRepository,
) : BaseRefreshViewModel<BookShelfEntity, BookRepository>(bookRepository) {

    init {
        // 书架变化事件收集（原 MainBookFragment.onCreate 中的 lifecycleScope 收集）。
        // 移入 ViewModel：不再依赖页面组合/生命周期，切 Tab 或重组期间事件不丢失；
        // 事件驱动刷新（Added/Removed/ProgressUpdated）与手动下拉刷新共用 refreshData，幂等。
        viewModelScope.launch {
            model.bookShelfEvents.collect { event ->
                when (event) {
                    is BookShelfEvent.Added,
                    is BookShelfEvent.Removed,
                    is BookShelfEvent.ProgressUpdated -> refreshData()
                }
            }
        }
    }

    override fun refreshData() {
        viewModelScope.launch {
            try {
                val value = model.getAllBooksWithDetails()
                updateList(value)
            } catch (e: Exception) {
                Logger.e(TAG, "refreshData 失败", e)
            } finally {
                // 无论成功失败都复位：适配既有 MvvmBinder（若仍绑定），
                // 保证书架下拉刷新指示器必然停止（空书架亦如此）
                updateStopRefresh()
            }
        }
    }
}
