package com.ebook.source.analyze

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PageWalker] 的机制回归：零新增终止、回环守卫、页/条目上限与去重开关。
 *
 * 四条翻页链（原生/脚本 × 目录/正文）原先各写一份循环；这里锁住共享机制，
 * 各链只保留「下一页怎么算、何时停」的策略。
 */
class PageWalkerTest {

    private fun page(url: String, vararg items: String) = WalkPage(url, items.toList())

    private suspend fun walk(
        entry: String = "a",
        pages: Map<String, WalkPage<List<String>>>,
        next: (WalkPage<List<String>>) -> String?,
        maxPages: Int = Int.MAX_VALUE,
        maxItems: Int = Int.MAX_VALUE,
        stopOnNoNewItems: Boolean = false,
        guardLoops: Boolean = true,
        keyOf: (String) -> String? = { it },
    ): WalkResult<String> {
        val fetched = mutableListOf<String>()
        val result = PageWalker<String, List<String>>(
            maxPages = maxPages,
            maxItems = maxItems,
            stopOnNoNewItems = stopOnNoNewItems,
            guardLoops = guardLoops,
            keyOf = keyOf,
        ).walk(
            entryUrl = entry,
            fetch = { url ->
                fetched += url
                pages.getValue(url)
            },
            items = { it.payload.asSequence() },
            next = next,
        )
        this.fetched = fetched
        return result
    }

    private var fetched: List<String> = emptyList()

    @Test
    fun `零新增即停且不再请求更后一页`() = runTest {
        val result = walk(
            pages = mapOf(
                "a" to page("a", "x"),
                "b" to page("b"),
                "c" to page("c", "y"),
            ),
            next = { when (it.url) { "a" -> "b"; "b" -> "c"; else -> null } },
            stopOnNoNewItems = true,
        )

        assertEquals(listOf("x"), result.items)
        assertFalse(result.truncated)
        assertEquals("零新增页必须取回才判得出；判定为到底后不得再取 c", listOf("a", "b"), fetched)
    }

    @Test
    fun `回环地址只请求一次`() = runTest {
        val result = walk(
            pages = mapOf("a" to page("a", "x")),
            next = { "a" },
        )

        assertEquals(listOf("x"), result.items)
        assertFalse(result.truncated)
        assertEquals(listOf("a"), fetched)
    }

    @Test
    fun `页数上限触顶时返回 truncated`() = runTest {
        val result = walk(
            pages = mapOf(
                "a" to page("a", "x"),
                "b" to page("b", "y"),
                "c" to page("c", "z"),
            ),
            next = { when (it.url) { "a" -> "b"; "b" -> "c"; else -> null } },
            maxPages = 2,
        )

        assertEquals(listOf("x", "y"), result.items)
        assertTrue(result.truncated)
        assertEquals(listOf("a", "b"), fetched)
    }

    @Test
    fun `条目上限触顶时返回 truncated`() = runTest {
        val result = walk(
            pages = mapOf("a" to page("a", "x", "y", "z"), "b" to page("b", "w")),
            next = { if (it.url == "a") "b" else null },
            maxItems = 2,
        )

        assertEquals(listOf("x", "y"), result.items)
        assertTrue("本页还有未处理条目，必须标记截断", result.truncated)
        assertEquals(listOf("a"), fetched)
    }

    @Test
    fun `条目上限触顶后不再消费本页剩余条目`() = runTest {
        var consumed = 0
        val result = PageWalker<Int, Unit>(maxItems = 2).walk(
            entryUrl = "a",
            fetch = { WalkPage(it, Unit) },
            items = { (1..1000).asSequence().onEach { consumed++ } },
            next = { null },
        )

        assertEquals(listOf(1, 2), result.items)
        assertTrue("不能把整页 1000 条都物化/消费掉，实际消费 $consumed", consumed < 10)
    }

    @Test
    fun `本页恰好收满上限且已消费完不算截断`() = runTest {
        val result = walk(
            pages = mapOf("a" to page("a", "x", "y"), "b" to page("b", "z")),
            next = { if (it.url == "a") "b" else null },
            maxItems = 2,
        )

        assertEquals(listOf("x", "y"), result.items)
        assertFalse("本页恰好消费完，不应误报分页规则配错", result.truncated)
        assertEquals("触顶后不再探测下一页", listOf("a"), fetched)
    }

    @Test
    fun `keyOf 为 null 时不按条目去重`() = runTest {
        val result = walk(
            pages = mapOf("a" to page("a", "x", "x")),
            next = { null },
            keyOf = { null },
        )

        assertEquals(listOf("x", "x"), result.items)
        assertFalse(result.truncated)
    }

    @Test
    fun `next 返回 null 时正常结束且不标记 truncated`() = runTest {
        val result = walk(
            pages = mapOf("a" to page("a", "x")),
            next = { null },
        )

        assertEquals(listOf("x"), result.items)
        assertFalse(result.truncated)
    }

    @Test
    fun `stopOnNoNewItems 为 false 时零新增仍继续`() = runTest {
        val result = walk(
            pages = mapOf("a" to page("a"), "b" to page("b", "x")),
            next = { if (it.url == "a") "b" else null },
            stopOnNoNewItems = false,
        )

        assertEquals(listOf("x"), result.items)
        assertEquals(listOf("a", "b"), fetched)
    }
}
