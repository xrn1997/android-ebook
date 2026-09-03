package com.ebook.db.entity

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * 书源正文解析结果承载模型（章节 URL + 正文文本）。
 *
 * 非数据库持久化实体，仅为书源正文解析在内存中传递结果的传输模型。
 */
@Parcelize
data class WebContentEntity(
    var url: String,
    var content: String,
) : Parcelable
