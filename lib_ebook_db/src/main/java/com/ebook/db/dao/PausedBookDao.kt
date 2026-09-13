package com.ebook.db.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import com.ebook.db.entity.PausedBookEntity

/**
 * 按书暂停标记表（paused_book）访问器：行存在即该书暂停（见 ADR-0036）。
 *
 * 消费方是 `module_book` 的 DownloadRepository：
 * - **取篇跳过**：`getNextDownloadTask` / `findLatestDownloadTask` 的书架遍历循环里，
 *   先 [getAll] 取一次暂停集再逐书比对——暂停是队列策略（仓库层），不进任务表的 SQL；
 * - **暂停/继续**：[insert] / [delete]；
 * - **连带清理**：取消本书、清空队列、清孤儿行时同步删标记，残留标记会让用户
 *   为同一本书重新排队时静默保持暂停。
 */
@Dao
interface PausedBookDao {
    /** 暂停某书：主键即 note_url，REPLACE 保证重复点按幂等 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: PausedBookEntity)

    /** 继续某书（删掉暂停标记） */
    @Query("DELETE FROM paused_book WHERE note_url = :noteUrl")
    suspend fun delete(noteUrl: String)

    /** 全部暂停书的 note_url：仓库层取篇遍历与页面装载（一级分组/二级选章）各查一次即够，行数=暂停书数，量小 */
    @Query("SELECT note_url FROM paused_book")
    suspend fun getAll(): List<String>

    /** 清空全部暂停标记：随「清空队列/取消全部」连带清理（队列空了，标记留着只会误导下次排队） */
    @Query("DELETE FROM paused_book")
    suspend fun clearAll()
}
