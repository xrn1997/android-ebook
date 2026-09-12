package com.ebook.db.entity

import android.os.Parcelable
import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey
import kotlinx.parcelize.Parcelize

/**
 * 作品分组关联行（spec §3 §9.2）：把"一个来源条目"与"一个评论桶键"绑起来。
 *
 * 刻意没有 `works` 表、也没有 `work_id` 这类标识符——**"作品"只以 `comment_key` 这个不透明
 * token 存在**。后端不得存书籍数据；客户端这边也不给"作品"配一个看起来像注册表主键的东西，
 * 免得将来有人以为服务端能解释它。
 *
 * 一个 `note_url` 可有任意多行（读评论取全部键的并集），其中恰好一行 [isPrimary]（写评论
 * 只用它）。"恰好一行"SQLite 与 Room 都表达不了（无部分唯一索引），由调用方在
 * `withWriteTransaction` 内保证。
 */
@Parcelize
@Entity(
    tableName = "book_group",
    primaryKeys = ["comment_key", "note_url"],
    indices = [Index(value = ["note_url"], name = "idx_book_group_note_url")]
)
data class BookGroupEntity(
    /** 客户端派生的不透明评论桶键，形如 `ck1:<64 hex>`（见 CommentKey） */
    @ColumnInfo(name = "comment_key")
    var commentKey: String = String(),
    /** 来源条目，等于 `book_shelf.note_url` */
    @ColumnInfo(name = "note_url")
    var noteUrl: String = String(),
    /** 写评论时使用的键所在行 */
    @ColumnInfo(name = "is_primary")
    var isPrimary: Boolean = false,
) : Parcelable
