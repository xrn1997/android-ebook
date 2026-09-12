package com.ebook.book.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Deselect
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Numbers
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ebook.book.R
import com.ebook.book.mvvm.viewmodel.BookChapterSelection
import com.ebook.book.mvvm.viewmodel.BookSelectionState
import com.ebook.common.ui.BookCover
import com.ebook.common.ui.CommonUiTokens
import com.ebook.common.ui.InfoChip
import kotlinx.coroutines.launch

/** 右下操作 FAB 的边长（与 Material3 FAB 默认尺寸一致，列表底衬与快速滚动条据此避让）。 */
private val FabSize = 56.dp

/** FAB 距左下/右下的留白（与页面横向留白同源，手感统一）。 */
private val FabEdgePadding = 16.dp

/** 面板与 FAB 之间的空隙。 */
private val PanelFabGap = 12.dp

/** 面板底边距列表底边的距离：FAB 边长 + 上下留白 + 间隙。 */
private val PanelBottomOffset = FabEdgePadding + FabSize + PanelFabGap

/**
 * 右下控件区的固定让出高度：面板下缘偏移 + 一道余白，**不含面板自身高度**。
 *
 * 面板多高只有量出来才知道（「本书」组显隐、底部预演小字都会改变它），故列表末章的让位量
 * 在此之上再叠实测的 panelHeight（见 BookChapterSelectContent 的 contentPadding）。
 */
private val BottomOverlayReserve = PanelBottomOffset + FabEdgePadding

/**
 * 快速滚动条固定让出的高度：只避常驻的 FAB。
 *
 * 面板那一段是动态的——它曾只在按下主操作后瞬时出现，为它长期截短轨道反而显眼；如今面板
 * 不再自动收起，于是展开期间按实测高度截短，收起时轨道恢复原长（面板本身也随即不见，不留盲区）。
 */
private val FabZoneHeight = FabEdgePadding + FabSize + 8.dp

/** 面板占屏宽比例：留出左缘一道缝，与右缘留白一起把「浮在列表上」这层关系说清楚。 */
private const val PanelWidthFraction = 0.86f

/**
 * 勾中行主色薄纱的浓度。
 *
 * 取 8% 是因为这页的勾中量以百、千章计（全选/范围是主路径）：中性底色浓一档只是「不明显」，
 * 主色浓一档会整屏并成一块实心色，反而看不出边界。薄纱只负责「这片被刷过了」的暗示，
 * 判「这一行选没选」靠的是左缘指示条与勾选框。
 */
private const val SelectionTintAlpha = 0.08f

/**
 * 勾中行左缘指示条的宽度：选中态的主要抓手。
 *
 * 它占满整行高度且不被圆角裁剪，因此上下相邻的勾中行会在列表左缘并成一条不间断的竖带——
 * 「从第 X 章到第 Y 章都选了」这件事由形状直接说出来，不必逐行数勾选框。
 */
private val SelectionRailWidth = 3.dp

/**
 * 书头操作列的宽度：按「取消本书 / 暂停本书 / 继续下载」四个汉字定宽。
 *
 * 不包内容：主操作的文字随三态换，包内容等于让按钮宽度跟着状态变——那正是刚从面板里
 * 撤掉的「落点漂移」，只是从横向搬到了纵向。
 */
private val HeaderActionWidth = 76.dp

/** 两枚按钮各 30dp + 4dp 间距 = 64dp，正好落在封面（48x64）那条高度带里，书头不因操作列而变高。 */
private val HeaderActionHeight = 30.dp
private val HeaderActionGap = 4.dp

