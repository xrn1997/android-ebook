package com.ebook.source.script

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * JSONPath 后端（规格 §2.1 的 JSONPath 模式，§10 能力矩阵「API 型站点」）。
 *
 * 手写子集而不是引第三方库：本项目对本格式整体持清洁室姿态、依赖收得极紧，而语料扫描
 * 缺位时无法论证第三方全量实现的必要性——先用类型化拒绝把「写了更复杂语法」的源如实挡下
 * （不猜语义、不静默空结果），扩集或引库一律以真实站点语料为据，不按推测。
 *
 * **子集边界**（规格 §11-17）：`$` 根、`.name`/`['name']`/`["name"]`、`[n]`/`[-n]`（越界跳过）、
 * `[*]`/`.*` 通配、`..name` 递归、`[a:b(:c)]` 切片、`[a,b]` 联合。
 * 切片按**标准 JSONPath 半开方言**（end 排他、负数从尾数）——与规格 §4 链式索引的闭区间
 * 是两个语法域的两种方言，谁也别推广到谁那边。
 *
 * 结果一律是 [RuleResult.Jsons]（保留节点形态，理由见该类 KDoc）；命中零条是 [RuleResult.Miss]，
 * 路径语法错误抛 [RuleSyntaxException]（§3.3，参与 `||` 短路）。
 */
internal object JsonPathBackend {

    /** 求值入口：种子化为单根节点后逐段折叠；零命中（含种子解析失败）收口为 [RuleResult.Miss] */
    fun evaluate(path: String, input: RuleValue, reverse: Boolean): RuleResult {
        val root = seed(input, path) ?: return RuleResult.Miss
        val items = parsePath(path).fold(listOf(root)) { current, segment -> segment(current) }
        if (items.isEmpty()) return RuleResult.Miss
        return RuleResult.Jsons(if (reverse) items.reversed() else items)
    }

    /**
     * 输入种子：JSON 原样；页面/文本按 lenient 解析；Nodes 无 JSON 语义、类型化拒绝——
     * 与 HTML/正则后端对 Json 输入的处置口径对称，跨域输入是规则写错而非「没取到值」。
     * Page/Texts 解析失败按 Miss 是刻意的不对称：响应不是规则串，坏响应属「未取到值」
     * 而非「规则写错」（见 [RuleValue.Json] KDoc）。
     */
    private fun seed(input: RuleValue, path: String): JsonElement? = when (input) {
        is RuleValue.Json -> input.element
        is RuleValue.Page -> input.source.parseJsonOrNull()
        is RuleValue.Texts -> input.values.firstOrNull()?.parseJsonOrNull()
        is RuleValue.Nodes -> throw UnsupportedRuleFeatureException("JSONPath 作用于 HTML 节点上下文", path)
    }

    private fun String.parseJsonOrNull(): JsonElement? =
        runCatching { ScriptJson.parseToJsonElement(this) }.getOrNull()

    // region 路径词法

