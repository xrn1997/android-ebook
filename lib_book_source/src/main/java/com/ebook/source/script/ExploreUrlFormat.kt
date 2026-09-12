package com.ebook.source.script

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 发现页 `exploreUrl` 的条目切分（规格 §8；2d 的发现链路消费）。
 *
 * 文本格式一：`名称::URL`，条目分隔**行优先**（先按 `\n` 切、再在单行内按 `&&` 切，
 * §11-13 本仓规定——URL 查询串天然含 `&&`，条目级只认行边界能把误切面收到最小）；
 * `{{}}`/`{}` 内容对分隔符**不透明**（§8.3：JS 表达式里的 `&&` 是数据）。
 * 缺 `名称::` 的条目标题为空——它可能是合法的单入口写法，丢弃比保留更难发现。
 * 未闭合的引号/花括号**从宽**：余段保持不透明、只是少切（不炸、不抛）——与
 * `ScriptUrlOption.optionTailCut` 的「整段不配平从严」是两处刻意不同的口径：
 * 条目少切只是入口变少，URL 尾段错判会发出错误请求。
 *
 * JSON 格式二：只取 `{title,url}`；`style` 与交互控件不是本仓能力（§10），含控件项忽略。
 *
 * 这是两种**可切分**的成文格式；第三种形态是**脚本程序**，本对象不消费——见 [isScriptProgram]。
 */
internal object ExploreUrlFormat {

    data class ExploreEntry(val title: String, val urlRule: String)

    /**
     * 该串是否为**脚本程序形态**：整串就是一段 `<js>…</js>`，由发现页脚本现场算出条目数组。
     *
     * 上游把这种 `exploreUrl` 的返回值定义为 `[{title, url, style, type, chars, default, action}]`：
     * `url` 是分类地址，其余键是**发现页 UI 的渲染与交互协议**——`type`/`chars`/`default`/`action`
     * 描述选择器等控件，`style.layout_*` 是布局键，控件当前值经 `infoMap` 读写、
     * `java.refreshExplore()` 触发重跑。本仓没有可承载这份协议的发现页宿主（§10 把控件一栏列为 ❌、
     * `exploreScreen` 键存在即登记不支持），故这一段 JS **不求值、其产物也不消费**——
     * 它不是「还没接线的能力」，是明确的能力边界。
     *
     * 为什么必须「识别出来」而不是让它掉进文本切分：它不是 `名称::URL` 文本，按行切会把
     * **JS 源码逐行当成 URL** 拼到源根地址上发出去。真实事故（2026-09-10，`番茄（发现）`，
     * `bookSourceUrl = https://fanqienovel.com`）里这条源被切成 **105 条**空标题条目、
     * 发出 105 次 `HTTP 404：https://fanqienovel.com/<JS 源码行>`，然后 105 个空区块
     * 撞死书城列表（`LazyColumn` 的 key 全为 `""`）——「静默产出坏源」的最坏形态。
     *
     * 返回空清单而不是抛：**发现面的缺口后果是「少一个入口」，不是「整条源不可用」**——
     * 搜索/详情/目录/正文四条链路与 `exploreUrl` 无关，仍可正常使用。这与
     * `getKindBook` 把分类页失败按空列表处置是同一条取舍（页面按「没数据」处理），
     * 与 [splitText] 的「未闭合引号/花括号从宽」也同源：本对象一律少切、不炸、不抛。
     *
     * 该形态的**如实报告**落在词法层（`ScriptRuleSet` 登记 `EXPLORE_URL_SCRIPT`），不在本对象：
     * 切分器只描述形状，能力登记归装载器（§12「失败要如实报」的分工）。
     */
    internal fun isScriptProgram(text: String): Boolean = text.trimStart().startsWith("<js>")

    fun split(raw: String): List<ExploreEntry> {
        val text = raw.trim()
        if (text.isEmpty()) return emptyList()
        // 脚本程序形态不是条目文本：见 isScriptProgram 的 KDoc（切它等于把 JS 源码当 URL 发出去）
        if (isScriptProgram(text)) return emptyList()
        return if (text.startsWith("[")) splitJson(text) else splitText(text)
    }

    private fun splitJson(text: String): List<ExploreEntry> =
        (runCatching { ScriptJson.parseToJsonElement(text) }.getOrNull() as? JsonArray)
            ?.mapNotNull { el ->
                val obj = el as? JsonObject ?: return@mapNotNull null
                val url = (obj["url"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
                if (url.isEmpty()) null
                else ExploreEntry((obj["title"] as? JsonPrimitive)?.contentOrNull.orEmpty(), url)
            }
            ?: emptyList()

    private fun splitText(text: String): List<ExploreEntry> {
        val out = mutableListOf<ExploreEntry>()
        for (line in text.split('\n')) {
            for (part in splitTopLevel(line, "&&")) {
                val entry = part.trim()
                if (entry.isEmpty()) continue
                val sep = topLevelIndexOf(entry, "::")
                if (sep < 0) out += ExploreEntry("", entry)
                else out += ExploreEntry(entry.substring(0, sep).trim(), entry.substring(sep + 2).trim())
            }
        }
        return out
    }

    /** 引号与 `{}` 深度感知的顶层切分；`sep` 在引号内或花括号深度 > 0 时不切 */
    private fun splitTopLevel(s: String, sep: String): List<String> {
        val out = mutableListOf<String>()
        var depth = 0
        var inQuote = false
        var cursor = 0
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '"' && (i == 0 || s[i - 1] != '\\') -> inQuote = !inQuote
                !inQuote && c == '{' -> depth++
                !inQuote && c == '}' -> depth = (depth - 1).coerceAtLeast(0)
                !inQuote && depth == 0 && s.startsWith(sep, i) -> {
                    out += s.substring(cursor, i)
                    cursor = i + sep.length
                    i += sep.length
                    continue
                }
            }
            i++
        }
        out += s.substring(cursor)
        return out
    }

    /** 同深度口径找 `sep` 的首个顶层出现位置；找不到返回 -1 */
    private fun topLevelIndexOf(s: String, sep: String): Int {
        var depth = 0
        var inQuote = false
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '"' && (i == 0 || s[i - 1] != '\\') -> inQuote = !inQuote
                !inQuote && c == '{' -> depth++
                !inQuote && c == '}' -> depth = (depth - 1).coerceAtLeast(0)
                !inQuote && depth == 0 && s.startsWith(sep, i) -> return i
            }
            i++
        }
        return -1
    }
}
