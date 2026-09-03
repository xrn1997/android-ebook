package com.ebook.db.dao

import androidx.room3.*
import com.ebook.db.entity.BookContentEntity

/**
 * 章节缓存表（book_content）访问器：已落到本地的章节正文（CONTEXT.md 所称「章节缓存」）。
 *
 * 一行一章，主键为自然键 `dur_chapter_url`（见 ADR-0003），因此本表**同时是缓存存在性的
 * 事实源**：某章 URL 有行即已缓存。chapter_list.has_cache 只是目录侧的加速标记，
 * 两者可以不一致，判定时以本表为准。
 *
 * 写入方是阅读器（读到即缓存）与离线下载服务（逐章抓取后入库），
 * 正文按章独立存储、不随书成批，故删除也只能按章节 URL 逐条/批量清。
 */
@Dao
interface BookContentDao {
    /** 取某章正文缓存；无行即未缓存，调用方据此决定走网络还是用本地 */
    @Query("SELECT * FROM book_content WHERE dur_chapter_url = :chapterUrl")
    suspend fun getContentByChapterUrl(chapterUrl: String): BookContentEntity?

    /**
     * 批量查询已存在正文内容的章节 URL。
     *
     * 供下载面板绘制"已缓存"徽章：以内容表为事实源（而非 ChapterListEntity.hasCache
     * 内存快照，后者在阅读器内存列表中可能滞后于阅读/下载的实际入库）。
     *
     * 返回的是**表里命中的子集**，调用方拿去和入参求差集即得"未缓存章节"；
     * 入参为空时请在调用侧短路（见 BookRepository.getCachedChapterUrls），别把空集合传进 IN。
     */
    @Query("SELECT dur_chapter_url FROM book_content WHERE dur_chapter_url IN (:chapterUrls)")
    suspend fun getExistingChapterUrls(chapterUrls: List<String>): List<String>

    /**
     * 按 `dur_chapter_url` upsert 一章正文：同章重复抓取以最后一次为准，天然不会重复堆积。
     *
     * REPLACE 只动本表，不回写 chapter_list.has_cache——标记同步是调用方的责任
     * （见 BookRepository.updateChapterCache），只调本方法会出现「有正文但目录显示未缓存」。
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(content: BookContentEntity)

    /**
     * 删除单章正文。
     *
     * 用途是带强制刷新标记的下载任务重抓前清旧内容（见 DownloadChapterEntity.forceRefresh 与
     * DownloadService.downloading）：不先删，后续命中旧缓存会让重抓结果永远不生效。
     */
    @Query("DELETE FROM book_content WHERE dur_chapter_url = :chapterUrl")
    suspend fun deleteByChapterUrl(chapterUrl: String)

    /** 批量删除正文：从书架移除时按该书全部章节 URL 一次清（见 BookRepository.removeFromShelf） */
    @Query("DELETE FROM book_content WHERE dur_chapter_url IN (:chapterUrls)")
    suspend fun deleteByChapterUrls(chapterUrls: List<String>)
}
