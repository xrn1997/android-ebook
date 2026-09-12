package com.ebook.source.script

/**
 * 一条规则串的切分结果：求值树 + 被剥到末尾的正则替换段（规格 §9 的第 2 步与第 6 步）
 * + 被剥出的 URL 选项尾段（§2.3 步骤 3，求值层按 §9 第 7 步回附到结果文本上）。
 *
 * 尾段在本类只负责「剥出」：让组合符/链式切分不被 `,{...}` 干扰成垃圾段；
 * 「取文层消费」在求值器与 2c 的 URL 解析侧。
 */
internal data class ParsedRule(
    val root: RuleNode,
    val replacement: RegexReplacement?,
    /** §2.3 步骤 3 剥出的 URL 选项尾段（含前导逗号，如 `,{"charset":"gbk"}`）；无则 null */
    val optionTail: String?,
    val raw: String,
)

/**
 * 规则串 → 树。切分次序固定 `%%` → `||` → `&&` → 模式判定（规格 §2.3，本仓规定）。
 *
 * 次序表有一个先前置例外：§2.2 硬约束 1 把 js 与正则一并排除在组合符之外，
 * 故 [RuleMode.JS] 与 [RuleMode.REGEX_ALL_IN_ONE] 在进组合循环之前就直接成一个叶（见 [node]），
 * 并且**同样先于** `##` 替换尾段的剥离（见 [parse]——§2.3 的步骤 1/1b 排在步骤 2 之前）。
 * 同一次探针还会剥掉规格 §2.5 的反序前缀 `-`（[RuleHead.reverse]）：组合切分看到的段文本里
 * 不含该前缀，反序落在各支自己的 [RuleNode.Leaf] 上。
 *
 * §2.3 步骤 3 的 URL 选项尾段（[ParsedRule.optionTail]）排在步骤 2 **之后**：只扫
 * `##` 之前的取值段——替换文本里的 `,{...}`（`##$##,{...}` 惯用法，见下方 [parse] 的注释）属替换段，不在此剥。
 *
 * 为什么这次序是「本仓规定」而不是上游实证：公开文档只给出三个组合符各自的语义，
 * 没有给出混用时的优先级。清洁室实现必须有唯一答案，否则同一份书源在两次实现里会解出
 * 不同结果。本段把语料与文档示例逐条锁进单测；将来与真实源行为冲突时按 bug 修
 * （ADR-0029「清洁室语义偏差按 bug 修」），改次序时**必须同时改本 KDoc 与单测**。
 *
 * 下标一律相对**原始规则串**：括号深度只算一次并随递归传递下标区间，避免对子串
 * 重算深度时下标平移导致的错位（这类错位的症状是「括号有时保护得住、有时保护不住」）。
 */
internal object RuleSplitter {

