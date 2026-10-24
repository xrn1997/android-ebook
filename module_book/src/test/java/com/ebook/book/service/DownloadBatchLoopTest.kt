package com.ebook.book.service

import com.ebook.db.entity.DownloadChapterEntity
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [DownloadBatchLoop] 的测试：批次循环、跳过计数与终态三分。
 *
 * 这段逻辑原先在 `DownloadService.toDownload` / `downloading` / `isPause` 里，两份终态判定
 * 各写一遍且都在 Android 框架里，改一次只能装机试。测试把五个函数依赖（取篇、跑章、清孤儿、
 * 计数、延时）与章间隔常量全部替换成可控值，于是「队头取不到 ≠ 队列跑空」这条判据第一次能直接断言。
 */
class DownloadBatchLoopTest {

    private fun task(url: String) = DownloadChapterEntity(durChapterUrl = url, noteUrl = "book")

    private class Recorder {
        val steps = mutableListOf<String>()
        val started = mutableListOf<String>()
        var delays = 0
    }

    private fun loop(
        recorder: Recorder,
        next: suspend () -> DownloadChapterEntity?,
        run: suspend (DownloadChapterEntity, () -> Boolean) -> DownloadOutcome = { _, _ ->
            DownloadOutcome.Completed
        },
        deleteOrphans: suspend () -> Unit = { recorder.steps += "deleteOrphans" },
        count: suspend () -> Int = { recorder.steps += "count"; 0 },
        delay: suspend (Long) -> Unit = { recorder.delays++ },
    ) = DownloadBatchLoop(
        nextTask = next,
        runChapter = run,
        deleteOrphans = deleteOrphans,
        countTasks = count,
        chapterIntervalMs = 1L,
        delay = delay,
    )

    @Test
    fun `队列为空时终态是 Finished`() = runTest {
        val recorder = Recorder()
        val loop = loop(recorder, next = { null })

        val end = loop.run(isRunning = { true }) { recorder.started += it.durChapterUrl }

        assertEquals(BatchEnd.Finished, end)
        assertTrue("空队列不该跑任何章", recorder.started.isEmpty())
        assertEquals(listOf("deleteOrphans", "count"), recorder.steps)
    }

    @Test
    fun `只剩暂停书的任务时终态是 PausedRemaining`() = runTest {
        val recorder = Recorder()
        val loop = loop(recorder, next = { null }, count = { recorder.steps += "count"; 2 })

        val end = loop.run(isRunning = { true }) {}

        assertEquals(BatchEnd.PausedRemaining, end)
    }

    @Test
    fun `runner 返回 Paused 时停在暂停态且不碰孤儿清理`() = runTest {
        val recorder = Recorder()
        val loop = loop(
            recorder,
            next = { task("t1") },
            run = { _, _ -> DownloadOutcome.Paused },
        )

        val end = loop.run(isRunning = { true }) { recorder.started += it.durChapterUrl }

        assertEquals(BatchEnd.Interrupted, end)
        assertEquals(listOf("t1"), recorder.started)
        assertTrue("暂停打断不是终态判定，不该清孤儿", recorder.steps.isEmpty())
    }

    @Test
    fun `Skipped 记数后继续下一章`() = runTest {
        val recorder = Recorder()
        val tasks = ArrayDeque(listOf(task("t1"), task("t2")))
        val loop = loop(
            recorder,
            next = { tasks.removeFirstOrNull() },
            run = { t, _ ->
                if (t.durChapterUrl == "t1") DownloadOutcome.Skipped else DownloadOutcome.Completed
            },
        )

        val end = loop.run(isRunning = { true }) { recorder.started += it.durChapterUrl }

        assertEquals(BatchEnd.Finished, end)
        assertEquals(listOf("t1", "t2"), recorder.started)
        assertEquals("请求跳过一章并继续", 1, loop.skippedCount)
        assertEquals("每章之后等一次间隔（含最后一章）", 2, recorder.delays)
    }

    @Test
    fun `isRunning 为 false 时立即返回当前终态且不跑章节`() = runTest {
        val recorder = Recorder()
        val loop = loop(recorder, next = { task("t1") })

        val end = loop.run(isRunning = { false }) { recorder.started += it.durChapterUrl }

        assertEquals(BatchEnd.Interrupted, end)
        assertTrue(recorder.started.isEmpty())
    }

    @Test
    fun `孤儿清理发生在终态判定之前`() = runTest {
        val recorder = Recorder()
        val loop = loop(recorder, next = { null })

        loop.run(isRunning = { true }) {}

        assertEquals(
            "先清孤儿再数任务：只剩孤儿行时必须判 Finished",
            listOf("deleteOrphans", "count"),
            recorder.steps,
        )
    }

    @Test
    fun `terminalState 在仍有可跑任务时是 PausedRemaining`() = runTest {
        val recorder = Recorder()
        val loop = loop(recorder, next = { task("t1") }, count = { recorder.steps += "count"; 0 })

        val end = loop.terminalState()

        assertEquals(BatchEnd.PausedRemaining, end)
        assertTrue("暂停收尾不负责清孤儿", recorder.steps.isEmpty())
    }

    @Test
    fun `新一轮开始时跳过计数归零`() = runTest {
        val recorder = Recorder()
        var calls = 0
        val loop = loop(
            recorder,
            next = { if (calls++ == 0) task("t1") else null },
            run = { _, _ -> DownloadOutcome.Skipped },
        )

        loop.run(isRunning = { true }) {}
        assertEquals(1, loop.skippedCount)

        loop.run(isRunning = { true }) {}

        assertEquals("新一批的完成文案只数本批", 0, loop.skippedCount)
    }
}
