package com.ebook.db.dao

import androidx.room3.*
import com.ebook.db.entity.BookShelfEntity
import com.ebook.db.entity.BookShelfFullInfo
import kotlinx.coroutines.flow.Flow

@Dao
interface BookShelfDao {
    @Transaction
    @Query("SELECT * FROM book_shelf ORDER BY final_date DESC")
    suspend fun getAllBooksFullInfo(): List<BookShelfFullInfo>

    /**
     * 观察书架全量数据（含书籍信息与章节列表），按最后阅读时间倒序。
     *
     * Flow 版本：Room 基于失效追踪自动推送，书架增删/进度更新时收集方自动刷新，
     * 供「我的」页阅读统计等响应式场景使用。
     */
    @Transaction
    @Query("SELECT * FROM book_shelf ORDER BY final_date DESC")
    fun getAllBooksFullInfoFlow(): Flow<List<BookShelfFullInfo>>

    @Transaction
    @Query("SELECT * FROM book_shelf WHERE note_url = :noteUrl")
    suspend fun getBookFullInfoByUrl(noteUrl: String): BookShelfFullInfo?

    @Query("SELECT * FROM book_shelf ORDER BY final_date DESC")
    fun getAllBooksFlow(): Flow<List<BookShelfEntity>>

    @Query("SELECT * FROM book_shelf ORDER BY final_date DESC")
    suspend fun getAllBooks(): List<BookShelfEntity>

    @Query("SELECT * FROM book_shelf WHERE note_url = :noteUrl")
    suspend fun getBookByUrl(noteUrl: String): BookShelfEntity?

    @Query("SELECT * FROM book_shelf WHERE note_url IN (:noteUrls)")
    suspend fun getBooksByUrls(noteUrls: List<String>): List<BookShelfEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(bookShelf: BookShelfEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(books: List<BookShelfEntity>)

    @Update
    suspend fun update(bookShelf: BookShelfEntity)

    @Delete
    suspend fun delete(bookShelf: BookShelfEntity)

    @Query("DELETE FROM book_shelf WHERE note_url = :noteUrl")
    suspend fun deleteByUrl(noteUrl: String)

    @Query("SELECT COUNT(*) FROM book_shelf")
    suspend fun getCount(): Int
}
