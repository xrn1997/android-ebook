package com.ebook.source.script

import kotlinx.serialization.json.JsonElement

/**
 * 规则求值入口：把 2a 的切分树对着 HTML 或 JSON 文档跑出值（规格 §9 的 1~7 步）。
 *
 * 本类只负责**分发与组合**，各模式的取数在各自后端文件里。分工的理由与 2a 一致：
 * 「切分错了」与「求值错了」必须能在故障时各自定位。
 *
 * 尚未落地的能力一律抛类型化异常而不是返回空——空列表在消费链上的语义是
 * 「这个源没有结果」，用它冒充「本项目还不支持这种语法」就是规格 §12 最后一条禁止的事。
 *
 * 求值次序照 §9：**先展开 `{{}}`（第 1 步）、再切分（第 3 步）、再逐支求值（第 5 步）、
 * 最后应用 `##` 替换段（第 6 步）并回附 URL 选项尾段（第 7 步）**。展开产物以 [Expansion] 的
 * 形式一路带到使用点回填，而不是在切分前就地替换——理由见 [Interpolation] 的类 KDoc。
 */
internal class ScriptRuleEvaluator(private val ctx: EvalContext) {

    fun evaluate(rule: String, input: RuleValue, tail: ChainTail = ChainTail.AUTO): RuleResult {
        val (expansion, masked) = Interpolation.expand(rule, ctx) { inner ->
            // `@@<规则>` 的递归求值：内层是独立的一条规则，其文本里不含外层占位符，
            // 故用 [Expansion.EMPTY]；它自己若带 `{{}}` 会在本次 evaluate 里另行展开。
            // 内层恒按单值口径（AUTO）：列表口径只适用于列表字段的**整条**规则，
            // 嵌在里面的 `@@` 是「取一个 URL/一段文本」的单值语义（§3.2 缺省口径）
            val nested = RuleSplitter.parse(inner)
            evaluateNode(nested.root, input, Expansion.EMPTY, ChainTail.AUTO)
        }
        val parsed = RuleSplitter.parse(masked)
        val raw = evaluateNode(parsed.root, input, expansion, tail)
        // 替换段带原始规则串下去：非法正则要抛的类型化错误得说得出是哪条规则（§3.3）
        val replaced = ReplacementApplier.apply(raw, parsed.replacement, parsed.raw, expansion)
        // 结果侧兜底回填：展开值本身成为结果文本时也不许把占位符漏给用户
        val filled = expansion.fillResult(replaced)
        // §9 第 7 步：URL 类字段的结果统一带尾段，取文层据此解析选项——
        // 剥出是为了切分不被尾段干扰，回附是为了让「`##$##,{...}` 惯用法」与「规则级尾段」
        // 两种写法在取文层是同一形态（前者靠替换文本带出尾段，后者由 RuleSplitter 剥出再回附；
        // 两条路产出的都是「URL + 以逗号开头的尾段」，取文层只需一种认法）
        val optionTail = parsed.optionTail?.let { expansion.fill(it) }
        return if (optionTail == null) filled else filled.withOptionTail(optionTail)
    }

    /**
     * 逐字段求值：列表字段用正则 AllInOne 造出的**一个条目**（捕获组列表，下标 0 是整段匹配），
     * 按字段规则取值（规格 §2.4 的 `chapterName: "$2"` / `chapterUrl: "$1"`）。
     *
     * 组引用在这里就地取组、**不进链式后端**：`$2` 不是选择器，把它喂给链式后端会得到
     * 「按属性名为 `$2` 去筛元素」这种看着像回事、实则永远选不中的行为（§3.2 的兜底是
     * 「不在取值器表里的段按属性名处理」）。而组引用与「条目内的子规则」两类写法在书源里
     * 并存，故判定放在这一层：能解析成 `$n` 就取组，否则整条规则照常求值。
     *
     * 非组引用的字段规则以 [RuleValue.Texts] 为输入（内容为条目的整段匹配），链式后端会把
     * 它当 HTML 解析（§9 第 5 步「每支的输入 = 当前上下文文档/文本」）——无 DOM 的 TXT 流
     * 站点就是这么在一段文本里继续定位的。
     */
    fun evaluateOnItem(fieldRule: String, item: List<String>): RuleResult {
        if (GroupRef.parseOrNull(fieldRule) != null) return RuleResult.Texts(listOf(GroupRef.valueOf(fieldRule, item)))
        return evaluate(fieldRule, RuleValue.Texts(listOf(item.firstOrNull().orEmpty())), ChainTail.AUTO)
    }

