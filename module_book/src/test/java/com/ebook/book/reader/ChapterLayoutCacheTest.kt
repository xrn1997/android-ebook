package com.ebook.book.reader

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 排版偏移缓存的测试。锁的是**失效键**：字号或宽度变了必须重算，否则页数与折行会
 * 与当前样式不符（`ReaderTypesetter` 的 KDoc 记录过这类"被裁掉的一行"缺陷）。
 */
class ChapterLayoutCacheTest {

    @Test
    fun sameKeyComputesOnce() {
        val cache = ChapterLayoutCache(capacity = 4)
        var calls = 0
        val key = ChapterLayoutKey("ref", 42f, 800)

        repeat(3) { cache.getOrCompute(key) { calls++; listOf(0, 5) } }

        assertEquals(1, calls)
    }

    @Test
    fun styleChangeIsANewKey() {
        val cache = ChapterLayoutCache(capacity = 4)
        var calls = 0

        cache.getOrCompute(ChapterLayoutKey("ref", 42f, 800)) { calls++; listOf(0) }
        cache.getOrCompute(ChapterLayoutKey("ref", 50f, 800)) { calls++; listOf(0) }

        assertEquals("字号变了要重算", 2, calls)
    }

    @Test
    fun bookInvalidationDropsAllItsChapters() {
        val cache = ChapterLayoutCache(capacity = 8)
        var calls = 0
        cache.getOrCompute(ChapterLayoutKey("books/A/c00000.txt", 42f, 800)) { calls++; listOf(0) }
        cache.getOrCompute(ChapterLayoutKey("books/B/c00000.txt", 42f, 800)) { calls++; listOf(0) }

        cache.invalidateBook("A")
        cache.getOrCompute(ChapterLayoutKey("books/A/c00000.txt", 42f, 800)) { calls++; listOf(0) }
        cache.getOrCompute(ChapterLayoutKey("books/B/c00000.txt", 42f, 800)) { calls++; listOf(0) }

        assertEquals(3, calls)
    }
}
