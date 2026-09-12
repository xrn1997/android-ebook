package com.ebook.book

import android.os.Bundle
import androidx.activity.viewModels
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ebook.book.mvvm.viewmodel.BookChapterSelection
import com.ebook.book.mvvm.viewmodel.DownloadBookGroup
import com.ebook.book.mvvm.viewmodel.DownloadManageViewModel
import com.ebook.book.reader.BookChapterSelectSheet
import com.ebook.book.repository.DownloadState
import com.ebook.book.service.DownloadService
import com.ebook.common.event.KeyCode
import com.ebook.common.ui.BookCover
import com.ebook.common.ui.CommonCard
import com.ebook.common.ui.CommonItemCard
import com.ebook.common.ui.CommonUiTokens
import com.permissionx.guolindev.PermissionX
import com.therouter.router.Route
import com.xrn1997.common.mvvm.compose.BaseMvvmActivity
import dagger.hilt.android.AndroidEntryPoint

/**
 * 下载管理页（Compose，替代书架上那个 80dp 的下载小弹窗）。
 *
 * 布局：顶部概览卡（全局状态 + 队列总数 + 全部开始/暂停/取消）+ 按书分组的任务列表。
 *
 * 进度口径两条并行（刻意分开，见 [DownloadBookGroup]）：
 * - "剩余 N 章"：`download_chapter` 队列里这本书还没下完的量，随批次消长
 * - "已缓存 y/z"：全书已可离线的比例（进度条），随阅读/下载单调增长
 *
 * 数据来源：任务/覆盖率实时查库（[DownloadManageViewModel.loadGroups]），当前进度经
 * [DownloadState] 状态流推送；打开页面时若队列有任务则自动续跑（对齐原弹窗 initWait）。
 */
@AndroidEntryPoint
@Route(path = KeyCode.Book.DOWNLOAD_PATH)
class DownloadManageActivity : BaseMvvmActivity<DownloadManageViewModel>() {
    override val viewModel: DownloadManageViewModel by viewModels()

    /**
     * 阅读器直达的一本书（仅在冷启动消费一次）。
     *
     * 旋转重建（savedInstanceState != null）时 ViewModel 已持有 bookSheet 现场，
     * 不重放直达参数，避免把已退回一级列表的用户硬拽回二级选章。
     */
    internal class PickBookParams(
        val noteUrl: String,
        val tag: String,
        val focusChapter: Int,
    )

    /** 阅读器直达参数：仅在 onCreate(savedInstanceState==null) 时消费一次，旋转重建不再重放。 */
    internal var pickParams: PickBookParams? = null

    companion object {
        const val EXTRA_NOTE_URL = "extra_note_url"
        const val EXTRA_TAG = "extra_tag"
        const val EXTRA_FOCUS_CHAPTER = "extra_focus_chapter"
        const val EXTRA_OPEN_PICK = "extra_open_pick"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        toolbarTitle.value = getString(R.string.download_manage_title)
        // 阅读器直达只该发生在冷启动：旋转重建（savedInstanceState != null）时 ViewModel 已
        // 持有 bookSheet 现场，不重放直达参数，避免用户退回一级后旋转被硬拽回二级选章。
        if (savedInstanceState == null) {
            pickParams = if (intent.getBooleanExtra(EXTRA_OPEN_PICK, false)) {
                PickBookParams(
                    noteUrl = intent.getStringExtra(EXTRA_NOTE_URL) ?: "",
                    tag = intent.getStringExtra(EXTRA_TAG) ?: "",
                    focusChapter = intent.getIntExtra(EXTRA_FOCUS_CHAPTER, -1)
                )
            } else null
        }
    }

    @Composable
    override fun PageContent() {
        DownloadCenterScreen(activity = this, viewModel = viewModel)
    }

    /**
     * 申请下载进度通知权限（POST_NOTIFICATIONS）。
     *
     * 无论授予与否都回调 [onResult]：通知只是进度的展示渠道，前台服务与落库不依赖它；
     * 把它当成下载前置门槛会造成"拒绝过一次通知 → 点下载完全没反应"。
     */
    fun requestDownloadPermission(onResult: () -> Unit) {
        PermissionX
            .init(this)
            .permissions(PermissionX.permission.POST_NOTIFICATIONS)
            .request { _: Boolean, _: List<String?>?, _: List<String?>? -> onResult() }
    }
}