    /** 路径串 → 段函数列表（每段作用于节点集、产出节点集）；子集之外的形态一律抛 [RuleSyntaxException] */
    private fun parsePath(path: String): List<(List<JsonElement>) -> List<JsonElement>> {
        val trimmed = path.trim()
        if (trimmed.isEmpty() || trimmed[0] != '$') throw RuleSyntaxException(path)
        val out = mutableListOf<(List<JsonElement>) -> List<JsonElement>>()
        var i = 1
        while (i < trimmed.length) {
            when {
                trimmed.startsWith("..", i) -> {
                    val name = readIdent(trimmed, i + 2) ?: throw RuleSyntaxException(path)
                    // `*` 是 readIdent 的合法产物，但递归通配不在子集内：放行会按字面键名查空，
                    // 两个支持特性（.. 与 *）组合出静默 Miss（§12 禁止），与过滤器同口径类型化拒绝
                    if (name == "*") throw UnsupportedRuleFeatureException("JSONPath 递归通配 ..*", path)
                    out += { current -> current.flatMap { recursiveByName(it, name) } }
                    i += 2 + name.length
                }
                trimmed[i] == '.' -> {
                    if (i + 1 < trimmed.length && trimmed[i + 1] == '*') {
                        // wildcard 作用于单节点，段函数的口径是节点集，flatMap 适配（同 parseBracket 的通配分支）
                        out += { current -> current.flatMap { wildcard(it) } }
                        i += 2
                    } else if (i + 1 < trimmed.length && trimmed[i + 1] == '[') {
                        // `.[…]` = 点号后紧接括号：跳过点号，括号段在下轮迭代处理。
                        // 不消费 `[` 是因为 parseBracket 需要完整的 `[inner]` 形态
                        i += 1
                    } else {
                        val name = readIdent(trimmed, i + 1) ?: throw RuleSyntaxException(path)
                        out += { current -> current.flatMap { child(it, name) } }
                        i += 1 + name.length
                    }
                }
                trimmed[i] == '[' -> {
                    val close = closeBracket(trimmed, i) ?: throw RuleSyntaxException(path)
                    out += parseBracket(trimmed.substring(i + 1, close), path)
                    i = close + 1
                }
                else -> throw RuleSyntaxException(path)
            }
        }
        return out
    }

    /** 合法标识符（点号路径的键名），读到下一个 `. `[` 前为止；为空返回 null */
    private fun readIdent(s: String, from: Int): String? {
        var i = from
        while (i < s.length && s[i] != '.' && s[i] != '[') i++
        val name = s.substring(from, i)
        return name.ifEmpty { null }
    }

    /** `[` 的配对 `]` 下标；引号内的 `]` 不参与配对 */
    private fun closeBracket(s: String, open: Int): Int? {
        var i = open + 1
        while (i < s.length) {
            val c = s[i]
            when {
                c == '"' || c == '\'' -> {
                    val end = s.indexOf(c, i + 1)
                    if (end < 0) return null
                    i = end + 1
                }
                c == ']' -> return i
                else -> i++
            }
        }
        return null
    }

    /** 括号内段的分发：通配 → 过滤器/脚本表达式拒绝 → 联合 → 单段；`?`/`(` 前缀必须先于顶层逗号切分判出 */
    private fun parseBracket(inner: String, path: String): (List<JsonElement>) -> List<JsonElement> = when {
        // wildcard 作用于单节点，段函数的口径是节点集，flatMap 适配（同点号通配分支）
        inner == "*" -> { current -> current.flatMap { wildcard(it) } }
        // 过滤器/脚本表达式的**前缀检查必须先于顶层逗号切分**：`[?(…,…)]` 内容里的逗号
        // 不是联合分隔符，先切分会把 `?` 前缀与判据拆散
        inner.startsWith("?") -> throw UnsupportedRuleFeatureException("JSONPath 过滤器", path)
        inner.startsWith("(") -> throw UnsupportedRuleFeatureException("JSONPath 脚本表达式", path)
        else -> {
            // 联合先于引号键判出：['name','author'] 整段以引号开头，若先走引号分支会被当成
            // 一个键名（name','author）查空，联合静默变 Miss
            val commaParts = splitTopLevel(inner, ',')
            if (commaParts.size > 1) parseUnion(commaParts, path)
            else parseSingle(inner.trim(), path)
        }
    }

    /** 单段（无顶层逗号）：带引号键 / 切片 / 索引三选一 */
    private fun parseSingle(t: String, path: String): (List<JsonElement>) -> List<JsonElement> {
        // 引号键必须以同一引号闭合：['a':2] / ['a'b] 这类残缺形态原先截掉首引号、拿 junk
        // 当字面键名查空而静默 Miss（§12 禁止），按语法错误类型化拒绝
        if (t.length >= 2 && (t.first() == '\'' || t.first() == '"')) {
            if (t.last() != t.first()) throw RuleSyntaxException(path)
            return { current -> current.flatMap { child(it, t.substring(1, t.length - 1)) } }
        }
        return parseSliceOrIndex(t, path)
    }

