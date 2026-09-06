package com.ebook.db.di

import android.content.Context
import androidx.room3.Room
import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import com.ebook.db.AppDatabase
import com.ebook.db.dao.BookGroupDao
import com.ebook.db.dao.BookInfoDao
import com.ebook.db.dao.BookShelfDao
import com.ebook.db.dao.ChapterListDao
import com.ebook.db.dao.DownloadChapterDao
import com.ebook.db.dao.SearchHistoryDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    /**
     * v1 → v2：download_chapter 新增强制刷新标记列。
     *
     * 选用显式 ALTER TABLE 而非破坏性迁移：开发期库里也有真实验证成本（书架/
     * 已缓存正文），旧行补默认值 0 后普通任务语义不变；若后续进入稳定期带数据
     * 上线，迁移链必须继续逐版追加，不得改为清库。
     */
    private val MIGRATION_1_2 = object : Migration(1, 2) {
        override suspend fun migrate(connection: SQLiteConnection) {
            connection.execSQL(
                "ALTER TABLE download_chapter ADD COLUMN force_refresh INTEGER NOT NULL DEFAULT 0"
            )
        }
    }

    /**
     * v2 → v3（M1a，spec §5）：本地书正文从 `book_content` 迁到应用私有目录的章文件。
     *
     * 做四件事：建 `book_group`、给 `book_shelf` 补本地来源所需列、把 `chapter_list` 主键列
     * 改名成通用内容定位符、**直接删除全部本地书数据**。
     *
     * 删而不迁移是刻意的：本地书的索引与正文都可再生（重新导入即得），而旧正文是**被清洗过**
     * 的——旧实现删光了行内空格并把全角缩进写进正文，把它搬进章文件等于将损毁固化成新基座。
     * 判据见 spec §2 决定 9（可再生则不背兼容）。
     *
     * `book_content` 表与 `chapter_list.has_cache` 本次都不删：网络书正文要到 M1b 才出 DB，
     * M1a 期间它们仍是网络书的缓存事实源与"已缓存"徽章依据，v4 一并收掉。
     */
    private val MIGRATION_2_3 = object : Migration(2, 3) {
        override suspend fun migrate(connection: SQLiteConnection) {
            connection.execSQL(
                "CREATE TABLE IF NOT EXISTS `book_group` (" +
                    "`comment_key` TEXT NOT NULL, `note_url` TEXT NOT NULL, " +
                    "`is_primary` INTEGER NOT NULL, PRIMARY KEY(`comment_key`, `note_url`))"
            )
            connection.execSQL(
                "CREATE INDEX IF NOT EXISTS `idx_book_group_note_url` " +
                    "ON `book_group` (`note_url`)"
            )
            connection.execSQL("ALTER TABLE book_shelf ADD COLUMN book_format TEXT")
            connection.execSQL("ALTER TABLE book_shelf ADD COLUMN text_charset TEXT")
            connection.execSQL("ALTER TABLE book_shelf ADD COLUMN match_name TEXT")
            connection.execSQL("ALTER TABLE book_shelf ADD COLUMN match_author TEXT")
            connection.execSQL("ALTER TABLE chapter_list RENAME COLUMN dur_chapter_url TO content_ref")
            connection.execSQL("DELETE FROM book_content WHERE tag = 'loc_book'")
            connection.execSQL("DELETE FROM chapter_list WHERE tag = 'loc_book'")
            connection.execSQL("DELETE FROM book_info WHERE tag = 'loc_book'")
            connection.execSQL("DELETE FROM book_shelf WHERE tag = 'loc_book'")
        }
    }

    /**
     * v3 → v4（M1b，spec §5）：网络书正文从 `book_content` 迁到章文件。
     *
     * 做两件事：删 `book_content` 表、删 `chapter_list.has_cache` 列。
     * 缓存存在性改由 BookStore 章文件存在性判定，不再需要数据库标记。
     */
    private val MIGRATION_3_4 = object : Migration(3, 4) {
        override suspend fun migrate(connection: SQLiteConnection) {
            connection.execSQL("DROP TABLE IF EXISTS book_content")
            connection.execSQL("ALTER TABLE chapter_list DROP COLUMN has_cache")
        }
    }

    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase {
        return Room.databaseBuilder(
            context.applicationContext,
            AppDatabase::class.java,
            AppDatabase.DATABASE_NAME
        ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4).setDriver(BundledSQLiteDriver()).build()
    }

    @Provides
    @Singleton
    fun provideBookShelfDao(db: AppDatabase): BookShelfDao = db.bookShelfDao()

    @Provides
    @Singleton
    fun provideBookInfoDao(db: AppDatabase): BookInfoDao = db.bookInfoDao()

    @Provides
    @Singleton
    fun provideChapterListDao(db: AppDatabase): ChapterListDao = db.chapterListDao()

    @Provides
    @Singleton
    fun provideSearchHistoryDao(db: AppDatabase): SearchHistoryDao = db.searchHistoryDao()

    @Provides
    @Singleton
    fun provideDownloadChapterDao(db: AppDatabase): DownloadChapterDao = db.downloadChapterDao()

    @Provides
    @Singleton
    fun provideBookGroupDao(db: AppDatabase): BookGroupDao = db.bookGroupDao()
}
