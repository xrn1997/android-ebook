package com.ebook.book.mvvm.viewmodel

import android.content.Context
import androidx.lifecycle.viewModelScope
import com.ebook.book.R
import com.ebook.book.repository.DownloadRepository
import com.ebook.book.repository.DownloadState
import com.ebook.book.service.DownloadService
import com.ebook.common.util.reportFailure
import com.ebook.db.entity.ChapterListEntity
import com.ebook.db.entity.DownloadChapterEntity
import com.xrn1997.common.mvvm.viewmodel.BaseViewModel
import com.xrn1997.common.util.Logger
import com.xrn1997.common.util.ToastUtil
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
 * - [tag]：该书书源归属标记（二级视图取 parser 与缓存定位用，来自组内首条任务）
 * - [activeChapter]：当前正在下载的章节（仅活跃书有值）
 *
 * 两个进度口径刻意分开：队列剩余反映"这批任务还剩多少"，覆盖率反映"全书已可离线的比例"，
 * 前者随批次消长、后者随阅读/下载单调增长，混在一起会误导用户（见 DownloadRepository.getCacheCoverage）。
 * 下载进度（[activeChapter] / 队列剩余）≠ 全书覆盖率（[cachedChapters]/[totalChapters]）。
 */
data class DownloadBookGroup(
    val noteUrl: String,
    val bookName: String,
    val coverUrl: String,
    val tag: String,
    val remaining: Int,
    val totalChapters: Int,
    val cachedChapters: Int,
    val isActive: Boolean = false,
    /** 当前正在下载的章节（仅活跃书有值；下载进度口径之一，见类 KDoc；仅服务 Progress 时展示，暂停/完成不显示） */
    val activeChapter: DownloadChapterEntity? = null,
)

/**
 * 二级选章页的装载结果，三态分开（加载中 / 书不在架 / 就绪）。
 *
 * 刻意不把 [Absent] 与 [Loading] 合并成同一个 null：两者都会渲染成页面的替代视图，
 * 混在一起会把「该书不在书架」的独立空态说成「正在加载…」，误导用户一直等。
 * 装载过程中的数据库/章文件 IO 异常落 [Failed]，避免卡在加载态或把失败伪装成空。
 */
sealed interface BookSelectionState {
    /** 首次进入 / 换书时的装载中。 */
    data object Loading : BookSelectionState

    /** 书架查无此书（阅读器入口先确保在架不会到这；一级入口的书被移出书架但队列任务残留时会到这）。 */
    data object Absent : BookSelectionState

    /** 装载失败（Room / 章文件 IO 异常）。 */
    data object Failed : BookSelectionState

    /** 装载完成，页面渲染选中数据。 */
    data class Ready(val selection: BookChapterSelection) : BookSelectionState
}

/**
 * 二级视图数据：书信息 + 章目录 + 缓存/队列事实 + 当前下载章 + 预勾选 + 焦点章。
 *
 * [initialSelected] 只在「从阅读器进入（带了焦点章）」时非空：沿用原下载面板
 * 的「当前章 + 50 章内未缓存且未排队者」一键下载预勾选；从一级进入则为空集。
 * [focusChapter] 与预勾选解耦：它是阅读器当前章本身（>=0），预勾选可能因范围全缓存
 * 而为空，但它仍用于「默认展开含当前章的那一组」（ADR-0034），不能从预勾选反推。
 */
