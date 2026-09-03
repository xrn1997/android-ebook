package com.ebook.db.entity

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * 本地导入书籍的书架承载模型（`new` 标记是否为新导入）。
 *
 * 非数据库持久化实体，仅为本地书导入链路在内存中传递书架条目的传输模型。
 */
@Parcelize
data class LocBookShelfEntity(
    var new: Boolean,
    var bookShelf: BookShelfEntity,
) : Parcelable
