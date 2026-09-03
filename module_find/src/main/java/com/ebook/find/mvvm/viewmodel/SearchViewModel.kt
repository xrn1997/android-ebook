package com.ebook.find.mvvm.viewmodel

import androidx.lifecycle.viewModelScope
import com.ebook.common.analyze.source.BookSourceManager
import com.ebook.common.manager.BookShelfManager
import com.ebook.common.repository.BookRepository
import com.ebook.common.repository.BookShelfEvent
import com.ebook.db.entity.BookShelfEntity
import com.ebook.db.entity.SearchBookEntity
import com.ebook.find.R
import com.ebook.db.entity.SearchHistoryEntity
import com.ebook.find.repository.SearchHistoryRepository
import com.xrn1997.common.BaseApplication.Companion.context
import com.xrn1997.common.mvvm.viewmodel.BaseRefreshViewModel
import com.xrn1997.common.mvvm.viewmodel.Overlay
import com.xrn1997.common.util.Logger
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 搜索页 VM。
 *
 * - "输入框是否有内容/是否已搜索"等纯 View 状态由 Activity 自持，不进 VM
 * - 书架快照与书架事件同步收敛在 VM 内部，View 只消费 [list] 与 [successEvent]
 */
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val searchHistoryRepository: SearchHistoryRepository,
    private val bookSourceManager: BookSourceManager,
    private val bookShelfManager: BookShelfManager,
    bookRepository: BookRepository
) : BaseRefreshViewModel<SearchBookEntity, SearchHistoryRepository>(searchHistoryRepository) {

    /** 当前书架快照（用于给搜索结果标记"已加书架"状态），仅 VM 内部维护 */
    private val bookShelves = mutableListOf<BookShelfEntity>()

    /** 当前搜索关键词（持久化到分页加载完成，供 loadMore 复用） */
    private var durSearchKey: String = ""

    /** 当前分页（仅在有结果时递增，避免空页越翻越深；刷新时归 1） */
    private var page = 1

    /** 搜索历史查询结果（供历史标签渲染） */
    private val _successEvent = MutableSharedFlow<List<SearchHistoryEntity>>(extraBufferCapacity = 1)
    val successEvent: SharedFlow<List<SearchHistoryEntity>> = _successEvent.asSharedFlow()

    init {
        viewModelScope.launch {
            try {
                bookShelves.addAll(bookShelfManager.loadBookShelves())
            } catch (e: Throwable) {
                Logger.e(TAG, "loadBookShelves onError: ", e)
            }
        }
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
                    is BookShelfEvent.ProgressUpdated -> Unit // 阅读进度与搜索页无关
                }
            }
        }
    }

    override fun loadMore() {
        searchBook(durSearchKey)
    }

    /** 插入搜索历史（upsert：同一词条仅更新时间戳），插入后自动刷新历史列表。 */
    fun insertSearchHistory(content: String) {
        viewModelScope.launch {
            try {
                searchHistoryRepository.insertSearchHistory(BOOK, content)
                // 插入后刷新全量历史（同一词条重复搜索仅更新时间戳，不影响展示集合）
                querySearchHistory()
            } catch (e: Throwable) {
                Logger.e(TAG, "onError: ", e)
            }
        }
    }

    /** 清除 BOOK 类型全部搜索历史，成功后向 [successEvent] 发射空列表。 */
    fun cleanSearchHistory() {
        viewModelScope.launch {
            try {
                val value = searchHistoryRepository.cleanSearchHistory(BOOK)
                if (value > 0) {
                    _successEvent.tryEmit(listOf())
                }
            } catch (e: Throwable) {
                Logger.e(TAG, "onError: ", e)
            }
        }
    }

    /** 查询 BOOK 类型全部搜索历史，结果通过 [successEvent] 发射。 */
    fun querySearchHistory() {
        viewModelScope.launch {
            try {
                val entities = searchHistoryRepository.querySearchHistory(BOOK)
                _successEvent.tryEmit(entities)
            } catch (e: Throwable) {
                Logger.e(TAG, "onError: ", e)
            }
        }
    }

    /** 重置分页到第 1 页（由 Activity 在发起新搜索前调用）。 */
    fun initPage() {
        this.page = 1
    }

    /**
     * 发起搜索：显示加载态、记录搜索关键词、触发分页搜索。
     * 空内容直接返回（由 Activity 侧拦截并触发抖动）。
     */
    fun toSearchBooks(content: String) {
        if (content.isEmpty()) {
            return
        }
        updateOverlay(Overlay.Loading)
        durSearchKey = content
        searchBook(durSearchKey)
    }

    /**
     * 分页搜索书籍：page=1 时替换列表，page>1 时经 [mergeBookPage] 去重追加，
     * 本页没带来新条目即置「没有更多」。仅在有结果时递增页码，避免空页越翻越深。
     */
    private fun searchBook(content: String) {
        viewModelScope.launch {
            try {
                val value = bookSourceManager.requireParser().searchBook(content, page)
                bookShelfManager.markShelfStatus(value, bookShelves)
                if (page == 1) {
                    // 首屏按 noteUrl 去重：列表以 noteUrl 作 item key，重复 key 直接抛异常
                    updateList(value.distinctBy { it.noteUrl })
                } else {
                    // 没带来新条目（空页或整页重复）= 没有更多；刷新路径由状态机自动重置，ADR-0041
                    val merged = mergeBookPage(list.value, value)
                    if (merged == null) updateHasMoreData(false) else updateList(merged)
                }
                // 仅在有结果时递增页码
                if (value.isNotEmpty()) {
                    page++
                }
                updateOverlay(Overlay.None)
                updateStopLoadMore(true)
            } catch (e: Throwable) {
                updateOverlay(Overlay.None)
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

    // 搜索页面无下拉刷新入口（刷新即重新搜索），基类抽象方法的空实现
    override fun refreshData() {}

    companion object {
        /** 搜索历史类型：书籍搜索（对齐 SearchHistoryEntity.type 字段） */
        const val BOOK: Int = 2
    }
}
