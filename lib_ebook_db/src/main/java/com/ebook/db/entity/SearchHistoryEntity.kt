package com.ebook.db.entity

import android.os.Parcelable
import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.PrimaryKey
import kotlinx.parcelize.Parcelize

@Parcelize
@Entity(tableName = "search_history")
data class SearchHistoryEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    var id: Long = 0,
    @ColumnInfo(name = "type")
    var type: Int = 0,
    @ColumnInfo(name = "content")
    var content: String = String(),
    @ColumnInfo(name = "date")
    var date: Long = 0,
) : Parcelable {
    constructor(type: Int, content: String, date: Long) : this(
        id = 0,
        type = type,
        content = content,
        date = date,
    )
}
