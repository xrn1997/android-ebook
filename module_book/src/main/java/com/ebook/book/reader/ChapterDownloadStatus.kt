package com.ebook.book.reader

/** 下载中心二级视图中一章的下载状态（仅作展示，不参与勾选决策——勾选语义仍是不变的重下）。 */
internal enum class ChapterDownloadStatus { NOT_DOWNLOADED, CACHED, QUEUED, DOWNLOADING }

/**
 * 把三方数据合并成逐章下载状态：缓存存在性（文件在 ≠ 内容对，ADR-0034 口径）、
 * 下载队列任务、当前正在下载的章。
 *
 * 状态优先级：**下载中 > 待下载 > 已缓存 > 未下载**。
 * - 已缓存且又入队（forceRefresh 重下）要展示队列态，用户才看得出勾中它会重抓；
 * - 下载中必须同时在队列内，active 与队列失配的中间态按普通队列/未下载处理，不出现幽灵「下载中」。
 */
internal fun chapterDownloadStatus(
    count: Int,
    cachedIndices: Set<Int>,
    queuedIndices: Set<Int>,
    activeChapterIndex: Int?,
): List<ChapterDownloadStatus> = List(count) { index ->
    when {
        index == activeChapterIndex && index in queuedIndices -> ChapterDownloadStatus.DOWNLOADING
        index in queuedIndices -> ChapterDownloadStatus.QUEUED
        index in cachedIndices -> ChapterDownloadStatus.CACHED
        else -> ChapterDownloadStatus.NOT_DOWNLOADED
    }
}