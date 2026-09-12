package com.ebook.common.analyze.source

import com.ebook.api.entity.BookSourceRule
import com.ebook.api.entity.SourceDefinition
import com.ebook.api.entity.SourceFormat
import com.ebook.db.entity.BookShelfEntity
import com.ebook.db.entity.LibraryEntity
import com.ebook.db.entity.SearchBookEntity
import com.ebook.db.entity.WebChapterEntity
import com.ebook.source.analyze.AggregateSearchEvent
import com.ebook.source.analyze.BookParser
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf

/**
 * [BookSourceManager] 的内存假件，锁「按 `tag` 找 parser」这条契约。
 *
 * 只实现 P3-a 相关的面（`getParserFor` / `getSourceByUrl` / 两个清单挂起读），
 * 其余成员一律 `error(...)`：假件把未实现成员静默返回空值，会让「其实没走到那条路径」的断言假绿，
 * 宁可炸在测试里。增删改与导入导出那些成员的行为契约由 `BookSourceManagerImplTest` 锁真实现。
 *
 * `getParserFor` **照抄真实现的两条短路**（空白 URL 与 `loc_book` 不查库直接 null），
 * 否则用它测出来的「本地书不会被报成书源失效」是假命题——真实现返回 null 而假件返回了缓存值，
 * 被测代码的分支就白测了。module_me 另有一份同名假件：跨模块看不到测试源码（无 test fixture 发布），
 * 只能各自维护，改动 `BookSourceManager` 接口时两处都要跟。
 *
 * **没有默认源相关的成员**：本应用不随包携带书源，默认源既没有同步读面、也没有可注入的内存快照，
 * 两个订阅面里 [observeDefaultSource] 给的是空流——用它的用例只关心按 `tag` 取 parser，
 * 不建模「谁是默认源」（那套现算逻辑由 [BookSourceManagerImplTest] 锁真实现）。
 *
 * @param parsersBySourceUrl 可被 [getParserFor] 取到的 parser，键为书源 URL
 * @param rulesBySourceUrl 可被 [getSourceByUrl] 取到的规则，键为书源 URL（P3-a 的用例用不到，留空即可）
 */
