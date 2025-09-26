package com.ebook.db.entity

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class SearchBook(
    @JvmField
    var noteUrl: String = String(),
    @JvmField
    var coverUrl: String = String(),
    @JvmField
    var name: String = String(),
    @JvmField
    var author: String = String(),
    @JvmField
    var words: Long = 0,
    @JvmField
    var state: String = String(),
    @JvmField
    var lastChapter: String = String(),
    var add: Boolean = false,
    @JvmField
    var tag: String = String(),
    @JvmField
    var kind: String = String(),
    @JvmField
    var origin: String = String(),
    @JvmField
    var desc: String = String()
) : Parcelable