package com.ebook.book.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ebook.book.R
import com.ebook.book.download.displayPositionOf
import com.ebook.book.download.originalIndexAt
import com.ebook.common.ui.CommonUiTokens
import com.ebook.common.ui.InfoChip
import com.ebook.db.entity.ChapterListEntity
import kotlinx.coroutines.launch
import kotlin.math.min

/**
 * 章节目录抽屉（左侧滑入，对齐原 ChapterListView 侧滑面板）。
 *
 * 早期迁移版用底部 ModalBottomSheet 承载，长目录在半屏弹层内浏览体验差；
 * 改回左侧滑出：全高列表、不遮正文，符合阅读器目录浏览习惯。
 *
 * 视觉：右侧 28dp 大圆角 + `surfaceContainerLow` 底色（M3 侧栏语义），
 * 条目改圆角行卡——当前章节用 `secondaryContainer` 整行底色 + 序号 + 位置圆点，
 * 原先"仅加粗变色"的选中态在长目录里几乎扫不到，需要更强的落点提示。
 *
 * 打开时定位滚动到当前章节；点击章节跳转并关闭；标题栏提供关闭按钮
 * （原仅能点遮罩/返回键关闭，抽屉占屏八成时右上角点击不可靠）。
 * 长目录可通过右侧快速滚动条（[ReaderFastScroll]，替代原 RecyclerViewBar）定位。
 *
 * 倒序开关仅本次会话有效（不落盘）：状态声明在本函数顶层而非 AnimatedVisibility 内，
 * 故关抽屉不丢、退出阅读器自然回正序。打开行为固定且随顺序分化：正序打开定位当前章、
 * 倒序打开停在顶部（最新章节）；标题栏瞄准镜是一次性「回到当前章」按钮（无常驻状态）；
 * 切顺序保持列表进度百分比不变（不主动滚动，停在镜像位置的另一章）；章号一律按原始序显示。
 *
 * 注意：本抽屉为自绘覆盖层（非 ModalBottomSheet，无内置返回处置），
 * 返回键关闭由调用侧 BackHandler 收口。
 *
 * @param visible 是否展开（常驻组合，进出场动画由内部 AnimatedVisibility 驱动）
 */