internal class FakeBookSourceManager(
    parsersBySourceUrl: Map<String, BookParser> = emptyMap(),
    rulesBySourceUrl: Map<String, BookSourceRule> = emptyMap(),
) : BookSourceManager {

    private val parsers = LinkedHashMap(parsersBySourceUrl)
    private val rules = LinkedHashMap(rulesBySourceUrl)

    /** [getParserFor] 收到过哪些 URL（按调用顺序）——断言「用的是这本书的归属」就靠它 */
    val parserForCalls = mutableListOf<String>()

    fun putParser(sourceUrl: String, parser: BookParser) {
        parsers[sourceUrl] = parser
    }

    override suspend fun getParserFor(sourceUrl: String): BookParser? {
        parserForCalls += sourceUrl
        if (sourceUrl.isBlank() || sourceUrl == BookShelfEntity.LOCAL_TAG) return null
        return parsers[sourceUrl]
    }

    override suspend fun getSourceByUrl(url: String): BookSourceRule? = rules[url]

    override suspend fun getAllSources(): List<BookSourceRule> = rules.values.toList()

    override suspend fun getEnabledSources(): List<BookSourceRule> =
        rules.values.filter { it.enabled }

    /**
     * 聚合搜索的编排由 `BookSourceManagerImplTest` 锁真实现（配假 parser 工厂）。
     * 本假件的用例只关心「按 tag 能不能取到 parser」，不在此重做一份并发语义。
     */
    override fun searchAcross(
        keyword: String,
        page: Int,
        skipSourceUrls: Set<String>,
    ): Flow<AggregateSearchEvent> = unsupported("searchAcross")

    override suspend fun addSource(rule: BookSourceRule): Result<Unit> = unsupported("addSource")

    /**
     * 按脚本格式导入过的原文（键 = `bookSourceUrl`），只为让 [getFormatByUrl] 答得出出身。
     *
     * **本假件不模拟脚本求值，也不把这些行进 [observeSources] 的清单**：格式路由、原样入库、
     * 默认源资格那套行为全部由 `BookSourceManagerImplTest` 锁真实现（假件里重做一份只会分裂口径）。
     * 若日后有用例需要「管理页看得见一条脚本源」，在这里把 `scriptSources` 一起映射成条目
     * （`format = SourceFormat.SCRIPT`），别去改真实现的解码路径。
     */
    private val scriptSources = LinkedHashMap<String, String>()

    /** 社区 JSON 顶层的 URL 键；假件不引序列化器，正则取这一个键就够用 */
    private val bookSourceUrlKey = Regex("\"bookSourceUrl\"\\s*:\\s*\"([^\"]+)\"")

    override suspend fun addScriptSource(rawJson: String): Result<Unit> {
        val url = bookSourceUrlKey.find(rawJson)?.groupValues?.get(1).orEmpty()
        if (url.isBlank()) {
            return Result.failure(IllegalArgumentException("书源 URL 为空，无法保存："))
        }
        scriptSources[url] = rawJson
        return Result.success(Unit)
    }

    /** 出身查询：原生清单命中算 NATIVE，脚本导入命中算 SCRIPT，都没命中 null（与真实现同判据） */
    override suspend fun getFormatByUrl(url: String): SourceFormat? = when {
        url in rules -> SourceFormat.NATIVE
        url in scriptSources -> SourceFormat.SCRIPT
        else -> null
    }

    override suspend fun getExploreEntries(sourceUrl: String): List<SourceExploreEntry> =
        unsupported("getExploreEntries")

    override suspend fun removeSource(url: String): Result<Unit> = unsupported("removeSource")

    override suspend fun setEnabled(url: String, enabled: Boolean) = unsupported("setEnabled")

    override suspend fun setDefaultSource(url: String) = unsupported("setDefaultSource")

    /**
     * 清单条目按规则现拼：假件的规则表就是全部行，`format` 一律取默认的 [SourceFormat.NATIVE]
     * （假件不建模格式路由——那套由 [BookSourceManagerImplTest] 锁真实现）。
     */
    override fun observeSources(): Flow<List<BookSourceItem>> =
        flowOf(rules.values.map { BookSourceItem(it) })

    /** 默认源没有同步读面、也不由假件建模：给空流，用它的用例只关心按 `tag` 取 parser */
    override fun observeDefaultSource(): Flow<SourceDefinition?> = emptyFlow()

    override fun importFromJson(jsonStr: String): BookSourceRule? = unsupported("importFromJson")

    override fun exportToJson(rule: BookSourceRule): String = unsupported("exportToJson")

    private fun unsupported(who: String): Nothing =
        throw UnsupportedOperationException("FakeBookSourceManager 未实现 $who")
}

/**
 * [BookParser] 的记录型替身：记住自己被谁、用什么入参调过，返回构造时给的固定结果。
 *
 * 「这本书用的是它自己书源的 parser」这句断言，靠的就是不同 tag 配不同实例，
 * 然后看请求落到了哪个实例上（见 [FakeBookSourceManager.parserForCalls] 与本类的 [bookInfoCalls]）。
 *
 * @param ownedSourceUrl 这个 parser 代表的书源 URL，仅用于断言时说得清「是谁在解析」
 * @param chapterListData [getChapterList] 的返回载荷
 */
internal class RecordingBookParser(
    val ownedSourceUrl: String,
    private val bookInfoResult: BookShelfEntity = BookShelfEntity(tag = ownedSourceUrl),
    private val chapterListData: BookShelfEntity = bookInfoResult,
) : BookParser {

    val bookInfoCalls = mutableListOf<BookShelfEntity>()
    val chapterListCalls = mutableListOf<BookShelfEntity>()
    val searchCalls = mutableListOf<Pair<String, Int>>()

    override suspend fun getBookInfo(bookShelf: BookShelfEntity): BookShelfEntity {
        bookInfoCalls += bookShelf
        return bookInfoResult
    }

    override suspend fun getChapterList(bookShelf: BookShelfEntity): WebChapterEntity<BookShelfEntity> {
        chapterListCalls += bookShelf
        return WebChapterEntity(data = chapterListData, next = false)
    }

    override suspend fun searchBook(content: String, page: Int): List<SearchBookEntity> {
        searchCalls += content to page
        return emptyList()
    }

    override suspend fun getKindBook(url: String, page: Int): List<SearchBookEntity> =
        unsupported("getKindBook")

    override suspend fun fetchLibraryData(): LibraryEntity = unsupported("fetchLibraryData")

    private fun unsupported(who: String): Nothing =
        throw UnsupportedOperationException("RecordingBookParser 未实现 $who")
}
