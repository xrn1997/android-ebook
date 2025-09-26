package com.ebook.api.entity

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class Comment(
    var id: Long = 0L,
    @JvmField
    var user: User = User(),
    var chapterUrl: String ?=null, //对应BookInfo noteUrl;
    @JvmField
    var chapterName: String ?=null, //当前章节名称
    @JvmField
    var bookName: String? = null,
    @JvmField
    var comment: String? = null,
    var addTime: String = ""
) : Parcelable