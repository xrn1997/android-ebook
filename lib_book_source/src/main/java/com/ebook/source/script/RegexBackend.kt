package com.ebook.source.script

/**
 * 正则 AllInOne 后端（规格 §2.4 第一形态：以 `:` 开头、对整个源文本切分、字段用 `$n` 引用）。
 *
 * 「以 `:` 开头」「对整个源文本切分」「字段用 `$n` 引用」这三点合起来决定结果的形状必须是
 * **条目 × 捕获组**的二维结构（[RuleResult.Matches]）：AllInOne 先把一本书/一章「造出来」，
 * 逐字段规则再去里面取。用 `List<String>` 表达就把二维压成一维，`chapterName: "$2"` 无从落地
 * ——而 §2.4 明确该模式只能用在搜索列表、发现列表、详情预处理与目录列表，正是「造条目」的场景。
 *
 * 它拿的是**文本**而不是节点树（页面对它而言就是一串字符），所以 [RuleValue.Nodes] 与
 * [RuleValue.Texts] 都先串回文本：格式没给「在某几个元素里跑整源正则」这种形态，
 * 拼回文本是唯一不猜的解法（§2.4「对整个源文本」）。
 */
internal object RegexBackend {

    /**
     * [pattern] 是词法层剥掉 `:` 标志后的载荷；[reverse] 是 §2.5 的列表反序前缀
     * （语料实证形态 `-:<li>…`，前缀在 `:` 之前，故它不在载荷里）。
     *
     * [expansion] 在编译正则之前回填：模式串里可能有 `{{}}` 插值，带着占位符去编译
     * 得到的是一个能编过、却永远匹配不到东西的正则。
     */
    fun evaluate(pattern: String, input: RuleValue, reverse: Boolean, expansion: Expansion): RuleResult {
        val filled = expansion.fill(pattern)
        val source = when (input) {
            is RuleValue.Page -> input.source
            is RuleValue.Nodes -> input.elements.joinToString("\n") { it.html() }
            is RuleValue.Texts -> input.values.joinToString("\n")
            // AllInOne 是文本模式的后端（§2.4 只用于列表场景），JSON 上下文不在本仓支持集：
            // 类型化拒绝而不是拿紧凑 JSON 全文去猜一种「对 JSON 跑正则」的语义（§12）
            is RuleValue.Json -> throw UnsupportedRuleFeatureException("正则 AllInOne 作用于 JSON 上下文", filled)
        }
        if (filled.isBlank() || source.isBlank()) return RuleResult.Miss
        val regex = try {
            // 多行语义：语料的正文/目录页常按行切条目，`^`/`$` 要按行而非整源锚定
            Regex(filled, RegexOption.MULTILINE)
        } catch (e: IllegalArgumentException) {
            // PatternSyntaxException 是 IllegalArgumentException 的子类。换成类型化语法错误（§3.3）：
            // 一条写坏的正则可被 `||` 当未取到值继续下一支，而不是拿运行期异常崩穿整次解析
            throw RuleSyntaxException(":$filled")
        }
        // 每条匹配 = 一个条目，组列表下标 0 是整段匹配（§2.4 的 `$1` 从 1 起，与本仓的组号一致）
        val items = regex.findAll(source).map { m -> m.groupValues.toList() }.toList()
        if (items.isEmpty()) return RuleResult.Miss
        return RuleResult.Matches(if (reverse) items.reversed() else items)
    }
}

/**
 * 捕获组引用 `$n`（规格 §2.4）。
 *
 * 它单独立一个对象、不塞回 [RuleMode] 的判定表：`$2` 作为字符串看起来更像一条链式规则
 * （`$` 不是任何标志），要让词法层认它就得给模式表开特例，而 `-`/`@@`/`:` 的处理会随之
 * 变得不一致。组引用只在「AllInOne 造出的条目内部」有意义，是求值层的概念。
 */
internal object GroupRef {

    /** 整串就是一个组引用时返回组号，否则 null（调用方据此决定走组取值还是继续按规则解） */
    fun parseOrNull(text: String): Int? {
        val t = text.trim()
        if (!t.startsWith("\$") || t.length < 2) return null
        return t.substring(1).toIntOrNull()?.takeIf { it >= 0 }
    }

    /**
     * 取某条目的第 n 组。组号越界返回**空串**而不是抛：§3.3 把「未取到值」定为不报错的
     * 正常路径，而源作者数错组号（写了 `$3` 而正则只有两个组）是语料里的常态。
     * 不是组引用的文本原样返回，方便调用方把「取组」与「取字面量」写在一条路上。
     */
    fun valueOf(reference: String, item: List<String>): String {
        val n = parseOrNull(reference) ?: return reference
        return item.getOrNull(n).orEmpty()
    }
}
