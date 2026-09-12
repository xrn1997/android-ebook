package com.ebook.find.mvvm.viewmodel

import com.ebook.api.entity.BookSourceRule
import com.ebook.api.entity.FindRule
import com.ebook.api.entity.KindItem
import com.ebook.api.entity.SourceFormat
import com.ebook.api.entity.SourceDefinition
import com.ebook.common.analyze.source.BookSourceItem
import com.ebook.common.analyze.source.BookSourceManager
import com.ebook.common.analyze.source.LibraryDiskCache
import com.ebook.common.analyze.source.SourceExploreEntry
import com.ebook.db.entity.BookShelfEntity
import com.ebook.db.entity.LibraryEntity
import com.ebook.db.entity.LibraryKindBookListEntity
import com.ebook.db.entity.SearchBookEntity
import com.ebook.find.entity.BookType
import com.ebook.find.repository.BookSourceRepository
import com.ebook.source.analyze.BookParser
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [LibraryViewModel] 的书源响应测试（ADR-0016 P3-c）。
 *
 * 锁的是「书城整页跟着书源走」这几件事：
 * 1. 默认源一到位，分类入口与书库就按**那个源**算（不再有「构造时读一次」的快照）；
 * 2. 切换器清单只收启用中的源（禁用项进了列表用户就会点了才知道切不过去），
 *    且只带规则、不带 `format` 那类管理面元数据（元数据出管理页是 [BookSourceManager] 接口 KDoc 的禁令）；
 *    2e 起**脚本书源也进**候选——脚本行已是合法默认源（`setDefaultSource` 对它生效、
 *    `getParserFor` 给真解析器），旧「点了没反应」的死条目成因消失
 *    （见 `脚本书源进切换器候选且能成为当前源`）；
 * 3. `switchSource` 只做一件事——把目标源设为默认；重拉是订阅回流的结果，
 *    所以「换了源但数据没跟着换」这类漏刷新不可能靠「记得再调一次 refresh」来避免；
 * 4. 一条源都没启用时进引导态：列表与分类都是空的，且**一个请求都不发**
 *    （没有源可解析，发出去就是拿不相干的站去填页面）；
 * 5. 换源后分类入口换一套（不同源的 `ruleFind.kinds` 不同）；
 * 6. **清空只发生在换源时**：同源下拉刷新期间列表保持原样（否则每次刷新都闪一下空页），
 *    换源则在新一源回来之前就是空的（避免「新书源名 + 旧书目」的错配画面）；
 * 7. **页面档位两档空态不混同**（`sourceState`）：一条源都没有 = NoSource，
 *    有源但当前源解析不出 = BrokenSource（换源/重导），后者被说成前者会把用户支去启用那条坏源；
 *    首帧落 Unknown（默认源要等 Room 回答，那一刻既不闪引导态也不闪空书库）。
 *
 * 手写假 Manager（仓内同一口径：只实现本页用到的面，其余成员一调就抛，
 * 免得「其实没走到那条路径」的断言假绿）。仓库配上**真的** [BookSourceRepository] 与
 * **真的** [LibraryDiskCache]（每测独立临时目录）加假 parser，于是「VM 传了哪个源给仓库、
 * 仓库又传给了谁的 parser、缓存有没有被真的读写」这条链是测真链路而不是两个替身私下约定。
 * 缓存路径必须是真的——旧的假 parser 绕过缓存，「下拉刷新会重拉」在测试里恒真、
 * 真机上却被缓存命中吞掉（假刷新），正是测试与生产分裂的现场。
 */
class LibraryViewModelTest {

