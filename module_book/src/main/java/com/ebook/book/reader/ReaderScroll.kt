package com.ebook.book.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ebook.book.R
import kotlin.math.abs

/**
 * 上下滚屏容器（与 [ReaderPager] 并列的另一种承载方式）。
 *
 * 布局：外层 Column 承担与 [ReaderPageCard] **完全相同**的 insets 与内边距口径，
 * 内部 LazyColumn 是滚动视口（`weight(1f)`，`onSizeChanged` 回报宽高供分块测算），
 * 视口下方一条常驻位置行——位置行留在滚动区**之外**而不是做覆盖层，
 * 正文就永远不会滚到它底下被压住（覆盖层要么遮最后一行，要么得做半透明/自动隐藏）。
 *
 * **列表跨章连续**：item 由 [ReaderScrollController] 按「章段 = 标题项 + 块项」给出，
 * 本章末块的下一项就是下一章标题，故章界不需要任何「切章」动作，也没有「上一章/下一章」
 * 链接项。两件事因此是硬约束（理由见控制器类 KDoc）：item **必须带稳定 key**
 * （LazyColumn 靠它在锚点上方插入 item 时保住视觉位置），且 key **全局唯一**。
 *
 * 块高由 [blockHeightPx] 给定（排版实测，见 [ReaderTypesetter.measureBlock]），
 * 每块恰好一屏、块与块精确无缝拼接。
 *
 * @param onViewportSizeChanged 视口宽高回调（喂 `ReadBookActivity.rePaginate` 测算一屏几行）
 * @param onCenterTap 点击中间三分之一区域唤出菜单（与翻页模式同一分区口径）
 */
