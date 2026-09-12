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
import com.ebook.db.dao.BookSourceDao
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

    /**
     * v4 → v5（ADR-0016 多书源共存）：新增 `book_source` 表，把书源从编译期 assets 配置升级成运行时可管理数据。
     *
     * 只有 CREATE TABLE、无破坏性变更：老用户升级后书架/章节/下载队列等数据完全保留。
     * 业务表里既有的 `tag` 值本就是那唯一书源的 URL，而这张表建成即空、**且此后不会有任何代码往里灌数据**
     * （书源一律由用户自行导入，应用不随包携带任何书源）——因此升级后这些 `tag` 暂时指向不存在的行，
     * 按既有语义显示「书源已失效」，由用户导入该书源后恢复。
     * 所以本迁移不去读 assets 自行灌数据（如今 assets 里也没有书源清单了）：`lib_ebook_db` 依赖不到
     * 书源规则模型（`BookSourceRule` 在 `lib_ebook_api`），在迁移里硬编码站点与规则等于把业务配置抄进 schema 层。
     *
     * **刻意不写 DEFAULT**（与 MIGRATION_2_3 建 `book_group` 同写法）：实体没有声明任何
     * `@ColumnInfo(defaultValue = ...)`，Room 为全新安装生成的建表语句也就不带 DEFAULT，而 Room 的
     * schema 校验只比对实体声明过的东西——这里多写一份实体没有的默认值，覆盖安装得到的库与全新安装的
     * 库结构就会漂移，且这种漂移 Room 查不出来。漂移的实际后果不是崩溃而是行为分叉：同一条漏列的裸
     * INSERT 在一侧成功、在另一侧违约束失败（或静默落 `added_at = 0`、在页面上显示成 1970 年），
     * 两种装机形态各走一套规则。DAO 一律整列绑定写入，本来也不需要数据库侧补默认值。
     */
    private val MIGRATION_4_5 = object : Migration(4, 5) {
        override suspend fun migrate(connection: SQLiteConnection) {
            connection.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `book_source` (
                    `url` TEXT NOT NULL,
                    `name` TEXT NOT NULL,
                    `rule_json` TEXT NOT NULL,
                    `enabled` INTEGER NOT NULL,
                    `weight` INTEGER NOT NULL,
                    `group_name` TEXT NOT NULL,
                    `is_user_imported` INTEGER NOT NULL,
                    `added_at` INTEGER NOT NULL,
                    PRIMARY KEY(`url`)
                )
                """.trimIndent()
            )
        }
    }

    /**
     * v5 → v6（ADR-0029 脚本书源双格式共存）：`book_source` 增加判别列 `format`，
     * 让原生规则书源与脚本书源在同一张表里并存、按出身各走各的求值路径。
     *
     * 只有一个 ALTER TABLE ADD COLUMN，无破坏性变更，且**必须带 DEFAULT**：SQLite 给存量行补列时取的就是
     * 这个默认值，v5 时代的表里只可能有原生规则书源，故存量行零感知地全部落成 `native`，不需要额外的回填语句。
     *
     * 与 [MIGRATION_4_5] 那次「实体没有 defaultValue、所以刻意不写 DEFAULT」的写法相反，本次实体声明了
     * `@ColumnInfo(defaultValue = "native")`，Room 为全新安装生成的建表语句于是也带 `DEFAULT 'native'`——
     * 这里的 SQL 必须与实体声明一致，否则覆盖安装与全新安装两份库结构漂移（Room 查不出来），
     * 裸写漏列的 INSERT 在两侧一成一败，同一个功能长出两套行为。
     */
    private val MIGRATION_5_6 = object : Migration(5, 6) {
        override suspend fun migrate(connection: SQLiteConnection) {
            connection.execSQL("ALTER TABLE book_source ADD COLUMN format TEXT NOT NULL DEFAULT 'native'")
        }
    }

    /**
     * v6 → v7：应用不再随包携带任何书源，`book_source` 去掉 `is_user_imported` 列。
     *
     * 两条语句，顺序不可颠倒：
     * 1. 先按该列清掉内置行——`is_user_imported = 0` 只可能由内置源种下（首启灌库，或用户以同 URL
     *    覆盖过它）；列一删就再也分不出来这些行了；
     * 2. 再删列，删除保护随之整体下线。
     *
     * `DROP COLUMN` 可用：本仓 `MIGRATION_3_4` 已在用（`chapter_list DROP COLUMN has_cache`），
     * 且数据库由 `BundledSQLiteDriver` 承接，DDL 能力取决于随包 SQLite 而非设备系统版本——
     * `sqlite-bundled` 2.7.0 携带的是 SQLite 3.50.1，高于该语法所需的 3.35；
     * `is_user_imported` 是普通列（非主键、无索引），不触及 `DROP COLUMN` 的限制。
     *
     * 后果：装过开发包的设备上，原本绑定该内置源的书升级后按既有语义显示「书源已失效」。
     * 这正是本迁移的目的（该源本就不该随包下发），不是回归。发布基线 `master` 从未有过本表
     * （其 `AppDatabase` 是 v2），正式用户的升级路径由 [MIGRATION_4_5] 建出一张空表，不受本迁移影响。
     *
     * 声明为 `internal` 而非 `private`：让同模块测试（`BookSourceMigration6To7Test`）能引用它，并把
     * `migrate(connection)` 直接驱动在**生产同款**的 bundled 引擎上——语句的顺序、条件与效果因此有
     * 回归覆盖（测试自己开 bundled 连接造出 v6 形态的表，走的就是本模块 `provideAppDatabase` 同一驱动）。
     * 「覆盖安装后 Room 认出 v7 结构并正常打开」仍归人工装机验证：那要接 Room 侧的建库链，测试不碰。
     */
    internal val MIGRATION_6_7 = object : Migration(6, 7) {
        override suspend fun migrate(connection: SQLiteConnection) {
            connection.execSQL("DELETE FROM book_source WHERE is_user_imported = 0")
            connection.execSQL("ALTER TABLE book_source DROP COLUMN is_user_imported")
        }
    }

    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase {
        return Room.databaseBuilder(
            context.applicationContext,
            AppDatabase::class.java,
            AppDatabase.DATABASE_NAME
        ).addMigrations(
            MIGRATION_1_2,
            MIGRATION_2_3,
            MIGRATION_3_4,
            MIGRATION_4_5,
            MIGRATION_5_6,
            MIGRATION_6_7,
        ).setDriver(BundledSQLiteDriver()).build()
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

    /** 书源表访问器：落地后由 `lib_book_common` 的 BookSourceManager 消费，业务模块不直调（见 ADR-0016） */
    @Provides
    @Singleton
    fun provideBookSourceDao(db: AppDatabase): BookSourceDao = db.bookSourceDao()
}
