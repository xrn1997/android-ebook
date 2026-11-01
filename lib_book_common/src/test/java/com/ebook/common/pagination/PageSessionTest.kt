package com.ebook.common.pagination

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [PageSession] 的翻页会话测试（纯 JVM，无 Android 依赖）。
 *
 * 锁的是两个宿主（书评区 `BookCommentsViewModel`、分类选书 `ChoiceBookViewModel`）共用的
 * 翻页协议本身：游标推进、在途闸门、「到底」置位与**信号顺序**。宿主各自的「到底」判据
 * （服务端 hasMore / mergeBookPage 非 null）不进本类——会话只收 boolean、只管时序（ADR-0057）。
 */
class PageSessionTest {

    @Test
    fun `游标初值即起始页`() {
        val session = PageSession(startPage = 2)

        assertEquals(2, session.requestLoadMore())
    }

    @Test
    fun `startRefresh 返回起始页并清掉在途闸门`() {
        val session = PageSession(startPage = 1)
        session.requestLoadMore()
        session.onLoadMoreSuccess(1, hasMore = true)
        assertEquals(2, session.requestLoadMore())

        // 第二次触底被在途闸门拦下时来了一次刷新：刷新后游标回到起始页、闸门重开
        val page = session.startRefresh()

        assertEquals(1, page)
        assertEquals(1, session.requestLoadMore())
    }

    @Test
    fun `在途时连点加载更多只放行一笔`() {
        val session = PageSession(startPage = 1)

        assertEquals(1, session.requestLoadMore())
        assertNull(session.requestLoadMore())
    }

    @Test
    fun `到底后请求加载更多返回空`() {
        val session = PageSession(startPage = 1)
        session.requestLoadMore()
        session.onLoadMoreSuccess(1, hasMore = false)

        assertNull(session.requestLoadMore())
    }

    @Test
    fun `刷新结果把游标放到刷新页的下一页`() {
        val session = PageSession(startPage = 2)

        // 书评区首屏走刷新、固定取第 1 页；刷新落地后第一次触底该要第 2 页
        session.startRefresh()
        session.onRefreshResult(1, hasMore = true)

        assertEquals(2, session.requestLoadMore())
    }

    @Test
    fun `刷新结果重新放行已到底的加载更多`() {
        val session = PageSession(startPage = 2)
        session.requestLoadMore()
        session.onLoadMoreSuccess(2, hasMore = false)
        assertNull(session.requestLoadMore())

        session.startRefresh()
        session.onRefreshResult(1, hasMore = true)

        assertEquals(2, session.requestLoadMore())
    }

    @Test
    fun `刷新结果先停刷新再同步是否还有更多`() {
        val session = PageSession(startPage = 1)

        val signals = session.onRefreshResult(1, hasMore = false)

        // 顺序承重：基类状态机收到刷新结束信号时会自动把 hasMore 复位为 true，
        // 显式结论必须排在 StopRefresh 之后才能覆盖复位值——换序即红
        assertEquals(listOf(PageSignal.StopRefresh, PageSignal.HasMore(false)), signals)
    }

    @Test
    fun `加载更多成功按先结论后收口的顺序给信号并推进游标`() {
        val session = PageSession(startPage = 1)
        session.requestLoadMore()

        val signals = session.onLoadMoreSuccess(1, hasMore = true)

        assertEquals(listOf(PageSignal.HasMore(true), PageSignal.StopLoadMore(true)), signals)
        assertEquals(2, session.requestLoadMore())
    }

    @Test
    fun `加载更多成功判到底时置位并停止放行`() {
        val session = PageSession(startPage = 1)
        session.requestLoadMore()

        val signals = session.onLoadMoreSuccess(1, hasMore = false)

        assertEquals(listOf(PageSignal.HasMore(false), PageSignal.StopLoadMore(true)), signals)
        assertNull(session.requestLoadMore())
    }

    @Test
    fun `加载更多失败释放闸门且游标不动可原页重试`() {
        val session = PageSession(startPage = 1)
        session.requestLoadMore()

        val signals = session.onLoadMoreFailure()

        assertEquals(listOf(PageSignal.StopLoadMore(false)), signals)
        assertEquals("游标不动：失败页原样重试", 1, session.requestLoadMore())
    }

    @Test
    fun `到底后刷新仍然放行`() {
        val session = PageSession(startPage = 1)
        session.requestLoadMore()
        session.onLoadMoreSuccess(1, hasMore = false)

        assertEquals("刷新没有闸门：到底与否都放行", 1, session.startRefresh())
    }
}
