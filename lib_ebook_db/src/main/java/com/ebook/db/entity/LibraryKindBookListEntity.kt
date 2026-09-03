package com.ebook.db.entity

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * 书城书籍分类推荐列表（承载模型）。
 *
 * 非数据库持久化实体，仅为 [LibraryEntity] 内分类分组数据的内存传输模型。
 */
@Parcelize
data class LibraryKindBookListEntity(
    var kindName: String = String(),
    var kindUrl: String = String(),
    var books: List<SearchBookEntity> = mutableListOf(),
) : Parcelable