@Composable
internal fun ReaderScroll(
    controller: ReaderScrollController,
    textColor: Color,
    bgColor: Color,
    textSizeSp: Float,
    lineHeight: TextUnit,
    blockHeightPx: Int,
    canClickTurn: Boolean,
    onCenterTap: () -> Unit,
    onViewportSizeChanged: (widthPx: Int, heightPx: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val touchSlop = LocalViewConfiguration.current.touchSlop
    val itemCount = controller.itemCount
    val jump = controller.jump

    // 落点：跳章与冷启动恢复是瞬移，点击/按键滚一屏是动画滚动（连续列表里滚一屏
    // 就是滚到下一项，跨章也照样动画过去）。token 每次都变，故同一落点也能重复触发。
    //
    // **本 effect 必须声明在下面「上报滚动位置」那个 effect 之前**：容器刚进入组合时
    // LazyListState 是全新的、`firstVisibleItemIndex` 恒为 0，而 0 号项是「最早物化的那一章」
    // 的标题（章段形如 [c-1, c, c+1]）。先落点、后上报，上报读到的就是落点之后的序号；
    // 顺序反过来会先报 0 号项，把进度写成上一章章首——实测 progress 变成 [(0,0), (1,0)]，
    // 随后才被纠正，而中间那一刻进程被杀就丢位置。回归由 ReaderScrollSeamlessTest 锁住。
    LaunchedEffect(jump) {
        val target = jump ?: return@LaunchedEffect
        if (target.animate) {
            listState.animateScrollToItem(target.itemIndex)
        } else {
            listState.scrollToItem(target.itemIndex)
        }
    }

    // 滚动位置原样上报，换算成 (章, 块) 是控制器的事（章段在它手里）。
    // 锚点上方插入 item 时这个序号会整体平移，而 LazyColumn 已按 key 保住了画面，
    // 故控制器按语义值判「是否同一屏」，平移不会被误当成阅读推进。
    val visibleIndex by remember { derivedStateOf { listState.firstVisibleItemIndex } }
    LaunchedEffect(visibleIndex) { controller.onScrolledToItem(visibleIndex) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(bgColor)
            // 与 ReaderPageCard 同一口径：阅读器 enableFitsSystemWindows=false，
            // 内容铺到屏幕边缘，不避让就会被系统手势条/三键栏压住
            .windowInsetsPadding(WindowInsets.systemBars)
            .padding(start = 15.dp, top = 5.dp, end = 15.dp, bottom = 5.dp)
            // 点击分区：左右三分区滚一屏（受「点击翻页」开关控制），中间唤出菜单。
            // 刻意**只判点击、不接管拖拽**：竖向滚动交给 LazyColumn 自己的滚动手势
            // （它带惯性与 fling，自己实现一套只会更差），这里若在 pointerInput 里消费
            // 竖向 drag 就会与它抢事件。故用 touchSlop 区分「点」与「滚」，滚动一律不消费。
            .pointerInput(controller, canClickTurn, touchSlop) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = true)
                    val startX = down.position.x
                    val widthPx = size.width.toFloat()
                    var moved = false
                    var pressed = true
                    while (pressed) {
                        val change = awaitPointerEvent().changes.firstOrNull() ?: break
                        pressed = change.pressed
                        if (!moved && abs(change.position.x - startX) > touchSlop) moved = true
                        if (!moved && abs(change.position.y - down.position.y) > touchSlop) moved = true
                    }
                    if (moved) return@awaitEachGesture
                    when {
                        canClickTurn && startX <= widthPx / 3 ->
                            controller.scrollOneScreen(forward = false)
                        canClickTurn && startX >= widthPx / 3 * 2 ->
                            controller.scrollOneScreen(forward = true)
                        else -> onCenterTap()
                    }
                }
            }
    ) {
        // 块高未知（排版还没落定）时不给高度约束：占位块按内容高画，
        // 否则 height(0.dp) 会让加载态/错误态整个不可见
        val blockHeightDp: Dp =
            if (blockHeightPx > 0) with(LocalDensity.current) { blockHeightPx.toDp() } else Dp.Unspecified

        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                // 视口尺寸**只在这里回报一次**。绝不能让块自己也回报：块高是由视口高
                // 算出来的（measureBlock），块再把自己回报成"正文区高度"会让 rePaginate
                // 拿块高当视口高、算出更少的行数、得到更矮的块——一路自我收缩到空。
                .onSizeChanged { onViewportSizeChanged(it.width, it.height) }
        ) {
            if (itemCount == 0) {
                // 排版未落定：只画一个占位态，不留一片空白。高度不参与——视口尺寸由上面
                // LazyColumn 的 onSizeChanged 给出，与有没有 item 无关，故不存在
                // "加载完才能测量、测量后才能分块"的死锁
                item {
                    Box(modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp)) {
                        ScrollBlockState(
                            ui = ReaderPageUi.Loading,
                            textColor = textColor,
                            textSizeSp = textSizeSp,
                            lineHeight = lineHeight,
                            onRetry = {},
                        )
                    }
                }
            } else {
                items(count = itemCount, key = { controller.itemKey(it) }) { index ->
                    when (val item = controller.itemAt(index)) {
                        is ScrollItem.Title -> ScrollChapterTitle(
                            title = controller.titleOf(item.chapterIndex),
                            textColor = textColor,
                        )

                        is ScrollItem.Block -> {
                            // 画出来的块自己请求加载：fling 时 firstVisibleItemIndex 会跳过
                            // 中间块号，只靠预取覆盖不到（理由见 ReaderScrollController.ensureLoaded）
                            LaunchedEffect(item) {
                                controller.ensureLoaded(item.chapterIndex, item.blockIndex)
                            }
                            ScrollBlock(
                                ui = controller.uiOf(item.chapterIndex, item.blockIndex),
                                textColor = textColor,
                                textSizeSp = textSizeSp,
                                lineHeight = lineHeight,
                                blockHeightDp = blockHeightDp,
                                onRetry = { controller.reload(item.chapterIndex, item.blockIndex) },
                            )
                        }

                        null -> Unit
                    }
                }
            }
        }

        // 常驻位置行：高度与翻页模式的页码行同一个 token，两种模式的位置指示节奏一致。
        // 口径仍是「本章第几屏」——列表虽跨章连续，进度语义没变（见 ADR-0037）
        val anchorChapter = controller.anchorChapter
        val anchorBlockCount = controller.blockCountOf(anchorChapter)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(ReaderPageTokens.pageNumberRowHeight),
            contentAlignment = Alignment.TopCenter,
        ) {
            if (anchorBlockCount > 0) {
                Text(
                    text = stringResource(
                        R.string.page_indicator_format,
                        controller.anchorBlock + 1,
                        anchorBlockCount,
                    ),
                    color = textColor,
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/**
 * 章标题项：一章只有一项，是章与章之间唯一的分隔。
 *
 * 上内边距比下内边距大，让「新章开始」在连续正文里看得出来；它不参与块高契约
 * （块与块无缝拼接靠的是块高取排版实测值），故这里的高度可以自由给。
 */
@Composable
private fun ScrollChapterTitle(title: String, textColor: Color) {
    Text(
        text = title,
        modifier = Modifier
            .fillMaxWidth()
            .alpha(0.7f)
            .padding(top = 26.dp, bottom = 10.dp),
        color = textColor,
        fontSize = 14.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/**
 * 正文块：只含正文，不含标题与页码行。
 *
 * 标题变成章首会滚走的分隔项、页码变成视口外的常驻行——块内若再画一遍，
 * 每屏都会重复章节标题，且相邻两块正文之间出现一条纸张色空带。
 *
 * 块高固定为 [blockHeightDp]（排版实测的 lineCount 行总高），与内容无关，
 * 因此 Loading/Error 块照样占一屏高，滚动位置不会因某块加载失败而塌陷
 * （与 [ReaderPageCard]「正文区高度与页面状态无关」是同一个占位契约）。
 */
@Composable
private fun ScrollBlock(
    ui: ReaderPageUi,
    textColor: Color,
    textSizeSp: Float,
    lineHeight: TextUnit,
    blockHeightDp: Dp,
    onRetry: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(blockHeightDp)
    ) {
        ScrollBlockState(
            ui = ui,
            textColor = textColor,
            textSizeSp = textSizeSp,
            lineHeight = lineHeight,
            onRetry = onRetry,
        )
    }
}

/**
 * 块的三态内容（加载中 / 失败 / 正文），不定高。
 *
 * 从 [ScrollBlock] 拆出来的唯一理由：排版尚未落定时的占位也要画同一套状态，
 * 但它没有块高可给（块高正是排版要算的东西）。配色全部由正文色按透明度派生——
 * 这三态同样画在纸上，属「阅读背景主题」层，正文层豁免深浅色切换。
 *
 * 接收者是 [BoxScope]：三态里的加载/错误需要 `align(Center)` 居中，而居中语义
 * 依赖父级是 Box。写成 BoxScope 扩展比传 Modifier 进来更准——后者会让居中语义
 * 散到调用点，两个调用点各写一次就会漂移。
 */
@Composable
private fun BoxScope.ScrollBlockState(
    ui: ReaderPageUi,
    textColor: Color,
    textSizeSp: Float,
    lineHeight: TextUnit,
    onRetry: () -> Unit,
) {
    when (ui) {
        is ReaderPageUi.Loaded -> Text(
            text = ui.text,
            modifier = Modifier.fillMaxWidth(),
            color = textColor,
            // 样式必须与分页测量同一份：字号/行高/行高对齐任何一项不一致，
            // 「切几行」与「画几行」就会错开（契约见 readerBodyTextStyle）
            style = readerBodyTextStyle(textSizeSp, lineHeight),
        )

        is ReaderPageUi.Loading -> Column(
            modifier = Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(26.dp),
                color = textColor.copy(alpha = 0.35f),
                strokeWidth = 2.5.dp,
            )
            Spacer(modifier = Modifier.height(14.dp))
            Text(
                text = stringResource(R.string.loading),
                color = textColor.copy(alpha = 0.55f),
                fontSize = 14.sp,
            )
        }

        is ReaderPageUi.Error -> Column(
            modifier = Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = Icons.Outlined.CloudOff,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = textColor.copy(alpha = 0.4f),
            )
            Spacer(modifier = Modifier.height(14.dp))
            Text(
                text = stringResource(R.string.reader_load_failed),
                color = textColor.copy(alpha = 0.8f),
                fontSize = 16.sp,
            )
            Spacer(modifier = Modifier.height(22.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(textColor.copy(alpha = 0.07f))
                    .border(
                        width = 1.dp,
                        color = textColor.copy(alpha = 0.3f),
                        shape = RoundedCornerShape(50),
                    )
                    .clickable(onClick = onRetry)
                    .padding(horizontal = 22.dp, vertical = 10.dp)
            ) {
                Text(
                    text = stringResource(R.string.retry),
                    color = textColor.copy(alpha = 0.85f),
                    fontSize = 14.sp,
                )
            }
        }
    }
}
