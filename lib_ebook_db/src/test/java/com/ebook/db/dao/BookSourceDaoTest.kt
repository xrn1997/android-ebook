package com.ebook.db.dao

import android.content.Context
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import com.ebook.db.AppDatabase
import com.ebook.db.entity.BookSourceEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [BookSourceDao] 的回归测试（Robolectric + Room 内存库，JVM 上直接跑真实 SQL）。
 *
 * 锁的是多书源架构（ADR-0016）里最容易在改造 Manager 时被无声破坏的四条语义：
 * - **自然键 upsert**：主键是 `url`，同 URL 再写是覆盖，表里不会出现同一站点的两行
 * - **统一排序口径**：`weight ASC, added_at ASC, url ASC`（`url` 是同毫秒批量导入时的兜底定序键），
 *   快照 [BookSourceDao.getAll] 与流 [BookSourceDao.observeAll] 必须一致，否则管理页顺序与聚合搜索取源顺序分裂
 * - **删除无保护**：表里每一行都是用户导入的，`deleteByUrl` 不带任何条件
 * - **格式出身列**（ADR-0029）：`format` 原样存取、DAO 不做任何归一化，整行替换时按实体默认值写回 `native`——
 *   它是双格式共存的路由键，被无声改写就会让脚本书源退回原生解析
 *
 * 规则 JSON 内容不在本测试关心范围内——DAO 只搬字符串，`BookSourceRule` 的编解码由
 * `lib_book_common` 的 BookSourceManager 测试覆盖。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BookSourceDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: BookSourceDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        // 不 setDriver(...)：Android 构建的默认驱动是 FrameworkSQLiteDriver，由 Robolectric 模拟实现，
        // 本测试锁的是 DAO 的 SQL 语义而不是某个引擎的行为。要跑生产同款 bundled 引擎的是
        // BookSourceMigration6To7Test，它显式配了 sqlite-bundled-jvm 并 setDriver(BundledSQLiteDriver)
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.bookSourceDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    /**
     * 造一条书源行：默认「启用、权重 0、格式 native、时间戳 0」，
     * 各测试只显式覆盖自己要验的那个字段，避免测试之间因默认值漂移而互相耦合。
     *
     * 注意：这里的默认值是工厂自己写的一份，**不反映实体默认值**——实体默认值由
     * `未显式赋值的字段取实体声明的默认值` 那条用例单独钉住，别指望本文件替你验它。
     */
    private fun source(
        url: String,
        name: String = "测试书源",
        ruleJson: String = """{"url":"$url"}""",
        enabled: Boolean = true,
        weight: Int = 0,
        group: String = "小说",
        format: String = "native",
        addedAt: Long = 0L,
    ) = BookSourceEntity(
        url = url,
        name = name,
        ruleJson = ruleJson,
        enabled = enabled,
        weight = weight,
        group = group,
        format = format,
        addedAt = addedAt,
    )

    @Test
    fun `upsert 写入后可按 url 取回并计入总数`(): Unit = runBlocking {
        dao.upsert(source(url = "https://b.test", name = "笔趣阁"))

        val saved = requireNotNull(dao.getByUrl("https://b.test"))

        assertEquals("笔趣阁", saved.name)
        assertEquals("""{"url":"https://b.test"}""", saved.ruleJson)
        assertEquals(1, dao.getAll().size)
    }

    @Test
    fun `未收录的 url 查不到书源`(): Unit = runBlocking {
        dao.upsert(source(url = "https://b.test"))

        assertNull(dao.getByUrl("https://other.test"))
    }

    @Test
    fun `同 url 再次 upsert 覆盖既有行而非新增第二条`(): Unit = runBlocking {
        dao.upsert(source(url = "https://b.test", name = "旧名", ruleJson = "old", weight = 5))

        dao.upsert(source(url = "https://b.test", name = "新名", ruleJson = "new", weight = 7))

        assertEquals(1, dao.getAll().size)
        val current = requireNotNull(dao.getByUrl("https://b.test"))
        assertEquals("新名", current.name)
        assertEquals("new", current.ruleJson)
        assertEquals(7, current.weight)
    }

    @Test
    fun `整行替换会把未赋值字段写回默认值`(): Unit = runBlocking {
        // 覆盖导入前先取旧行回填，否则用户改过的启用状态与权重会被默认值冲掉（BookSourceDao.upsert KDoc）
        dao.upsert(
            source(
                url = "https://b.test",
                enabled = false,
                weight = 3,
                group = "漫画",
                format = "script",
                addedAt = 111L,
            )
        )

        dao.upsert(source(url = "https://b.test"))

        val current = requireNotNull(dao.getByUrl("https://b.test"))
        assertTrue(current.enabled)
        assertEquals(0, current.weight)
        assertEquals("小说", current.group)
        // 路由键也被整行替换冲回默认值：覆盖导入想把脚本书源并进原生行，format 必须显式带上
        assertEquals("native", current.format)
        assertEquals(0L, current.addedAt)
    }

    @Test
    fun `未显式赋值的字段取实体声明的默认值`(): Unit = runBlocking {
        // 故意绕开通用工厂：只给三个必填参数，其余走 BookSourceEntity 自己声明的默认值。
        // 「整行替换写回默认值」那条用例锁的是工厂重写的一份默认值，实体默认值改了它照样绿，故单独钉这里。
        val before = System.currentTimeMillis()
        dao.upsert(BookSourceEntity(url = "https://b.test", name = "笔趣阁", ruleJson = "rule"))

        val saved = requireNotNull(dao.getByUrl("https://b.test"))

        assertTrue(saved.enabled)
        assertEquals(0, saved.weight)
        assertEquals("小说", saved.group)
        // 格式出身默认 native：v5 升上来的存量行也是这个值，两者必须同字才谈得上「按出身路由」
        assertEquals("native", saved.format)
        // addedAt 默认取当前毫秒：必须落在本次用例的起止时刻之间，而不是 Kotlin 侧的 0 或库侧的缺省
        assertTrue(
            "addedAt 默认值应为入库时刻，实际为 ${saved.addedAt}",
            saved.addedAt in before..System.currentTimeMillis(),
        )
    }

    @Test
    fun `format 原样存取且 DAO 不做任何归一化`(): Unit = runBlocking {
        // 路由键的取值是 `lib_ebook_api` 那侧的事（未知值由 SourceFormat.fromRaw 回落 native），
        // 本表只负责按行原样搬进去再搬出来。哪天 DAO 上长了大小写折叠或枚举校验，这条会先红。
        dao.upsertAll(
            listOf(
                source(url = "https://s.test", name = "脚本书源", format = "script"),
                source(url = "https://n.test", name = "原生书源", format = "native"),
            )
        )

        assertEquals(
            listOf("native", "script"),
            dao.getAll().map { it.format },
        )
    }

    @Test
    fun `全部书源按权重再按导入时间升序返回`(): Unit = runBlocking {
        dao.upsertAll(
            listOf(
                source(url = "https://c.test", name = "C", weight = 1, addedAt = 100L),
                source(url = "https://a.test", name = "A", weight = 0, addedAt = 200L),
                source(url = "https://b.test", name = "B", weight = 0, addedAt = 100L),
            )
        )

        assertEquals(
            listOf("B", "A", "C"),
            dao.getAll().map { it.name },
        )
    }

    @Test
    fun `权重与导入时间全同时按 url 升序定序`(): Unit = runBlocking {
        // 模拟批量导入整包社区书源：weight 同为 0、addedAt 同为 0（真实场景是同毫秒），只有主键 url 能定序
        dao.upsertAll(
            listOf(
                source(url = "https://z.test", name = "Z"),
                source(url = "https://a.test", name = "A"),
                source(url = "https://m.test", name = "M"),
            )
        )

        val expected = listOf("A", "M", "Z")
        assertEquals(expected, dao.getAll().map { it.name })
        // 三条查询口径必须一致，否则管理页顺序与聚合搜索取源顺序分裂
        assertEquals(expected, dao.getEnabled().map { it.name })
        assertEquals(expected, dao.observeAll().first().map { it.name })
    }

    @Test
    fun `观察流的排序口径与快照一致且含禁用行`(): Unit = runBlocking {
        dao.upsertAll(
            listOf(
                source(url = "https://c.test", name = "C", weight = 1),
                source(url = "https://b.test", name = "B", weight = 0, enabled = false),
            )
        )

        val emitted = dao.observeAll().first()

        assertEquals(listOf("B", "C"), emitted.map { it.name })
        // 名字里的「与快照一致」得在同一数据集上真比一次，否则两条查询各自漂移也测不出来
        assertEquals(dao.getAll().map { it.url }, emitted.map { it.url })
    }

    @Test
    fun `getEnabled 只返回启用中的书源`(): Unit = runBlocking {
        dao.upsertAll(
            listOf(
                source(url = "https://on1.test", name = "在架1", weight = 1),
                source(url = "https://off.test", name = "已禁用", weight = 0, enabled = false),
                source(url = "https://on2.test", name = "在架2", weight = 2),
            )
        )

        assertEquals(listOf("在架1", "在架2"), dao.getEnabled().map { it.name })
    }

    @Test
    fun `setEnabled 只改启用状态不动其他列`(): Unit = runBlocking {
        dao.upsert(source(url = "https://b.test", name = "笔趣阁", ruleJson = "rule", weight = 4))

        dao.setEnabled("https://b.test", false)

        val current = requireNotNull(dao.getByUrl("https://b.test"))
        assertFalse(current.enabled)
        assertEquals("笔趣阁", current.name)
        assertEquals("rule", current.ruleJson)
        assertEquals(4, current.weight)
    }

    @Test
    fun `删除任意书源返回受影响行数`(): Unit = runBlocking {
        dao.upsert(source(url = "https://user.test"))

        assertEquals(1, dao.deleteByUrl("https://user.test"))
        assertEquals(0, dao.getAll().size)
    }

    @Test
    fun `删除不存在的 url 返回零行`(): Unit = runBlocking {
        dao.upsert(source(url = "https://b.test"))

        assertEquals(0, dao.deleteByUrl("https://missing.test"))
        assertEquals(1, dao.getAll().size)
    }

    @Test
    fun `批量 upsertAll 一次落库且按 url 覆盖`(): Unit = runBlocking {
        dao.upsertAll(
            listOf(
                source(url = "https://a.test", name = "A"),
                source(url = "https://b.test", name = "B"),
            )
        )

        dao.upsertAll(
            listOf(
                source(url = "https://b.test", name = "B 改名"),
                source(url = "https://c.test", name = "C"),
            )
        )

        assertEquals(3, dao.getAll().size)
        assertEquals(listOf("A", "B 改名", "C"), dao.getAll().map { it.name })
    }
}
