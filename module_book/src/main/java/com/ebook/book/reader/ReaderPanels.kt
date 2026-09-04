package com.ebook.book.reader

import android.app.Activity
import android.content.Context
import android.provider.Settings
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectVerticalDragGestures
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ListAlt
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.BrightnessHigh
import androidx.compose.material.icons.outlined.BrightnessLow
import androidx.compose.material.icons.outlined.BrightnessMedium
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.ModeComment
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.TouchApp
// 别名避免与 android.provider.Settings 冲突
import androidx.compose.material.icons.outlined.Settings as SettingsIcon
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ebook.book.R
import com.ebook.book.view.ReadBookControl
import com.ebook.common.ui.CommonCard
import com.ebook.common.ui.CommonUiTokens
import com.ebook.common.ui.InfoChip
import com.ebook.common.ui.SectionLabel
import com.ebook.db.entity.ChapterListEntity
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.milliseconds
import androidx.core.content.edit
import com.xrn1997.common.util.Logger

/**
 * 阅读器面板枚举（原五个 PopupWindow 的显隐状态统一收口）。
 *
 * 由 ReadBookScreen（ReadBookActivity.kt）持有，底栏据其高亮当前打开的入口，
 * 故声明在 reader 包内供 chrome 层共用（不再放在 Activity 文件里，避免反向依赖）。
 */
internal enum class ReaderPanel { NONE, CHAPTER, LIGHT, FONT, SETTING, DOWNLOAD }

/**
 * 阅读器 chrome 层局部设计常量。
 *
 * 顶/底栏与三类面板共用同一套节奏（圆角/图标块/缩进），集中定义避免散落的魔法值；
 * 跨模块通用的间距/圆角仍一律走 [CommonUiTokens]，此处只放阅读器专属值。
 */
private object ReaderChromeTokens {
    /** 栏体高度（顶栏内容区） */
    val barHeight = 56.dp

    /** 面板开关行的图标容器尺寸（对齐 CommonListItem 的 36dp 图标块语言） */
    val iconBox = 36.dp

    /** 图标块与文本的间隙 */
    val iconGap = 12.dp

    /** 面板开关行的水平内边距（留出与卡片 12dp 内边距叠加后的呼吸空间） */
    val switchRowPadding = 8.dp

    /**
     * 开关行之间分割线的起始缩进：[switchRowPadding] + [iconBox] + [iconGap]。
     *
     * 不复用 [CommonUiTokens.dividerIndent]（64dp）——那个值以 CommonListItem 的
     * 16dp 卡片内边距为基准，本处开关行自带 8dp 行内边距，缩进基准不同。
     */
    val switchDividerIndent = switchRowPadding + iconBox + iconGap

    /** 面板顶部留白（dragHandle 已移除，标题需要自身与圆角边保持距离） */
    val sheetTopPadding = 20.dp

    /** 面板底部留白 */
    val sheetBottomPadding = 24.dp

    /** 自绘滑条轨道厚度（章节滑条与亮度滑条共用） */
    val sliderTrackHeight = 4.dp

    /** 自绘滑条整体高度：32dp 触摸留白，与两端 44dp 按钮同行时不额外撑高底栏 */
    val sliderHeight = 32.dp

    /** 滑条旋钮静止直径（同时也是触点→数值映射的固定基准，见 [ReaderSlider]） */
    val sliderThumbRest = 14.dp

    /** 滑条旋钮按下直径 */
    val sliderThumbPressed = 18.dp

    /** 滑条按下光晕直径 */
    val sliderThumbHalo = 34.dp
}