data class BookChapterSelection(
    val noteUrl: String,
    val tag: String,
    val bookName: String,
    val coverUrl: String,
    val chapters: List<ChapterListEntity>,
    val cachedIndices: Set<Int>,
    val queuedIndices: Set<Int>,
    val activeChapterIndex: Int?,
    val initialSelected: Set<Int>,
    /** 阅读器进入时携带的当前章（>=0 有效；一级入口为 -1）。 */
    val focusChapter: Int = -1,
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

    /** 二级选章 sheet：null = 收起（一级列表态）；非空 = 该书装载结果（Loading/Absent/Failed/Ready）。 */
    private val _bookSheet = MutableStateFlow<BookSelectionState?>(null)
    val bookSheet: StateFlow<BookSelectionState?> = _bookSheet.asStateFlow()

    /** 阅读器进入时携带的当前章（>=0 才启用「当前章+50」预勾选与定位；一级入口为 -1）。 */
    private var pendingFocusChapter: Int = -1

    /** 当前展开 sheet 的书；openBook 时写入，供 loadSelection/refreshSelection 使用。 */
    private var activeNoteUrl: String = ""
    private var activeTag: String = ""

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

    /** 服务当前是否在真正下载（只有 Progress 时才能断言「正在下载第 N 章」，暂停/完成时收起） */
    private var isDownloading: Boolean = false

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
                    // 归属取该组首条任务的 tag：同一 noteUrl 下的任务归属必然一致——
                    // 换源会把旧 noteUrl 名下的任务整批删掉（见 BookRepository.commitSwitch），
                    // 不会出现「一本书的组里混着两个源的任务」，故不必回查 book_shelf
                    val coverage = model.getCacheCoverage(noteUrl, first.tag)
                    val active = isDownloading && tasks.any { it.durChapterUrl == activeChapterUrl }
                    DownloadBookGroup(
                        noteUrl = noteUrl,
                        bookName = first.bookName,
                        coverUrl = first.coverUrl,
                        tag = first.tag,
                        remaining = tasks.size,
                        totalChapters = coverage.total,
                        cachedChapters = coverage.cached,
                        isActive = active,
                        activeChapter = if (active) {
                            tasks.first { it.durChapterUrl == activeChapterUrl }
                        } else null
                    )
                }
            _groups.value = grouped
        }
    }

    /**
     * 服务状态事件统一入口：Progress 时记录活跃章并高亮所属书；Paused/Finished 时
     * 收起「正在下载」断言（队列不出队，章还在表里，仅靠队列判会误报）。
     */
    fun onDownloadState(state: DownloadState) {
        isDownloading = state is DownloadState.Progress
        if (state is DownloadState.Progress) {
            if (activeChapterUrl != state.chapter.durChapterUrl) {
                activeChapterUrl = state.chapter.durChapterUrl
                _groups.value = _groups.value.map { it.copy(isActive = it.noteUrl == state.chapter.noteUrl) }
            }
        }
    }

    /**
     * 发送下载控制动作（[DownloadService.ACTION_RESUME] / [DownloadService.ACTION_PAUSE] /
     * [DownloadService.ACTION_CANCEL]）。
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

    /**
     * 展开某书二级选章 sheet 并装载数据。
     *
     * [focusChapter] 为阅读器传入的当前章；书不在架时落 [BookSelectionState.Absent]（sheet 内空态）。
     * 先落 [BookSelectionState.Loading]：换书（或上次装载失败）时避免把上一本书的
     * [BookChapterSelection] 画在下一本书的 sheet 上。
     */
    fun openBook(noteUrl: String, tag: String, focusChapter: Int = -1) {
        activeNoteUrl = noteUrl
        activeTag = tag
        pendingFocusChapter = focusChapter
        _bookSheet.value = BookSelectionState.Loading
        loadSelection()
    }

    /** 重新装载当前书的二级数据（下载进行中每章推进后刷新状态标签用）。 */
    fun refreshSelection() {
        if (_bookSheet.value != null) loadSelection()
    }

    /**
     * 装载二级数据：书架信息 + 章目录 + 缓存 + 队列三方合并。
     *
     * 失败（Room / 章文件 IO 异常）落 [BookSelectionState.Failed] 并经 [reportFailure] 提示，
     * 不会让页面无限停在「正在加载…」（同模块范式：EditBookMetaViewModel.loadState）。
     */
    private fun loadSelection() {
        val noteUrl = activeNoteUrl
        val tag = activeTag
        if (noteUrl.isEmpty()) return
        viewModelScope.launch {
            try {
                val full = model.getBookFullInfo(noteUrl)
                if (full == null) {
                    // 书架无此书：二级无内容可展示。阅读器入口先确保在架不会到这；
                    // 一级入口的队列书若已被移出书架（任务残留）才会走进这个分支。
                    _bookSheet.value = BookSelectionState.Absent
                    return@launch
                }
                val chapters = full.chapters
                val cached = model.getCachedIndices(noteUrl, tag, chapters)
                val tasks = model.getTasksByBook(noteUrl)
                val queued = tasks.mapTo(mutableSetOf()) { it.durChapterIndex }
                // 当前下载章只在 Progress 期间断言：暂停/完成时 isDownloading=false，
                // 即便该章仍在队列里，二级「下载中」徽章也随之收起（与一级卡片同口径）
                val active = if (isDownloading) {
                    tasks.firstOrNull { it.durChapterUrl == activeChapterUrl }?.durChapterIndex
                } else null
                val initialSelected = buildInitialSelection(chapters, cached, queued)
                _bookSheet.value = BookSelectionState.Ready(
                    BookChapterSelection(
                        noteUrl = noteUrl,
                        tag = tag,
                        bookName = full.info?.name ?: "",
                        coverUrl = full.info?.coverUrl ?: "",
                        chapters = chapters,
                        cachedIndices = cached,
                        queuedIndices = queued,
                        activeChapterIndex = active,
                        initialSelected = initialSelected,
                        focusChapter = pendingFocusChapter,
                    )
                )
            } catch (e: Exception) {
                Logger.e(TAG, "loadSelection 失败", e)
                _bookSheet.value = BookSelectionState.Failed
                reportFailure(e, context.getString(R.string.download_center_load_failed))
            }
        }
    }

    /**
     * 预勾选：「当前章 .. 当前章+50」中未缓存且未排队者（原下载面板一键下载习惯）。
     * **排除已排队**——排队中的章确认时会跳过，预勾上只会让确认文案多一行"已跳过"。
     */
    private fun buildInitialSelection(
        chapters: List<ChapterListEntity>,
        cached: Set<Int>,
        queued: Set<Int>,
    ): Set<Int> {
        if (chapters.isEmpty() || pendingFocusChapter < 0) return emptySet()
        val end = (pendingFocusChapter + 50).coerceAtMost(chapters.size - 1)
        return (pendingFocusChapter..end).filterTo(mutableSetOf()) { i ->
            i !in cached && i !in queued
        }
    }

    /** 收起二级选章 sheet（[androidx.compose.material3.ModalBottomSheet] 的 onDismiss 触发）。 */
    fun closeBookSheet() {
        _bookSheet.value = null
    }

    /**
     * 确认下载：**剔除已在队列中的章**（排队中再勾 = 无意义重下，与队列唯一索引语义重复），
     * 构建任务（forceRefresh=true）后经 [DownloadRepository.startDownload] 统一下发。
     * 跳过计数由页面读 selection + selected 计算（见 BookChapterSelectContent）。
     *
     * 下发后**乐观更新**已排队集：新勾章立即打上「排队中」标签，不必等下一次
     * Progress 事件驱动的 [refreshSelection] 才回显（下载未推进时标签会一直滞后）。
     */
    fun confirmDownload(selected: Set<Int>) {
        val ready = _bookSheet.value as? BookSelectionState.Ready ?: return
        val selection = ready.selection
        val tasks = (selected - selection.queuedIndices).sorted().mapNotNull { i ->
            selection.chapters.getOrNull(i)?.let { chapter ->
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
        if (tasks.isEmpty()) return
        _bookSheet.value = BookSelectionState.Ready(
            selection.copy(queuedIndices = selection.queuedIndices + tasks.map { it.durChapterIndex })
        )
        viewModelScope.launch { model.startDownload(tasks) }
    }
}
