package com.ebook.source.script

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * URL 选项尾段（规格 §6.1 `URL,{option}`）的定位扫描器与选项模型。
 *
 * 切分依据是「引号与花括号配对」而不是「按逗号裸切」：选项整体是一个 JSON 对象，
 * 值里的逗号、花括号必须由 JSON 自己表达（§6.1 原文）。扫描器找出**最后一个**
 * 深度 0 的 `,{`，且要求从那里到串尾恰是一个配平对象——中途断掉就当没有尾段。
 *
 * [commaDepth] 供切分层（[RuleSplitter]）传入 2a 的括号深度表（把 `{{}}`/`{}`/`[]`
 * 也算作深度，防止规则载荷里的花括号干扰）；取文层在**已回填**的 URL 上调用，
 * 回填值可能含引号与花括号，由本扫描器自己的引号感知兜住，默认深度恒 0。
 *
 * 识别的前提是**整段**花括号配平：取值段里出现落单的花括号会让扫描深度错位、
 * 候选识别不出来，此时按无尾段处理——从严取舍，误剥会截断取值段，漏剥只是回到
 * 不识别尾段的原行为。
 */
internal object ScriptUrlOption {

    /** 返回尾段起点（`,` 的下标）；无尾段返回 null */
    internal fun optionTailCut(s: String, commaDepth: (Int) -> Int = { 0 }): Int? {
        var candidate = -1
        var depth = 0
        var inString = false
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (inString) {
                if (c == '\\') i++          // 跳过转义对
                else if (c == '"') inString = false
            } else when (c) {
                '"' -> inString = true
                '{' -> {
                    depth++
                    // 深度归零后的第一个 { 才可能是尾段开头；逗号必须在它紧前且在规则层深度 0
                    if (depth == 1 && i > 0 && s[i - 1] == ',' && commaDepth(i - 1) == 0) candidate = i - 1
                }
                '}' -> depth--
            }
            i++
        }
        return if (candidate >= 0 && depth == 0 && s.endsWith("}")) candidate else null
    }

    /** 尾段切分：返回（取值段, 尾段）；无尾段返回 null。尾段以 `,` 开头（起点即逗号） */
    internal fun splitTail(s: String): Pair<String, String>? =
        optionTailCut(s)?.let { s.substring(0, it) to s.substring(it) }

    /**
     * 解析选项对象（[rule] 只作错误消息定位用）。
     *
     * 拒绝集与理由（§10 能力矩阵）：`webView` 无 WebView 桥；`proxy` 安全口径（书源请求
     * 不得出到任意代理）；`dnsIp`/`followRedirects` 是客户端配置口径非规则语义。
     * `js`/`bodyJs` 已改为**携带**（识别归本层、执行归 `ScriptPageFetcher`），
     * 不再出现在拒绝集里。`type` 语义未核实（§6.2）、连同一切未知键**忽略**——
     * 格式允许未知键存在，把不认识的键当错误会让带新兴键的源整条不可用。
     * 「声明了」才拒绝：空串在 §1.1 默认列就是未配置——但**非空原始值一律算声明**，
     * 布尔 `false`、数字 `0` 同样是作者写下的意图（上游对 `webView` 本就是非空即用）。
     * `timeout` 若给出必须是正整数毫秒：0 在底层超时语义里是「永不超时」、负数会让传输层
     * 抛非类型化异常——静默翻转语义比报错更危险，作者写错就是规则值错误（§12 如实报）。
     */
    fun parse(obj: JsonObject, rule: String): ScriptUrlOptions {
        fun declared(key: String): Boolean = when (val v = obj[key]) {
            null, is JsonNull -> false
            is JsonPrimitive -> v.contentOrNull?.isNotBlank() == true
            else -> true
        }
        if (declared("webView")) throw UnsupportedRuleFeatureException("webView 渲染", rule)
        if (declared("proxy")) throw UnsupportedRuleFeatureException("请求代理", rule)
        if (declared("dnsIp")) throw UnsupportedRuleFeatureException("dnsIp 解析", rule)
        if (declared("followRedirects")) throw UnsupportedRuleFeatureException("followRedirects", rule)
        // js/bodyJs 不再在此抛待执行（关键事实 5）：识别归这里，执行归 `ScriptPageFetcher`。
        // 「本机没有沙箱」的报错也在取文层发，两条路径的话术因此与 2d 完全一致。
        val js = (obj["js"] as? JsonPrimitive)?.takeIf { it != JsonNull }?.content?.trim().orEmpty()
            .takeIf { it.isNotBlank() }
        val bodyJs = (obj["bodyJs"] as? JsonPrimitive)?.takeIf { it != JsonNull }?.content?.trim().orEmpty()
            .takeIf { it.isNotBlank() }

        val headers = when (val h = obj["headers"]) {
            null, is JsonNull -> emptyMap()
            is JsonObject -> h.entries.associate { (k, v) -> k to primitiveText(v) }
            is JsonPrimitive -> (runCatching { ScriptJson.parseToJsonElement(h.content) }.getOrNull() as? JsonObject)
                ?.let { parsed -> parsed.entries.associate { (k, v) -> k to primitiveText(v) } }
                ?: throw RuleSyntaxException(rule)
            else -> throw RuleSyntaxException(rule)
        }
        val body = when (val b = obj["body"]) {
            null, is JsonNull -> null
            is JsonPrimitive -> b.content
            // §6.3 本仓规定：对象/数组按 JSON 文本序列化后使用（§11-6 跟踪）
            else -> b.toString()
        }
        return ScriptUrlOptions(
            method = (obj["method"] as? JsonPrimitive)?.contentOrNull?.trim()?.uppercase().takeUnless { it.isNullOrEmpty() } ?: "GET",
            charset = (obj["charset"] as? JsonPrimitive)?.contentOrNull?.trim().takeUnless { it.isNullOrEmpty() } ?: "UTF-8",
            body = body,
            headers = headers,
            timeoutMs = (obj["timeout"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()?.also {
                if (it <= 0) throw RuleSyntaxException(rule)
            },
            retry = (obj["retry"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()?.also {
                // 与 timeout 同一条口径：可解析但写成负数就是作者把规则值写错了，如实报（§12）。
                // 不在这一层挡下，它会一路走到 OkHttpScriptTransport 的 coerceAtLeast(0) 被静默
                // 纠正成 0，作者永远不知道自己的重试次数没生效。不可解析的写法仍与 timeout 一致
                // （toIntOrNull 为 null 即「视为未给」，取缺省 0）。
                if (it < 0) throw RuleSyntaxException(rule)
            } ?: 0,
            js = js,
            bodyJs = bodyJs,
        )
    }

    /** 头值取文本：标量直接取 content，嵌套对象/数组按 JSON 文本序列化 */
    private fun primitiveText(v: JsonElement): String = when (v) {
        is JsonPrimitive -> v.content
        else -> v.toString()
    }
}

/**
 * 解析后的请求选项（规格 §6.2 实现集；`proxy`/`webView`/`dnsIp`/`followRedirects` 见 [parse] 的拒绝）。
 *
 * [headers] 的键**保持原样大小写**（§6.2：key 区分大小写，`User-Agent` 正确、`user-agent` 无效）
 * ——HTTP 头本身大小写不敏感，但作者写小写 UA 说明按某个站点的怪癖在配，原样透传最诚实。
 */
internal data class ScriptUrlOptions(
    val method: String = "GET",
    val charset: String = "UTF-8",
    val body: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val timeoutMs: Long? = null,
    val retry: Int = 0,

    /** URL 选项 `js`：请求前改写 url/headers（由取文层执行）。null = 未声明 */
    val js: String? = null,

    /** URL 选项 `bodyJs`：对响应体做二次处理（由取文层执行）。null = 未声明 */
    val bodyJs: String? = null,
) {
    companion object {
        val DEFAULT = ScriptUrlOptions()
    }
}
