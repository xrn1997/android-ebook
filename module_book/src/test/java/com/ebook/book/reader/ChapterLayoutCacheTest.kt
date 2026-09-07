package com.ebook.book.reader

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 排版偏移缓存的测试。锁的是**失效键**：字号、宽度或正文本身变了都必须重算，否则页数与
 * 折行会与当前内容/样式不符（`ReaderTypesetter` 的 KDoc 记录过这类"被裁掉的一行"缺陷）。
 */
class ChapterLayoutCacheTest {

    @Test
    fun `同键翻页只排版一次`() {
        val cache = ChapterLayoutCache(capacity = 4)
        var calls = 0
        val key = ChapterLayoutKey("ref", 100, 42f, 800)

        repeat(3) { cache.getOrCompute(key) { calls++; listOf(0, 5) } }

        assertEquals(1, calls)
    }

    @Test
    fun `改字号是新键因而重排`() {
        val cache = ChapterLayoutCache(capacity = 4)
        var calls = 0

        cache.getOrCompute(ChapterLayoutKey("ref", 100, 42f, 800)) { calls++; listOf(0) }
        cache.getOrCompute(ChapterLayoutKey("ref", 100, 50f, 800)) { calls++; listOf(0) }

        assertEquals("字号变了要重算", 2, calls)
    }

    @Test
    fun `重解析后 contentRef 不变也要重排`() {
        val cache = ChapterLayoutCache(capacity = 4)
        var calls = 0

        // 「强制刷新缓存」重抓：网络书的 content_ref 就是章节 URL，重抓前后不变，只有正文变了。
        // 键里不带内容指纹的话，这里会把新正文配上旧行偏移——页数没变但内容接不上
        cache.getOrCompute(ChapterLayoutKey("https://src/1.html", 100, 42f, 800)) { calls++; listOf(0) }
        cache.getOrCompute(ChapterLayoutKey("https://src/1.html", 137, 42f, 800)) { calls++; listOf(0) }

        assertEquals("正文换了不能沿用旧行偏移", 2, calls)
    }

    @Test
    fun `clear 清空全部书的排版结果`() {
        val cache = ChapterLayoutCache(capacity = 8)
        var calls = 0
        cache.getOrCompute(ChapterLayoutKey("books/A/c00000.txt", 10, 42f, 800)) { calls++; listOf(0) }
        cache.getOrCompute(ChapterLayoutKey("books/B/c00000.txt", 10, 42f, 800)) { calls++; listOf(0) }

        cache.clear()
        cache.getOrCompute(ChapterLayoutKey("books/A/c00000.txt", 10, 42f, 800)) { calls++; listOf(0) }
        cache.getOrCompute(ChapterLayoutKey("books/B/c00000.txt", 10, 42f, 800)) { calls++; listOf(0) }

        assertEquals(4, calls)
    }
}