/**
 * 下载中心二级：某书全章节的「状态 + 选章」整屏页。
 *
 * 两个入口：一级点书行进入（返回回一级）、阅读器直达（返回直接退出回阅读器，
 * 由活动层按直达态分流，见 DownloadCenterScreen 的 BackHandler）。
 *
 * 顶层负责四态渲染（加载中/书不在架/失败/就绪）：加载中是转圈而非文字（「正在加载」
 * 的一句话没有进度反馈，spinner 是通用语言）；不在架/失败带弱化图标 + 文案，失败
 * 另给「重试」按钮——只写「请重试」却不给按钮，等于让用户找不到重试的落点。
 * 内容复用原选章页的章节三态/软上限纯逻辑（ChapterSelection.kt）。
 * 顶部导航由宿主基类 Toolbar（标题 + 返回箭头）承担，本页**不自绘返回按钮**，
 * 避免与基类 Toolbar 出现双返回入口；系统返回/工具栏箭头均经活动层 BackHandler
 * 在 [DownloadCenterStep] 间切换。
 */
@Composable
fun BookChapterSelectPage(
    state: BookSelectionState,
    onConfirm: (Set<Int>) -> Unit,
    onPauseBook: (BookChapterSelection) -> Unit,
    onResumeBook: (BookChapterSelection) -> Unit,
    onCancelBook: (BookChapterSelection) -> Unit,
    onRetry: () -> Unit,
) {
    when (state) {
        is BookSelectionState.Loading -> CenteredState(text = stringResource(R.string.download_center_loading))
        is BookSelectionState.Absent -> CenteredState(
            text = stringResource(R.string.download_center_not_on_shelf),
            icon = Icons.Outlined.SearchOff,
        )
        is BookSelectionState.Failed -> CenteredState(
            text = stringResource(R.string.download_center_load_failed),
            icon = Icons.Outlined.ErrorOutline,
            onRetry = onRetry,
        )
        is BookSelectionState.Ready -> BookChapterSelectContent(
            selection = state.selection,
            onConfirm = onConfirm,
            onPauseBook = { onPauseBook(state.selection) },
            onResumeBook = { onResumeBook(state.selection) },
            onCancelBook = { onCancelBook(state.selection) },
        )
    }
}

/**
 * 二级页居中占位（加载中/书不在架/失败共用骨架：可选图标 + 文案 + 可选重试）。
 *
 * 图标走 outlined 系 + 弱化透明度：占位是「暂时的、次要的」，不能比内容更抢眼。
 */
@Composable
private fun CenteredState(
    text: String,
    icon: ImageVector? = null,
    onRetry: (() -> Unit)? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.size(40.dp)
            )
            Spacer(modifier = Modifier.height(12.dp))
        }
        if (icon == null) {
            // 无图标的加载态用 spinner 表达「进行中」，比静默文字多一层时间感
            CircularProgressIndicator(
                strokeWidth = 3.dp,
                modifier = Modifier.size(32.dp)
            )
            Spacer(modifier = Modifier.height(16.dp))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (onRetry != null) {
            Spacer(modifier = Modifier.height(8.dp))
            TextButton(onClick = onRetry) {
                Text(stringResource(R.string.retry))
            }
        }
    }
}

/**
 * 下载中心二级：某书全章节的「状态 + 选章」整屏内容。
 *
 * 每章状态来自 [chapterDownloadStatus] 的三方合并（已缓存/待下载/下载中），**只作展示**：
 * 勾选语义保持不变——勾中任何章（含已缓存）都按 forceRefresh 重下。
 * 确认时剔除已在队列中的章（见 DownloadManageViewModel.confirmDownload），
 * 按当前勾选预演的跳过数写在书头胶囊行下面（挨着它所指的那枚主操作）。
 * 章节三态/软上限等语义沿用 ChapterSelection.kt。
 *
 * 版面自上而下：书头（封面 48x64 + 书名 + 「共 N 章 · 已选 M 章 · 下载中|排队中|已暂停」胶囊行
 * + 右缘上下两枚书级动作）→ 章节平铺列表（带右缘快速滚动条）→ 右下悬停的勾选面板（主 FAB 展开）。
 * 书级动作在书头、勾选工具在浮窗这条分界见 [BookHeaderActions] 与 [DownloadActionPanel]；
 * 本页**不留常驻底栏**，列表通到底（仅让出右下控件区的高度），长目录少一行固定占位。
 */
