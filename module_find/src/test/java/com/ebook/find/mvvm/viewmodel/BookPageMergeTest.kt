package com.ebook.find.mvvm.viewmodel

import com.ebook.db.entity.SearchBookEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [mergeBookPage] 的单元测试（纯 JVM，无 Android 依赖）。
 *
 * 覆盖分类页/搜索页「加载更多」的两条不变量：列表内 noteUrl 唯一（列表页以它作 item key，
 * 重复即抛异常），以及「整页无新条目 = 到底」。后者是内置书源的软 404 形态逼出来的——
 * 越界页返回 HTTP 200 + 首页书目，靠「空页」永远判不到底。
 */
class BookPageMergeTest {

    private fun book(url: String) = SearchBookEntity(noteUrl = url, name = url.substringAfterLast('/'))

    @Test
    fun `新页全部条目按原顺序追加`() {
        val merged = mergeBookPage(listOf(book("/1"), book("/2")), listOf(book("/3"), book("/4")))

        assertEquals(listOf("/1", "/2", "/3", "/4"), merged?.map { it.noteUrl })
    }

    @Test
    fun `整页重复时返回空表示没有更多`() {
        val current = listOf(book("/1"), book("/2"))

        assertNull(mergeBookPage(current, listOf(book("/1"), book("/2"))))
    }

    @Test
    fun `返回空页同样视为没有更多`() {
        assertNull(mergeBookPage(listOf(book("/1")), emptyList()))
    }

    @Test
    fun `只追加本页里没见过的条目`() {
        val merged = mergeBookPage(listOf(book("/1")), listOf(book("/1"), book("/2")))

        assertEquals(listOf("/1", "/2"), merged?.map { it.noteUrl })
    }

    @Test
    fun `页内自身重复的条目只保留一条`() {
        val merged = mergeBookPage(emptyList(), listOf(book("/1"), book("/1"), book("/2")))

        assertEquals(listOf("/1", "/2"), merged?.map { it.noteUrl })
    }
}
