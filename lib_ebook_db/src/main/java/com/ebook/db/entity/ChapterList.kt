package com.ebook.db.entity

import android.os.Parcelable
import com.ebook.db.ObjectBoxManager
import io.objectbox.annotation.Entity
import io.objectbox.annotation.Id
import io.objectbox.annotation.Unique
import io.objectbox.relation.ToOne
import kotlinx.parcelize.IgnoredOnParcel
import kotlinx.parcelize.Parcelize


/**
 * 章节列表
 */
@Parcelize
@Entity
data class ChapterList(
    /**
     * 对应BookInfo noteUrl;
     */
    var noteUrl: String = String(),
    /**
     * 当前章节数
     */
    @JvmField
    var durChapterIndex: Int = 0,
    /**
     * 当前章节对应的文章地址
     */
    @Unique
    var durChapterUrl: String = String(),
    /**
     * 当前章节名称
     */
    var durChapterName: String = String(),
    var tag: String = String(),
    var hasCache: Boolean = false,
    @Id var id: Long = 0
) : Parcelable {
    constructor(
        noteUrl: String,
        durChapterIndex: Int,
        durChapterUrl: String,
        durChapterName: String,
        tag: String,
        hasCache: Boolean
    ) : this() {
        this.noteUrl = noteUrl
        this.durChapterIndex = durChapterIndex
        this.durChapterUrl = durChapterUrl
        this.durChapterName = durChapterName
        this.tag = tag
        this.hasCache = hasCache
    }

    @IgnoredOnParcel
    lateinit var bookInfo: ToOne<BookInfo>

    @IgnoredOnParcel
    lateinit var bookContent: ToOne<BookContent>

    fun clone(): ChapterList {
        val chapterList = this.copy()
        ObjectBoxManager.chapterListBox.attach(chapterList)
        chapterList.bookContent = ToOne(chapterList, ChapterList_.bookContent)
        if (chapterList.bookContent.target == null) {
            if (this::bookContent.isInitialized && this.bookContent.target != null) {
                chapterList.bookContent.target = this.bookContent.target.clone()
            } else {
                // 尝试从数据库查找
                val bookContentFromDB = ObjectBoxManager.bookContentBox
                    .query(BookContent_.durChapterUrl.equal(this.durChapterUrl))
                    .build().findFirst()
                if (bookContentFromDB != null) {
                    chapterList.bookContent.target = bookContentFromDB
                }
            }
        }
        return chapterList
    }
}
