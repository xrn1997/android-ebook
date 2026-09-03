package com.ebook.db.entity

import android.os.Parcelable
import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.Ignore
import androidx.room3.PrimaryKey
import kotlinx.parcelize.Parcelize

/**
 * 书本信息
 */
@Parcelize
@Entity(tableName = "book_info")
data class BookInfoEntity(
    /**
     * 小说名
     */
    @ColumnInfo(name = "name")
    var name: String = String(),
    @ColumnInfo(name = "tag")
    var tag: String = String(),
    /**
     * 如果是来源网站   则小说根地址 /如果是本地  则是小说本地MD5
     */
    @PrimaryKey
    @ColumnInfo(name = "note_url")
    var noteUrl: String = String(),
    /**
     * 章节目录地址
     */
    @ColumnInfo(name = "chapter_url")
    var chapterUrl: String = String(),
    /**
     * 章节最后更新时间
     */
    @ColumnInfo(name = "final_refresh_data")
    var finalRefreshData: Long = 0,
    /**
     * 小说封面
     */
    @ColumnInfo(name = "cover_url")
    var coverUrl: String = String(),
    /**
     * 作者
     */
    @ColumnInfo(name = "author")
    var author: String = String(),
    /**
     * 简介
     */
    @ColumnInfo(name = "introduce")
    var introduce: String = String(),
    /**
     * 来源
     */
    @ColumnInfo(name = "origin")
    var origin: String = String(),
    /**
     * 状态，连载or完结
     */
    @ColumnInfo(name = "status")
    var status: String = String(),
    /**
     * 章节列表（不存入数据库，由 UI 层填充）
     */
    @Ignore
    var chapterList: List<ChapterListEntity> = emptyList(),
) : Parcelable
