package com.ebook.book.reader

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [chapterDownloadStatus] 的纯逻辑用例：把缓存存在性、队列任务、当前下载章三方合并成逐章状态。
 *
 * 合并顺序算错只会让二级视图的「已缓存/待下载/下载中」标签错位，不编译失败也不闪退，
 * 故集中在纯 JVM 上锁住（本仓 Compose 页面不做装机级单测，见 AGENTS.md）。
 */
class ChapterDownloadStatusTest {

    @Test
    fun `无任何事实时全部是未下载`() {
        val status = chapterDownloadStatus(3, emptySet(), emptySet(), activeChapterIndex = null)

        assertEquals(
            listOf(
                ChapterDownloadStatus.NOT_DOWNLOADED,
                ChapterDownloadStatus.NOT_DOWNLOADED,
                ChapterDownloadStatus.NOT_DOWNLOADED,
            ),
            status
        )
    }

    @Test
    fun `缓存命中的章标记为已缓存`() {
        val status = chapterDownloadStatus(
            count = 3,
            cachedIndices = setOf(0, 1),
            queuedIndices = emptySet(),
            activeChapterIndex = null
        )

        assertEquals(ChapterDownloadStatus.CACHED, status[0])
        assertEquals(ChapterDownloadStatus.CACHED, status[1])
        assertEquals(ChapterDownloadStatus.NOT_DOWNLOADED, status[2])
    }

    @Test
    fun `队列与缓存并行时队列态优先`() {
        // 章 1 已被缓存但又在队列（forceRefresh 重下进行中）：展示「待下载」而非「已缓存」，
        // 用户因此知道勾中它会产生重下动作
        val status = chapterDownloadStatus(
            count = 3,
            cachedIndices = setOf(1),
            queuedIndices = setOf(1),
            activeChapterIndex = null
        )

        assertEquals(ChapterDownloadStatus.QUEUED, status[1])
    }

    @Test
    fun `当前下载章在队列内标记为下载中`() {
        val status = chapterDownloadStatus(
            count = 3,
            cachedIndices = emptySet(),
            queuedIndices = setOf(1, 2),
            activeChapterIndex = 1
        )

        assertEquals(ChapterDownloadStatus.DOWNLOADING, status[1])
        assertEquals(ChapterDownloadStatus.QUEUED, status[2])
    }

    @Test
    fun `active 不在队列时按普通队列态处理`() {
        // 防御：activeIndex 与 queued 失配（极端中间态）时不应出现"没在队却显示下载中"
        val status = chapterDownloadStatus(
            count = 3,
            cachedIndices = emptySet(),
            queuedIndices = setOf(2),
            activeChapterIndex = 1
        )

        assertEquals(ChapterDownloadStatus.NOT_DOWNLOADED, status[1])
    }

    @Test
    fun `空目录与越界输入不崩溃`() {
        assertEquals(emptyList<ChapterDownloadStatus>(), chapterDownloadStatus(0, emptySet(), emptySet(), null))

        val status = chapterDownloadStatus(
            count = 2,
            cachedIndices = setOf(9),
            queuedIndices = setOf(9),
            activeChapterIndex = 9
        )
        assertEquals(
            listOf(ChapterDownloadStatus.NOT_DOWNLOADED, ChapterDownloadStatus.NOT_DOWNLOADED),
            status
        )
    }
}
