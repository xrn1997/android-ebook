package com.ebook.find.mvvm.viewmodel

import com.ebook.common.util.reportFailure
import com.ebook.find.R
import com.ebook.source.analyze.BookSourceNotFoundException
import com.xrn1997.common.BaseApplication.Companion.context
import com.xrn1997.common.mvvm.viewmodel.BaseViewModel

/**
 * 「把一条搜索结果加入书架」失败的**统一上报口径**（[SearchViewModel] 与 [ChoiceBookViewModel] 共用）。
 *
 * 两个 VM 走的是同一条链路（`BookShelfManager.addFromSearch` + 共享的 `reportFailure`），文案也必须一致：
 * - [BookSourceNotFoundException]：换成 `book_source_invalid` 资源文案——它的 message 形如
 *   「书源已失效：<URL>」，直接上屏等于把内部标识甩给用户，且没告诉他该做什么（重导或换源）；
 * - 其余异常：沿用异常自身消息，取不到消息时兜底一句网络超时。
 *
 * **为什么收成一个函数而不是各写一遍**：这段「异常类型 → 用户可见文案」的分支原先在两个 VM 里逐字重复，
 * 一旦漂移，同一个失败在两个页面会说两句不同的话（且改了一边没人会发现）。上报本身仍走
 * `reportFailure`——「A0230 会话过期已由网络层全局处置、这里只记日志不重复提示」那条不变量由它收口，
 * 本函数只负责选文案，不自己判会话过期。
 *
 * @param e 加书架失败的异常
 */
internal fun BaseViewModel<*>.reportAddBookFailure(e: Throwable) {
    val message = if (e is BookSourceNotFoundException) {
        context.getString(R.string.book_source_invalid)
    } else {
        e.message ?: context.getString(R.string.network_request_timeout)
    }
    reportFailure(e, message)
}
