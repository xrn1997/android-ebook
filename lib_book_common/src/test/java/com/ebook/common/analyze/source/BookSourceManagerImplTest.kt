package com.ebook.common.analyze.source

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.ebook.api.entity.BookSourceRule
import com.ebook.api.entity.FindRule
import com.ebook.api.entity.KindItem
import com.ebook.api.entity.SourceDefinition
import com.ebook.api.entity.SourceFormat
import com.ebook.db.dao.BookSourceDao
import com.ebook.db.entity.BookShelfEntity
import com.ebook.db.entity.BookSourceEntity
import com.ebook.db.entity.LibraryEntity
import com.ebook.db.entity.SearchBookEntity
import com.ebook.db.entity.WebChapterEntity
import com.ebook.source.analyze.AggregateSearchEvent
import com.ebook.source.analyze.BookParser
import com.ebook.source.analyze.JsoupBookParser
import com.ebook.source.analyze.ScriptBookParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [BookSourceManagerImpl] 的回归测试（Robolectric + 手写 [FakeBookSourceDao]）。
 *
 * 为什么必须 Robolectric：本类构造要拿 SharedPreferences 与 Application 上下文，
 * 纯 JVM 起不来（与 [com.ebook.common.domain.AndroidUserSessionManagerTest] 同一套跑法）。
 * 为什么不建 Room 内存库跑真 DAO：本测试锁的是 Manager 的**决策**（以谁为默认源、什么时候失效缓存、
 * 元数据怎么出门），不是 SQL 语义；SQL 语义由 `lib_ebook_db` 的 BookSourceDaoTest 锁。
 * 假件按真 DAO 的语义逐条对齐（整行 REPLACE、按 URL 删任意行、三条查询同排序口径），
 * 真 DAO 语义若变那边会红、这里的假件需同步——这是用假件换掉内存库付的代价。
 *
 * **前置事实：应用不随包携带任何书源**（合法性考虑），`book_source` 表里的每一行都由用例显式写入
 * （`addSource` 的写路径或 [FakeBookSourceDao.seed] 的直插）。没有首启灌库、也没有任何同步读面，
 * 因此**没有任何后台任务需要驱动**：「当前默认源是谁」在用例里一律经 [currentDefault] 从订阅面现算。
 *
 * 锁住的关键行为：
 * - **零源起步**：空表即空清单、默认源为 null（页面据此进引导态），第一条导入的启用源顶上；
 * - 默认源每次从 Room 现算（SP 命中且启用 → 否则第一条启用行，两种格式平等）：禁用当前默认源即回落、
 *   全部禁用即为 null、再次启用即恢复；
 * - 覆盖导入保留旧行的 `addedAt`/`weight`/`group`（REPLACE 整行替换的未赋值列会吃实体默认值，
 *   而列表位置与「导入时间」展示就挂在这些列上）；
 * - 规则被覆盖、启用状态被改、源被删除之后 parser 缓存都要失效（否则解析行为与库里的真值不一致）；
 * - **元数据只从订阅面出门**：`observeSources` 带出 `format`（原生规则行 or 脚本行），挂起读面仍是纯规则；
 *   脏行跳过在两侧共用同一条解码路径（否则「页面看得见这行、解析看不见」）；
 * - **聚合搜索的编排**（`searchAcross`）：一条源失败不带走整条流、空页即该源到底、并发度压在风控
 *   可容忍的上限内、全部源结束才收 AllFinished。这组用例注入假 parser 工厂（见
 *   [newManagerWithParserFactory]）——真工厂会直接向第三方站点发请求，测试面不该有网络。
 * - **`format` 列的双后端路由**：script 行经 `getParserFor` 拿到真解析器（求值抛类型化异常，
 *   与「书源不存在」的 null 严格区分）、原始 JSON 不经翻译直达 parser 工厂、规则类型化读面看不见它
 *   （默认源则两种格式平等），且同 URL 换回原生格式后不残留脚本解析器（见「脚本书源」那组用例）。
 * - **脚本行不被原生解码失败吞掉**：`toItem` 只对原生行解 `rule_json`，脚本行的条目 rule 由实体列合成。
 *   曾经它对所有行都按 [BookSourceRule] 解一遍，社区 JSON 里同名不同形的键（`weight: null`、
 *   `headers` 写成数组）会抛异常并按「脏行」整行丢弃——库里有行、管理页却什么都没有，
 *   而「可导入、可管理」正是脚本书源阶段一的验收状态（用例见「rule_json 与原生类型冲突」那条）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BookSourceManagerImplTest {

    private companion object {
        /** 单源用例的主源地址：零内置源下它就是「用户导入的第一条」，因而也就是现算出的默认源 */
        const val PRIMARY_URL = "https://primary.example"

        /** 单源用例里后导入的那条源（默认源回落与清单排序用例的第二条） */
        const val SECOND_URL = "https://second.example"

        /** 聚合搜索那组用例的两条源（URL 字典序即断言里的排序序） */
        const val SOURCE_A = "https://a.example"
        const val SOURCE_B = "https://b.example"

        /** 脚本书源用例的源地址：独占一张「只有脚本行」的表 */
        const val SCRIPT_URL = "https://script.example"

        /** 实现里的私有常量（PREFS_NAME / KEY_CURRENT_SOURCE），按字面钉死，改动实现需同步改这里 */
        const val PREFS_NAME = "book_source_prefs"
        const val KEY_DEFAULT_URL = "current_source_url"
    }

    private lateinit var context: Context
    private lateinit var dao: FakeBookSourceDao

    private fun savedDefaultUrl(): String? =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_DEFAULT_URL, null)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        dao = FakeBookSourceDao()
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
    }

    /** 被测对象；[sourceDao] 单独传，便于同一用例里放第二个「库」（模拟二次启动） */
    private fun newManager(sourceDao: BookSourceDao = dao) =
        BookSourceManagerImpl(context, sourceDao, OkHttpClient())

    /**
     * 注入 parser 工厂的被测对象。
     *
     * 真工厂会构造 [JsoupBookParser]（一发出去就是真网络请求），单测一律换成
     * [FakeSearchParser]：本类的用例锁的是**编排**（缓存何时失效、聚合怎么并发），
     * 不是「按规则怎么解 HTML」。
     *
     * 工厂的入参已随实现一起从 [BookSourceRule] 换成 [SourceDefinition]（原生/脚本两条后端都从
     * 这一个口子诞生），所以注入方一律 `is SourceDefinition.Native -> 假件`，
     * Script 分支**抛断**而不是兜个 null——见实现类该参数的 KDoc：静默兜住等于吞掉接线错误。
     * 唯一例外是 `脚本行经工厂诞生且原始 JSON 原样递出` 那条用例，它测的就是 Script 分支本身。
     */
    private fun newManagerWithParserFactory(
        sourceDao: BookSourceDao = dao,
        parserFactory: (SourceDefinition) -> BookParser,
    ) = BookSourceManagerImpl(context, sourceDao, OkHttpClient(), parserFactory = parserFactory)

    /**
     * 现算当前默认源（订阅面首值）；零启用行时为 null。
     *
     * 默认源已**没有同步读面**（同步出口必然要求随包资产或第二份内存缓存），所以「谁是当前默认源」
     * 这个断言在所有用例里都只能走这条挂起路径，别再想一步到位的同步属性。
     */
    private suspend fun currentDefault(manager: BookSourceManager): SourceDefinition? =
        manager.observeDefaultSource().first()

    /**
     * 「只会服务原生规则」的假工厂：把 [SourceDefinition] 拆成原生分支，Script 分支**抛断**。
     *
     * 为什么不在这里返回 null 或复用原生假件兜一手：那样等于把「哪天长出一条把脚本行当原生规则解的路」
     * 这种接线错误咽掉——本类的假工厂存在的意义就是替用例盯住真实构造，兜住即失明。
     * 抛断同时也是一条断言：这组用例**只装了原生行**，2d 起脚本行确实会进聚合候选集，
     * 于是「有脚本行走到工厂」在这些用例里就意味着候选集形状与用例假设不符（要验脚本行请另用
     * 两分支都给的工厂，见「聚合搜索候选集包含脚本行并产出其结果」）。
     * 默认源候选是条目面的全部启用行（两种格式平等），但用本工厂的那组用例只播原生行，
     * 故工厂不会被脚本定义触达；要验「脚本行当默认源」请用两分支都给的工厂。
     */
    private fun nativeOnlyFactory(newParser: (BookSourceRule) -> BookParser): (SourceDefinition) -> BookParser =
        { definition ->
            when (definition) {
                is SourceDefinition.Native -> newParser(definition.rule)
                is SourceDefinition.Script -> throw AssertionError(
                    "这组用例只装原生行：脚本行走到工厂说明候选集形状与用例假设不符（脚本源参与聚合已在 2d 落地）",
                )
            }
        }

    private fun rule(url: String, name: String = "源<$url>", enabled: Boolean = true, weight: Int = 0) =
        BookSourceRule(name = name, url = url, enabled = enabled, weight = weight)

    /**
     * 造一行落库实体。
     *
     * 应用不随包携带书源，行的一切实情只住在列上：`addedAt` 必须由调用方给（覆盖导入要沿用旧行时刻），
     * 别无 `isUserImported` 之类的身份位——表里每一行都是用户导入的，任何一行都可删。
     */
    private fun entityOf(rule: BookSourceRule, addedAt: Long) = BookSourceEntity(
        url = rule.url,
        name = rule.name,
        ruleJson = Json.encodeToString(BookSourceRule.serializer(), rule),
        enabled = rule.enabled,
        weight = rule.weight,
        group = rule.group,
        addedAt = addedAt,
    )

    // region 零源与默认源

    /**
     * 零源是本应用**出厂即是的常态**（不随包携带任何书源，见类 KDoc）：空表即空清单，
     * 订阅面必须发 null 让页面进引导态，而不是让用户对着永远加载不出来的空白页。
     */
    @Test
    fun `零源时清单为空且默认源为 null`(): Unit = runTest {
        val manager = newManager()

        assertTrue(manager.getAllSources().isEmpty())
        assertNull("没有任何启用源时订阅面必须发 null，页面据此进引导态", manager.observeDefaultSource().first())
    }

    /**
     * 与「零源」配对的不变式：第一条导入的启用源就是默认源。
     * 默认源每次从 Room 现算，它的候选必须包含这次刚写进去的行——否则用户导入完仍停在引导态。
     *
     * 两条断言各锁一半，缺一不可：
     * - 订阅面给出这条新源 —— 由 [BookSourceManagerImpl.defaultFromRows] 现算保证；
     * - SP 也记下它 —— 由写路径的收敛（`syncDefaultAfterWrite`）保证，也就是「用户上次主动选了哪个源」
     *   这份跨启动记忆。少了后一条，用户在多源场景里选中的源下次启动会被「按权重取第一条」换掉，
     *   而订阅面当场看不出任何异常（现算照样给出一条可用源）。
     */
    @Test
    fun `导入第一条启用源即成为默认源`(): Unit = runTest {
        val manager = newManager()

        manager.addSource(rule("https://only.example"))

        assertEquals("https://only.example", manager.observeDefaultSource().first()?.sourceUrl)
        assertEquals(
            "写路径要把现算到的选择固化进 SP（跨启动记忆），不能只靠订阅面现算",
            "https://only.example",
            savedDefaultUrl(),
        )
    }

    /**
     * 删除不再有身份保护：表里每一行都是用户导入的，所以任意一行都可删。
     * 删掉最后一行即回到零源态，订阅面必须跟着发 null（否则书城还指着一个已经删掉的源）。
     */
    @Test
    fun `任意一条源都可删除`(): Unit = runTest {
        val manager = newManager()
        manager.addSource(rule("https://a.example"))

        assertTrue(manager.removeSource("https://a.example").isSuccess)

        assertTrue(manager.getAllSources().isEmpty())
        assertNull(manager.observeDefaultSource().first())
    }

    // endregion
    // region 增删改

    @Test
    fun `addSource 新 URL 落库`(): Unit = runTest {
        val manager = newManager()

        assertTrue(manager.addSource(rule("https://new.example")).isSuccess)

        val row = dao.getByUrl("https://new.example")!!
        assertEquals("源<https://new.example>", row.name)
    }

    /**
     * REPLACE 整行替换会把未回填的列一起吃实体默认值，`addedAt` 就是其中一个：
     * 它是列表排序的次级键、也是管理页「导入时间」展示的来源，覆盖一次导入就刷成今天等于把用户的
     * 列表位置与历史一起抹掉。故 addSource 必须先把旧行的 `addedAt` 读出来带过去。
     */
    @Test
    fun `addSource 覆盖既有行时保持原入库时间`(): Unit = runTest {
        dao.seed(entityOf(rule(PRIMARY_URL), addedAt = 1L))
        val manager = newManager()

        assertTrue(manager.addSource(rule(PRIMARY_URL, name = "改过的源")).isSuccess)

        val row = dao.getByUrl(PRIMARY_URL)!!
        assertEquals("改过的源", row.name)
        assertEquals("REPLACE 会把 addedAt 吃成当前时间，列表位置与导入时间展示都被刷新", 1L, row.addedAt)
    }

    @Test
    fun `addSource 拒绝空白 URL`(): Unit = runTest {
        val manager = newManager()

        val failure = manager.addSource(rule(url = "  ", name = "没地址")).exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
    }

    /**
     * 删除的失败成因**只剩一条**：这行本就不存在。表里没有受保护的行（应用不随包携带书源），
     * 所以这里不再断言「内置源删不掉」，但要钉住失败话术仍分得清——曾经的实现会拿
     * 「不可删除」去答一个不存在的 URL，用户照着话术找不到任何可操作的东西。
     */
    @Test
    fun `removeSource 对不存在的 URL 报不存在`(): Unit = runTest {
        val manager = newManager()
        manager.addSource(rule("https://user.example"))

        assertTrue(manager.removeSource("https://user.example").isSuccess)
        assertNull(dao.getByUrl("https://user.example"))

        val missingFailure = manager.removeSource("https://never.imported").exceptionOrNull()!!
        assertTrue("不存在的 URL 该报「不存在」而不是别的成因", missingFailure.message!!.contains("不存在"))
    }

    @Test
    fun `禁用当前默认源后自动落到另一条启用源并写回 SP`(): Unit = runTest {
        val manager = newManager()
        // 第一条导入即默认源（SP 记下它），第二条只是备用
        manager.addSource(rule(PRIMARY_URL))
        manager.addSource(rule(SECOND_URL, weight = 5))
        assertEquals(PRIMARY_URL, currentDefault(manager)?.sourceUrl)

        manager.setEnabled(PRIMARY_URL, false)

        assertEquals("SP 命中已禁用行 → 回落到第一条启用行", SECOND_URL, currentDefault(manager)?.sourceUrl)
        assertEquals("回落结果必须固化进 SP，否则下次启动又指回那个禁用行", SECOND_URL, savedDefaultUrl())
        assertTrue((currentDefault(manager) as SourceDefinition.Native).rule.enabled)
    }

    @Test
    fun `启用其它源不会抢走默认源`(): Unit = runTest {
        val manager = newManager()
        manager.addSource(rule(PRIMARY_URL))
        manager.addSource(rule(SECOND_URL, weight = 5))
        manager.setEnabled(SECOND_URL, false)

        manager.setEnabled(SECOND_URL, true)

        assertEquals(
            "SP 仍记着 PRIMARY_URL，重新启用别的源不该改写默认源",
            PRIMARY_URL,
            currentDefault(manager)?.sourceUrl,
        )
    }

    @Test
    fun `全部禁用后默认源为空，重新启用即恢复`(): Unit = runTest {
        val manager = newManager()
        manager.addSource(rule(PRIMARY_URL))

        manager.setEnabled(PRIMARY_URL, false)

        assertNull("零启用行即零默认源，订阅面必须给 null（页面据此进引导态）", currentDefault(manager))

        manager.setEnabled(PRIMARY_URL, true)

        assertEquals("启用完必须立刻有可用默认源，否则用户仍是无源可用", PRIMARY_URL, currentDefault(manager)?.sourceUrl)
        assertEquals(PRIMARY_URL, savedDefaultUrl())
        assertNotNull(
            "清空-恢复不该打断解析链路：按 URL 仍取到 parser",
            manager.getParserFor(PRIMARY_URL),
        )
    }

    @Test
    fun `无可用默认源时新导入的启用源直接顶上`(): Unit = runTest {
        val manager = newManager()
        manager.addSource(rule(PRIMARY_URL))
        manager.setEnabled(PRIMARY_URL, false)
        assertNull(currentDefault(manager))

        assertTrue(manager.addSource(rule("https://fresh.example")).isSuccess)

        assertEquals("https://fresh.example", currentDefault(manager)?.sourceUrl)
        assertEquals("提升结果要固化进 SP，冷启动才不会又回到无源态", "https://fresh.example", savedDefaultUrl())
    }

    @Test
    fun `setDefaultSource 更新默认源、SP 与订阅，指向不存在的源时被忽略`(): Unit = runTest {
        val manager = newManager()
        manager.addSource(rule("https://default.example"))

        manager.setDefaultSource("https://default.example")

        assertEquals("https://default.example", currentDefault(manager)?.sourceUrl)
        assertEquals("https://default.example", savedDefaultUrl())

        manager.setDefaultSource("https://not.in.db")
        assertEquals("库里没有的行不该把默认源指向空气", "https://default.example", currentDefault(manager)?.sourceUrl)
        assertEquals("https://default.example", savedDefaultUrl())
    }

    @Test
    fun `把禁用源设为默认源时顺带启用它`(): Unit = runTest {
        // 不变式「默认源永不为禁用态」：不启用就会被回落规则换掉，用户表现为「点了没换」
        val manager = newManager()
        manager.addSource(rule("https://off.example"))
        manager.setEnabled("https://off.example", false)

        manager.setDefaultSource("https://off.example")

        assertTrue((currentDefault(manager) as SourceDefinition.Native).rule.enabled)
        assertTrue(dao.getByUrl("https://off.example")!!.enabled)
    }

    // endregion
    // region parser 缓存

    /**
     * 零内置源下用例里唯一那条源就是默认源，所以本用例同时覆盖「默认源的 parser 也走同一个 LRU、
     * 不额外复制实例」——旧实现曾有一份「规则 + parser」的内存快照装默认源，那份不在 LRU 里，
     * 于是「取默认源会不会多造一个 parser」需要单独立一条用例；快照拆掉后这一点由本用例免费守住。
     */
    @Test
    fun `同一 URL 两次取到同一 parser 实例`(): Unit = runTest {
        val manager = newManager()
        manager.addSource(rule("https://a.example"))

        assertSame(manager.getParserFor("https://a.example"), manager.getParserFor("https://a.example"))
    }

    @Test
    fun `LRU 容量为三，第四个源挤掉最久未用的那个`(): Unit = runTest {
        val manager = newManager()
        val u1 = "https://u1.example"
        val u2 = "https://u2.example"
        val u3 = "https://u3.example"
        val u4 = "https://u4.example"
        listOf(u1, u2, u3, u4).forEach { manager.addSource(rule(it)) }

        val p1 = manager.getParserFor(u1)!!
        val p2 = manager.getParserFor(u2)!!
        manager.getParserFor(u3)
        assertSame(p1, manager.getParserFor(u1)) // u1 刚被访问过，于是 u2 成了最久未用的
        manager.getParserFor(u4)

        assertNotSame("u2 应已被淘汰并重建", p2, manager.getParserFor(u2))
    }

    @Test
    fun `本地书 tag 与空白 URL 不查库直接返回 null`(): Unit = runTest {
        val manager = newManager()

        assertNull(manager.getParserFor("loc_book"))
        assertNull(manager.getParserFor(""))
        assertNull(manager.getParserFor("   "))
    }

    @Test
    fun `未知 URL 取不到 parser`(): Unit = runTest {
        val manager = newManager()

        assertNull(manager.getParserFor("https://never.example"))
    }

    /**
     * 缓存失效的正面用例。零内置源下这条被覆盖的源**就是**当前默认源（用例里唯一的源），
     * 所以它同时守住「覆盖默认源那一行也必须换 parser」——默认源与其它源共用同一个 LRU，
     * 不存在「缓存清了、默认源那份还留着」的缝隙（旧实现那份「规则 + parser」的内存快照正是这种缝隙，
     * 拆掉它之后 evictParser 一处即可覆盖全类）。
     */
    @Test
    fun `覆盖书源规则后旧 parser 实例被失效`(): Unit = runTest {
        val manager = newManager()
        manager.addSource(rule("https://a.example", name = "旧名"))
        assertEquals("前置条件：这是唯一一条源，也就是当前默认源", "https://a.example", currentDefault(manager)?.sourceUrl)
        val old = manager.getParserFor("https://a.example") as JsoupBookParser

        manager.addSource(rule("https://a.example", name = "新名"))
        val fresh = manager.getParserFor("https://a.example") as JsoupBookParser

        assertNotSame("默认源的 parser 必须随规则一起换，否则 evictParser 只是白做一次", old, fresh)
        assertEquals("新名", fresh.rule.name)
    }

    @Test
    fun `改启用状态后旧 parser 实例被失效`(): Unit = runTest {
        // enabled 由实体列覆盖回 rule，缓存留着就是让旧 rule（enabled=true）继续参与解析
        val manager = newManager()
        manager.addSource(rule("https://a.example"))
        val old = manager.getParserFor("https://a.example") as JsoupBookParser

        manager.setEnabled("https://a.example", false)
        val fresh = manager.getParserFor("https://a.example") as JsoupBookParser

        assertNotSame("setEnabled 也必须失效缓存", old, fresh)
        assertFalse(fresh.rule.enabled)
    }

    @Test
    fun `删除书源后其 parser 缓存被清空`(): Unit = runTest {
        val manager = newManager()
        manager.addSource(rule("https://gone.example"))
        assertNotNull(manager.getParserFor("https://gone.example"))

        assertTrue(manager.removeSource("https://gone.example").isSuccess)

        assertNull(
            "行已删，缓存里留着实例就等于让已删的书源继续解析",
            manager.getParserFor("https://gone.example"),
        )
    }

    // endregion
    // region 读取容错

    @Test
    fun `脏行解码失败只跳过该行，不带走整份清单`(): Unit = runTest {
        val manager = newManager()
        dao.seed(entityOf(rule(PRIMARY_URL), addedAt = 1L))
        dao.seed(BookSourceEntity(url = "https://broken.example", name = "坏行", ruleJson = "{ 这不是合法 JSON"))

        assertEquals("一行脏数据不该把整个书源列表打成空", listOf(PRIMARY_URL), manager.getAllSources().map { it.url })
    }

    @Test
    fun `实体列覆盖规则里的同名字段，库里禁用即禁用`(): Unit = runTest {
        val manager = newManager()
        // rule_json 说 enabled=true / weight=0，实体列说 enabled=false / weight=7
        dao.seed(
            entityOf(rule("https://split.example"), addedAt = 3L)
                .copy(enabled = false, weight = 7)
        )

        val split = manager.getAllSources().first { it.url == "https://split.example" }

        assertFalse("库里禁用、内存 rule 却说启用 —— 视图与真值分裂", split.enabled)
        assertEquals(7, split.weight)
        assertEquals("源<https://split.example>", split.name)
        assertTrue(manager.getEnabledSources().none { it.url == "https://split.example" })
    }

    @Test
    fun `禁用中的源仍可按 URL 取到 parser，归属不被切断`(): Unit = runTest {
        val manager = newManager()
        manager.addSource(rule("https://off.example"))
        manager.setEnabled("https://off.example", false)

        assertNotNull(manager.getParserFor("https://off.example"))
        assertNotNull(manager.getSourceByUrl("https://off.example"))
    }

    // endregion
    // region 脚本书源（format=script 行）

    /**
     * 社区通用格式的最小脚本源样本：判别特征键是 `bookSourceUrl`（原生格式的对应键是 `url`）。
     *
     * 用真实形态而不是随便一段 JSON：`BookSourceRule` 全字段带默认值 + `ignoreUnknownKeys`，
     * 解这份样本会「成功」并得到一条空规则——这正是 script 行必须被挡在规则类型化读面之外的原因
     * （放行不会崩，只会拿着一条没有选择器的空规则去解析，返回空数据且零报错）。
     */
    private val scriptRuleJson = """{"bookSourceName":"脚本测试源","bookSourceUrl":"$SCRIPT_URL"}"""

    /**
     * 真实形态的社区脚本源 JSON，专挑**与原生规则同名但形状不同**的键，按 [BookSourceRule] 解必然抛。
     *
     * 两处冲突都是社区 JSON 的日常写法，不是为测试编出来的畸形数据：
     * - `headers`：原生规则是 `Map<String,String>`，社区源常写成 `[{"key":…,"value":…}]` 数组；
     * - `weight`：原生规则是非空 Int，社区源的「响应速度权重」没测过时直接给 `null`。
     *
     * 为什么单独摆这么一份：`toItem` 曾经对**每一行**都按原生规则解 `rule_json`，解不出来就整行丢弃、
     * 只留一行 ERROR 日志。键名全对不上时（如上面那份最小样本）解码会「成功」并给出空规则，
     * 而键名碰巧同名、形状不同时解码直接抛——于是同一种出身的两行在管理页里一行看得见、
     * 一行凭空消失。「脚本书源能导入、能管理、能禁用」是阶段一的验收状态，页面看不见等于功能不可用。
     */
    private val communityScriptRuleJson = """
        {
          "bookSourceUrl": "$SCRIPT_URL",
          "bookSourceName": "脚本测试源",
          "bookSourceGroup": "社区",
          "bookSourceType": 0,
          "bookSourceComment": "仅用于单测",
          "customOrder": 0,
          "enabled": true,
          "enabledCookieJar": true,
          "weight": null,
          "lastUpdateTime": "2026-09-01 00:00:00",
          "respondTime": 180000,
          "userAgent": "Mozilla/5.0",
          "headers": [
            {"key": "User-Agent", "value": "Mozilla/5.0"}
          ],
          "searchUrl": "/search.php?keyword={{key}}&page={{page}}",
          "bookUrlPattern": ".*${'$'}/book/[0-9]+.*",
          "ruleSearch": {
            "bookList": "class.bookbox",
            "name": "class.bookname a@text",
            "bookUrl": "class.bookname a@href",
            "checkKey": "bookid=([0-9]+)"
          },
          "ruleBookInfo": {
            "name": "class.name@tag.h1@text",
            "author": "class.writer a@text",
            "coverUrl": "class.cover img@src",
            "tocUrl": "class.intro a.0@href",
            "lastChapter": "class.chapter a.0@text"
          },
          "ruleToc": {
            "chapterList": "class.listmain dd",
            "chapterName": "a@text",
            "chapterUrl": "a@href",
            "nextTocUrl": ["id.next", "text.下一页@href"]
          },
          "ruleContent": {
            "content": "id.content@textNodes",
            "title": "class.content_title@text",
            "nextContentUrl": "text.下一页@href",
            "replaceRegex": "##\\s*[章节卷].*",
            "imageRule": {"image": "tag.img@src"}
          },
          "ruleExplore": {
            "bookList": "class.bookbox",
            "name": "class.bookname a@text",
            "bookUrl": "class.bookname a@href"
          }
        }
    """.trimIndent()

    /**
     * 断言「这份 JSON 按**原生**规则解不开」，且抛点就在 [expectedPath] 那个键上。
     *
     * 存在的理由：脚本行不进原生解码路径之后，「清单里有没有这一行」不再由解码结果决定，
     * 于是本组用例的前提（这份样本会让原生解码抛）必须由测试自己钉住。
     * 解码器直接取实现侧那一个 [storageJson] 实例（同模块测试源集看得到 internal），
     * 不再另抄一份「同款配置」——抄来的那份会随实现漂移，留下的是「测了一个已经不存在的前提」；
     * 用同一个实例时实现改了配置或社区样本形状变了，这里立刻红。
     */
    private fun assertNativeDecodeThrows(json: String, expectedPath: String) {
        val failure = runCatching {
            storageJson.decodeFromString(BookSourceRule.serializer(), json)
        }.exceptionOrNull()
        assertNotNull("前提：这份社区 JSON 按原生规则解要抛（$expectedPath）", failure)
        assertTrue(
            "前提：抛点应在 $expectedPath，实际消息：${failure?.message?.take(200)}",
            failure?.message?.contains(expectedPath) == true,
        )
    }

    /**
     * 直接经 DAO 播种一条脚本书源行，绕过 Manager 的写路径。
     *
     * 为什么还要绕过（`addScriptSource` 已经能写脚本行）：两条路径各锁各的东西。本辅助负责**前提**——
     * 「库里此刻存在一行 script」，包括写入口给不出的形态（`rule_json` 与原生类型形状冲突的社区原文、
     * 任意 `enabled`/`url` 组合）；`addScriptSource` **怎么写**由下面那组 addScriptSource 用例锁。
     * 播种后表里只有这一条脚本行，清单里也只有它——断言「规则类型化读面为空」不会被别的行污染。
     */
    private fun seedScriptRow(
        url: String = SCRIPT_URL,
        ruleJson: String = scriptRuleJson,
        enabled: Boolean = true,
    ) {
        dao.seed(
            BookSourceEntity(
                url = url,
                name = "脚本测试源",
                ruleJson = ruleJson,
                format = SourceFormat.SCRIPT.raw,
                enabled = enabled,
            )
        )
    }

    /**
     * 阶段一验收的最低要求：导入过的脚本书源**必须在管理页清单里看得见**。
     *
     * 独立于下一条用例存在，是为了把「看得见」这件事与「解码会不会抛」分开钉：
     * 这份最小样本按原生规则解不会报错（全字段有默认值 + `ignoreUnknownKeys`），
     * 所以「行是否出现」与「解得开解不开」是两条独立的断言，混在一条里挂了分不清是哪种退化。
     */
    @Test
    fun `script 行出现在 observeSources 清单里且带 SCRIPT 出身`() = runTest {
        seedScriptRow()
        val manager = newManager()

        val item = manager.observeSources().first().single { it.rule.url == SCRIPT_URL }

        assertEquals(SourceFormat.SCRIPT, item.format)
        assertEquals("脚本测试源", item.rule.name)
        assertTrue("禁用开关要能对上库里的真值", item.rule.enabled)
    }

    /**
     * PART 0 的主用例：**rule_json 与原生类型冲突的脚本行不能被整行丢掉**。
     *
     * 修复前的形态是「导入成功、库里有行、管理页什么都没有」，只有一行 ERROR 日志——
     * 因为 `toItem` 对每一行都按 [BookSourceRule] 解 `rule_json`，这份样本的 `headers`（数组 vs Map）
     * 与 `weight`（null vs 非空 Int）都会让解码抛异常并 `return null`。
     *
     * 修复后脚本行不再进那条解码路径：条目里的 `rule` 由实体列合成，**只用于展示**
     * （名字/地址/启用态），求值另有 [BookSourceManager.getParserFor] 的格式路由（本用例一并钉住）。
     * 同时规则类型化读面必须仍然看不见它——那才是「不会有人拿着空规则去发请求」的防线。
     */
    @Test
    fun `脚本行的 rule_json 与原生类型冲突时仍在清单里且仍能取到桩`() = runTest {
        // 前置条件：这份样本按原生规则解**必然抛**，且两处同名不同形的键各抛一次。
        // 不钉这一步就分不清「清单里看不见」是「解码抛异常丢行」还是「解出一条空规则」，根因会糊。
        assertNativeDecodeThrows(communityScriptRuleJson, "$.weight")
        assertNativeDecodeThrows(communityScriptRuleJson.replace("\"weight\": null,", ""), "$.headers")
        seedScriptRow(ruleJson = communityScriptRuleJson)
        val manager = newManager()

        val items = manager.observeSources().first()

        val item = items.single { it.rule.url == SCRIPT_URL }
        assertEquals(SourceFormat.SCRIPT, item.format)
        // 展示字段全部来自实体列（rule_json 根本没被解）
        assertEquals("脚本测试源", item.rule.name)
        assertEquals(SCRIPT_URL, item.rule.url)
        assertTrue(item.rule.enabled)
        assertEquals(
            "展示用 rule 是空壳：没有选择器，页面必须先看 format 再决定渲染什么",
            "",
            item.rule.ruleContent.content,
        )
        assertEquals(SourceFormat.SCRIPT.raw, dao.getByUrl(SCRIPT_URL)?.format)

        assertTrue(
            "看不见 ≠ 解得了：这类行的求值仍必须路由到真解析器，而不是 null（null 在链路上是「书源已失效」）",
            manager.getParserFor(SCRIPT_URL) is ScriptBookParser,
        )
        assertEquals(
            "本修复不放宽规则类型化读面的格式边界，否则调用方会拿这条空壳规则去发请求",
            emptyList<BookSourceRule>(),
            manager.getAllSources(),
        )
        assertNull(manager.getSourceByUrl(SCRIPT_URL))
    }

    @Test
    fun `getParserFor 对 script 行返回真解析器而不是 null`(): Unit = runTest {
        seedScriptRow()
        val manager = newManager()

        val parser = manager.getParserFor(SCRIPT_URL)

        assertTrue(
            "源在库里、能力可能不足这件事必须由解析器承载：null 在消费链上的语义是「书源不存在」，" +
                "书架里的书会因此被报成「书源已失效」而不是「这条规则解不动」。" +
                "取 parser 本身也不得因规则内容失败——坏 JSON 要等到求值才报（见下一条）",
            parser is ScriptBookParser,
        )
    }

    /**
     * 真解析器的**惰性装载**是 getParserFor 契约的另一半：上一条只证明「拿到的对象类型对」，
     * 本条证明「坏规则不会在取 parser 时炸、也不会静默给出空结果」。
     *
     * 两个失败时点必须分开：构造期抛会让 [BookSourceManagerImpl.getParserFor] 在缓存锁内炸出
     * 未预期异常（那里只处理「没这行」）；求值期返回空值则等于对用户撒谎「这个源没有这条信息」，
     * 而真相是「这行的 JSON 读不懂」。null 恒等于「书源不存在」，两种结局都不能借用它。
     *
     * 三个入口各测一次（各自摸 rules 的路径不同）：只要有任何一个成员退化成吞异常返回空值，
     * 页面上就是「这本书没有章节」这种彻底的误导，而不是可解释的一句「规则读不懂」。
     */
    @Test
    fun `rule_json 坏掉的脚本行求值抛类型化装载失败而不是 null`(): Unit = runTest {
        seedScriptRow(ruleJson = """{"bookSourceUrl":}""")
        val manager = newManager()
        val parser = manager.getParserFor(SCRIPT_URL)
        assertNotNull("契约：脚本行永不 null，取 parser 也不因坏 JSON 失败", parser)

        val failures = listOf(
            "searchBook" to runCatchingBlocking { parser!!.searchBook("都市", 1) },
            "getBookInfo" to runCatchingBlocking {
                parser!!.getBookInfo(BookShelfEntity().apply { noteUrl = "$SCRIPT_URL/book/1.html" })
            },
            "fetchLibraryData" to runCatchingBlocking { parser!!.fetchLibraryData() },
        )

        failures.forEach { (who, failure) ->
            val message = failure?.message
            assertNotNull(
                "$who 应抛类型化装载失败，实际是 ${failure?.javaClass?.name ?: "没抛异常"}",
                message,
            )
            assertTrue(
                "$who 的消息要能直接进用户文案（须含「脚本书源 JSON 无法解析」）：$message",
                message!!.contains("脚本书源 JSON 无法解析"),
            )
        }
    }

    @Test
    fun `script 行不进入规则类型化读面但仍是表里的一行`(): Unit = runTest {
        seedScriptRow()
        val manager = newManager()

        assertEquals("getAllSources 只给原生规则", emptyList<BookSourceRule>(), manager.getAllSources())
        assertEquals(
            "getEnabledSources 是规则类型化读面，脚本源参与聚合走条目面（见 searchAcross）",
            emptyList<BookSourceRule>(),
            manager.getEnabledSources(),
        )
        assertNull(
            "getSourceByUrl 对脚本行给 null，否则调用方会拿到一条空规则去解析",
            manager.getSourceByUrl(SCRIPT_URL),
        )

        assertNotNull("行必须留在库里——原始规则已导入，待引擎升级后自动生效", dao.getByUrl(SCRIPT_URL))
        assertEquals(SourceFormat.SCRIPT.raw, dao.getByUrl(SCRIPT_URL)?.format)
    }

    /**
     * 2e 反转：脚本行**有**当默认源的资格。
     * 载体是 [SourceDefinition.Script]（展示信息来自实体列），按 URL 取 parser 仍是真解析器——
     * 「被立为默认源」不改变求值路由，改变的只是书城把它当基准。
     * 默认源如今只剩订阅面一个出口（每次现算），所以断言就落在这一个面上。
     */
    @Test
    fun `只有脚本行时它被立为默认源且载体是 Script`(): Unit = runTest {
        seedScriptRow()
        val manager = newManager()

        val observed = manager.observeDefaultSource().first()
        assertTrue("唯一启用行是脚本行，它必须被立为默认源，实际 $observed", observed is SourceDefinition.Script)
        assertEquals(SCRIPT_URL, observed?.sourceUrl)
        assertEquals("展示名来自实体列", "脚本测试源", observed?.displayName)
        assertTrue(
            "立为默认源不改变求值路由：按 URL 仍是真解析器",
            manager.getParserFor(SCRIPT_URL) is ScriptBookParser,
        )
    }

    @Test
    fun `setDefaultSource 指向脚本行时生效且 SP 同步`(): Unit = runTest {
        seedScriptRow()
        val manager = newManager()
        manager.addSource(rule("https://native.example", weight = 5))
        manager.setDefaultSource("https://native.example")
        assertEquals("https://native.example", currentDefault(manager)?.sourceUrl)

        manager.setDefaultSource(SCRIPT_URL)

        assertEquals(
            "脚本行是合法的默认源候选：设置必须生效",
            SCRIPT_URL,
            currentDefault(manager)?.sourceUrl,
        )
        assertEquals("SP 记着用户上次主动选的源，两种格式一视同仁", SCRIPT_URL, savedDefaultUrl())
    }

    /**
     * 路由走的是 [BookSourceManagerImpl] 唯一的 parser 诞生点（工厂），而不是偷偷 new 一个桩。
     *
     * 「只经本工厂诞生」这条不变式一旦破了，注入假工厂的用例就再也锁不住某条路径的真实构造；
     * 本用例同时钉住原始 JSON **逐字**递出——脚本书源的规则不翻译，一旦被改写就偏离社区格式。
     */
    @Test
    fun `脚本行经工厂诞生且原始 JSON 原样递出`(): Unit = runTest {
        seedScriptRow()
        val seen = mutableListOf<SourceDefinition>()
        val injectedScriptParser = FakeSearchParser(SCRIPT_URL)
        val manager = newManagerWithParserFactory { definition ->
            seen += definition
            when (definition) {
                is SourceDefinition.Script -> injectedScriptParser
                is SourceDefinition.Native -> FakeSearchParser(definition.rule.url)
            }
        }

        assertSame(
            "工厂给什么就诞生什么：格式路由只经 parserFactory 一处，Manager 不在别处偷偷 new 真后端",
            injectedScriptParser,
            manager.getParserFor(SCRIPT_URL),
        )

        // 库里只有这一条脚本行，取它 parser 时工厂恰好被叫一次；筛 Script 分支是为了排除任何
        // 「被当成原生行又解一遍规则」的旁路（那时这里会出现 Native 分支的条目）
        val scriptDefinitions = seen.filterIsInstance<SourceDefinition.Script>()
        assertEquals(
            "脚本行只该被路由一次，且必须是 Script 分支而不是又解一遍原生规则",
            1,
            scriptDefinitions.size,
        )
        assertEquals(scriptRuleJson, scriptDefinitions.single().rawJson)
    }

    /**
     * LRU 只按 URL 缓存，格式翻转若不清缓存，缓存就成了「第二个格式事实源」：
     * 同 URL 改回原生格式后仍拿到脚本解析器，用户看到的现象是「明明换成了原生源，解析还是不可用」。
     *
     * 默认源与其它源共用同一个 LRU（既无内存快照、也无专属缓存格），所以本用例不需要再拿一条原生源
     * 占住默认源来「逼」它走 LRU 那一格——缓存随格式翻转一起失效，这件事实与谁是默认源无关。
     *
     * 方向原本只有「脚本 → 原生」：那时 Manager 还没有能写出脚本行的写入口。
     * `addScriptSource` 落地后反方向补成了独立一条
     * （见「原生行被脚本格式覆盖后原生 parser 不残留在缓存里」），两边同用 evictParser 这条约定。
     */
    @Test
    fun `同 URL 改回原生格式后脚本 parser 不残留在缓存里`(): Unit = runTest {
        seedScriptRow()
        val manager = newManager()

        val stub = manager.getParserFor(SCRIPT_URL)
        assertTrue("前置条件：先拿到脚本解析器", stub is ScriptBookParser)

        assertTrue(manager.addSource(rule(SCRIPT_URL)).isSuccess)
        val fresh = manager.getParserFor(SCRIPT_URL)

        assertTrue("addSource 写的是原生格式且会失效缓存，此处必须换成 JsoupBookParser", fresh is JsoupBookParser)
        assertNotSame(stub, fresh)
    }

    // region addScriptSource / getFormatByUrl（脚本格式的写入口）

    /**
     * 写一条脚本格式的行（URL 用 [SCRIPT_URL]，名称可换）。
     *
     * 消息体只带顶层必需的两个键：`addScriptSource` **不做格式内容校验**（校验与警示归导入 UI），
     * 所以本组用例不需要一份完整的社区 JSON；原样入库这件事由断言里的 `ruleJson` 逐字比较负责。
     */
    private fun scriptSourceJson(name: String = "脚本源A", url: String = SCRIPT_URL) =
        """{"bookSourceName":"$name","bookSourceUrl":"$url"}"""

    /** 带 exploreUrl（JSON 形态）的脚本源 JSON：分类条目读面的用例用 */
    private fun scriptSourceWithExplore(
        name: String = "脚本源A",
        url: String = SCRIPT_URL,
        exploreUrl: String = """[{"title":"玄幻","url":"/xuanhuan/{{page}}"},{"title":"","url":"/ghost"}]""",
    ) = """{"bookSourceName":"$name","bookSourceUrl":"$url","exploreUrl":"${exploreUrl.replace("\"", "\\\"")}"}"""

    @Test
    fun `addScriptSource 原样入库且 getFormatByUrl 返回 script`(): Unit = runTest {
        val manager = newManager()
        val raw = """{"bookSourceName":"脚本源A","bookSourceUrl":"https://a.example.com"}"""

        manager.addScriptSource(raw).getOrThrow()

        assertEquals(SourceFormat.SCRIPT, manager.getFormatByUrl("https://a.example.com"))
        val row = dao.getByUrl("https://a.example.com")!!
        assertEquals(
            "原始 JSON 必须逐字落 rule_json——脚本书源的规则不翻译，被改写一次就偏离社区格式",
            raw,
            row.ruleJson,
        )
        assertEquals("列值取 SourceFormat.raw（小写），写枚举名会被 fromRaw 静默判成 native", "script", row.format)
        assertEquals("脚本源A", row.name)
    }

    @Test
    fun `addScriptSource 重导同 URL 覆盖原始 JSON`(): Unit = runTest {
        val url = "https://a.example.com"
        val manager = newManager()

        manager.addScriptSource("""{"bookSourceName":"脚本源A","bookSourceUrl":"$url"}""").getOrThrow()
        manager.addScriptSource("""{"bookSourceName":"脚本源A改","bookSourceUrl":"$url"}""").getOrThrow()

        assertEquals(SourceFormat.SCRIPT, manager.getFormatByUrl(url))
        val rows = dao.stored().filter { it.url == url }
        assertEquals("主键 url 命中即 REPLACE，不该多出一行", 1, rows.size)
        assertEquals(
            "社区通常整包重发，重导即更新规则：旧原文必须被换掉",
            """{"bookSourceName":"脚本源A改","bookSourceUrl":"$url"}""",
            rows.single().ruleJson,
        )
        assertEquals("脚本源A改", rows.single().name)
    }

    @Test
    fun `getFormatByUrl 对不存在的源返回 null`(): Unit = runTest {
        val manager = newManager()

        assertEquals(null, manager.getFormatByUrl("https://no.where"))
    }

    /**
     * 列保留的取舍（与 [BookSourceManager.addSource] 同构，见其 KDoc 的 REPLACE 陷阱）：
     * 整行 REPLACE 会把未赋值的列一起按实体默认值写回，所以旧行的 `addedAt`（列表位置与「导入时间」
     * 展示）与 `weight`（社区最小模型里没有对应键，冲成 0 就是白丢用户的调整）必须带过来，
     * 「社区 JSON 没写 group 时的旧 group」同理；`name`/`enabled` 由 JSON 说了算（与 addSource 同口径）。
     */
    @Test
    fun `addScriptSource 覆盖既有行时保留入库时间与权重`(): Unit = runTest {
        dao.seed(entityOf(rule("https://native.example"), addedAt = 1L).copy(weight = 42, group = "自定义组"))
        val manager = newManager()

        manager.addScriptSource(scriptSourceJson(name = "脚本化源", url = "https://native.example")).getOrThrow()

        val row = dao.getByUrl("https://native.example")!!
        assertEquals("列表位置与「导入时间」展示都挂在 addedAt 上", 1L, row.addedAt)
        assertEquals("社区最小模型里没有权重字段，旧行的用户调整不该被冲成 0", 42, row.weight)
        assertEquals("JSON 未声明 bookSourceGroup 时沿用旧行，不该悄悄换成默认分组", "自定义组", row.group)
        assertEquals(SourceFormat.SCRIPT.raw, row.format)
    }

    /**
     * 失败形态与 [BookSourceManager.addSource] 对齐（C4）：**带消息的 [IllegalArgumentException]**、
     * 不抛进调用方栈、且失败不留行。Task 9 的导入 UI 直接把 `message` 渲染给用户，
     * 所以三种入参问题都得有中文话术，而不是一个裸 `SerializationException`。
     */
    @Test
    fun `addScriptSource 的入参问题以带消息的失败返回且不落库`(): Unit = runTest {
        val manager = newManager()

        val noUrl = manager.addScriptSource("""{"bookSourceName":"没地址"}""").exceptionOrNull()
        assertTrue("URL 为空应是 IllegalArgumentException（与 addSource 同型），实际 $noUrl", noUrl is IllegalArgumentException)
        assertTrue("消息要指出是哪一行坏：${noUrl?.message}", noUrl?.message?.contains("书源 URL 为空") == true)

        val noName = manager.addScriptSource("""{"bookSourceUrl":"https://x.example"}""").exceptionOrNull()
        assertTrue(noName is IllegalArgumentException)
        assertTrue("名称为空也要报得出原因：${noName?.message}", noName?.message?.contains("书源名称为空") == true)

        val broken = manager.addScriptSource("{ 这不是合法 JSON").exceptionOrNull()
        assertTrue(broken is IllegalArgumentException)
        assertTrue(
            "坏 JSON 要被翻成面向用户的说法（裸 SerializationException 的英文消息没法进 UI）：${broken?.message}",
            broken?.message?.contains("脚本书源") == true,
        )

        assertNull("URL 为空那一条不该留下半个", dao.getByUrl("https://x.example"))
        assertTrue("三种失败都不该留下半个（表本来就空，失败后仍该是空）", dao.stored().isEmpty())
    }

    /**
     * 2e 反转：脚本行**有**当默认源的资格，「无可用默认源时提升第一条启用源」对脚本行同样成立。
     *
     * 判据住在 [BookSourceManagerImpl.defaultFromRows]（候选为条目面的全部启用行、不筛格式），
     * 订阅面与写路径的收敛共用它，于是「本来无可用默认源 + 导入一条启用的脚本源」会把它立为默认源
     * （订阅面发出的就是该脚本行的 [SourceDefinition.Script]）。源本身也没有被降级：
     * 按 URL 仍路由到真解析器。
     */
    @Test
    fun `没有可用原生源时脚本导入会被提升为默认源`(): Unit = runTest {
        val manager = newManager()
        manager.addSource(rule(PRIMARY_URL))
        manager.setEnabled(PRIMARY_URL, false)
        assertNull("前置条件：用户把唯一的原生源禁了", currentDefault(manager))

        manager.addScriptSource(scriptSourceJson()).getOrThrow()

        assertEquals(
            "无可用默认源时导入的启用脚本源就地顶上（与 addSource 同一条提升不变式）",
            SCRIPT_URL,
            currentDefault(manager)?.sourceUrl,
        )
        assertEquals(SCRIPT_URL, savedDefaultUrl())
        assertTrue(manager.getParserFor(SCRIPT_URL) is ScriptBookParser)
    }

    /**
     * 反方向的格式翻转：原生行被脚本格式覆盖后，LRU 里那个按旧规则构造的 parser 不能留下。
     *
     * 与「同 URL 改回原生格式」那条对称——[BookSourceManagerImpl.evictParser] 的 KDoc 说「任何写
     * format 的入口都必须在写成功后失效缓存」，这一条锁的就是 `addScriptSource` 真的照做了。
     * 现象如果没锁住：用户把某个源换成脚本格式，页面却仍在用旧原生规则解（或反之），零报错。
     */
    @Test
    fun `原生行被脚本格式覆盖后原生 parser 不残留在缓存里`(): Unit = runTest {
        val manager = newManager()
        manager.addSource(rule(SCRIPT_URL, weight = 5))
        val nativeParser = manager.getParserFor(SCRIPT_URL)
        assertTrue("前置条件：先按原生格式拿到真解析器", nativeParser is JsoupBookParser)

        manager.addScriptSource(scriptSourceJson()).getOrThrow()
        val after = manager.getParserFor(SCRIPT_URL)

        assertNotSame(nativeParser, after)
        assertTrue("格式已翻转成 script，缓存里留着旧的就等于让 LRU 当第二个格式事实源", after is ScriptBookParser)
    }

    /**
     * 脚本行的分类条目经 [BookSourceManagerImpl.getExploreEntries] 原样递出：
     * 条目不滤空白标题（过滤归 UI 侧），url 承载的是 **URL 规则串**（可含 `{{page}}`），
     * 不是渲染后的地址——渲染发生在解析器的 `getKindBook`，本面纯解析零网络。
     */
    @Test
    fun `getExploreEntries 对脚本行给出 exploreUrl 条目且 url 承载规则串`(): Unit = runTest {
        seedScriptRow(ruleJson = scriptSourceWithExplore())
        val manager = newManager()

        val entries = manager.getExploreEntries(SCRIPT_URL)

        assertEquals(
            "条目原样递出（含空白标题——过滤是 UI 侧的事），url 是 URL 规则串不是渲染后的地址",
            listOf(
                SourceExploreEntry("玄幻", "/xuanhuan/{{page}}"),
                SourceExploreEntry("", "/ghost"),
            ),
            entries,
        )
    }

    /**
     * 原生行的分类条目来自 `ruleFind.kinds` 逐项映射，与脚本行走 `exploreUrl` 切分**共用同一个返回形状**
     * （[SourceExploreEntry]）——消费方（书城 VM）拿到两种出身时不必按格式分岔。
     */
    @Test
    fun `getExploreEntries 对原生行给出 ruleFind kinds`(): Unit = runTest {
        val native = rule("https://native.example").copy(
            ruleFind = FindRule(kinds = listOf(KindItem("科幻", "/kehuan"), KindItem("都市", "/dushi")))
        )
        dao.seed(entityOf(native, addedAt = 1L))
        val manager = newManager()

        val entries = manager.getExploreEntries("https://native.example")

        assertEquals(
            listOf(
                SourceExploreEntry("科幻", "/kehuan"),
                SourceExploreEntry("都市", "/dushi"),
            ),
            entries,
        )
    }

    /** 空白 URL 与库里没有的行都按「无分类」给空列表，不抛异常（与 getFormatByUrl 的 null 同一兜底口径） */
    @Test
    fun `getExploreEntries 对空白 URL 与缺失行给空列表`(): Unit = runTest {
        val manager = newManager()

        assertTrue(manager.getExploreEntries("").isEmpty())
        assertTrue(manager.getExploreEntries("https://no.where").isEmpty())
    }

    // endregion

    /** 在挂起作用域里就地求值并抓异常（kotlin.test 不在本模块测试依赖里，JUnit4 也没有 assertFailsWith） */
    private suspend fun runCatchingBlocking(block: suspend () -> Unit): Throwable? {
        var caught: Throwable? = null
        try {
            block()
        } catch (e: Throwable) {
            caught = e
        }
        return caught
    }

    // endregion
    // region 订阅

    /**
     * 清单是冷流：收集时才查 DAO，所以增删改之后重新收集就该看到新清单。
     * 起点是**空表**（不随包携带书源），第一条与第二条都靠用例自己导入。
     */
    @Test
    fun `observeSources 跟随增删重推清单`(): Unit = runTest {
        val manager = newManager()

        assertTrue("零书源时清单为空", manager.observeSources().first().isEmpty())

        manager.addSource(rule(PRIMARY_URL))
        assertEquals(listOf(PRIMARY_URL), manager.observeSources().first().map { it.rule.url })

        // weight 显式给大，让顺序取决于 weight 而不是 addedAt 的毫秒
        manager.addSource(rule(SECOND_URL, weight = 5))
        assertEquals(
            listOf(PRIMARY_URL, SECOND_URL),
            manager.observeSources().first().map { it.rule.url },
        )
    }

    @Test
    fun `observeSources 对脏行同样只跳过该行`(): Unit = runTest {
        val manager = newManager()
        manager.addSource(rule(PRIMARY_URL))
        dao.seed(BookSourceEntity(url = "https://broken.example", name = "坏行", ruleJson = "{ 这不是合法 JSON"))

        // 订阅面与挂起读共用同一个解码函数，脏行的跳过口径必须一致
        assertEquals(
            listOf(PRIMARY_URL),
            manager.observeSources().first().map { it.rule.url },
        )
        assertEquals(listOf(PRIMARY_URL), manager.getAllSources().map { it.url })
    }

    @Test
    fun `默认源被删除后订阅回落到第一条启用源`(): Unit = runTest {
        val manager = newManager()
        manager.addSource(rule(PRIMARY_URL))
        manager.addSource(rule(SECOND_URL, weight = 5))
        manager.setDefaultSource(SECOND_URL)

        manager.removeSource(SECOND_URL)

        // 删掉的正是用户选中的默认源：订阅面按新清单重算回落，SP 也收敛到新的默认源
        assertEquals(PRIMARY_URL, manager.observeDefaultSource().first()?.sourceUrl)
        assertEquals(PRIMARY_URL, savedDefaultUrl())
    }

    // endregion
    // region 聚合搜索 searchAcross

    @Test
    fun `两条源都成功时各自发一组开始-结果-结束且最后收 AllFinished`(): Unit = runTest {
        val manager = aggregatingManager(
            urls = listOf(SOURCE_A, SOURCE_B),
            pages = mapOf(
                SOURCE_A to mapOf(1 to listOf(noteUrl("https://a.example/book/1"))),
                SOURCE_B to mapOf(1 to listOf(noteUrl("https://b.example/book/1"))),
            ),
        )

        val events = manager.searchAcross("都市", 1).toList()

        assertEquals("两条源各 3 个事件 + 一个 AllFinished", 7, events.size)
        assertSame(events.last(), AggregateSearchEvent.AllFinished)
        // 不同源的事件会**交错**（并发路数共用一个下游），所以只能按源断言相对顺序，
        // 断「全局顺序」等于把编排写死成串行，那正是本方法要避免的事
        assertEquals(
            listOf("SourceStarted", "SourceResult", "SourceFinished"),
            events.filter { it.sourceUrlOf() == SOURCE_A }.map { it.javaClass.simpleName },
        )
        assertEquals(
            "每条源的 result 事件带的是它自己的书",
            listOf("https://a.example/book/1", "https://b.example/book/1"),
            events.filterIsInstance<AggregateSearchEvent.SourceResult>()
                .sortedBy { it.sourceUrl }
                .flatMap { result -> result.books.map { it.noteUrl } },
        )
        assertEquals(
            "解析器写入的归属随结果原样递出（tag=源 URL、origin=源名）",
            listOf(SOURCE_A to "源<$SOURCE_A>", SOURCE_B to "源<$SOURCE_B>"),
            events.filterIsInstance<AggregateSearchEvent.SourceResult>()
                .sortedBy { it.sourceUrl }
                .map { result -> result.books.single().let { it.tag to it.origin } },
        )
    }

    @Test
    fun `一条源抛异常时另一条源结果照常到达且聚合流不终止`(): Unit = runTest {
        val boom = IllegalStateException("A 站炸了")
        val manager = aggregatingManager(
            urls = listOf(SOURCE_A, SOURCE_B),
            pages = mapOf(
                SOURCE_A to mapOf(1 to listOf(noteUrl("https://a.example/book/1"))),
                SOURCE_B to mapOf(1 to listOf(noteUrl("https://b.example/book/1"))),
            ),
            failures = mapOf(SOURCE_A to boom),
        )

        val events = manager.searchAcross("都市", 1).toList()

        val failed = events.filterIsInstance<AggregateSearchEvent.SourceFailed>()
        assertEquals("只有炸掉的那条源报失败", listOf(SOURCE_A), failed.map { it.sourceUrl })
        assertSame(boom, failed.single().error)
        assertEquals(
            "另一条源的结果与收尾一个都不少",
            listOf("https://b.example/book/1"),
            events.filterIsInstance<AggregateSearchEvent.SourceResult>()
                .single { it.sourceUrl == SOURCE_B }.books.map { it.noteUrl },
        )
        assertTrue("流必须走到终点，而不是被单源异常带走", events.last() == AggregateSearchEvent.AllFinished)
        assertEquals(
            "失败也要 Finished（hasMore=false）：UI 靠它收进度",
            listOf(SOURCE_A to false, SOURCE_B to true),
            events.filterIsInstance<AggregateSearchEvent.SourceFinished>()
                .sortedBy { it.sourceUrl }
                .map { it.sourceUrl to it.hasMore },
        )
    }

    @Test
    fun `某源该页空结果时只有它被判到底`(): Unit = runTest {
        val manager = aggregatingManager(
            urls = listOf(SOURCE_A, SOURCE_B),
            pages = mapOf(
                // A 的第 1 页有结果、第 2 页空；B 两页都有
                SOURCE_A to mapOf(
                    1 to listOf(noteUrl("https://a.example/book/1")),
                    2 to emptyList(),
                ),
                SOURCE_B to mapOf(
                    1 to listOf(noteUrl("https://b.example/book/1")),
                    2 to listOf(noteUrl("https://b.example/book/2")),
                ),
            ),
        )

        val secondPage = manager.searchAcross("都市", 2).toList()

        assertEquals(
            "空页即该源到底，有结果的源不受影响",
            listOf(SOURCE_A to false, SOURCE_B to true),
            secondPage.filterIsInstance<AggregateSearchEvent.SourceFinished>()
                .sortedBy { it.sourceUrl }
                .map { it.sourceUrl to it.hasMore },
        )
    }

    @Test
    fun `同时在跑的书源不超过并发上限五`(): Unit = runTest {
        // 8 条源，每条挂起 100ms（虚拟时间）：并发度只有「同时被按住在跑的数量」可观测，
        // 用真实时间 sleep 断言既慢又不确定，故这里数数量、不量时间
        val urls = (1..8).map { "https://site$it.example" }
        val counter = ConcurrencyCounter()
        val manager = aggregatingManager(
            urls = urls,
            holdMs = 100,
            counter = counter,
        )

        val events = manager.searchAcross("都市", 1).toList()

        assertEquals(
            "并发上限应为 5（同时打更多第三方站点会触发风控）",
            5,
            counter.maxRunning,
        )
        assertEquals("八条源最终都要被跑完", urls.size, counter.totalStarted)
        assertEquals(
            "八条源各发三个事件",
            urls.size * 3 + 1,
            events.size,
        )
    }

    @Test
    fun `没有任何启用源时只发 AllFinished`(): Unit = runTest {
        // 一条源都不导入（也就是应用的出厂状态）→ enabled 清单为空
        val manager = aggregatingManager(urls = emptyList())

        val events = manager.searchAcross("都市", 1).toList()

        assertEquals(listOf(AggregateSearchEvent.AllFinished), events)
    }

    @Test
    fun `skipSourceUrls 里的源本轮不参与聚合`(): Unit = runTest {
        val manager = aggregatingManager(
            urls = listOf(SOURCE_A, SOURCE_B),
            pages = mapOf(
                SOURCE_A to mapOf(1 to listOf(noteUrl("https://a.example/book/1"))),
                SOURCE_B to mapOf(1 to listOf(noteUrl("https://b.example/book/1"))),
            ),
        )

        val events = manager.searchAcross("都市", 2, skipSourceUrls = setOf(SOURCE_A)).toList()

        assertEquals(
            "已到底的源不该再被请求一次",
            listOf(SOURCE_B),
            events.filterIsInstance<AggregateSearchEvent.SourceStarted>().map { it.sourceUrl },
        )
    }

    /**
     * 2d 的落地项：脚本书源参与聚合搜索（ADR-0029 决策 6）。
     *
     * 候选集从 [BookSourceManagerImpl.getEnabledSources]（规则类型化读面，按格式挡下脚本行）换成
     * 「启用行 × [BookSourceManagerImpl.toItem]」正是为此。这里注入两分支都给的假工厂：
     * 真 [com.ebook.source.analyze.ScriptBookParser] 会真的向第三方站点发请求，而本用例锁的是
     * **编排**——脚本行有没有进候选、事件与归属有没有按它自己走；「按规则怎么解」由
     * `lib_book_source` 的 ScriptBookParserTest 锁。
     */
    @Test
    fun `聚合搜索候选集包含脚本行并产出其结果`(): Unit = runTest {
        val manager = newManagerWithParserFactory { definition ->
            when (definition) {
                is SourceDefinition.Native -> FakeSearchParser(definition.rule.url)
                is SourceDefinition.Script -> FakeSearchParser(
                    sourceUrl = SCRIPT_URL,
                    pagesByNumber = mapOf(1 to listOf(noteUrl("https://s.example/book/1"))),
                )
            }
        }
        seedScriptRow()

        val events = manager.searchAcross("都市", 1).toList()

        assertEquals(
            "唯一的启用源是那条脚本行，它必须进候选集",
            listOf(SCRIPT_URL),
            events.filterIsInstance<AggregateSearchEvent.SourceStarted>().map { it.sourceUrl },
        )
        assertEquals(
            "脚本行的三格事件一个不少，并照常收尾",
            listOf("SourceStarted", "SourceResult", "SourceFinished"),
            events.filter { it.sourceUrlOf() == SCRIPT_URL }.map { it.javaClass.simpleName },
        )
        assertEquals(
            "结果归属写在脚本源 URL 上（tag 由解析器写，聚合只原样递出）",
            listOf(SCRIPT_URL to "https://s.example/book/1"),
            events.filterIsInstance<AggregateSearchEvent.SourceResult>()
                .flatMap { result -> result.books.map { result.sourceUrl to it.noteUrl } },
        )
        assertSame(events.last(), AggregateSearchEvent.AllFinished)
    }

    /**
     * 锁住 [BookSourceManagerImpl.searchOneSource] 里 `catch (e: CancellationException) { throw e }`
     * 那一条——它是「单源异常一律收敛」唯一的例外：取消不是「这条源失败了」。
     *
     * 为什么单独立一条：上一条用例已经证明「抛 Exception 会被收敛成 Failed」，所以**删掉这条 CE 分支
     * 不会让那一条变红**（通用的 `catch (e: Exception)` 照样接得住 CancellationException），
     * 只有取消的语义会坏。实测的语义是这样：单源抛 CE 时 `flatMapMerge` 把它当作**子协程被取消**，
     * 于是这一路就地消失——既不发 [AggregateSearchEvent.SourceFailed] 也不发
     * [AggregateSearchEvent.SourceFinished]，而**其它源照常跑完、[AggregateSearchEvent.AllFinished]
     * 照常到达**（一路取消不带走整条聚合，这正是本方法要的行为）。
     * 反过来，若把 CE 交给通用 catch，这一源就会被当成一次普通失败报出 Failed + Finished —— 归因错，
     * 且调用方在「已经取消」的收集者上还会再撞一次取消。故本用例断的是「不该出现 Failed」。
     *
     * 调用方要记住的后果：一路被取消时该源不会有任何收尾事件，所以「本轮是否真的结束了」只能信
     * [AggregateSearchEvent.AllFinished]，不能拿「Finished 计数 == Started 计数」当判据
     * （[com.ebook.find.mvvm.viewmodel.SearchViewModel] 因此在 AllFinished 时把进度收满）。
     */
    @Test
    fun `书源抛出取消异常时不被报成单源失败且其它源照常跑完`(): Unit = runTest {
        val manager = aggregatingManager(
            urls = listOf(SOURCE_A, SOURCE_B),
            pages = mapOf(
                SOURCE_B to mapOf(1 to listOf(noteUrl("https://b.example/book/1"))),
            ),
            failures = mapOf(SOURCE_A to CancellationException("这一路被取消（换关键词重搜）")),
        )

        val events = manager.searchAcross("都市", 1).toList()

        assertTrue(
            "取消不该被报成「这条源失败了」，也不该配一个 Failed 的 Finished（events = $events）",
            events.filter {
                it is AggregateSearchEvent.SourceFailed || it is AggregateSearchEvent.SourceFinished
            }.none { it.sourceUrlOf() == SOURCE_A },
        )
        assertEquals(
            "另一条源的结果与收尾一个都不少，聚合流照常走到 AllFinished",
            listOf("SourceStarted", "SourceResult", "SourceFinished"),
            events.filter { it.sourceUrlOf() == SOURCE_B }.map { it.javaClass.simpleName },
        )
        assertSame(events.last(), AggregateSearchEvent.AllFinished)
    }

    // endregion

    /**
     * 装一个「启用源清单可控、parser 全是假件」的 Manager，供聚合搜索那组用例用。
     *
     * 清单起点是**空表**（不随包携带书源），[urls] 逐条导入即构成全部候选——不会再有随包源
     * 混进来把事件数量与「谁失败了」的断言搅浑。[holdMs] 与 [counter] 只用于并发度观测（见对应用例）。
     */
    private suspend fun TestScope.aggregatingManager(
        urls: List<String>,
        pages: Map<String, Map<Int, List<SearchBookEntity>>> = emptyMap(),
        failures: Map<String, Exception> = emptyMap(),
        holdMs: Long = 0,
        counter: ConcurrencyCounter? = null,
    ): BookSourceManager {
        val manager = newManagerWithParserFactory(
            parserFactory = nativeOnlyFactory { rule ->
                FakeSearchParser(
                    sourceUrl = rule.url,
                    pagesByNumber = pages[rule.url].orEmpty(),
                    failure = failures[rule.url],
                    holdMs = holdMs,
                    counter = counter,
                )
            },
        )
        // weight 逐条递增：FakeBookSourceDao 按 weight 排序，固定同权重会让清单顺序取决于 addedAt 毫秒
        urls.forEachIndexed { index, url ->
            manager.addSource(rule(url, weight = index + 1))
        }
        return manager
    }
}

