package com.ebook.book.reader

import androidx.compose.ui.unit.dp
import com.ebook.common.ui.CommonUiTokens

/**
 * 阅读器 chrome 层局部设计常量。
 *
 * 顶/底栏与三类面板共用同一套节奏（圆角/图标块/缩进），集中定义避免散落的魔法值；
 * 跨模块通用的间距/圆角仍一律走 [CommonUiTokens]，此处只放阅读器专属值。
 */
internal object ReaderChromeTokens {
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
