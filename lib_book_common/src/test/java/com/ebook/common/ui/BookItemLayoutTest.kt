package com.ebook.common.ui

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 「三行书条目」中间行行数的算式与封面比例（实现见 [bookItemLineCount]、[BookItemLayout.coverWidth]）。
 *
 * 卡片等高的全部代价押在这一步除法上：中间区是定值，而行高随系统字体缩放变化，所以行数只能算。
 * 这里按 Material 3 的行盒（书名 `titleSmall` 20、中间行 `bodySmall` 16、底行 `labelSmall` 16）
 * 乘以各档缩放跑一遍，钉住「100% 两行、放大后退到一行、连一行都放不下时返回 0」这条口径。
 * 其中一条直接用 [BookItemLayout] 的常量算——把测试与出厂档位绑在一起：改档而不同步预期就会红，
 * 而不是让线上出现切半行 / 画到框外。
 */
class BookItemLayoutTest {

    /** 按系统字体缩放档位列出行高：20/16/16 同比例放大（行距也同比例，它同样是 sp 语义的量） */
    private fun linesAt(column: Dp, scale: Float, spacing: Float) = bookItemLineCount(
        columnHeight = column,
        nameLine = (20 * scale).dp,
        bottomLine = (16 * scale).dp,
        lineHeight = (16 * scale).dp,
        spacing = (spacing * scale).dp,
    )

    private fun linesAt(column: Dp, scale: Float) =
        linesAt(column, scale, BookItemLayout.middleSpacing.value)

    @Test
    fun `出厂档位下系统默认字号放得下两行`() {
        assertEquals(2, linesAt(BookItemLayout.columnHeight, 1f))
    }

    @Test
    fun `字号放大到百分之一百一十五时退到一行`() {
        assertEquals(1, linesAt(BookItemLayout.columnHeight, 1.15f))
    }

    @Test
    fun `百分之一百三十时仍是一行`() {
        assertEquals(1, linesAt(BookItemLayout.columnHeight, 1.3f))
    }

    @Test
    fun `连一行都放不下时返回零由调用方整格不渲染`() {
        assertEquals(0, linesAt(BookItemLayout.columnHeight, 1.6f))
    }

    @Test
    fun `三行档要 96dp 列高才装得下`() {
        // 记下这条是因为两行档不是随手砍的：76dp 只够 2.19 行，想回到三行简介得把列高抬到 96dp，
        // 而那会在"源没配 intro"的页面上留下 40dp 的空白（实测就是这样被否掉的）
        assertEquals(3, linesAt(96.dp, 1f))
        assertEquals(2, linesAt(76.dp, 1f))
    }

    @Test
    fun `刚好超出整数行一点点时宁可少一行`() {
        // 68.5 - 20 - 16 = 32.5，除以 16 是 2.03 行：贴着算会放行两行，可那 0.5dp 的余量
        // 扛不住字形自然行高与非线性字体缩放，一抬就多排一行压住底行。扣掉 1dp 余量后判回一行
        assertEquals(
            1,
            bookItemLineCount(columnHeight = 68.5.dp, nameLine = 20.dp, bottomLine = 16.dp, lineHeight = 16.dp)
        )
    }

    @Test
    fun `行高未指定时返回零而不是除零`() {
        // TextStyle.lineHeight 没设时换算出来是 0.dp，直接除会得到 Infinity，转 Int 是未定义行为
        assertEquals(0, bookItemLineCount(76.dp, 20.dp, 16.dp, 0.dp))
    }

    @Test
    fun `上下两行已经吃掉整列时行数不为负`() {
        assertEquals(0, bookItemLineCount(76.dp, 40.dp, 40.dp, 16.dp))
    }

    @Test
    fun `封面宽按列高推出三比四`() {
        // 3:4 时 BookCover 的 Crop 一边都不裁。反向派生（给宽求高）随书城横卡的 101×123
        // 定档一并删掉——那张卡刻意不等比，留着就没有任何调用方，只会多一个"改了没人用"的出口
        assertEquals(57.dp, BookItemLayout.coverWidth(76.dp))
        assertEquals(72.dp, BookItemLayout.coverWidth(96.dp))
    }
}
