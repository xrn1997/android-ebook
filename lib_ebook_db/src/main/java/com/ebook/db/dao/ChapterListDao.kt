package com.ebook.db.dao

import androidx.room3.*
import com.ebook.db.entity.ChapterListEntity

/**
 * 章节目录表（chapter_list）访问器：一本书抓下来的目录条目。
 *
 * 每行是「章序号 + 章节定位符 + 章节名 + 书源归属标记」，主键为自然键 `content_ref`
 * （一章一定位符，重复抓目录天然去重），`note_url` 上建索引支撑按书取全目录。
 * 目录页展示、阅读器的上下章跳转都读这张表；缓存存在性改由 BookStore 章文件判定。
 */
@Dao
interface ChapterListDao {
    /**
     * 某书完整目录，按章序号升序。
     *
     * 显式 ORDER BY 是必需的：书架侧的 `@Relation` 关联查询无排序，
     * 只有这里按 durChapterIndex 排才拿得到稳定顺序。
     */
    @Query("SELECT * FROM chapter_list WHERE note_url = :bookNoteUrl ORDER BY dur_chapter_index ASC")
    suspend fun getChaptersForBook(bookNoteUrl: String): List<ChapterListEntity>

    /** 按内容定位符取单章（主键直查）：阅读器由进度里的 URL 反查章名/序号时使用 */
    @Query("SELECT * FROM chapter_list WHERE content_ref = :chapterUrl")
    suspend fun getChapterByUrl(chapterUrl: String): ChapterListEntity?

    /**
     * 按 `content_ref` 批量 upsert 目录条目：再次抓目录时同一章节定位符覆盖旧行，不会重复堆积。
     *
     * 注意 REPLACE 是整行替换且先删后插：行 rowid 会跳到表尾——依赖物理顺序的关联查询需自行排序。
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(chapters: List<ChapterListEntity>)

    /** 某书总章节数 */
    @Query("SELECT COUNT(*) FROM chapter_list WHERE note_url = :bookNoteUrl")
    suspend fun countChaptersForBook(bookNoteUrl: String): Int

    /**
     * 删除某本书的全部目录行。
     *
     * 只清目录：章文件与 download_chapter 里的任务都不会被顺带删除，
     * 所以从书架移除时调用方要先清章文件（见 BookRepository.removeFromShelf）。
     */
    @Query("DELETE FROM chapter_list WHERE note_url = :bookNoteUrl")
    suspend fun deleteChaptersForBook(bookNoteUrl: String)
}
