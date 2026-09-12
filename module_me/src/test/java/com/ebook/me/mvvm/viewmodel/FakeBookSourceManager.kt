package com.ebook.me.mvvm.viewmodel

import com.ebook.api.entity.BookSourceRule
import com.ebook.api.entity.ContentRule
import com.ebook.api.entity.FindRule
import com.ebook.api.entity.SearchRule
import com.ebook.api.entity.SourceFormat
import com.ebook.api.entity.SourceDefinition
import com.ebook.common.analyze.source.BookSourceItem
import com.ebook.common.analyze.source.BookSourceManager
import com.ebook.common.analyze.source.SourceExploreEntry
import com.ebook.source.analyze.BookParser
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import org.json.JSONException
import org.json.JSONObject

/**
 * [BookSourceManager] 的内存假件，供 module_me 的书源相关 ViewModel 测试共用
 * （`BookSourceViewModelTest` 与 `SettingViewModelTest` 同包，故 internal 即可）。
 *
 * 抄的是接口的**行为契约**而不是实现细节，几条与页面上「哪条算默认源」直接相关的语义都保留了：
 * - `addSource` 同 URL 即覆盖，空白 URL 返回 failure；
 * - 库里没有启用中的源时 [observeDefaultSource] 为 null；
 * - [removeSource] 对不存在的 URL 返回 failure，且**消息刻意避开真实现的措辞**：
 *   `BookSourceViewModel.removeSource` 的提示分流读的是「清单里还有没有这一行」、不读消息文本——
 *   假件换套措辞用例照样绿，正是「不把跨模块文案当协议」这条的锁；哪天真改回按关键字分流，
 *   `BookSourceViewModelTest` 的删除用例会立刻变红；
 * - 表里每一行都是用户导入的，**删除无保护**（应用不随包携带任何书源）；
 * - [setDefaultSource] 顺带启用目标源（「默认源永不为禁用态」那条不变式）。
 *
 * **编解码是有意简化的测试替身**：真实现用 kotlinx-serialization 整块序列化规则（`-json` 制品
 * 不在本模块编译类路径上，测试里也用不了）。这里只搬 8 个页面与校验器真正关心的字段，
 * 足够锁住「导出 → 再导入」的往返一致性；全字段的编解码契约由 `lib_book_common` 自己的
 * 书源管理器测试锁，不在本模块重做一遍。
 */