    private val mainDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private companion object {
        const val URL_A = "https://a.example"
        const val URL_B = "https://b.example"
        const val URL_C = "https://c.example"
        const val URL_SCRIPT = "https://script.example"

        fun ruleA(enabled: Boolean = true) = BookSourceRule(
            name = "A 站",
            url = URL_A,
            enabled = enabled,
            ruleFind = FindRule(kinds = listOf(KindItem("玄幻", "/xuanhuan"), KindItem("都市", "/dushi"))),
        )

        fun ruleB(enabled: Boolean = true) = BookSourceRule(
            name = "B 站",
            url = URL_B,
            enabled = enabled,
            ruleFind = FindRule(kinds = listOf(KindItem("科幻", "/kehuan"))),
        )

        fun ruleC(enabled: Boolean = true) = BookSourceRule(
            name = "C 站",
            url = URL_C,
            enabled = enabled,
            ruleFind = FindRule(kinds = listOf(KindItem("历史", "/lishi"))),
        )

        fun item(rule: BookSourceRule) = BookSourceItem(rule = rule)

        /**
         * 脚本书源的一行清单条目，形状照真实现：[BookSourceItem.rule] 只是按实体列合成的**展示用空壳**
         * （名称/地址/启用态，没有任何选择器），真正的区分信息在 `format` 那一位上。
         * 假件的 [BookSourceManager.getFormatByUrl] 与 [BookSourceManager.addScriptSource] 仍是
         * 「一调就抛」——本页只吃 [BookSourceManager.observeSources]，用不到那两个面。
         */
        fun scriptItem(name: String = "脚本站", url: String = URL_SCRIPT, enabled: Boolean = true) =
            BookSourceItem(
                rule = BookSourceRule(name = name, url = url, enabled = enabled),
                format = SourceFormat.SCRIPT,
            )

        /** 每个源给一节自己的分类区块，区块名与书名都带源名，便于断言「这屏数据来自哪个源」 */
        fun libraryOf(sourceUrl: String) = LibraryEntity().apply {
            kindBooks = listOf(
                LibraryKindBookListEntity(
                    kindName = "分类@$sourceUrl",
                    kindUrl = "$sourceUrl/kind",
                    books = listOf(SearchBookEntity(noteUrl = "$sourceUrl/book/1", name = "书@$sourceUrl")),
                )
            )
        }
    }

    // region 装配

    /**
     * 只实现「清单订阅 + 默认源 + 按 URL 取源/取 parser」的 Manager 假件。
     *
     * [setDefaultSource] 的行为照真实现：**改默认源快照并推送订阅**（禁用中的目标源顺带启用，
     * 那是 Manager 的既定语义，本假件不必模拟成两步）。
     */
    private class FakeBookSourceManager(
        private val items: List<BookSourceItem>,
        private val defaultUrl: String?,
        private val parsers: Map<String, BookParser>,
    ) : BookSourceManager {
        val setDefaultCalls = mutableListOf<String>()
        val parserForCalls = mutableListOf<String>()
        val urlByQueryCalls = mutableListOf<String>()

        private val defaultSource = MutableStateFlow(items.defaultDefinition(defaultUrl))

        /** 条目 → 载体：Native 用真规则；脚本行的壳没有原文，造一个空 rawJson 的 Script（本页只读展示位） */
        private fun List<BookSourceItem>.defaultDefinition(url: String?): SourceDefinition? {
            val item = firstOrNull { it.rule.url == url } ?: return null
            return when (item.format) {
                SourceFormat.NATIVE -> SourceDefinition.Native(item.rule)
                SourceFormat.SCRIPT -> SourceDefinition.Script(
                    rawJson = "{}",
                    name = item.rule.name,
                    url = item.rule.url,
                )
            }
        }

        override fun observeSources(): Flow<List<BookSourceItem>> = flowOf(items)

        override fun observeDefaultSource(): Flow<SourceDefinition?> = defaultSource

        override suspend fun setDefaultSource(url: String) {
            setDefaultCalls += url
            // 真实现会顺带启用禁用中的目标源；defaultDefinition 不带 enabled，VM 也不读它，沿用即可
            defaultSource.value = items.defaultDefinition(url)
        }

        override suspend fun getParserFor(sourceUrl: String): BookParser? {
            parserForCalls += sourceUrl
            if (sourceUrl.isBlank()) return null
            return parsers[sourceUrl]
        }

        override suspend fun getSourceByUrl(url: String): BookSourceRule? {
            urlByQueryCalls += url
            if (url.isBlank()) return null
            return items.firstOrNull { it.rule.url == url }?.rule
        }

        /** 分类条目读面照真实现建模：条目壳 rule 的 ruleFind.kinds 对脚本行是默认空表，映射结果自然为空 */
        override suspend fun getExploreEntries(sourceUrl: String): List<SourceExploreEntry> {
            urlByQueryCalls += sourceUrl
            if (sourceUrl.isBlank()) return emptyList()
            return items.firstOrNull { it.rule.url == sourceUrl }
                ?.rule?.ruleFind?.kinds?.map { SourceExploreEntry(it.title, it.url) }
                ?: emptyList()
        }

        private fun unsupported(who: String): Nothing =
            throw UnsupportedOperationException("FakeBookSourceManager 未实现 $who")

        override suspend fun getAllSources(): List<BookSourceRule> = unsupported("getAllSources")
        override suspend fun getEnabledSources(): List<BookSourceRule> = unsupported("getEnabledSources")
        override suspend fun addSource(rule: BookSourceRule): Result<Unit> = unsupported("addSource")
        override suspend fun addScriptSource(rawJson: String): Result<Unit> = unsupported("addScriptSource")
        override suspend fun getFormatByUrl(url: String): SourceFormat? = unsupported("getFormatByUrl")
        override suspend fun removeSource(url: String): Result<Unit> = unsupported("removeSource")
        override suspend fun setEnabled(url: String, enabled: Boolean) = unsupported("setEnabled")
        override fun searchAcross(keyword: String, page: Int, skipSourceUrls: Set<String>) =
            unsupported("searchAcross")

        override fun importFromJson(jsonStr: String): BookSourceRule? = unsupported("importFromJson")
        override fun exportToJson(rule: BookSourceRule): String = unsupported("exportToJson")
    }

