package com.ebook.db.entity

import android.os.Parcelable
import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.Ignore
import androidx.room3.PrimaryKey
import kotlinx.parcelize.Parcelize

/**
 * 书本缓存内容
 */
@Parcelize
@Entity(tableName = "book_content")
data class BookContentEntity(
    /**
     * 对应BookInfo noteUrl;
     */
    @PrimaryKey
    @ColumnInfo(name = "dur_chapter_url")
    var durChapterUrl: String = String(),

    /**
     * 当前章节  （包括番外）
     */
    @ColumnInfo(name = "dur_chapter_index")
    var durChapterIndex: Int = 0,

    /**
     * 当前章节内容
     */
    @ColumnInfo(name = "dur_chapter_content")
    var durChapterContent: String = String(),

    /**
     * 来源  某个网站/本地
     */
    @ColumnInfo(name = "tag")
    var tag: String = String(),
    /**
     * 是否解析成功（不存入数据库，由 UI 层填充）
     */
    @Ignore
    var right: Boolean = true,
) : Parcelable