/**
 * 阅读器顶栏（对齐原 ll_menu_top：返回 + 章节标题 + 更多入口）。
 *
 * 视觉：`surfaceContainer` 底色 + 3dp 下沿投影，让菜单像"浮在正文之上的一层"，
 * 替代原先与正文同色、边界靠硬编码分割线维持的扁平观感。
 *
 * 标题居中并把书名作为副标题常驻：阅读器顶栏的标题是"当前章节"，读者更关心
 * "我在读哪本书的哪一章"，两级信息同屏可省去返回详情页确认的成本。
 * 标题单行省略（原 AutofitTextView 的自缩放在 Compose 无等价物，以省略号替代，
 * 章节名场景可接受）；"更多"仅非本地书显示下载入口时可见。
 *
 * 下载入口只有一个：下发任务统一带强制刷新标记，勾中已缓存章节即等价"刷新缓存"，
 * 故不再单列"强制刷新缓存"菜单项（该能力本就与下载共用同一条流水线）。
 *
 * edge-to-edge 避让：阅读页 enableFitsSystemWindows=false，顶栏需自行避让状态栏，
 * 否则返回键/标题会画到状态栏下面被遮挡。statusBarsPadding 写在底色内层：
 * 背景延伸到状态栏后面（视觉连续），内容下移避让。
 */
