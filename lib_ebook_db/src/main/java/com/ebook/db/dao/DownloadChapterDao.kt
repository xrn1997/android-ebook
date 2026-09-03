package com.ebook.db.dao

import androidx.room3.*
import com.ebook.db.entity.DownloadChapterEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface DownloadChapterDao {
    @Query("SELECT * FROM download_chapter WHERE dur_chapter_url = :chapterUrl")
    suspend fun getChapterByUrl(chapterUrl: String): DownloadChapterEntity?

    @Query("SELECT * FROM download_chapter WHERE note_url = :noteUrl ORDER BY dur_chapter_index ASC LIMIT 1")
    suspend fun getFirstByNoteUrl(noteUrl: String): DownloadChapterEntity?

    @Query("SELECT * FROM download_chapter WHERE note_url = :noteUrl ORDER BY dur_chapter_index DESC LIMIT 1")
    suspend fun getLastByNoteUrl(noteUrl: String): DownloadChapterEntity?

    @Query("SELECT * FROM download_chapter ORDER BY dur_chapter_index ASC LIMIT 1")
    suspend fun getFirst(): DownloadChapterEntity?

    /**
     * 全部待下载任务（按书名再按章节升序，供下载管理页按书分组）。
     *
     * 队列表体量小（未完成任务），一次性取全在 Kotlin 侧分组即可，
     * 不必为分组引入聚合 SQL。
     */
    @Query("SELECT * FROM download_chapter ORDER BY note_url ASC, dur_chapter_index ASC")
    suspend fun getAllTasks(): List<DownloadChapterEntity>

    /** 删除某本书的全部待下载任务（下载管理页"取消本书"） */
    @Query("DELETE FROM download_chapter WHERE note_url = :noteUrl")
    suspend fun deleteByNoteUrl(noteUrl: String)

    /**
     * 队列剩余数（表内只存未完成任务，故等价"剩余章数"）。
     *
     * 供下载通知展示进度总量。
     */
    @Query("SELECT COUNT(*) FROM download_chapter")
    suspend fun count(): Int

    /**
     * 队列剩余数的响应式观察（书架下载图标角标）。
     *
     * Room Flow：任务增/删时自动重推新值，角标无需手动刷新；
     * 无任务时推 0，角标自然隐藏。
     */
    @Query("SELECT COUNT(*) FROM download_chapter")
    fun observeRemainingCount(): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(chapter: DownloadChapterEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(chapters: List<DownloadChapterEntity>)

    @Delete
    suspend fun delete(chapter: DownloadChapterEntity)

    @Query("DELETE FROM download_chapter")
    suspend fun clearAll()
}
