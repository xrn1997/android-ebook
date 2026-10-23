package com.ebook.find.search

import com.ebook.db.entity.SearchBookEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AggregationSessionTest {

    private fun book(url: String, name: String = "书") =
        SearchBookEntity(noteUrl = url, name = name, author = "作者")

    @Test
    fun `首轮从第一页开始且不跳过任何源`() {
        val session = AggregationSession()
        session.startSearch("都市")

        val plan = session.beginRound()

        assertEquals("都市", plan.keyword)
        assertEquals(1, plan.page)
        assertTrue(plan.skipSourceUrls.isEmpty())
    }

    @Test
    fun `结果只返回全局未见过的新条目`() {
        val session = AggregationSession()
        session.startSearch("都市")
        session.beginRound()
        session.onSourceStarted("A")

        val freshA = session.onSourceResult("A", listOf(book("a1"), book("a1"), book("a2")))
        session.onSourceStarted("B")
        val freshB = session.onSourceResult("B", listOf(book("a2"), book("b1")))

        assertEquals(listOf("a1", "a2"), freshA.map { it.noteUrl })
        assertEquals(listOf("b1"), freshB.map { it.noteUrl })
    }

    @Test
    fun `有新增且 hasMore 时游标推进`() {
        val session = AggregationSession()
        session.startSearch("都市")
        session.beginRound()
        session.onSourceStarted("A")
        session.onSourceResult("A", listOf(book("a1")))
        session.onSourceFinished("A", hasMore = true)

        val next = session.beginRound()

        assertEquals(2, next.page)
        assertTrue(next.skipSourceUrls.isEmpty())
    }

    @Test
    fun `连续两轮新增后第三轮请求第三页且不跳过源`() {
        val session = AggregationSession()
        session.startSearch("都市")

        session.beginRound()
        session.onSourceStarted("A")
        session.onSourceResult("A", listOf(book("a1")))
        session.onSourceFinished("A", hasMore = true)
        session.onSourceStarted("B")
        session.onSourceResult("B", listOf(book("b1")))
        session.onSourceFinished("B", hasMore = true)

        session.beginRound()
        session.onSourceStarted("A")
        session.onSourceResult("A", listOf(book("a2")))
        session.onSourceFinished("A", hasMore = true)
        session.onSourceStarted("B")
        session.onSourceResult("B", listOf(book("b2")))
        session.onSourceFinished("B", hasMore = true)

        val third = session.beginRound()

        assertEquals(3, third.page)
        assertTrue(third.skipSourceUrls.isEmpty())
    }

    @Test
    fun `软 404 零新增时结束该源`() {
        val session = AggregationSession()
        session.startSearch("都市")
        session.beginRound()
        session.onSourceStarted("A")
        session.onSourceResult("A", listOf(book("a1")))
        session.onSourceFinished("A", hasMore = true)

        session.beginRound()
        session.onSourceStarted("A")
        session.onSourceResult("A", listOf(book("a1")))
        session.onSourceFinished("A", hasMore = true)

        val next = session.beginRound()
        assertEquals(setOf("A"), next.skipSourceUrls)
    }

    @Test
    fun `空结果也结束该源`() {
        val session = AggregationSession()
        session.startSearch("都市")
        session.beginRound()
        session.onSourceStarted("A")
        session.onSourceResult("A", emptyList())
        session.onSourceFinished("A", hasMore = true)

        assertTrue(session.beginRound().skipSourceUrls.contains("A"))
    }

    @Test
    fun `加载更多只取活跃源的最小游标并跳过已结束源`() {
        val session = AggregationSession()
        session.startSearch("都市")
        session.beginRound()
        session.onSourceStarted("A")
        session.onSourceResult("A", listOf(book("a1")))
        session.onSourceFinished("A", hasMore = false)
        session.onSourceStarted("B")
        session.onSourceResult("B", listOf(book("b1")))
        session.onSourceFinished("B", hasMore = true)

        val next = session.beginRound()

        assertEquals(2, next.page)
        assertEquals(setOf("A"), next.skipSourceUrls)
    }

    @Test
    fun `全部源失败才判 allFailed`() {
        val session = AggregationSession()
        session.startSearch("都市")
        session.beginRound()
        session.onSourceStarted("A")
        session.onSourceFailed("A")
        session.onSourceFinished("A", hasMore = false)

        val outcome = session.finishRound(null)

        assertEquals(SearchProgress(finished = 1, total = 1), outcome.progress)
        assertTrue(outcome.allFailed)
        assertFalse(outcome.hasMore)
    }

    @Test
    fun `部分源失败但仍有活跃源时不算 allFailed 且有更多`() {
        val session = AggregationSession()
        session.startSearch("都市")
        session.beginRound()
        session.onSourceStarted("A")
        session.onSourceFailed("A")
        session.onSourceFinished("A", hasMore = false)
        session.onSourceStarted("B")
        session.onSourceResult("B", listOf(book("b1")))
        session.onSourceFinished("B", hasMore = true)

        val outcome = session.finishRound(null)

        assertEquals(SearchProgress(finished = 2, total = 2), outcome.progress)
        assertFalse(outcome.allFailed)
        assertTrue(outcome.hasMore)
    }

    @Test
    fun `空轮携带致命异常时判 allFailed 且没有更多`() {
        val session = AggregationSession()
        session.startSearch("都市")
        session.beginRound()

        val outcome = session.finishRound(RuntimeException())

        assertEquals(SearchProgress(finished = 0, total = 0), outcome.progress)
        assertTrue(outcome.allFailed)
        assertFalse(outcome.hasMore)
    }

    @Test
    fun `AllFinished 补齐消失源的进度并保留活跃源`() {
        val session = AggregationSession()
        session.startSearch("都市")
        session.beginRound()
        session.onSourceStarted("A")
        session.onSourceStarted("B")
        session.onSourceResult("B", listOf(book("b1")))
        session.onSourceFinished("B", hasMore = true)

        val outcome = session.finishRound(null)

        assertEquals(SearchProgress(finished = 2, total = 2), outcome.progress)
        assertFalse(outcome.allFailed)
        assertTrue(outcome.hasMore)
    }

    @Test
    fun `空源集合的 AllFinished 不算失败且没有更多`() {
        val session = AggregationSession()
        session.startSearch("都市")
        session.beginRound()

        val outcome = session.finishRound(null)

        assertEquals(SearchProgress(finished = 0, total = 0), outcome.progress)
        assertFalse(outcome.allFailed)
        assertFalse(outcome.hasMore)
    }

    @Test
    fun `所有源都到底时收尾没有更多`() {
        val session = AggregationSession()
        session.startSearch("都市")
        session.beginRound()
        session.onSourceStarted("A")
        session.onSourceResult("A", listOf(book("a1")))
        session.onSourceFinished("A", hasMore = false)

        val outcome = session.finishRound(null)

        assertFalse(outcome.allFailed)
        assertFalse(outcome.hasMore)
        assertEquals(SearchProgress(finished = 1, total = 1), outcome.progress)
    }

    @Test
    fun `非空轮遇致命异常时补齐进度且没有更多`() {
        val session = AggregationSession()
        session.startSearch("都市")
        session.beginRound()
        session.onSourceStarted("A")

        val outcome = session.finishRound(RuntimeException("boom"))

        assertTrue(outcome.allFailed)
        assertFalse(outcome.hasMore)
        assertEquals(SearchProgress(finished = 1, total = 1), outcome.progress)
    }

    @Test
    fun `换关键词后游标与已见条目一起重置`() {
        val session = AggregationSession()
        session.startSearch("旧词")
        // 每轮都有新增且 hasMore，A 的游标逐轮推进；四轮后游标停在 5，换词前再让 A 结束
        (1..4).forEach { round ->
            session.beginRound()
            session.onSourceStarted("A")
            session.onSourceResult("A", listOf(book("a$round")))
            session.onSourceFinished("A", hasMore = true)
        }
        session.onSourceFinished("A", hasMore = false)

        session.startSearch("新词")
        assertEquals(1, session.beginRound().page)

        // 只登记、不交结果：新轮游标能否留在第一页，全看 startSearch 有没有清空 pageBySource。
        // 若不清空，putIfAbsent 会保留旧词留下的 5，下一轮 beginRound 就会请求第 5 页。
        session.onSourceStarted("A")
        val next = session.beginRound()

        assertEquals(1, next.page)
        assertTrue(next.skipSourceUrls.isEmpty())

        val fresh = session.onSourceResult("A", listOf(book("a4")))

        assertEquals(listOf("a4"), fresh.map { it.noteUrl })
    }

    @Test
    fun `进度随 Started 与 Finished 变化`() {
        val session = AggregationSession()
        session.startSearch("都市")
        session.beginRound()
        assertEquals(SearchProgress(0, 0), session.progress)
        session.onSourceStarted("A")
        assertEquals(SearchProgress(0, 1), session.progress)
        session.onSourceFinished("A", hasMore = false)
        assertEquals(SearchProgress(1, 1), session.progress)
    }
}
