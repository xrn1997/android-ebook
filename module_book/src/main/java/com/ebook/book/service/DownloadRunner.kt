package com.ebook.book.service

import com.ebook.common.analyze.local.BookFormat
import com.ebook.common.analyze.local.BookLocation
import com.ebook.common.analyze.local.ChapterContent
import com.ebook.common.analyze.local.ChapterEntry
import com.ebook.db.entity.DownloadChapterEntity
import com.xrn1997.common.util.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/** 单章一次执行的结局；暂停与失败耗尽必须分开，前者不出队、后者出队。 */
internal sealed class DownloadOutcome {
    data object Completed : DownloadOutcome()
    data object Skipped : DownloadOutcome()
    data object Paused : DownloadOutcome()
}

/**
 * 单章下载执行器：缓存命中、强刷、抓取、空正文校验、重试与出队。
 *
 * 从 `DownloadService.downloading` 抽出的理由：Service 只剩前台生命周期与通知，
 * 这段「重试耗尽必须出队、暂停中断不出队」的铁律才能用假仓库与假时钟在纯 JVM 下锁住。
 *
 * 依赖以函数传入而不是直接取 Service 的字段：测试可替换假实现，生产由 Service 绑定
 * `BookStore` / `JsoupSourceReader` / `ChapterContentCache` / `DownloadRepository`。
 */
internal class DownloadRunner(
    private val hasChapter: (BookLocation, Int) -> Boolean,
    private val deleteChapter: (BookLocation, Int) -> Unit,
    private val readChapter: suspend (ChapterEntry, BookLocation) -> ChapterContent,
    private val deleteTask: suspend (DownloadChapterEntity) -> Unit,
    private val invalidateBook: suspend (String) -> Unit,
    private val retryDelay: suspend (Long) -> Unit = { delay(it) },
    private val maxAttempts: Int = RETRY_TIMES,
) {

    suspend fun run(
        task: DownloadChapterEntity,
        shouldContinue: () -> Boolean,
    ): DownloadOutcome {
        if (!shouldContinue()) return DownloadOutcome.Paused
        // data.tag = 这本书的书源归属 URL：正文按书绑源抓取，不按「当前默认源」
        val location = BookLocation(task.noteUrl, BookFormat.NETWORK, task.tag)

        // 缓存检查是本地 IO：异常不能逃出服务协程（会让整批停摆），按「未缓存」继续抓取
        val cached = try {
            if (task.forceRefresh) {
                // 强刷只删这一章的章文件；不能图省事删整本书目录
                if (hasChapter(location, task.durChapterIndex)) {
                    deleteChapter(location, task.durChapterIndex)
                }
                false
            } else {
                hasChapter(location, task.durChapterIndex)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Logger.e(TAG, "缓存检查失败，按未缓存继续: ${task.durChapterUrl}", e)
            false
        }
        if (cached) return dequeue(task, DownloadOutcome.Completed)

        var attempt = 0
        var fetched = false
        while (attempt < maxAttempts && shouldContinue()) {
            attempt++
            try {
                val content = readChapter(
                    ChapterEntry(
                        index = task.durChapterIndex,
                        title = task.durChapterName,
                        contentRef = task.durChapterUrl,
                    ),
                    location,
                )
                // 空正文判失败：站点常以 HTTP 200 空壳页回应选择器失配；若当成功，
                // 任务被删而缓存里空着，离线打开就是空白页且 hasChapter 也救不回来。
                if (content.paragraphs.none { it.isNotBlank() }) {
                    throw IllegalStateException("章节内容解析失败: ${task.durChapterUrl}")
                }
                // 先失效再出队：章文件已是新正文，缓存若继续供给旧正文，正是强刷要消除的现象
                if (task.forceRefresh) invalidateBook(task.noteUrl)
                fetched = true
                break
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Logger.e(
                    TAG,
                    "章节下载失败（第 $attempt/$maxAttempts 次）: ${task.durChapterUrl}",
                    e,
                )
                if (attempt < maxAttempts) retryDelay(RETRY_DELAY_MS)
            }
        }

        if (fetched) return dequeue(task, DownloadOutcome.Completed)
        // 暂停打断不是失败耗尽：任务保留，点继续后从头重试
        if (!shouldContinue()) return DownloadOutcome.Paused
        Logger.w(TAG, "重试 $maxAttempts 次仍失败，跳过本章: ${task.durChapterUrl}")
        return dequeue(task, DownloadOutcome.Skipped)
    }

    /**
     * 出队失败时任务还在队头，继续排下一章会无限重试同一任务；停在暂停态等用户续跑。
     * 出队与抓取分开收口：成功抓取后的出队失败不重抓正文（那只会白耗三次网络）。
     */
    private suspend fun dequeue(task: DownloadChapterEntity, success: DownloadOutcome): DownloadOutcome =
        try {
            deleteTask(task)
            success
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Logger.e(TAG, "任务出队失败: ${task.durChapterUrl}", e)
            DownloadOutcome.Paused
        }

    companion object {
        private const val TAG = "DownloadRunner"

        /** 单章重试次数；与旧 Service 常量同值 */
        const val RETRY_TIMES: Int = 3

        /** 单章重试前的等待：避开瞬时限流/网络抖动 */
        private const val RETRY_DELAY_MS = 1_000L
    }
}
