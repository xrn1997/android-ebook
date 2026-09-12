package com.ebook.source.script

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import org.jsoup.nodes.Element

/**
 * 列表字段 → 条目上下文 → 逐字段取值（规格 §9 第 5 步「每支的输入 = 当前上下文文档/文本」的装配件）。
 *
 * 与 [ScriptRuleEvaluator] 的分工：求值器答「一条规则对一个输入解出什么」；本类答
 * 「列表结果怎么变成条目、条目里的字段规则喂哪个输入、URL 类字段怎么落位」——
 * 搜索/发现/详情/目录四个链路共用，各写一份就会出现「同一个 bookList 结果两种条目口径」。
 */
internal class ScriptFieldExtractor(
    private val evaluator: ScriptRuleEvaluator,
    private val sourceRoot: String,
) {

    /** 一个条目的求值上下文：四类列表结果各走各的字段入口（`evaluate`/`evaluateOnItem`/`evaluateOnJsonItem`） */
    internal sealed interface ItemContext {
        data class ElementCtx(val element: Element) : ItemContext
        data class GroupsCtx(val groups: List<String>) : ItemContext
        data class JsonCtx(val node: JsonElement) : ItemContext
        data class TextCtx(val text: String) : ItemContext
    }

    /**
     * 列表字段的求值结果 → 条目上下文集；[RuleResult.Miss] 即零条目（§3.1 未取到值独立事实）。
     *
     * JSON 侧有一条**本层**的展开口径：JSONPath 列表字段的惯用写法 `$.data.books` 解出的
     * 是**数组节点本身**（后端刻意保留节点形态——单值字段的 `$.…tags` 要把数组字符串化，
     * 该行为由 `JsonPathBackendTest` 锁死），条目 = 数组元素；展开放本层而不放后端，
     * 因为「数组当条目集」只在列表字段成立，单值字段见到数组要的是它的文本。
     */
    fun listItems(result: RuleResult): List<ItemContext> = when (result) {
        RuleResult.Miss -> emptyList()
        is RuleResult.Nodes -> result.elements.map { ItemContext.ElementCtx(it) }
        is RuleResult.Matches -> result.items.map { ItemContext.GroupsCtx(it) }
        is RuleResult.Jsons -> result.items
            .flatMap { node -> if (node is JsonArray) node.toList() else listOf(node) }
            .map { ItemContext.JsonCtx(it) }
        is RuleResult.Texts -> result.values.map { ItemContext.TextCtx(it) }
    }

    /**
     * 条目 × 字段规则 → 单值（§11-2 收敛口径：firstText，没有则空串）。
     * 字段规则空白直接给空串：空规则在求值器里本就是 Miss，这里省一次切分开销且语义相同。
     */
    fun fieldText(item: ItemContext, fieldRule: String): String {
        if (fieldRule.isBlank()) return ""
        return when (item) {
            is ItemContext.ElementCtx -> evaluator.evaluate(fieldRule, RuleValue.Nodes(listOf(item.element)))
            is ItemContext.GroupsCtx -> evaluator.evaluateOnItem(fieldRule, item.groups)
            is ItemContext.JsonCtx -> evaluator.evaluateOnJsonItem(fieldRule, item.node)
            is ItemContext.TextCtx -> evaluator.evaluate(fieldRule, RuleValue.Texts(listOf(item.text)))
        }.firstText()
    }

    /** URL 类字段：取值后按 §9 第 7 步落位（尾段回附，见 [resolveUrl]） */
    fun fieldUrl(item: ItemContext, fieldRule: String, baseUrl: String): String {
        val raw = fieldText(item, fieldRule)
        return if (raw.isBlank()) "" else resolveUrl(raw, baseUrl)
    }

    /**
     * URL 字段落位（§6.5）：先剥选项尾段 → `TocPageUrl.join` 三形态落位 → 回附尾段。
     * 尾段是**下一次取文输入**的一部分（gbk/POST 选项跟 URL 走），字段落位只处理地址部分；
     * 不剥就落位会把 `,{...}` 当成路径段拼出 404。
     */
    fun resolveUrl(raw: String, baseUrl: String): String {
        val (url, tail) = ScriptUrlOption.splitTail(raw) ?: (raw to null)
        val absolute = com.ebook.source.analyze.TocPageUrl.join(baseUrl, url, sourceRoot)
        return if (tail == null) absolute else absolute + tail
    }

    /**
     * 列表字段转发（bookList/chapterList/explore）：用列表口径，末段裸词按 CSS 选择器出节点集（§3.2）。
     * 列表字段要的是**元素**（`.box@ul@li` 的末段 `li`），不是文本。
     */
    fun evaluateListField(rule: String, input: RuleValue): RuleResult =
        evaluator.evaluate(rule, input, ChainTail.SELECTOR)

    /**
     * 单值字段与翻页链转发（content/nextTocUrl/nextContentUrl 等）：单值口径，末段裸词按
     * `@任意属性名` 取值器（§3.2）。这些字段的规则末段在真实语料里恒为取值器（`@html`/`@href`），
     * 与列表口径同解；用单值口径是为了与「单值字段」的规格分类一致，并让裸词末段的兜底更稳。
     */
    fun evaluateValue(rule: String, input: RuleValue): RuleResult =
        evaluator.evaluate(rule, input)
}
