package com.ebook.book.reader

import com.ebook.db.event.DBCode

/**
 * 分页所需的排版能力：行数测算与整章行偏移。
 *
 * [ReaderTypesetter] 是生产适配器；测试用固定行偏移的假件跨同一个接缝。
 * 字号放在接口上，是为了让「测量样式」与「排版缓存键」读同一个值——
 * 两处各读一次 `ReadBookControl.textSize` 时，改字号的瞬间可能取到不同代的值。
 */
internal interface ReaderLayoutEngine {
    val fontSizeSp: Float

    fun measureBlock(widthPx: Int, viewportHeightPx: Int): ReaderBlockMetrics?

    fun lineStartOffsets(text: String, widthPx: Int): List<Int>
}

/**
 * 一章排版结果的切片视图：页数、哨兵页码解析与原文子串切片。
 *
 * 只持有数据，不碰 Activity / Compose；末页按剩余行数切，页与页首尾相接。
 */
internal class ReaderPageWindow(
    private val text: String,
    private val lineStarts: List<Int>,
    val pageLineCount: Int,
) {
    init {
        require(pageLineCount > 0) { "pageLineCount 必须为正：$pageLineCount" }
    }

    val pageCount: Int =
        if (lineStarts.isEmpty()) 0 else (lineStarts.size + pageLineCount - 1) / pageLineCount

    fun resolvePageIndex(requested: Int): Int = when {
        pageCount == 0 -> 0
        requested == DBCode.BookContentView.DUR_PAGE_INDEX_BEGIN -> 0
        requested == DBCode.BookContentView.DUR_PAGE_INDEX_END -> pageCount - 1
        else -> requested.coerceIn(0, pageCount - 1)
    }

    fun slice(pageIndex: Int): String {
        if (pageCount == 0) return ""
        val resolved = pageIndex.coerceIn(0, pageCount - 1)
        val start = resolved * pageLineCount
        val end = minOf(start + pageLineCount, lineStarts.size)
        val from = lineStarts[start]
        val to = if (end < lineStarts.size) lineStarts[end] else text.length
        return text.substring(from, to).trimEnd('\r', '\n')
    }
}

/**
 * 阅读器分页的唯一状态机：视口测量、整章排版缓存、页码换算与切片。
 *
 * 之前这三件事分散在 `ReadBookActivity` 的五个调用点与三个字段上；现在一次
 * [measure] 落定行数与块高，[paginate] 只读这份结果。Activity 只负责
 * 把 Compose 测到的视口尺寸交进来，翻页与滚屏控制器只消费 [ReaderPageWindow]。
 */
internal class ReaderPagination(
    private val layoutCache: ChapterLayoutCache = ChapterLayoutCache(),
) {
    // 测量在主线程落定，paginate 按 loadPage 的既有约定在 Dispatchers.Default 上读；
    // @Volatile 保证快照对工作线程可见（高代价的偏移计算仍留在后台）。
    // 排版引擎与测量结果必须成对发布：分两次写会让后台分页读到「新字号 + 旧行数」。
    private data class Snapshot(
        val engine: ReaderLayoutEngine,
        val metrics: ReaderBlockMetrics,
        val widthPx: Int,
    )

    @Volatile
    var widthPx: Int = 0
        private set

    var heightPx: Int = 0
        private set

    @Volatile
    private var snapshot: Snapshot? = null

    val pageLineCount: Int get() = snapshot?.metrics?.lineCount ?: 0
    val blockHeightPx: Int get() = snapshot?.metrics?.heightPx ?: 0

    fun updateViewport(widthPx: Int, heightPx: Int) {
        if (widthPx > 0) this.widthPx = widthPx
        if (heightPx > 0) this.heightPx = heightPx
    }

    /**
     * 用渲染引擎实测当前视口放得下几行、这几行多高。
     *
     * 视口或测量结果非法时返回 null 且**不覆盖**上一次结果：排版未就绪的中间帧
     * 不应把已可用的分页打回 0。
     */
    fun measure(engine: ReaderLayoutEngine): ReaderBlockMetrics? {
        val width = widthPx
        val height = heightPx
        if (width <= 0 || height <= 0) return null
        val measured = engine.measureBlock(width, height) ?: return null
        if (measured.lineCount <= 0) return null
        snapshot = Snapshot(engine, measured, width)
        return measured
    }

    /**
     * 对一章正文排版并返回可切片的窗口。
     *
     * 排版偏移按 [ChapterLayoutKey] 缓存：同章翻页只重排一次；字号取自
     * [ReaderLayoutEngine.fontSizeSp]，与测量样式同源。
     */
    fun paginate(text: String, contentRef: String): ReaderPageWindow? {
        // 引擎、行数与宽度来自同一份快照：并发换字号/改视口时读过多个 volatile 会拼出
        // 「新字号排版 + 旧每屏行数」或「键里旧宽度、排版用新宽度」的错页。
        val active = snapshot ?: return null
        if (active.widthPx <= 0 || text.isEmpty()) return null
        val key = ChapterLayoutKey(
            contentRef = contentRef,
            contentLength = text.length,
            fontSizeSp = active.engine.fontSizeSp,
            widthPx = active.widthPx,
        )
        val lineStarts = layoutCache.getOrCompute(key) {
            active.engine.lineStartOffsets(text, active.widthPx)
        }
        if (lineStarts.isEmpty()) return null
        return ReaderPageWindow(text, lineStarts, active.metrics.lineCount)
    }

    /**
     * 翻页方式切换时的落点换算：按行号而不是屏号跨模式对齐。
     *
     * 旧模式读到第 oldPage 屏 = 读到了第 `oldPage × oldLineCount` 行，
     * 新模式下含该行的屏是 `lineOffset / newLineCount`。
     */
    fun convertPageIndex(oldPageIndex: Int, oldLineCount: Int, newLineCount: Int): Int {
        if (oldLineCount <= 0 || newLineCount <= 0) return 0
        val lineOffset = oldPageIndex.coerceAtLeast(0) * oldLineCount
        return lineOffset / newLineCount
    }
}