    /**
     * 记录「哪个源被拉过书库」的假 parser；书库数据按源现场造，便于断言没串源。
     *
     * [blockNextCall] / [release] 是「在途闸门」：本类要断言的不只是终态，还有**请求还没回来时
     * 列表长什么样**（刷新该保留、换源该清空）。没有闸门的话那一瞬间在测试线程上根本抓不住——
     * 假 parser 立刻返回，清空与回填挤在同一次推进里，无论实现清不清空断言都会通过（恒真）。
     *
     * [failNextCall] 让下一次 fetch 抛异常：模拟强刷时的网络故障。真解析器对网络故障不抛
     * （按空区块兜底），但仓库层对「fetch 抛异常」的分支（强刷失败保留旧列表）仍要有人能触发。
     */
    private class FakeLibraryParser(val sourceUrl: String) : BookParser {
        val libraryCalls = mutableListOf<String>()

        private var gate: CompletableDeferred<Unit>? = null
        private var failNext = false

        fun blockNextCall() {
            gate = CompletableDeferred()
        }

        fun release() {
            gate?.complete(Unit)
        }

        fun failNextCall() {
            failNext = true
        }

        override suspend fun fetchLibraryData(): LibraryEntity {
            libraryCalls += sourceUrl
            if (failNext) {
                failNext = false
                throw IllegalStateException("模拟网络故障: $sourceUrl")
            }
            gate?.await()
            return libraryOf(sourceUrl)
        }

        private fun unsupported(who: String): Nothing =
            throw UnsupportedOperationException("FakeLibraryParser 未实现 $who")

        override suspend fun searchBook(content: String, page: Int): List<SearchBookEntity> =
            unsupported("searchBook")

        override suspend fun getBookInfo(bookShelf: BookShelfEntity): BookShelfEntity =
            unsupported("getBookInfo")

        override suspend fun getChapterList(bookShelf: BookShelfEntity) = unsupported("getChapterList")

