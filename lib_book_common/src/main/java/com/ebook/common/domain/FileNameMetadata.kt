package com.ebook.common.domain

/**
 * 从本地文件名解析书名与作者（spec §6）。
 *
 * 存在的理由是**作品身份**：`comment_key = hash(书名 ‖ 作者)`（spec §9.1）。旧实现直接拿
 * 文件名去扩展名当书名、作者写死占位，后果是两本同名不同作者的书算出同一个键（误并），
 * 同一本书的两种文件名写法算出两个键（分裂）。这两类错误一旦发生就开始积累评论数据。
 *
 * 解析不出作者返回 null，由显示层决定占位词；占位词与键计算无关（见 [CommentKey]）。
 */
object FileNameMetadata {

    /** @param author null 表示文件名里没有可识别的作者信息 */
    data class Parsed(val title: String, val author: String?)

    private val extensions = listOf(".txt", ".epub")

    /** 解析规则：titleGroup 是书名所在捕获组，authorGroup 为 null 表示该模式不含作者 */
    private class Rule(val regex: Regex, val titleGroup: Int, val authorGroup: Int?)

    /** 按优先级排列，命中即止；第一条模式的前缀杂项用非捕获组，书名仍是组 1 */
    private val rules = listOf(
        Rule(Regex("""^(?:.*?)《(.+?)》.*?作者\s*[：:]\s*(.+)$"""), 1, 2),
        Rule(Regex("""^(.+?)\s+作者\s*[：:]\s*(.+)$"""), 1, 2),
        Rule(Regex("""^(.+?)\s+by\s+(.+)$""", RegexOption.IGNORE_CASE), 1, 2),
        Rule(Regex("""^《(.+?)》\s*$"""), 1, null),
    )

    /** 文件名里常见的站点/版本尾巴，成对括号包裹，反复剥直到不再变化 */
    private val trailingNoise = listOf(
        Regex("""\s*[(（][^)）]*[)）]\s*$"""),
        Regex("""\s*[【\[][^】\]]*[】\]]\s*$"""),
    )

    /** 文件名可能极长（整段简介塞进文件名），书名截断上限 */
    private const val MAX_TITLE_CHARS = 120

    fun parse(rawName: String): Parsed {
        val base = stripNoise(rawName)
        for (rule in rules) {
            val match = rule.regex.find(base) ?: continue
            val title = match.groupValues[rule.titleGroup].trim().limitTitle()
            if (title.isEmpty()) continue
            val author = rule.authorGroup
                ?.let { match.groupValues[it].trim() }
                ?.takeIf { it.isNotEmpty() }
            return Parsed(title, author)
        }
        return Parsed(base.limitTitle(), null)
    }

    private fun stripNoise(name: String): String {
        var result = name.trim()
        extensions.firstOrNull { result.endsWith(it, ignoreCase = true) }?.let { ext ->
            result = result.substring(0, result.length - ext.length).trim()
        }
        var changed = true
        while (changed) {
            changed = false
            for (noise in trailingNoise) {
                val stripped = noise.replace(result, "").trim()
                if (stripped != result) {
                    result = stripped
                    changed = true
                }
            }
        }
        return result
    }

    private fun String.limitTitle(): String =
        if (length <= MAX_TITLE_CHARS) this else take(MAX_TITLE_CHARS).trim()
}