@Composable
private fun BookChapterSelectContent(
    selection: BookChapterSelection,
    onConfirm: (Set<Int>) -> Unit,
    onPauseBook: () -> Unit,
    onResumeBook: () -> Unit,
    onCancelBook: () -> Unit,
) {
    var selected by remember { mutableStateOf(selection.initialSelected) }
    // 软上限二次确认：超过 500 章时不直接下发（见 exceedsSelectionCap）
    var capConfirmVisible by remember { mutableStateOf(false) }
    // 操作面板展开态：与「按书暂停」等状态刷新解耦，刷新后保持用户当前的展开选择
    var panelExpanded by remember { mutableStateOf(false) }
    // 面板实测高度：面板收起时离开组合树、不再回调，所以这里是「最近一次展开时量到的」高度，
    // 列表末端与滚动条轨道据此让位。量而不写死：面板只剩一行四枚，但标签长短与系统字号
    // 仍会把这一行撑高（字号放大时四枚标签会折行），写死一个数就会在那种机型上盖住末章。
    var panelHeight by remember { mutableStateOf(0.dp) }
    val density = LocalDensity.current
    var rangeDialogVisible by remember { mutableStateOf(false) }

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

    // 未缓存且未排队索引集：「仅未缓存」工具的选择输入（队列中的章预勾只会被确认时跳过）
    val uncachedIndices = remember(chapters, selection.cachedIndices, queuedIndices) {
        chapters.indices.filterTo(mutableSetOf()) { i ->
            i !in selection.cachedIndices && i !in queuedIndices
        }
    }

    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    // 平铺列表：打开时滚动到焦点章（阅读器当前章）附近；批量勾选由操作面板的
    // 「勾选」组（全选/仅未缓存/范围/清除）承担（见 ADR-0034 修订说明）。
    LaunchedEffect(Unit) {
        val focus = selection.focusChapter
        if (focus in chapters.indices) listState.scrollToItem(focus)
    }

    // 有效下发数（剔除已排队）与跳过统计，供主操作旁的预演小字
    val effectiveCount = (selected - queuedIndices).size
    val skippedCount = (selected intersect queuedIndices).size
    // 书级动作收在书头那一枚上（判定与置灰规则见 ChapterSelection.kt 的三态纯逻辑，JVM 单测锁住）
    val hasQueue = queuedIndices.isNotEmpty()
    val bookAction = bookDownloadActionOf(
        hasQueue = hasQueue,
        paused = selection.paused,
        effectiveCount = effectiveCount,
    )
    // 预演只回答「按下去会发生什么」：有可下发的量才有跳过可言（勾中的全在队列时换那一句），
    // 两种情况各说各的，不并列
    val actionHint = when {
        effectiveCount > 0 && skippedCount > 0 ->
            stringResource(R.string.download_skip_queued_format, skippedCount)
        effectiveCount == 0 && selected.isNotEmpty() ->
            stringResource(R.string.download_all_queued_hint)
        else -> null
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // 书头：封面 + 书名 + 状态胶囊行（本书长期事实 + 本次勾选 + 进行态）+ 右侧书级动作列。
        // 顶部返回由基类 Toolbar 承担，本页不再自绘返回箭头（避免双返回入口）。
        // 动作列贴在状态胶囊右边（见 [BookHeaderActions]）：胶囊说「这本书现在怎样」，
        // 开关就在它旁边，不必让用户在页头与右下角浮窗之间来回抬眼。
        // 封面尺寸与一级书行同规格（48x64）：同一本书在两级页面间的身份锚点一致。
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = CommonUiTokens.pagePadding, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BookCover(
                url = selection.coverUrl,
                modifier = Modifier.size(width = 48.dp, height = 64.dp),
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
                Spacer(modifier = Modifier.height(6.dp))
                // 状态胶囊行：总章数（长期事实）+ 已选数（本次勾选，随点随变）+ 进行态
                //（下载中 / 已暂停二者互斥——暂停书不可能同时是活跃书）。
                // 「已选 M 章」是本次勾选的唯一计数位：原「下载 X 章」按钮文案随底栏一并撤销。
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    InfoChip(
                        text = stringResource(R.string.chapter_count_format, chapters.size),
                        shape = RoundedCornerShape(50),
                        textStyle = MaterialTheme.typography.labelSmall,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 3.dp)
                    )
                    if (selected.isNotEmpty()) {
                        InfoChip(
                            text = stringResource(R.string.download_selected_format, selected.size),
                            shape = RoundedCornerShape(50),
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                            textStyle = MaterialTheme.typography.labelSmall,
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 3.dp)
                        )
                    }
                    if (selection.paused) {
                        InfoChip(
                            text = stringResource(R.string.download_manage_paused_tag),
                            shape = RoundedCornerShape(50),
                            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                            textStyle = MaterialTheme.typography.labelSmall,
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 3.dp)
                        )
                    } else if (selection.activeChapterIndex != null) {
                        InfoChip(
                            text = stringResource(R.string.download_manage_active_tag),
                            shape = RoundedCornerShape(50),
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            textStyle = MaterialTheme.typography.labelSmall,
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 3.dp)
                        )
                    } else if (hasQueue) {
                        // 任务已入库、服务还没轮到它（冷启动续跑、或前台服务被系统挡下）：
                        // 原先这一格什么都不画，于是右边那枚明明给着「暂停」，头上却说不出这本书在干什么
                        InfoChip(
                            text = stringResource(R.string.download_manage_queued_tag),
                            shape = RoundedCornerShape(50),
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            textStyle = MaterialTheme.typography.labelSmall,
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 3.dp)
                        )
                    }
                }
                // 预演小字跟着主操作放：它是「按下右边那枚会发生什么」的答案，
                // 留在右下角面板里就只有在面板展开时才看得见，而面板收起时那枚按钮照样能点
                actionHint?.let {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Spacer(modifier = Modifier.width(10.dp))
            BookHeaderActions(
                action = bookAction,
                enabled = bookAction.isEnabledWith(effectiveCount),
                cancelEnabled = hasQueue,
                onAction = {
                    when (bookAction) {
                        // 超软上限只多一道确认，不改变下发口径
                        BookDownloadAction.Download ->
                            if (exceedsSelectionCap(effectiveCount)) capConfirmVisible = true
                            else onConfirm(selected)
                        BookDownloadAction.Pause -> onPauseBook()
                        BookDownloadAction.Resume -> onResumeBook()
                    }
                },
                // 取消走活动层的二次确认对话框；确认后回一级，本页随之消失
                onCancelBook = onCancelBook,
            )
        }
        // 列表区：navigationBarsPadding 让整块内容（含 FAB/面板/滚动条）避开手势条——
        // 基类 Scaffold 对带工具栏页面不注入 insets，页面自行兜住
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .navigationBarsPadding()
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxWidth(),
                // 底部让出右下控件区 + 面板实高：末章能滚到按钮与**常驻面板**上方。
                // 面板过去只在按下主操作后自动收起，让位量按「只有 FAB 常驻」算就够；
                // 现在它不再自动收起，不把 panelHeight 算进来，读到书末的章会永久压在面板底下。
                contentPadding = PaddingValues(
                    start = 12.dp,
                    top = 4.dp,
                    end = 12.dp,
                    bottom = BottomOverlayReserve + panelHeight
                )
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
            // 快速滚动条：几千章的书靠拇指拖动定位（与阅读器目录同一组件）。
            // 底部先让开常驻 FAB；面板展开时再让开面板实高——面板不透明，轨道下段整截
            // 落在它下面，滑块一拖进去就看不见。轨道宁可短一截，也不留一段"摸得到却看不见"的行程
            ReaderFastScroll(
                listState = listState,
                itemCount = chapters.size,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .padding(bottom = FabZoneHeight + if (panelExpanded) panelHeight else 0.dp)
            )
            // 操作面板：浮在列表上、锚在 FAB 正上方，只管「选哪些章」这一件事。无遮罩——展开时
            // 列表仍可点选，「已选 M 章」与行内状态随操作实时刷新（见 [DownloadActionPanel]）
            OverlayVisibility(
                visible = panelExpanded,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = FabEdgePadding, bottom = PanelBottomOffset)
                    .fillMaxWidth(PanelWidthFraction)
            ) {
                DownloadActionPanel(
                    modifier = Modifier.onSizeChanged {
                        panelHeight = with(density) { it.height.toDp() }
                    },
                    onSelectAll = { selected = chapters.indices.toSet() },
                    onSelectUncached = { selected = uncachedIndices },
                    onOpenRange = { rangeDialogVisible = true },
                    onClearSelection = { selected = emptySet() },
                )
            }
            FloatingActionButton(
                onClick = { panelExpanded = !panelExpanded },
                shape = CircleShape,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(FabEdgePadding)
                    .size(FabSize)
            ) {
                Icon(
                    imageVector = if (panelExpanded) Icons.Outlined.Close else Icons.Outlined.Tune,
                    contentDescription = stringResource(
                        if (panelExpanded) R.string.download_manage_panel_collapse
                        else R.string.download_manage_panel_expand
                    ),
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }

    // 超限确认：点确认后才真正下发；点取消只关弹窗，选择集合原样保留
    if (capConfirmVisible) {
        AlertDialog(
            onDismissRequest = { capConfirmVisible = false },
            text = {
                Text(stringResource(R.string.download_bulk_confirm_message, effectiveCount))
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

    // 范围选章：确定后**替换**当前勾选并滚到范围起点，让用户看清将下什么、可再微调
    //（不直接下发，见 ADR-0036 决策 6）；超 500 章仍走既有的软上限二次确认
    if (rangeDialogVisible) {
        ChapterRangeDialog(
            total = chapters.size,
            initialRange = defaultRangeSelection(chapters.size, selected, selection.focusChapter),
            onDismiss = { rangeDialogVisible = false },
            onConfirm = { range ->
                rangeDialogVisible = false
                selected = range.toSet()
                scope.launch { listState.animateScrollToItem(range.first) }
            },
        )
    }
}

/**
 * 右下主 FAB 展开的**勾选**面板：四枚筛选工具（全选 / 仅未缓存 / 范围 / 清除），图标圆钮 + 下标签。
 *
 * 面板只管「要下哪些章」。书维度的动作（取消本书，以及下载/暂停/继续那一枚）2026-09-12 起
 * 搬到书头状态胶囊的右边（见 [BookHeaderActions]）——它们与勾选无关，而且原先「本书」组的
 * 枚数随状态在 1~3 之间变，`SpaceEvenly` 是按个数重新分配间隙的：每一枚的横向落点都跟着漂移，
 * 最坏的一格是上一帧「下载」所在的坐标，下一帧正好落上破坏性的「取消本书」。搬走之后这里
 * 只剩固定四枚，位置从此能靠记。
 *
 * 四枚图标语义互不重叠（曾与已搬走的主操作撞形两次：「仅未缓存」画下载箭头＝把「选哪些」
 * 说成「开始下」，「清除」画垃圾桶＝把「清空勾选」说成「取消整本下载」，两者代价不对等，故各换一枚）。
 * 中间两枚另外要避开「设置项」观感：「仅未缓存」画带斜杠的云（云端那份还没落到本地＝
 * 待补的那些章），不画筛选漏斗——漏斗在本页其它地方是排序/筛选的意思，而这枚给出的是一批勾选；
 * 「范围」画 123（它打开的是起止**章号**输入框），不画滑块刻度——那张形更像「调节一个值」的条。
 *
 * **收起只由 FAB 上的 X 承担**：面板内任何操作执行后都不自动收起——它是用户自己展开的
 * 控制台，抢走它等于让下一批选择重新展开一次。代价是它长期占住右下这块列表区，所以列表末端的
 * 让位高度与滚动条轨道长度都要按它的**实测**高度算（见调用方）。
 */
@Composable
private fun DownloadActionPanel(
    modifier: Modifier = Modifier,
    onSelectAll: () -> Unit,
    onSelectUncached: () -> Unit,
    onOpenRange: () -> Unit,
    onClearSelection: () -> Unit,
) {
    // 与列表分层靠三件事叠加：底色推到中性档最高一级、一圈 outlineVariant 描边、16dp 投影。
    // 原先面板与勾中行同为 surfaceContainerHigh，浮在一片同色选中块上只靠投影区分——那是
    // 「控制界面不明显」的直接根因。现在选中态带主色薄纱、色相已错开，但浮层边界仍要描边兜底：
    // 浅色主题下投影会被接近白底的列表吃掉，勾中薄纱铺到面板边缘时更是只剩描边还在说话。
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(CommonUiTokens.cardCorner),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shadowElevation = 16.dp
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            PanelSectionLabel(stringResource(R.string.download_manage_group_select))
            // 勾选组四枚等距铺满：数量固定，位置不随状态变，用户能靠位置记动作
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                PanelToolButton(
                    icon = Icons.Outlined.SelectAll,
                    label = stringResource(R.string.select_all),
                    onClick = onSelectAll,
                )
                PanelToolButton(
                    icon = Icons.Outlined.CloudOff,
                    label = stringResource(R.string.select_uncached),
                    onClick = onSelectUncached,
                )
                PanelToolButton(
                    icon = Icons.Outlined.Numbers,
                    label = stringResource(R.string.download_manage_range),
                    onClick = onOpenRange,
                )
                PanelToolButton(
                    icon = Icons.Outlined.Deselect,
                    label = stringResource(R.string.clear_selection),
                    onClick = onClearSelection,
                )
            }
        }
    }
}