/**
 * [BookSourceDao] 的内存假件，语义逐条对齐真 DAO（`lib_ebook_db` 的 BookSourceDaoTest 锁真 DAO）：
 * - [upsert]/[upsertAll] 是整行 REPLACE（未赋值列吃实体默认值）；
 * - [deleteByUrl] 对任何一行都成功（没有删除保护），只对不存在的 URL 返回 0；
 * - 三条列表查询共用 `weight ASC, added_at ASC, url ASC` 排序口径。
 */
private class FakeBookSourceDao : BookSourceDao {
    private val rows = linkedMapOf<String, BookSourceEntity>()
    private val tick = MutableStateFlow(0)

    fun stored(): List<BookSourceEntity> = sorted(rows.values.toList())

    /** 测试直接播种（脏行、脚本行、用户已配好的行），绕过 Manager 的写路径 */
    fun seed(entity: BookSourceEntity) {
        rows[entity.url] = entity
        tick.value++
    }

    private fun sorted(values: List<BookSourceEntity>) =
        values.sortedWith(compareBy({ it.weight }, { it.addedAt }, { it.url }))

    override fun observeAll(): Flow<List<BookSourceEntity>> = tick.map { sorted(rows.values.toList()) }

    override suspend fun getAll(): List<BookSourceEntity> = sorted(rows.values.toList())

