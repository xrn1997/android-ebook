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
     * 本地书的格式名（`BookFormat` 枚举名），网络书为 null。与 [textCharset] 一起构成
     * 重解析所需的全部信息；路由 reader 也读它。
     */
    @ColumnInfo(name = "book_format")
    var bookFormat: String? = null,
    /**
     * 探测一次即固化的**源文件**编码。章文件本身统一 UTF-8，因此此列只在重解析时用
     * （spec §4 §7：旧实现每次导入都重头探测一遍全文件）。
     */
    @ColumnInfo(name = "text_charset")
    var textCharset: String? = null,
    /**
     * 主匹配名：算 `comment_key` 用，为空回落到 `book_info.name`。
     * 与显示名分开的理由见 spec §9.3——不分开就会出现"为了对上评论去改用户看到的书名"。
     */
    @ColumnInfo(name = "match_name")
    var matchName: String? = null,
    /** 匹配作者，为空回落到 `book_info.author` */
    @ColumnInfo(name = "match_author")
    var matchAuthor: String? = null,
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