/** 面板分组小标题（现只剩「勾选」一组），只做分组不加装饰。 */
@Composable
private fun PanelSectionLabel(text: String) {
    Text(
        text = text,
        modifier = Modifier.padding(start = 4.dp),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

/**
 * 面板显隐容器（淡入淡出）。
 *
 * 单独立一个 composable 是因为 `AnimatedVisibility` 在 `Column` 的子 `Box` 里直接调用时，
 * 会被 ColumnScope 的同名扩展抢走隐式接收者（Kotlin 2 的 DSL 作用域规则直接报错），
 * 而那个重载是「往 Column 里排布」的，拿不到 Box 的对齐能力——本函数体里没有任何隐式
 * 布局作用域，调用落在顶层 `AnimatedVisibility` 上，对齐由外部传进来的 modifier 承担。
 */
@Composable
private fun OverlayVisibility(
    visible: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn(),
        exit = fadeOut(),
    ) {
        content()
    }
}

/**
 * 面板操作钮：44dp 圆位 + 下标签，四枚一律平铺。
 *
 * 44dp 触达尺寸低于 Material 建议的 48dp，但整列（圆位 + 标签）才是可点区域、
 * 且标签在下、按钮在上，实际命中区比圆位更大；换来的是四枚工具能在一行内等距排开。
 *
 * 面板只剩四枚同级筛选工具，因此不再有「强调档」一说：一组里只给其中一枚铺底会被读成
 * 「这一项已激活」的状态指示，而不是"更值得点的动作"。原先独占实底主色的那枚是书级主操作，
 * 它已随其余动作搬到书头（见 [BookHeaderActions]）。
 */
@Composable
private fun PanelToolButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier.clickable(role = Role.Button, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Box(
            modifier = Modifier.size(44.dp),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            softWrap = false
        )
    }
}

