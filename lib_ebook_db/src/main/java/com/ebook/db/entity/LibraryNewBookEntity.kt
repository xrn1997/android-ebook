package com.ebook.db.entity

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * 书城新书推荐条目（承载模型）。
 *
 * 非数据库持久化实体，仅为书城新书推荐数据的内存传输模型。
 */
@Parcelize
data class LibraryNewBookEntity(
    var name: String,
    var url: String,
    var tag: String,
    var origin: String,
) : Parcelable
