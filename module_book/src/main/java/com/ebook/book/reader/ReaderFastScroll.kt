package com.ebook.book.reader

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

/**
 * 章节目录快速滚动条（替代原 RecyclerViewBar）。
 *
 * 右侧竖直可拖动手柄，按 y 比例 scrollToItem 做长目录定位；拖动或列表滚动时出现，
 * 1 秒无操作自动隐藏（对齐原 AutoHideTimer 1000ms：每次拖动/滚动事件都重启倒计时，
 * 滚动 fling 期间同样显示滑块）。滑块顶端位置随列表首可见项同步（比例映射）。
 *
 * 视觉：轨道常显（弱化的细线）负责"这里可以拖"的可寻性，滑块仅在操作期间出现；
 * 滑块高度按可视条目占比换算（下限 28dp），千章目录下一格高度比固定 18dp 更能
 * 表达"当前处于全书的哪一段"。
 *
 * 可见性 internal：除阅读器目录抽屉外，下载中心二级选章页（ChapterDownloadSheet）
 * 的长目录复用同一组件——同为"几千章里快速定位"的场景，不应各长一套滚动条。
 */
@Composable
internal fun ReaderFastScroll(
    listState: LazyListState,
    itemCount: Int,
    modifier: Modifier = Modifier
) {
    if (itemCount <= 0) return
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    var visible by remember { mutableStateOf(false) }
    // 自动隐藏倒计时 Job：每次操作（拖动/滚动）cancel 旧任务并重启，保证"1 秒无操作"才隐藏，
    // 而不是"可见后固定 1 秒必隐藏"（对齐原 RecyclerViewBar.restartAutoHideTimer 语义）
    var hideJob by remember { mutableStateOf<Job?>(null) }
    var boxHeightPx by remember { mutableIntStateOf(0) }

    // 显示滑块并重启 1 秒无操作倒计时
    fun showWithAutoHide() {
        visible = true
        hideJob?.cancel()
        hideJob = scope.launch {
            delay(1000L.milliseconds)
            visible = false
        }
    }

    // 拖到指定 y → 按比例滚动到对应章节（clamp 到有效范围）
    fun scrollToY(yPx: Float) {
        if (boxHeightPx <= 0) return
        val index = ((yPx / boxHeightPx) * itemCount).toInt().coerceIn(0, itemCount - 1)
        scope.launch { listState.scrollToItem(index) }
    }

    // 列表滚动/fling 时也显示滑块并重启倒计时（原实现滚动事件同样 showSlider）
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }
            .collect { scrolling ->
                if (scrolling) showWithAutoHide()
            }
    }

    // 滑块顶端比例 = 首可见项比例；高度比例 = 可视条目 / 总条目（下限 28dp 保证可点）
    // layoutInfo / firstVisibleItemIndex 标注了 @FrequentlyChangingValue，
    // 直接读取会让组合每帧重组；derivedStateOf 缓存结果，仅在派生值真正变化时触发重组
    val visibleItems by remember {
        derivedStateOf { listState.layoutInfo.visibleItemsInfo.size.coerceAtLeast(1) }
    }
    val fraction by remember {
        derivedStateOf {
            if (itemCount > 1)
                listState.firstVisibleItemIndex.toFloat() / (itemCount - 1) else 0f
        }
    }
    val minThumbPx = with(density) { 28.dp.toPx() }
    val thumbHeightPx = if (boxHeightPx <= 0) {
        minThumbPx
    } else {
        (boxHeightPx * (visibleItems.toFloat() / itemCount))
            .coerceIn(minThumbPx, boxHeightPx.toFloat())
    }
    val thumbTopPx = (boxHeightPx - thumbHeightPx).coerceAtLeast(0f) * fraction
    val thumbAlpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        label = "fastScrollThumb"
    )

    Box(
        modifier = modifier
            .width(18.dp)
            .fillMaxHeight()
            .onSizeChanged { boxHeightPx = it.height }
            .pointerInput(listState, itemCount, boxHeightPx) {
                detectVerticalDragGestures(
                    onDragStart = { showWithAutoHide() },
                    onVerticalDrag = { change, _ ->
                        scrollToY(change.position.y)
                        // 拖动期间每次移动都重置倒计时（按住不动 1 秒才隐藏）
                        showWithAutoHide()
                    },
                    onDragEnd = { showWithAutoHide() },
                    onDragCancel = { showWithAutoHide() }
                )
            },
        contentAlignment = Alignment.CenterEnd
    ) {
        // 轨道：常显但弱化，给长目录一个"可拖动"的位置暗示
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .padding(vertical = 10.dp, horizontal = 6.dp)
                .width(2.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.08f))
        )
        // 滑块：贴右缘（4dp 宽占 14~18）后左偏 5dp，使其中心与轨道中心（10~12 的中点）重合
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset(x = (-5).dp, y = with(density) { thumbTopPx.toDp() })
                .width(4.dp)
                .height(with(density) { thumbHeightPx.toDp() })
                .alpha(thumbAlpha)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary)
        )
    }
}