    fun parse(rule: String): ParsedRule {
        val d = RuleScanner.depths(rule)
        // §2.3 的步骤 1/1b（整条即 JS 段 / 正则 AllInOne）**先于**步骤 2（剥 ## 替换尾段）：
        // 这两段的载荷是 JS 与正则本身，串内的 `##` 是字面量而不是分隔符
        // （§2.3「嵌套」条：「正则段内的 # 不参与 ## 判定」；§2.4 又把 OnlyOne/净化的适用场景
        // 明确排除在 AllInOne 能用的四种列表场景之外）。剥错的后果不是报错，而是正则少一段、
        // 二维条目被拉去跑一次无意义的文本替换——与 [node] 里对组合符的那道豁免同一个根因。
        val whole = RuleMode.of(rule).mode
        if (whole == RuleMode.JS || whole == RuleMode.REGEX_ALL_IN_ONE) {
            // §6.1：选项尾段不挂在 JS/正则上，前置例外整条豁免。把 URL 选项尾段拼到结果后面的
            // 惯用法是 `##$##,{...}`——第二个 `##` 之后**带逗号**，替换文本即含前导逗号的尾段本身。
            // 不带逗号的 `##$##{...}` 不报错，但那个 `{"..."}` 会被当作 URL 正文拼进去而不被识别为
            // 选项，最终请求一个带花括号的地址而取不到内容（见 RegexReplacement 类 KDoc）
            return ParsedRule(node(rule, d, 0, rule.length), null, null, rule)
        }
        val cut = RuleScanner.topLevelOf(rule, d, "##").firstOrNull()
        val beforeReplacement = cut ?: rule.length
        // §2.3 步骤 3：选项尾段只在 `##` 之前的取值段里剥（步骤 2 之后，见类 KDoc）。
        // 取值段是原始串的前缀，下标不变，故深度表可按原始下标直接查；
        // 尾段文本从原串按下标截取，但**必须止于 beforeReplacement**——否则 `##` 存在时
        // 替换段会被一并并进尾段（「替换段与选项尾段并存时各归各」用例锁的就是这条边界）。
        val tailCut = ScriptUrlOption.optionTailCut(rule.substring(0, beforeReplacement)) { d[it] }
        val valueEnd = tailCut ?: beforeReplacement
        val replacement = cut?.let { RegexReplacement.parse(rule.substring(it)) }
        return ParsedRule(
            node(rule, d, 0, valueEnd),
            replacement,
            tailCut?.let { rule.substring(it, beforeReplacement) },
            rule,
        )
    }

    private fun node(s: String, d: IntArray, from: Int, to: Int): RuleNode {
        val (start, end) = trim(s, from, to)
        if (start >= end) return RuleNode.Empty
        // §2.2 硬约束 1：组合符「只能在同种规则之间使用，不包括 js 和正则」。
        // `@js:`/`<js>` 与以 `:` 开头的正则 AllInOne 都辖整条规则，故在组合切分之前判定——
        // 否则 JS 里的 `'||'`、正则里的字面量 `||`/`%%` 会被当成组合符把一条规则切成两支。
        // 这道豁免只能靠模式判定、不能靠扫描器：`()` 不计入括号深度（§2.3 只认 {{}}/{}/[]），
        // 所以 :x("a||b") 里的 || 在扫描器看来就在深度 0。切错的后果不是报错而是静默解错内容。
        val probe = RuleMode.of(s.substring(start, end))
        if (probe.mode == RuleMode.JS || probe.mode == RuleMode.REGEX_ALL_IN_ONE) {
            return RuleNode.Leaf(probe.mode, probe.body, probe.reverse)
        }
        for (sep in COMBINATORS) {
            val hits = RuleScanner.topLevelOf(s, d, sep.symbol, start, end)
            if (hits.isEmpty()) continue
            val parts = ArrayList<RuleNode>(hits.size + 1)
            var cursor = start
            for (h in hits) {
                parts += node(s, d, cursor, h)
                cursor = h + sep.symbol.length
            }
            parts += node(s, d, cursor, end)
            return sep.wrap(parts)
        }
        // 反序标志按「每条规则段各自」判定：走到这里的就是一个整段，前导 `-` 已在 probe 里剥净
        return RuleNode.Leaf(probe.mode, probe.body, probe.reverse)
    }

    /** 三个组合符按优先级排列，并各自绑定其树节点 */
    private class Combinator(val symbol: String, val wrap: (List<RuleNode>) -> RuleNode)

    private val COMBINATORS = listOf(
        Combinator("%%") { RuleNode.Percent(it) },
        Combinator("||") { RuleNode.FirstOf(it) },
        Combinator("&&") { RuleNode.AllOf(it) },
    )

    /** 去掉区间两端空白，返回（新起, 新止）。空白不改括号深度，故可安全缩区间。 */
    private fun trim(s: String, from: Int, to: Int): Pair<Int, Int> {
        var i = from
        var j = to
        while (i < j && s[i].isWhitespace()) i++
        while (j > i && s[j - 1].isWhitespace()) j--
        return i to j
    }
}