        override suspend fun getKindBook(url: String, page: Int): List<SearchBookEntity> =
            unsupported("getKindBook")
    }

    private class Fixture(
        val manager: FakeBookSourceManager,
        val viewModel: LibraryViewModel,
        val parsers: Map<String, FakeLibraryParser>,
    )

    /**
     * @param brokenUrls 这些源**在清单里、也能被订阅成默认源，但取不到 parser**——
     *   真机上就是「行还在但 `rule_json` 读不出」或「清单变化与本次请求之间那行被删了」，
     *   仓库侧因此抛 [com.ebook.source.analyze.BookSourceNotFoundException]。
     *   这正是 [LibrarySourceState.BrokenSource] 与 [LibrarySourceState.NoSource] 必须分开的现场。
     */
    private fun fixture(
        items: List<BookSourceItem>,
        defaultUrl: String?,
        brokenUrls: Set<String> = emptySet(),
    ): Fixture {
        val parsers = items.filter { it.rule.url !in brokenUrls }
            .associate { it.rule.url to FakeLibraryParser(it.rule.url) }
        val manager = FakeBookSourceManager(items, defaultUrl, parsers)
        // 每测独立的真文件缓存：SWR 的命中/未命中参与本组断言（见类 KDoc），目录必须每测全新，
        // 否则上一个用例写下的新鲜缓存会让本用例的 parser 不被调用、断言偶发失败
        val repository = BookSourceRepository(
            manager,
            LibraryDiskCache(Files.createTempDirectory("library-vm-test").toFile().apply { deleteOnExit() }),
        )
        return Fixture(manager, LibraryViewModel(repository, manager), parsers)
    }

