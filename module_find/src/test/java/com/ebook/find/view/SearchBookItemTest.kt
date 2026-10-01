package com.ebook.find.view

import com.ebook.db.entity.SearchBookEntity
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 列表项第二行的取文优先级（口径与理由见 [listBlurb]）。
 *
 * 这一行是列表里唯一「两个来源抢一个位置」的地方：简介讲「这是本什么书」，最新章讲
 * 「更到哪了」。第三方源两边都可能有、也可能只有其一，所以规则必须是**有简介就用简介，
 * 没简介才退回最新章**，而不是反过来——末章名几乎总存在，反向写法会让简介永远露不了面。
 */
class SearchBookItemTest {

    @Test
    fun `有简介时第二行显示简介而不是最新章节`() {
        val book = SearchBookEntity(
            desc = "一个山村少年的逆袭",
            lastChapter = "第 1 章 开局一枚面板",
        )

        assertEquals("一个山村少年的逆袭", listBlurb(book))
    }

    @Test
    fun `没有简介时第二行回落成最新章节`() {
        val book = SearchBookEntity(
            desc = "",
            lastChapter = "第 1 章 开局一枚面板",
        )

        assertEquals("第 1 章 开局一枚面板", listBlurb(book))
    }

    @Test
    fun `简介与最新章节都没有时第二行为空`() {
        // 两格都解析不出是第三方源的真实形态（页面结构对不上规则）。此时该行留空由
        // 条目自己按「按条数选形态」处理，不能拿书名之类的字段去顶。
        assertEquals("", listBlurb(SearchBookEntity()))
    }

    /**
     * 形态选择的判据（实现见 [searchBookInfoRows]）：布局里不留分支，改由条数决定
     * 走三行还是两行，所以这条判据本身必须钉住——它错一格就是一张顶对齐、
     * 底下空一整片的卡。
     */
    @Test
    fun `书名加简介加作者凑出三行走三行形态`() {
        val book = SearchBookEntity(
            desc = "一枚子嗣面板",
            author = "笔墨三石",
            origin = "笔趣阁",
        )
        assertEquals(3, searchBookInfoRows(book, showOrigin = true))
    }

    @Test
    fun `没有简介只有末章与作者时也是三行`() {
        // 末章名是简介的回落来源，同样占第二行
        val book = SearchBookEntity(lastChapter = "第 1163 章 反攻开始", author = "唐风")
        assertEquals(3, searchBookInfoRows(book, showOrigin = false))
    }

    @Test
    fun `只有一行附加信息时走两行形态`() {
        assertEquals(2, searchBookInfoRows(SearchBookEntity(desc = "只有简介"), true))
        assertEquals(2, searchBookInfoRows(SearchBookEntity(author = "只有作者"), true))
    }

    @Test
    fun `关掉书源后仅剩书源可算的信息就不算一行`() {
        // 分类选书页 showOrigin=false：那一格本来就是噪声，不能因为它撑出「有底行」的假象
        val book = SearchBookEntity(origin = "笔趣阁")
        assertEquals(2, searchBookInfoRows(book, showOrigin = true))
        assertEquals(1, searchBookInfoRows(book, showOrigin = false))
    }

    @Test
    fun `什么都没解析出来时只剩书名一行`() {
        assertEquals(1, searchBookInfoRows(SearchBookEntity(), true))
    }
}
