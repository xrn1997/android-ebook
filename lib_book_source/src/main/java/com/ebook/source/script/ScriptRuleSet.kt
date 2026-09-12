package com.ebook.source.script

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/** 六类规则对象（规格 §1.2~§1.6）。`ruleReview` 不在内：本仓不实现（§1.7）。 */
internal enum class RuleObjectKind(val jsonKey: String) {
    SEARCH("ruleSearch"),
    EXPLORE("ruleExplore"),
    BOOK_INFO("ruleBookInfo"),
    TOC("ruleToc"),
    CONTENT("ruleContent"),
}

/**
 * 整源级的不支持/延后能力，供导入报告与求值前置检查共用。
 *
 * 每一项都对应规格能力矩阵里一栏「不实现的后果」：报告要能逐条说出少了什么，
 * 而不是给一句「该源可能不完全可用」——后者等于把判断成本推回给用户。
 */
internal enum class ScriptUnsupported {
    LOGIN,
    WEB_JS,
    SOURCE_REGEX,
    JS_LIB,
    COVER_DECODE,
    EXPLORE_SCREEN,

    /**
     * 发现页 `exploreUrl` 是**脚本程序形态**（整串一段 `<js>…</js>`，现场算出条目数组）。
     *
     * 与 [JS] 分开记：那项说的是「本仓会执行这段脚本，注意它会外呼」，而这一形态本仓
     * **不执行也不消费**（缺的是发现页 UI 宿主与 `infoMap`/`refreshExplore` 一族宿主回调，
     * 不是执行器）——并进 [JS] 会让报告对一条「整源可用、只少发现面」的源喊出错误的话术。
     */
    EXPLORE_URL_SCRIPT,
    BOOK_URL_PATTERN,
    DOWNLOAD_URLS,
    REVIEW,
    XPATH,
    JS,
    CACHE_PREFIX,
}

/**
 * 脚本书源的求值输入：原始 JSON 的**规则装载视图**（规格 §1）。
 *
 * 与 `com.ebook.api.entity.ScriptSourceRule` 的分工：后者是导入校验用的最小模型
 * （只有 6 个顶层字段），本类是求值要用的全量规则表。两者读同一份原始 JSON、职责不同，
 * 合并会让导入校验被迫携带全部规则字段，并让「未知键忽略」这条格式得以存活的口径
 * 渗进校验层（ADR-0029 决策 1：原始入库零翻译）。
 *
 * 装载只保留**字符串形态的规则字段**：非字符串（数组、对象、数字）不进 [objects]，
 * 只记进 [irregularFields]——语料里 `nextTocUrl: []` 与 `nextTocUrl: "..."` 并存，数组形态的
 * 展开是求值层的事（§7.1 的「URL 数组」），在词法层把它当成空规则会让「没配」与
 * 「配了多个」同形，而后者恰是求值层据以决定要不要继续翻页的依据。
 * 原始 JSON 节点本身保留在 [rawObjects]（经 [jsonField] 取）——「展开」仍归求值层，
 * 装载只负责让数组形态的字段**可达**，不必从 [raw] 整串重解析。
 */
