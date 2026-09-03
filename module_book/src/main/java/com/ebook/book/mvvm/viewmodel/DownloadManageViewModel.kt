package com.ebook.book.mvvm.viewmodel

import android.content.Context
import androidx.lifecycle.viewModelScope
import com.ebook.book.R
import com.ebook.book.repository.DownloadRepository
import com.ebook.book.repository.DownloadState
import com.ebook.book.service.DownloadService
import com.ebook.db.entity.DownloadChapterEntity
import com.xrn1997.common.util.ToastUtil
import com.xrn1997.common.mvvm.viewmodel.BaseViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 下载管理页的按书聚合模型。
 *
 * - [remaining]：队列里这本书还没下完的章数（随任务增删变化）
 * - [totalChapters]/[cachedChapters]：全书章节数与已缓存数，构成"全书缓存覆盖率"进度条
 * - [isActive]：该书是否有章节正在被服务抓取（用于高亮当前书）
 *
 * 两个进度口径刻意分开：队列剩余反映"这批任务还剩多少"，覆盖率反映"全书已可离线的比例"，
 * 前者随批次消长、后者随阅读/下载单调增长，混在一起会误导用户（见 DownloadRepository.getCacheCoverage）。
 */
data class DownloadBookGroup(
    val noteUrl: String,
    val bookName: String,
    val coverUrl: String,
    val remaining: Int,
    val totalChapters: Int,
    val cachedChapters: Int,
    val isActive: Boolean = false,
)

/**
 * 下载管理页 ViewModel（Model = [DownloadRepository]，遵循本仓库"仓库即 Model"约定）。
 *
 * 职责：
 * 1. 把 `download_chapter` 队列表按书分组，并叠加全书缓存覆盖率（页面数据源）
 * 2. 承接服务状态流 [downloadState]，标记"当前正在下载的章节"（高亮）
 * 3. 发送开始/暂停/取消动作（与通知按钮同一 Intent 直达 [DownloadService]，
 *    不依赖任何页面存活的命令通道）
 * 4. 暴露 [remainingCount]（队列剩余数）供书架下载图标角标订阅
 *
 * 注：本页与书架页各自持有独立实例（ViewModel 按宿主作用域隔离），数据经 Model 实时查库，
 * 因此不存在"晚开页面拿到旧快照"的问题——[downloadState] 的 replay=1 仅用于兜底当前进度。
 */
@HiltViewModel
class DownloadManageViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    downloadRepository: DownloadRepository,
) : BaseViewModel<DownloadRepository>(downloadRepository) {

    /** 下载状态流（Service → UI）。SharedFlow 无初始值，页面收集时以 [DownloadState.Finished] 起步。 */
    val downloadState: SharedFlow<DownloadState> = model.downloadState

    /** 按书聚合的任务列表（剩余章数 + 全书缓存覆盖率），页面据此渲染分组卡片。 */
    private val _groups = MutableStateFlow<List<DownloadBookGroup>>(emptyList())
    val groups: StateFlow<List<DownloadBookGroup>> = _groups.asStateFlow()

    /**
     * 队列剩余数的响应式观察（书架下载图标角标）。
     *
     * WhileSubscribed：角标仅在书架页可见时保持活跃，离开后停止查库；
     * 初始值 0 = 无任务时角标隐藏。
     */
    val remainingCount: StateFlow<Int> = model.observeRemainingCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /** 当前正在下载的章节 URL（用于判断哪本书处于活跃态） */
    private var activeChapterUrl: String? = null

    /**
     * 加载/刷新按书分组：读队列任务表 + 逐书叠加缓存覆盖率。
     *
     * 每次任务增删后都应重调（进度事件驱动，见 DownloadManageActivity 的状态收集）。
     */
    fun loadGroups() {
        viewModelScope.launch {
            val grouped = model.getAllTasks()
                .groupBy { it.noteUrl }
                .map { (noteUrl, tasks) ->
                    val first = tasks.first()
                    val coverage = model.getCacheCoverage(noteUrl)
                    DownloadBookGroup(
                        noteUrl = noteUrl,
                        bookName = first.bookName,
                        coverUrl = first.coverUrl,
                        remaining = tasks.size,
                        totalChapters = coverage.total,
                        cachedChapters = coverage.cached,
                        isActive = tasks.any { it.durChapterUrl == activeChapterUrl }
                    )
                }
            _groups.value = grouped
        }
    }

    /**
     * 标记当前正在下载的章节（由 Progress 事件驱动），并把"活跃"高亮切到所属书。
     *
     * 服务同一时刻只抓一章，故活跃书唯一；只在 URL 变化时改写，避免每章重复重组。
     */
    fun onProgressChapter(chapter: DownloadChapterEntity) {
        if (activeChapterUrl != chapter.durChapterUrl) {
            activeChapterUrl = chapter.durChapterUrl
            _groups.value = _groups.value.map { it.copy(isActive = it.noteUrl == chapter.noteUrl) }
        }
    }

    /**
     * 发送下载控制动作（[DownloadService.ACTION_RESUME] / [ACTION_PAUSE] / [ACTION_CANCEL]）。
     *
     * Intent 直达，服务已存活或被回收都能送达（见 DownloadService 类注释）。
     * 启动被拒只剩一种现实情形：dataSync 前台配额（24 小时/6 小时）已用尽——此时页内提示
     * 用户而不能未捕获抛异常（点“全部开始”直接闪退），任务仍在库里，稍后重试即续跑
     * （见 [DownloadService.start]）。
     *
     * 提示走 [ToastUtil] 而非基类的 `sendToast` 命令通道：本 VM 除下载管理页外，还被
     * `BookShelfPage` 以 `hiltViewModel()` 承载（见该页），那条宿主路径上没有 `MvvmBinder`
     * 消费命令，`sendToast` 只会堆在 Channel 里随 VM 销毁丢弃——即「该提示的没提示」。
     */
    fun sendAction(action: String) {
        if (!DownloadService.start(context, DownloadService.buildActionIntent(context, action))) {
            ToastUtil.showShort(context, context.getString(R.string.download_start_restricted))
        }
    }

    /**
     * 打开页面时：若队列还有任务，恢复下载（对齐原弹窗 initWait 的自动续跑）。
     *
     * 必须先确认有任务再发 RESUME——空队列发 RESUME 会走到 finishDownload，
     * 误发"下载完成"通知与 Toast（见 DownloadService.toDownload 的无任务分支）。
     */
    fun resumeIfPending() {
        viewModelScope.launch {
            if (model.findLatestDownloadTask() != null) {
                sendAction(DownloadService.ACTION_RESUME)
            }
        }
    }

    /** 取消某本书：删除其队列任务后刷新分组（不动已完成的缓存内容）。 */
    fun cancelBook(noteUrl: String) {
        viewModelScope.launch {
            model.deleteTasksForBook(noteUrl)
            loadGroups()
        }
    }
}
