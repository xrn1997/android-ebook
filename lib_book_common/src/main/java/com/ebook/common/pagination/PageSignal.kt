package com.ebook.common.pagination

/**
 * 翻页状态机发给宿主的一次性信号，宿主按顺序应用到基类刷新状态机。
 *
 * 只有三种，且**没有第五种**：本仓两个翻页宿主（书评区、分类选书）需要的收口恰好是
 * 「停刷新 / 同步有没有更多 / 停加载更多」。要加第四种先想清楚它是不是又一处会静默分叉的口径
 * （ADR-0057）。
 */
sealed interface PageSignal {

    /** 结束下拉刷新。顺序承重：基类状态机收到它时会自动把 hasMore 复位，故它必须排在 [HasMore] 之前。 */
    data object StopRefresh : PageSignal

    /**
     * 同步「到底了没有」（true=还有更多，false=到底）。
     *
     * 经基类 `updateHasMoreData` 落到刷新状态机上——它同时是 VM 的闸门事实与 UI 的触底许可。
     */
    data class HasMore(val value: Boolean) : PageSignal

    /** 结束一次加载更多（true=成功，false=失败）。 */
    data class StopLoadMore(val success: Boolean) : PageSignal
}
