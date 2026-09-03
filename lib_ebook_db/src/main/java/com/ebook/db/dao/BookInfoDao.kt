package com.ebook.db.dao

import androidx.room3.*
import com.ebook.db.entity.BookInfoEntity

/**
 * 书籍信息表（book_info）访问器：一本书的元数据（书名、作者、封面、简介、连载状态等）。
 *
 * 与 book_shelf 一对一：主键就是自然键 `note_url`（网页书为书籍根地址、本地书为文件 MD5），
 * 两边靠它对齐。之所以拆两张表，是因为元数据来自书源详情抓取、进度由阅读器频繁改写，
 * 分开后保存进度只需重写书架行（见 ADR-0003 的自然键设计）。
 */
@Dao
interface BookInfoDao {
    /** 按 `note_url` 取书籍元数据；书架有记录但元数据缺失（孤立行）时返回 null */
    @Query("SELECT * FROM book_info WHERE note_url = :noteUrl")
    suspend fun getBookInfoByUrl(noteUrl: String): BookInfoEntity?

    /**
     * 按 `note_url` upsert 元数据：再次抓取详情时以最新一次为准，故用 REPLACE 覆盖整行。
     *
     * 实体上 `@Ignore` 的 chapterList 不落库，章节要另走 ChapterListDao.insertAll。
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(bookInfo: BookInfoEntity)

    /**
     * 按 URL 删除元数据。无外键级联，从书架移除时必须由调用方显式清理（见 BookRepository.removeFromShelf），
     * 否则会留下 book_shelf 已删、book_info 仍在的反向孤立行。
     */
    @Query("DELETE FROM book_info WHERE note_url = :noteUrl")
    suspend fun deleteByUrl(noteUrl: String)
}
