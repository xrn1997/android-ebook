package com.ebook.common.analyze.local

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
        splitter.split(lines.asSequence()).toList()

    @Test
    fun `按章名规则切分出标题序号与各章正文`() = runTest {
        val chapters = split(
            "第一章 起", "正文甲", "", "第二章 承", "正文乙", "第三章 转", "正文丙"
        )
        assertEquals(3, chapters.size)
        assertEquals(listOf("第一章 起", "第二章 承", "第三章 转"), chapters.map { it.title })
        assertEquals(listOf(0, 1, 2), chapters.map { it.index })
        assertEquals(listOf("正文甲", "正文乙", "正文丙"), chapters.map { it.paragraphs.single() })
    }

    @Test
    fun `阿拉伯数字章号也能识别为章名`() = runTest {
        val chapters = split("第12章 风雨欲来", "正文")
        assertEquals("第12章 风雨欲来", chapters.single().title)
    }

    @Test
    fun `全书无章名时合成单章并以首行命名`() = runTest {
        val chapters = split("开篇第一段", "第二段", "第三段")
        assertEquals(1, chapters.size)
        assertEquals("开篇第一段", chapters.single().title)
        assertEquals(3, chapters.single().paragraphs.size)
    }

    @Test
    fun `只有标题没有正文的章不产出不占索引位`() = runTest {
        // 只有标题没有正文的章不该占一个索引位
        val chapters = split("第一章 空", "第二章 有内容", "正文")
        assertEquals(1, chapters.size)
        assertEquals("第二章 有内容", chapters.single().title)
    }

    @Test
    fun `空白行被丢弃不进正文`() = runTest {
        val chapters = split("第一章 起", "", "   ", "正文")
        assertEquals(listOf("正文"), chapters.single().paragraphs)
    }

    @Test
    fun `开篇即章名时序号仍从 0 起编`() = runTest {
        val chapters = split("第一章 起", "正文")
        assertEquals(0, chapters.single().index)
    }

    @Test
    fun `正文行提及章名字样同样触发切章（锁为显式契约）`() = runTest {
        // 已知取舍：正文里出现"第三章"字样的整行会被当标题。与旧实现行为一致，
        // 这里把它锁成显式契约而不是留作意外
        val chapters = split("第一章 起", "他想起第三章的情节", "正文")
        assertEquals(2, chapters.size)
        assertEquals("第三章的情节", chapters[1].title)
    }

    @Test
    fun `正文段保持原样留给读取层清洗`() = runTest {
        // 存储层不清洗（spec §4 §8）：行首缩进与行内连续空白必须原样留在切片里，
        // 折叠动作发生在读取管线，将来改规范化规则不必重导
        val chapters = split("第一章 起", "　　正文 里 空白", "  尾部空白  ")
        assertEquals(
            listOf("　　正文 里 空白", "  尾部空白  "),
            chapters.single().paragraphs
        )
    }

    @Test
    fun `章名清洗归一而正文保持原样`() = runTest {
        // 章名是元数据（落 chapter_list、参与章名比对与显示），取清洗后的形态
        val chapters = split("　　第三章  归来   ", "正文")
        assertEquals("第三章 归来", chapters.single().title)
    }
}
