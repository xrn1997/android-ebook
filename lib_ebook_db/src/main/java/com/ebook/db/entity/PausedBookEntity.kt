package com.ebook.db.entity

import android.os.Parcelable
import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.PrimaryKey
import kotlinx.parcelize.Parcelize

/**
 * 按书暂停的标记行（表 paused_book，v8 新增，见 ADR-0036）：`note_url` 命中即该书暂停。
 *
 * 暂停语义：该书 `download_chapter` 里的排队任务**保留**，但服务取篇的遍历跳过该书；
 * 继续即删行。表是书级事实源，天然覆盖暂停期间新加的任务（行级标记做不到这一点，
 * 还会产生「同书部分行暂停、部分行不暂停」的混合态）。
 *
 * 键的设计（对齐 ADR-0003 的自然键策略）：主键就是 `note_url`（一本书最多一行标记），
 * REPLACE 即幂等重按。行与 `book_shelf` 的存续无外键约束——书被移出书架时
 * 暂停行暂留（与孤儿任务同一清理口径，见 DownloadRepository.deleteTasksOutsideShelf），
 * 取消本书/清空队列时由仓库层显式连带清理，否则用户为同一本书重新排队时会静默保持暂停。
 *
 * 与包内其他实体一致实现 [Parcelable]（虽只在仓库/VM 层内存传递，保持同层约定）。
 */
@Parcelize
@Entity(tableName = "paused_book")
data class PausedBookEntity(
    /** 书源侧根地址，与 `book_shelf` / `download_chapter` 的 note_url 同源；主键即它，存在行=该书暂停 */
    @PrimaryKey
    @ColumnInfo(name = "note_url")
    var noteUrl: String = String(),
) : Parcelable
