package com.ebook.db.entity

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * 搜索结果书籍承载模型（`tag` 为书源归属 URL，`origin` 为书源名，`add` 标记是否已加入书架）。
 *
 * 非数据库持久化实体，仅为搜索/书城结果在内存中传递的传输模型；加入书架时据此构建 [BookShelfEntity]。
 */
@Parcelize
data class SearchBookEntity(
    var noteUrl: String = String(),
    var coverUrl: String = String(),
    var name: String = String(),
    var author: String = String(),
    var words: Long = 0,
    var state: String = String(),
    var lastChapter: String = String(),
    var add: Boolean = false,
    var tag: String = String(),
    var kind: String = String(),
    var origin: String = String(),
    var desc: String = String(),
) : Parcelable
