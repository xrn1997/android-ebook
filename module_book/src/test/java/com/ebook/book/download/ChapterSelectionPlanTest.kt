package com.ebook.book.download

import com.ebook.db.entity.ChapterListEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 下载选章计划（`ChapterSelectionPlan.kt`）的纯 JVM 用例。
 *
 * 预勾选、任务构造、跳过计数与书头三态原本散在 ViewModel 与 选章页里各算一遍；
 * 任何一处算错都不会编译失败或闪退，只会静默下发错的任务集或给出错的按钮态，
 * 所以集中到纯函数上用这些用例锁住。
 */
class ChapterSelectionPlanTest {

    private fun chapter(index: Int) = ChapterListEntity(
        noteUrl = "chapter-book",
        durChapterIndex = index,
        contentRef = "c$index",
        durChapterName = "第${index + 1}章",
        tag = "chapter-src",
    )

    private fun selection(
        chapters: List<ChapterListEntity>,
        cached: Set<Int> = emptySet(),
        queued: Set<Int> = emptySet(),
        paused: Boolean = false,
        focus: Int = -1,
    ) = BookChapterSelection(
        noteUrl = "book",
        tag = "src",
        bookName = "书",
        coverUrl = "cover",
        chapters = chapters,
        cachedIndices = cached,
        queuedIndices = queued,
        activeChapterIndex = null,
        initialSelected = emptySet(),
        focusChapter = focus,
        paused = paused,
    )

    @Test
    fun `预勾选取焦点章起五十章并过滤缓存与排队`() {
        val chapters = (0..60).map(::chapter)
        val selected = initialChapterSelection(
            chapters = chapters,
            cachedIndices = setOf(0, 2),
            queuedIndices = setOf(1),
            focusChapter = 0,
        )
        assertEquals((3..50).toSet(), selected)
    }

    @Test
    fun `无有效焦点章时预勾选为空`() {
        val selected = initialChapterSelection(
            chapters = (0..5).map(::chapter),
            cachedIndices = emptySet(),
            queuedIndices = emptySet(),
            focusChapter = -1,
        )
        assertTrue(selected.isEmpty())
    }

    @Test
    fun `焦点章窗口封顶最后一章`() {
        val selected = initialChapterSelection(
            chapters = (0..3).map(::chapter),
            cachedIndices = emptySet(),
            queuedIndices = emptySet(),
            focusChapter = 2,
        )
        assertEquals(setOf(2, 3), selected)
    }

    @Test
    fun `未缓存集合同样排除排队`() {
        assertEquals(
            setOf(2, 3),
            uncachedChapterSelection((0..3).map(::chapter), cachedIndices = setOf(0), queuedIndices = setOf(1)),
        )
    }

    @Test
    fun `确认计划按序构造任务并统计跳过`() {
        val chapters = (0..3).map(::chapter)
        val plan = planChapterDownload(
            selection(chapters, cached = setOf(0), queued = setOf(1)),
            selected = setOf(2, 0, 1),
        )

        assertEquals(listOf(0, 2), plan.tasks.map { it.durChapterIndex })
        assertEquals(2, plan.effectiveCount)
        assertEquals(1, plan.skippedCount)
        assertEquals(BookDownloadAction.Pause, plan.bookAction)
        assertEquals(setOf(2, 3), plan.uncachedIndices)
        assertTrue(plan.tasks.all { it.forceRefresh })
        assertEquals("book", plan.tasks[0].noteUrl)
        assertEquals("src", plan.tasks[0].tag)
        assertEquals("书", plan.tasks[0].bookName)
        assertEquals("cover", plan.tasks[0].coverUrl)
        assertEquals("第1章", plan.tasks[0].durChapterName)
        assertEquals("c0", plan.tasks[0].durChapterUrl)
    }

    @Test
    fun `越界勾选不虚增有效数`() {
        val plan = planChapterDownload(selection((0..1).map(::chapter)), selected = setOf(0, 9))
        assertEquals(1, plan.effectiveCount)
        assertEquals(listOf(0), plan.tasks.map { it.durChapterIndex })
    }

    @Test
    fun `三态动作矩阵`() {
        val chapters = (0..1).map(::chapter)
        assertEquals(
            BookDownloadAction.Pause,
            planChapterDownload(selection(chapters, queued = setOf(0)), setOf(1)).bookAction,
        )
        assertEquals(
            BookDownloadAction.Resume,
            planChapterDownload(selection(chapters, queued = setOf(0), paused = true), emptySet()).bookAction,
        )
        assertEquals(
            BookDownloadAction.Download,
            planChapterDownload(selection(chapters), setOf(0)).bookAction,
        )
    }

    @Test
    fun `有队列已暂停但勾中未排队章节时仍为下载`() {
        val plan = planChapterDownload(
            selection((0..1).map(::chapter), queued = setOf(0), paused = true),
            selected = setOf(1),
        )
        assertEquals(BookDownloadAction.Download, plan.bookAction)
        assertEquals(1, plan.effectiveCount)
    }

    @Test
    fun `空勾选已暂停有队列时继续且不下发`() {
        val plan = planChapterDownload(selection((0..1).map(::chapter), queued = setOf(0), paused = true), emptySet())
        assertTrue(plan.tasks.isEmpty())
        assertEquals(0, plan.effectiveCount)
        assertEquals(0, plan.skippedCount)
        assertEquals(BookDownloadAction.Resume, plan.bookAction)
    }

    @Test
    fun `空勾选无队列时下载不可用`() {
        val plan = planChapterDownload(selection((0..1).map(::chapter)), emptySet())
        assertEquals(BookDownloadAction.Download, plan.bookAction)
        assertFalse(plan.bookAction.isEnabledWith(plan.effectiveCount))
    }
}
