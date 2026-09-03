package com.ebook.db.dao

import androidx.room3.*
import com.ebook.db.entity.BookContentEntity

@Dao
interface BookContentDao {
    @Query("SELECT * FROM book_content WHERE dur_chapter_url = :chapterUrl")
    suspend fun getContentByChapterUrl(chapterUrl: String): BookContentEntity?

    /**
     * 批量查询已存在正文内容的章节 URL。
     *
     * 供下载面板绘制"已缓存"徽章：以内容表为事实源（而非 ChapterListEntity.hasCache
     * 内存快照，后者在阅读器内存列表中可能滞后于阅读/下载的实际入库）。
     */
    @Query("SELECT dur_chapter_url FROM book_content WHERE dur_chapter_url IN (:chapterUrls)")
    suspend fun getExistingChapterUrls(chapterUrls: List<String>): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(content: BookContentEntity)

    @Query("DELETE FROM book_content WHERE dur_chapter_url = :chapterUrl")
    suspend fun deleteByChapterUrl(chapterUrl: String)

    @Query("DELETE FROM book_content WHERE dur_chapter_url IN (:chapterUrls)")
    suspend fun deleteByChapterUrls(chapterUrls: List<String>)
}
