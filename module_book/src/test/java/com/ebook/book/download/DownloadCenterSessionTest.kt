package com.ebook.book.download

import com.ebook.db.entity.ChapterListEntity
import com.ebook.db.entity.DownloadChapterEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [DownloadCenterSession] 的纯 JVM 用例（下载中心的第一份单测）。
 *
 * 锁住三件事：
 * - **焦点章只有一处派生**——一级卡片与二级选章都读 [DownloadCenterSession.activeChapter]，
 *   改一处即改两处（旧写法两处各算 `isDownloading && url==activeChapterUrl`，靠 KDoc
 *   自陈「与一级卡片同口径」维持，是本次收口的病根）；
 * - **服务结局→该刷哪几面**的路由（[DownloadCenterSession.onDownloadState] 的返回值），
 *   以及暂停/完成时 `isDownloading` 收起、焦点章随之为 null；
 * - 确认下载的**乐观回显**（[DownloadCenterSession.optimisticEnqueue]）：暂停即时收起、
 *   新勾章即时并入排队集。
 *
 * 暂停「这本书算不算暂停」的真值源仍在 `paused_book` 表（经 `DownloadBatch`），会话只收
 * 派生与乐观写入，不回查库。
 */
class DownloadCenterSessionTest {

    private fun task(noteUrl: String, index: Int) = DownloadChapterEntity(
        noteUrl = noteUrl,
        durChapterIndex = index,
        durChapterUrl = "$noteUrl/ch/$index",
        durChapterName = "第${index + 1}章",
        tag = "https://source",
        bookName = "书",
    )

    private fun selection(
        queued: Set<Int> = emptySet(),
        paused: Boolean = false,
    ) = BookChapterSelection(
        noteUrl = "book",
        tag = "src",
        bookName = "书",
        coverUrl = "cover",
        chapters = (0..3).map {
            ChapterListEntity(
                noteUrl = "book",
                durChapterIndex = it,
                contentRef = "book/ch/$it",
                durChapterName = "第${it + 1}章",
                tag = "src",
            )
        },
        cachedIndices = emptySet(),
        queuedIndices = queued,
        activeChapterIndex = null,
        initialSelected = emptySet(),
        focusChapter = -1,
        paused = paused,
    )

    @Test
    fun `同一任务表下一级与二级读到同一个焦点章`() {
        val session = DownloadCenterSession()
        val tasks = listOf(task("book", 2), task("book", 3))
        session.onDownloadState(DownloadState.Progress(task("book", 3)))

        val focus = session.activeChapter(tasks)

        // 一级卡片拿这一行实体显示「正在下载 第 M 章」，二级拿它的 index——两处同一处派生
        assertEquals(3, focus?.durChapterIndex)
        assertEquals("book/ch/3", focus?.durChapterUrl)
        // 焦点章整行取自任务表里活跃 URL 那一行（不是首行、也不是临时拼出的副本）
        assertEquals(task("book", 3), focus)
    }

    @Test
    fun `书在架但零任务不给焦点`() {
        val session = DownloadCenterSession()
        // 正在下载另一本书：活跃章的 URL 不在这本书的任务表里
        session.onDownloadState(DownloadState.Progress(task("other", 5)))

        assertNull("零任务的书拿不到焦点章", session.activeChapter(emptyList()))
        assertNull("焦点章 URL 不属于这本书时不给焦点", session.activeChapter(listOf(task("book", 0))))
    }

    @Test
    fun `暂停与完成都刷新两面并收起焦点章`() {
        val session = DownloadCenterSession()
        val tasks = listOf(task("book", 1))
        val both = setOf(DownloadRefreshTarget.Groups, DownloadRefreshTarget.Selection)

        assertEquals("进度推进：一级与二级都刷", both, session.onDownloadState(DownloadState.Progress(task("book", 1))))
        assertEquals(tasks.single(), session.activeChapter(tasks))

        // 暂停：队列不出队、章仍在表里，但 isDownloading 收起 → 焦点为 null（否则误报「正在下载」）
        assertEquals("暂停：两面都刷以收起进行中态", both, session.onDownloadState(DownloadState.Paused))
        assertNull(session.activeChapter(tasks))

        // 收尾：同样两面都刷；未再来 Progress 之前焦点章保持收起
        assertEquals("完成：两面都刷以收起进行中态", both, session.onDownloadState(DownloadState.Finished))
        assertNull(session.activeChapter(tasks))
    }

    @Test
    fun `乐观写入即时收起暂停并并入排队位置`() {
        val session = DownloadCenterSession()
        val before = selection(queued = setOf(0), paused = true)

        val after = session.optimisticEnqueue(before, queuedIndices = setOf(1, 2))

        assertFalse("确认下载即解除暂停的乐观回显（不等下一次 Progress）", after.paused)
        assertEquals(setOf(0, 1, 2), after.queuedIndices)
    }
}