    /**
     * 逐条目求值（JSON 版）：JSONPath 列表字段（如 `$.data.books`）解出的**一个条目**
     * 作为上下文，按子字段规则（`$.name`/`$.author`）取值。与 [evaluateOnItem] 对称——
     * 组引用是正则条目的专属取值形态，JSON 条目整条就是一条规则、不需要组引用判定。
     */
    fun evaluateOnJsonItem(fieldRule: String, item: JsonElement): RuleResult =
        evaluate(fieldRule, RuleValue.Json(item), ChainTail.AUTO)

    /**
     * `ruleBookInfo.init` 的 JS 分支（规格 §1.4）：脚本回传一个对象，字段名即键。
     *
     * 回 null 表示「init 不是 JS 形态」，调用方回落到 AllInOne 首个匹配的老路径——
     * 判定放在这里而不是解析器里，因为只有本层能安全地看出「这条规则是不是单个 JS 段」
     * （`RuleMode.of` 会被 `||`/`&&` 组合规则骗过去，而 `@js:a||b` 的 `||` 是脚本内容的一部分）。
     */
    fun evaluateInitObject(rule: String, input: RuleValue): Map<String, String>? {
        if (rule.isBlank()) return null
        val (expansion, masked) = Interpolation.expand(rule, ctx) { inner ->
            evaluateNode(RuleSplitter.parse(inner).root, input, Expansion.EMPTY, ChainTail.AUTO)
        }
        val leaf = RuleSplitter.parse(masked).root as? RuleNode.Leaf ?: return null
        if (leaf.mode != RuleMode.JS) return null
        val bridge = ctx.js ?: throw JsEvaluationPendingException(expansion.fill(leaf.body))
        return bridge.runInit(expansion.fill(leaf.body), input)
    }

    private fun evaluateNode(node: RuleNode, input: RuleValue, expansion: Expansion, tail: ChainTail): RuleResult =
        when (node) {
            RuleNode.Empty -> RuleResult.Miss
            is RuleNode.Leaf -> evaluateLeaf(node, input, expansion, tail)
            is RuleNode.AllOf -> mergeAllOf(node.parts, input, expansion, tail)
            is RuleNode.Percent -> interleave(node.streams, input, expansion, tail)
            is RuleNode.FirstOf -> firstOf(node.alternatives, input, expansion, tail)
        }

    /**
     * §2.2 `||`：以第一个取到值的分支为准（短路）。
     *
     * 语法错误按「未取到值」处理并继续下一支（§3.3），但**全部支都失败时把最后那个错误抛出**：
     * 只返回 Miss 会把「规则写错了」冒充成「这个源没有这条信息」，用户会被支去重导一条本来好的源。
     */
    private fun firstOf(
        alternatives: List<RuleNode>,
        input: RuleValue,
        expansion: Expansion,
        tail: ChainTail,
    ): RuleResult {
        var lastError: RuleSyntaxException? = null
        for (alt in alternatives) {
            val r = try {
                evaluateNode(alt, input, expansion, tail)
            } catch (e: RuleSyntaxException) {
                lastError = e
                continue
            }
            if (r !is RuleResult.Miss) return r
        }
        lastError?.let { throw it }
        return RuleResult.Miss
    }

