package com.ebook.common.domain

import com.ebook.common.util.DateUtil
import com.ebook.common.util.DateUtil.FormatType

/**
 * 评论发表时间的**唯一**口径：把线上时间串映射成排序键与展示串两个派生量。
 *
 * 线上契约是服务端 Asia/Shanghai 的 `yyyy-MM-dd HH:mm:ss`
 * （见 [com.ebook.api.entity.Comment] 的字段说明）。收口前两个页面各写一份解析，
 * 已经分叉出两个用户可见的后果：
 * - 章节评论区按**分钟**解析排序，同一分钟内的两条评论拿到同一个键，
 *   顺序退化成服务端返回的顺序（`sortedByDescending` 稳定），刷新两次可能看到两样排法；
 * - 「我的评论」按秒解析排序，却把原串（带秒）直接显示，
 *   同一条评论在两个页面上是两种样子。
 *
 * 展示**不做时区换算**：服务端给的是墙钟文本，换算成设备时区会把 16:04 显示成 08:04，
 * 那是另一个 bug。解析与格式化都走同一默认时区，来回一趟原样保留。
 */
object CommentTime {

    /** 排序键（epoch 毫秒，精确到秒）。不可解析归 0，即在倒序列表里排到最后。 */
    fun sortMillis(addTime: String): Long =
        DateUtil.parseTime(addTime, FormatType.yyyyMMddHHmmss)?.time ?: 0L

    /** 展示串（`yyyy-MM-dd HH:mm`，裁到分）。不可解析给空串，让 UI 留白而不是显示脏值。 */
    fun displayText(addTime: String): String =
        DateUtil.formatDate(addTime, FormatType.yyyyMMddHHmmss, FormatType.yyyyMMddHHmm)
}
