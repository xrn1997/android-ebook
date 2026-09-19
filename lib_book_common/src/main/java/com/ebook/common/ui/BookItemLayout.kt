package com.ebook.common.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 「三行书条目」这一族版式的共用词汇：列高、行距、封面比例、动作槽宽与中间行的行数算式。
 * 找书条目（搜索 / 分类选书）与书架条目用的是**同一档**，所以两边卡片同高、同分格。
 *
 * 等高的硬约束是「同一个列表内所有卡片等高」。预留行数取的是**两行**而不是三行：
 * 实测这两页的源大多不配 `intro`，中间那一格实际只有一句「第 N 章 …」，
 * 按三行预留就在每张卡里塞进 40~56dp 谁也没用的空白（等高于是变成了"等高且空"）。
 * 两行覆盖得住配了 `intro` 的源的首屏观感，长简介的全文在详情页看。
 */
object BookItemLayout {
    /**
     * 正文列定高：书名 20 + 行距 4 + 中间行两行 32 + 底行 16 = 72，再留 4dp 给
     * 字形自然行高与非线性字体缩放（见 [lineFitSlop] 那条理由）→ **76dp**，
     * 配上条目卡上下各 10dp 内边距是 96dp 卡高。
     */
    val columnHeight = 76.dp

    /** 书名 → 中间行的固定行距。它也参与行数算式，改动要同步传进 [bookItemLineCount] */
    val middleSpacing = 4.dp

    /** 条目右上角动作图标（如加书架）的槽宽：书名那一行为它让出这么多，其余两行通宽 */
    val actionSlot = 24.dp

    /**
     * 由正文列高推出封面宽，使封面恰好 3:4。
     *
     * 理由是 [BookCover] 用 `ContentScale.Crop` 按框裁切，比例对上就一边都不裁——不少站点
     * 把书名与作者印在封面底边，裁掉的正是那一条。列高与封面等高也让卡片没有多余空白。
     */
    fun coverWidth(columnHeight: Dp): Dp = columnHeight * 0.75f
}

/**
 * 中间区还能塞下几行文字：`行数 = ⌊(列高 − 书名行 − 底行 − 固定行距 − 余量) ÷ 本行行高⌋`。
 *
 * 为什么必须有这一步：Compose 的文本只有 `maxLines`（"最多几行"），**没有**"给这块高度、
 * 装得下几行就显示几行"的开关。不算是做不到等高的——写死 3 行时，系统字号放大后三行的自然高
 * 超过中间区，多出来的部分会画到框外压住底行（或 `clipToBounds` 切半字）。
 * 算出来则反过来：字号越大行数越少，卡片高度恒定。返回 0 表示这一格连一行都放不下，
 * 调用方应当整格不渲染。
 *
 * 为什么要减 [lineFitSlop] 而不是贴着边界除：算式里的行高是主题的 `lineHeight`，而文字真正占的
 * 行高还会被两件事抬高——CJK 字形的自然行高可高于所设的 `lineHeight`、Android 14+ 的字体缩放是
 * **非线性**的。115% 那一档贴着算会得到 2.97 行，0.03 行的余量扛不住这种偏差，一抬就多排一行、
 * 直接溢出到下面。宁可少排一行（空白集中到一处），也不要多排一行压住底行。
 *
 * @param spacing 书名与中间行之间那段**固定**行距（不传即视为 0），它也要从可用高度里扣掉——
 *   不扣就会多算一行、把底行顶出去，等高当场失效
 */
fun bookItemLineCount(
    columnHeight: Dp,
    nameLine: Dp,
    bottomLine: Dp,
    lineHeight: Dp,
    spacing: Dp = 0.dp,
): Int {
    if (lineHeight <= 0.dp) return 0
    val room = columnHeight - nameLine - bottomLine - spacing - lineFitSlop
    if (room <= 0.dp) return 0
    return (room / lineHeight).toInt().coerceAtLeast(0)
}

/** 除行数时预留的余量，用来吸收「主题设的行高」与「文字实际占的行高」之间的差 */
private val lineFitSlop = 1.dp

/**
 * [bookItemLineCount] 的装配版：把三个 `TextStyle` 的 `lineHeight`（Sp）换算成 Dp 再算。
 *
 * 换算必须走 `TextUnit.toDp()`：Android 上它经 `FontScaleConverter`，Android 14+ 是**非线性**
 * 字体缩放，自己乘 `fontScale` 会算多或算少一行；写死行高字面值（20/16/16）则会在主题或
 * token 变动后与真实行高脱钩——两种错都不崩，只是行数不对，要么切半行要么白留一格。
 *
 * @param columnHeight 该列表自己的正文列高（见 [BookItemLayout] 的说明）
 */
@Composable
fun bookItemLineCount(
    columnHeight: Dp,
    nameStyle: TextStyle,
    bottomStyle: TextStyle,
    bodyStyle: TextStyle,
    spacing: Dp = 0.dp,
): Int {
    val density = LocalDensity.current
    return bookItemLineCount(
        columnHeight = columnHeight,
        nameLine = with(density) { nameStyle.lineHeight.toDp() },
        bottomLine = with(density) { bottomStyle.lineHeight.toDp() },
        lineHeight = with(density) { bodyStyle.lineHeight.toDp() },
        spacing = spacing,
    )
}
