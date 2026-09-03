package com.ebook.db.entity

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * 阅读正文承载模型（章节正文列表 + 当前页索引）。
 *
 * 非数据库持久化实体，仅为阅读器在内存中传递已分页正文的传输模型。
 */
@Parcelize
data class ReadBookContentEntity(
    var bookContentList: List<BookContentEntity>,
    var pageIndex: Int,
) : Parcelable
