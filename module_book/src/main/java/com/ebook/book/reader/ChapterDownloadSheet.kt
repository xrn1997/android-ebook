package com.ebook.book.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ebook.book.R
import com.ebook.book.mvvm.viewmodel.BookChapterSelection
import com.ebook.book.mvvm.viewmodel.BookSelectionState
import com.ebook.common.ui.BookCover
import com.ebook.common.ui.CommonUiTokens
import com.ebook.common.ui.InfoChip

/**
 * 下载中心二级：某书全章节的「状态 + 选章」整屏页（一级点书行进入，返回键回一级）。
 *
 * 顶层负责四态渲染（加载中/书不在架/失败/就绪）；
 * 内容复用原选章页的章节分组/三态/软上限纯逻辑（ChapterSelection.kt 零改动）。
 * 顶部导航由宿主基类 Toolbar（标题 + 返回箭头）承担，本页**不自绘返回按钮**，
 * 避免与基类 Toolbar 出现双返回入口；系统返回/工具栏箭头均经活动层 BackHandler
 * 在 [DownloadCenterStep] 间切换。
 */
@Composable
fun BookChapterSelectPage(
    state: BookSelectionState,
    onConfirm: (Set<Int>) -> Unit,
    onCancelBook: (BookChapterSelection) -> Unit,
) {
    when (state) {
        is BookSelectionState.Loading -> CenteredHint(stringResource(R.string.download_center_loading))
        is BookSelectionState.Absent -> CenteredHint(stringResource(R.string.download_center_not_on_shelf))
        is BookSelectionState.Failed -> CenteredHint(stringResource(R.string.download_center_load_failed))
        is BookSelectionState.Ready -> BookChapterSelectContent(
            selection = state.selection,
            onCancelBook = { onCancelBook(state.selection) },
            onConfirm = onConfirm,
        )
    }
}

/** 二级页内居中占位文案（加载中/书不在架/失败共用）。 */
@Composable
private fun CenteredHint(text: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(24.dp)
        )
    }
}

/**
 * 下载中心二级：某书全章节的「状态 + 选章」整屏内容。
 *
 * 每章状态来自 [chapterDownloadStatus] 的三方合并（已缓存/待下载/下载中），**只作展示**：
 * 勾选语义保持不变——勾中任何章（含已缓存）都按 forceRefresh 重下。
 * 确认时剔除已在队列中的章（见 DownloadManageViewModel.confirmDownload），跳过计数显示在
 * 按钮上方。分组/三态/软上限等语义沿用 ChapterSelection.kt。
 */
