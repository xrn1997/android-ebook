package com.ebook.source.script

/**
 * 把 `##` 尾段应用到已取到的值上（规格 §2.4 的净化与 OnlyOne、§9 第 6 步）。
 *
 * 替换是**后置**的一步：它作用于取到的文本，不改变选择器行为。所以本类只吃 [RuleResult]，
 * 不认识 HTML 也不碰 [ChainLink]——「切分错了」与「替换错了」必须能各自定位。
 *
 * 三条形态口径都落在这里，而不是散在各后端里：
 *
 * - [RuleResult.Miss] 原样穿过（[apply] 第一步就返回）。把「未取到值」洗成
 *   `Texts(空)`/`Texts([""])` 会让它看起来像「取到了一个空串」，`||` 的短路随之反掉
 *   （§3.3 把未取到值定为独立事实）。
 * - [RuleResult.Nodes] 先按 `text()` 收敛成文本：**写了替换却没带取值器，说明作者要的是
 *   清洗后的文本**；返回节点集等于让替换无处可施，而清洗没发生这件事没有任何信号。
 * - [RuleResult.Matches] 一律给出类型化失败，见该分支的注释。
 * - [RuleResult.Jsons] 先按 [jsonText] 字符串化成文本：JSON 结果进替换段先字符串化——
 *   净化是文本级操作，字段值取出来就该是文本。
 *
 * 编译正则用 [RegexOption.MULTILINE]，与 [RegexBackend] 同一档：两处都是「作者写的 Java 正则
 * 方言」（§2.4 明说含 `(?i)`、`\w`、`\d`、`.*?`），一方按行锚定另一方不按，就会出现
 * 「同一条正则在选择段与替换段语义不同」——本仓反复踩的那类「同一个规则串两种解法」。
 */
internal object ReplacementApplier {

    /**
     * [rule] 是**原始规则串**（[ParsedRule.raw]）：非法正则要抛的是类型化语法错误，
     * 消息里得让用户对得上是哪条规则写坏了，只给切出来的模式段无从对上。
     *
     * [expansion] 默认空表：`##` 的模式段与替换文本段都可能是 `{{}}` 插值的目标
     * （`##{{key}}##{{page}}`），故两处在使用前都要回填占位符（§5.1 第 4 步）；
     * 直接对替换段做单测、不经过求值器的调用点传默认值即可。
     */
    fun apply(
        result: RuleResult,
        replacement: RegexReplacement?,
        rule: String,
        expansion: Expansion = Expansion.EMPTY,
    ): RuleResult {
        if (replacement == null) return result
        val texts = when (result) {
            RuleResult.Miss -> return result
            is RuleResult.Texts -> result.values
            is RuleResult.Nodes -> result.elements.map { it.text() }
            // §2.4 把 OnlyOne 与净化的适用场景写成「除四种列表场景之外」，AllInOne 恰在那四种里；
            // §2.3 又规定「正则段内的 # 不参与 ## 判定」。所以 Matches + 替换段同串是坏规则，
            // 而两种正则形态都不构成在这里把二维结构压平的理由——压平就把 Task 4 立起来的
            // 「条目 × 捕获组」废了，`chapterName: "$2"` 这类逐字段规则无从落地。
            // 抛类型化失败而不是猜一种语义：§12 禁止用看起来合理的结果冒充支持。
            is RuleResult.Matches ->
                throw UnsupportedRuleFeatureException("正则 AllInOne 与 ## 替换段同串", rule)
            // JSON 结果进替换段先字符串化：净化是文本级操作，字段值取出来就该是文本
            is RuleResult.Jsons -> result.items.map { it.jsonText() }
        }
        val filled = replacement.copy(
            pattern = expansion.fill(replacement.pattern),
            replacement = expansion.fill(replacement.replacement),
        )
        val regex = try {
            Regex(filled.pattern, RegexOption.MULTILINE)
        } catch (e: IllegalArgumentException) {
            // PatternSyntaxException 是 IllegalArgumentException 的子类。换成类型化语法错误（§3.3），
            // 一条写坏的替换正则可被 `||` 当未取到值继续下一支，而不是崩穿整次解析
            throw RuleSyntaxException(rule)
        }
        return RuleResult.Texts(texts.map { replaceOne(it, regex, filled) })
    }

    /**
     * 净化（[RegexReplacement.onlyFirst] = false）循环替换**全部**命中；
     * OnlyOne（三井号收尾）只替**第一个**。这一点是两种正则形态的唯一区别，混了就会把
     * 「只去掉一个前缀」的详情页字段洗成整串消失。
     *
     * 两条路都走 `Regex` 的替换串模板，`$n` 组引用由它展开（§2.4 的替换文本用法），不自己解析。
     * 替换作用于**每一个已取到的值**：多匹配是保留在结果里的（§3.1），清洗哪一条都不是特权。
     */
    private fun replaceOne(text: String, regex: Regex, replacement: RegexReplacement): String =
        if (replacement.onlyFirst) {
            regex.replaceFirst(text, replacement.replacement)
        } else {
            regex.replace(text, replacement.replacement)
        }
}
