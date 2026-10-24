package com.ebook.book.service

import com.ebook.db.entity.DownloadChapterEntity
import kotlinx.coroutines.delay as kotlinDelay

/**
 * 一批下载的终态。
 *
 * [Finished] 与 [PausedRemaining] 都必须由「表内还剩不剩任务」判出来，而不是由「队头取不到」
 * 直接推出来（ADR-0036）：取篇会跳过暂停书，队头为空可能只是剩下那批都暂停着。
 * 调用方（[DownloadService]）据它决定停服与发哪种通知；判定本身不归 Service。
 */
internal sealed interface BatchEnd {
    /** 队列真的跑空：表里一条任务都不剩 */
    data object Finished : BatchEnd

    /** 队列里仍有任务（暂停书的待续任务），但当前取不到可跑的：对用户是「已暂停」 */
    data object PausedRemaining : BatchEnd

    /**
     * 被外部打断（用户暂停 / runner 返回 Paused）：任务保留，服务留在暂停态。
     *
     * 与 [PausedRemaining] 分开是因为收尾动作不同——[PausedRemaining] 是「这一批没得跑了」，
     * 调用方会静默停服；[Interrupted] 是「用户还打算继续」，服务常驻并改写通知。
     * 两者都判「已暂停」，但停服与否则不该由一句布尔文案决定。
     */
    data object Interrupted : BatchEnd
}

/**
 * 下载批次循环的唯一主人：取篇 → 跑一章 → 跳过计数 → 间隔 → 下一章，以及终态三分。
 *
 * 原先这段在 `DownloadService.toDownload` / `downloading` / `isPause` 里，`DownloadService`
 * 是本仓改动最频繁的文件之一，而「队头为空 → countTasks>0 → Paused else Finished」这条判定
 * 写了两份、两份都在 Android 框架里，JVM 侧零测试。收进这里后五个函数依赖（取篇、跑章、
 * 清孤儿、计数、延时）外加一个章间隔常量全部以构造参数注入；Service 只留前台生命周期、
 * 通知与 Intent 分派。
 *
 * 线程：本类不做线程切换，调用方在自己的 IO/主线程上下文里跑。
 * 暂停语义：runner 返回 [DownloadOutcome.Paused] 时**不出队**并立即停下——那只是被打断，
 * 不是失败耗尽；失败耗尽的出队由 runner 自己做，这里只记跳过数。
 */
internal class DownloadBatchLoop(
    private val nextTask: suspend () -> DownloadChapterEntity?,
    private val runChapter: suspend (DownloadChapterEntity, shouldContinue: () -> Boolean) -> DownloadOutcome,
    private val deleteOrphans: suspend () -> Unit,
    private val countTasks: suspend () -> Int,
    private val chapterIntervalMs: Long,
    private val delay: suspend (Long) -> Unit = { kotlinDelay(it) },
) {

    /**
     * 本批「重试耗尽被跳过」的章数；[run] 开始时归零。
     *
     * 收尾文案据此区分「全部下载完成」与「完成但跳过了 N 章」——一律说全部完成会让用户
     * 以为失败章也下好了。
     */
    var skippedCount: Int = 0
        private set

    /**
     * 跑一批：直到队列跑空、取不到可跑任务、runner 暂停或 [isRunning] 变 false。
     *
     * [onChapterStart] 在每章开跑前回调（Service 用它推进度通知），不参与判定。
     */
    suspend fun run(
        isRunning: () -> Boolean,
        onChapterStart: (DownloadChapterEntity) -> Unit = {},
    ): BatchEnd {
        skippedCount = 0
        while (isRunning()) {
            val task = nextTask() ?: return settled()
            onChapterStart(task)
            when (runChapter(task, isRunning)) {
                DownloadOutcome.Paused -> return BatchEnd.Interrupted
                DownloadOutcome.Skipped -> skippedCount++
                DownloadOutcome.Completed -> Unit
            }
            if (!isRunning()) return BatchEnd.Interrupted
            delay(chapterIntervalMs)
        }
        return BatchEnd.Interrupted
    }

    /**
     * 不跑章、只判当前队列的终态；供「用户按暂停」这类打断路径使用。
     *
     * 与 [run] 跑空时的判定差一步：这里**不清孤儿**——暂停不是收尾，孤儿清理属于
     * 「这一批确实没有可跑任务了」的那个时点（见 [settled]）。
     */
    suspend fun terminalState(): BatchEnd =
        if (nextTask() != null || countTasks() > 0) BatchEnd.PausedRemaining else BatchEnd.Finished

    /**
     * 这一批确实取不到可跑任务了：先清孤儿（书已不在架的行永远取不到，留着只会在下次
     * 批次里复活），再按剩余任务数判终态。
     *
     * 顺序是语义的一部分：先判后清会把孤儿行算进剩余任务里，队列跑空却报「已暂停」。
     */
    private suspend fun settled(): BatchEnd {
        deleteOrphans()
        return if (countTasks() > 0) BatchEnd.PausedRemaining else BatchEnd.Finished
    }
}
