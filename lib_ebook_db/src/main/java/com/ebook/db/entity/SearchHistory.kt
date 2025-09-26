package com.ebook.db.entity

import android.os.Parcelable
import io.objectbox.annotation.Entity
import io.objectbox.annotation.Id
import kotlinx.parcelize.Parcelize

@Parcelize
@Entity
data class SearchHistory(
    var type: Int = 0,
    @JvmField
    var content: String = String(),
    @JvmField
    var date: Long = 0,
    @Id var id: Long = 0
) : Parcelable {
    constructor(type: Int, content: String, data: Long) : this(
        type = type,
        content = content,
        date = data
    )
}