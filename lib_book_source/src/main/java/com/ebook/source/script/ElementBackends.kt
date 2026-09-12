package com.ebook.source.script

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.select.Selector.SelectorParseException

/**
 * 默认（链式）模式与 `@css:` 模式两个后端（规格 §2.5、§3.2）。
 *
 * 两个模式共用「起步节点集 → 选下一批 → 按索引裁剪 → 反序 → 末段取值器出文本」这套骨架，
 * 因此放在同一文件：分开写会让裁剪与取值器映射长出两份，而 §4 的口径（闭区间、负索引从尾数、
 * 越界一律不抛）改一处漏一处的症状是「同一规则串两种解法」。差别只在一步「怎么选下一批节点」——
 * 链式按 `@` 逐级收窄、CSS 把整段选择器交给 Jsoup。
 *
 * 裁剪只经 [selectIndices]（`IndexSelector` 一侧的唯一入口），本文件不自己算区间与负数。
 */
internal object ElementBackends {

    /**
     * 链式：`@` 分段，逐段收窄当前节点集（§2.5）。
     *
     * 末段是取值器还是选择器决定整体出什么形态：取值器（§3.2 把「@任意属性名」与 text/html
     * 列在同一张表里，故属性名同样是取值器）把节点集映射成文本集；选择器就停在节点集上——
     * 列表字段要的是节点，逐字段规则再以之为输入（§9 第 5 步）。
     *
     * [expansion] 在**使用载荷之前**回填：选择器名称里可能有 `{{}}`（`class.{{cls}}@text`），
     * 带着占位符去选类名不会报错，只会永远选不中。
     */
    fun evaluateChain(
        body: String,
        input: RuleValue,
        reverse: Boolean,
        expansion: Expansion,
        tail: ChainTail = ChainTail.AUTO,
    ): RuleResult {
        val filled = expansion.fill(body)
        val links = ChainLink.parseChain(filled, tail)
        if (links.isEmpty()) return RuleResult.Miss
        // 局部名避开新参 `tail`（同名会触发 NAME_SHADOWING 警告）；语义仍是「链的最后一段」
        val lastLink = links.last()
        val valueTail = lastLink.kind == ChainKind.ACCESSOR || lastLink.kind == ChainKind.ATTRIBUTE
        val selectorLinks = if (valueTail) links.dropLast(1) else links
        requireAccessorOnlyAtTail(selectorLinks, filled)
        var current = seed(input, filled)
        for (link in selectorLinks) {
            current = apply(link, current, filled)
            // 中途选空即「未取到值」（§3.3）。继续往下走只会得到空集映射出的空文本，
            // 而 Texts(空) 在 `||` 看来是「有值」，短路语义就反了。
            if (current.isEmpty()) return RuleResult.Miss
        }
        // 反序作用在「已选中并裁剪完的节点列表」上、取值器之前：§2.5 的 `-` 是「取列表」这一步的标志，
        // 先反序再出文本，`[0:1]` 之类裁剪的才是原始顺序（§11-14 的「按支各自」口径与此一致）
        if (reverse) current = current.reversed()
        return if (valueTail) valueText(current, AccessorKind.of(lastLink.name), lastLink.name)
        else RuleResult.Nodes(current)
    }

    /**
     * `@css:`：整段选择器交给 Jsoup，尾随取值器决定怎么出值（§2.1/§3.2）。
     *
     * 选择器内部合法地含 `@`（属性值里的邮箱等），故切分交给 [SelectorAndAccessor]——
     * 它只按最后一个顶层 `@` 切，把左边原样交给 Jsoup，本层不猜 CSS 语法。
     *
     * [expansion] 同 [evaluateChain]：选择器与取值器都可能带 `{{}}` 插值，使用前先回填。
     */
    fun evaluateCss(body: String, input: RuleValue, reverse: Boolean, expansion: Expansion): RuleResult {
        val filled = expansion.fill(body)
        val (selector, accessorToken) = SelectorAndAccessor.split(filled)
        if (selector.isBlank()) return RuleResult.Miss
        var current = seed(input, filled).flatMap { selectIn(it, selector, filled) }.distinct()
        if (current.isEmpty()) return RuleResult.Miss
        if (reverse) current = current.reversed()
        val nodes = RuleResult.Nodes(current)
        return if (accessorToken == null) nodes
        else valueText(current, AccessorKind.of(accessorToken), accessorToken)
    }

