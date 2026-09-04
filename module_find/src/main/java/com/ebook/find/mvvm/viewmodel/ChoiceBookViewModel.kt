package com.ebook.find.mvvm.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.ebook.common.repository.BookRepository
import com.ebook.common.repository.BookShelfEvent
import com.ebook.common.manager.BookShelfManager
import com.ebook.db.entity.BookShelfEntity
import com.ebook.db.entity.SearchBookEntity
import com.ebook.find.R
import com.ebook.find.repository.BookSourceRepository
import com.xrn1997.common.BaseApplication.Companion.context
import com.xrn1997.common.mvvm.viewmodel.BaseRefreshViewModel
import com.xrn1997.common.mvvm.viewmodel.Overlay
import com.xrn1997.common.util.Logger
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 分类选书页 VM。
 *
 * 首屏加载在 init 中自动驱动：分类 URL 经 [SavedStateHandle] 同步读取
 * （activity-scoped VM 的 SavedStateHandle 自动以 Activity intent extras 填充），
 * init 在 VM 生命周期内只执行一次——旋转重建时 VM 存活、不重复加载，
 * 天然幂等，无"先赋 url 后刷新"的线程调度竞态。
 */
@HiltViewModel
class ChoiceBookViewModel @Inject constructor(
    private val bookSourceRepository: BookSourceRepository,
    private val bookShelfManager: BookShelfManager,
    savedStateHandle: SavedStateHandle,
    bookRepository: BookRepository,
) : BaseRefreshViewModel<SearchBookEntity, BookSourceRepository>(bookSourceRepository) {

    /** 分类列表页地址（activity-scoped VM 的 SavedStateHandle 自动含 intent extras） */
    private val url: String = savedStateHandle.get<String>("url").orEmpty()

    /** 当前分页（加载更多递增，刷新时归 1） */
    private var page = 1

    /** 当前书架快照（用于给列表项标记"已加书架"状态），仅 VM 内部维护 */
    private val bookShelves = mutableListOf<BookShelfEntity>()

    init {
        // 书架事件同步：书架增删时更新快照与列表项状态，替代原 Activity 侧的重复收集逻辑
        viewModelScope.launch {
            bookRepository.bookShelfEvents.collect { event ->
                when (event) {
                    is BookShelfEvent.Added -> {
                        bookShelves.add(event.bookShelf)
                        updateBookAddState(event.bookShelf, true)
                    }
                    is BookShelfEvent.Removed -> {
                        bookShelves.remove(event.bookShelf)
                        updateBookAddState(event.bookShelf, false)
                    }
                    is BookShelfEvent.ProgressUpdated -> Unit // 阅读进度与列表页无关
                }
            }
        }

        // 首屏加载：书架快照加载完成后再触发自动刷新，保证 markShelfStatus 拿到完整书架数据。
        // 空 url（无分类参数）不触发，避免空列表空转；信号经 BUFFERED Channel 缓冲，
        // MvvmBinder 稍后订阅也能收到，无时序依赖。
        if (url.isNotEmpty()) {
            viewModelScope.launch {
                try {
                    bookShelves.addAll(bookShelfManager.loadBookShelves())
                } catch (e: Exception) {
                    // 书架加载失败不阻断列表加载，只是"已加书架"标记可能不准
                    Logger.e(TAG, "loadBookShelves onError: ", e)
                }
                page = 1
                triggerAutoRefresh()
            }
        }
    }

    /**
     * 分页加载分类书籍：page=1 时替换列表，page>1 时经 [mergeBookPage] 去重追加，
     * 本页没带来新条目即置「没有更多」。加载后标记书架状态。
     */
    private fun searchBook() {
        if (url.isEmpty()) {
            return
        }
        viewModelScope.launch {
            try {
                val value = bookSourceRepository.getKindBook(url, page)
                bookShelfManager.markShelfStatus(value, bookShelves)
                if (page == 1) {
                    // 首屏按 noteUrl 去重：列表以 noteUrl 作 item key，重复 key 直接抛异常
                    updateList(value.distinctBy { it.noteUrl })
                } else {
                    // 无新条目 = 已经到底：越界页会以 200 重复返回首页书目，只靠「空页」判不到底
                    val merged = mergeBookPage(list.value, value)
                    if (merged == null) updateHasMoreData(false) else updateList(merged)
                }
                page++
                updateStopRefresh()
                updateStopLoadMore(true)
            } catch (e: Exception) {
                Logger.e(TAG, "onError: ", e)
                updateStopRefresh()
                updateStopLoadMore(false)
            }
        }
    }

    /** 将搜索结果中的书籍加入书架，失败时 toast 提示。 */
    fun addBookToShelf(searchBook: SearchBookEntity) {
        updateOverlay(Overlay.Loading)
        viewModelScope.launch {
            bookShelfManager.addFromSearch(searchBook)
                .onFailure { e -> sendToast(e.message ?: context.getString(R.string.network_request_timeout)) }
            updateOverlay(Overlay.None)
        }
    }

    /**
     * 书架事件同步：更新列表中对应书籍的"是否已加入书架"状态。
     */
    private fun updateBookAddState(bookShelf: BookShelfEntity, isAdd: Boolean) {
        val currentList = list.value.toMutableList()
        val index = currentList.indexOfFirst { it.noteUrl == bookShelf.noteUrl }
        if (index != -1) {
            val updatedBook = currentList[index].copy(add = isAdd)
            currentList[index] = updatedBook
            updateList(currentList)
        }
    }

    override fun refreshData() {
        page = 1
        searchBook()
    }

    override fun loadMore() {
        searchBook()
    }
}
