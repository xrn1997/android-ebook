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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ebook.book.R
import com.ebook.common.ui.CommonUiTokens
import com.ebook.common.ui.InfoChip
import com.ebook.db.entity.ChapterListEntity

// 本文件由 ReaderPanels.kt 搬迁而来：QuickSelectChip / DownloadChapterRow 仍是原样搬入的
// 冻结基线，ChapterDownloadSheet 已在此之上改为按百章分组（改造请让 diff 只含逻辑变更）
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
    focusIndex: Int,
    onConfirm: (selected: Set<Int>) -> Unit,
    onDismiss: () -> Unit
) {
    var selected by remember { mutableStateOf(initialSelected) }

    // 未缓存索引集：列表打开期间不变，remember 避免每次勾选重算
    val uncachedIndices = remember(chapters, cachedIndices) {
        chapters.indices.filterTo(mutableSetOf()) { it !in cachedIndices }
    }

    // 分组只随章节数变化（章节列表在面板存活期间不会变）
    val groups = remember(chapters.size) { chapterGroups(chapters.size) }

    // 默认只展开含当前章的那一组：其余折叠后 3000 章 = 30 行组头，翻几屏即可扫完全书范围
    // （组头行高约 48dp，30 行仍要滚动，但比在三千行里找快得多）。
    // 面板是"关闭即离开组合"的，这份状态每次打开都重建 → 每次进面板都回到当前章那组。
    // 组身份一律用 ChapterGroup.index（不是 groups 的列表下标）：两者数值目前相同但语义不同，
    // 且 expanded 会被 rowIndexOfGroup 当序号比较。
    val focusGroup = groups.firstOrNull { focusIndex in it.first..it.last }
    var expanded by remember {
        mutableStateOf(if (focusGroup != null) setOf(focusGroup.index) else emptySet())
    }

    // 打开即把当前章所在组的组头滚到列表顶部，确保用户在 UI 上直接看见它。
    // scrollToItem 在列表尚未完成首次测量时也能用（它会等到能滚动时再落位）。
    val listState = rememberLazyListState()
    LaunchedEffect(Unit) {
        if (focusGroup != null) {
            listState.scrollToItem(rowIndexOfGroup(groups, expanded, focusGroup.index))
        }
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
            // 章节列表：高度仍限半屏（ModalBottomSheet 不该被内容无限撑开），但导航面已从
            // 「章节数」降到「组数」——每 100 章一行组头，3000 章的书扫 30 行就能定位到区间，
            // 这也是本面板始终不需要快速滚动条的原因。
            val listHeight = with(LocalDensity.current) {
                LocalWindowInfo.current.containerSize.height.toDp() / 2
            }
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(listHeight),
                contentPadding = PaddingValues(vertical = 6.dp)
            ) {
                groups.forEach { group ->
                    // 组头与子项的 key 都取自"原始章序号"域内的稳定值，绝不能用显示位置：
                    // 展开/收起会让其后所有位置整体位移，拿位置当 key 等于每次操作全量重建。
                    // 两类 item 给不同 contentType，展开/收起时才能真正复用组合。
                    stickyHeader(key = "g${group.index}", contentType = "group") { _ ->
                        GroupHeaderRow(
                            rangeText = stringResource(
                                R.string.chapter_group_range_format,
                                group.first + 1,
                                group.last + 1
                            ),
                            cachedText = stringResource(
                                R.string.chapter_group_cached_format,
                                (group.first..group.last).count { it in cachedIndices },
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
                                isCached = index in cachedIndices,
                            ) {
                                selected = if (index in selected) selected - index else selected + index
                            }
                        }
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
 * 下载面板的组头行：三态勾选框 + 章范围 + 已缓存计数 + 展开箭头。
 *
 * 整行点击 = 展开/收起，勾选框独立响应：一次误触整行没有后果，但落到勾选框上就是一次选中
 * 100 章，两个热区必须分开（Checkbox 的 onClick 只接自己的点击，行点击另经 clickable）。
 *
 * 组头必须有**实心底色**：它是吸附头，滚动时下面的章行会从它背后经过，透明底会串字。
 *
 * 已缓存计数只是**信息**——本面板不拿"是否已缓存"做任何决策：缓存文件存在不等于内容正确
 * （缓存失败时也会落盘），故它既不参与勾选、也不参与上限判定。
 */
@Composable
private fun GroupHeaderRow(
    rangeText: String,
    cachedText: String,
    state: GroupState,
    expanded: Boolean,
    onToggleGroup: () -> Unit,
    onToggleExpand: () -> Unit,
) {
    // 不做圆角：这是吸附在列表顶部的表头，圆角会让四角透出下方滚过的章行文字
    // （透明角不是"设计留白"，是穿帮）。实心底色 + 满宽直角才是吸附头的正确形态。
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
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
        // 箭头是装饰：整行 clickable 会合并子语义，它的动作已由 onClickLabel 表达，
        // 再挂 contentDescription 只会把"展开/收起"念两遍
        Icon(
            imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.width(8.dp))
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
