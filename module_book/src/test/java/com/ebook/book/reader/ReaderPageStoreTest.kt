package com.ebook.book.reader

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ReaderPageStore] 的回归测试（纯 JVM，不渲染、不依赖 Context）。
 *
 * 锁三条仓库层自己的口径——它们与前端几何无关、两种翻页方式都依赖：
 * 在途去重、已 Loaded 不重抓、[ReaderPageStore.retain] 只清保留集之外。
 * 窗口收敛规则不在这里，那是翻页前端的事，由 [ReaderPagerControllerTest] 锁。
 */
class ReaderPageStoreTest {

    @Test
    fun `在途页不重复发请求`() = runTest {
        val book = FakeStoreBook()
        book.gates[KEY] = CompletableDeferred()
        val store = newStore(book)

        store.ensureLoad(KEY)
        store.ensureLoad(KEY)
        advanceUntilIdle()

        assertEquals("在途 job 已登记，第二次 ensureLoad 必须被挡下", 1, book.requests[KEY])

        // 放行闸门收尾：runTest 要等前台 TestScope 的子协程结束，闸门悬着就是 60s 超时
        book.gates.getValue(KEY).complete(Unit)
        advanceUntilIdle()
    }

    @Test
    fun `已加载的页不重抓`() = runTest {
        val book = FakeStoreBook()
        val store = newStore(book)

        store.ensureLoad(KEY)
        advanceUntilIdle()
        store.ensureLoad(KEY)
        advanceUntilIdle()

        // job 完成即从 jobs 注销，只看 jobs 去重会让前端重算把刚读过的页打回 Loading 再抓一遍
        assertEquals(1, book.requests[KEY])
        assertTrue(store.uiOf(KEY) is ReaderPageUi.Loaded)
    }

    @Test
    fun `加载失败置错误态且 reload 会重发`() = runTest {
        val book = FakeStoreBook()
        book.fail(KEY)
        val store = newStore(book)

        store.ensureLoad(KEY)
        advanceUntilIdle()
        assertEquals(ReaderPageUi.Error, store.uiOf(KEY))

        store.reload(KEY)
        advanceUntilIdle()

        assertEquals("Error 不是 Loaded，不被 ensureLoad 的短路挡下", 2, book.requests[KEY])
    }

    @Test
    fun `retain 清掉保留集之外的页并取消其在途任务`() = runTest {
        val book = FakeStoreBook()
        book.gates[OTHER] = CompletableDeferred()
        val store = newStore(book)

        store.ensureLoad(KEY)
        store.ensureLoad(OTHER)
        advanceUntilIdle()

        store.retain(setOf(KEY))

        assertTrue("保留集内的页不动", store.uiOf(KEY) is ReaderPageUi.Loaded)
        assertEquals(
            "保留集外的在途任务被取消，uiOf 回到加载中兜底而不是残留陈旧状态",
            ReaderPageUi.Loading, store.uiOf(OTHER)
        )
    }

    @Test
    fun `clear 之后同一页会重新发请求`() = runTest {
        val book = FakeStoreBook()
        val store = newStore(book)

        store.ensureLoad(KEY)
        advanceUntilIdle()
        store.clear()
        store.ensureLoad(KEY)
        advanceUntilIdle()

        // clear 是换装点（字号/跳章/换翻页方式），必须真的让 Loaded 短路失效
        assertEquals(2, book.requests[KEY])
    }

    @Test
    fun `onLoaded 只在成功时回调一次`() = runTest {
        val book = FakeStoreBook()
        book.fail(OTHER)
        val hits = mutableListOf<ReaderPageKey>()
        val store = ReaderPageStore(this) { c, p -> book.load(c, p) }.apply {
            onLoaded = { key, _ -> hits += key }
        }

        store.ensureLoad(KEY)
        store.ensureLoad(OTHER)
        advanceUntilIdle()

        assertEquals("失败页只置 Error、不回调，前端据此不会拿它去重算几何", listOf(KEY), hits)
    }

    private companion object {
        val KEY = ReaderPageKey(0, 0)
        val OTHER = ReaderPageKey(0, 1)
    }
}

/**
 * 假书：只够仓库层用（无章节数概念）。
 *
 * 失败与闸门语义与 [ReaderPagerControllerTest] 的 FakeBook 一致：[gates] 用来确定性地
 * 制造「请求已发出但尚未完成」的窗口，不靠线程调度碰运气。
 */
private class FakeStoreBook {

    val requests = mutableMapOf<ReaderPageKey, Int>()
    val gates = mutableMapOf<ReaderPageKey, CompletableDeferred<Unit>>()
    private val failures = mutableSetOf<ReaderPageKey>()

    fun fail(vararg keys: ReaderPageKey) {
        failures += keys
    }

    suspend fun load(chapterIndex: Int, pageIndex: Int): ReaderPageUi.Loaded? {
        val key = ReaderPageKey(chapterIndex, pageIndex)
        requests[key] = (requests[key] ?: 0) + 1
        gates[key]?.await()
        if (key in failures) return null
        return ReaderPageUi.Loaded(
            title = "第${chapterIndex + 1}章",
            chapterIndex = chapterIndex,
            durPageIndex = pageIndex,
            pageAll = 3,
            text = "正文 $chapterIndex-$pageIndex"
        )
    }
}

private fun TestScope.newStore(book: FakeStoreBook) =
    ReaderPageStore(this) { c, p -> book.load(c, p) }
