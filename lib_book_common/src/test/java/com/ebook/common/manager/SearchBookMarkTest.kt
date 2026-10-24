package com.ebook.common.manager

import com.ebook.common.repository.BookShelfEvent
import com.ebook.db.entity.BookShelfEntity
import com.ebook.db.entity.SearchBookEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [applying] 的测试：书架事件 → 结果列表的已加书架标记。
 *
 * 这是原先 `SearchViewModel` / `ChoiceBookViewModel` 各写一份的「书架事件同步」逻辑，
 * 现在只在这里有一份。测试锁三件事：命中改标记、无关事件返回同一实例（调用方据此跳过更新）、
 * 列表里没有这本书时不动。
 */
class SearchBookMarkTest {

    private fun searchBook(noteUrl: String, add: Boolean = false) =
        SearchBookEntity(noteUrl = noteUrl, name = "书", add = add)

    private fun shelf(noteUrl: String) = BookShelfEntity().apply { this.noteUrl = noteUrl }

    @Test
    fun `Added 事件把命中 noteUrl 的条目置为已加书架`() {
        val list = listOf(searchBook("u1"), searchBook("u2"))

        val result = list.applying(BookShelfEvent.Added(shelf("u2")))

        assertFalse(result[0].add)
        assertTrue("命中的条目应被标记", result[1].add)
    }

    @Test
    fun `Removed 事件把它置回未加书架`() {
        val list = listOf(searchBook("u1", add = true), searchBook("u2", add = true))

        val result = list.applying(BookShelfEvent.Removed(shelf("u1")))

        assertFalse(result[0].add)
        assertTrue("未命中的条目不受影响", result[1].add)
    }

    @Test
    fun `与已加书架无关的事件返回同一实例`() {
        val list = listOf(searchBook("u1"))

        val result = list.applying(BookShelfEvent.ProgressUpdated(shelf("u1")))

        assertSame("调用方靠实例相同跳过 updateList", list, result)
    }

    @Test
    fun `列表里没有这本书时返回同一实例`() {
        val list = listOf(searchBook("u1"))

        val result = list.applying(BookShelfEvent.Added(shelf("other")))

        assertSame(list, result)
    }

    @Test
    fun `标记已经正确时返回同一实例`() {
        val list = listOf(searchBook("u1", add = true))

        val result = list.applying(BookShelfEvent.Added(shelf("u1")))

        assertSame(list, result)
    }

    @Test
    fun `同一事件应用两次不改变结果`() {
        val once = listOf(searchBook("u1")).applying(BookShelfEvent.Added(shelf("u1")))

        val twice = once.applying(BookShelfEvent.Added(shelf("u1")))

        assertSame(once, twice)
        assertEquals(1, twice.count { it.add })
    }
}
