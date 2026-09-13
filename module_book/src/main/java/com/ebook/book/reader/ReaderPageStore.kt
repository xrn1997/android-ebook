package com.ebook.book.reader

import androidx.compose.runtime.mutableStateMapOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 阅读页的加载仓库：[ReaderPageKey] → [ReaderPageUi]，两种翻页方式共用。
 *
 * 只回答「这一屏的内容是什么状态」，不含任何几何——横向拖拽的三页窗口见
 * [ReaderPagerController]，跨章连续的竖向列表见 [ReaderScrollController]。
 * 抽出来的理由是两类前端都需要同一套竞态处置，而那套处置的结论很贵（见 [ensureLoad]
 * 里两处「为什么不这样做」的注释），不该在两个类里各写一遍再各自漂移。
 *
 * 竞态说明：页面加载以 [ReaderPageKey] 为键去重（在途 job 与已 Loaded 的页都不重复请求）；
 * 加载成功后经 [onLoaded] 通知前端重算自己的几何，前端据此判断这次完成是否仍与自己相关
 * （翻页模式判 `key == durKey`，滚屏模式判该章是否仍在已物化的章段里），
 * 因此翻页/滚动途中提交的在途任务会自动归属新窗口，无需时间戳过期校验。
 * 跳转时由前端调 [clear] 取消全部在途任务。
 *
 * **两端都跨章累积、都要裁（[retain]），差别只在裁剪半径的单位**：翻页模式的几何就是三页窗口
 * （它在章首/章末把窗口指到**相邻章**，`refreshWindow` 给出 `(c±1, 哨兵)`，翻页提交又不走
 * [clear]），故按窗口裁到三个键。滚屏模式的列表跨章连续，按**块**半径裁是错的——同章内回滚
 * 会把刚读过的块丢掉、重走一遍加载并在 UI 上闪一下转圈（实测 0→6→0 往返，7 块里 4 块被请求
 * 两次），故它按**章**半径裁（锚点章 ±2 章）。
 */
internal class ReaderPageStore(
    private val scope: CoroutineScope,
    private val loadPage: suspend (chapterIndex: Int, pageIndex: Int) -> ReaderPageUi.Loaded?,
) {

    private val pages = mutableStateMapOf<ReaderPageKey, ReaderPageUi>()
    private val jobs = mutableMapOf<ReaderPageKey, Job>()

    /**
     * 加载成功回调（失败置 [ReaderPageUi.Error] 后不回调）。
     *
     * 前端用它重算自己的几何，相关性判据由前端给、仓库不关心。
     */
    var onLoaded: ((ReaderPageKey, ReaderPageUi.Loaded) -> Unit)? = null

    /** 取指定页渲染状态（未登记按加载中兜底） */
    fun uiOf(key: ReaderPageKey?): ReaderPageUi = key?.let { pages[it] } ?: ReaderPageUi.Loading

    fun ensureLoad(key: ReaderPageKey) {
        if (jobs.containsKey(key)) return
        // 已就绪的页不重抓：job 完成即从 [jobs] 注销，只看 jobs 去重会让前端重算把仍是
        // Loaded 的来路页打回 Loading 再抓一遍（快速回翻时刚读过的那页会闪一下转圈，
        // 白跑一次 DB/网络 + 整章重排）。翻页只改窗口、不改排版，Loaded 的正文不会失效；
        // 真正的换装点（字号/跳章/换翻页方式）走 [clear]，那里已清空 [pages]，不受本短路影响；
        // [reload] 只挂在错误态重试按钮上（Error 不是 Loaded），也不会被挡。
        if (pages[key] is ReaderPageUi.Loaded) return
        pages[key] = ReaderPageUi.Loading
        jobs[key] = scope.launch {
            val myJob = coroutineContext[Job]
            val loaded = loadPage(key.chapterIndex, key.pageIndex)
            // 仅当本协程仍是该 key 的当前登记任务时注销：若中途被 clear/reload/retain
            // 取消并移出 jobs，后继任务可能已用同 key 重新登记，此处无条件删除会把后继任务
            // 误删成孤儿（完成后用陈旧页覆盖已清空的窗口）。身份比对 + isActive 双保险。
            if (myJob?.isActive == true && jobs[key] === myJob) jobs.remove(key)
            if (isActive.not()) return@launch
            if (loaded != null) {
                pages[key] = loaded
                onLoaded?.invoke(key, loaded)
            } else {
                pages[key] = ReaderPageUi.Error
            }
        }
    }

    /** 加载失败重试（只挂在错误态的重试按钮上，Error 不是 Loaded 故不被 ensureLoad 短路挡下） */
    fun reload(key: ReaderPageKey) {
        jobs.remove(key)?.cancel()
        ensureLoad(key)
    }

    /** 跳转/换装点：取消全部在途任务并清空状态 */
    fun clear() {
        jobs.values.forEach { it.cancel() }
        jobs.clear()
        pages.clear()
    }

    /**
     * 清理保留集之外的页面状态与在途任务，防止内存累积。
     *
     * 保留集由前端按自己的几何给出，两端连续阅读都会跨章累积、都要调：翻页模式保留三页窗口的
     * 三个键；滚屏模式保留锚点章 ±2 章的全部块——半径单位是**章**而不是块，理由见类 KDoc。
     */
    fun retain(keep: Set<ReaderPageKey>) {
        pages.keys.toList().filter { it !in keep }.forEach {
            pages.remove(it)
            jobs.remove(it)?.cancel()
        }
    }
}