    override suspend fun getByUrl(url: String): BookSourceEntity? = rows[url]

    override suspend fun getEnabled(): List<BookSourceEntity> = sorted(rows.values.filter { it.enabled })

    override suspend fun upsert(source: BookSourceEntity) = seed(source)

    override suspend fun upsertAll(sources: List<BookSourceEntity>) {
        sources.forEach { seed(it) }
    }

    override suspend fun setEnabled(url: String, enabled: Boolean) {
        rows[url]?.let { seed(it.copy(enabled = enabled)) }
    }

    /**
     * 与真 DAO 同语义：按 URL 删，**不做任何身份判断**——应用不随包携带书源，
     * 表里每一行都是用户导入的，因此没有「删不掉的行」，返回 0 只有「这行本就不存在」一种成因。
     */
    override suspend fun deleteByUrl(url: String): Int {
        if (rows.remove(url) == null) return 0
        tick.value++
        return 1
    }
}

/** 造一条搜索结果：聚合用例只关心 noteUrl 与「归属由解析器写」，其余字段留空 */
private fun noteUrl(url: String) = SearchBookEntity(noteUrl = url)

/**
 * 这条事件属于哪个书源；[AggregateSearchEvent.AllFinished] 不属于任何一条源，故为 null。
 *
 * 聚合事件会**交错**到达（并发路数共用一个下游），按源筛事件是本文件反复要做的动作，
 * 统一走这里，省得每个用例重抄一遍 when 分支。
 */
