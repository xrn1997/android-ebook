package com.ebook.db.entity

import android.os.Parcelable
import com.ebook.db.ObjectBoxManager
import io.objectbox.annotation.Backlink
import io.objectbox.annotation.Entity
import io.objectbox.annotation.Id
import io.objectbox.annotation.Unique
import io.objectbox.relation.ToMany
import kotlinx.parcelize.IgnoredOnParcel
import kotlinx.parcelize.Parcelize


/**
 * 书本信息
 */
@Parcelize
@Entity
data class BookInfo(
    /**
     * 小说名
     */
    var name: String = String(),
    var tag: String = String(),
    /**
     * 如果是来源网站   则小说根地址 /如果是本地  则是小说本地MD5
     */
    @Unique
    var noteUrl: String = String(),
    /**
     * 章节目录地址
     */
    var chapterUrl: String = String(),
    /**
     * 章节最后更新时间
     */
    @JvmField
    var finalRefreshData: Long = 0,
    /**
     * 小说封面
     */
    var coverUrl: String = String(),
    /**
     * 作者
     */
    var author: String = String(),
    /**
     * 简介
     */
    var introduce: String = String(),
    /**
     * 来源
     */
    var origin: String = String(),
    /**
     * 状态，连载or完结
     */
    var status: String = String(),
    @Id var id: Long = 0
) : Parcelable {

    /**
     * 章节列表
     */
    @IgnoredOnParcel
    @Backlink(to = "bookInfo")
    lateinit var chapterList: ToMany<ChapterList>

    fun clone(): BookInfo {
        val bookInfo = this.copy()
        //将对象与数据库进行关联，不然无法初始化ToMany。
        ObjectBoxManager.bookInfoBox.attach(bookInfo)
        bookInfo.chapterList = ToMany(bookInfo, BookInfo_.chapterList)

        //数据保存到数据库之前如果调用了clone，需要手动处理一下关系。
        if (this::chapterList.isInitialized && bookInfo.chapterList.isEmpty()) {
            for (chapter in chapterList) {
                bookInfo.chapterList.add(chapter.clone())
            }
        }
        return bookInfo
    }
}