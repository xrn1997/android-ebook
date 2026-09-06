package com.ebook.common.analyze.local

import com.ebook.common.text.TextNormalizer
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ChapterSplitter] 单测。这是被从"扫描循环里直接 insert 数据库"中解放出来的那段逻辑，
 * 从此可单测——旧实现 `BookImportManager（已删除）` 零测试覆盖正是因为切分与写库焊在一起。
 */
class ChapterSplitterTest {

    private val splitter = ChapterSplitter()

    private suspend fun split(vararg lines: String): List<ChapterSplitter.RawChapter> =
        splitter.split(lines.asSequence().map { TextNormalizer.cleanParagraph(it) }).toList()

    @Test
    fun splitsChaptersByTitleRule() = runTest {
        val chapters = split(
            "第一章 起", "正文甲", "", "第二章 承", "正文乙", "第三章 转", "正文丙"
        )
        assertEquals(3, chapters.size)
        assertEquals(listOf("第一章 起", "第二章 承", "第三章 转"), chapters.map { it.title })
        assertEquals(listOf(0, 1, 2), chapters.map { it.index })
        assertEquals(listOf("正文甲", "正文乙", "正文丙"), chapters.map { it.paragraphs.single() })
    }

    @Test
    fun titleMayCarryNumberOnlyForm() = runTest {
        val chapters = split("第12章 风雨欲来", "正文")
        assertEquals("第12章 风雨欲来", chapters.single().title)
    }

    @Test
    fun bookWithoutTitlesBecomesSingleChapterNamedByFirstLine() = runTest {
        val chapters = split("开篇第一段", "第二段", "第三段")
        assertEquals(1, chapters.size)
        assertEquals("开篇第一段", chapters.single().title)
        assertEquals(3, chapters.single().paragraphs.size)
    }

    @Test
    fun emptyChaptersAreNotEmitted() = runTest {
        // 只有标题没有正文的章不该占一个索引位
        val chapters = split("第一章 空", "第二章 有内容", "正文")
        assertEquals(1, chapters.size)
        assertEquals("第二章 有内容", chapters.single().title)
    }

    @Test
    fun blankLinesAreDropped() = runTest {
        val chapters = split("第一章 起", "", "   ", "正文")
        assertEquals(listOf("正文"), chapters.single().paragraphs)
    }

    @Test
    fun leadingTitleStillStartsAtIndexZero() = runTest {
        val chapters = split("第一章 起", "正文")
        assertEquals(0, chapters.single().index)
    }

    @Test
    fun proseMentioningChapterMarkerSplitsChapter() = runTest {
        // 已知取舍：正文里出现"第三章"字样的整行会被当标题。与旧实现行为一致，
        // 这里把它锁成显式契约而不是留作意外
        val chapters = split("第一章 起", "他想起第三章的情节", "正文")
        assertEquals(2, chapters.size)
        assertEquals("第三章的情节", chapters[1].title)
    }
}