    /** §2.2 `&&`：每支都求值后合并。空支跳过而不是带走整条。 */
    private fun mergeAllOf(
        parts: List<RuleNode>,
        input: RuleValue,
        expansion: Expansion,
        tail: ChainTail,
    ): RuleResult {
        val results = parts.map { evaluateNode(it, input, expansion, tail) }.filterNot { it is RuleResult.Miss }
        return when {
            results.isEmpty() -> RuleResult.Miss
            results.size == 1 -> results.first()
            // 节点与文本混着合并时统一成文本：`&&` 的典型用法是把两个字段值拼成一个串，
            // 而拼成节点集没有任何后续语义（再往下取什么？）
            results.all { it is RuleResult.Nodes } ->
                RuleResult.Nodes(results.flatMap { (it as RuleResult.Nodes).elements })
            results.all { it is RuleResult.Matches } ->
                RuleResult.Matches(results.flatMap { (it as RuleResult.Matches).items })
            // JSON 节点合并仍保留节点形态：条目还要当子字段规则的上下文，提前字符串化
            // 会把结构压扁（与 RuleResult.Jsons 的类 KDoc 同一口径）
            results.all { it is RuleResult.Jsons } ->
                RuleResult.Jsons(results.flatMap { (it as RuleResult.Jsons).items })
            // Texts 一律扁平合并成多值列表，**不**在这里拼接（§11-15）：合并层不替调用方收敛，
            // 「单值字段取第一个」由字段级的 firstText 一处收口。真语料金标准是判据：
            // 手机看书的 `kind = a.1@text&&span@textNodes` 两支各出一个值（分类「修真」与日期
            // 「2026-09-07」），站点要的是分类；在这里拼成单串会得到「修真2026-09-07」，
            // 而扁平合并 + firstText 得到「修真」——与 `ScriptRealSourceGoldenTest` 一致。
            else -> RuleResult.Texts(results.flatMap { it.toTextList() })
        }
    }

    /**
     * §2.2 `%%`：依次交错取数——三路时先取路 1 的第 1 个、路 2 的第 1 个、路 3 的第 1 个，
     * 再取路 1 的第 2 个……短的走完就不再补位。
     */
    private fun interleave(
        streams: List<RuleNode>,
        input: RuleValue,
        expansion: Expansion,
        tail: ChainTail,
    ): RuleResult {
        val results = streams.map { evaluateNode(it, input, expansion, tail) }.filterNot { it is RuleResult.Miss }
        if (results.isEmpty()) return RuleResult.Miss
        if (results.size == 1) return results.first()
        if (results.all { it is RuleResult.Nodes }) {
            val lists = results.map { (it as RuleResult.Nodes).elements }
            return RuleResult.Nodes(interleaved(lists))
        }
        // JSON 节点交错仍保留节点形态，理由同 mergeAllOf 的 Jsons 合并分支
        if (results.all { it is RuleResult.Jsons }) {
            return RuleResult.Jsons(interleaved(results.map { (it as RuleResult.Jsons).items }))
        }
        val texts = results.map { it.toTextList() }
        return RuleResult.Texts(interleaved(texts))
    }

    /** 按「轮次」把 N 路列表交错成一个：`[[a1,a2,a3],[b1]]` → `[a1,b1,a2,a3]` */
    private fun <T> interleaved(lists: List<List<T>>): List<T> {
        val out = ArrayList<T>(lists.sumOf { it.size })
        val max = lists.maxOf { it.size }
        for (i in 0 until max) {
            for (l in lists) if (i < l.size) out += l[i]
        }
        return out
    }

