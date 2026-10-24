package com.ebook.book.reader

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * 面板统一标题行：左标题 + 可选右侧操作位。
 *
 * 三个面板（亮度/字体/设置）共用同一节奏，`dragHandle = null` 后标题即面板唯一的
 * 身份标识，字号与颜色在此收口，避免各面板自定 titleMedium/bodyLarge 造成层级漂移。
 */
@Composable
internal fun SheetHeader(
    title: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        trailing?.invoke()
    }
}

/**
 * 面板开关行：36dp 彩色图标块 + 标题/说明 + 尾部 Switch。
 *
 * 图标块沿用 CommonListItem 的「圆角色块承载图标」语言（ADR-0006），让阅读器面板
 * 与设置页属同一视觉体系；说明文本用于交代开关的实际作用范围（音量键/点击区域/系统亮度），
 * 原实现只有标题，用户需要试了才知道开关干什么。
 * 整行可点（点标题区同样切换），Switch 与行点击共用同一入口保证状态同步。
 */
@Composable
internal fun PanelSwitchRow(
    icon: ImageVector,
    label: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    iconContainerColor: Color = MaterialTheme.colorScheme.primaryContainer,
    iconContentColor: Color = MaterialTheme.colorScheme.onPrimaryContainer,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = ReaderChromeTokens.switchRowPadding, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            modifier = Modifier.size(ReaderChromeTokens.iconBox),
            shape = RoundedCornerShape(10.dp),
            color = iconContainerColor
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = iconContentColor
                )
            }
        }
        Spacer(modifier = Modifier.width(ReaderChromeTokens.iconGap))
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/**
 * 面板单选行：36dp 彩色图标块 + 标题/说明 + 尾部 RadioButton。
 *
 * 与 [PanelSwitchRow] 同一套尺寸口径（[ReaderChromeTokens]），只把尾部控件换成单选钮：
 * 翻页方式是**互斥的二选一**，用 Switch 会被读成「开/关某个功能」而不是「选哪一档」。
 * 整行可点（不只尾部控件），与 PanelSwitchRow 的点击面一致；已选中行不可再点。
 *
 * 可见性为 internal 而非 private：ReaderTurnModeRowRenderTest 要直接渲染它来锁
 * 「图标块底色随调板翻转」——该测试防的是颜色角色被写回常量，那种失败布局与文案全对、
 * 只有像素是错的，只能靠渲染看见。
 */
@Composable
internal fun PanelChoiceRow(
    icon: ImageVector,
    label: String,
    description: String,
    selected: Boolean,
    onSelect: () -> Unit,
    iconContainerColor: Color = MaterialTheme.colorScheme.primaryContainer,
    iconContentColor: Color = MaterialTheme.colorScheme.onPrimaryContainer,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = !selected, onClick = onSelect)
            .padding(horizontal = ReaderChromeTokens.switchRowPadding, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            modifier = Modifier.size(ReaderChromeTokens.iconBox),
            shape = RoundedCornerShape(10.dp),
            color = iconContainerColor
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = iconContentColor
                )
            }
        }
        Spacer(modifier = Modifier.width(ReaderChromeTokens.iconGap))
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        RadioButton(selected = selected, onClick = onSelect)
    }
}

/**
 * 阅读器紧凑滑条（自绘，替代 Material3 Slider）。
 *
 * 为什么自绘：material3 新版滑条按「竖条手柄 + 细轨道」绘制，手柄高度与底栏这一行的
 * 文字/图标抢视线，取值处于最小端时轨道另一端还会露出一个端点圆点，整体读起来像控件
 * 坏了。阅读器要表达的是"一条能拖的进度"，故回到经典形态：4dp 轨道 + 圆旋钮 +
 * 按下放大与光晕。底栏章节滑条与亮度面板共用本组件，两处保持同一控件语言。
 *
 * 手势语义与替换前一致：按下即定位、拖动实时回调 [onValueChange]、
 * 抬手/取消回调 [onValueChangeFinished]（底栏据此取整跳章）。
 *
 * 位置映射固定用静止态旋钮直径（[ReaderChromeTokens.sliderThumbRest]）计算，
 * 不随按下后的放大尺寸变化——否则手指按住不动时旋钮变大可移动区间就缩短，
 * 同一触点会被换算成另一个值，表现为数值抖动。
 */
