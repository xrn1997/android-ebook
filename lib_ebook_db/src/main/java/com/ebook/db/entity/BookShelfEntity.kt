package com.ebook.db.entity

import android.os.Parcelable
import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.Ignore
import androidx.room3.PrimaryKey
import com.ebook.db.event.DBCode
import kotlinx.parcelize.Parcelize

/**
 * 书架item
 */
@Parcelize
@Entity(tableName = "book_shelf")
data class BookShelfEntity(
    /**
     * 对应BookInfo noteUrl;
     */
    @PrimaryKey
    @ColumnInfo(name = "note_url")
    var noteUrl: String = String(),
    /**
     * 当前章节 （包括番外）
     */
    @ColumnInfo(name = "dur_chapter")
    var durChapter: Int = 0,
    @ColumnInfo(name = "dur_chapter_page")
    var durChapterPage: Int = DBCode.BookContentView.DUR_PAGE_INDEX_BEGIN,
    /**
     * 最后阅读时间
     */
    @ColumnInfo(name = "final_date")
    var finalDate: Long = 0,
    @ColumnInfo(name = "tag")
    var tag: String = String(),
    /**
     * 书籍信息（不存入数据库，由 UI 层填充）
     */
    @Ignore
    var bookInfo: BookInfoEntity? = null,
    /**
     * 章节列表（不存入数据库，由 UI 层填充）
     */
    @Ignore
    var chapterList: List<ChapterListEntity> = emptyList(),
) : Parcelable {
    companion object {
        /**
         * 更新时间间隔 至少
         */
        const val REFRESH_TIME: Long = (5 * 60 * 1000).toLong()
        const val LOCAL_TAG: String = "loc_book"
    }
}