    /**
     * 单支的取数：每个模式的载荷由**该模式的执行点**先经 [Expansion.fill] 回填占位符
     * （§9 第 5 步的「每支求值」发生在第 1 步展开之后），本方法只做分发。
     *
     * 异常消息与变量分支的键在此就地回填：用户看到的是自己写的规则串，
     * 而不是带控制字符的中间态。
     */
    private fun evaluateLeaf(node: RuleNode.Leaf, input: RuleValue, expansion: Expansion, tail: ChainTail): RuleResult {
        // §2.1「@js: 只能放在其他规则的最后」：链式/CSS 规则的载荷里出现 `@js:` 时，
        // 前半段按原模式求值、结果交给后半段 JS 继续处理。
        // `flagOf` 已把「整条以 `@js:` 开头」的形态归为 JS 模式，这里命中的是
        // `tag.td.3@text@js:result+'字'` 这类「前链 + JS 后处理」的复合形态
        if ((node.mode == RuleMode.DEFAULT_CHAIN || node.mode == RuleMode.CSS) && !node.body.contains("<js>")) {
            val atJsIndex = findJsPostfix(node.body)
            if (atJsIndex >= 0) return runJsPostfix(node, atJsIndex, input, expansion, tail)
        }
        return when (node.mode) {
            // 没有桥 = 本机没有沙箱，原样抛待执行（消息与 2d 一致，现有锁形用例不受影响）；
            // 有桥 = 真跑，跑坏了由桥抛 JsExecutionFailedException，两种失败不混（关键事实 2）
            RuleMode.JS -> evaluateJsLeaf(node, input, expansion, tail)
            RuleMode.XPATH -> throw UnsupportedRuleFeatureException("XPath", expansion.fill(node.body))
            RuleMode.UNSUPPORTED -> throw UnsupportedRuleFeatureException("未知的规则标志", expansion.fill(node.body))
            RuleMode.JSON_PATH -> JsonPathBackend.evaluate(expansion.fill(node.body), input, node.reverse)
            RuleMode.REGEX_ALL_IN_ONE -> RegexBackend.evaluate(node.body, input, node.reverse, expansion)
            RuleMode.CSS -> ElementBackends.evaluateCss(node.body, input, node.reverse, expansion)
            RuleMode.DEFAULT_CHAIN -> ElementBackends.evaluateChain(node.body, input, node.reverse, expansion, tail)
            RuleMode.VARIABLE_PUT -> putVariables(expansion.fill(node.body), input, expansion)
            RuleMode.VARIABLE_GET ->
                ctx.variables[expansion.fill(node.body).trim()]?.let { RuleResult.Texts(listOf(it)) } ?: RuleResult.Miss
        }
    }

    /**
     * 在链式/CSS 载荷里找 `@js:` 后位次标（§2.1 的「放在其他规则的最后」）。
     *
     * 返回 -1 表示没有后位 `@js:`。链式语法没有引号/转义，`@js:` 不会出现在段内文本里，
     * 故直接 `lastIndexOf` 足够——不需要引号感知。
     */
    private fun findJsPostfix(body: String): Int = body.lastIndexOf("@js:").let { if (it > 0) it else -1 }

    /**
     * `@js:` 后位求值：前段按原模式（链式/CSS）求值 → 结果种子化 → JS 引擎跑后段代码。
     *
     * 反序标志辖整条规则的最终产物（与 [evaluateJsLeaf] 同口径）：前段不反序，
     * JS 完成值再交给后段（如果有后链的话——后位 `@js:` 已是末尾，不存在后链）。
     */
    private fun runJsPostfix(
        node: RuleNode.Leaf,
        atJsIndex: Int,
        input: RuleValue,
        expansion: Expansion,
        tail: ChainTail,
    ): RuleResult {
        val prefixBody = node.body.substring(0, atJsIndex).trim()
        val jsCode = expansion.fill(node.body.substring(atJsIndex + 4))
        val bridge = ctx.js ?: throw JsEvaluationPendingException(expansion.fill(node.body))
        // 前段按原模式求值：`@js:` 前的载荷仍是规则语法（含占位符），
        // evaluateChain/evaluateCss 内部会 Expansion.fill
        val prefixResult = when (node.mode) {
            RuleMode.CSS -> ElementBackends.evaluateCss(prefixBody, input, reverse = false, expansion = expansion)
            else -> ElementBackends.evaluateChain(prefixBody, input, reverse = false, expansion = expansion, tail = tail)
        }
        // 前段没取到值 → JS 拿到 undefined 必然炸（`result.match(...)` 之类），
        // 而 `JsExecutionFailedException` 不被 `firstOf` 捕获——会穿透整条 `||` 链。
        // 前段空结果（Miss / 空 Texts / 空 Nodes）都落成 `Texts(emptyList())`，
        // 直接回落让 `||` 试下一支——这类 `@js:` 后位的用法（翻页 URL 计算等）
        // 本就挂在 `||` 链里，前段没值意味着「这页没有该字段」
        val seed = prefixResult.toSeed()
        if (seed is RuleValue.Texts && seed.values.isEmpty()) return RuleResult.Miss
        // JS 可能用到桥接层尚未提供的绑定（如 `src` = 页面原文，当前为 null）：
        // 这类 `@js:` 在旧解析器里是死代码（链式后端不认识 `@js:` 分隔符），
        // 现在真正跑起来会因 `src.match(...)` 之类的调用抛运行时异常。
        // 捕获后回落 Miss，让 `||` 链照常短路到下一支——与旧行为等价
        val out = try {
            bridge.runSegment(jsCode, seed)
        } catch (e: JsExecutionFailedException) {
            return RuleResult.Miss
        }
        return if (node.reverse) out.reversedResult() else out
    }

