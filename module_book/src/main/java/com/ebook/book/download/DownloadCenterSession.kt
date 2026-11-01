package com.ebook.book.download

import com.ebook.db.entity.DownloadChapterEntity

/**
 * 下载中心「哪一章在跑 / 该刷哪一面 / 暂停态乐观回显」的单一主人（纯 JVM，无 Context/Android 依赖）。
 *
 * 病根：这三件事原先在多处各算一遍——
 * - **焦点章**：[DownloadManageViewModel.loadGroups] 用 `isDownloading && tasks.any { url==activeChapterUrl }`
 *   再 `tasks.first { … }`，[DownloadManageViewModel.loadSelection] 又独立地
 *   `if (isDownloading) tasks.firstOrNull { … }?.durChapterIndex`，其 KDoc 自陈「与一级卡片同口径」
 *   靠注释与记性维持——改一处必须记得改另一处，正是抽离宿主收进会话的理由
 *   （同 [ADR-0057] 的分页会话、[ADR-0048] 的派生口径单主一族）。
 * - **结局→该刷哪面**：`when (DownloadState)` 编排在 `DownloadCenterScreen` 的 `LaunchedEffect` 里
 *   （Compose 层），而它推进的 `isDownloading`/`activeChapterUrl` 又住在 ViewModel 的裸字段上。
 * - **乐观回显**：`confirmDownload` 直接在页面状态上做 `paused=false` + 并入排队索引。
 *
 * 会话把这三件事收成一处：[onDownloadState] 吃服务结局、推进下载态并返回该刷新的页面集，
 * [activeChapter] 是一级卡片与二级选章**共用**的焦点章判据，[optimisticEnqueue] 是唯一一处
 * 定义「确认下载后哪些展示字段即时翻转」的地方。宿主（ViewModel）只接线、只订阅结论。
 *
 * 边界：某本书**算不算暂停**的真值源是 `paused_book` 表（经 `DownloadBatch`），会话不回查库，
 * 只负责「暂停书不得同时显示为活跃章」这条呈现层不变量——它靠 [activeChapter] 里的
 * `isDownloading` 门实现（暂停/完成时收起，队列里的章不冒充「正在下载」）。
 */
class DownloadCenterSession {

    /** 服务当前是否在真正下载：只有 Progress 之后才能断言「正在下载第 N 章」。 */
    private var isDownloading: Boolean = false

    /** 当前活跃章的 URL：[DownloadState.Progress] 落值，暂停/收尾不重置但被 [isDownloading] 收起。 */
    private var activeChapterUrl: String? = null

    /**
     * 收到一条服务结局：推进下载态，并返回「该刷新哪几面」的路由结论（宿主据此刷新，编排不再活在
     * Composable 里）。
     *
     * 三种结局目前都要同时刷新一级分组与二级选章——一级收起/更新「正在下载」副行，二级同步徽章
     * 与状态标签；`refreshSelection` 内部对「当前不在二级」自行短路。**路由差异（若有）只改这一处**，
     * 不必再回到 Screen 里挪 `when`。
     */
    fun onDownloadState(state: DownloadState): Set<DownloadRefreshTarget> {
        isDownloading = state is DownloadState.Progress
        if (state is DownloadState.Progress) {
            activeChapterUrl = state.chapter.durChapterUrl
        }
        return when (state) {
            is DownloadState.Progress -> setOf(DownloadRefreshTarget.Groups, DownloadRefreshTarget.Selection)
            DownloadState.Paused, DownloadState.Finished ->
                setOf(DownloadRefreshTarget.Groups, DownloadRefreshTarget.Selection)
        }
    }

    /**
     * 某本书任务列表里的焦点章（当前正在下载的那一章），一级卡片与二级选章**同一处**算出：
     * 一级直接展示返回的这一行（「正在下载 第 M 章 · 章名」），二级取它的 `durChapterIndex` 打徽章。
     *
     * 不在下载（未 Progress，或被暂停/收尾收起）返回 null；活跃章 URL 不在这本书的任务表里
     * （书在架但零任务、或正在下载别的书）也返回 null。
     */
    fun activeChapter(tasks: List<DownloadChapterEntity>): DownloadChapterEntity? =
        if (isDownloading) tasks.firstOrNull { it.durChapterUrl == activeChapterUrl } else null

    /**
     * 确认下载后的**乐观回显**：不等下一次 Progress 事件驱动的刷新，立即把「已暂停」收起、把新勾章
     * 并入排队集，让二级页当场显示「排队中」与「继续下载」。
     *
     * 只翻展示字段（`paused` / `queuedIndices`），不碰章节/缓存等装载事实——那些仍在下一次
     * [DownloadManageViewModel.loadSelection] 从库里取回。与下发路径的顺序（先解暂停再拉服务）无关，
     * 会话不算服务，只算「此刻该给用户看什么」。
     */
    fun optimisticEnqueue(
        selection: BookChapterSelection,
        queuedIndices: Set<Int>,
    ): BookChapterSelection = selection.copy(
        paused = false,
        queuedIndices = selection.queuedIndices + queuedIndices,
    )
}

/** 一条服务结局之后该刷新的下载中心页面（[DownloadCenterSession.onDownloadState] 的路由输出）。 */
enum class DownloadRefreshTarget {
    /** 一级按书任务列表（[DownloadManageViewModel.loadGroups]）。 */
    Groups,

    /** 二级该书选章（[DownloadManageViewModel.refreshSelection]）。 */
    Selection,
}