/**
 * 下载中心编排：一级按书任务列表，二级以全高 ModalBottomSheet 展示该书选章。
 *
 * sheet 的收起（swipe / 系统返回）由 ModalBottomSheet 自处理并经 onDismiss 复位状态，
 * 不再有页面级 BackHandler；返回键语义与 sheet 原生手势一致。
 * 从阅读器带 extras 进入时直达二级（EXTRA_OPEN_PICK）。
 */
@Composable
private fun DownloadCenterScreen(
    activity: DownloadManageActivity,
    viewModel: DownloadManageViewModel,
) {
    val bookSheet by viewModel.bookSheet.collectAsState()

    // 状态驱动刷新：进度每推进一章，一级分组与（若已展开 sheet）该书状态标签同步刷新；
    // 打开页面时若队列有任务则自动续跑（对齐原弹窗 initWait，见 resumeIfPending）
    LaunchedEffect(Unit) {
        viewModel.loadGroups()
        viewModel.resumeIfPending()
        viewModel.downloadState.collect { s ->
            viewModel.onDownloadState(s)
            when (s) {
                is DownloadState.Progress -> {
                    viewModel.refreshSelection()
                    viewModel.loadGroups()
                }
                DownloadState.Paused, DownloadState.Finished -> {
                    // 暂停/收尾也刷新二级状态：isDownloading 已在 onDownloadState 置 false，
                    // activeChapterIndex 收起，二级「下载中」徽章与一级卡片同口径不再误显
                    viewModel.refreshSelection()
                    viewModel.loadGroups()
                }
            }
        }
    }

    // 阅读器直达：仅冷启动时 activity.pickParams 非空（旋转重建已被 onCreate 门滤掉，
    // 此时 ViewModel 的 bookSheet 已保留用户在二级/一级的现场，不重放直达）
    LaunchedEffect(Unit) {
        val params = activity.pickParams ?: return@LaunchedEffect
        viewModel.openBook(params.noteUrl, params.tag, params.focusChapter)
    }

    DownloadManageScreen(
        viewModel = viewModel,
        onOpenBook = { group -> viewModel.openBook(group.noteUrl, group.tag) }
    )

    // 二级选章 sheet：bookSheet != null 时展开（Loading/Absent/Failed/Ready 四态），
    // ModalBottomSheet 自带返回收起语义，不再用 BackHandler 干预。
    var pendingCancelBook by remember { mutableStateOf<BookChapterSelection?>(null) }
    bookSheet?.let { sheetState ->
        BookChapterSelectSheet(
            state = sheetState,
            onDismiss = {
                pendingCancelBook = null
                viewModel.closeBookSheet()
            },
            onConfirm = { selected ->
                activity.requestDownloadPermission { viewModel.confirmDownload(selected) }
            },
            onCancelBook = { selection -> pendingCancelBook = selection },
        )
    }
    // 取消本书二次确认：书维度操作归书上下文（sheet 头部触发）
    pendingCancelBook?.let { ready ->
        AlertDialog(
            onDismissRequest = { pendingCancelBook = null },
            text = { Text(stringResource(R.string.download_manage_cancel_book_confirm, ready.bookName)) },
            confirmButton = {
                TextButton(onClick = {
                    pendingCancelBook = null
                    viewModel.cancelBook(ready.noteUrl)
                    viewModel.closeBookSheet()
                }) {
                    Text(
                        stringResource(R.string.download_manage_cancel_book),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingCancelBook = null }) {
                    Text(stringResource(com.ebook.common.R.string.cancel))
                }
            }
        )
    }
}

/**
 * 一级：按书下载列表 + 右下单主 FAB（播放/暂停互斥）。
 *
 * - 书行 = [CommonItemCard]（12dp 圆角、listSpacing 行距）：封面 + 书名 + 覆盖率进度条 +
 *   「剩余 N 章 · 正在下载 第 M 章」进行态 + 尾箭头；整行点击展开该书选章 sheet。
 * - 「全部取消」为破坏性操作，不放 FAB（M3 单一主动作），做在列表尾部动作项 + 二次确认。
 * - 全局状态表达：运行/暂停由 FAB 图标互斥；单书进度由行内进行态表达。
 */
