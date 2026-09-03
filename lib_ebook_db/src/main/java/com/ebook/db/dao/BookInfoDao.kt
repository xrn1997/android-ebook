package com.ebook.db.dao

import androidx.room3.*
import com.ebook.db.entity.BookInfoEntity

@Dao
interface BookInfoDao {
    @Query("SELECT * FROM book_info WHERE note_url = :noteUrl")
    suspend fun getBookInfoByUrl(noteUrl: String): BookInfoEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(bookInfo: BookInfoEntity)

    @Query("DELETE FROM book_info WHERE note_url = :noteUrl")
    suspend fun deleteByUrl(noteUrl: String)
}