    /**
     * 轮询等到 [cond] 成立：交替推进测试调度器与真实时间。
     *
     * 仓库那三个方法都挂在 `Dispatchers.IO` 上（真实线程、不受虚拟时钟控制），只推进调度器
     * 就会与之竞态（同一写法见 SearchViewModelTest 与 module_book 的 BookDetailViewModelSourceTest）。
     */
    private suspend fun TestScope.awaitUntil(message: String, cond: suspend () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!cond()) {
            advanceUntilIdle()
            if (cond()) break
            assertTrue("$message（5s 内未达成）", System.currentTimeMillis() < deadline)
            withContext(Dispatchers.IO) { Thread.sleep(10) }
        }
        advanceUntilIdle()
    }

    // endregion

    @Test
    fun `初始默认源到位后分类入口与书库都按该源给出`(): Unit = runTest(mainDispatcher) {
        val f = fixture(listOf(item(ruleA())), defaultUrl = URL_A)

        awaitUntil("A 源的书库已到手") { f.viewModel.list.value.isNotEmpty() }

        assertEquals(
            "分类入口来自默认源的 ruleFind.kinds",
            listOf(BookType("玄幻", "/xuanhuan"), BookType("都市", "/dushi")),
            f.viewModel.bookTypeList.value,
        )
        assertEquals(
            listOf("分类@$URL_A"),
            f.viewModel.list.value.map { it.kindName },
        )
        assertEquals("书库由 A 源的 parser 解析", listOf(URL_A), f.parsers.getValue(URL_A).libraryCalls)
        assertEquals(URL_A, f.viewModel.currentSource.value?.sourceUrl)
    }

    @Test
    fun `切换器清单只列启用中的源且只带规则`(): Unit = runTest(mainDispatcher) {
        val f = fixture(
            items = listOf(item(ruleA()), item(ruleB(enabled = false)), item(ruleC())),
            // 默认源是启用中的 A：禁用项不该出现在候选里，但默认源本身该在
            defaultUrl = URL_A,
        )

        awaitUntil("清单已就位") { f.viewModel.sources.value.isNotEmpty() }

        assertEquals(
            "禁用中的 B 站不进候选：切换器回答的是「我能切到哪个源」",
            listOf(URL_A, URL_C),
            f.viewModel.sources.value.map { it.url },
        )
        assertEquals(
            "切换器拿到的是纯规则，没有 format 那类管理面元数据（接口 KDoc 的分工）",
            listOf("A 站", "C 站"),
            f.viewModel.sources.value.map { it.name },
        )
    }

    /**
     * 2e 反转：脚本行**进**切换器候选，且能被立为当前源。
     * 旧边界（脚本行点不动）的成因已消失：setDefaultSource 对脚本行生效、getParserFor 给真解析器。
     */
    @Test
    fun `脚本书源进切换器候选且能成为当前源`(): Unit = runTest(mainDispatcher) {
        val f = fixture(
            items = listOf(item(ruleA()), scriptItem(), item(ruleC())),
            defaultUrl = URL_A,
        )
        awaitUntil("清单已就位") { f.viewModel.sources.value.isNotEmpty() }

        assertEquals(
            "脚本行是合法候选：enabled 即进清单",
            listOf(URL_A, URL_SCRIPT, URL_C),
            f.viewModel.sources.value.map { it.url },
        )

        f.viewModel.switchSource(URL_SCRIPT)
        // 等的是**列表终态**，不是「currentSource 翻转」或「parser 被调过」：那行记账发生在仓库切到
        // Dispatchers.IO 的当刻，只等 currentSource 会抢在 IO 回填之前读到空 libraryCalls
        // （约定见 awaitUntil 的 KDoc 与 `切换书源即设为默认并按新源重拉书库`）
        awaitUntil("脚本源的书库已回填") { f.viewModel.list.value.map { it.kindName } == listOf("分类@$URL_SCRIPT") }

        assertEquals("页面档位是 Ready：脚本源坏了才进 BrokenSource", LibrarySourceState.Ready, f.viewModel.sourceState.value)
        assertEquals("换源即重拉：脚本源的 parser 被调", listOf(URL_SCRIPT), f.parsers.getValue(URL_SCRIPT).libraryCalls)
    }

    @Test
    fun `脚本源作默认源时页面 Ready 分类入口为空`(): Unit = runTest(mainDispatcher) {
        val f = fixture(items = listOf(scriptItem()), defaultUrl = URL_SCRIPT)
        // 等终态而不是「不等于 NoSource」：首帧初值是 Unknown，后者在起点就成立、等不到任何东西
        awaitUntil("档位就位") { f.viewModel.sourceState.value == LibrarySourceState.Ready }

        assertEquals(LibrarySourceState.Ready, f.viewModel.sourceState.value)
        assertTrue("脚本壳规则没有 kinds：分类入口空（该源若配 exploreUrl 由真实现经 getExploreEntries 给出）",
            f.viewModel.bookTypeList.value.isEmpty())
    }

    @Test
    fun `切换书源即设为默认并按新源重拉书库`(): Unit = runTest(mainDispatcher) {
        val f = fixture(listOf(item(ruleA()), item(ruleB())), defaultUrl = URL_A)
        awaitUntil("A 源的书库已到手") { f.viewModel.list.value.isNotEmpty() }

        f.viewModel.switchSource(URL_B)
        // 等的是**列表终态**，不是「B 源的 parser 被调过」：那行记账发生在仓库切到 Dispatchers.IO 的
        // 当刻，回填排在它之后，只等请求就会偶发抢在数据之前读到空列表（awaitUntil 的约定见其 KDoc）
        awaitUntil("B 源的书库已回填") { f.viewModel.list.value.map { it.kindName } == listOf("分类@$URL_B") }

        assertEquals("切换只做一件事：设默认源", listOf(URL_B), f.manager.setDefaultCalls)
        assertEquals(
            "换源后列表里是 B 源的书目（清空 + 重拉，不留上一源的残留）",
            listOf("分类@$URL_B"),
            f.viewModel.list.value.map { it.kindName },
        )
        assertEquals(
            "A 源的 parser 只被首屏那一次用到",
            listOf(URL_A),
            f.parsers.getValue(URL_A).libraryCalls,
        )
        assertEquals(URL_B, f.viewModel.currentSource.value?.sourceUrl)
    }

    @Test
    fun `没有任何启用中的源时进引导态且不发请求`(): Unit = runTest(mainDispatcher) {
        val f = fixture(
            items = listOf(item(ruleA(enabled = false)), item(ruleB(enabled = false))),
            // 全禁用：Manager 的同步面与订阅面同时为 null
            defaultUrl = null,
        )

        advanceUntilIdle()

        assertTrue("无源时没有书库数据", f.viewModel.list.value.isEmpty())
        assertTrue("无源时没有分类入口", f.viewModel.bookTypeList.value.isEmpty())
        assertEquals("无源不是错误：currentSource 为 null，页面据此画引导态", null, f.viewModel.currentSource.value)
        assertEquals(
            "一条源都没有 = NoSource（页面那句是「请先启用或导入书源」）",
            LibrarySourceState.NoSource,
            f.viewModel.sourceState.value,
        )
        assertTrue(
            "一条请求都不该发：没有源可解析，发出去就是拿不相干的站去填页面",
            f.manager.parserForCalls.isEmpty() && f.manager.urlByQueryCalls.isEmpty()
        )

        // 用户在这个状态下手动下拉刷新：仍然不该有任何源被解析（引导态由 currentSource 决定，
        // 不由「加载失败」推断，所以刷新也不会长出请求来）
        f.viewModel.refreshData()
        advanceUntilIdle()
        assertTrue(f.manager.parserForCalls.isEmpty())
        assertTrue(f.viewModel.list.value.isEmpty())
    }

    /**
     * 锁 M4：「有源但当前这个源解析不出来」必须是一档**不同于**「一条源都没有」的状态。
     *
     * 改造前这一档只有一行 `Logger.w`：页面既不发请求也不报错，只剩一个空列表，用户对着它什么也
     * 推断不出来（ADR-0016 决策 5 承诺的「UI 提示书源已失效」没落地）。反过来把它并进引导态也不行：
     * 那句「请先启用或导入书源」会把用户支去启用**正是坏掉的那条源**，动作完全指错。
     *
     * 顺带锁清零时机：换到一个健康源后必须回到 [LibrarySourceState.Ready]——旧源「坏了」这个结论
     * 不许跟着用户跑到新源那一屏上。
     */
    @Test
    fun `有源但当前源解析不出时报失效源而不是无源`(): Unit = runTest(mainDispatcher) {
        val f = fixture(
            items = listOf(item(ruleA()), item(ruleB())),
            defaultUrl = URL_A,
            // A 站是默认源，但那一行的规则读不出（取不到 parser）
            brokenUrls = setOf(URL_A),
        )

        awaitUntil("A 源已被试过并判为失效") { f.viewModel.sourceState.value == LibrarySourceState.BrokenSource }

        assertEquals(
            "有源且源坏了 ≠ 没源：两句话不同，动作也不同（换源/重导 vs 启用/导入）",
            LibrarySourceState.BrokenSource,
            f.viewModel.sourceState.value,
        )
        assertNotNull("默认源依然存在，这是 BrokenSource 与 NoSource 的分界", f.viewModel.currentSource.value)
        assertEquals(URL_A, f.manager.parserForCalls.last())
        assertTrue("该源解析不出，页面自然没有书目", f.viewModel.list.value.isEmpty())

        f.viewModel.switchSource(URL_B)
        awaitUntil("换到健康的 B 源后回到 Ready") { f.viewModel.sourceState.value == LibrarySourceState.Ready }
        // Ready 只代表「换了源且没被判坏」，B 源那一屏还要等一次加载回来
        awaitUntil("B 源的书库已到手") { f.viewModel.list.value.isNotEmpty() }
        assertEquals(
            "B 源的数据照常渲染：A 源「已失效」这个结论不许带进新源那一屏",
            listOf("分类@$URL_B"),
            f.viewModel.list.value.map { it.kindName },
        )
    }

    /**
     * 合成规则本身（页面判据的唯一来源）：无源优先，其次才是「有源但坏了」。
     *
     * 单独立一条而不是只靠上面那一条端到端用例：将来加一档（如「源还在但网络不通」）时，
     * 这条会直接指出 `when` 的顺序需要表态，而端到端用例只会以某种绕路的方式变红。
     */
    @Test
    fun `两路事实合成档位时无源优先`(): Unit {
        assertEquals(LibrarySourceState.NoSource, librarySourceStateOf(null, sourceUnusable = false))
        // 无源优先：sourceUnusable 那一路在没有源时本就不该被置位，档位也不许被它抢
        assertEquals(LibrarySourceState.NoSource, librarySourceStateOf(null, sourceUnusable = true))
        assertEquals(LibrarySourceState.BrokenSource, librarySourceStateOf(SourceDefinition.Native(ruleA()), sourceUnusable = true))
        assertEquals(LibrarySourceState.Ready, librarySourceStateOf(SourceDefinition.Native(ruleA()), sourceUnusable = false))
    }

    /**
     * 首帧档位是 [LibrarySourceState.Unknown]，**不是** NoSource。
     *
     * 默认源现在每次由 Room 现算（不再随包携带、也没有冷启动快照），页面组合的那一刻答复还没回来。
     * 若拿 NoSource 当兜底，有源用户的冷启动会先闪一帧「还没有可用书源」+ 导入按钮，而下一秒
     * Room 回来说的是「有源」——错误信息比空白更糟，故首帧单独占一档、页面整片留空。
     */
    @Test
    fun `首帧档位是 Unknown 而不是无源`(): Unit = runTest(mainDispatcher) {
        // 刻意不推进调度器：stateIn 的初值在构造时同步就位，Room 那一跳还没跑
        val f = fixture(listOf(item(ruleA())), defaultUrl = URL_A)

        assertEquals(
            "Room 还没答复，此刻只能说「还不知道」，不能说「没有源」",
            LibrarySourceState.Unknown,
            f.viewModel.sourceState.value,
        )

        // 答复落地后必须离开 Unknown：它只是首帧占位，不是一种会停住的状态
        awaitUntil("Room 的答复已落定") { f.viewModel.sourceState.value == LibrarySourceState.Ready }
    }

    @Test
    fun `换源后分类入口跟着换成新源的那一套`(): Unit = runTest(mainDispatcher) {
        val f = fixture(listOf(item(ruleA()), item(ruleB())), defaultUrl = URL_A)
        awaitUntil("A 源的分类入口已就位") { f.viewModel.bookTypeList.value.isNotEmpty() }
        assertEquals(
            listOf(BookType("玄幻", "/xuanhuan"), BookType("都市", "/dushi")),
            f.viewModel.bookTypeList.value,
        )

        f.viewModel.switchSource(URL_B)
        awaitUntil("分类入口已换成 B 源那套") { f.viewModel.bookTypeList.value.size == 1 }

        assertEquals(
            "分类胶囊与书库同源：A 源的分类 url 交给 B 源解析会拉回不相干的书目",
            listOf(BookType("科幻", "/kehuan")),
            f.viewModel.bookTypeList.value,
        )
    }

    @Test
    fun `同源下拉刷新保留列表而换源先清空`(): Unit = runTest(mainDispatcher) {
        val f = fixture(listOf(item(ruleA()), item(ruleB())), defaultUrl = URL_A)
        awaitUntil("A 源的书库已到手") { f.viewModel.list.value.isNotEmpty() }

        // ── 同源下拉刷新：请求照发，但列表在新一屏回来前保持原样 ──
        f.parsers.getValue(URL_A).blockNextCall()
        f.viewModel.refreshData()
        awaitUntil("刷新确实发出了请求") { f.parsers.getValue(URL_A).libraryCalls.size == 2 }

        assertEquals(
            "刷新中还是 A 源那一屏：每次下拉都闪一下空页，用户会以为书源被清空了",
            listOf("分类@$URL_A"),
            f.viewModel.list.value.map { it.kindName },
        )

        f.parsers.getValue(URL_A).release()
        awaitUntil("刷新的结果已回填") { f.viewModel.list.value.map { it.kindName } == listOf("分类@$URL_A") }

        // ── 换源：新一源在途期间列表必须是空的 ──
        f.parsers.getValue(URL_B).blockNextCall()
        f.viewModel.switchSource(URL_B)
        awaitUntil("B 源的请求已发出") { f.parsers.getValue(URL_B).libraryCalls.isNotEmpty() }

        assertTrue(
            "换源时先清空：留着 A 源的书目配上顶部的 B 源名，是「新书源名 + 旧书目」的错配画面",
            f.viewModel.list.value.isEmpty(),
        )

        f.parsers.getValue(URL_B).release()
        awaitUntil("B 源的书库已到手") { f.viewModel.list.value.isNotEmpty() }

        assertEquals(
            listOf("分类@$URL_B"),
            f.viewModel.list.value.map { it.kindName },
        )
    }

    /**
     * 锁 SWR 的命中路径（端到端）：切回一个**缓存仍新鲜**的源，书目立即上屏且不再发请求。
     *
     * 这是「进页 cache-first 秒开」的用户可见承诺：A→B→A 的往返里，第二次 A 不该再付一次
     * 「逐分类串行抓 N 页」的网络成本。`libraryCalls` 不增长是「没发请求」的直接证据
     * ——假 parser 是唯一的数据来源，没被调就没有数据来过。
     */
    @Test
    fun `换源后再切回已缓存的书源立即显示缓存书目且不再发请求`(): Unit = runTest(mainDispatcher) {
        val f = fixture(listOf(item(ruleA()), item(ruleB())), defaultUrl = URL_A)
        awaitUntil("A 源的书库已到手") { f.viewModel.list.value.isNotEmpty() }

        f.viewModel.switchSource(URL_B)
        awaitUntil("B 源的书库已到手") { f.viewModel.list.value.map { it.kindName } == listOf("分类@$URL_B") }

        f.viewModel.switchSource(URL_A)
        awaitUntil("切回 A 后缓存书目秒显") {
            f.viewModel.list.value.map { it.kindName } == listOf("分类@$URL_A")
        }

        assertEquals(
            "A 源的 parser 只被首屏那一次用到：切回时命中新鲜缓存，SWR 不发网络",
            listOf(URL_A),
            f.parsers.getValue(URL_A).libraryCalls,
        )
    }

    /**
     * 锁强刷失败与 VM 的交互：网络故障发生在下拉刷新**期间**时，列表保留旧数据、
     * 档位不误报「源失效」。
     *
     * 两个断言各挡一个回归方向：
     * - 列表被清——用户看到突然空白，比保留旧屏更让人以为「书没了」；
     * - 档位落到 BrokenSource——网络不通与「这条源的规则坏了」是两回事，后者会把用户
     *   支去重导一条本来没坏的源。
     */
    @Test
    fun `刷新期间网络失败时列表保留旧数据且不误报源失效`(): Unit = runTest(mainDispatcher) {
        val f = fixture(listOf(item(ruleA())), defaultUrl = URL_A)
        awaitUntil("A 源的书库已到手") { f.viewModel.list.value.isNotEmpty() }

        f.parsers.getValue(URL_A).failNextCall()
        f.viewModel.refreshData()
        awaitUntil("强刷那次请求已失败") { f.parsers.getValue(URL_A).libraryCalls.size == 2 }

        assertEquals(
            "刷新失败不清列表：用户仍看着上一屏，而不是突然空白",
            listOf("分类@$URL_A"),
            f.viewModel.list.value.map { it.kindName },
        )
        assertEquals(
            "网络故障不是书源失效：档位仍是 Ready，不许拿「换源/重导」吓用户",
            LibrarySourceState.Ready,
            f.viewModel.sourceState.value,
        )
    }
}