@Composable
fun ChapterListDrawer(
    visible: Boolean,
    bookName: String,
    chapters: List<ChapterListEntity>,
    durChapter: Int,
    onChapterClick: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    // 倒序开关：**声明在 AnimatedVisibility 之外**——放进去每次关抽屉都会随内容一起被丢弃，
    // 用户来回开合会被打回正序。放这里则关抽屉不丢、退出阅读器（组合销毁）自然回正序，
    // 即"仅本次阅读会话有效"（不落盘）。
    var descending by remember { mutableStateOf(false) }
    val count = chapters.size
    // asReversed() 是 O(1) 的视图不拷贝，但必须 remember：每次重组都新建实例会让 LazyColumn
    // 的 item provider 每帧换身份
    val displayChapters = remember(chapters, descending) {
        if (descending) chapters.asReversed() else chapters
    }
    val listState = rememberLazyListState()
    // 打开行为固定、随顺序分化（不设用户开关，避免出现"开着的开关被切顺序悄悄关掉"的困惑）：
    // - 正序打开 → 定位到当前章（看"我在哪"）；
    // - 倒序打开 → 停在列表顶部（最新章节在顶，倒序的意义就是先看最新）。
    // 只由 visible 驱动，descending 读取时取现值；切顺序本身不滚动（进度百分比保持，
    // 见顺序胶囊 onClick），故不要把 descending 放进 key——翻转当刻不做任何重新定位。
    LaunchedEffect(visible) {
        if (visible && count > 0) {
            val target = if (descending) 0 else durChapter
            if (target in 0 until count) listState.scrollToItem(target)
        }
    }
    val scope = rememberCoroutineScope()

    // 抽屉宽度：窗口宽度八成、上限 320dp（对齐原侧滑面板观感，兼容宽屏/平板）
    // 使用 LocalWindowInfo 而非 LocalConfiguration：前者反映实际窗口尺寸，
    // 在多窗口/分屏模式下不会取到全屏宽度导致抽屉溢出
    val density = LocalDensity.current
    val drawerWidth = with(density) {
        val windowWidthDp = LocalWindowInfo.current.containerSize.width.toDp()
        min(320f, windowWidthDp.value * 0.8f).dp
    }

    // 遮罩：淡入淡出，点击空白关闭（对齐原侧滑面板外点击收起）
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut()
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                // 抽屉遮罩：scrim 语义色（浅/深调板下都是暗色），对齐 M3 惯例，不硬编码 Color.Black
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f))
                .clickable(onClick = onDismiss)
        )
    }

    // 抽屉本体：自屏幕左侧滑入/出，常驻调用侧 Box 的起始侧（默认 TopStart）
    AnimatedVisibility(
        visible = visible,
        enter = slideInHorizontally { -it },
        exit = slideOutHorizontally { -it }
    ) {
        Surface(
            modifier = Modifier
                .width(drawerWidth)
                .fillMaxHeight(),
            shape = RoundedCornerShape(topEnd = 28.dp, bottomEnd = 28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            tonalElevation = 3.dp
        ) {
            Column(
                modifier = Modifier
                    // edge-to-edge 避让：padding 写在底色内层，背景延伸到系统栏后面，
                    // 内容（标题/列表）避开状态栏与手势条（与顶/底栏同理）
                    .statusBarsPadding()
                    .navigationBarsPadding()
            ) {
                // 无障碍：胶囊文字是"当前模式"，动作语义由 stateDescription 补上——只念"正序"
                // 听不出这是个可切换的开关（本仓 Compose 页面不做装机级单测，读屏行为需人工验证）
                val orderToggleStateDescription = stringResource(R.string.catalog_order_toggle)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 8.dp, top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 第一行：书名独占剩余宽度（weight 1f），右侧只放关闭键。
                    // 背景：工具键（瞄准镜/顺序胶囊）曾与书名挤在同一行，长书名被右侧三键压成
                    // 省略号，故改为两行排布，两行互不抢横向空间。
                    Text(
                        text = bookName,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Outlined.Close,
                            contentDescription = stringResource(R.string.reader_close),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                // 工具行：信息（章节数）与两个工具（排序 / 回到当前章）左聚成组，右侧让位给列表。
                // 原先排序胶囊与瞄准镜被 weight spacer 顶到右缘孤悬，长目录里两个灰色控件挤在
                // 角落不易被发现；左聚后三者同属一条"信息 + 操作"带，扫读时一次就能看全。
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 20.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 章节总数弱化为胶囊标签，避免与书名争夺视觉重心
                    InfoChip(
                        text = stringResource(R.string.chapter_count_format, chapters.size),
                        shape = CommonUiTokens.pillShape,
                        textStyle = MaterialTheme.typography.labelSmall,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 3.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    // 顺序切换：显示"当前模式"而不是"点击后的动作"，避免"点了会变成什么"的歧义；
                    // 复用共享 InfoChip 的可点胶囊形态，与左侧章节数标签同一视觉语言（ADR-0006）
                    InfoChip(
                        text = stringResource(
                            if (descending) R.string.catalog_order_descending
                            else R.string.catalog_order_ascending
                        ),
                        modifier = Modifier.semantics {
                            stateDescription = orderToggleStateDescription
                        },
                        shape = CommonUiTokens.pillShape,
                        containerColor = if (descending) MaterialTheme.colorScheme.secondaryContainer
                        else MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = if (descending) MaterialTheme.colorScheme.onSecondaryContainer
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        textStyle = MaterialTheme.typography.labelSmall,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 3.dp),
                        onClick = {
                            // 切顺序只翻转顺序：**不做任何滚动**——display 列表翻转后 listState
                            // 停在原 index，列表进度百分比自然不变（约 10% 处倒序后仍停在约 10%
                            // 处，看到的章变成镜像位置的另一章，这正是"保持进度而非保持章节"的
                            // 语义，见 ADR-0034）。
                            // **该保持隐式依赖本列表 item 不带 key**（抽屉每行无跨滚动保留状态，
                            // 见下方 itemsIndexed）：若日后补 animateItem 或按内容加 key，翻转时
                            // LazyLayout 会按内容身份重排、位置按内容而非 index 复原，此语义即失效，
                            // 且没有编译错误或测试能拦住——改 key 政策前先想清楚这里。
                            descending = !descending
                        }
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    // 「回到当前章」：一次性动作（非开关、无常驻状态，任何顺序下语义一致）——
                    // 点一下即滚动到当前章在当序中的位置（倒序下即跳到当前章的镜像位）。
                    // 曾做成常驻锁定开关，结果与顺序互为拆台（切顺序悄悄关锁、倒序下无意义），
                    // 故降级为按需按钮，见 ADR-0034。
                    IconButton(onClick = {
                        if (durChapter in chapters.indices) {
                            scope.launch {
                                listState.scrollToItem(
                                    displayPositionOf(
                                        count = count,
                                        descending = descending,
                                        originalIndex = durChapter
                                    )
                                )
                            }
                        }
                    }) {
                        Icon(
                            imageVector = Icons.Outlined.MyLocation,
                            contentDescription = stringResource(R.string.catalog_locate_current),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.weight(1f))
                }
                // 列表 + 右侧快速滚动条（覆盖层高度与列表一致）
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                    ) {
                        // 不带 key：本列表每行没有需要跨滚动保留的状态，而"按 index 作 key"与"缺省 key"
                        // 在身份上是同一件事（缺省值即 DefaultLazyLayoutKey(index)，逐位唯一），
                        // 去掉只少一层 keyFactory、行为不变。
                        itemsIndexed(displayChapters) { position, chapter ->
                            // 章号、高亮、跳转一律用**原始索引**：倒序后首行仍显示"第 3000 章"，
                            // 点击跳转语义与正序完全一致
                            val index = originalIndexAt(
                                count = count,
                                descending = descending,
                                position = position
                            )
                            ChapterRow(
                                index = index,
                                name = chapter.durChapterName,
                                isCurrent = index == durChapter,
                                onClick = { onChapterClick(index) }
                            )
                        }
                    }
                    ReaderFastScroll(
                        listState = listState,
                        itemCount = chapters.size,
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .fillMaxHeight()
                    )
                }
            }
        }
    }
}

