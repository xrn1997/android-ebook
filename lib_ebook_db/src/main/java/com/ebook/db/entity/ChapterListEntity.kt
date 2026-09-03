package com.ebook.db.entity

import android.os.Parcelable
import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.Ignore
import androidx.room3.Index
import androidx.room3.PrimaryKey
import kotlinx.parcelize.Parcelize

/**
 * 章节列表
 */
@Parcelize
@Entity(
    tableName = "chapter_list",
    indices = [
        Index(value = ["note_url"], name = "idx_chapter_list_note_url")
    ]
)
data class ChapterListEntity(
    /**
     * 对应BookInfo noteUrl;
     */
    @ColumnInfo(name = "note_url")
    var noteUrl: String = String(),
    /**
     * 当前章节数
     */
    @ColumnInfo(name = "dur_chapter_index")
    var durChapterIndex: Int = 0,
    /**
     * 当前章节对应的文章地址
     */
    @PrimaryKey
    @ColumnInfo(name = "dur_chapter_url")
    var durChapterUrl: String = String(),
    /**
     * 当前章节名称
     */
    @ColumnInfo(name = "dur_chapter_name")
    var durChapterName: String = String(),
    @ColumnInfo(name = "tag")
    var tag: String = String(),
    @ColumnInfo(name = "has_cache")
    var hasCache: Boolean = false,
    /**
     * 章节内容（不存入数据库，由 UI 层填充）
     */
    @Ignore
    var bookContent: BookContentEntity? = null,
) : Parcelable