internal class FakeBookSourceManager(
    initial: List<BookSourceRule> = emptyList(),
) : BookSourceManager {

    private val rows = LinkedHashMap<String, BookSourceRule>()

    /** 清单版本戳：写操作后自增，驱动 [observeSources] 重推 */
    private val tick = MutableStateFlow(0)

    /** 调用记录：断言「只对校验通过项调 addSource」这类编排 */
    val addedUrls = mutableListOf<String>()
    val removedUrls = mutableListOf<String>()
    val enabledCalls = mutableListOf<Pair<String, Boolean>>()
    val defaultCalls = mutableListOf<String>()

    /** 让这些 URL 的 [addSource] 返回失败，模拟写库异常 */
    var failingAddUrls: Set<String> = emptySet()

    /**
     * 让这些 URL 的 [removeSource] 在**行存在**的前提下返回失败，模拟写库异常。
     *
     * 与 [failingAddUrls] 对称：真实故障里「清单里查得到这一行、删却删不掉」是写库失败，
     * 不是「这行不存在」——`BookSourceViewModelTest` 用它锁住「提示分流只看清单里有没有这一行，
     * 不按 Manager 的失败消息措辞分流」（见 [removeSource]）。
     */
    var failingRemoveUrls: Set<String> = emptySet()

    /** 当前默认源 URL（null = 库里没有任何启用源） */
    private var defaultUrl: String? = null

    init {
        initial.forEach { rows[it.url] = it }
        defaultUrl = sortedRows().firstOrNull { it.enabled }?.url
    }

    fun rules(): List<BookSourceRule> = sortedRows()

    private fun bump() {
        tick.value++
    }

    /** 排序口径与真 DAO 一致：`weight ASC, url ASC`（假件没有 addedAt，末位键足够定序） */
    private fun sortedRows(): List<BookSourceRule> =
        rows.values.sortedWith(compareBy({ it.weight }, { it.url }))

    private fun defaultRule(): BookSourceRule? =
        rows[defaultUrl]?.takeIf { it.enabled } ?: rows.values.firstOrNull { it.enabled }

    /**
     * 默认源载体：本假件清单只有原生行，故一律包成 [SourceDefinition.Native]。
     * 「脚本行也能当默认源」由 `BookSourceManagerImplTest` 锁真实现，不在本假件另立口径。
     */
    private fun defaultDefinition(): SourceDefinition? = defaultRule()?.let { SourceDefinition.Native(it) }

    // region 同步面：P2 的页面只用挂起面与订阅面，这里一律不提供 parser

    override suspend fun getParserFor(sourceUrl: String): BookParser? = null

    /** 聚合搜索由搜索页发起，书源管理页的用例不会走到这里 */
    override fun searchAcross(keyword: String, page: Int, skipSourceUrls: Set<String>) =
        throw UnsupportedOperationException("书源管理页不发起聚合搜索")

    // endregion
    // region 挂起读

    override suspend fun getAllSources(): List<BookSourceRule> = sortedRows()

    override suspend fun getEnabledSources(): List<BookSourceRule> =
        sortedRows().filter { it.enabled }

    override suspend fun getSourceByUrl(url: String): BookSourceRule? = rows[url]

    // endregion
    // region 写侧

    override suspend fun addSource(rule: BookSourceRule): Result<Unit> {
        addedUrls += rule.url
        if (rule.url.isBlank()) {
            return Result.failure(IllegalArgumentException("书源 URL 为空，无法保存：${rule.name}"))
        }
        if (rule.url in failingAddUrls) {
            return Result.failure(IllegalStateException("写库失败：${rule.url}"))
        }
        rows[rule.url] = rule
        // 同 URL 的脚本行一并删掉：真实现是一行 REPLACE，出身只能有一个（见 addScriptSource 的对称处理）
        scriptSources.remove(rule.url)
        bump()
        // 当前无可用默认源时，新导入的启用源就地顶上
        if (defaultRule() == null && rule.enabled) {
            defaultUrl = rule.url
        }
        return Result.success(Unit)
    }

    /**
     * 按脚本格式导入过的原文（键 = `bookSourceUrl`）。
     *
     * **不进 [observeSources] 的清单**：真实现里脚本行确实可见（条目 `format = SCRIPT`），但那份路由
     * 与「原样入库 / 默认源资格」全部由 `BookSourceManagerImplTest` 锁真实现，假件重做一份只会分裂口径。
     * 若 module_me 有用例要验证「管理页看得见一条脚本源」，在这里把 [scriptSources] 映射成条目
     * （`BookSourceItem(rule = 按列合成的空壳, format = SourceFormat.SCRIPT)`），
     * 并**一并接上按 URL 的写操作**（`removeSource` / `setEnabled` / `setDefaultSource` 现在只认 [rows]）
     * ——只接清单不接写侧，会造出一个「看得见却删不掉」的半截假件，测出来的都是假命题。
     *
     * Task 9（导入链路）按上面的判据**决定不接**：它要锁的是「解析 → 分流 → 落库」与覆盖判定，
     * 那些都走 [getFormatByUrl]（对两种出身平等可见的那一面），不需要清单里出现脚本行；
     * 而「管理页看得见脚本行」在真实现侧已由 `BookSourceManagerImplTest` 锁住。
     */
    private val scriptSources = LinkedHashMap<String, String>()

    /**
     * 已入库的脚本书源原文（键 = `bookSourceUrl`），供用例断言「落库交出去的就是那份原文整块」
     * ——脚本书源的存储契约是零翻译（ADR-0029 决策 1），经模型转一道就会把规则块与脚本全丢掉。
     */
    fun scriptRawSources(): Map<String, String> = scriptSources.toMap()

    /**
     * 社区 JSON 的顶层键；假件不引序列化器（本模块编译类路径上没有 `-json` 制品），正则取两个键足够。
     *
     * **取到之后必须还原 `\/`**：Android 的 org.json 序列化时会把斜杠转义（`JSONObject.toString()`
     * 产出 `https:\/\/x`），真实现经 kotlinx 解码会自动还原，假件按正则取键若不还原就会把
     * `https:\/\/x` 当成主键存进去——[getFormatByUrl] 随即查不到刚写入的那一行，
     * 「将覆盖 + 被覆盖的是哪种出身」这条链路在测试里全变成假命题。
     */
    private val scriptUrlKey = Regex("\"bookSourceUrl\"\\s*:\\s*\"([^\"]+)\"")
    private val scriptNameKey = Regex("\"bookSourceName\"\\s*:\\s*\"([^\"]+)\"")

    /** 把正则取到的 JSON 字符串值还原成解码器会给的形态（见上面两条正则的说明） */
    private fun String.jsonDecoded(): String = replace("\\/", "/")

    /**
     * 与真实现同形的失败话术（`书源 URL 为空` / `书源名称为空`，均 [IllegalArgumentException]）：
     * Task 9 的导入 UI 会把这些消息直接渲染给用户，假件换个措辞测试就会假绿。
     *
     * **同 URL 的原生行一并删掉**（真实现是整行 REPLACE）：一张表只有一个主键，
     * 两份存储互斥才是「这一行的出身」的语义。留着旧行的话 [getFormatByUrl] 会一直报
     * 被覆盖前的出身，导入预览的「将覆盖 + 被覆盖的是哪种出身」就地被测成假命题。
     */
    override suspend fun addScriptSource(rawJson: String): Result<Unit> {
        val url = scriptUrlKey.find(rawJson)?.groupValues?.get(1).orEmpty().jsonDecoded()
        val name = scriptNameKey.find(rawJson)?.groupValues?.get(1).orEmpty().jsonDecoded()
        if (url.isBlank()) {
            return Result.failure(IllegalArgumentException("书源 URL 为空，无法保存：$name"))
        }
        if (name.isBlank()) {
            return Result.failure(IllegalArgumentException("书源名称为空，无法保存：$url"))
        }
        rows.remove(url)
        scriptSources[url] = rawJson
        bump()
        return Result.success(Unit)
    }

    /** 出身查询：两种格式的行在这里平等（原生清单 → NATIVE，脚本导入 → SCRIPT），都没命中才 null */
    override suspend fun getFormatByUrl(url: String): SourceFormat? = when {
        url in rows -> SourceFormat.NATIVE
        url in scriptSources -> SourceFormat.SCRIPT
        else -> null
    }

    /** 书城分类条目由书城页发起，书源管理页的用例不会走到这里 */
    override suspend fun getExploreEntries(sourceUrl: String): List<SourceExploreEntry> =
        throw UnsupportedOperationException("书源管理页不发起分类条目查询")

    override suspend fun removeSource(url: String): Result<Unit> {
        removedUrls += url
        if (url.isBlank()) {
            return Result.failure(IllegalArgumentException("书源 URL 为空，无法删除"))
        }
        if (rows[url] == null) {
            // 措辞刻意与真实现不同（见类注释）：VM 的提示分流读清单里还有没有这一行，不读这条消息
            return Result.failure(IllegalStateException("删除被拒：请求的行不在清单中（$url）"))
        }
        if (url in failingRemoveUrls) {
            return Result.failure(IllegalStateException("写库失败：$url"))
        }
        rows.remove(url)
        bump()
        // 删掉的是默认源时按回落规则重选（全删完则为 null），与真实现同口径
        defaultUrl = defaultRule()?.url
        return Result.success(Unit)
    }

    override suspend fun setEnabled(url: String, enabled: Boolean) {
        enabledCalls += url to enabled
        rows[url]?.let {
            rows[url] = it.copy(enabled = enabled)
            bump()
        }
        if (enabled && defaultRule() == null) defaultUrl = url
        if (!enabled && defaultUrl == url) defaultUrl = sortedRows().firstOrNull { it.enabled }?.url
    }

    override suspend fun setDefaultSource(url: String) {
        defaultCalls += url
        val rule = rows[url] ?: return
        if (!rule.enabled) {
            rows[url] = rule.copy(enabled = true)
        }
        defaultUrl = url
        bump()
    }

    // endregion
    // region 订阅面

    /**
     * 清单条目只带规则与出身（[BookSourceItem] 现只有这两位），与真实现同形。
     *
     * 假件清单清一色是原生行（脚本行按 [scriptSources] 的 KDoc 说明不接进清单），
     * 故 [BookSourceItem.format] 一律取默认值 [SourceFormat.NATIVE]。
     */
    override fun observeSources(): Flow<List<BookSourceItem>> = tick.map {
        sortedRows().map { rule -> BookSourceItem(rule) }
    }

    override fun observeDefaultSource(): Flow<SourceDefinition?> = tick.map { defaultDefinition() }

    // endregion
    // region 编解码（测试替身，只覆盖页面关心的字段）

    override fun importFromJson(jsonStr: String): BookSourceRule? = try {
        val obj = JSONObject(jsonStr)
        val name = obj.optString("name")
        val url = obj.optString("url")
        if (name.isEmpty() || url.isEmpty()) null else fromJson(obj)
    } catch (e: JSONException) {
        null
    }

    override fun exportToJson(rule: BookSourceRule): String = toJson(rule).toString(2)

    private fun toJson(rule: BookSourceRule) = JSONObject().apply {
        put("name", rule.name)
        put("url", rule.url)
        put("enabled", rule.enabled)
        put("group", rule.group)
        put("weight", rule.weight)
        put("charset", rule.charset)
        put("searchUrl", rule.searchUrl)
        put("ruleSearch", JSONObject().put("list", rule.ruleSearch.list))
        put("ruleContent", JSONObject().put("content", rule.ruleContent.content))
        put("ruleFind", JSONObject().put("url", rule.ruleFind.url))
    }

    private fun fromJson(obj: JSONObject): BookSourceRule {
        val search = obj.optJSONObject("ruleSearch")
        val content = obj.optJSONObject("ruleContent")
        val find = obj.optJSONObject("ruleFind")
        return BookSourceRule(
            name = obj.optString("name"),
            url = obj.optString("url"),
            enabled = obj.optBoolean("enabled", true),
            group = obj.optString("group", "小说"),
            weight = obj.optInt("weight", 0),
            charset = obj.optString("charset", "utf-8"),
            searchUrl = obj.optString("searchUrl"),
            ruleSearch = SearchRule(list = search?.optString("list").orEmpty()),
            ruleContent = ContentRule(content = content?.optString("content").orEmpty()),
            ruleFind = FindRule(url = find?.optString("url").orEmpty()),
        )
    }

    // endregion
}