private fun AggregateSearchEvent.sourceUrlOf(): String? = when (this) {
    is AggregateSearchEvent.SourceStarted -> sourceUrl
    is AggregateSearchEvent.SourceResult -> sourceUrl
    is AggregateSearchEvent.SourceFailed -> sourceUrl
    is AggregateSearchEvent.SourceFinished -> sourceUrl
    AggregateSearchEvent.AllFinished -> null
}

/**
 * 「同时在跑几路」的观测器。
 *
 * 并发上限这件事**只能数数量**：断言耗时会在慢机器上假红、在快机器上假绿。
 * 所有事件都发生在本组用例的单一测试调度器上（[FakeSearchParser] 挂的是虚拟时间的
 * [kotlinx.coroutines.delay]），所以普通 Int 就够，不需要原子类。
 */
private class ConcurrencyCounter {
    var running = 0
        private set
    var maxRunning = 0
        private set

    /** 累计进入过解析的次数：断言「每条源最终都被跑过」，防止「上限之外直接被丢弃」混过并发断言 */
    var totalStarted = 0
        private set

    fun enter() {
        running++
        totalStarted++
        if (running > maxRunning) maxRunning = running
    }

    fun leave() {
        running--
    }
}

/**
 * [BookParser] 的搜索假件：按页码给定好的结果，可配置成直接抛异常，也可挂起以观测并发。
 *
 * 它同时替 [JsoupBookParser] 承担一条真解析器已有的行为——**把 `tag`/`origin` 写成自己所属的书源**
 * （见 `JsoupBookParser.parseSearchBookWithRule`）。聚合搜索的结果归属完全靠解析器写这两个字段，
 * 假件不写就等于把「结果自带归属」这条契约从测试面上抹掉。
 *
 * 其余成员未实现即抛：假件静默返回空值会让「其实没走到那条路径」的断言假绿。
 *
 * @param pagesByNumber 页码 → 该页结果；缺省为空页（等价于「这一页没结果」）
 * @param failure 非空则该源直接抛它，用来锁「单源异常不外溢」
 * @param holdMs 每次解析前先挂起这么久（虚拟时间），配合 [counter] 把并发度变成可断言的数字
 */
