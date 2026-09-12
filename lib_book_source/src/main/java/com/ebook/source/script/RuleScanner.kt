package com.ebook.source.script

/**
 * 规则串的「可切分位置」扫描器（规格 §2.3 的「嵌套」条）。
 *
 * 为什么需要它：分隔符（`%%`/`||`/`&&`/`##`/`@`）在字面上无处不在，而语料里
 * `##[|\\]` 这类字符类、`{"body":"a&&b"}` 这类 URL 选项值、`{{$.id}}` 这类插值里
 * 都可能含分隔符。任何一次「裸切」都会把一条规则腰斩，且切错的后果不是崩溃而是
 * 解出**不相干的内容**——本仓最难查的那类故障。所以所有分隔符判定都必须先看括号深度。
 *
 * 深度口径：`{{`/`}}` 各计 2（双花括号是能自我识别的强标记，计 2 让 `{{` 与 `{`
 * 在同一套计数下共存），`{`/`}` 与 `[`/`]` 各计 1。右括号按「先减后记」处理，
 * 使闭合符本身落在外层深度上；多余的右括号用 `coerceAtLeast(0)` 夹住，
 * 深度恒非负——残缺规则串是真实存在的输入形态，扫描器不许抛。
 */
internal object RuleScanner {

    /** 每个下标处「包住该字符的括号层数」。返回数组长度恒等于入参长度。 */
    fun depths(s: String): IntArray {
        val d = IntArray(s.length)
        var depth = 0
        var i = 0
        while (i < s.length) {
            val c = s[i]
            val n = if (i + 1 < s.length) s[i + 1] else ' '
            if (c == '{' && n == '{') {
                d[i] = depth
                d[i + 1] = depth + 1
                depth += 2
                i += 2
            } else if (c == '}' && n == '}') {
                depth = (depth - 2).coerceAtLeast(0)
                d[i] = depth
                d[i + 1] = depth
                i += 2
            } else if (c == '{' || c == '[') {
                d[i] = depth
                depth++
                i++
            } else if (c == '}' || c == ']') {
                depth = (depth - 1).coerceAtLeast(0)
                d[i] = depth
                i++
            } else {
                d[i] = depth
                i++
            }
        }
        return d
    }

    /**
     * `sep` 在 `[from, to)` 内、且**起点位于深度 0** 的全部下标（按出现顺序）。
     *
     * 命中后跳过整个 `sep`，因此 `###` 这种收尾不会被当成两个 `##` 的重叠起点。
     */
    fun topLevelOf(
        s: String,
        d: IntArray,
        sep: String,
        from: Int = 0,
        to: Int = s.length,
    ): List<Int> {
        if (sep.isEmpty()) return emptyList()
        val out = mutableListOf<Int>()
        var i = from
        val last = to - sep.length
        while (i <= last) {
            if (d.getOrElse(i) { 0 } == 0 && s.startsWith(sep, i)) {
                out += i
                i += sep.length
            } else {
                i++
            }
        }
        return out
    }
}