/**
 * 书头右侧的书级动作列：上「取消本书」、下那一枚三态主操作，上下堆叠、各钉一处。
 *
 * 为什么挂在书头而不是右下角面板里：面板管「选哪些章」，这两件事管「这本书的任务」，
 * 维度不同；而进行态胶囊（下载中/排队中/已暂停）本来就在书头，开关跟着状态走，
 * 一眼读下来是同一句话。搬走的另一半收益是**坐标恒定**——旧「本书」组随状态在 1~3 枚之间
 * 增减，`SpaceEvenly` 每次重新分配间隙，实测主操作落点在 155/215/244 之间跳，且 1↔3 枚
 * 变化时破坏性的「取消本书」正好落在上一帧「下载」所在的坐标上（手没挪就按错）。
 *
 * 两枚都定宽定高（[HeaderActionWidth] / [HeaderActionHeight]）：主操作文案随三态换
 * （下载 / 暂停本书 / 继续下载），包内容会让宽度跟着状态变，等于把漂移从横向搬到纵向。
 * 「取消本书」无任务时**置灰而非隐藏**：这一列位置恒定比"少一枚灰钮"更值钱——
 * 隐藏会让下面那枚整列上移，而它正是最常按的那枚。
 */
@Composable
private fun BookHeaderActions(
    action: BookDownloadAction,
    enabled: Boolean,
    cancelEnabled: Boolean,
    onAction: () -> Unit,
    onCancelBook: () -> Unit,
) {
    Column(
        modifier = Modifier.width(HeaderActionWidth),
        verticalArrangement = Arrangement.spacedBy(HeaderActionGap)
    ) {
        HeaderActionButton(
            text = stringResource(R.string.download_manage_cancel_book),
            enabled = cancelEnabled,
            filled = false,
            onClick = onCancelBook,
        )
        HeaderActionButton(
            text = stringResource(
                when (action) {
                    BookDownloadAction.Download -> R.string.download_manage_download
                    BookDownloadAction.Pause -> R.string.download_manage_pause_book
                    BookDownloadAction.Resume -> R.string.download_manage_resume_book
                }
            ),
            enabled = enabled,
            filled = true,
            onClick = onAction,
        )
    }
}

