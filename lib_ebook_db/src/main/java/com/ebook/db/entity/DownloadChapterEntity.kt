package com.ebook.db.entity

import android.os.Parcelable
import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey
import kotlinx.parcelize.Parcelize

@Parcelize
@Entity(
    tableName = "download_chapter",
    indices = [
        Index(value = ["note_url"], name = "idx_download_chapter_note_url"),
        Index(value = ["dur_chapter_url"], name = "idx_download_chapter_dur_chapter_url", unique = true),
    ]
)
data class DownloadChapterEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    var id: Long = 0,
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
    @ColumnInfo(name = "dur_chapter_url")
    var durChapterUrl: String = String(),
    /**
     * 当前章节名称
     */
    @ColumnInfo(name = "dur_chapter_name")
    var durChapterName: String = String(),
    @ColumnInfo(name = "tag")
    var tag: String = String(),
    @ColumnInfo(name = "book_name")
    var bookName: String = String(),
    /**
     * 小说封面
     */
    @ColumnInfo(name = "cover_url")
    var coverUrl: String = String(),
    /**
     * 强制刷新标记：命中已有缓存也不跳过，先删旧内容再重新抓取。
     *
     * 阅读器下载入口下发的任务统一带上该标记（刷新缓存能力已合并进下载，
     * 勾中已缓存章节即等价重下，见 ReadBookActivity），对未缓存章节则为空操作。
     * v2 新增列，旧行默认 0（命中即跳过的语义不变）。
     */
    @ColumnInfo(name = "force_refresh")
    var forceRefresh: Boolean = false,
) : Parcelable