@Composable
private fun BookChapterSelectContent(
    selection: BookChapterSelection,
    onCancelBook: () -> Unit,
    onConfirm: (Set<Int>) -> Unit,
) {
    var selected by remember { mutableStateOf(selection.initialSelected) }
    // 软上限二次确认：超过 500 章时不直接下发（见 exceedsSelectionCap）
    var capConfirmVisible by remember { mutableStateOf(false) }

    val chapters = selection.chapters
    val queuedIndices = selection.queuedIndices
    val statusList = remember(chapters, selection.cachedIndices, queuedIndices, selection.activeChapterIndex) {
        chapterDownloadStatus(
            count = chapters.size,
            cachedIndices = selection.cachedIndices,
            queuedIndices = queuedIndices,
            activeChapterIndex = selection.activeChapterIndex,
        )
    }

    // 未缓存且未排队索引集：「仅未缓存」chip 的选择输入（队列中的章预勾只会被确认时跳过）
    val uncachedIndices = remember(chapters, selection.cachedIndices, queuedIndices) {
        chapters.indices.filterTo(mutableSetOf()) { i ->
            i !in selection.cachedIndices && i !in queuedIndices
        }
    }

    val listState = rememberLazyListState()
    // 平铺列表：打开时滚动到焦点章（阅读器当前章）附近；批量勾选由顶部快捷选择
    // （全选/仅未缓存/清除）承担，长目录不做数值分组折叠（见 LazyColumn 上方注释）。
    LaunchedEffect(Unit) {
        val focus = selection.focusChapter
        if (focus in chapters.indices) listState.scrollToItem(focus)
    }

    // 确认实际下发数（剔除已排队）与跳过统计
    val downloadCount = (selected - queuedIndices).size
    val skippedQueued = (selected intersect queuedIndices).size
    val confirmEnabled = downloadCount > 0

    Column(modifier = Modifier.fillMaxSize()) {
        // 书头：封面 + 书名 + 状态徽章 + 「取消本书下载」（书维度操作归书上下文）。
        // 顶部返回由基类 Toolbar 承担，本页不再自绘返回箭头（避免双返回入口）。
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = CommonUiTokens.pagePadding, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BookCover(
                url = selection.coverUrl,
                modifier = Modifier.size(width = 40.dp, height = 54.dp),
                contentDescription = selection.bookName
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = selection.bookName,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                selection.activeChapterIndex?.let {
                    Text(
                        text = stringResource(R.string.download_manage_active_tag),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            TextButton(onClick = onCancelBook) {
                Text(
                    stringResource(R.string.download_manage_cancel_book),
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
        // 快捷选择：全选 / 仅未缓存 / 清除 + 已选计数（计数 chip 放行尾）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = CommonUiTokens.pagePadding),
            verticalAlignment = Alignment.CenterVertically
        ) {
            InfoChip(
                text = stringResource(R.string.select_all),
                shape = RoundedCornerShape(50),
                textStyle = MaterialTheme.typography.labelLarge,
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                modifier = Modifier.weight(1f),
                onClick = { selected = chapters.indices.toSet() }
            )
            Spacer(modifier = Modifier.width(8.dp))
            InfoChip(
                text = stringResource(R.string.select_uncached),
                shape = RoundedCornerShape(50),
                textStyle = MaterialTheme.typography.labelLarge,
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                modifier = Modifier.weight(1f),
                onClick = { selected = uncachedIndices }
            )
            Spacer(modifier = Modifier.width(8.dp))
            InfoChip(
                text = stringResource(R.string.clear_selection),
                shape = RoundedCornerShape(50),
                textStyle = MaterialTheme.typography.labelLarge,
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                modifier = Modifier.weight(1f),
                onClick = { selected = emptySet() }
            )
            Spacer(modifier = Modifier.width(12.dp))
            InfoChip(
                text = stringResource(R.string.download_selected_format, selected.size),
                shape = RoundedCornerShape(50),
                containerColor = if (selected.isEmpty()) MaterialTheme.colorScheme.surfaceVariant
                else MaterialTheme.colorScheme.secondaryContainer,
                contentColor = if (selected.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.onSecondaryContainer,
                textStyle = MaterialTheme.typography.labelMedium,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
        // 章节列表：平铺整列（与阅读器目录同构），LazyColumn 虚拟化承载几千章；
        // 行业常规不对长目录做任意数值分组折叠——批量勾选交给顶部快捷选择，
        // 打开时滚动到焦点章附近（见 listState 的 LaunchedEffect）
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentPadding = PaddingValues(vertical = 6.dp)
        ) {
            items(
                count = chapters.size,
                key = { it },
                contentType = { "chapter" }
            ) { index ->
                DownloadChapterRow(
                    index = index,
                    name = chapters[index].durChapterName,
                    isChecked = index in selected,
                    status = statusList[index],
                ) {
                    selected = if (index in selected) selected - index else selected + index
                }
            }
        }
        // 跳过统计 + 确认按钮
        Column(modifier = Modifier.padding(CommonUiTokens.pagePadding)) {
            if (skippedQueued > 0) {
                Text(
                    text = stringResource(R.string.download_skip_queued_format, skippedQueued),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(6.dp))
            }
            Button(
                onClick = {
                    if (exceedsSelectionCap(downloadCount)) capConfirmVisible = true
                    else onConfirm(selected)
                },
                enabled = confirmEnabled,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
            ) {
                Text(stringResource(R.string.download_short_format, downloadCount))
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
    }

    // 超限确认：点确认后才真正下发；点取消只关弹窗，选择集合原样保留
    if (capConfirmVisible) {
        AlertDialog(
            onDismissRequest = { capConfirmVisible = false },
            text = {
                Text(stringResource(R.string.download_bulk_confirm_message, downloadCount))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        capConfirmVisible = false
                        onConfirm(selected)
                    }
                ) {
                    Text(stringResource(R.string.download_bulk_confirm_continue))
                }
            },
            dismissButton = {
                TextButton(onClick = { capConfirmVisible = false }) {
                    Text(stringResource(com.ebook.common.R.string.cancel))
                }
            }
        )
    }
}

/**
 * 下载行：勾选框 + 序号 + 章名 + 状态标签。
 *
 * 整行可点切换勾选；Checkbox 的 onCheckedChange 置 null（语义由整行点击统一控制）。
 * 状态标签只展示（下载中/待下载/已缓存），不参与勾选决策。
 */
@Composable
private fun DownloadChapterRow(
    index: Int,
    name: String,
    isChecked: Boolean,
    status: ChapterDownloadStatus,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (isChecked) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent
            )
            .clickable(onClick = onToggle)
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = isChecked, onCheckedChange = null)
        Text(
            text = stringResource(R.string.chapter_number, index + 1),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = name,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(modifier = Modifier.width(8.dp))
        val chipText = when (status) {
            ChapterDownloadStatus.DOWNLOADING -> R.string.download_manage_active_tag
            ChapterDownloadStatus.QUEUED -> R.string.download_manage_queued_tag
            ChapterDownloadStatus.CACHED -> R.string.cached_badge
            ChapterDownloadStatus.NOT_DOWNLOADED -> null
        }
        if (chipText != null) {
            InfoChip(
                text = stringResource(chipText),
                shape = RoundedCornerShape(50),
                containerColor = if (status == ChapterDownloadStatus.DOWNLOADING) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
                contentColor = if (status == ChapterDownloadStatus.DOWNLOADING) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                textStyle = MaterialTheme.typography.labelSmall,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 3.dp)
            )
        }
    }
}
