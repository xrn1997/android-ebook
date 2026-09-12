package com.ebook.book.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ebook.book.R
import com.ebook.common.ui.CommonUiTokens
import com.ebook.common.ui.InfoChip
import com.ebook.db.entity.ChapterListEntity

// 以下三个可组合函数自 ReaderPanels.kt 原样搬入，行为不变（本文件是冻结基线，
// 后续改造请让 diff 只体现逻辑变更）
/**
 * 章节多选下载面板（替代原 DownloadRangeDialog 的起止章号输入框）。
 *
 * 缓存感知：逐章按 [cachedIndices]（以章文件存在性为事实源，调用方经
 * BookRepository.getCachedChapterIndices 查询）绘制"已缓存"徽章；默认预勾选集合由调用方传入。
 * 已缓存章节勾上即重下：下发任务统一带 forceRefresh 标记（服务端先删旧内容再重抓），
 * 故不再区分"下载"与"强制刷新缓存"两种模式（对未缓存章节该标记为空操作），
 * 刷新缓存的能力已合并进本面板。
 *
 * 视觉：快捷选择由三枚 OutlinedButton 改为等宽胶囊（弱化边框噪声、并排更整齐）；
 * 标题右侧新增"已选 N 章"计数胶囊——列表限半屏，滚动后确认按钮文案会脱离视野，
 * 需要一个常驻的选择反馈；行选中态加底色，勾选结果不再只依赖 20dp 的小方框。
 * 配色走 MaterialTheme 语义色（阅读器浅色作用域内自动解析），字号走 Material typography。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChapterDownloadSheet(
    chapters: List<ChapterListEntity>,
    cachedIndices: Set<Int>,
    initialSelected: Set<Int>,
    onConfirm: (selected: Set<Int>) -> Unit,
    onDismiss: () -> Unit
) {
    var selected by remember { mutableStateOf(initialSelected) }

    // 未缓存索引集：列表打开期间不变，remember 避免每次勾选重算
    val uncachedIndices = remember(chapters, cachedIndices) {
        chapters.indices.filterTo(mutableSetOf()) { it !in cachedIndices }
    }

    // 跳过数 = 已缓存但未勾选的章节（本次不会下发任务）；确认文案实时反映选择结果
    val skippedCached = (cachedIndices - selected).size
    val confirmText = stringResource(R.string.download_skip_cached_format, selected.size, skippedCached)

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = CommonUiTokens.pagePadding)
                .navigationBarsPadding()
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.offline_download),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = stringResource(
                            R.string.cached_count_format,
                            chapters.size,
                            cachedIndices.size
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                // 选择计数：有选择时用 secondaryContainer 提亮，空选择保持弱化底色
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
            Spacer(modifier = Modifier.height(14.dp))
            // 快捷选择：全选 / 仅未缓存 / 清除（已缓存章节的"重下/刷新"靠全选或逐行勾选）
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            // 章节列表：高度限半屏，避免 ModalBottomSheet 被超长目录无限撑开（大目录快速滚动可加，
            // 本面板以选择为目的、逐行可视更重要，不引入 FastScroll）
            val listHeight = with(LocalDensity.current) {
                LocalWindowInfo.current.containerSize.height.toDp() / 2
            }
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(listHeight),
                contentPadding = PaddingValues(vertical = 6.dp)
            ) {
                itemsIndexed(chapters, key = { index, _ -> index }) { index, chapter ->
                    DownloadChapterRow(
                        index = index,
                        name = chapter.durChapterName,
                        isChecked = index in selected,
                        isCached = index in cachedIndices,
                    ) {
                        selected = if (index in selected) selected - index else selected + index
                    }
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            // 确认：选中集为空时禁用，避免下发空任务拉起前台服务空转
            Button(
                onClick = { onConfirm(selected) },
                enabled = selected.isNotEmpty(),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
            ) {
                Text(confirmText)
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

/**
 * 快捷选择胶囊：surfaceVariant 底 + 居中文案。
 *
 * 比 OutlinedButton 少一层描边噪声，三枚等宽并排时更整齐，且点击目标铺满整枚胶囊。
 */
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
 * 下载面板章节行：勾选框 + 序号 + 章名 + 已缓存徽章。
 *
 * 整行可点切换勾选；Checkbox 自身 onCheckedChange 置 null 避免双重触发，
 * 勾选态仅作展示（语义由整行点击统一控制）；选中时整行加底色，
 * 长列表里逐行的小勾难以扫读，底色让"已选范围"一眼可见。
 */
@Composable
private fun DownloadChapterRow(
    index: Int,
    name: String,
    isChecked: Boolean,
    isCached: Boolean,
    onToggle: () -> Unit
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
        if (isCached) {
            // 已缓存徽章：复用共享 InfoChip（语义色小标签），与全书其它标签同语言
            InfoChip(
                text = stringResource(R.string.cached_badge),
                shape = RoundedCornerShape(50),
                textStyle = MaterialTheme.typography.labelSmall,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 3.dp)
            )
        }
    }
}