@Composable
internal fun ReaderSlider(
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    onValueChangeFinished: (() -> Unit)? = null,
    enabled: Boolean = true,
    contentDescription: String? = null,
) {
    val density = LocalDensity.current
    var trackWidthPx by remember { mutableIntStateOf(0) }
    var pressed by remember { mutableStateOf(false) }

    // 单章书（range 两端相同）时 span 兜底为正数，避免除零得到 NaN
    val span = (valueRange.endInclusive - valueRange.start).coerceAtLeast(1e-6f)
    val fraction = ((value - valueRange.start) / span).coerceIn(0f, 1f)
    val mapThumbPx = with(density) { ReaderChromeTokens.sliderThumbRest.toPx() }
    val travelPx = (trackWidthPx - mapThumbPx).coerceAtLeast(0f)

    fun valueAt(xPx: Float): Float {
        if (trackWidthPx <= 0) return value
        return valueRange.start +
            ((xPx - mapThumbPx / 2f) / travelPx).coerceIn(0f, 1f) * span
    }

    val thumbColor = if (enabled) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    }
    val inactiveTrackColor = if (enabled) {
        MaterialTheme.colorScheme.surfaceVariant
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
    }
    val thumbSize by animateDpAsState(
        targetValue = if (pressed) {
            ReaderChromeTokens.sliderThumbPressed
        } else {
            ReaderChromeTokens.sliderThumbRest
        },
        label = "readerSliderThumb"
    )
    val haloAlpha by animateFloatAsState(
        targetValue = if (pressed) 1f else 0f,
        label = "readerSliderHalo"
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(ReaderChromeTokens.sliderHeight)
            .onSizeChanged { trackWidthPx = it.width }
            .then(
                // 禁用态不挂手势：亮度面板"跟随系统"时滑条只表达当前值，不可调
                if (enabled) {
                    Modifier.pointerInput(valueRange, trackWidthPx) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            down.consume()
                            pressed = true
                            onValueChange(valueAt(down.position.x))
                            while (true) {
                                val change = awaitPointerEvent().changes.firstOrNull() ?: break
                                val isUp = !change.pressed
                                change.consume()
                                if (isUp) break
                                onValueChange(valueAt(change.position.x))
                            }
                            pressed = false
                            onValueChangeFinished?.invoke()
                        }
                    }
                } else {
                    Modifier
                }
            )
            .semantics(mergeDescendants = true) {
                // 与 Material3 Slider 同源的无障碍表达：进度信息 + 描述
                // （Compose 语义无 Slider 角色，靠 ProgressBarRangeInfo 即可读作滑条）
                contentDescription?.let { this.contentDescription = it }
                progressBarRangeInfo = ProgressBarRangeInfo(value, valueRange, steps = 0)
            },
        contentAlignment = Alignment.CenterStart
    ) {
        // 轨道：未填充段通铺，已填充段按 fraction 覆盖（fraction=0 时宽度 0，不露色块）
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(ReaderChromeTokens.sliderTrackHeight)
                .clip(CircleShape)
                .background(inactiveTrackColor)
        )
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction)
                .height(ReaderChromeTokens.sliderTrackHeight)
                .clip(CircleShape)
                .background(thumbColor)
        )
        // 按下光晕先画，旋钮叠在其上，两者中心由同一 fraction 保证重合
        val haloPx = with(density) { ReaderChromeTokens.sliderThumbHalo.toPx() }
        Box(
            modifier = Modifier
                .size(ReaderChromeTokens.sliderThumbHalo)
                .offset {
                    IntOffset(
                        (travelPx * fraction + mapThumbPx / 2f - haloPx / 2f).roundToInt(),
                        0
                    )
                }
                .alpha(haloAlpha)
                .clip(CircleShape)
                .background(thumbColor.copy(alpha = 0.14f))
        )
        Box(
            modifier = Modifier
                .size(thumbSize)
                .offset {
                    // 以"静止态旋钮中心"为锚点定位：按下放大时旋钮围绕自身中心生长，
                    // 不会因尺寸变化让触点与旋钮错位（映射基准见函数头注释）
                    val thumbPx = with(density) { thumbSize.toPx() }
                    IntOffset(
                        (mapThumbPx / 2f - thumbPx / 2f + travelPx * fraction).roundToInt(),
                        0
                    )
                }
                .clip(CircleShape)
                .background(thumbColor)
        )
    }
}
