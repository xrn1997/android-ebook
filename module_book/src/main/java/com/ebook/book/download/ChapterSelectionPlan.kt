package com.ebook.book.download

/*
 * 下载选章的计划计算（纯逻辑，零 Compose / Android 运行时依赖）。
 *
 * 二级选章页上「预勾哪些章、确认后下发哪些任务、跳过几条、书头那枚按钮该是哪一态」
 * 原本散在 `DownloadManageViewModel` 与选章页里各算一遍：页面用 `selected - queued`
 * 反算有效数、ViewModel 再按同一条规则构造任务。两处只要有一处分叉，用户看到的
 * 「将下载 N 章」就会与实际入库的任务数不一致——不编译失败、不闪退，只是静默错。
 * 这里把这些口径收成一个计划对象，页面与 ViewModel 读同一份结果。
 *
 * `ChapterDownloadPlan.uncachedIndices` 一并放在计划里而不是让页面另算：它和
 * `ChapterDownloadPlan.tasks` 用的是同一组缓存/排队事实，分开算迟早会在「排队集合
 * 刚乐观更新」的那一帧上对不上。
 */

import com.ebook.db.entity.ChapterListEntity
import com.ebook.db.entity.DownloadChapterEntity

/**
 * 一次确认操作的完整结果，页面与下发路径共用。
 */
internal data class ChapterDownloadPlan(
    /** 按章序号升序的待入库任务（已剔除排队中与越界索引）。 */
    val tasks: List<DownloadChapterEntity>,
    /** 勾中但已在队列中的章数（确认时被跳过）。 */
    val skippedCount: Int,
    /** 书头主操作的三态（见 [bookDownloadActionOf]）。 */
    val bookAction: BookDownloadAction,
    /** 未缓存且未排队的索引集（「仅未缓存」工具的选择输入）。 */
    val uncachedIndices: Set<Int>,
) {
    /** 实际会下发的章数，恒等于 [tasks] 的长度。 */
    val effectiveCount: Int get() = tasks.size
}

/**
 * 「未缓存且未排队」的剔除口径，两处选择共用：已排队的章即使未缓存也不选入，
 * 预勾上只会在确认时被跳过，徒增一行「将跳过」的说明。
 */
private fun eligibleIndices(
    indices: Iterable<Int>,
    cachedIndices: Set<Int>,
    queuedIndices: Set<Int>,
): Set<Int> = indices.filter { index ->
    index !in cachedIndices && index !in queuedIndices
}.toSet()

/**
 * 阅读器进入二级页时的预勾选：「焦点章起 50 章」窗口内未缓存且未排队者。
 *
 * 窗口口径复用 [defaultChapterWindow]，与范围对话框的默认区间同源；无有效焦点章
 * （一级入口传 -1）返回空集，由页面呈现「未勾选」的初始态。
 */
internal fun initialChapterSelection(
    chapters: List<ChapterListEntity>,
    cachedIndices: Set<Int>,
    queuedIndices: Set<Int>,
    focusChapter: Int,
): Set<Int> {
    val window = defaultChapterWindow(chapters.size, focusChapter) ?: return emptySet()
    return eligibleIndices(window, cachedIndices, queuedIndices)
}

/**
 * 「仅未缓存」工具的选择集：全部索引剔除已缓存与已排队（口径见 [eligibleIndices]）。
 */
internal fun uncachedChapterSelection(
    chapters: List<ChapterListEntity>,
    cachedIndices: Set<Int>,
    queuedIndices: Set<Int>,
): Set<Int> = eligibleIndices(chapters.indices, cachedIndices, queuedIndices)

/**
 * 把一次确认的勾选折算成任务、跳过数、未缓存集与书头动作。
 *
 * 口径：
 * - 已在队列中的章一律跳过（重复入库只会撞 `download_chapter` 的唯一索引）；
 * - 越界索引被 `getOrNull` 丢弃，[ChapterDownloadPlan.effectiveCount] 取实际任务数而非勾选数，
 *   否则「将下载 2 章」会对应 1 条任务；
 * - 任务按章序号升序、`forceRefresh = true`：下载中心确认即强刷（勾中已缓存
 *   章节等价于重下，强刷删章与失效在 `DownloadRunner` 里执行）；
 * - 书的身份（noteUrl/tag/bookName/coverUrl）取 [BookChapterSelection]，
 *   不取章节行里的冗余字段——同一本书的任务归属必须整批一致。
 *
 * 索引空间：`selected`、`focusChapter` 与预勾选窗口都是 `chapters` 中的位置；
 * `cachedIndices`/`queuedIndices` 是章节索引，当前列表按 `durChapterIndex` 排序，
 * 故二者恰好重合（不改行为，排序 follow-up 单独处理）。
 */
internal fun planChapterDownload(
    selection: BookChapterSelection,
    selected: Set<Int>,
): ChapterDownloadPlan {
    val tasks = (selected - selection.queuedIndices)
        .sorted()
        .mapNotNull { index ->
            selection.chapters.getOrNull(index)?.let { chapter ->
                DownloadChapterEntity(
                    noteUrl = selection.noteUrl,
                    durChapterIndex = chapter.durChapterIndex,
                    durChapterName = chapter.durChapterName,
                    durChapterUrl = chapter.contentRef,
                    tag = selection.tag,
                    bookName = selection.bookName,
                    coverUrl = selection.coverUrl,
                    forceRefresh = true,
                )
            }
        }
    return ChapterDownloadPlan(
        tasks = tasks,
        skippedCount = (selected intersect selection.queuedIndices).size,
        bookAction = bookDownloadActionOf(
            hasQueue = selection.queuedIndices.isNotEmpty(),
            paused = selection.paused,
            effectiveCount = tasks.size,
        ),
        uncachedIndices = uncachedChapterSelection(
            chapters = selection.chapters,
            cachedIndices = selection.cachedIndices,
            queuedIndices = selection.queuedIndices,
        ),
    )
}
