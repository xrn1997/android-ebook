package com.ebook.db.dao

import androidx.room3.*
import com.ebook.db.entity.ChapterListEntity

/**
 * 章节目录表（chapter_list）访问器：一本书抓下来的目录条目。
 *
 * 每行是「章序号 + 章节 URL + 章节名 + 书源归属标记 + 缓存标记」，主键为自然键
 * `dur_chapter_url`（一章一 URL，重复抓目录天然去重），`note_url` 上建索引支撑按书取全目录。
 * 目录页展示、阅读器的上下章跳转、下载面板的"已缓存"徽章与全书覆盖率都读这张表；
 * 正文本身在 book_content，本表只有 `has_cache` 这个轻量标记（可能滞后于真实入库，
 * 需要准确判定时查 BookContentDao.getExistingChapterUrls）。
 */
@Dao
interface ChapterListDao {
    /**
     * 某书完整目录，按章序号升序。
     *
     * 显式 ORDER BY 是必需的而非锦上添花：本表行可能被 REPLACE 先删后插而把 rowid 挪到表尾
     * （见 [updateHasCache] 的成因），书架侧的 `@Relation` 关联查询又无排序，
     * 只有这里按 durChapterIndex 排才拿得到稳定顺序。
     */
    @Query("SELECT * FROM chapter_list WHERE note_url = :bookNoteUrl ORDER BY dur_chapter_index ASC")
    suspend fun getChaptersForBook(bookNoteUrl: String): List<ChapterListEntity>

    /** 按章节 URL 取单章（主键直查）：阅读器由进度里的 URL 反查章名/序号时使用 */
    @Query("SELECT * FROM chapter_list WHERE dur_chapter_url = :chapterUrl")
    suspend fun getChapterByUrl(chapterUrl: String): ChapterListEntity?

    /**
     * 按 `dur_chapter_url` 批量 upsert 目录条目：再次抓目录时同一章节 URL 覆盖旧行，不会重复堆积。
     *
     * 注意 REPLACE 是整行替换且先删后插：入参的 `hasCache` 会原样落库（别拿内存里的旧快照
     * 覆盖已缓存标记），行 rowid 也会跳到表尾——依赖物理顺序的关联查询需自行排序。
     * 只改缓存标记请走 [updateHasCache]。
     */
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

    /**
     * 删除某本书的全部目录行。
     *
     * 只清目录：book_content 里的正文与 download_chapter 里的任务都不会被顺带删除，
     * 所以从书架移除时调用方要先取章节 URL 清正文（见 BookRepository.removeFromShelf），
     * 否则缓存章节会以无主 URL 的形态永久留在正文表里。
     */
    @Query("DELETE FROM chapter_list WHERE note_url = :bookNoteUrl")
    suspend fun deleteChaptersForBook(bookNoteUrl: String)
}
