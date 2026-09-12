package com.ebook.common.store

import com.ebook.common.analyze.local.ChapterContent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [ChapterContentCache] 单测。锁两件事：同一章只读盘一次；三种失效条件各自生效。
 *
 * 失效条件是这套缓存唯一容易错的地方——读完不失效，用户重导入同一本书（章文件已被新目录
 * 覆盖）后会继续读到内存里的旧内容。
 */
class ChapterContentCacheTest {

    private fun content(mark: String) = ChapterContent("标题", listOf(mark))

    @Test
    fun `同一章重复加载只回源一次`() = runTest {
        val cache = ChapterContentCache(capacity = 3)
        var calls = 0

        repeat(5) { cache.getOrLoad("ref-a") { calls++; content("a") } }

        assertEquals(1, calls)
    }

    @Test
    fun `超容量时逐出最久未使用的章`() = runTest {
        val cache = ChapterContentCache(capacity = 2)
        var calls = 0
        suspend fun touch(ref: String) = cache.getOrLoad(ref) { calls++; content(ref) }

        touch("a"); touch("b"); touch("a"); touch("c")

        assertEquals("a 是最近使用过的，不该被逐出", 3, calls)
        touch("b")
        assertEquals("b 已被逐出，应重新加载", 4, calls)
    }

    @Test
    fun `invalidateBook 只失效该书不波及其他书`() = runTest {
        val cache = ChapterContentCache(capacity = 4)
        var calls = 0
        cache.getOrLoad("books/A/c00000.txt") { calls++; content("x") }
        cache.getOrLoad("books/B/c00000.txt") { calls++; content("y") }

        cache.invalidateBook("A")
        cache.getOrLoad("books/A/c00000.txt") { calls++; content("x") }
        cache.getOrLoad("books/B/c00000.txt") { calls++; content("y") }

        assertEquals("A 重新加载、B 未受影响", 3, calls)
    }

    @Test
    fun `clear 清空后同章重新回源`() = runTest {
        val cache = ChapterContentCache(capacity = 4)
        var calls = 0
        cache.getOrLoad("r") { calls++; content("x") }

        cache.clear()
        cache.getOrLoad("r") { calls++; content("x") }

        assertEquals(2, calls)
    }
}