@Composable
fun ReaderTopBar(
    title: String,
    subtitle: String,
    showMore: Boolean,
    onBack: () -> Unit,
    onDownload: () -> Unit,
    onComment: () -> Unit,
    modifier: Modifier = Modifier
) {
    var moreExpanded by remember { mutableStateOf(false) }
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainer,
        shadowElevation = 3.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // 避让修饰符位于固定高度（56dp Row）外层，整栏下移而非压缩内容，
                // 否则状态栏 insets 会从固定高度内扣除把内容压成零高（见踩坑记录）
                .statusBarsPadding()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(ReaderChromeTokens.barHeight)
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(com.ebook.common.R.string.back),
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
                // 左右操作区同宽（48dp），中央列的剩余空间以屏幕中线对称 → 标题真正居中
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (subtitle.isNotEmpty()) {
                        Text(
                            text = subtitle,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (showMore) {
                    Box {
                        IconButton(onClick = { moreExpanded = true }) {
                            Icon(
                                imageVector = Icons.Outlined.MoreVert,
                                contentDescription = stringResource(R.string.setting),
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        // 更多菜单（对齐原 ReadBookMenuMorePop：下载 + 评论入口）
                        DropdownMenu(
                            expanded = moreExpanded,
                            onDismissRequest = { moreExpanded = false },
                            shape = RoundedCornerShape(CommonUiTokens.cardCornerSmall),
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            tonalElevation = 3.dp
                        ) {
                            ReaderMenuItem(
                                icon = Icons.Outlined.Download,
                                text = stringResource(com.ebook.common.R.string.download),
                            ) {
                                moreExpanded = false
                                onDownload()
                            }
                            ReaderMenuItem(
                                icon = Icons.Outlined.ModeComment,
                                text = stringResource(R.string.comment),
                            ) {
                                moreExpanded = false
                                onComment()
                            }
                        }
                    }
                } else {
                    Spacer(modifier = Modifier.width(48.dp))
                }
            }
        }
    }
}

/**
 * 顶栏"更多"菜单项：前置图标 + 文本。
 *
 * 纯文字菜单项在两项时视觉重心偏右，加图标后与全 App 的图标化条目语言一致。
 */
@Composable
private fun ReaderMenuItem(
    icon: ImageVector,
    text: String,
    onClick: () -> Unit
) {
    DropdownMenuItem(
        text = { Text(text, style = MaterialTheme.typography.bodyLarge) },
        leadingIcon = {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
        onClick = onClick
    )
}

/**
 * 阅读器底栏（对齐原 ll_menu_bottom）：
 * 章节进度文本 / 上一章 / 章节滑条 / 下一章 + 目录/亮度/字体/设置四入口。
 *
 * 相对原快照的实现改进：
 * - 「上一章/下一章」由裸文字点击区改为圆形图标按钮——原文字热区仅 30dp 上下，
 *   低于 48dp 最小可点击尺寸；图标 + 禁用态淡出让"能不能点"一眼可辨
 * - 新增章节进度文本行：拖动滑条时数字实时跟手，补齐"拖到哪一章"的反馈
 * - 章节滑条改用自绘 [ReaderSlider]：Material3 新版滑条的竖条手柄在这条紧凑行里过重，
 *   与两端按钮、文字抢视线（详见该组件 KDoc）
 * - 四入口改胶囊底色高亮，并用 [activePanel] 标记当前已打开的面板，
 *   避免"面板被下拉把手遮住后看不出是哪个入口开的"
 *
 * 滑条语义对齐原 MHorProgressBar：拖动实时跟手，抬手取整跳章；
 * 首末章禁用对应上一章/下一章按钮。
 *
 * edge-to-edge 避让：底栏贴屏底，需自行避让手势条/导航栏（与顶栏同理，
 * padding 写在 background 内层：背景延伸到导航栏后面，内容上移避让）。
 */
@Composable
internal fun ReaderBottomBar(
    chapterAll: Int,
    sliderValue: Float,
    activePanel: ReaderPanel,
    onSliderChange: (Float) -> Unit,
    onSliderFinished: () -> Unit,
    prevEnabled: Boolean,
    nextEnabled: Boolean,
    onPrevChapter: () -> Unit,
    onNextChapter: () -> Unit,
    onCatalog: () -> Unit,
    onLight: () -> Unit,
    onFont: () -> Unit,
    onSetting: () -> Unit,
    modifier: Modifier = Modifier
) {
    // 滑条值即 1-based 章序号；无章节时给出明确文案，避免"第 1 章 · 共 0 章"的矛盾表述
    val progressText = if (chapterAll <= 0) {
        stringResource(R.string.no_chapter)
    } else {
        stringResource(
            R.string.reader_chapter_progress_format,
            sliderValue.roundToInt().coerceIn(1, chapterAll),
            chapterAll
        )
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            // surfaceContainer：与正文背景、顶栏同调，形成上下对称的"菜单层"
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .navigationBarsPadding()
    ) {
        Text(
            text = progressText,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ChapterStepButton(
                icon = Icons.Outlined.ChevronLeft,
                description = stringResource(R.string.last_chapter),
                enabled = prevEnabled,
                onClick = onPrevChapter
            )
            ReaderSlider(
                value = sliderValue,
                valueRange = 1f..chapterAll.coerceAtLeast(1).toFloat(),
                onValueChange = onSliderChange,
                onValueChangeFinished = onSliderFinished,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 6.dp),
                contentDescription = stringResource(R.string.reader_chapter_slider)
            )
            ChapterStepButton(
                icon = Icons.Outlined.ChevronRight,
                description = stringResource(R.string.next_chapter),
                enabled = nextEnabled,
                onClick = onNextChapter
            )
        }
        HorizontalDivider(
            modifier = Modifier.padding(top = 2.dp),
            color = MaterialTheme.colorScheme.outlineVariant
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 6.dp)
        ) {
            ReaderBottomEntry(
                Icons.AutoMirrored.Outlined.ListAlt,
                stringResource(R.string.catalogue),
                activePanel == ReaderPanel.CHAPTER,
                onCatalog,
                Modifier.weight(1f)
            )
            ReaderBottomEntry(
                Icons.Outlined.BrightnessMedium,
                stringResource(R.string.luminance),
                activePanel == ReaderPanel.LIGHT,
                onLight,
                Modifier.weight(1f)
            )
            ReaderBottomEntry(
                Icons.Outlined.TextFields,
                stringResource(R.string.font),
                activePanel == ReaderPanel.FONT,
                onFont,
                Modifier.weight(1f)
            )
            ReaderBottomEntry(
                Icons.Outlined.SettingsIcon,
                stringResource(R.string.setting),
                activePanel == ReaderPanel.SETTING,
                onSetting,
                Modifier.weight(1f)
            )
        }
    }
}

/**
 * 章节快进/快退按钮（上一章 / 下一章）。
 *
 * 44dp IconButton 保证触摸目标达标，内部再画 30dp 圆形色块承载图标：
 * 色块给出"这是个按钮"的可点性暗示，禁用时整块淡出，比原先"文字变灰"更明确。
 */
