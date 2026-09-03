package com.ebook.db

import androidx.room3.Database
import androidx.room3.RoomDatabase
import com.ebook.db.dao.*
import com.ebook.db.entity.*

/**
 * ebook 本地数据库（Room 3.0.0，artifact 群组 `androidx.room3`），六张表的装配点。
 *
 * 承载的都是「离线可读」所需的数据：书架（book_shelf）、书籍信息（book_info）、
 * 章节目录（chapter_list）、章节缓存（book_content）、搜索历史（search_history）、
 * 下载队列（download_chapter）。本类只声明表与 DAO 的对应关系，读写一律经各 DAO
 * 由其上层仓库（`lib_book_common` 的 BookRepository、`module_book` 的 DownloadRepository 等）发起。
 *
 * 主键策略（见 ADR-0003）：书架/书籍/章节/正文用自然键（`note_url` / `dur_chapter_url`），
 * 同一 URL 天然只存一份、upsert 语义清晰；下载任务与搜索历史是流水型数据，用自增 `id`，
 * 去重责任上移到仓库（如 DownloadRepository.addTasks 按 durChapterUrl 查重）。
 *
 * 注意：`com.ebook.db.entity` 包内另有若干非持久化的传输模型（SearchBookEntity、
 * LibraryEntity 等），不在下方 entities 中，勿按表来理解。
 *
 * Schema 演进（见 ADR-0003「Schema 演进」）：改实体必须三件事同做——version +1、
 * 在 [com.ebook.db.di.DatabaseModule] 的迁移链上追加紧邻的 `MIGRATION_n_n+1`（不跳版、
 * 不删旧迁移）、提交 Room 生成的新 schema JSON（exportSchema = true，目录由约定插件
 * `xrn1997.android.room` 指向本模块 `schemas/`）。禁止启用 `fallbackToDestructiveMigration`。
 */
@Database(
    entities = [
        BookShelfEntity::class,
        BookInfoEntity::class,
        ChapterListEntity::class,
        BookContentEntity::class,
        SearchHistoryEntity::class,
        DownloadChapterEntity::class
    ],
    version = 2,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    /** 书架表：书架列表与阅读进度，书架页/「我的」页阅读统计的数据源 */
    abstract fun bookShelfDao(): BookShelfDao
    /** 书籍信息表：书名、作者、封面等元数据，以 note_url 与书架行对应 */
    abstract fun bookInfoDao(): BookInfoDao
    /** 章节目录表：一本书的章节列表与逐章缓存标记，目录页/下载面板的数据源 */
    abstract fun chapterListDao(): ChapterListDao
    /** 章节缓存表：已落地的章节正文，离线阅读与「已缓存」判定的事实源 */
    abstract fun bookContentDao(): BookContentDao
    /** 搜索历史表：按搜索类型分组的本地搜索词记录 */
    abstract fun searchHistoryDao(): SearchHistoryDao
    /** 下载队列表：未完成的离线下载任务，`DownloadService` 逐章取队头的依据（见 ADR-0018） */
    abstract fun downloadChapterDao(): DownloadChapterDao

    companion object {
        /** 数据库文件名；改动等于换库（旧数据不再可见），迁移链只对同名文件生效 */
        const val DATABASE_NAME = "ebook_db"
    }
}
