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
     * 只写 `final_refresh_data` 一列（章节最后更新时间）。
     *
     * 为什么不用现成的 [insert]：那是整行 `OnConflictStrategy.REPLACE`，要求调用方传一个
     * 字段完整的对象。拿它写时间戳就要先读回整行、改一个字段、再写回去 ——
     * 中间漏读或漏填任何一个字段（书名、封面、简介…）就会把那列静默抹成实体默认值，
     * 页面只是少显示几项、不崩不报错，属最难发现的一类数据损坏。
     * 定向 UPDATE 让「改一列」在 SQL 层面就只碰那一列。
     *
     * 主要消费方是目录重抓的限频（见
     * `com.ebook.common.repository.BookRepository.syncChaptersFromSource`），
     * 语义是「上次得出结论的时间」而非「上次成功追加的时间」。
     * 行不存在时静默不写（不抛），调用方不需要先确认存在。
     */
    @Query("UPDATE book_info SET final_refresh_data = :timestamp WHERE note_url = :noteUrl")
    suspend fun setFinalRefreshData(noteUrl: String, timestamp: Long)

    /**
     * 按 URL 删除元数据。无外键级联，从书架移除时必须由调用方显式清理（见 BookRepository.removeFromShelf），
     * 否则会留下 book_shelf 已删、book_info 仍在的反向孤立行。
     */
    @Query("DELETE FROM book_info WHERE note_url = :noteUrl")
    suspend fun deleteByUrl(noteUrl: String)
}
