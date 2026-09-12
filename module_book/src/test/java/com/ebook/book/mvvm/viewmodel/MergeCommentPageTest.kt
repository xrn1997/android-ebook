package com.ebook.book.mvvm.viewmodel

import com.ebook.common.domain.BookComment
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [mergeCommentPage]（加载更多的页合并）的纯函数回归测试，纯 JVM。
 *
 * 评论分页把「翻页取回的数据如何进列表」收口在这一个函数上：
 * 去重（并发窗口/键变化可能带回已见条目）与全局时间倒序（任何页到达顺序都收敛到一致展示序）。
 */
class MergeCommentPageTest {

    private fun comment(id: Long, time: String) = BookComment(
        id = id, userId = 9L, username = "u$id", avatar = "",
        commentKey = "ck1:t#0", chapterUrl = null, chapterName = null,
        bookName = null, content = "c$id", addTime = time
    )

    @Test
    fun `追加页并按时间倒序重排`() {
        val existing = listOf(
            comment(1, "2026-01-02 10:00:00"),
            comment(2, "2026-01-01 10:00:00")
        )
        val fetched = listOf(
            comment(3, "2026-01-03 10:00:00"),
            comment(4, "2026-01-01 09:00:00")
        )

        val merged = mergeCommentPage(existing, fetched)

        assertEquals(listOf(3L, 1L, 2L, 4L), merged.map { it.id })
    }

    @Test
    fun `重复 id 只保留一条`() {
        val existing = listOf(comment(1, "2026-01-02 10:00:00"))
        val fetched = listOf(
            comment(1, "2026-01-02 10:00:00"),
            comment(2, "2026-01-01 10:00:00")
        )

        val merged = mergeCommentPage(existing, fetched)

        assertEquals(listOf(1L, 2L), merged.map { it.id })
    }

    @Test
    fun `空既有列表等价于首页排序`() {
        val fetched = listOf(
            comment(2, "2026-01-01 10:00:00"),
            comment(1, "2026-01-02 10:00:00")
        )

        val merged = mergeCommentPage(emptyList(), fetched)

        assertEquals(listOf(1L, 2L), merged.map { it.id })
    }
}