    /**
     * 末段取值器：节点集映射成文本集，**映射后为空即「未取到值」**（§3.3）。
     *
     * 空映射与「选择器选不到节点」是同一件事：§3.2 要求属性缺失的元素跳过，语料里 `@_src` 这类
     * 懒加载属性又只在部分标签上存在，全缺失时 [RuleResult.Nodes.mapToTexts] 产出的是 `Texts(空)`
     * 而不是 [RuleResult.Miss]。留在 `Texts(空)` 会让 `firstOf` 判成「有值」——`A@_src||A@src`
     * 这种兜底写法的第二支永远不执行，症状是封面取到空串且零报错，正是 §12 要防的静默解错。
     * `textNodes` 取值器下全空白节点被过滤后同理。
     *
     * 同文件的选择器选空（[evaluateChain] 循环内与 [evaluateCss] 起步）早已出口成 [RuleResult.Miss]，
     * 取值器尾段是同一口径的最后一处，必须一起收口。
     *
     * 收口放在这里而不是 [RuleResult.Nodes.mapToTexts]：那个函数是「节点→文本」的纯映射，
     * 而 `RuleResultTest.Miss 是独立事实而不是空列表` 明确钉住「零节点映射出的空 Texts」与
     * 「Miss」不是同一个值——把归一塞进映射函数会同时推翻那条用例。
     */
    private fun valueText(nodes: List<Element>, accessor: AccessorKind, token: String): RuleResult {
        val texts = RuleResult.Nodes(nodes).mapToTexts(accessor, token)
        return if (texts.values.isEmpty()) RuleResult.Miss else texts
    }

    /**
     * 起步节点集：页面→文档根；节点集→原样；文本集→把第一段文本当 HTML 解析（§9 第 5 步
     * 「每支的输入 = 当前上下文文档/文本」）。
     *
     * 文本集走「解析」而不是「当纯文本」：AllInOne 造出的条目是整段匹配文本，逐字段规则
     * 仍可能要在其中定位标签（`evaluateOnItem` 对非组引用的字段规则就是这么走的）。
     *
     * JSON 文档不在 HTML 后端的语义域内（2c 起可达：混合组合符里 HTML 支对 JSON 上下文），
     * 类型化拒绝而不是静默空结果——拿空结果冒充「这个源没有这条信息」是 §12 禁止的事。
     */
    private fun seed(input: RuleValue, body: String): List<Element> = when (input) {
        is RuleValue.Page -> listOf(Jsoup.parse(input.source))
        is RuleValue.Nodes -> input.elements
        is RuleValue.Texts -> listOf(Jsoup.parse(input.values.firstOrNull().orEmpty()))
        is RuleValue.Json -> throw UnsupportedRuleFeatureException("HTML 选择器作用于 JSON 上下文", body)
    }

    /** 链式的一段作用在当前节点集上，产出下一批节点（索引裁剪统一走 [selectIndices]） */
    private fun apply(link: ChainLink, from: List<Element>, body: String): List<Element> {
        val picked = when (link.kind) {
            ChainKind.CLASS -> {
                requireSelectorName(link, body)
                from.flatMap { it.getElementsByClass(link.name) }
            }
            ChainKind.ID -> {
                requireSelectorName(link, body)
                from.mapNotNull { it.getElementById(link.name) }
            }
            ChainKind.TAG -> {
                requireSelectorName(link, body)
                from.flatMap { it.getElementsByTag(link.name) }
            }
            ChainKind.TEXT -> {
                requireSelectorName(link, body)
                locateByText(from, link.name)
            }
            // children 不需要名称，也不接受名称（§2.5）；空串段（`head@.1` 的那种段首索引）也落到这里
            ChainKind.CHILDREN -> from.flatMap { it.children() }
            // 空段=恒等：当前节点集原样传给下一步（§2.5「leading @ 作用于当前节点」）
            ChainKind.IDENTITY -> from
            // 裸 CSS 选择器段（§2.5）：整段交给 Jsoup.select，复用 selectIn 的类型化语法错误包装
            ChainKind.CSS -> {
                requireSelectorName(link, body)
                from.flatMap { selectIn(it, link.name, body) }
            }
            ChainKind.ATTRIBUTE -> from.flatMap { it.getAllElements() }.filter { it.hasAttr(link.name) }
            // 走到这里说明取值器不在链尾，上面的 [requireAccessorOnlyAtTail] 已经把它判成语法错误；
            // 留着这一支只为 when 穷尽，不参与正常求值
            ChainKind.ACCESSOR -> from
        }
        // 去重：seed 里的节点可能互为祖先，逐根遍历子树会把同一节点命中多遍。
        // 重复必须在裁剪之前消掉，否则 `[0]` 取到的不是「第一个」而是「第一个两次」，
        // 列表字段还会把同一本书解成两条。
        return link.index?.let { picked.distinct().selectIndices(it) } ?: picked.distinct()
    }

