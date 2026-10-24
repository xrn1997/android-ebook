package com.ebook.book.reader

import com.ebook.source.analyze.BookSourceNotFoundException
import com.xrn1997.common.util.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 「装一页」的唯一组合处：取正文 → 后台分页 → 哨兵页码解析 → 切片 → 失败分档。
 *
 * 两个端点原先各有主人（[ReaderSession] 管正文、[ReaderPagination] 管排版与切片），
 * 但把它们串起来的这段长在 `ReadBookActivity.loadPage` 里，只有起一个 Activity 才能触发，
 * 于是「哪种异常该弹用户提示」这条判据一直零测试。抽到这里后，Activity 只递进
 * 「按章索引取正文」这一支 lambda 与分页状态机，测试在纯 JVM 下用假测量引擎即可。
 *
 * 失败分档（见 [load]）：
 * - [BookSourceNotFoundException] 是**用户可处置**的业务失败，经 [reportFailure] 开口；
 * - 其余异常（排版未就绪、解析失败）保持静默降级，页面留错误态与重试按钮；
 * - [CancellationException] 原样上抛——取消不是失败，吞掉它会让协程无法收敛。
 */
internal class ReaderPageLoader(
    private val loadChapter: suspend (chapterIndex: Int) -> ReaderChapterText?,
    private val pagination: ReaderPagination,
    private val reportFailure: suspend (Throwable) -> Unit,
) {

    /**
     * 装载一章的一页；返回 null 表示这一页没装成（调用方置错误态或保持占位）。
     *
     * 分页在 [Dispatchers.Default] 上跑整章 O(章长) 的排版；一旦测量尚未落定
     * （[ReaderPagination.paginate] 返回 null），本方法按「还没准备好」返回 null，
     * 既不记日志也不上报——那一帧还没到能判断失败的时候。
     */
    suspend fun load(chapterIndex: Int, pageIndex: Int): ReaderPageUi.Loaded? {
        return try {
            val chapter = loadChapter(chapterIndex) ?: return null
            val window = withContext(Dispatchers.Default) {
                pagination.paginate(chapter.text, chapter.contentRef)
            } ?: return null
            val resolved = window.resolvePageIndex(pageIndex)
            ReaderPageUi.Loaded(
                title = chapter.title,
                chapterIndex = chapterIndex,
                durPageIndex = resolved,
                pageAll = window.pageCount,
                text = window.slice(resolved),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: BookSourceNotFoundException) {
            Logger.e(TAG, "loadPage 书源已失效: chapter=$chapterIndex", e)
            reportFailure(e)
            null
        } catch (e: Exception) {
            Logger.e(TAG, "loadPage error: chapter=$chapterIndex", e)
            null
        }
    }

    private companion object {
        private val TAG: String = ReaderPageLoader::class.java.simpleName
    }
}
