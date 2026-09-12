package com.ebook.book.reader

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TriStateCheckbox
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
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ebook.book.R
import com.ebook.book.mvvm.viewmodel.BookChapterSelection
import com.ebook.common.ui.CommonUiTokens
import com.ebook.common.ui.InfoChip

/**
 * 下载中心二级：某书全章节的「状态 + 选章」整屏页（替代原 ModalBottomSheet 下载面板）。
 *
 * 每章状态来自 [chapterDownloadStatus] 的三方合并（已缓存/待下载/下载中），**只作展示**：
 * 勾选语义保持不变——勾中任何章（含已缓存）都按 forceRefresh 重下。
 * 确认时剔除已在队列中的章（见 DownloadManageViewModel.confirmDownload），跳过计数显示在
 * 按钮上方。分组/三态/软上限等语义沿用 ChapterSelection.kt。
 */
@Composable
fun BookChapterSelectPage(
    selection: BookChapterSelection,
    onConfirm: (Set<Int>) -> Unit,
    onBack: () -> Unit,
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

    val groups = remember(chapters.size) { chapterGroups(chapters.size) }
    // 默认只展开含焦点章（阅读器当前章）的那一组：其余折叠后 3000 章 = 30 行组头
    val focusChapter = selection.initialSelected.minOrNull() ?: -1
    val focusGroupIndex = if (focusChapter >= 0) {
        groups.indexOfFirst { focusChapter in it.first..it.last }
    } else -1
    var expanded by remember {
        mutableStateOf(if (focusGroupIndex >= 0) setOf(focusGroupIndex) else emptySet())
    }
    val listState = rememberLazyListState()
    LaunchedEffect(Unit) {
        if (focusGroupIndex >= 0) {
            listState.scrollToItem(rowIndexOfGroup(groups, expanded, focusGroupIndex))
        }
    }

    // 确认实际下发数（剔除已排队）与跳过统计
    val downloadCount = (selected - queuedIndices).size
    val skippedQueued = (selected intersect queuedIndices).size
    val confirmEnabled = downloadCount > 0

    Column(modifier = Modifier.fillMaxSize()) {
        // 页头：返回 + 书名 + 已选计数
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = CommonUiTokens.pagePadding, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = stringResource(R.string.download_center_back),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = selection.bookName,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(8.dp))
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
        // 快捷选择：全选 / 仅未缓存 / 清除
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = CommonUiTokens.pagePadding),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            QuickSelectChip(
                label = stringResource(R.string.select_all),
                modifier = Modifier.weight(1f),
            ) { selected = chapters.indices.toSet() }
            QuickSelectChip(
                label = stringResource(R.string.select_uncached),
                modifier = Modifier.weight(1f),
            ) { selected = uncachedIndices }
            QuickSelectChip(
                label = stringResource(R.string.clear_selection),
                modifier = Modifier.weight(1f),
            ) { selected = emptySet() }
        }
        Spacer(modifier = Modifier.height(12.dp))
        // 章节列表：整屏可用（替代原半屏 ModalBottomSheet），分组后导航面 = 组数
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentPadding = PaddingValues(vertical = 6.dp)
        ) {
            // 行结构是与 rowIndexOfGroup 的契约：每组恒占 1 行组头 + 展开时 count 行章行
            groups.forEach { group ->
                stickyHeader(key = "g${group.index}", contentType = "group") { _ ->
                    GroupHeaderRow(
                        rangeText = stringResource(
                            R.string.chapter_group_range_format,
                            group.first + 1,
                            group.last + 1
                        ),
                        cachedText = stringResource(
                            R.string.chapter_group_cached_format,
                            (group.first..group.last).count { it in selection.cachedIndices },
                            group.count
                        ),
                        state = groupState(group, selected),
                        expanded = group.index in expanded,
                        onToggleGroup = { selected = toggleGroup(group, selected) },
                        onToggleExpand = {
                            expanded = if (group.index in expanded) {
                                expanded - group.index
                            } else {
                                expanded + group.index
                            }
                        }
                    )
                }
                if (group.index in expanded) {
                    items(
                        count = group.count,
                        key = { offset -> "c${group.first + offset}" },
                        contentType = { "chapter" }
                    ) { offset ->
                        val index = group.first + offset
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

/** 组头行：三态勾选框 + 章范围 + 已缓存计数 + 展开箭头（吸附头需实心底色防串字）。 */
@Composable
private fun GroupHeaderRow(
    rangeText: String,
    cachedText: String,
    state: GroupState,
    expanded: Boolean,
    onToggleGroup: () -> Unit,
    onToggleExpand: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .clickable(
                onClickLabel = stringResource(
                    if (expanded) R.string.chapter_group_collapse
                    else R.string.chapter_group_expand
                ),
                onClick = onToggleExpand
            )
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TriStateCheckbox(
            state = when (state) {
                GroupState.ALL -> ToggleableState.On
                GroupState.PARTIAL -> ToggleableState.Indeterminate
                GroupState.NONE -> ToggleableState.Off
            },
            onClick = onToggleGroup
        )
        Text(
            text = rangeText,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = cachedText,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Icon(
            imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.width(8.dp))
    }
}

/** 快捷选择胶囊（同原下载面板）。 */
@Composable
private fun QuickSelectChip(
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
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