/**
 * 书头动作钮：定宽小圆角钮，实底主色（主操作）或描边 + error 文字（破坏性动作）。
 *
 * 不用 Material 的 Button：它自带 40dp 最小高度，而书头这条带只有封面那么高（64dp）、
 * 要塞两枚。这里自己画底，语义色照旧（primary/onPrimary、error、置灰走 onSurface 两档
 * 透明度），与面板工具钮同一套配色语言。破坏性动作不铺底：它自带二次确认，
 * 不需要用色块预先吓人。
 */
@Composable
private fun HeaderActionButton(
    text: String,
    enabled: Boolean,
    filled: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(CommonUiTokens.cardCornerSmall)
    val container = when {
        !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
        filled -> MaterialTheme.colorScheme.primary
        else -> Color.Transparent
    }
    val content = when {
        !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        filled -> MaterialTheme.colorScheme.onPrimary
        else -> MaterialTheme.colorScheme.error
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(HeaderActionHeight)
            .clip(shape)
            .background(container)
            .then(
                // 描边只给不铺底的那枚，且置灰时一起退到浅档：灰底配一条实打实的边框看着仍像可点
                if (filled) Modifier
                else Modifier.border(
                    BorderStroke(
                        1.dp,
                        if (enabled) MaterialTheme.colorScheme.outlineVariant
                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
                    ),
                    shape
                )
            )
            .then(
                if (enabled) Modifier.clickable(role = Role.Button, onClick = onClick)
                else Modifier
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = content,
            fontWeight = if (filled && enabled) FontWeight.Medium else null,
            maxLines = 1,
            softWrap = false
        )
    }
}

