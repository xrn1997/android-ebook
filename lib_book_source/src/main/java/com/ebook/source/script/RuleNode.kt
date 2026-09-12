package com.ebook.source.script

/**
 * 一条规则串切分后的树（规格 §2.2/§2.3）。
 *
 * 四种组合形态各有独立真值语义，求值层必须能一比一读出来：
 * [Percent] 每路都求值后按序交错取数；[FirstOf] 短路——前一支未取到值才走下一支；
 * [AllOf] 每支都求值后合并；[Leaf] 才是真正带模式的规则体。
 *
 * [Empty] 表达「这一段是空串」：它**不是**没有节点，而是「未取到值」这个事实的载体
 * （§2.3 空段行为）。把它折叠掉会让 `a||` 与 `a` 变成同一棵树，短路语义随之消失。
 */
internal sealed interface RuleNode {
    object Empty : RuleNode

    /**
     * 一条带模式的规则体。
     *
     * [reverse] 是规格 §2.5「列表反序」的前导 `-`（由 [RuleMode.of] 剥出，见 [RuleHead]）。
     * 它是**每条规则段自己的**标志：切分成树时对每支各判一次，故
     * `-@@tag.a@text||tag.b@text` 只反序前一支。而「`-` 与组合符混用时的作用域」
     * （那样写到底是整条反序还是前一支反序）规格没有答案，已记进规格 §11 的未知项清单——
     * 本仓按「每条规则段各自」实现，2b 不得自行推广成「辖整个组合表达式」。
     */
    data class Leaf(val mode: RuleMode, val body: String, val reverse: Boolean = false) : RuleNode

    data class AllOf(val parts: List<RuleNode>) : RuleNode

    data class FirstOf(val alternatives: List<RuleNode>) : RuleNode

    data class Percent(val streams: List<RuleNode>) : RuleNode
}