    /**
     * JS 叶子（规格 §2.1）的三种形态：
     *
     * - `@js:` 前缀的**纯 JS**：整段进内核，`result` 绑定 = 本次输入；
     * - 内联 `<js>` 段是**分隔符**——`前链<js>代码</js>后链`：前链产物作为代码里的 `result`
     *   绑定种子，完成值再交给后链（规格实证形态 `tag.li<js></js>//a`）；
     * - `<js>` 在**串首且无闭合**：它是「整条规则写成 JS」的标记而非分隔符（语料 URL 位 39 条），
     *   代码取到串尾、后链为空，与 `@js:` 同义。
     *
     * 为什么不能整串进内核：`前链<js>…</js>` 的前链文本是规则语法不是 JS，整串送进去
     * 只会得到一句 `expecting ';'`——真语料「全本小说」的 coverUrl
     * （`tag.td.0@tag.a@href<js>…</js>`）当场炸出来的缺口（2026-09-11 语料回放）。
     *
     * 后链把 JS 产物的**首段文本当 HTML 文档**跑链式（JS 的完成值在这类用法里就是
     * 一段页面/片段；多段取首段）。反序标志仍辖整条规则的最终产物，前链是种子不参与反序。
     */
    private fun evaluateJsLeaf(node: RuleNode.Leaf, input: RuleValue, expansion: Expansion, tail: ChainTail): RuleResult {
        val body = node.body
        val bridge = ctx.js ?: throw JsEvaluationPendingException(expansion.fill(body))
        val out = if (!body.contains("<js>")) {
            bridge.runSegment(expansion.fill(body), input)
        } else {
            // 先切分、各段各自展开：evaluateChain 内部还要 fill 一次，先展开整串会双重展开
            val start = body.indexOf("<js>")
            val end = body.indexOf("</js>", start)
            // 只有开标记、且它前面还有前链：内核拿到的前链文本已经注定解不了，按语法错就地拒绝。
            // 开标记就在串首时不算这种形态——「`<js>` 当整条规则的标记、不写闭合」是语料 URL 位
            // 的实证形态（39 条），此时代码取到串尾、后链为空，与 `@js:` 同义。
            if (end < 0 && start > 0) throw RuleSyntaxException(expansion.fill(body))
            val prefix = expansion.fill(body.take(start)).trim()
            val code = expansion.fill(body.substring(start + 4, if (end >= 0) end else body.length))
            val suffix = if (end < 0) "" else expansion.fill(body.substring(end + 5)).trim()
            // 前链没取到值 → result 绑 undefined（垫片把缺失落成 undefined，脚本自己判空），
            // 而不是 Miss 掉整条：JS 里常见 `result || 兜底` 的写法要能走到
            val seed = if (prefix.isEmpty()) input
                else ElementBackends.evaluateChain(prefix, input, reverse = false, expansion = expansion, tail = tail)
                    .toSeed()
            val jsOut = bridge.runSegment(code, seed)
            if (suffix.isEmpty()) jsOut
                else ElementBackends.evaluateChain(suffix, jsOut.toPageSeed(), reverse = false, expansion = expansion, tail = tail)
        }
        return if (node.reverse) out.reversedResult() else out
    }

