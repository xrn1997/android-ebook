package com.ebook.me.mvvm.viewmodel

import com.ebook.api.entity.BookSourceRule
import com.ebook.api.entity.ContentRule
import com.ebook.api.entity.FindRule
import com.ebook.api.entity.SearchRule
import com.ebook.api.entity.ScriptSourceRule
import com.ebook.api.entity.SourceFormat
import com.ebook.common.analyze.source.BookSourceItem
import com.ebook.me.domain.ScriptWarning
import com.ebook.me.domain.ValidationReason
import com.ebook.me.domain.ValidationResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [BookSourceViewModel] 的导入/导出与清单编排测试。
 *
 * 锁的是「用户点一下导入之后到底发生了什么」这六件事：
 * 1. **两种 JSON 形态都要吃下**（社区既有整包数组也有单条对象），整段读不出时给的是提示、
 *    不是空弹层；
 * 2. **预览不落库**：解析阶段一条都不能进 `addSource`，用户确认前必须可以反悔；
 * 3. **统计口径**：只把校验通过的条目落库，且「成功含覆盖 / 失败 = 校验未通过 + 写库失败」；
 * 4. **导出再导入是往返的**：导出的文件必须能原样导回，否则「备份书源」这句话是假的；
 * 5. **清单状态只带规则与出身**（`BookSourceItem` 现只有 `rule` + `format`）：应用不随包携带
 *    书源，表里每一行都是用户导入的、删除无保护；删除失败后的提示分流只看
 *    「清单里还有没有这一行」（理由见 `BookSourceViewModel.removeSource`），
 *    而 [FakeBookSourceManager] 的拒绝消息刻意与真实现不同措辞——用例因此锁住
 *    「提示不把跨模块文案当协议」；
 * 6. **两种出身各走各路**（Task 9）：按键集判别格式、警示只知情不拦导入、整块解不出的对象
 *    只坏它自己那一条、「将覆盖」要认得出被覆盖的是哪种出身、脚本书源不参与导出。
 *
 * 替身是 [FakeBookSourceManager]（手抄的内存实现，语义逐条对齐接口 KDoc）。
 * **`SourceStorageJson` 刻意不做成替身**：它是 `lib_book_common` 的真解码器（与 `addScriptSource`
 * 共用同一个实例），这里的用例因此能真跑到「社区 JSON 字段值漂移」这类解码失败，
 * 不必假造一个会抛的解码器；至于「预览的接受集 == 落库的接受集」这条本身，
 * 由 `lib_book_common` 的 `SourceStorageJsonTest` 与 `BookSourceManagerImplTest` 锁
 * （假件的 `addScriptSource` 是按正则取键的，本模块编译类路径上没有 `-json` 制品）。
 * 断言都走 [BookSourceViewModel.bookSourceState] 与假件的调用记录——一次性提示经状态流
 * 而不是命令通道下发，正是因为 lib_common 的命令通道在测试里观测不到（见 `SettingViewModelTest`）。
 *
 * 用 Robolectric 的理由：解析路径落在 `org.json`（Android SDK 自带），纯 JVM 下它是抛
 * 「Stub!」的桩实现。调度器仍全部挂在测试时钟上，故 [advanceUntilIdle] 就足够，
 * 不需要 `SettingViewModelTest` 里那种与真实 IO 线程轮询竞态的 awaitUntil。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BookSourceViewModelTest {

    private val mainDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** 四条规则齐备的规则；不设置 body/headers/method 等字段，好让「导出再导入」能逐字段相等 */
    private fun rule(
        url: String,
        name: String = url,
        enabled: Boolean = true,
        weight: Int = 0,
        searchUrl: String = "$url/so/{{keyword}}",
        findUrl: String = "",
        searchList: String = "div.item",
        content: String = "#doc",
    ) = BookSourceRule(
        name = name,
        url = url,
        enabled = enabled,
        weight = weight,
        searchUrl = searchUrl,
        ruleFind = FindRule(url = findUrl),
        ruleSearch = SearchRule(list = searchList),
        ruleContent = ContentRule(content = content),
    )

    /** 只填名称与地址的 JSON 片段（其余规则字段由 [fullSourceJson] 补全） */
    private fun sourceJson(
        name: String,
        url: String,
        searchUrl: String = "$url/so/{{keyword}}",
        searchList: String = "div.item",
        content: String = "#doc",
    ) = """
        {"name": "$name", "url": "$url", "searchUrl": "$searchUrl",
         "ruleSearch": {"list": "$searchList"}, "ruleContent": {"content": "$content"}}
    """.trimIndent()

    @Test
    fun `页面状态合并清单与默认源，已启用数只数启用项`(): Unit = runTest(mainDispatcher) {
        val fake = FakeBookSourceManager(
            initial = listOf(
                rule("https://a.example", name = "甲站", weight = 1),
                rule("https://b.example", name = "乙站", weight = 0),
                rule("https://c.example", name = "丙站", enabled = false),
            )
        )
        val viewModel = BookSourceViewModel(fake)

        advanceUntilIdle()
        val state = viewModel.bookSourceState.value
        assertEquals("清单按 weight 定序，禁用项也留在列表里", 3, state.sources.size)
        assertEquals("乙站", state.sources.first().rule.name)
        assertEquals("默认源是启用清单的第一条", "https://b.example", state.defaultSourceUrl)
        assertEquals("已启用数不含丙站", 2, state.enabledCount)
    }

    @Test
    fun `全部禁用后页面状态里没有默认源`(): Unit = runTest(mainDispatcher) {
        val fake = FakeBookSourceManager(initial = listOf(rule("https://a.example", name = "甲站")))
        val viewModel = BookSourceViewModel(fake)
        advanceUntilIdle()
        assertEquals("https://a.example", viewModel.bookSourceState.value.defaultSourceUrl)

        viewModel.setEnabled("https://a.example", enabled = false)
        advanceUntilIdle()

        val state = viewModel.bookSourceState.value
        assertNull("没有任何启用源时不再有默认源（书城据此展示引导态）", state.defaultSourceUrl)
        assertEquals(0, state.enabledCount)
        assertEquals("源本身仍在清单里，只是禁用", 1, state.sources.size)
    }

    @Test
    fun `导入数组形态的 JSON 得到逐条预览且顺序跟随文件`(): Unit = runTest(mainDispatcher) {
        val viewModel = BookSourceViewModel(FakeBookSourceManager())

        val items = viewModel.parseImportJson(
            """
                [
                  ${sourceJson("甲站", "https://a.example")},
                  ${sourceJson("乙站", "https://b.example")}
                ]
            """.trimIndent()
        )
        advanceUntilIdle()

        assertEquals(2, items.size)
        assertEquals(listOf("甲站", "乙站"), items.map { it.displayName })
        assertTrue("两条都应校验通过", items.all { it.isValid })
        assertEquals("预览必须同时进状态流供弹层渲染", items, viewModel.bookSourceState.value.preview)
    }

    @Test
    fun `导入单条对象形态的 JSON 也认`(): Unit = runTest(mainDispatcher) {
        val viewModel = BookSourceViewModel(FakeBookSourceManager())

        val items = viewModel.parseImportJson(sourceJson("甲站", "https://a.example"))
        advanceUntilIdle()

        assertEquals("社区也常单条分享一个书源", listOf("甲站"), items.map { it.displayName })
        assertTrue(items.single().isValid)
    }

    @Test
    fun `读不出书源对象时给提示而不是弹一个空预览`(): Unit = runTest(mainDispatcher) {
        val fake = FakeBookSourceManager(initial = listOf(rule("https://a.example")))
        val viewModel = BookSourceViewModel(fake)
        advanceUntilIdle()

        // 三种「文件里没有一个书源对象」的形态：纯文本、数组里没有对象、空串
        listOf("这不是 JSON", "[1, 2, 3]", "").forEach { text ->
            val items = viewModel.parseImportJson(text)
            advanceUntilIdle()

            assertTrue("「$text」应一条都解析不出", items.isEmpty())
            assertNull("没有条目时不该弹一个空预览层", viewModel.bookSourceState.value.preview)
            assertEquals(
                "「$text」应给出可读性提示",
                BookSourceViewModel.Notice.ImportUnreadable,
                viewModel.bookSourceState.value.notice,
            )
            viewModel.consumeNotice()
        }
        assertEquals("解析阶段不得落库", emptyList<String>(), fake.addedUrls)
    }

    @Test
    fun `预览项里已存在同 URL 的标为将覆盖`(): Unit = runTest(mainDispatcher) {
        val fake = FakeBookSourceManager(initial = listOf(rule("https://a.example", name = "甲站")))
        val viewModel = BookSourceViewModel(fake)

        val items = viewModel.parseImportJson(
            """
                [
                  ${sourceJson("甲站新版", "https://a.example")},
                  ${sourceJson("乙站", "https://b.example")}
                ]
            """.trimIndent()
        )
        advanceUntilIdle()

        assertEquals(
            "同 URL 的条目标为将覆盖，新 URL 不标",
            listOf(true, false),
            items.map { it.willOverwrite },
        )
    }

    @Test
    fun `确认导入只落库校验通过的条目并按三类统计`(): Unit = runTest(mainDispatcher) {
        val fake = FakeBookSourceManager(initial = listOf(rule("https://a.example", name = "甲站")))
        val viewModel = BookSourceViewModel(fake)
        advanceUntilIdle()

        val items = viewModel.parseImportJson(
            """
                [
                  ${sourceJson("甲站新版", "https://a.example")},
                  ${sourceJson("乙站", "https://b.example")},
                  ${sourceJson("丙站", "https://c.example", content = "")}
                ]
            """.trimIndent()
        )
        advanceUntilIdle()
        fake.addedUrls.clear()

        viewModel.confirmImport(items)
        advanceUntilIdle()

        assertEquals("丙站缺正文规则，不该被写库", listOf("https://a.example", "https://b.example"), fake.addedUrls)
        assertEquals(
            "统计口径：成功 2（其中覆盖 1），失败 1（校验未通过）",
            BookSourceViewModel.Notice.Imported(success = 2, overwritten = 1, failed = 1),
            viewModel.bookSourceState.value.notice,
        )
        assertNull("确认完必须收起预览层", viewModel.bookSourceState.value.preview)
        assertEquals("落库后清单是甲(覆盖)/乙两条，丙没进来", 2, viewModel.bookSourceState.value.sources.size)
    }

    @Test
    fun `写库失败计入失败条数并另走失败上报`(): Unit = runTest(mainDispatcher) {
        val fake = FakeBookSourceManager()
        fake.failingAddUrls = setOf("https://b.example")
        val viewModel = BookSourceViewModel(fake)

        val items = viewModel.parseImportJson(
            """
                [
                  ${sourceJson("甲站", "https://a.example")},
                  ${sourceJson("乙站", "https://b.example")}
                ]
            """.trimIndent()
        )
        advanceUntilIdle()

        viewModel.confirmImport(items)
        advanceUntilIdle()

        assertEquals(
            "两条都过了校验，但乙写库失败 → 成功 1 / 覆盖 0 / 失败 1",
            BookSourceViewModel.Notice.Imported(success = 1, overwritten = 0, failed = 1),
            viewModel.bookSourceState.value.notice,
        )
    }

    /**
     * 清单里**还有这一行**时删除失败，提示不该编出一句人话——它交回只记日志的失败上报。
     *
     * 这是「删除失败的分流判据是清单里有没有这一行、不是 Manager 的消息措辞」的锁：
     * 假件的拒绝消息刻意含「不在清单中」这段与真实现不同的字眼（见 [FakeBookSourceManager] 类注释），
     * 若 [BookSourceViewModel.removeSource] 退回按关键字分流，本用例会拿到
     * [BookSourceViewModel.Notice.DeleteMissing] 而非 null——而那会把一条本该只有日志的写库异常
     * 说成「这个源已经没了」，把用户支去检查一个其实还在的清单。
     */
    @Test
    fun `行仍在清单里时删除失败只走失败上报不编人话`(): Unit = runTest(mainDispatcher) {
        val fake = FakeBookSourceManager(initial = listOf(rule("https://a.example", name = "甲站")))
        fake.failingRemoveUrls = setOf("https://a.example")
        val viewModel = BookSourceViewModel(fake)
        advanceUntilIdle()

        viewModel.removeSource("https://a.example")
        advanceUntilIdle()

        assertNull(
            "清单里查得到这一行，说明失败另有成因（写库异常等），不该弹「已不在清单中」",
            viewModel.bookSourceState.value.notice,
        )
        assertEquals("行必须还在清单里：不能提示失败却又把条目从界面上删掉", 1, fake.rules().size)
    }

    /** 「这一行已不在库里」的判据是清单里查不到它（而不是 Manager 消息里有没有「不存在」） */
    @Test
    fun `删除不存在的源时给已不在清单中的解释`(): Unit = runTest(mainDispatcher) {
        val viewModel = BookSourceViewModel(FakeBookSourceManager())

        viewModel.removeSource("https://gone.example")
        advanceUntilIdle()

        assertEquals(
            BookSourceViewModel.Notice.DeleteMissing,
            viewModel.bookSourceState.value.notice,
        )
    }

    @Test
    fun `删除任一源成功后不再发提示`(): Unit = runTest(mainDispatcher) {
        val fake = FakeBookSourceManager(
            initial = listOf(rule("https://a.example"), rule("https://b.example")),
        )
        val viewModel = BookSourceViewModel(fake)
        advanceUntilIdle()

        viewModel.removeSource("https://b.example")
        advanceUntilIdle()

        assertEquals(listOf("https://b.example"), fake.removedUrls)
        assertEquals("清单只剩另一条", 1, viewModel.bookSourceState.value.sources.size)
        assertNull("删除成功由清单变化自证，不额外弹一条", viewModel.bookSourceState.value.notice)
    }

    @Test
    fun `导出的全部书源能被原样解析回预览`(): Unit = runTest(mainDispatcher) {
        val fake = FakeBookSourceManager(
            initial = listOf(
                rule("https://a.example", name = "甲站", weight = 1),
                rule("https://b.example", name = "乙站", weight = 0),
                // 禁用项也一并导出：禁用只是本地开关状态，规则本身要保得下来
                rule("https://c.example", name = "丙站", enabled = false),
            )
        )
        val viewModel = BookSourceViewModel(fake)
        advanceUntilIdle()

        val json = viewModel.exportAll()
        val items = viewModel.parseImportJson(json)
        advanceUntilIdle()

        assertTrue("导出必须是数组文本，单条拼接不成包", json.trim().startsWith("["))
        assertEquals(
            "往返一致：导出的文件再导入应与库里的清单逐条同形",
            fake.rules(),
            items.mapNotNull { it.nativeRule },
        )
        assertTrue(
            "自产的导出文件仍判为原生出身：判别看的是脚本书源独有的 bookSourceUrl 键，" +
                "我们的导出写的是 url——一旦这条破了，导出的规则会被当成脚本书源整块存进 rule_json，" +
                "原生规则从此不再参与解析却毫无报错",
            items.all { it.format == SourceFormat.NATIVE },
        )
        assertTrue("导出的都是合法规则", items.all { it.isValid })
        assertTrue("导回的就是这些已存在的 URL，全部标将覆盖", items.all { it.willOverwrite })
    }

    @Test
    fun `库里没有书源时导出空串`(): Unit = runTest(mainDispatcher) {
        val viewModel = BookSourceViewModel(FakeBookSourceManager())

        assertEquals("", viewModel.exportAll())
    }

    @Test
    fun `设为默认会转发到管理器且顺带启用由管理器负责`(): Unit = runTest(mainDispatcher) {
        val fake = FakeBookSourceManager(
            initial = listOf(
                rule("https://a.example", name = "甲站", weight = 0),
                rule("https://b.example", name = "乙站", weight = 1, enabled = false),
            )
        )
        val viewModel = BookSourceViewModel(fake)
        advanceUntilIdle()

        // 页面不必「先启用、再设默认」：setDefaultSource 自己会把禁用源带上（接口 KDoc 的副作用）
        viewModel.setDefaultSource("https://b.example")
        advanceUntilIdle()

        assertEquals(listOf("https://b.example"), fake.defaultCalls)
        val state = viewModel.bookSourceState.value
        assertEquals("https://b.example", state.defaultSourceUrl)
        assertEquals("被设为默认的禁用源应同时变为启用，否则默认源成了禁用态", 2, state.enabledCount)
    }

    @Test
    fun `预览项校验不通过时保留全部原因供弹层展示`(): Unit = runTest(mainDispatcher) {
        val viewModel = BookSourceViewModel(FakeBookSourceManager())

        // 一条既没有搜索地址也没有解析规则的 JSON（只有名称与地址是好的）
        val items = viewModel.parseImportJson(
            """{"name": "残缺站", "url": "https://broken.example"}"""
        )
        advanceUntilIdle()

        val reasons = (items.single().validation as ValidationResult.Invalid).reasons
        assertEquals(
            listOf(ValidationReason.NO_ENTRY, ValidationReason.NO_PARSE_RULE),
            reasons,
        )
        assertEquals("校验不通过的条目不进确认导入的计数", 0, items.count { it.isValid })
    }

    // region 脚本书源（Task 9：分流 / 警示 / 原文落库 / 跨出身覆盖 / 导出不参与）

    /**
     * 脚本书源格式的一条源。
     *
     * 顶层键是社区那一套（`bookSourceName`/`bookSourceUrl`/`bookSourceType`/`loginUrl`），
     * 判别用的正是原生格式没有的 `bookSourceUrl` 键。`ruleContent` 与 `searchUrl` 是**模型未声明**的键，
     * 摆进来是为了断言「原文整块落库」（那些键不能经过最小模型转一道）。
     * [content] 含 `<js>` 时才会触发可执行代码警示，默认给一份干净的。
     */
    private fun scriptSourceJson(
        name: String,
        url: String,
        type: Int = 0,
        loginUrl: String? = null,
        content: String = "#novel",
    ): String {
        val login = if (loginUrl == null) "" else """ "loginUrl": "$loginUrl","""
        return """{"bookSourceName": "$name", "bookSourceUrl": "$url", "bookSourceType": $type, $login""" +
            """ "searchUrl": "{{bookSourceUrl}}/so", "ruleContent": {"content": "$content"}}"""
    }

    @Test
    fun `脚本书源按特征键判为脚本出身并给出三项警示`(): Unit = runTest(mainDispatcher) {
        val fake = FakeBookSourceManager()
        val viewModel = BookSourceViewModel(fake)

        val items = viewModel.parseImportJson(
            scriptSourceJson(
                name = "甲脚本站",
                url = "https://s.example",
                type = 1,
                loginUrl = "https://s.example/login",
                content = "<js>1</js>",
            )
        )
        advanceUntilIdle()

        val item = items.single()
        assertEquals("有 bookSourceUrl 这个独有键 → 脚本出身", SourceFormat.SCRIPT, item.format)
        assertEquals("甲脚本站", item.displayName)
        assertEquals("https://s.example", item.displayUrl)
        assertEquals(
            "三项各命中一条：可执行代码 / 依赖登录 / 非文本源",
            listOf(
                ScriptWarning.HAS_EXECUTABLE_CODE,
                ScriptWarning.NEEDS_LOGIN,
                ScriptWarning.NOT_TEXT_SOURCE,
            ),
            item.scriptWarnings,
        )
        assertTrue("警示不拦导入：三项都命中也照样是通过态（计入「确认导入 N 条」）", item.isValid)
        assertEquals(
            "原生那两条（缺入口 / 缺解析规则）不得牵连脚本项",
            emptyList<ValidationReason>(),
            (item.validation as? ValidationResult.Invalid)?.reasons.orEmpty(),
        )
        assertEquals("预览阶段一条都不落库", emptyList<String>(), fake.addedUrls)
    }

    @Test
    fun `确认导入脚本项交出的是原文整块且不走 addSource`(): Unit = runTest(mainDispatcher) {
        val fake = FakeBookSourceManager()
        val viewModel = BookSourceViewModel(fake)

        val items = viewModel.parseImportJson(
            scriptSourceJson("甲脚本站", "https://s.example", content = "<js>getHost()</js>")
        )
        advanceUntilIdle()
        val shownRaw = items.single().scriptRawJson

        viewModel.confirmImport(items)
        advanceUntilIdle()

        assertEquals("脚本项不走原生写面", emptyList<String>(), fake.addedUrls)
        val written = fake.scriptRawSources().getValue("https://s.example")
        assertEquals("确认时写下去的必须是预览那一份原文", shownRaw, written)
        // 注意不能直接对原文做子串比较：`obj.toString()` 会把斜杠转义成 `\/`（真解码会还原，
        // 见 FakeBookSourceManager 的同一条说明），所以这里用 org.json 把存进去的文本再解一遍，
        // 断言的是「那个值还在」而不是「那串字面量长得一样」
        val writtenJson = JSONObject(written)
        assertEquals(
            "零翻译：模型未声明的规则键与脚本内容都得原样留着（转一道就会全丢）",
            "<js>getHost()</js>",
            writtenJson.getJSONObject("ruleContent").getString("content"),
        )
        assertTrue(
            "搜索模板这类模型看不见的顶层键也要留在原文里",
            writtenJson.has("searchUrl"),
        )
        assertEquals(
            "脚本项计入成功条数",
            BookSourceViewModel.Notice.Imported(success = 1, overwritten = 0, failed = 0),
            viewModel.bookSourceState.value.notice,
        )
    }

    @Test
    fun `脚本书源里坏一条不该带崩整包`(): Unit = runTest(mainDispatcher) {
        val fake = FakeBookSourceManager()
        val viewModel = BookSourceViewModel(fake)

        // 社区手改 JSON 的常态：bookSourceType 是 Int，却装了非数值（带引号的数字反而能过，
        // 判据见 lib_book_common 的 SourceStorageJsonTest——预览与落库用的是同一个解码器）。
        // 同一条还带着 loginUrl：解不出时**不拿默认值猜字段**，所以这一条不该出现登录警示
        val broken = """{"bookSourceName": "坏坏站", "bookSourceUrl": "https://bad.example",""" +
            """ "bookSourceType": "abc", "loginUrl": "https://bad.example/login"}"""

        val items = viewModel.parseImportJson(
            """
                [
                  $broken,
                  ${scriptSourceJson("好脚本站", "https://good.example")},
                  ${sourceJson("甲规则站", "https://native.example")}
                ]
            """.trimIndent()
        )
        advanceUntilIdle()

        assertEquals("三种形态混排也要切出三条预览项", 3, items.size)
        val bad = items.first { it.displayName == "坏坏站" }
        assertEquals("坏的那一条仍按脚本出身处理", SourceFormat.SCRIPT, bad.format)
        assertEquals(
            "根因只报一条：整块读不出来（名称与地址其实都是好的）",
            listOf(ValidationReason.SCRIPT_UNPARSABLE),
            (bad.validation as ValidationResult.Invalid).reasons,
        )
        assertEquals(
            "解不出时不猜字段：原文里有 loginUrl 也不给警示，那本身就是要报的解码失败的一部分",
            emptyList<ScriptWarning>(),
            bad.scriptWarnings,
        )
        assertTrue("其余条目不受影响：好脚本项通过", items.first { it.displayName == "好脚本站" }.isValid)
        assertTrue("其余条目不受影响：好原生项通过", items.first { it.displayName == "甲规则站" }.isValid)

        viewModel.confirmImport(items)
        advanceUntilIdle()

        assertEquals("只有原生那条走 addSource", listOf("https://native.example"), fake.addedUrls)
        assertEquals(
            "好脚本条目照常入库，坏的那条不在其中",
            listOf("https://good.example"),
            fake.scriptRawSources().keys.toList(),
        )
        assertEquals(
            "统计口径：成功 2 / 失败 1（校验未通过）",
            BookSourceViewModel.Notice.Imported(success = 2, overwritten = 0, failed = 1),
            viewModel.bookSourceState.value.notice,
        )
    }

    @Test
    fun `将覆盖判定认得跨出身的同URL行`(): Unit = runTest(mainDispatcher) {
        val fake = FakeBookSourceManager(initial = listOf(rule("https://a.example", name = "甲站")))
        val viewModel = BookSourceViewModel(fake)
        advanceUntilIdle()

        // ① 库里是原生行，导一条同 URL 的脚本书源
        val scriptItems = viewModel.parseImportJson(scriptSourceJson("甲站脚本版", "https://a.example"))
        advanceUntilIdle()
        val scriptItem = scriptItems.single()
        assertTrue(
            "同一张表同一主键，脚本导入会整行换掉那条原生规则源",
            scriptItem.willOverwrite,
        )
        assertEquals(SourceFormat.NATIVE, scriptItem.overwriteFormat)

        viewModel.confirmImport(scriptItems)
        advanceUntilIdle()

        // ② 反向：库里现在是脚本行，导一条同 URL 的原生规则
        //    旧写法拿 getAllSources() 预取 URL 集合判覆盖，那个面看不见脚本行，
        //    这一步就会被报成「新增」——用户以为在加一个源，实际把刚导进来的脚本源换掉了
        val nativeItems = viewModel.parseImportJson(sourceJson("甲站规则版", "https://a.example"))
        advanceUntilIdle()
        val nativeItem = nativeItems.single()
        assertTrue(nativeItem.willOverwrite)
        assertEquals("覆盖的是脚本出身", SourceFormat.SCRIPT, nativeItem.overwriteFormat)

        viewModel.confirmImport(nativeItems)
        advanceUntilIdle()

        // 一主键一行：写原生就把那条脚本行整行换掉了。库里如果同时留着两份，
        // getFormatByUrl 就会报出被覆盖前的出身，下一步预览又是错的
        assertEquals(listOf("https://a.example"), fake.rules().map { it.url })
        assertTrue("脚本行不再残留", fake.scriptRawSources().isEmpty())
    }

    @Test
    fun `脚本书源不参与导出`(): Unit = runTest(mainDispatcher) {
        val fake = FakeBookSourceManager(initial = listOf(rule("https://a.example", name = "甲站")))
        val viewModel = BookSourceViewModel(fake)
        val scriptItem = BookSourceItem(
            // 真实现里脚本行的 rule 就是这样一条按实体列合成的展示用空壳（没有任何选择器）
            rule = BookSourceRule(name = "乙脚本", url = "https://s.example"),
            format = SourceFormat.SCRIPT,
        )

        assertNull(
            "行内导出对脚本行返回 null：不渲染入口是表现层，这一道才是守卫",
            viewModel.exportSourceJson(scriptItem),
        )
        assertNotNull(
            "原生行照常能导",
            viewModel.exportSourceJson(BookSourceItem(rule = rule("https://a.example"))),
        )

        val json = viewModel.exportAll()
        // 只比 host 段：美化导出经 org.json，斜杠会被转义成 `\/`，带 scheme 的子串匹配不上
        assertTrue("导出全部含原生行", json.contains("a.example"))
        assertFalse("脚本行不在 getAllSources 里，因此也不出现在导出全部", json.contains("s.example"))
    }

    /**
     * 展示字段只按出身取，**不跨格式兜底**。
     *
     * 正确构造的两种项已由上面两组用例覆盖（原生走 `name`/`url`、脚本走
     * `bookSourceName`/`bookSourceUrl`）；这里锁的是另一半——出身与载荷不一致时必须什么都不显示。
     * 旧写法是一条跨格式的 `?:` 链（`nativeRule?.name ?: scriptRule?...`），那种项会借用
     * 另一格式的字段照常渲染出一个看起来正常的名字，于是「构造点把 format 标错」这类路由 bug
     * 在预览上完全看不出来。改成 `when (format)` 后错配只会显示为空名（UI 回落「未命名」）。
     */
    @Test
    fun `预览项展示字段只按出身读取不借用另一格式的载荷`() {
        val nativeRule = BookSourceRule(name = "原生名", url = "https://native.example")
        val scriptRule = ScriptSourceRule(
            bookSourceName = "脚本名",
            bookSourceUrl = "https://script.example",
        )

        fun previewItem(
            format: SourceFormat,
            native: BookSourceRule? = null,
            script: ScriptSourceRule? = null,
        ) = BookSourceViewModel.ImportPreviewItem(
            format = format,
            nativeRule = native,
            scriptRule = script,
            validation = ValidationResult.Valid,
            willOverwrite = false,
        )

        val nativeItem = previewItem(SourceFormat.NATIVE, native = nativeRule)
        assertEquals("原生项取规则的 name", "原生名", nativeItem.displayName)
        assertEquals("原生项取规则的 url", "https://native.example", nativeItem.displayUrl)

        val scriptItem = previewItem(SourceFormat.SCRIPT, script = scriptRule)
        assertEquals("脚本项取最小模型的 bookSourceName", "脚本名", scriptItem.displayName)
        assertEquals("脚本项取 bookSourceUrl", "https://script.example", scriptItem.displayUrl)

        val misroutedNative = previewItem(SourceFormat.NATIVE, script = scriptRule)
        val misroutedScript = previewItem(SourceFormat.SCRIPT, native = nativeRule)
        assertEquals("出身标原生、载荷却只有脚本 → 不借用对方的名字", "", misroutedNative.displayName)
        assertEquals("同理不借用对方的地址", "", misroutedNative.displayUrl)
        assertEquals("出身标脚本、载荷却只有原生 → 同样为空", "", misroutedScript.displayName)
        assertEquals("同理不借用对方的地址", "", misroutedScript.displayUrl)
    }
}
