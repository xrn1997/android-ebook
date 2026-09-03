package com.ebook.book

import android.os.Bundle
import androidx.activity.viewModels
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import com.ebook.book.mvvm.viewmodel.DownloadBookGroup
import com.ebook.book.mvvm.viewmodel.DownloadManageViewModel
import com.ebook.book.repository.DownloadState
import com.ebook.book.service.DownloadService
import com.ebook.common.event.KeyCode
import com.ebook.common.ui.BookCover
import com.ebook.common.ui.CommonCard
import com.ebook.common.ui.CommonUiTokens
import com.ebook.common.ui.InfoChip
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
    protected override val viewModel: DownloadManageViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        toolbarTitle.value = getString(R.string.download_manage_title)
    }

    @Composable
    override fun PageContent() {
        DownloadManageScreen(viewModel = viewModel)
    }
}

/**
 * 下载管理页内容。
 *
 * 参数化传入 [viewModel] 而非在内部 hiltViewModel()：Activity 已通过 by viewModels()
 * 持有，页面必须与其共用同一实例。
 */
@Composable
fun DownloadManageScreen(viewModel: DownloadManageViewModel) {
    val groups by viewModel.groups.collectAsState()
    val state by viewModel.downloadState.collectAsState(initial = DownloadState.Finished)
    var showCancelAll by remember { mutableStateOf(false) }
    var pendingCancelBook by remember { mutableStateOf<DownloadBookGroup?>(null) }

    // 打开页面：拉分组 + 有任务则续跑
    LaunchedEffect(Unit) {
        viewModel.loadGroups()
        viewModel.resumeIfPending()
    }

    // 状态驱动刷新：每章推进/暂停/完成时任务表已变化，重拉分组对齐
    LaunchedEffect(Unit) {
        viewModel.downloadState.collect { s ->
            when (s) {
                is DownloadState.Progress -> {
                    viewModel.onProgressChapter(s.chapter)
                    viewModel.loadGroups()
                }
                DownloadState.Paused, DownloadState.Finished -> viewModel.loadGroups()
            }
        }
    }

    val totalRemaining = groups.sumOf { it.remaining }
    val isRunning = state is DownloadState.Progress
    val isPaused = state is DownloadState.Paused

    Column(modifier = Modifier.fillMaxSize()) {
        SummarySection(
            isRunning = isRunning,
            isPaused = isPaused,
            totalRemaining = totalRemaining,
            hasTask = groups.isNotEmpty(),
            onResumeAll = { viewModel.sendAction(DownloadService.ACTION_RESUME) },
            onPauseAll = { viewModel.sendAction(DownloadService.ACTION_PAUSE) },
            onCancelAll = { showCancelAll = true }
        )

        if (groups.isEmpty()) {
            EmptyState(modifier = Modifier.weight(1f))
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValuesForList(),
                verticalArrangement = Arrangement.spacedBy(CommonUiTokens.listSpacing)
            ) {
                items(groups, key = { it.noteUrl }) { group ->
                    DownloadGroupCard(
                        group = group,
                        onCancelBook = { pendingCancelBook = group }
                    )
                }
            }
        }
    }

    // 取消全部确认
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

    // 取消本书确认
    pendingCancelBook?.let { group ->
        AlertDialog(
            onDismissRequest = { pendingCancelBook = null },
            text = { Text(stringResource(R.string.download_manage_cancel_book_confirm, group.bookName)) },
            confirmButton = {
                TextButton(onClick = {
                    pendingCancelBook = null
                    viewModel.cancelBook(group.noteUrl)
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
 * 列表内容边距：页面水平边距与上下留白走 [CommonUiTokens]。
 */
private fun PaddingValuesForList(): PaddingValues =
    PaddingValues(
        start = CommonUiTokens.pagePadding,
        end = CommonUiTokens.pagePadding,
        top = CommonUiTokens.listSpacing,
        bottom = CommonUiTokens.sectionSpacing
    )

/**
 * 概览区：全局状态文案 + 队列总数 + 全部开始/暂停/取消。
 *
 * 按钮可用态由当前状态推导，避免在"运行中点开始""无任务点取消"这类无效操作。
 */
@Composable
private fun SummarySection(
    isRunning: Boolean,
    isPaused: Boolean,
    totalRemaining: Int,
    hasTask: Boolean,
    onResumeAll: () -> Unit,
    onPauseAll: () -> Unit,
    onCancelAll: () -> Unit
) {
    val statusText = when {
        isRunning -> stringResource(R.string.download_manage_status_running)
        isPaused -> stringResource(R.string.download_manage_status_paused)
        else -> stringResource(R.string.download_manage_status_idle)
    }

    Column(modifier = Modifier.padding(CommonUiTokens.pagePadding)) {
        CommonCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f)
                    )
                    if (hasTask) {
                        Text(
                            text = stringResource(R.string.download_manage_total_format, totalRemaining),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = onResumeAll,
                        enabled = hasTask && !isRunning
                    ) {
                        Text(stringResource(R.string.download_manage_resume_all))
                    }
                    OutlinedButton(
                        onClick = onPauseAll,
                        enabled = isRunning
                    ) {
                        Text(stringResource(R.string.download_manage_pause_all))
                    }
                    OutlinedButton(
                        onClick = onCancelAll,
                        enabled = hasTask
                    ) {
                        Text(
                            stringResource(R.string.download_manage_cancel_all),
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(CommonUiTokens.sectionSpacing))
    }
}

/**
 * 单本书的下载分组卡：封面 + 书名 + 状态标签 + 剩余/覆盖率 + 进度条 + 取消本书。
 */
@Composable
private fun DownloadGroupCard(
    group: DownloadBookGroup,
    onCancelBook: () -> Unit
) {
    val coverageRatio = remember(group.totalChapters, group.cachedChapters) {
        if (group.totalChapters > 0) group.cachedChapters.toFloat() / group.totalChapters else 0f
    }

    CommonCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BookCover(
                    url = group.coverUrl,
                    modifier = Modifier.size(width = 48.dp, height = 64.dp),
                    contentDescription = group.bookName
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = group.bookName,
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        InfoChip(
                            text = stringResource(
                                if (group.isActive) R.string.download_manage_active_tag
                                else R.string.download_manage_queued_tag
                            ),
                            containerColor = if (group.isActive) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = if (group.isActive) MaterialTheme.colorScheme.onPrimaryContainer
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.download_manage_remaining_format, group.remaining),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            LinearProgressIndicator(
                progress = { coverageRatio },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = stringResource(
                    R.string.download_manage_coverage_format,
                    group.cachedChapters,
                    group.totalChapters
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = onCancelBook) {
                    Text(
                        stringResource(R.string.download_manage_cancel_book),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
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
