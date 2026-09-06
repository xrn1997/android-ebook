package com.ebook.common.domain

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 评论发表时间的口径测试——同一个 `add_time` 在两个页面既被排序又被展示，
 * 收口前两处各写一份，且已经分叉出两个用户可见的后果。
 *
 * 线上契约是服务端 Asia/Shanghai 的 `yyyy-MM-dd HH:mm:ss`：
 * - **排序键必须到秒**：章节评论区原先按 `yyyy-MM-dd HH:mm` 解析，同一分钟内的两条评论
 *   拿到同一个 key，顺序退化成服务端返回的顺序（稳定排序），用户刷新两次看到两样的排法；
 * - **展示串到分、不带秒**：两个页面此前一个显示原串（带秒）一个显示裁到分，
 *   同一条评论在「我的评论」和「章节评论区」长得不一样的两行时间。
 *   展示只裁格式、**不做时区换算**——服务端给的就是墙钟文本，按设备时区换算会把
 *   「16:04」变成「08:04」，那是另一个 bug。
 */
class CommentTimeTest {

    @Test
    fun `排序键精确到秒，同一分钟内的两条评论能分出先后`() {
        val earlier = CommentTime.sortMillis("2026-09-06 16:04:10")
        val later = CommentTime.sortMillis("2026-09-06 16:04:56")

        assertEquals("同一分钟内相差 46 秒必须是两个不同的键", true, later > earlier)
    }

    @Test
    fun `展示串裁到分钟，不显示秒`() {
        assertEquals("2026-09-06 16:04", CommentTime.displayText("2026-09-06 16:04:56"))
    }

    @Test
    fun `展示不做时区换算，服务端墙钟文本原样保留`() {
        // 输入与输出的时分必须一致：换算只在 sortMillis 的 Date 里发生，展示再按同一默认时区格式化回来
        assertEquals("2026-01-01 00:00", CommentTime.displayText("2026-01-01 00:00:00"))
    }

    @Test
    fun `时间串不可解析时展示空串、排序键归零而不抛异常`() {
        assertEquals("", CommentTime.displayText(""))
        assertEquals("", CommentTime.displayText("不是时间"))
        assertEquals(0L, CommentTime.sortMillis("不是时间"))
    }
}
