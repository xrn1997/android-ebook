package com.ebook.book.service

import com.ebook.common.analyze.local.ChapterContent
import com.ebook.db.entity.DownloadChapterEntity
import com.ebook.source.analyze.BookSourceNotFoundException
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [DownloadRunner] 的单章重试/出队语义（原 DownloadService.downloading 的核心）。
 *
 * 服务只剩前台生命周期与通知；把「取任务 → 重试 → 出队/跳过/暂停」抽出来后，
 * 失败耗尽必须出队、暂停中断不出队这两条铁律可以在纯 JVM 下锁住。
 */
class DownloadRunnerTest {

    private fun task(forceRefresh: Boolean = false) = DownloadChapterEntity(
        noteUrl = "book",
        durChapterIndex = 2,
        durChapterUrl = "https://source/ch/2",
        durChapterName = "第三章",
        tag = "https://source",
        bookName = "书",
        forceRefresh = forceRefresh,
    )

    private class Harness(
        var hasChapter: Boolean = false,
        var content: () -> ChapterContent = { ChapterContent("章", listOf("正文")) },
        var keepRunning: () -> Boolean = { true },
        var failDeletes: Boolean = false,
    ) {
        var reads = 0
        var deletes = 0
        var chapterDeletes = 0
        var invalidations = 0
        var delays = 0

        val runner = DownloadRunner(
            hasChapter = { _, _ -> hasChapter },
            deleteChapter = { _, _ ->
                chapterDeletes++
                hasChapter = false
            },
            readChapter = { _, _ ->
                reads++
                content()
            },
            deleteTask = {
                deletes++
                if (failDeletes) throw IOException("db locked")
            },
            invalidateBook = { invalidations++ },
            retryDelay = { delays++ },
            maxAttempts = 3,
        )

        suspend fun run(task: DownloadChapterEntity): DownloadOutcome = runner.run(task, keepRunning)
    }

    @Test
    fun `已有章文件且非强刷时直接完成并出队`() = runTest {
        val h = Harness(hasChapter = true)

        val outcome = h.run(task())

        assertEquals(DownloadOutcome.Completed, outcome)
        assertEquals("命中缓存不应再抓正文", 0, h.reads)
        assertEquals(1, h.deletes)
    }

    @Test
    fun `空正文按失败重试到上限后出队并返回 Skipped`() = runTest {
        val h = Harness(content = { ChapterContent("章", emptyList()) })

        val outcome = h.run(task())

        assertEquals(DownloadOutcome.Skipped, outcome)
        assertEquals("重试上限 3 次", 3, h.reads)
        assertEquals("三次尝试之间等两次", 2, h.delays)
        assertEquals("失败耗尽必须出队", 1, h.deletes)
    }

    @Test
    fun `暂停中断重试时不出队并返回 Paused`() = runTest {
        var keepRunning = true
        val h = Harness(
            content = {
                keepRunning = false
                throw IOException("网络中断")
            },
            keepRunning = { keepRunning },
        )

        val outcome = h.run(task())

        assertEquals(DownloadOutcome.Paused, outcome)
        assertEquals(1, h.reads)
        assertEquals("暂停不是失败耗尽，不得出队", 0, h.deletes)
    }

    @Test
    fun `强刷成功后失效内存缓存再出队`() = runTest {
        val h = Harness(hasChapter = true)

        val outcome = h.run(task(forceRefresh = true))

        assertEquals(DownloadOutcome.Completed, outcome)
        assertEquals("强刷先删旧章文件", 1, h.chapterDeletes)
        assertEquals("重抓成功后失效内存缓存", 1, h.invalidations)
        assertEquals(1, h.deletes)
    }

    @Test
    fun `取源失败与其他失败走同一条重试出队通道`() = runTest {
        val h = Harness(content = { throw BookSourceNotFoundException("https://source") })

        val outcome = h.run(task())

        assertEquals(DownloadOutcome.Skipped, outcome)
        assertEquals(3, h.reads)
        assertEquals(1, h.deletes)
    }

    @Test
    fun `成功抓取后出队且不失效缓存`() = runTest {
        val h = Harness()

        val outcome = h.run(task())

        assertEquals(DownloadOutcome.Completed, outcome)
        assertEquals(1, h.reads)
        assertEquals(1, h.deletes)
        assertEquals(0, h.invalidations)
        assertTrue("普通抓取没有强刷语义", h.chapterDeletes == 0)
    }

    @Test
    fun `成功抓取后出队失败停在暂停态且不重抓正文`() = runTest {
        val h = Harness(failDeletes = true)

        val outcome = h.run(task())

        assertEquals("出队失败不是「再抓一次」的信号", DownloadOutcome.Paused, outcome)
        assertEquals("正文已成功落盘，不得因出队失败重抓", 1, h.reads)
        assertEquals(1, h.deletes)
        assertEquals(0, h.delays)
    }
}
