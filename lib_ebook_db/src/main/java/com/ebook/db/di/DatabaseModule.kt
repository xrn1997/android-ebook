package com.ebook.db.di

import android.content.Context
import androidx.room3.Room
import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import com.ebook.db.AppDatabase
import com.ebook.db.dao.BookContentDao
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

    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase {
        return Room.databaseBuilder(
            context.applicationContext,
            AppDatabase::class.java,
            AppDatabase.DATABASE_NAME
        ).addMigrations(MIGRATION_1_2).setDriver(BundledSQLiteDriver()).build()
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
    fun provideBookContentDao(db: AppDatabase): BookContentDao = db.bookContentDao()

    @Provides
    @Singleton
    fun provideSearchHistoryDao(db: AppDatabase): SearchHistoryDao = db.searchHistoryDao()

    @Provides
    @Singleton
    fun provideDownloadChapterDao(db: AppDatabase): DownloadChapterDao = db.downloadChapterDao()
}
