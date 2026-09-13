package com.ebook.common.repository

import com.ebook.db.entity.ChapterListEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ChapterTocDiff] 的单元测试（纯 JVM，零假件）。
 *
 * 锁住不变式 I1「前缀不变式」：只有「本地逐位是远端的前缀」才允许写入，否则整笔放弃。
 * 这一层之所以必须独立成纯函数：判错一次的后果是**静默读错章**
 * （章文件按序号命名，序号一漂移内容与文件就对不上，不报错也不闪退），
 * 而这种后果无法在集成测试里用眼睛发现，只能靠穷举边界形态钉住。
 */
class ChapterTocDiffTest {

    private companion object {
        const val NOTE_URL = "a.example/book/1.html"
        const val SOURCE = "https://a.example"

        /** 造第 [index] 章：contentRef 按序号变化，章名同序 */
        fun chapter(index: Int, name: String = "第${index + 1}章") = ChapterListEntity(
            noteUrl = NOTE_URL,
            durChapterIndex = index,
            contentRef = "$SOURCE/chapter/${index + 1}.html",
            durChapterName = name,
            tag = SOURCE,
        )

        /** 本地 [count] 章、序号连续从 0 */
        fun at(count: Int) = (0 until count).map { chapter(it) }

        /** 把第 [at] 位换成另一个 contentRef（模拟站点改了章节地址） */
        fun withRef(list: List<ChapterListEntity>, at: Int, ref: String): List<ChapterListEntity> =
            list.mapIndexed { i, c -> if (i == at) c.copy(contentRef = ref) else c }
    }

    @Test
    fun `远端与本地逐位相同则为 UpToDate`() {
        assertEquals(TocDiff.UpToDate, ChapterTocDiff.diff(at(3), at(3)))
    }

    @Test
    fun `两侧都是空目录则为 UpToDate`() {
        assertEquals(TocDiff.UpToDate, ChapterTocDiff.diff(emptyList(), emptyList()))
    }

    @Test
    fun `远端多出一章时 tail 只有那一章`() {
        val result = ChapterTocDiff.diff(at(3), at(4))
        assertTrue("实际 $result", result is TocDiff.Appendable)
        assertEquals(listOf(chapter(3)), (result as TocDiff.Appendable).tail)
    }

    @Test
    fun `远端多出多章时 tail 保序且数量正确`() {
        val result = ChapterTocDiff.diff(at(2), at(7)) as TocDiff.Appendable
        assertEquals((2 until 7).map { chapter(it) }, result.tail)
    }

    @Test
    fun `本地为空而远端非空时带出全部远端`() {
        // 加书架时目录抓取失败的书，下一次重抓走这条路径把目录补齐
        val result = ChapterTocDiff.diff(emptyList(), at(3)) as TocDiff.Appendable
        assertEquals(at(3), result.tail)
    }

    @Test
    fun `首章 contentRef 就不同则分叉`() {
        val remote = withRef(at(4), at = 0, ref = "https://a.example/other/1.html")
        assertEquals(TocDiff.Diverged, ChapterTocDiff.diff(at(3), remote))
    }

    @Test
    fun `中段某章不同则分叉`() {
        // 中间插章或删章重排都落在这个形态：位置一错，后面每一章都对不上
        val remote = withRef(at(4), at = 1, ref = "https://a.example/other/2.html")
        assertEquals(TocDiff.Diverged, ChapterTocDiff.diff(at(3), remote))
    }

    @Test
    fun `远端比本地短时判分叉而非跟着删本地`() {
        // 跟着删会吃掉用户已下载的章文件；判分叉把处置权交回用户
        assertEquals(TocDiff.Diverged, ChapterTocDiff.diff(at(5), at(3)))
    }

    @Test
    fun `contentRef 全同但章名不同时仍判 UpToDate`() {
        // 章名不参与判定：站点改标题不代表换了书，拿它参与会把同一章判成分叉
        val remote = listOf(chapter(0, name = "改名了"), chapter(1, name = "也改名了"))
        assertEquals(TocDiff.UpToDate, ChapterTocDiff.diff(at(2), remote))
    }

    @Test
    fun `本地有序号洞时仍能正常追加`() {
        // 真实成因：用户删过站点第 3、4 章，剩余行的 durChapterIndex 是 0,1,4,5，
        // contentRef 仍是原来的第 1、2、5、6 章。此时远端多出第 7 章，属于可追加。
        // 这条是给「按位置逐位比对」准备的：那种写法会把用户删过章的书判成一世分叉、再也追不了更。
        val holed = listOf(chapter(0), chapter(1), chapter(4), chapter(5))
        val result = ChapterTocDiff.diff(holed, at(7)) as TocDiff.Appendable
        assertEquals(listOf(chapter(6)), result.tail)
    }

    @Test
    fun `本地有洞且远端多出多章时 tail 从本地末章之后算起`() {
        val holed = listOf(chapter(0), chapter(1), chapter(4), chapter(5))
        val result = ChapterTocDiff.diff(holed, at(9)) as TocDiff.Appendable
        assertEquals(listOf(chapter(6), chapter(7), chapter(8)), result.tail)
    }

    @Test
    fun `本地相对顺序与远端不一致时判分叉`() {
        // 站点重排过章节：本地按 durChapterIndex 排出来后，contentRef 的远端位序是乱的。
        // 这时"末章之前还有本地末章之后的章"，硬追加会把顺序错得更远，放弃更稳妥。
        val reordered = listOf(
            chapter(0).copy(durChapterIndex = 0, contentRef = "$SOURCE/chapter/1.html"),
            chapter(2).copy(durChapterIndex = 1, contentRef = "$SOURCE/chapter/3.html"),
            chapter(1).copy(durChapterIndex = 2, contentRef = "$SOURCE/chapter/2.html"),
        )
        assertEquals(TocDiff.Diverged, ChapterTocDiff.diff(reordered, at(4)))
    }

    @Test
    fun `输入乱序时内部按序号排序后仍得出正确结论`() {
        // @Relation 关联查询不带 ORDER BY、按物理 rowid 返回，上游传入的顺序不保证
        val shuffledLocal = listOf(chapter(1), chapter(0), chapter(2))
        val shuffledRemote = listOf(chapter(3), chapter(2), chapter(0), chapter(1))
        val result = ChapterTocDiff.diff(shuffledLocal, shuffledRemote) as TocDiff.Appendable
        assertEquals(listOf(chapter(3)), result.tail)
    }

    @Test
    fun `本地有洞时 tail 长度不等于两者长度差`() {
        // 与上一条同族但口径不同：改成按 contentRef 定位后，洞会让 tail 比"长度差"更少。
        // 这条锁的是「不会把本地缺的那几章当成新章重复插回去」。
        val holed = listOf(chapter(0), chapter(1), chapter(4), chapter(5))
        val result = ChapterTocDiff.diff(holed, at(8)) as TocDiff.Appendable
        assertEquals("本地末章之后只剩第 6、7 两章，缺掉的第 3、4 章不该被补回来", 2, result.tail.size)
    }
}