internal data class ScriptRuleSet(
    val sourceUrl: String,
    val name: String,
    val group: String,
    val type: Int,
    val enabled: Boolean,
    val enabledExplore: Boolean,
    val searchUrl: String?,
    val exploreUrl: String?,
    /** 源级自定义变量整串（`variable` 键）：沙箱里 `source.getVariable()` 的初值（§5.2 的持久变量三套 API 之一） */
    val variable: String,
    /** `variable` 的说明文本，脚本里以 `source.variableComment` 出现（语料拿它判「用户填过没有」） */
    val variableComment: String,
    /** `header` 键（JSON 串的请求头）：脚本里 `JSON.parse(source.header)` 取源自带头域 */
    val header: String,
    /** 源注释：语料里有脚本拿它当「配置开关」使（把参数写在注释里再正则取回），故必须是原文而非摘要 */
    val bookSourceComment: String,
    /** 登录地址**文本**：本仓不做登录（`ScriptUnsupported.LOGIN`），但脚本读它只是读一段配置 */
    val loginUrl: String,
    /** 最后更新时间**原文文本**：语料里数字与日期字符串两种形态都有，翻成 Long 会让后者静默变 0 */
    val lastUpdateTime: String,
    val objects: Map<RuleObjectKind, Map<String, String>>,
    /** 各规则对象的**原始 JSON 节点表**：字符串与非字符串字段都在，供 [jsonField] 展开非常规形态 */
    private val rawObjects: Map<RuleObjectKind, Map<String, JsonElement>>,
    val irregularFields: List<String>,
    val unsupported: List<ScriptUnsupported>,
    val raw: String,
) {
    /** 取一条规则串；键缺失或形态非常规（数组/对象/数字）都返回 null，由调用方按「未配置」处理 */
    fun rule(kind: RuleObjectKind, field: String): String? = objects[kind]?.get(field)

    /** 条目的原始 JSON 节点：数组等非常规形态字段的展开入口（`nextTocUrl`/`nextContentUrl` 的 URL 数组，§7.1/§7.2）；键缺失返回 null */
    fun jsonField(kind: RuleObjectKind, field: String): JsonElement? = rawObjects[kind]?.get(field)

    /**
     * 沙箱里 `source` 对象的**数据面**（作为 `sourceJson` 绑定送过边界）。
     *
     * 只装数据、不装行为：`getKey()`/`getVariable()`/`put()` 这些方法要能在执行中途读回主进程的最新
     * 状态，只能由垫片在 JS 侧挂上（见 `QuickJsPrelude`）。这一分为二也就界定了「谁可能过期」——
     * JSON 里的这段是**装载期快照**，源级字段在解析过程中不会变；会变的那一份走 host call 现取。
     *
     * 可选键**只在声明过时占位**：脚本里 `if (!source.variableComment)` 判的是「用户填没填」，
     * 给一个恒空串与给 `undefined` 在这里同为假，但 `source.header.length` 之类一判就露出差别——
     * 空串是「配了个空头」，`undefined` 才是「压根没配」，后者才是真话。
     */
    fun sourceBindingJson(): String = buildJsonObject {
        put("bookSourceUrl", sourceUrl)
        put("bookSourceName", name)
        putIfPresent("bookSourceGroup", group)
        putIfPresent("bookSourceComment", bookSourceComment)
        putIfPresent("variable", variable)
        putIfPresent("variableComment", variableComment)
        putIfPresent("header", header)
        putIfPresent("loginUrl", loginUrl)
        putIfPresent("lastUpdateTime", lastUpdateTime)
    }.toString()

    /**
     * `nextTocUrl`/`nextContentUrl` 的 URL 数组形态（§7.1/§7.2）：字符串元素逐个取出，
     * 非数组（或键缺失）返回空。数组 = 多页一次给出、按固定页序访问，是翻页链
     * （`ScriptPageChain`）据以决定要不要继续翻的依据，不能与「没配」混同。
     */
    fun nextUrlArray(kind: RuleObjectKind, field: String): List<String> =
        (jsonField(kind, field) as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            .orEmpty()

    companion object {


        /** 顶层非规则对象的键：值若是字符串也可能是 URL 选项之类，只取本仓认识的这几个 */
        private val TOP_LEVEL_URL_KEYS = listOf("searchUrl", "exploreUrl")

        fun load(rawJson: String): ScriptRuleSet {
            val root = try {
                ScriptJson.parseToJsonElement(rawJson)
            } catch (e: Exception) {
                throw ScriptRuleParseException("脚本书源 JSON 无法解析：${e.message}", e)
            }
            val obj = root as? JsonObject
                ?: throw ScriptRuleParseException(
                    "脚本书源必须整块装载；数组由导入链路逐条拆开后交给本装载器"
                )
            val irregular = mutableListOf<String>()
            val unsupported = linkedSetOf<ScriptUnsupported>()

            // 延后/不支持项一律按 **JSON 键名** 检测。用「整串里含 webJs 字样」去凑会在别的
            // 字段上误报、又漏掉真配了这两键的源（键值可能是空串，那压根不是规则内容）。
            if (obj["loginUrl"].isDeclared()) unsupported += ScriptUnsupported.LOGIN
            if (obj["jsLib"].isDeclared()) unsupported += ScriptUnsupported.JS_LIB
            if (obj["coverDecodeJs"].isDeclared()) unsupported += ScriptUnsupported.COVER_DECODE
            if (obj["exploreScreen"].isDeclared()) unsupported += ScriptUnsupported.EXPLORE_SCREEN
            if (obj["bookUrlPattern"].isDeclared()) unsupported += ScriptUnsupported.BOOK_URL_PATTERN
            if (obj.containsKey("ruleReview")) unsupported += ScriptUnsupported.REVIEW
            (obj["ruleContent"] as? JsonObject)?.let { content ->
                if (content.containsKey("webJs")) unsupported += ScriptUnsupported.WEB_JS
                if (content.containsKey("sourceRegex")) unsupported += ScriptUnsupported.SOURCE_REGEX
                if (content.containsKey("contentBatch")) irregular += "${RuleObjectKind.CONTENT.jsonKey}.contentBatch"
            }
            (obj["ruleBookInfo"] as? JsonObject)?.let { info ->
                if (info.containsKey("downloadUrls")) unsupported += ScriptUnsupported.DOWNLOAD_URLS
            }

            val urls = TOP_LEVEL_URL_KEYS.associateWith { obj.stringOrNull(it) }
            // 原始节点表：与 objects 同一趟遍历的原料（load 持有的是已解析 root，不重复解析），
            // 字符串/数组/对象/数字形态全保留——jsonField 的展开语义全靠它。
            // JsonObject 本身即 Map<String, JsonElement>，直接作为表值（不可变，可安全共享）
            val rawObjects: Map<RuleObjectKind, Map<String, JsonElement>> =
                RuleObjectKind.entries.associateWith { kind ->
                    (obj[kind.jsonKey] as? JsonObject) ?: emptyMap()
                }
            val objects = RuleObjectKind.entries.associateWith { kind ->
                val node = obj[kind.jsonKey] as? JsonObject ?: return@associateWith emptyMap<String, String>()
                node.entries.mapNotNull { (field, value) ->
                    val path = "${kind.jsonKey}.$field"
                    when {
                        value is JsonPrimitive && value.isString -> {
                            val rule = value.contentOrNull.orEmpty()
                            classify(rule, path, unsupported, irregular)
                            field to rule
                        }
                        value is JsonArray -> {
                            // 数组形态仍逐条分类其元素：能力登记（含 JS/XPath）不能因为外层是数组就漏掉，
                            // 但整笔按非常规字段记账、不进规则表（见类 KDoc）
                            value.forEach { el -> el.stringOrNull()?.let { classify(it, path, unsupported, irregular) } }
                            irregular += path
                            null
                        }
                        else -> {
                            irregular += path
                            null
                        }
                    }
                }.toMap()
            }
            urls["searchUrl"]?.let { classify(it, "searchUrl", unsupported, irregular) }
            // exploreUrl 例外：脚本程序形态本仓不执行（见 ScriptUnsupported.EXPLORE_URL_SCRIPT），
            // 故不能落进 classify 的 RuleMode.JS 分支——那会把它说成「会执行的 JS」。
            // 其余形态照旧逐条分类（文本一里 url 含 `<js>` 段是合法的，那才是要执行的 JS）。
            urls["exploreUrl"]?.let { raw ->
                if (ExploreUrlFormat.isScriptProgram(raw)) {
                    unsupported += ScriptUnsupported.EXPLORE_URL_SCRIPT
                } else {
                    classify(raw, "exploreUrl", unsupported, irregular)
                }
            }

            return ScriptRuleSet(
                sourceUrl = obj.stringOrNull("bookSourceUrl").orEmpty(),
                name = obj.stringOrNull("bookSourceName").orEmpty(),
                group = obj.stringOrNull("bookSourceGroup").orEmpty(),
                type = obj["bookSourceType"].intOrZero(),
                // 布尔键缺失按 true（§1.1 默认列）；用 stringOrNull 而不是 jsonPrimitive，
                // 是为了让「配成了对象/数组」这种坏形态落到默认值上而不是抛序列化异常
                enabled = obj.stringOrNull("enabled")?.toBooleanStrictOrNull() ?: true,
                enabledExplore = obj.stringOrNull("enabledExplore")?.toBooleanStrictOrNull() ?: true,
                searchUrl = urls["searchUrl"]?.ifBlank { null },
                exploreUrl = urls["exploreUrl"]?.ifBlank { null },
                // 源级字段一律「缺失即空串」：这些键在 §1.1 的默认列里就是 ""，
                // 装成 null 会让下游每个使用点各判一次空，而空串本身就是「未配置」的表示
                variable = obj.stringOrNull("variable").orEmpty(),
                variableComment = obj.stringOrNull("variableComment").orEmpty(),
                header = obj.stringOrNull("header").orEmpty(),
                bookSourceComment = obj.stringOrNull("bookSourceComment").orEmpty(),
                loginUrl = obj.stringOrNull("loginUrl").orEmpty(),
                lastUpdateTime = obj.stringOrNull("lastUpdateTime").orEmpty(),
                objects = objects,
                rawObjects = rawObjects,
                irregularFields = irregular.toList(),
                unsupported = unsupported.toList(),
                raw = rawJson,
            )
        }

        /**
         * 逐条规则的能力判定：只登记 JS / XPath / `@cache:` 三类，其余不做任何猜测。
         *
         * 这里**不解析规则内容**（不切链、不判索引），因为装载发生在导入时、
         * 而切分结果要留给求值层；把两者混在一处会让「读不懂」与「不支持」两种失败
         * 在同一处代码里互相掩盖（§12「失败要如实报」）。
         */
        private fun classify(
            rule: String,
            path: String,
            unsupported: MutableSet<ScriptUnsupported>,
            irregular: MutableList<String>,
        ) {
            if (rule.isBlank()) return
            // 只看 mode 分量：`of` 会先剥掉 §2.5 的反序前缀 `-`，故 `-//a/@text()` 这类规则
            // 在能力登记上也看得穿（剥之前它会被判成默认模式、漏掉 XPATH）。登记逻辑本身不变。
            when (RuleMode.of(rule).mode) {
                RuleMode.JS -> unsupported += ScriptUnsupported.JS
                RuleMode.XPATH -> unsupported += ScriptUnsupported.XPATH
                RuleMode.UNSUPPORTED -> {
                    unsupported += ScriptUnsupported.CACHE_PREFIX
                    irregular += path
                }
                else -> Unit
            }
        }

        /**
         * 该键是否**声明了**对应能力：必须存在且是非空白字符串。
         *
         * 空串在 §1.1 清单的「默认」列里就是未配置（`loginUrl` 等键默认 ""），把它也算成
         * 不支持项会让报告对着一堆正常源喊「依赖登录」——误报的代价是用户不再相信报告。
         */
        private fun JsonElement?.isDeclared(): Boolean = stringOrNull()?.isNotBlank() == true

        private fun JsonObject.stringOrNull(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

        private fun JsonElement?.stringOrNull(): String? = (this as? JsonPrimitive)?.contentOrNull

        private fun JsonElement?.intOrZero(): Int = (this as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: 0
    }
}

/** 声明过（非空白）才占键位；判据的理由见 `ScriptRuleSet.sourceBindingJson` 的 KDoc */
private fun JsonObjectBuilder.putIfPresent(key: String, value: String) {
    if (value.isNotBlank()) put(key, value)
}