private class FakeSearchParser(
    val sourceUrl: String,
    private val pagesByNumber: Map<Int, List<SearchBookEntity>> = emptyMap(),
    private val failure: Exception? = null,
    private val holdMs: Long = 0,
    private val counter: ConcurrencyCounter? = null,
) : BookParser {

    /** 被请求过哪些页（按调用顺序） */
    val searchCalls = mutableListOf<Int>()

    override suspend fun searchBook(content: String, page: Int): List<SearchBookEntity> {
        searchCalls += page
        val hold = holdMs > 0
        if (hold) counter?.enter()
        try {
            if (hold) delay(holdMs)
            failure?.let { throw it }
            return pagesByNumber[page].orEmpty().map { it.copy(tag = sourceUrl, origin = "源<$sourceUrl>") }
        } finally {
            if (hold) counter?.leave()
        }
    }

    private fun unsupported(who: String): Nothing =
        throw UnsupportedOperationException("FakeSearchParser 未实现 $who")

    override suspend fun getBookInfo(bookShelf: BookShelfEntity): BookShelfEntity =
        unsupported("getBookInfo")

    override suspend fun getChapterList(bookShelf: BookShelfEntity): WebChapterEntity<BookShelfEntity> =
        unsupported("getChapterList")

    override suspend fun getKindBook(url: String, page: Int): List<SearchBookEntity> =
        unsupported("getKindBook")

    override suspend fun fetchLibraryData(): LibraryEntity = unsupported("fetchLibraryData")
}