@Composable
fun DownloadManageScreen(
    viewModel: DownloadManageViewModel,
    onOpenBook: (DownloadBookGroup) -> Unit
) {
    val groups by viewModel.groups.collectAsState()
    val state by viewModel.downloadState.collectAsState(initial = DownloadState.Finished)
    var showCancelAll by remember { mutableStateOf(false) }

    val hasTask = groups.isNotEmpty()
    val isRunning = state is DownloadState.Progress

    Box(modifier = Modifier.fillMaxSize()) {
        if (!hasTask) {
            EmptyState(modifier = Modifier.fillMaxSize())
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = CommonUiTokens.pagePadding)
                    .padding(bottom = 88.dp), // FAB 悬浮区留白，防最后一行被遮挡
                contentPadding = PaddingValues(
                    top = CommonUiTokens.listSpacing,
                    bottom = CommonUiTokens.sectionSpacing
                ),
                verticalArrangement = Arrangement.spacedBy(CommonUiTokens.listSpacing)
            ) {
                items(groups, key = { it.noteUrl }) { group ->
                    DownloadBookRow(
                        group = group,
                        onClick = { onOpenBook(group) }
                    )
                }
                item(key = "cancel_all") {
                    // 列表尾部破坏性动作：FAB 只管主操作，取消全部放列表上下文 + 二次确认
                    CommonCard(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { showCancelAll = true }
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = stringResource(R.string.download_manage_cancel_all),
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                    }
                }
            }
        }

        // 主操作 FAB：图标随下载状态互斥（运行中=暂停，否则=播放/继续）
        if (hasTask) {
            FloatingActionButton(
                onClick = {
                    viewModel.sendAction(
                        if (isRunning) DownloadService.ACTION_PAUSE
                        else DownloadService.ACTION_RESUME
                    )
                },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(CommonUiTokens.pagePadding)
            ) {
                Icon(
                    imageVector = if (isRunning) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = stringResource(
                        if (isRunning) R.string.download_manage_pause_all
                        else R.string.download_manage_resume_all
                    )
                )
            }
        }
    }

    // 全部取消确认
    if (showCancelAll) {
        AlertDialog(
            onDismissRequest = { showCancelAll = false },
            text = { Text(stringResource(R.string.download_manage_cancel_all_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    showCancelAll = false
                    viewModel.sendAction(DownloadService.ACTION_CANCEL)
                }) {
                    Text(
                        stringResource(R.string.download_manage_cancel_all),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showCancelAll = false }) {
                    Text(stringResource(com.ebook.common.R.string.cancel))
                }
            }
        )
    }
}

/**
 * 单本书的下载行：封面 + 书名 + 覆盖率进度 + 进行态副行 + 尾箭头。
 *
 * 取消本书不在行内（在选章 sheet 头部）；本行只负责「点进去选章」。
 * 进行态文案仅 DownloadState.Progress 期间展示（activeChapter 已随 isDownloading 收起）。
 */
@Composable
private fun DownloadBookRow(
    group: DownloadBookGroup,
    onClick: () -> Unit,
) {
    val coverageRatio = remember(group.totalChapters, group.cachedChapters) {
        if (group.totalChapters > 0) group.cachedChapters.toFloat() / group.totalChapters else 0f
    }

    CommonItemCard(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BookCover(
                url = group.coverUrl,
                modifier = Modifier.size(width = 48.dp, height = 64.dp),
                contentDescription = group.bookName
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = group.bookName,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { coverageRatio },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(
                        R.string.download_manage_coverage_format,
                        group.cachedChapters,
                        group.totalChapters
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                group.activeChapter?.let { active ->
                    Text(
                        text = stringResource(
                            R.string.download_manage_downloading_chapter_format,
                            active.durChapterIndex + 1,
                            active.durChapterName
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = stringResource(R.string.download_manage_remaining_format, group.remaining),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 无任务时的空态占位。 */
@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Text(
            text = stringResource(R.string.download_manage_no_task),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
