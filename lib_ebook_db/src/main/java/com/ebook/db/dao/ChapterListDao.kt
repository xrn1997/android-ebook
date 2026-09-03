package com.ebook.db.dao

import androidx.room3.*
import com.ebook.db.entity.ChapterListEntity

@Dao
interface ChapterListDao {
    @Query("SELECT * FROM chapter_list WHERE note_url = :bookNoteUrl ORDER BY dur_chapter_index ASC")
    suspend fun getChaptersForBook(bookNoteUrl: String): List<ChapterListEntity>

    @Query("SELECT * FROM chapter_list WHERE dur_chapter_url = :chapterUrl")
    suspend fun getChapterByUrl(chapterUrl: String): ChapterListEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(chapters: List<ChapterListEntity>)

    /**
     * 仅更新章节缓存标记。
     *
     * 不能用 insertAll(REPLACE) 代替：SQLite 的 REPLACE 是先删后插，重插行的 rowid
     * 会跳到表尾；而书架章节关联查询（@Relation）无 ORDER BY、按 rowid 返回，
     * 导致被更新过的章节在目录中排到最后（错序缺陷）。UPDATE 原地改写不动 rowid。
     */
    @Query("UPDATE chapter_list SET has_cache = :hasCache WHERE dur_chapter_url = :chapterUrl")
    suspend fun updateHasCache(chapterUrl: String, hasCache: Boolean)

    /** 某书总章节数（下载管理页"全书缓存覆盖率"的分母） */
    @Query("SELECT COUNT(*) FROM chapter_list WHERE note_url = :bookNoteUrl")
    suspend fun countChaptersForBook(bookNoteUrl: String): Int

    /** 某书已缓存章节数（下载管理页"全书缓存覆盖率"的分子） */
    @Query("SELECT COUNT(*) FROM chapter_list WHERE note_url = :bookNoteUrl AND has_cache = 1")
    suspend fun countCachedChaptersForBook(bookNoteUrl: String): Int

    @Query("DELETE FROM chapter_list WHERE note_url = :bookNoteUrl")
    suspend fun deleteChaptersForBook(bookNoteUrl: String)
}