/**
 * 范围选章对话框：起止章号（1-based，与列表行号同义）→ 闭区间索引。
 *
 * [initialRange] 由 [defaultRangeSelection] 给出（0-based 闭区间）：已有勾选＝勾选边界、
 * 无勾选＝当前章起 50 章、一级入口＝第 1 章起 50 章——进对话框永远不是两个空框，
 * 用户改的是「已经给出的范围」而不是从零开始打两个数。
 * 非法输入（空/非数字/越界/起>止）时**「确定」置灰 + 一行说明**：只置灰不解释
 * 等于让用户猜哪儿错了；只提示不置灰则会下发一个空区间（看着成功、勾选没变）。
 * 校验与换算由 [parseChapterRange] 承担（纯逻辑，JVM 单测锁定）。
 */
@Composable
private fun ChapterRangeDialog(
    total: Int,
    initialRange: IntRange?,
    onDismiss: () -> Unit,
    onConfirm: (IntRange) -> Unit,
) {
    // 每次展开都是新的一次输入：对话框离开组合树后 remember 随之丢弃，下次进来重取默认值
    var from by remember { mutableStateOf(initialRange?.first?.plus(1)?.toString().orEmpty()) }
    var to by remember { mutableStateOf(initialRange?.last?.plus(1)?.toString().orEmpty()) }
    val range = parseChapterRange(from, to, total)
    val hasInput = from.isNotBlank() || to.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.download_manage_range_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = from,
                        onValueChange = { from = it },
                        modifier = Modifier.weight(1f),
                        label = { Text(stringResource(R.string.download_manage_range_from)) },
                        placeholder = { Text("1") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                    OutlinedTextField(
                        value = to,
                        onValueChange = { to = it },
                        modifier = Modifier.weight(1f),
                        label = { Text(stringResource(R.string.download_manage_range_to)) },
                        placeholder = { Text(total.toString()) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                }
                if (hasInput && range == null) {
                    Text(
                        text = stringResource(R.string.download_manage_range_invalid, total),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { range?.let(onConfirm) },
                enabled = range != null
            ) {
                Text(stringResource(R.string.confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(com.ebook.common.R.string.cancel))
            }
        }
    )
}

/**
 * 下载行：勾选框 + 序号 + 章名 + 状态标签。
 *
 * 整行可点切换勾选；Checkbox 的 onCheckedChange 置 null（语义由整行点击统一控制）。
 * 序号固定列宽（对齐阅读器目录行），长短不一的章名左边界有基准线，长目录扫读不串行。
 * 选中态是「左缘主色指示条 + 主色薄纱 + 章名转主色」，不是整块不透明底色：全选动辄数千章，
 * 实心色块会把整屏糊成一片、还会与悬浮面板同色（见 ADR-0036 修订说明的配色口径）。
 * 指示条与薄纱的裁剪层只给勾中行建（未勾行背景透明，保留 clip 等于给每个可见行分配
 * 渲染层——数千章滚动时的纯浪费；代价是未勾行的水波纹从圆角变直角，可忽略）。
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
    // 选中态两档色在组合期取好：drawBehind 的 lambda 不是 composable 上下文，读不到
    // MaterialTheme.colorScheme；而 toPx() 必须留在 lambda 内——那是 DrawScope(实现 Density) 的能力。
    val railColor = MaterialTheme.colorScheme.primary
    val tintColor = MaterialTheme.colorScheme.primary.copy(alpha = SelectionTintAlpha)
    val rowModifier = Modifier
        .fillMaxWidth()
        .then(
            if (isChecked) {
                Modifier
                    // 指示条画在 clip **之前**：clip 只裁剪排在它后面的修饰符与子内容，
                    // 放后面就会被行尾圆角啃掉端头，连续勾选的竖带长成虚线（正是要避免的样子）。
                    .drawBehind {
                        drawRect(
                            color = railColor,
                            size = Size(SelectionRailWidth.toPx(), size.height),
                        )
                    }
                    // 只圆右半边：左缘与指示条齐平、上下不留缝，连续勾中行才会并成一整块
                    // 高亮带，而不是每行一枚小药片——后者铺满长列表正是「脏」的来源。
                    .clip(
                        RoundedCornerShape(
                            topEnd = CommonUiTokens.cardCornerSmall,
                            bottomEnd = CommonUiTokens.cardCornerSmall,
                        )
                    )
                    .background(tintColor)
            } else {
                Modifier
            }
        )
        .clickable(onClick = onToggle)
        .padding(horizontal = 8.dp, vertical = 2.dp)
    Row(
        modifier = rowModifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = isChecked, onCheckedChange = null)
        Text(
            text = stringResource(R.string.chapter_number, index + 1),
            modifier = Modifier.width(34.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = name,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = if (isChecked) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurface
            },
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
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 3.dp)
            )
        }
    }
}
