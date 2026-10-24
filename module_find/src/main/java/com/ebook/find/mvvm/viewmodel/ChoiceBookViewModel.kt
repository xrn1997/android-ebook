package com.ebook.find.mvvm.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.ebook.common.manager.BookShelfManager
import com.ebook.common.manager.applying
import com.ebook.common.repository.BookRepository
import com.ebook.common.util.reportFailure
import com.ebook.db.entity.SearchBookEntity
import com.ebook.find.R
import com.ebook.find.repository.BookSourceRepository
import com.ebook.source.analyze.BookSourceNotFoundException
import com.xrn1997.common.BaseApplication.Companion.context
import com.xrn1997.common.mvvm.viewmodel.BaseRefreshViewModel
import com.xrn1997.common.mvvm.viewmodel.Overlay
import com.xrn1997.common.util.Logger
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
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

    /**
     * 本页归属的书源 URL：**由书城页经导航参数传入**（进入分类页那一刻的默认源）。
     *
     * 分类 url 是书城页用**当时的默认源**规则渲染出来的（`ruleFind.kinds` 的 url 只对那个源有意义），
     * 所以请求必须继续由同一个源的 parser 发出——这样结果的 `tag` 天然是该源，
     * [BookShelfManager.markShelfStatus] 与后续「加入书架」按 `tag` 取源才对得上（ADR-0016 P3-a）。
     *
     * 为什么不在这里现读默认源：翻页是一整段会话，中途换源会让第 2 页用另一套规则解析
     * 同一个分类 url，`mergeBookPage` 拿到的就是两个源的混合书目，去重与「到底」判定全失效。
     * 本页整段会话锁定导航参数带进来的那一个源。
     *
     * 空串（书城页那一刻没有源、或该路由被别处调用）与既有的空 `url` 同一处置：请求发不出去，
     * 列表空着，不会退化成「拿随便一个源去解别人的分类 url」。
     */
    private val sourceUrl: String = savedStateHandle.get<String>("source_url").orEmpty()

    /** 当前分页（加载更多递增，刷新时归 1） */
    private var page = 1

    init {
        // 书架事件同步：快照由 BookShelfManager 单点应用，本页只把结果列表的标记对齐
        viewModelScope.launch {
            bookRepository.bookShelfEvents.collect { event ->
                bookShelfManager.applyEvent(event)
                val next = list.value.applying(event)
                if (next !== list.value) updateList(next)
            }
        }

        // 首屏加载：书架快照加载完成后再触发自动刷新，保证 markShelfStatus 拿到完整书架数据。
        // 空 url（无分类参数）不触发，避免空列表空转；信号经 BUFFERED Channel 缓冲，
        // MvvmBinder 稍后订阅也能收到，无时序依赖。
        if (url.isNotEmpty()) {
            viewModelScope.launch {
                try {
                    bookShelfManager.refresh()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // 书架加载失败不阻断列表加载，只是"已加书架"标记可能不准
                    Logger.e(TAG, "refresh shelves onError: ", e)
                }
                page = 1
                triggerAutoRefresh()
            }
        }
    }

    /**
     * 分页加载分类书籍：page=1 时替换列表，page>1 时经 [mergeBookPage] 去重追加，
     * 本页没带来新条目即置「没有更多」。加载后标记书架状态。
     *
     * 请求带着 [sourceUrl] 走（本仓库三个方法都不再自己猜源，见 [BookSourceRepository]）。
     */
    private fun searchBook() {
        if (url.isEmpty()) {
            return
        }
        viewModelScope.launch {
            try {
                val value = bookSourceRepository.getKindBook(sourceUrl, url, page)
                bookShelfManager.markShelfStatus(value)
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
            } catch (e: BookSourceNotFoundException) {
                // 本源在页面打开后被删掉/规则读不出：与网络故障分开说，前者要用户去重新导入
                Logger.e(TAG, "getKindBook onError: ", e)
                reportFailure(e, context.getString(R.string.book_source_invalid))
                updateStopRefresh()
                updateStopLoadMore(false)
            } catch (e: Exception) {
                Logger.e(TAG, "onError: ", e)
                updateStopRefresh()
                updateStopLoadMore(false)
            }
        }
    }

    /**
     * 将选中书籍加入书架，失败时提示。
     *
     * 与 `SearchViewModel.addBookToShelf` 同一口径：[reportAddBookFailure]（共享的 `reportFailure`
     * 收口「会话过期只记日志、不重复提示」，书源失效换资源文案的理由见它）。
     */
    fun addBookToShelf(searchBook: SearchBookEntity) {
        updateOverlay(Overlay.Loading)
        viewModelScope.launch {
            bookShelfManager.addFromSearch(searchBook)
                .onFailure { e -> reportAddBookFailure(e) }
            updateOverlay(Overlay.None)
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