@Composable
private fun ChapterStepButton(
    icon: ImageVector,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val contentColor = if (enabled) {
        MaterialTheme.colorScheme.onSurfaceVariant
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.32f)
    }
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(44.dp)) {
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(CircleShape)
                .background(
                    if (enabled) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = description,
                modifier = Modifier.size(20.dp),
                tint = contentColor
            )
        }
    }
}

/**
 * 底栏功能入口（矢量图标 + 文案，原 ll_catalog 等四联排结构）。
 *
 * 图标统一 Material 矢量 + 语义着色（替代原位图，符合全项目禁位图图标约定）。
 * 选中态用 `secondaryContainer` 胶囊底 + `onSecondaryContainer` 前景，
 * 与 M3 NavigationBar 的选中语义一致；未选中保持透明底，避免四个入口同时"亮"。
 *
 * 等宽分配由调用侧在 RowScope 内传 [Modifier]（weight 为作用域修饰符，
 * 不能在本函数内部直接使用）。
 */
@Composable
private fun ReaderBottomEntry(
    icon: ImageVector,
    label: String,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val contentColor = if (active) {
        MaterialTheme.colorScheme.onSecondaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Column(
        modifier = modifier
            .padding(3.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(
                if (active) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent
            )
            .clickable(onClick = onClick)
            .padding(vertical = 7.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            modifier = Modifier.size(22.dp),
            tint = contentColor
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = contentColor
        )
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
private fun ReaderSlider(
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
    val listState = rememberLazyListState()
    // 打开即定位当前章节（对齐原 scrollToPositionWithOffset(durChapter, 0)）
    LaunchedEffect(visible) {
        if (visible && durChapter in chapters.indices) {
            listState.scrollToItem(durChapter)
        }
    }

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
                // 抽屉遮罩：scrim 语义色（浅色作用域内即黑色系），对齐 M3 惯例，不硬编码 Color.Black
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
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = bookName,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        // 章节总数弱化为胶囊标签，避免与书名争夺视觉重心
                        InfoChip(
                            text = stringResource(R.string.chapter_count_format, chapters.size),
                            shape = RoundedCornerShape(50),
                            textStyle = MaterialTheme.typography.labelSmall,
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 3.dp)
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Outlined.Close,
                            contentDescription = stringResource(R.string.reader_close),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
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
                        itemsIndexed(chapters, key = { index, _ -> index }) { index, chapter ->
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
 */
@Composable
private fun ChapterRow(
    index: Int,
    name: String,
    isCurrent: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 1.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (isCurrent) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 11.dp),
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
 */
@Composable
private fun ReaderFastScroll(
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

/**
 * 面板统一标题行：左标题 + 可选右侧操作位。
 *
 * 三个面板（亮度/字体/设置）共用同一节奏，`dragHandle = null` 后标题即面板唯一的
 * 身份标识，字号与颜色在此收口，避免各面板自定 titleMedium/bodyLarge 造成层级漂移。
 */
@Composable
private fun SheetHeader(
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
private fun PanelSwitchRow(
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
 * 亮度面板（替代原 WindowLightPop）：
 * - 滑条实时写窗口亮度（仅"不跟随系统"时生效），两端低/高亮度图标锚定方向语义；
 *  滑条包在 [CommonCard] 内，与其他模块设置页的卡片分组语言一致（ADR-0006）
 * - 数值文本实时显示当前亮度百分比（跟随系统时显示"自动"），
 *   原面板只有滑条，"调到哪一档"只能靠肉眼观察屏幕变化
 * - 「跟随系统」开关从卡片外的裸行收进同一张卡（与开关行统一为图标行语言），
 *   开启后恢复 BRIGHTNESS_OVERRIDE_NONE；关闭立即应用当前手动亮度，
 *   避免"关闭后屏幕亮度不变、必须拖一下滑条才生效"的错位；
 *   Switch 本体显式接线（onCheckedChange 传回调），与整行点击共用 setFollowSys 入口，
 *   保证开关状态与窗口实际亮度同步变化（对齐原 Checkbox 修复逻辑）
 * - 关闭面板时持久化到 SP（键与原实现一致，升级无感）；
 *   重新进入阅读器由 [applyReaderBrightness] 恢复手动亮度（窗口亮度不跨生命周期）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LightPanel(activity: Activity, onDismiss: () -> Unit) {
    val preferences = activity.getSharedPreferences(LIGHT_SP_NAME, Context.MODE_PRIVATE)
    var followSys by remember { mutableStateOf(preferences.getBoolean(KEY_FOLLOW_SYS, true)) }
    // 单一事实源：滑条与持久化共用 light，避免镜像状态漂移（对齐原 WindowLightPop）
    var light by remember { mutableIntStateOf(preferences.getInt(KEY_LIGHT, getSystemBrightness(activity))) }

    // 切换"跟随系统"的唯一入口：勾选恢复系统亮度；取消勾选立即应用手动亮度，
    // 保证复选框状态与窗口实际亮度同步变化（不依赖滑条拖动）
    fun setFollowSys(follow: Boolean) {
        followSys = follow
        if (follow) {
            val params = activity.window.attributes
            params.screenBrightness =
                WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            activity.window.attributes = params
        } else {
            setWindowBrightness(activity, light)
        }
    }

    ModalBottomSheet(
        onDismissRequest = {
            // 对齐原 dismiss()：保存亮度配置
            preferences.edit {
                putInt(KEY_LIGHT, light)
                    .putBoolean(KEY_FOLLOW_SYS, followSys)
            }
            onDismiss()
        },
        // 面板自带标题且下滑/点遮罩/返回键均可关闭，默认拖动手柄横杠冗余，去掉
        dragHandle = null
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = CommonUiTokens.pagePadding)
                .padding(top = ReaderChromeTokens.sheetTopPadding)
        ) {
            SheetHeader(stringResource(R.string.luminance))
            Spacer(modifier = Modifier.height(CommonUiTokens.sectionSpacing))
            CommonCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 14.dp)
                ) {
                    // 数值行：手动态显示百分比（主色，强调"这是可调值"），跟随系统态显示"自动"
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = stringResource(R.string.reader_current_brightness),
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Text(
                            text = if (followSys) {
                                stringResource(R.string.brightness_auto)
                            } else {
                                stringResource(R.string.reader_brightness_percent_format, light)
                            },
                            style = MaterialTheme.typography.labelLarge,
                            color = if (followSys) MaterialTheme.colorScheme.onSurfaceVariant
                            else MaterialTheme.colorScheme.primary
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    // 亮度滑条：两端图标锚定低/高方向语义，禁用态（跟随系统）由滑条淡出表达
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Outlined.BrightnessLow,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        ReaderSlider(
                            value = light.toFloat(),
                            valueRange = 0f..255f,
                            enabled = !followSys,
                            onValueChange = { value ->
                                light = value.roundToInt()
                                setWindowBrightness(activity, light)
                            },
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 8.dp),
                            contentDescription = stringResource(R.string.luminance)
                        )
                        Icon(
                            imageVector = Icons.Outlined.BrightnessHigh,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 10.dp),
                        color = MaterialTheme.colorScheme.outlineVariant
                    )
                    // 跟随系统亮度：整行可点，Switch 本体与行点击共用同一切换入口（见类注释）
                    PanelSwitchRow(
                        icon = Icons.Outlined.Tune,
                        label = stringResource(R.string.follow_system_brightness),
                        description = stringResource(R.string.follow_system_brightness_desc),
                        checked = followSys,
                        onCheckedChange = { setFollowSys(it) }
                    )
                }
            }
            Spacer(modifier = Modifier.height(ReaderChromeTokens.sheetBottomPadding))
        }
    }
}

private const val LIGHT_SP_NAME = "CONFIG"
private const val KEY_LIGHT = "light"
private const val KEY_FOLLOW_SYS = "is_follow_sys"
private const val TAG = "ReaderPanels"

/** 读取系统亮度（0~255），读取失败兜底 0（对齐原 WindowLightPop.screenBrightness） */
private fun getSystemBrightness(context: Context): Int {
    return try {
        Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
    } catch (e: Settings.SettingNotFoundException) {
        Logger.w(TAG, "系统亮度设置不存在，兜底 0", e)
        0
    }
}

/**
 * 设置窗口亮度：把 0~255 的亮度值换算为 0~1 浮点写入 window attributes。
 *
 * 仅作用于当前 Activity 的窗口显示亮度（不持久化）；持久化由调用方（亮度面板）
 * 负责。对齐原 WindowLightPop 的窗口亮度设置方式。
 */
internal fun setWindowBrightness(activity: Activity, value: Int) {
    val params = activity.window.attributes
    params.screenBrightness = value / 255f
    activity.window.attributes = params
}

/**
 * 进入阅读器时恢复已持久化的手动亮度（仅"不跟随系统"时生效）。
 *
 * 窗口亮度（screenBrightness）只在本窗口生命周期内有效：用户设过手动亮度后，
 * 若不在此恢复，下次进入阅读器仍是系统亮度，造成"调了亮度但不生效"的观感缺陷。
 * "跟随系统"时窗口默认即 OVERRIDE_NONE，无需处理。
 */
internal fun applyReaderBrightness(activity: Activity) {
    val preferences = activity.getSharedPreferences(LIGHT_SP_NAME, Context.MODE_PRIVATE)
    if (!preferences.getBoolean(KEY_FOLLOW_SYS, true)) {
        setWindowBrightness(
            activity,
            preferences.getInt(KEY_LIGHT, getSystemBrightness(activity))
        )
    }
}

/**
 * 字体面板（替代原 FontPop）：字号档位 + 阅读主题四色选择。
 *
 * 视觉重构：
 * - 字号由「A- / 数值 / A+ / 默认」四件套改为 8 档等宽数字胶囊一排：原结构里
 *   "默认"按钮与步进按钮混在同一行，语义层级混乱，且改档位需逐次点击；一排档位
 *   既显示全量可选值又支持直达。恢复默认移到标题行右侧（次级动作降级到次级位置）
 * - 阅读主题由"纯色圆点"改为「底色纸片 + 正文色预览字 + 主题名」：圆点只表达背景色，
 *   读者真正要选的是"字与底的搭配"，所见即正文才是有效预览
 * - 选中描边由原硬编码 #F3B63F 改为 colorScheme.primary（浅色作用域内自动适配）
 *
 * 变更即时写 [ReadBookControl] 并经回调通知外部重分页/刷新状态栏色。
 * 字号档位仍走点选（不用滑条）：每次变更都要重排全文分页，拖动过程中的连续回调
 * 会触发同等次数的重分页，代价不可接受。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FontPanel(
    onTextChange: () -> Unit,
    onBgChange: () -> Unit,
    onDismiss: () -> Unit
) {
    // 字号/背景档位用面板本地状态镜像 ReadBookControl：
    // ReadBookControl 是普通单例（非 Compose 状态），本地状态才能驱动重组；
    // 每次变更同步写回 ReadBookControl（内存 + SP 持久化）
    var textKindIndex by remember { mutableIntStateOf(ReadBookControl.textKindIndex) }
    var textDrawableIndex by remember { mutableIntStateOf(ReadBookControl.textDrawableIndex) }
    val kindList = ReadBookControl.getTextKindList()
    val drawableList = ReadBookControl.getTextDrawableList()

    // dragHandle = null：同亮度面板，标题已表明用途、关闭途径齐全，去掉手柄横杠
    ModalBottomSheet(onDismissRequest = onDismiss, dragHandle = null) {
        Column(
            modifier = Modifier
                .padding(horizontal = CommonUiTokens.pagePadding)
                .padding(top = ReaderChromeTokens.sheetTopPadding)
        ) {
            SheetHeader(
                title = stringResource(R.string.font),
                // 恢复默认字号：已是默认档时禁用（避免"点了没反应"的错觉）
                trailing = {
                    TextButton(
                        onClick = {
                            ReadBookControl.updateTextKindIndex(ReadBookControl.DEFAULT_TEXT)
                            textKindIndex = ReadBookControl.textKindIndex
                            onTextChange()
                        },
                        enabled = textKindIndex != ReadBookControl.DEFAULT_TEXT
                    ) {
                        Text(stringResource(R.string.font_default))
                    }
                }
            )
            Spacer(modifier = Modifier.height(CommonUiTokens.sectionSpacing))
            SectionLabel(stringResource(R.string.font_size))
            CommonCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    kindList.forEachIndexed { index, kind ->
                        FontSizeChip(
                            label = kind.textSize.toString(),
                            selected = index == textKindIndex,
                            modifier = Modifier.weight(1f),
                        ) {
                            ReadBookControl.updateTextKindIndex(index)
                            textKindIndex = ReadBookControl.textKindIndex
                            onTextChange()
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(CommonUiTokens.sectionSpacing))
            SectionLabel(stringResource(R.string.reader_theme))
            CommonCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    drawableList.forEachIndexed { index, item ->
                        ReaderThemeSwatch(
                            item = item,
                            selected = index == textDrawableIndex,
                            modifier = Modifier.weight(1f),
                        ) {
                            ReadBookControl.updateTextDrawableIndex(index)
                            textDrawableIndex = ReadBookControl.textDrawableIndex
                            onBgChange()
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(ReaderChromeTokens.sheetBottomPadding))
        }
    }
}

/**
 * 字号档位胶囊：等宽排布，选中走主色底 + onPrimary 字。
 *
 * 高度固定 34dp，让八个数字形成一条明确的"刻度尺"，比原 3 按钮 + 1 数值更易读全貌。
 */
@Composable
private fun FontSizeChip(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .height(34.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surfaceVariant
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) MaterialTheme.colorScheme.onPrimary
            else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * 阅读主题色卡：以主题的背景色画一块"纸片"，纸片中央用主题正文色写预览字。
 *
 * 预览字与描边色都取自主题自身配色（[ReadBookControl.TextDrawable]），
 * 属于阅读背景主题的呈现，不受阅读器 chrome 固定浅色调板影响；
 * 未选中时 1dp 弱描边保证近白色主题（素白）在白底卡片上仍有可辨边界，
 * 选中时 2dp 主色描边 + 主题名转主色，双重编码避免只靠颜色表达状态。
 */
@Composable
private fun ReaderThemeSwatch(
    item: ReadBookControl.TextDrawable,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val background = Color(item.textBackground)
    val foreground = Color(item.textColor)
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(background)
                .border(
                    width = if (selected) 2.dp else 1.dp,
                    color = if (selected) MaterialTheme.colorScheme.primary
                    else foreground.copy(alpha = 0.22f),
                    shape = RoundedCornerShape(12.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = stringResource(R.string.theme_preview_text),
                color = foreground,
                fontSize = 18.sp,
                fontWeight = FontWeight.Medium
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = stringResource(item.labelRes),
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * 更多设置面板（替代原 MoreSettingPop）：按键翻页 / 点击翻页开关。
 *
 * 开关行收进 [CommonCard] 分组并补齐说明文案（对齐共享设计语言，ADR-0006）；
 * 行间分割线按本面板的图标列缩进（[ReaderChromeTokens.switchDividerIndent]），
 * 不用 CommonListDivider 的 64dp——本卡开关行的内边距基准与 CommonListItem 不同。
 *
 * @param onClickTurnChanged 点击翻页开关即时回调（面板开启期间正文不可点击，
 *   但 ReadBookScreen 需在切换瞬间同步本地 State 以消除"依赖 panel 变化才重组生效"的隐式耦合）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoreSettingPanel(onDismiss: () -> Unit, onClickTurnChanged: (Boolean) -> Unit) {
    var canKeyTurn by remember { mutableStateOf(ReadBookControl.canKeyTurn) }
    var canClickTurn by remember { mutableStateOf(ReadBookControl.canClickTurn) }
    // dragHandle = null：同亮度面板，标题已表明用途、关闭途径齐全，去掉手柄横杠
    ModalBottomSheet(onDismissRequest = onDismiss, dragHandle = null) {
        Column(
            modifier = Modifier
                .padding(horizontal = CommonUiTokens.pagePadding)
                .padding(top = ReaderChromeTokens.sheetTopPadding)
        ) {
            SheetHeader(stringResource(R.string.setting))
            Spacer(modifier = Modifier.height(CommonUiTokens.sectionSpacing))
            SectionLabel(stringResource(R.string.reader_section_turn))
            CommonCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                    PanelSwitchRow(
                        icon = Icons.AutoMirrored.Outlined.VolumeUp,
                        label = stringResource(R.string.volume_key_turn),
                        description = stringResource(R.string.volume_key_turn_desc),
                        checked = canKeyTurn,
                        onCheckedChange = {
                            canKeyTurn = it
                            ReadBookControl.setCanKeyTurn(it)
                        },
                        iconContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                        iconContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(start = ReaderChromeTokens.switchDividerIndent),
                        color = MaterialTheme.colorScheme.outlineVariant
                    )
                    PanelSwitchRow(
                        icon = Icons.Outlined.TouchApp,
                        label = stringResource(R.string.click_turn),
                        description = stringResource(R.string.click_turn_desc),
                        checked = canClickTurn,
                        onCheckedChange = {
                            canClickTurn = it
                            ReadBookControl.setCanClickTurn(it)
                            onClickTurnChanged(it)
                        },
                        iconContainerColor = MaterialTheme.colorScheme.tertiaryContainer,
                        iconContentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                }
            }
            Spacer(modifier = Modifier.height(ReaderChromeTokens.sheetBottomPadding))
        }
    }
}

/**
 * 章节多选下载面板（替代原 DownloadRangeDialog 的起止章号输入框）。
 *
 * 缓存感知：逐章按 [cachedUrls]（以 book_content 内容表为事实源，调用方经
 * BookRepository.getCachedChapterUrls 查询）绘制"已缓存"徽章；默认预勾选集合由调用方传入。
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
    cachedUrls: Set<String>,
    initialSelected: Set<Int>,
    onConfirm: (selected: Set<Int>) -> Unit,
    onDismiss: () -> Unit
) {
    var selected by remember { mutableStateOf(initialSelected) }

    // 缓存/未缓存索引集：列表打开期间不变，remember 避免每次勾选重算
    val cachedIndices = remember(chapters, cachedUrls) {
        chapters.indices.filterTo(mutableSetOf()) { chapters[it].durChapterUrl in cachedUrls }
    }
    val uncachedIndices = remember(chapters, cachedUrls) {
        chapters.indices.filterTo(mutableSetOf()) { chapters[it].durChapterUrl !in cachedUrls }
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

/**
 * 加入书架确认弹窗（替代原 CheckAddShelfPop，居中展示）。
 *
 * 主/次动作用不同按钮形态区分（加入书架 = 填充主按钮，退出 = 文本按钮），
 * 原实现两个 TextButton 并排，误触"退出"直接结束阅读的概率更高。
 */
@Composable
fun AddShelfDialog(
    bookName: String,
    onExit: () -> Unit,
    onAddShelf: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Outlined.BookmarkBorder,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        },
        text = { Text(stringResource(R.string.tv_pop_checkaddshelf, bookName)) },
        confirmButton = {
            Button(
                onClick = onAddShelf,
                shape = RoundedCornerShape(50)
            ) {
                Text(stringResource(R.string.add_to_shelf))
            }
        },
        dismissButton = {
            TextButton(onClick = onExit) {
                Text(stringResource(R.string.exit))
            }
        }
    )
}
