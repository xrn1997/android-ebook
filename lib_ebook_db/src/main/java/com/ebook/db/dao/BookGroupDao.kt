package com.ebook.db.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import com.ebook.db.entity.BookGroupEntity

/**
 * 作品分组关联表访问器。
 *
 * M1a 写入与随删；M2 新增合并/拆分/切主键操作。"恰好一行 is_primary"由调用方在
 * `withWriteTransaction` 内保证（SQLite 无部分唯一索引）。
 */
@Dao
interface BookGroupDao {

    /** 按 (comment_key, note_url) upsert 一行关联 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(row: BookGroupEntity)

    /** 删除某条来源的全部关联行：从书架移除书时随之清理 */
    @Query("DELETE FROM book_group WHERE note_url = :noteUrl")
    suspend fun deleteFor(noteUrl: String)

    /** 查出某条来源关联的全部 comment_key（M2：并集读评论，跨源聚合） */
    @Query("SELECT comment_key FROM book_group WHERE note_url = :noteUrl")
    suspend fun getKeysForNoteUrl(noteUrl: String): List<String>

    /** 取某条来源当前的主键（is_primary = true 那行的 comment_key），无行返回 null */
    @Query("SELECT comment_key FROM book_group WHERE note_url = :noteUrl AND is_primary = 1 LIMIT 1")
    suspend fun getPrimaryForNoteUrl(noteUrl: String): String?

    /**
     * 取全表的主键行（每本书一行）。
     *
     * 导入前判重要拿「待导入作品的键 vs 书架所有条目的当前主键」，逐本调
     * [getPrimaryForNoteUrl] 会是 N+1 次查询；一次捞出来在内存里比。
     */
    @Query("SELECT * FROM book_group WHERE is_primary = 1")
    suspend fun getPrimaryRows(): List<BookGroupEntity>

    /** 取某条来源的全部关联行（含 isPrimary 标记），供修键面板展示 */
    @Query("SELECT * FROM book_group WHERE note_url = :noteUrl")
    suspend fun getAllForNoteUrl(noteUrl: String): List<BookGroupEntity>

    /** 删除某条来源的特定关联行（拆分操作：只删一行，其余不动） */
    @Query("DELETE FROM book_group WHERE note_url = :noteUrl AND comment_key = :commentKey")
    suspend fun deleteSpecific(noteUrl: String, commentKey: String)

    /**
     * 切主键第一步：把 noteUrl 下所有行的 is_primary 清零。
     *
     * 两步操作必须在调用方的 `withWriteTransaction` 内执行，保证"恰好一行 primary"。
     */
    @Query("UPDATE book_group SET is_primary = 0 WHERE note_url = :noteUrl")
    suspend fun clearPrimary(noteUrl: String)

    /** 切主键第二步：把目标行设为 primary */
    @Query("UPDATE book_group SET is_primary = 1 WHERE note_url = :noteUrl AND comment_key = :commentKey")
    suspend fun promotePrimary(noteUrl: String, commentKey: String)

    /** 添加非主键关联行（合并操作：把另一个键加到当前来源的并集里） */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addSecondary(row: BookGroupEntity)
}
