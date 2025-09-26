package com.ebook.db.entity

import android.os.Parcelable
import io.objectbox.annotation.Entity
import io.objectbox.annotation.Id
import io.objectbox.annotation.Unique
import kotlinx.parcelize.Parcelize

@Parcelize
@Entity
data class DownloadChapter(
    @Id var id: Long = 0,
    @JvmField
    var noteUrl: String = String(),
    /**
     * 当前章节数
     */
    @JvmField
    var durChapterIndex: Int = 0,
    /**
     * 当前章节对应的文章地址
     */
    @JvmField
    @Unique
    var durChapterUrl: String = String(),
    /**
     * 当前章节名称
     */
    @JvmField
    var durChapterName: String = String(),

    @JvmField
    var tag: String = String(),

    @JvmField
    var bookName: String = String(),
    /**
     * 小说封面
     */
    @JvmField
    var coverUrl: String = String(),

    ) : Parcelable