    /**
     * `text.名称` 按文本内容定位（§2.5）：名称是文本的**一部分**，不是全等。
     *
     * 只留「自己子孙里没有更深的命中者」的那些元素。外层元素的 `text()` 天然涵盖子孙文本，
     * 一并返回会让 `text.第一章` 命中 html/body/div/a 四个节点——而「按文本内容定位」要的是
     * 承载这段文本的那个元素。不收窄的后果不是报错，是把整页文本当成一条候选喂给下游。
     */
    private fun locateByText(roots: List<Element>, needle: String): List<Element> =
        roots.flatMap { root -> root.getAllElements().filter { it !== root && it.text().contains(needle) } }
            .filterNot { hit -> hit.getAllElements().any { it !== hit && it.text().contains(needle) } }
            .distinct()

    /**
     * §2.5「最后一段是取值器」：取值器已经把节点变成文本，其后的段没有任何东西可作用。
     * 静默放过得到的是「永远选不中」的空结果，正是本层要防的零报错故障，故判语法错误
     * （§3.3：类型化错误，可被 `||` 当作未取到值继续下一支）。
     *
     * 词法层只在**末段**才产出 [ChainKind.ACCESSOR]（见 `ChainLink.link` 的 isLast 判定），
     * 所以出现在中间的取值器会以另外两种形态漏到这里：`text` 既是取值器又是类型关键字
     * （中间位置解成名称为空的 TEXT 段），其余六个取值器不在类型表里（中间位置解成 ATTRIBUTE 段）。
     * 三种形态在本层一并认出，而不是回头改 2a 的段结构——模式判定表是词法层的契约，
     * 为求值层的便利去动它会让 `-`/`@@`/`:` 的处理长出特例。
     */
    private fun requireAccessorOnlyAtTail(links: List<ChainLink>, body: String) {
        links.forEach {
            val isAccessor = it.kind == ChainKind.ACCESSOR ||
                (it.kind == ChainKind.TEXT && it.name.isBlank()) ||
                (it.kind == ChainKind.ATTRIBUTE && AccessorKind.of(it.name) != AccessorKind.ATTRIBUTE)
            if (isAccessor) throw RuleSyntaxException(body)
        }
    }

    /**
     * 选择器名称为空（`class.@text`、`id.@text`、`text.@x`）没有可匹配的对象。
     *
     * Jsoup 对空串直接抛 `IllegalArgumentException`（`Validate.notEmpty`），那是**未类型化**的崩溃：
     * [ScriptRuleEvaluator] 的 `||` 只把 [RuleSyntaxException] 当未取到值，穿透出去会让一整条
     * 带兜底的规则作废（书源作者写的 `A||B` 直接崩，而不是走 B）。故在此换成类型化语法错误。
     */
    private fun requireSelectorName(link: ChainLink, body: String) {
        if (link.name.isBlank()) throw RuleSyntaxException(body)
    }

    /**
     * CSS 的一步选择。Jsoup 对非法选择器抛 [SelectorParseException]（及 `Validate` 的
     * IllegalArgumentException），一律换成 [RuleSyntaxException]：§3.3 要求 CSS 语法错误给出
     * 类型化错误参与 `||` 短路；原样抛出是未类型化崩溃，吞成 Miss 则是拿「这个源没有这条信息」
     * 冒充「规则写错了」。
     */
    private fun selectIn(root: Element, selector: String, body: String): List<Element> = try {
        root.select(selector)
    } catch (e: SelectorParseException) {
        throw RuleSyntaxException(body)
    } catch (e: IllegalArgumentException) {
        throw RuleSyntaxException(body)
    }
}
