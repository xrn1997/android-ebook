package com.ebook.db

import com.ebook.db.entity.BookSourceEntity
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * `AppDatabase` 迁移链与 Room 导出 schema 的契约测试（见 ADR-0003「Schema 演进」）。
 *
 * 锁的是三件编译器**不会**替我们查的事：
 * - **链上不留洞、也不长尾巴**：`schemas/` 下 1..[SCHEMA_LATEST_VERSION] 每版一份 JSON，且没有更新的版本
 *   冒出来。少一版意味着某条升级路径上没有可比对的 schema；多出来的那一版则是「有人改了实体却忘了
 *   本测试与迁移链的约定」，必须在这里被拦下而不是悄悄进主干。JSON 里的 `database.version` 就是
 *   `@Database(version = ...)` 的落地值（Room 3 把该注解编成了 BINARY 保留，运行时反射读不到，
 *   故以生成物为准来反查声明）
 * - **实体 ↔ schema 一致**：`@ColumnInfo(defaultValue = ...)` 是 Room 生成建表语句与比对结构的依据，
 *   而 Kotlin 侧的属性默认值它一个字都不看——两者一旦漂移，「新建行取 Kotlin 默认值」与
 *   「裸 INSERT 由库侧补默认值」就各说各话（最后那条「同字」用例专门管这件事）
 * - **改表只动该动的那一列**：v5 → v6 只多出 `format`，v6 → v7 只少掉 `is_user_imported`，
 *   顺手夹带的别的列改动会被抓住
 *
 * 用 Robolectric 而非纯 JVM：schema JSON 的解析走 Android 的 `org.json`，
 * 纯 JVM 单测里它是 android.jar 的桩（一调就抛 not mocked）。
 *
 * **本测试的边界**：它钉的是 Room 导出的结构（全新安装走的建表语句）这一半；迁移**语句**的行为那一半
 * 由 `BookSourceMigration6To7Test` 承担——v7 的迁移已声明成 `internal`，该用例用裸连接在**生产同款**的
 * bundled 引擎上直接驱动它。至于本模块唯一的装配入口 `provideAppDatabase` 里的 Room 建库链，两侧都
 * 跑不到（Room 3 的 `RoomDatabase` 不公开任何裸 SQL 通道，`openHelper` / `execSQL` 皆 `@RestrictTo`），
 * 连「建表语句上的 DEFAULT 真在库侧兜底」也无从驱动。所以「覆盖安装后存量行的 format 真是 native」
 * 按 AGENTS.md 的分工留给人工装机验证；本测试能做的是把它的前提交代清楚：迁移里的 ALTER 必须与这里
 * 断言的 `createSql` 片段同字。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppDatabaseSchemaTest {

    /**
     * Room 导出 schema 的目录（约定插件 `xrn1997.android.room` 把它指到本模块 `schemas/` 下、
     * 以数据库类全名分子目录）。单测的工作目录随运行方式而变：Gradle 跑在模块目录、
     * IDE 的运行配置常落在仓库根，故两处都试、并向上找父目录，避免测试只在某一种跑法下成立。
     */
    private val schemaDir: File by lazy {
        val workingDir = checkNotNull(System.getProperty("user.dir")) { "JVM 未给出 user.dir" }
        val relative = listOf("schemas/$DB_SCHEMA_DIR", "$MODULE_DIR/schemas/$DB_SCHEMA_DIR")
        generateSequence(File(workingDir)) { it.parentFile }
            .flatMap { base -> relative.map { File(base, it) } }
            .firstOrNull { it.isDirectory }
            ?: error("找不到 Room schema 目录（期望 $DB_SCHEMA_DIR/1.json 存在），工作目录为 $workingDir")
    }

    /** 该版 schema 的 JSON 文件。缺文件即测试失败，正好充当「链上不留洞」的断言 */
    private fun schemaFile(version: Int): File = File(schemaDir, "$version.json").also {
        assertTrue("缺少 schema JSON：$it（迁移链不得留洞、exportSchema 不得关）", it.isFile)
    }

    /** 读取指定版本的 schema JSON */
    private fun schemaOf(version: Int): JSONObject =
        JSONObject(schemaFile(version).readText(Charsets.UTF_8))

    /** 取某版 schema 里指定表的实体项（含 createSql 与 fields） */
    private fun entityOf(version: Int, tableName: String): JSONObject {
        val entities = schemaOf(version).getJSONObject("database").getJSONArray("entities")
        return (0 until entities.length())
            .map(entities::getJSONObject)
            .firstOrNull { it.getString("tableName") == tableName }
            ?: error("schema $version.json 中没有 $tableName 表")
    }

    /** 取某版 schema 里指定表的字段清单，键为列名（Room 的字段项以 columnName 标识列） */
    private fun fieldsOf(version: Int, tableName: String): Map<String, JSONObject> {
        val fields = entityOf(version, tableName).getJSONArray("fields")
        return (0 until fields.length())
            .map(fields::getJSONObject)
            .associateBy { it.getString("columnName") }
    }

    /** 取某版 schema 里的全部表名集合（比对跨版本表级增删用） */
    private fun tableNamesOf(version: Int): Set<String> {
        val entities = schemaOf(version).getJSONObject("database").getJSONArray("entities")
        return (0 until entities.length())
            .map(entities::getJSONObject)
            .map { it.getString("tableName") }
            .toSet()
    }

    /** `schemas/` 里实际存在的版本号，升序 */
    private fun exportedVersions(): List<Int> =
        requireNotNull(schemaDir.listFiles()) { "schema 目录不可读：$schemaDir" }
            .mapNotNull { file -> file.nameWithoutExtension.toIntOrNull()?.takeIf { file.isFile } }
            .sorted()

    @Test
    fun `导出的 schema 链条不留洞也不长尾巴且版本与文件名相符`() {
        val versions = exportedVersions()

        // 1..最新 逐版都在：链上任一版本缺文件，那条升级路径就没有可比对的 schema
        assertEquals((1..SCHEMA_LATEST_VERSION).toList(), versions)
        // 长出 6 之后的一版 = 有人改了实体没走本测试的约定，改测试与迁移链后再让它放行
        assertFalse("出现了预期之外的 schema 版本：$versions", versions.any { it > SCHEMA_LATEST_VERSION })
        // JSON 内的 version 由 @Database(version) 生成，两者不同字就是导出环节被动过
        assertEquals(
            SCHEMA_LATEST_VERSION,
            schemaOf(SCHEMA_LATEST_VERSION).getJSONObject("database").getInt("version"),
        )
    }

    @Test
    fun `v6 的 book_source 含 format 列且结构与实体声明一致`() {
        val format = checkNotNull(fieldsOf(6, TABLE_BOOK_SOURCE)["format"]) {
            "6.json 的 book_source 没有 format 列"
        }

        assertEquals("TEXT", format.getString("affinity"))
        // notNull + defaultValue 是这条列的两个硬约束：少了任一项，ALTER TABLE ADD COLUMN 就跑不动
        assertTrue("format 列应为 NOT NULL", format.getBoolean("notNull"))
        // Room 导出的 defaultValue 是**SQL 字面量**、带单引号（实体里写 `defaultValue = "native"`，
        // 导出成 `'native'`），别照着注解值来改这条断言
        assertEquals("'native'", format.getString("defaultValue"))

        // 全新安装走的就是这句建表语句，迁移里的 ALTER 必须与它同义（表名/列名/NOT NULL/DEFAULT 逐项对上）
        val createSql = entityOf(6, TABLE_BOOK_SOURCE).getString("createSql")
        assertTrue(
            "6.json 的建表语句应含 format 列的完整定义，实际为 $createSql",
            createSql.contains("`format` TEXT NOT NULL DEFAULT 'native'"),
        )
    }

    @Test
    fun `v6 相对 v5 只多出 format 一列`() {
        val before = fieldsOf(5, TABLE_BOOK_SOURCE).keys
        val after = fieldsOf(6, TABLE_BOOK_SOURCE).keys

        assertFalse("v5 不该已有 format 列（那样迁移就成了空操作）", before.contains("format"))
        // 只允许这一列进差集：顺手夹带的别的字段改动必须被这条用例挡下
        assertEquals(setOf("format"), after - before)
        assertEquals(before, after - "format")
    }

    @Test
    fun `v7 相对 v6 只少 is_user_imported 一列`() {
        val before = fieldsOf(6, TABLE_BOOK_SOURCE).keys
        val after = fieldsOf(7, TABLE_BOOK_SOURCE).keys

        assertTrue("v6 本应有 is_user_imported 列（否则迁移就成了空操作）", before.contains("is_user_imported"))
        assertEquals(setOf("is_user_imported"), before - after)
        assertEquals(before - "is_user_imported", after)
    }

    @Test
    fun `v7 的 book_source 建表语句不含 is_user_imported`() {
        val createSql = entityOf(7, TABLE_BOOK_SOURCE).getString("createSql")
        assertFalse(
            "7.json 的建表语句不该再含 is_user_imported，实际为 $createSql",
            createSql.contains("is_user_imported")
        )
    }

    @Test
    fun `v8 相对 v7 只多出 paused_book 一张表`() {
        val before = tableNamesOf(7)
        val after = tableNamesOf(8)

        assertFalse("v7 不该已有 paused_book 表（否则迁移就成了空操作）", before.contains(TABLE_PAUSED_BOOK))
        // 只允许这一张表进差集：顺手夹带的别的表改动必须被这条用例挡下
        assertEquals(setOf(TABLE_PAUSED_BOOK), after - before)
        assertEquals(before, after - TABLE_PAUSED_BOOK)
    }

    @Test
    fun `v8 的 paused_book 主键是 note_url 且建表语句与迁移同字`() {
        val entity = entityOf(8, TABLE_PAUSED_BOOK)

        // 主键即 note_url（一本书最多一行标记，REPLACE 即幂等重按，见 ADR-0036）：
        // Room 把主键列同时标进 fields 与 primaryKey.columnNames，两处都要有
        val pkColumns = entity.getJSONObject("primaryKey").getJSONArray("columnNames")
        assertEquals(listOf("note_url"), (0 until pkColumns.length()).map(pkColumns::getString))
        assertTrue(
            "note_url 应为 NOT NULL（自然键主键参与外键语义时 Room 要求非空）",
            fieldsOf(8, TABLE_PAUSED_BOOK).getValue("note_url").getBoolean("notNull")
        )

        // 全新安装走的就是这句建表语句，迁移里的 CREATE TABLE 必须与它同字
        // （DatabaseModule.MIGRATION_7_8 的 DDL 即此串，改一处必须改另一处）
        val createSql = entity.getString("createSql")
        assertTrue(
            "8.json 的建表语句应为 note_url TEXT NOT NULL 单列自然键，实际为 $createSql",
            createSql.contains("`note_url` TEXT NOT NULL") && createSql.contains("PRIMARY KEY(`note_url`)")
        )
    }

    @Test
    fun `实体的 Kotlin 默认值与 schema 声明的列默认值同字`() {
        // Room 只认 @ColumnInfo(defaultValue)，Kotlin 属性默认值改了什么它都不吭声（本类 KDoc 第二条）。
        // schema 里是带引号的 SQL 字面量，剥掉引号才和 Kotlin 侧的裸字符串同字。
        val schemaDefault = fieldsOf(SCHEMA_LATEST_VERSION, TABLE_BOOK_SOURCE).getValue("format")
            .getString("defaultValue")
            .removeSurrounding("'")
        val entityDefault = BookSourceEntity(url = "https://a.test", name = "n", ruleJson = "{}").format

        assertEquals(schemaDefault, entityDefault)
    }

    private companion object {
        /** Room 导出目录的子目录名：数据库类全名 */
        const val DB_SCHEMA_DIR = "com.ebook.db.AppDatabase"

        /** 本模块目录；工作目录落在仓库根时用它定位 schema */
        const val MODULE_DIR = "lib_ebook_db"

        /** 期望的最新 schema 版本；[AppDatabase] 的 @Database(version) 升版时同步改这里 */
        const val SCHEMA_LATEST_VERSION = 8

        const val TABLE_BOOK_SOURCE = "book_source"

        const val TABLE_PAUSED_BOOK = "paused_book"
    }
}