/**
 * 目录条目行：序号 + 章名 + 当前章节标记。
 *
 * 序号列固定宽度，让长短不一的章名左边界对齐，长目录扫读时视线有基准线；
 * 当前章节整行 `secondaryContainer` 底色 + 主色圆点，滚动后仍能一眼定位。
 * 圆角与底色只建在当前章那一行（其余行不建裁剪层）。
 */
@Composable
private fun ChapterRow(
    index: Int,
    name: String,
    isCurrent: Boolean,
    onClick: () -> Unit
) {
    // 圆角底与它的裁剪层只给当前章那一行建：非当前行的背景是 Transparent，为它保留 clip
    // 等于给每个可见行都分配一个渲染层（数千章滚动时的纯浪费）。代价是非当前行的水波纹
    // 从圆角变直角——那些行本身没有底色，视觉差异可以忽略。
    val rowModifier = Modifier
        .fillMaxWidth()
        .padding(vertical = 1.dp)
        .then(
            if (isCurrent) {
                Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.secondaryContainer)
            } else {
                Modifier
            }
        )
        .clickable(onClick = onClick)
        .padding(horizontal = 12.dp, vertical = 11.dp)
    Row(
        modifier = rowModifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(R.string.chapter_number, index + 1),
            modifier = Modifier.width(34.dp),
            style = MaterialTheme.typography.labelMedium,
            color = if (isCurrent) {
                MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
        Text(
            text = name,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (isCurrent) FontWeight.Medium else FontWeight.Normal,
            color = if (isCurrent) MaterialTheme.colorScheme.onSecondaryContainer
            else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (isCurrent) {
            Spacer(modifier = Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
            )
        }
    }
}
