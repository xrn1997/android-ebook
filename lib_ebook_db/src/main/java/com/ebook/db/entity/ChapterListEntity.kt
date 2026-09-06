package com.ebook.db.entity

import android.os.Parcelable
import androidx.room3.ColumnInfo
import androidx.room3.Entity
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
     * 内容定位符：本地书是私有目录里的相对路径（`books/<bookId>/c00042.txt`），网络书是
     * 该站章节 URL。主键仍是自然键——一章一定位符（见 ADR-0003）。
     *
     * 原名 `dur_chapter_url`。改名能成立的前提是评论聚合键已从章节 URL 换成 `comment_key`
     * （spec §9）：此前这个字段同时背着"书源章节 URL"与"评论关联键"两个身份，谁也动不了它。
     */
    @PrimaryKey
    @ColumnInfo(name = "content_ref")
    var contentRef: String = String(),
    /**
     * 当前章节名称
     */
    @ColumnInfo(name = "dur_chapter_name")
    var durChapterName: String = String(),
    @ColumnInfo(name = "tag")
    var tag: String = String(),
) : Parcelable
