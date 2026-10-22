package com.ebook.book.repository

import com.ebook.db.entity.BookShelfEntity
import com.ebook.db.entity.DownloadChapterEntity
import com.ebook.db.entity.PausedBookEntity
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [DownloadBatch] 的队列策略回归：候选域、暂停跳过、孤儿清理与队列计数。
 *
 * 这些规则原先散在 `DownloadRepository` 的三份同构遍历与 `DownloadService` 的两处
 * null-队头分支里；收成模块后可以在这里直接锁住。
 */
class DownloadBatchTest {

    private fun shelf(noteUrl: String, tag: String = "https://source") =
        BookShelfEntity(noteUrl = noteUrl, tag = tag)

    private fun task(noteUrl: String, index: Int) = DownloadChapterEntity(
        noteUrl = noteUrl,
        durChapterIndex = index,
        durChapterUrl = "$noteUrl/ch/$index",
        durChapterName = "第${index + 1}章",
        tag = "https://source",
        bookName = "书",
    )

    private class Fixture {
        val shelf = FakeShelfDao()
        val tasks = FakeDownloadChapterDao()
        val paused = FakePausedBookDao()
        val batch = DownloadBatch(tasks, shelf, paused)
    }

    @Test
    fun `nextTask 跳过暂停书且取该书最小章`() = runTest {
        val f = Fixture()
        f.shelf.seed(shelf("a"))
        f.shelf.seed(shelf("b"))
        f.shelf.seed(shelf("local", tag = BookShelfEntity.LOCAL_TAG))
        f.tasks.insert(task("a", 1))
        f.tasks.insert(task("a", 2))
        f.tasks.insert(task("b", 0))
        f.tasks.insert(task("local", 0))
        f.paused.insert(PausedBookEntity("a"))

        val next = f.batch.nextTask()

        assertEquals("b", next?.noteUrl)
        assertEquals(0, next?.durChapterIndex)
    }

    @Test
    fun `latestTask 与 nextTask 同候选域但取最大章`() = runTest {
        val f = Fixture()
        f.shelf.seed(shelf("a"))
        f.shelf.seed(shelf("b"))
        f.tasks.insert(task("a", 0))
        f.tasks.insert(task("a", 3))
        f.tasks.insert(task("b", 1))
        f.paused.insert(PausedBookEntity("b"))

        assertEquals("a", f.batch.latestTask()?.noteUrl)
        assertEquals(3, f.batch.latestTask()?.durChapterIndex)
    }

    @Test
    fun `deleteOrphans 删除书架外任务并清暂停标记`() = runTest {
        val f = Fixture()
        f.shelf.seed(shelf("a"))
        f.tasks.insert(task("a", 0))
        f.tasks.insert(task("ghost", 0))
        f.paused.insert(PausedBookEntity("ghost"))

        f.batch.deleteOrphans()

        assertEquals(1, f.tasks.storedValues().size)
        assertEquals("a", f.tasks.storedValues().single().noteUrl)
        assertFalse("书架外的暂停标记也是脏数据", "ghost" in f.paused.stored())
    }

    @Test
    fun `队列空时 nextTask 为 null`() = runTest {
        val f = Fixture()
        f.shelf.seed(shelf("a"))

        assertNull(f.batch.nextTask())
    }

    @Test
    fun `count 返回表内任务数`() = runTest {
        val f = Fixture()
        f.tasks.insert(task("a", 0))
        f.tasks.insert(task("b", 3))

        assertEquals(2, f.batch.count())
    }

    @Test
    fun `clearAllTasks 连暂停标记一起清空`() = runTest {
        val f = Fixture()
        f.tasks.insert(task("a", 0))
        f.paused.insert(PausedBookEntity("a"))

        f.batch.clearAllTasks()

        assertEquals(0, f.tasks.count())
        assertTrue(f.paused.stored().isEmpty())
    }

    @Test
    fun `deleteTasksForBook 连暂停标记一起清空`() = runTest {
        val f = Fixture()
        f.tasks.insert(task("a", 0))
        f.tasks.insert(task("a", 1))
        f.paused.insert(PausedBookEntity("a"))

        f.batch.deleteTasksForBook("a")

        assertEquals(0, f.tasks.count())
        assertTrue(f.paused.stored().isEmpty())
    }
}
