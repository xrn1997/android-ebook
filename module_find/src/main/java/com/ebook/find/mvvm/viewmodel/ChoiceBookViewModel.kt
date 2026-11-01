package com.ebook.find.mvvm.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.ebook.common.manager.BookShelfManager
import com.ebook.common.manager.applying
import com.ebook.common.pagination.PageSession
import com.ebook.common.pagination.PageSignal
import com.ebook.common.repository.ShelfRepository
import com.ebook.db.entity.SearchBookEntity
import com.ebook.find.repository.BookSourceRepository
import com.ebook.source.analyze.BookSourceNotFoundException
import com.xrn1997.common.mvvm.viewmodel.BaseRefreshViewModel
import com.xrn1997.common.mvvm.viewmodel.Overlay
import com.xrn1997.common.util.Logger
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow
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
    bookShelfRepository: ShelfRepository,
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

    /**
     * 翻页会话（ADR-0057）：持有游标、在途闸门与「到底」置位，信号时序也由它给出。
     *
     * 起始页 1——本页首屏与刷新都从第 1 页起。原先散在本类的 `page` 裸字段收进会话，
     * 本类只把「到底」判据（[mergeBookPage] 是否返回 null）算成 boolean 交进去、再按序
     * apply 返回的 [PageSignal]。分类页的「到底」不能只看空页：越界页会以 200 重复返回
     * 首页书目，靠「本页无新条目」才判得到底。
     */
    private val pageSession = PageSession(startPage = 1)

    /**
     * 一次性失败结论（分类书目那条源失效、加书架失败）。
     *
     * 槽与 [SearchViewModel] 用同一实现，两个页面的同一条失败因此说同一句话；
     * 文案与上报出口在页面（见 `ChoiceBookActivity` 与 [FindNotice]）。
     */
    private val findNotices = FindNoticeSlot()

    /** 待页面消费的结论；页面展示后经 [consumeFindNotice] 清零 */
    internal val findNotice: StateFlow<FindNotice?> = findNotices.notice

    /** 结论已被页面取走并展示 */
    fun consumeFindNotice() = findNotices.consume()

    init {
        // 书架事件同步：快照由 BookShelfManager 单点应用，本页只把结果列表的标记对齐
        viewModelScope.launch {
            bookShelfRepository.bookShelfEvents.collect { event ->
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
                // 游标复位由 refreshData 里的 pageSession.startRefresh() 接管，这里不再自置
                triggerAutoRefresh()
            }
        }
    }

    /**
     * 分页加载分类书籍：刷新页（首页）整表替换，加载更多页经 [mergeBookPage] 去重追加，
     * 本页没带来新条目即置「没有更多」。加载后标记书架状态。
     *
     * 页码由调用方（刷新走 [PageSession.startRefresh]、触底走 [PageSession.requestLoadMore]）
     * 从会话取出后传进来，本方法不自持游标。请求带着 [sourceUrl] 走
     * （本仓库三个方法都不再自己猜源，见 [BookSourceRepository]）。
     */
    private fun searchBook(page: Int, fromRefresh: Boolean) {
        if (url.isEmpty()) {
            return
        }
        viewModelScope.launch {
            try {
                val value = bookSourceRepository.getKindBook(sourceUrl, url, page)
                bookShelfManager.markShelfStatus(value)
                if (fromRefresh) {
                    // 首屏按 noteUrl 去重：列表以 noteUrl 作 item key，重复 key 直接抛异常。
                    // 刷新页不判「到底」：整表替换后基类把 hasMore 复位，等加载更多用无新条目去判。
                    updateList(value.distinctBy { it.noteUrl })
                    applySignals(pageSession.onRefreshResult(page, hasMore = true))
                } else {
                    // 无新条目 = 已经到底：越界页会以 200 重复返回首页书目，只靠「空页」判不到底
                    val merged = mergeBookPage(list.value, value)
                    if (merged != null) updateList(merged)
                    applySignals(pageSession.onLoadMoreSuccess(page, hasMore = merged != null))
                }
            } catch (e: BookSourceNotFoundException) {
                // 本源在页面打开后被删掉/规则读不出：与网络故障分开说，前者要用户去重新导入。
                // 这里只发结论，文案与上报出口在页面（见 ChoiceBookActivity 与 [FindNotice]）
                Logger.e(TAG, "getKindBook onError: ", e)
                findNotices.emit(FindNotice.SourceInvalid(e))
                stopAfterFailure(fromRefresh)
            } catch (e: Exception) {
                Logger.e(TAG, "onError: ", e)
                stopAfterFailure(fromRefresh)
            }
        }
    }

    /**
     * 失败收口：刷新停刷新、加载更多停加载更多并释放会话闸门（游标不动、可原页重试）。
     *
     * 两类失败分开发信号而不是合并成一条——刷新与加载更多在基类状态机里是两条通道，
     * 停错一条会让转圈卡住。
     */
    private fun stopAfterFailure(fromRefresh: Boolean) {
        if (fromRefresh) {
            updateStopRefresh()
        } else {
            applySignals(pageSession.onLoadMoreFailure())
        }
    }

    /** 按序把会话给出的信号落到基类刷新状态机上；顺序由会话保证，这里只转发（ADR-0057）。 */
    private fun applySignals(signals: List<PageSignal>) {
        for (signal in signals) {
            when (signal) {
                PageSignal.StopRefresh -> updateStopRefresh()
                is PageSignal.HasMore -> updateHasMoreData(signal.value)
                is PageSignal.StopLoadMore -> updateStopLoadMore(signal.success)
            }
        }
    }

    /**
     * 将选中书籍加入书架，失败时发结论、不选文案。
     *
     * 与 `SearchViewModel.addBookToShelf` 同一口径：都经共享的 [FindNoticeSlot.reportAddBookFailure]
     * 分类成 [FindNotice]，文案与上报出口都在页面（`reportFailure` 那条「会话过期只记日志、
     * 不重复提示」的不变量因此仍然成立）。
     */
    fun addBookToShelf(searchBook: SearchBookEntity) {
        updateOverlay(Overlay.Loading)
        viewModelScope.launch {
            bookShelfManager.addFromSearch(searchBook)
                .onFailure { e -> findNotices.reportAddBookFailure(e) }
            updateOverlay(Overlay.None)
        }
    }

    override fun refreshData() {
        searchBook(pageSession.startRefresh(), fromRefresh = true)
    }

    override fun loadMore() {
        // null = 已有一笔在途、或已到底；触底信号连发时只放行一笔（会话闸门）
        val page = pageSession.requestLoadMore() ?: return
        searchBook(page, fromRefresh = false)
    }
}
