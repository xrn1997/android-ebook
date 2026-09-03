package com.ebook.db

import androidx.room3.Database
import androidx.room3.RoomDatabase
import com.ebook.db.dao.*
import com.ebook.db.entity.*

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
    abstract fun bookShelfDao(): BookShelfDao
    abstract fun bookInfoDao(): BookInfoDao
    abstract fun chapterListDao(): ChapterListDao
    abstract fun bookContentDao(): BookContentDao
    abstract fun searchHistoryDao(): SearchHistoryDao
    abstract fun downloadChapterDao(): DownloadChapterDao

    companion object {
        const val DATABASE_NAME = "ebook_db"
    }
}
