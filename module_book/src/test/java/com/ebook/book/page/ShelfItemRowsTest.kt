package com.ebook.book.page

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 书架条目的形态判据（实现见 [shelfInfoRows]）：几行有效信息就走几行的壳，
 * 布局内部不留分支，所以这条判据得单独钉住——它错一格就是一张顶对齐、底下空一整片的卡。
 */
class ShelfItemRowsTest {

    @Test
    fun `书名加读至加作者凑出三行走三行形态`() {
        assertEquals(3, shelfInfoRows(readToChapter = "第 120 章 山雨欲来", author = "笔墨三石", chapterCount = 800))
    }

    @Test
    fun `没有作者但有章节数时元信息行仍然成立`() {
        // 共 N 章是本地书与作者解析不出来时的兜底，它照样占一行
        assertEquals(3, shelfInfoRows(readToChapter = "第 3 章", author = "", chapterCount = 42))
    }

    @Test
    fun `没读到任何章节时只剩书名与元信息两行`() {
        assertEquals(2, shelfInfoRows(readToChapter = "", author = "唐风", chapterCount = 0))
    }

    @Test
    fun `既没读到章节也没有作者与章数时只剩书名一行`() {
        assertEquals(1, shelfInfoRows(readToChapter = "", author = "", chapterCount = 0))
    }
}
