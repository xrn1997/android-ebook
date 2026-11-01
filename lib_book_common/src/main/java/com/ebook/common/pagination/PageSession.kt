package com.ebook.common.pagination

/**
 * 列表分页的会话单主：持有游标、在途闸门与「到底」置位，只收「到底了没有」的 boolean，不管内容合并（ADR-0057）。
 *
 * 两个宿主（书评区 `BookCommentsViewModel`、分类选书 `ChoiceBookViewModel`）原先各在裸字段里养一套
 * `nextPage`/`page` + 闸门，信号先后靠调用点的记忆维持；这里把**时序**收成一处，宿主只交判据、按序 apply。
 *
 * - **游标**：[startRefresh] 重置回 [startPage]，[requestLoadMore] 放行并推进，成功回调按返回页 +1。
 * - **在途闸门**：触底信号在滚动中可能连续到来，[requestLoadMore] 在途时返回 null，只放行一笔。
 * - **到底置位**：成功回调收到 `hasMore=false` 即置位，此后 [requestLoadMore] 一直返回 null，直到刷新重置。
 * - **刷新永远放行**：下拉刷新不受闸门与「到底」限制，它会用新首页整体替换列表。
 *
 * 会话不碰列表内容：合并（去重、排序）是宿主各自的域知识，「到底」的判据（服务端 hasMore /
 * `mergeBookPage` 是否非 null）也由宿主算成 boolean 传进来。
 *
 * @param startPage 首屏之后第一页的页码。书评区取 2（首页 1 走刷新，会话不管）；分类取 1（刷新即首页）。
 */
class PageSession(private val startPage: Int) {

    /** 下一次加载更多要请求的页码。 */
    private var nextPage = startPage

    /** 加载更多在途闸门：触底信号连发时只放行一笔。 */
    private var loadMoreInProgress = false

    /** 到底置位：成功回调判 `hasMore=false` 后挡住后续触底，直到刷新重置。 */
    private var exhausted = false

    /**
     * 开始刷新：把游标重置回 [startPage]、清在途闸门与到底置位，返回首页码。
     *
     * 刷新永远放行——不受在途与到底约束（它用新首页整体替换列表，旧游标无意义）。
     */
    fun startRefresh(): Int {
        loadMoreInProgress = false
        exhausted = false
        nextPage = startPage
        return startPage
    }

    /**
     * 请求加载更多：放行则返回要请求的页码并拉下在途闸门；在途或已到底返回 null。
     */
    fun requestLoadMore(): Int? {
        if (loadMoreInProgress || exhausted) return null
        loadMoreInProgress = true
        return nextPage
    }

    /**
     * 一次加载更多成功：清在途、游标 +1，并按 [hasMore] 决定是否置到底。
     *
     * 返回 `[HasMore, StopLoadMore(true)]`——先同步「还有没有更多」再收口本次加载。
     */
    fun onLoadMoreSuccess(page: Int, hasMore: Boolean): List<PageSignal> {
        loadMoreInProgress = false
        nextPage = page + 1
        if (!hasMore) exhausted = true
        return listOf(PageSignal.HasMore(hasMore), PageSignal.StopLoadMore(true))
    }

    /**
     * 一次加载更多失败：清在途、**游标不动**（失败页原样重试），返回 `[StopLoadMore(false)]`。
     *
     * 不置到底：失败是暂时的，下拉刷新之外用户还应能靠再次触底重试这一页。
     */
    fun onLoadMoreFailure(): List<PageSignal> {
        loadMoreInProgress = false
        return listOf(PageSignal.StopLoadMore(false))
    }

    /**
     * 刷新返回：返回 `[StopRefresh, HasMore]`。
     *
     * 顺序承重：**基类刷新状态机在收到刷新结束信号（StopRefresh）时会自动把 hasMore 复位为 true**，
     * 显式的 [PageSignal.HasMore] 必须排在 StopRefresh 之后才能覆盖该复位值、留下本页的真实结论。
     * 反序（先 HasMore 再 StopRefresh）会让「到底」结论被复位吞掉——这是本仓修过的一个真实 bug，
     * 收进会话就是为了把这个顺序锁在类型返回值上，而不是靠每个调用点记得（ADR-0057）。
     */
    fun onRefreshResult(page: Int, hasMore: Boolean): List<PageSignal> {
        nextPage = page + 1
        exhausted = !hasMore
        return listOf(PageSignal.StopRefresh, PageSignal.HasMore(hasMore))
    }
}
