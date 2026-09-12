package com.ebook.source.script

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import org.jsoup.nodes.Element

/**
 * 一次规则求值的**输出**（规格 §3.1~§3.3）。
 *
 * [Miss] 独立存在：把「未取到值」折叠成空列表，`||` 就再也分不清「这一支没值」与
 * 「这一支取到了零个条目」，短路语义整体失效（§2.3 空段行为的依据）。
 *
 * 列表型结果（[Nodes] / [Texts] / [Matches]）**一律保留全部匹配**，本层不替调用方
 * 收敛——规格 §3.1 说「不加位置会获取所有」，却没说单值字段怎么变成一个串，
 * 这条未知项（§11-2）因此留在字段级调用方（2d）用 [firstText] 一处收口，
 * 而不是散落在每个后端里各猜一次。
 */
internal sealed interface RuleResult {

    object Miss : RuleResult

    data class Nodes(val elements: List<Element>) : RuleResult

    data class Texts(val values: List<String>) : RuleResult

    /**
     * 正则 AllInOne 的产物：每个条目是**捕获组列表**（下标 0 是整段匹配），
     * 逐字段规则用 `$1`/`$2` 引用（§2.4）。塌成字符串列表就会丢掉「一条目多字段」
     * 的结构，`chapterName: "$2"` 这类写法将无从落地。
     */
    data class Matches(val items: List<List<String>>) : RuleResult

    /**
     * JSONPath 的产物：命中的**全部** JSON 节点（§3.1 一律保留全部匹配的口径同样适用）。
     *
     * 保留节点形态而不是落成字符串列表：JSON 源的列表字段（`$.data.books`）解出的每个
     * 条目还要作为子字段规则（`$.name`/`$.author`）的上下文，字符串化会把结构提前压扁，
     * 与 AllInOne「条目×捕获组二维」同一理由。单值字段的收敛仍归 [firstText] 一处。
     */
    data class Jsons(val items: List<JsonElement>) : RuleResult
}

/** 单值字段的收敛口径（规格 §11-2 本仓规定）：取第一个，没有则空串。只允许在这一处收口 */
internal fun RuleResult.firstText(): String = when (this) {
    RuleResult.Miss -> ""
    is RuleResult.Texts -> values.firstOrNull().orEmpty()
    is RuleResult.Nodes -> elements.firstOrNull()?.text().orEmpty()
    is RuleResult.Matches -> items.firstOrNull()?.firstOrNull().orEmpty()
    is RuleResult.Jsons -> items.firstOrNull()?.jsonText().orEmpty()
}

/**
 * 结果 → 文本列表（合并/交错/沙箱往返等多值场合的唯一实现）。
 *
 * [accessor] 为 null 时 [Nodes] 按纯 `text()` 取：合并与交错这类结构无关的场合要的就是节点自身文本。
 * 给出时按该取值器映射——沙箱里 `java.get(元素, rule, accessor)` 一类的调用由脚本指定取值器，
 * 拿 `text()` 顶替会把 `href`/属性取值静默换成文本。两个口径都收在这一处，避免各调用方各写一份
 * （历史上 `ScriptRuleEvaluator` 与 `JsCallbackProxy` 各有一份，Nodes 分支已经悄悄分了岔）。
 * [Matches] 取每条目的首个捕获组（下标 0 是整段匹配），[Jsons] 按 [jsonText] 落成文本。
 */
internal fun RuleResult.toTextList(accessor: AccessorKind? = null): List<String> = when (this) {
    RuleResult.Miss -> emptyList()
    is RuleResult.Texts -> values
    is RuleResult.Nodes -> if (accessor == null) elements.map { it.text() } else mapToTexts(accessor).values
    is RuleResult.Matches -> items.mapNotNull { it.firstOrNull() }
    is RuleResult.Jsons -> items.map { it.jsonText() }
}

/**
 * 节点集 → 文本集（规格 §3.2 取值器）。
 *
 * [attribute] 仅在 [accessor] 为 [AccessorKind.ATTRIBUTE] 时给出属性名。
 * 属性缺失的元素**跳过**而不是补空串：语料里 `@_src` 这类延迟加载属性只在部分标签上存在，
 * 补空串会让「这个元素没有该属性」变成一个看起来合法的候选值。
 */
internal fun RuleResult.Nodes.mapToTexts(accessor: AccessorKind, attribute: String = ""): RuleResult.Texts =
    RuleResult.Texts(
        when (accessor) {
            AccessorKind.TEXT -> elements.map { it.text() }
            AccessorKind.OWN_TEXT -> elements.map { it.ownText() }
            // textNodes 只取**命中元素自己**的直接文本子节点（不递归进子元素），语料形态
            // `@css:.articleDiv p@textNodes` 依赖这一点：先选中 p，再取 p 内被 <br> 切开的各段。
            // 丢掉纯空白节点是因为 Jsoup 会把源码里的换行与缩进原样留成文本节点，
            // 那些段落间距不是正文的一部分，留着会让「逐段拼接」的下游长出空行。
            AccessorKind.TEXT_NODES -> elements.flatMap { e -> e.textNodes().map { it.getWholeText().trim() }.filter { it.isNotEmpty() } }
            AccessorKind.HTML -> elements.map { it.html() }
            // all = 含自身标签的整个元素外形态（§3.2「整个元素（含自身标签）」）
            AccessorKind.ALL -> elements.map { it.outerHtml() }
            AccessorKind.HREF -> elements.attrNonNull("href")
            AccessorKind.SRC -> elements.attrNonNull("src")
            AccessorKind.ATTRIBUTE -> elements.attrNonNull(attribute)
        }
    )

/**
 * JSON 节点 → 文本（§3.2 取值语义在 JSON 上的对应物）。
 *
 * 字符串去引号取原值、数字/布尔取字面量；JSON null 字符串化成**空串**而不是字面 "null"
 * ——「这个键没值」与「值为字符串 null」在本格式里都不该产出 "null" 三个字符；
 * 对象/数组给紧凑 JSON 文本（kotlinx 的 `toString()`），供整块取出再处理的字段用。
 */
internal fun JsonElement.jsonText(): String = when (this) {
    is JsonNull -> ""
    is JsonPrimitive -> content
    else -> toString()
}

/** JSON 结果 → 文本结果（字段净化、单值收敛前的统一出口） */
internal fun RuleResult.Jsons.mapToTexts(): RuleResult.Texts = RuleResult.Texts(items.map { it.jsonText() })

/**
 * 节点集 → 该属性的非空值列表（`href`/`src` 与 §3.2 的「@任意属性名」共用这一条路）。
 *
 * Jsoup 对不存在的属性返回空串，故「没有该属性」与「属性值为空」在这里同形并一并被丢掉：
 * 两者都属「未取到值」，留着空串等于给下游一个看起来合法的假候选。
 */
private fun List<Element>.attrNonNull(name: String): List<String> =
    mapNotNull { it.attr(name).ifBlank { null } }
