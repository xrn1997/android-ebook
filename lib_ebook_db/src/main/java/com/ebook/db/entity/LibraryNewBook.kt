package com.ebook.db.entity

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class LibraryNewBook(
    var name: String,
    var url: String,
    var tag: String,
    var origin: String
) : Parcelable