    /** 引号感知的顶层单字符切分（联合 `[0,'x']` / 切片里的逗号不可能是顶层） */
    private fun splitTopLevel(s: String, sep: Char): List<String> {
        val out = mutableListOf<String>()
        val buf = StringBuilder()
        var inQuote: Char? = null
        for (c in s) {
            val q = inQuote
            when {
                q != null -> {
                    buf.append(c)
                    if (c == q) inQuote = null
                }
                c == '"' || c == '\'' -> {
                    inQuote = c
                    buf.append(c)
                }
                c == sep -> {
                    out += buf.toString()
                    buf.clear()
                }
                else -> buf.append(c)
            }
        }
        out += buf.toString()
        return out
    }

    /** 联合段：每段是引号键或整数下标，命中各段并集；残缺引号键与 [parseSingle] 同口径拒绝 */
    private fun parseUnion(parts: List<String>, path: String): (List<JsonElement>) -> List<JsonElement> {
        data class UnionPart(val index: Int?, val name: String?)

        val parsed = parts.map { raw ->
            val t = raw.trim()
            val quoted = t.length >= 2 && (t.first() == '\'' || t.first() == '"')
            if (quoted) {
                // 同 parseSingle：残缺引号形态截出来的是 junk 键，静默查空不如类型化拒绝
                if (t.last() != t.first()) throw RuleSyntaxException(path)
                UnionPart(null, t.substring(1, t.length - 1))
            } else UnionPart(t.toIntOrNull() ?: throw RuleSyntaxException(path), null)
        }
        return { current ->
            current.flatMap { e ->
                parsed.flatMap { p ->
                    when {
                        p.index != null -> index(e, p.index)
                        else -> child(e, p.name!!)
                    }
                }
            }
        }
    }

    /** 标准半开切片：`[start:end(:step)]`，end 排他、负数从尾数；两段都可省略 */
    private fun parseSliceOrIndex(inner: String, path: String): (List<JsonElement>) -> List<JsonElement> {
        val m = Regex("""^(-?\d+)?\s*:\s*(-?\d+)?(?:\s*:\s*(\d+))?$""").matchEntire(inner)
        if (m == null) {
            val i = inner.toIntOrNull() ?: throw RuleSyntaxException(path)
            return { current -> current.flatMap { index(it, i) } }
        }
        val start = m.groupValues[1].toIntOrNull()
        val end = m.groupValues[2].toIntOrNull()
        val step = (m.groupValues[3].toIntOrNull() ?: 1).also { if (it == 0) throw RuleSyntaxException(path) }
        return { current ->
            current.flatMap { e ->
                if (e is JsonArray) {
                    val n = e.size
                    var i = start ?: 0
                    if (i < 0) i += n
                    var stop = end ?: n
                    if (stop < 0) stop += n
                    val out = mutableListOf<JsonElement>()
                    while (if (step > 0) i < stop else i > stop) {
                        if (i in 0 until n) out += e[i]
                        i += step
                    }
                    out
                } else emptyList()
            }
        }
    }

    // endregion

    // region 求值

    private fun child(e: JsonElement, name: String): List<JsonElement> =
        listOfNotNull((e as? JsonObject)?.get(name))

    private fun index(e: JsonElement, i: Int): List<JsonElement> {
        if (e !is JsonArray) return emptyList()
        val resolved = if (i < 0) e.size + i else i
        return listOfNotNull(e.getOrNull(resolved))
    }

    private fun wildcard(e: JsonElement): List<JsonElement> = when (e) {
        is JsonArray -> e.toList()
        is JsonObject -> e.values.toList()
        else -> emptyList()
    }

    /** 递归下探：先收本层同名值、再向全部子节点下探——文档序，先父后子 */
    private fun recursiveByName(e: JsonElement, name: String, out: MutableList<JsonElement> = mutableListOf()): List<JsonElement> {
        if (e is JsonObject) {
            e[name]?.let { out += it }
            e.values.forEach { recursiveByName(it, name, out) }
        } else if (e is JsonArray) {
            e.forEach { recursiveByName(it, name, out) }
        }
        return out
    }

    // endregion
}
