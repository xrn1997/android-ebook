package com.ebook.db

import android.content.Context
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import androidx.test.core.app.ApplicationProvider
import com.ebook.db.di.DatabaseModule
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * `MIGRATION_6_7`（v6 → v7：先清掉内置源行、再删 `is_user_imported` 列）的**行为**回归测试，
 * 跑在**生产同款引擎**上。
 *
 * **为什么是 bundled 驱动**：本模块的装配入口 `provideAppDatabase` 写死了 `.setDriver(BundledSQLiteDriver())`，
 * 故生产 DDL 能力取决于**随包 SQLite**，与设备系统版本无关。JVM 单测里能拿到同一个引擎：把
 * `androidx.sqlite:sqlite-bundled-jvm`（见 `lib_ebook_db/build.gradle.kts` 的 `testImplementation`）
 * 挂到测试 classpath 上即可——它是同一份驱动的 JVM 变体，jar 里带 `natives/windows_x64/sqliteJni.dll`
 * 等桌面原生库，`BundledSQLiteDriver` 的 JVM 侧加载器会把对应架构的那份解出来 `System.load`。
 * 实测本用例的 `SELECT sqlite_version()` 返回 **3.50.1**，`ALTER TABLE … DROP COLUMN …`（SQLite 3.35
 * 起进语法表）真能跑。于是一条手写的裸 SQL 迁移在这里得到了**与生产同一条**执行路径，不需要任何仿真。
 *
 * **为什么不用 framework 驱动**：`AndroidSQLiteDriver`（`androidx.sqlite:sqlite-framework`）在
 * Robolectric 下落到的是 Robolectric 自带的原生 SQLite（实测 3.32.2，早于 3.35），`DROP COLUMN`
 * 只会得到 `near "DROP": syntax error`——那是**测试与生产的引擎落差**，用它就等于在用一个生产不会用的
 * 引擎验收迁移。本用例曾在那个引擎上挂一层「重建表」兼容层模拟删列，如今 bundled 变体既然可加载，
 * 兼容层整体删掉了：仿真层一旦与真语法有偏差，红的绿的都是假的。
 *
 * **为什么不用 `MigrationTestHelper`**：它要 Room 的 schema 资产走一遍完整的建库/校验流程，本用例只需
 * 要**任意**一个 `SQLiteConnection` 来驱动 `Migration.migrate(connection)`——后者是 `Migration` 的
 * 公开入口、与 Room 的运行时无关。裸连接反而让失败形态直接落在迁移的 SQL 上（哪条语句、什么语法错），
 * 不必穿过建库流程的包装异常。Room 导出的 schema 那一半由 `AppDatabaseSchemaTest` 从生成物侧钉住。
 *
 * **前置守卫**：用例开头断言引擎的 SQLite 主次版本 ≥ 3.35，把「本用例的前提」显式钉住——将来若有人把
 * 这条依赖抽掉、或 JVM 变体的原生库加载路径变了，红的会是「引擎过旧」这句人话，而不是一句
 * `near "DROP": syntax error`。
 *
 * 锁的是三件事（顺序或条件错一件都会**静默**丢用户数据）：
 * - **先清行后删列**：`is_user_imported` 是「这行是不是内置源」的唯一判据，列一删就再也分不出来；
 *   两条语句对调后 `DELETE` 会以 `no such column` 失败（而不是「没清干净就直接删列」）
 * - **只清内置行**：`is_user_imported = 1` 的用户导入行必须原样留下——本迁移的目的只是让「内置源随包
 *   下发」这件事退场，不是清空书源表。这条断言跑在真库上、验的就是迁移里那句 `DELETE` 的原文
 * - **列真的消失**：v7 的实体已不再声明该列，迁移不删就会让覆盖安装的库比全新安装多一列；
 *   `AppDatabaseSchemaTest` 从 Room 导出的 schema 侧钉了「v7 不含该列」的另一半，两半合起来才完整
 *
 * **边界**：本用例不驱动 Room 的建库与 schema 校验，也不覆盖「覆盖安装后 Room 认出 v7 结构并正常
 * 打开」这一步（那要接 Room 侧的驱动链，仍是人工装机验证项）；这里钉的只有迁移语句在真库上的效果。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BookSourceMigration6To7Test {

    private lateinit var context: Context

    /**
     * 迁移拿到的连接：`BundledSQLiteDriver` 在测试文件路径上开的真连接，与 `provideAppDatabase`
     * 交给 Room 的是同一个驱动。本用例的建表、插数、查证与迁移全走它，无任何包装或仿真。
     */
    private lateinit var connection: SQLiteConnection

    /**
     * 每个用例都从「一张干净的 v6 库文件」起跑。
     *
     * 用文件库而不是内存库：`BundledSQLiteDriver.open` 的入参就是文件路径，而 v6 形态的表得靠裸 SQL
     * 自己建出来（本模块的 Room 实体已升到 v7，没有任何能生成 v6 结构的入口）。先 `deleteDatabase`
     * （它连带清掉 `-wal`/`-shm`）再开连接，保证用例可重复跑——残留库会让建表语句撞上既有表。
     * 目录须先 `mkdirs`：SQLite 会创建库文件，但不会替调用方创建上层目录。
     */
    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(DB_NAME)
        val dbFile = context.getDatabasePath(DB_NAME)
        dbFile.parentFile?.mkdirs()
        connection = BundledSQLiteDriver().open(dbFile.absolutePath)
    }

    /** 关连接并删库文件，不给下一次运行留状态（Robolectric 的沙箱目录通常已隔离，显式收尾更稳） */
    @After
    fun tearDown() {
        connection.close()
        context.deleteDatabase(DB_NAME)
    }

    @Test
    fun `升级到 v7 会清掉内置源行并删掉 is_user_imported 列`(): Unit = runBlocking {
        // 前置守卫：本用例的整个前提是引擎认得 DROP COLUMN。挂在最前面，红了就是「环境变了」而不是
        // 「迁移写错了」——后者才是本用例要报的东西，别让两者混成同一种失败
        val version = querySqliteVersion()
        val (major, minor) = version.split(".").let { it[0].toInt() to it[1].toInt() }
        assertTrue(
            "引擎 SQLite 为 $version（低于 3.35），本用例的前提不成立：" +
                "ALTER TABLE … DROP COLUMN … 自 3.35 起才进语法表，跑下去只会是 near \"DROP\": syntax error",
            major > 3 || (major == 3 && minor >= 35),
        )

        createV6Table()
        insertV6Row(url = BUILTIN_URL, name = "内置源", isUserImported = 0)
        insertV6Row(url = USER_URL, name = "用户导入", isUserImported = 1)

        // 前置断言：迁移前这两行、这一列真的都在。少了它，用例在「什么都没做的空库」上也会假绿，
        // 正是「迁移零覆盖」那种最坏情况——看着有测试，实际什么都没驱动
        assertEquals(listOf(BUILTIN_URL, USER_URL), queryUrls())
        assertTrue("v6 形态的表本该有 is_user_imported 列", queryColumnNames().contains(COLUMN_IS_USER_IMPORTED))

        DatabaseModule.MIGRATION_6_7.migrate(connection)

        // 内置行（is_user_imported = 0）被清掉，用户导入的行保留
        assertEquals(listOf(USER_URL), queryUrls())
        // 列真的没了：承上，两条语句对调后 DELETE 会以 no such column 失败，这条断言是「顺序不可颠倒」的落点
        assertFalse(
            "迁移后不该再有 $COLUMN_IS_USER_IMPORTED 列，实际列为 ${queryColumnNames()}",
            queryColumnNames().contains(COLUMN_IS_USER_IMPORTED),
        )
    }

    /**
     * 在空库上建出**最终 v6 形态**的 `book_source` 表。
     *
     * 列集合逐条对应迁移链的落点：建表语句来自 `MIGRATION_4_5`，`format` 列来自 `MIGRATION_5_6`
     * 的 `ALTER TABLE … ADD COLUMN … DEFAULT 'native'`——这里把它内联进建表语句（`ALTER` 实际是把列
     * 追加在末尾，此处按可读顺序内联，列序与本用例的断言无关）。除 `format` 外各列刻意都没有 DEFAULT：
     * v4→v5 那条迁移的 KDoc 已交代过「实体没声明 defaultValue、建表就不带 DEFAULT」的口径。
     */
    private fun createV6Table() {
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
                `format` TEXT NOT NULL DEFAULT 'native',
                `added_at` INTEGER NOT NULL,
                PRIMARY KEY(`url`)
            )
            """.trimIndent()
        )
    }

    /**
     * 往 v6 表里塞一行。`is_user_imported` 是本用例唯一的自变量：0 = 首启灌库种下的内置源（迁移要清掉），
     * 1 = 用户自己导入的（迁移必须留着）。其余列给固定值——本迁移不看它们。
     */
    private fun insertV6Row(url: String, name: String, isUserImported: Int) {
        connection.execSQL(
            "INSERT INTO book_source " +
                "(url, name, rule_json, enabled, weight, group_name, is_user_imported, format, added_at) " +
                "VALUES ('$url', '$name', '{}', 1, 0, '小说', $isUserImported, 'native', 0)"
        )
    }

    /** 取回引擎自报的 SQLite 版本（`3.50.1` 这种形态），供前置守卫判 3.35 门槛 */
    private fun querySqliteVersion(): String =
        connection.prepare("SELECT sqlite_version()").use { statement ->
            check(statement.step()) { "sqlite_version() 没有返回行" }
            statement.getText(0)
        }

    /** 取回表里剩下的全部 url；显式 `ORDER BY url` 是为了不依赖 SQLite 的默认行序，断言才稳定 */
    private fun queryUrls(): List<String> =
        connection.prepare("SELECT url FROM book_source ORDER BY url ASC").use { statement ->
            buildList {
                while (statement.step()) {
                    add(statement.getText(0))
                }
            }
        }

    /**
     * 取回 `book_source` 的列名集合（`PRAGMA table_info` 的第二个字段是列名）。
     *
     * 断言列**集合**而不是建表语句字符串：迁移走的是 `DROP COLUMN`，库里的表定义已被重写，
     * 只有真读一次 PRAGMA 才能证明这列确实不在了。
     */
    private fun queryColumnNames(): List<String> =
        connection.prepare("PRAGMA table_info(`book_source`)").use { statement ->
            buildList {
                while (statement.step()) {
                    add(statement.getText(1))
                }
            }
        }

    private companion object {
        /** 本用例私有的库文件名：与 AppDatabase 的真实库名无关，避免误碰生产数据文件 */
        const val DB_NAME = "book_source_migration_test.db"

        /** `is_user_imported = 0` 那一侧的站点：模拟随包下发的内置源 */
        const val BUILTIN_URL = "https://builtin.example"

        /** `is_user_imported = 1` 那一侧的站点：模拟用户导入的源 */
        const val USER_URL = "https://user.example"

        const val COLUMN_IS_USER_IMPORTED = "is_user_imported"
    }
}