    /** 前链产物 → JS 的 `result` 种子（`SandboxScriptJs.textOf` 的同款口径，缺失落成 undefined） */
    private fun RuleResult.toSeed(): RuleValue = when (this) {
        RuleResult.Miss -> RuleValue.Texts(emptyList())
        is RuleResult.Texts -> RuleValue.Texts(values)
        is RuleResult.Nodes -> RuleValue.Nodes(elements)
        is RuleResult.Matches -> RuleValue.Texts(items.map { it.joinToString("") })
        is RuleResult.Jsons -> items.firstOrNull()?.let { RuleValue.Json(it) } ?: RuleValue.Texts(emptyList())
    }

    private fun RuleResult.toPageSeed(): RuleValue =
        (this as? RuleResult.Texts)?.values?.firstOrNull()?.let { RuleValue.Page(it) } ?: RuleValue.Texts(emptyList())

    /**
     * `@put:{键:"规则", …}`（规格 §5.2）：逐条求值写入 [EvalContext.variables]，并按序生效
     * （同名后写覆盖先写）。返回值是各条写入的**值**——它常挂在 `&&` 里当一个字段用，
     * 返回 Miss 会被下游当成「没取到值」。
     *
     * 全部条目都不可解析时抛 [RuleSyntaxException]：静默不写会让后续 `@get:` 恒 Miss，
     * 而 Miss 在 `||` 里是「这一支没值」，用户看到的是「兜底支也没解出来」——
     * 根因（这条 `@put:` 写坏了）完全不可见。
     *
     * 值的规则以**同一次求值的输入**求值（`@put:{link:"tag.a@href"}` 里的链式规则跑在当前文档上），
     * 且写进变量表的是 `firstText()` 收敛后的单值——变量表是 `String` 到 `String`，多匹配无从存放。
     */
    private fun putVariables(body: String, input: RuleValue, expansion: Expansion): RuleResult {
        val inner = body.trim().removePrefix("{").removeSuffix("}")
        val written = Interpolation.topLevelEntries(inner).mapNotNull { entry ->
            Interpolation.parsePutEntry(entry)?.let { (key, ruleString) ->
                val nested = RuleSplitter.parse(ruleString)
                key to evaluateNode(nested.root, input, expansion, ChainTail.AUTO).firstText()
            }
        }
        if (written.isEmpty()) throw RuleSyntaxException(body)
        written.forEach { (key, value) -> ctx.variables[key] = value }
        return RuleResult.Texts(written.map { it.second })
    }

    /**
     * 选项尾段只附在文本结果上：Miss 没有可请求的 URL，节点/条目不是「单一 URL」形态。
     * JSON 结果直挂尾段（`$.url,{...}`）在此被静默放过——§6.1 声明这是用法错误，
     * JSON 源的 URL 字段应把尾段写进替换文本（`##$##,{...}` 惯用法），随替换落成 Texts。
     */
    private fun RuleResult.withOptionTail(tail: String): RuleResult = when (this) {
        is RuleResult.Texts -> RuleResult.Texts(values.map { it + tail })
        else -> this
    }

    /** §2.5 反序：JS 后端的产出已是文本/结构，整体倒转就是它能表达的倒序 */
    private fun RuleResult.reversedResult(): RuleResult = when (this) {
        is RuleResult.Texts -> RuleResult.Texts(values.reversed())
        is RuleResult.Nodes -> RuleResult.Nodes(elements.reversed())
        is RuleResult.Matches -> RuleResult.Matches(items.reversed())
        is RuleResult.Jsons -> RuleResult.Jsons(items.reversed())
        RuleResult.Miss -> this
    }
}
