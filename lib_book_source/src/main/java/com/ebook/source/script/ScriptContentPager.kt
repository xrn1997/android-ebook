package com.ebook.source.script

/**
 * 脚本书源的正文翻页链（§7.2/§7.3）。
 *
 * 三条不得违反的口径：
 * - **不套用 `ChapterPageMatcher`**（§7.3 明令）：`nextContentUrl` 是作者显式给出的下一页，
 *   可信度高于 URL 形状启发式，一过滤就永久漏页；防串章靠**回环访问集**（§7.2 本仓规定的
 *   防御件，与原生共享「已访问集合 + 页数上限」的防御思路、不共享判定函数）；
 * - 停止条件：空/`null`（§7.1 同判）、回环、[MAX_CONTENT_PAGES] 上限——**没有**「零新增即到底」
 *   （正文没有去重语义，空页不构成终止证据）；
 * - `replaceRegex` 跑在**拼接后的整章串**上（规则可能跨段，§1.6）。
 *
 * content 字段结果的收敛例外（登记 §11）：单值字段一律 `firstText`，但 content 是段落性字段
 * ——各形态的值集按 `\n` 连接（textNodes/多匹配的段落数据不许丢）。
 *
 * 实例非线程安全（Task 5 的 ScriptPageChain 同约定）：单次 collect 独占。
 */
internal class ScriptContentPager(
    private val extractor: ScriptFieldExtractor,
    private val rules: ScriptRuleSet,
    private val ctx: EvalContext,
    private val fetchPage: suspend (String) -> ScriptPage,
) {
    companion object {
        /** 单章正文页数上限，与 `JsoupSourceReader.MAX_CONTENT_PAGES` 同值同理由 */
        const val MAX_CONTENT_PAGES = 50
    }

    /**
     * 从正文入口 [entryRef]（= contentRef，可含选项尾段）逐页抓取并拼接整章文本。
     * 每取回一页把 `ctx.baseUrl` 推进到该页，让 `nextContentUrl` 结果里的相对链接按
     * 「写在哪页、就相对哪页」落位（§6.5）；页间以 `\n` 分段拼接。
     *
     * 返回前做一次 trim——**放在净化（replaceRegex）之后**：被删段落留下的边角换行与整章
     * 首尾空白不构成任何段落，trim 掉的正是净化残留；段落**之间**的空行留给读取层的
     * TextNormalizer 逐段清理，本层不碰（两层各清各的，混了就会出现「同一段落两种清洗语义」）。
     */
    suspend fun collect(entryRef: String): String {
        val contentRule = rules.rule(RuleObjectKind.CONTENT, "content").orEmpty()
        val nextRule = rules.rule(RuleObjectKind.CONTENT, "nextContentUrl").orEmpty()
        val replaceRegex = rules.rule(RuleObjectKind.CONTENT, "replaceRegex").orEmpty()
        val chain = ScriptPageChain(
            entryUrl = entryRef,
            nextRule = nextRule,
            nextArray = rules.nextUrlArray(RuleObjectKind.CONTENT, "nextContentUrl"),
            extractor = extractor,
        )
        val text = StringBuilder()
        var current: String? = entryRef
        while (current != null && chain.visitedCount() <= MAX_CONTENT_PAGES) {
            val page = fetchPage(current)
            ctx.baseUrl = page.url
            val input = RuleValue.Page(page.text)
            // content 是单值字段（§3.2）：走单值口径，裸词末段按 `@任意属性名` 取值器解，
            // 不与列表字段的节点选择器口径混用
            val chunk = contentToText(extractor.evaluateValue(contentRule, input))
            if (chunk.isNotBlank() && text.isNotEmpty()) text.append('\n')
            text.append(chunk)
            current = chain.nextOf(input, page.url)
        }
        // 净化后收边（trim 在 applyReplaceRegex 之后）：被删段落留下的边角换行与整章首尾空白
        // 不构成任何段落；段落之间的空行留给读取层的 TextNormalizer 逐段清理，本层不碰
        return applyReplaceRegex(text.toString(), replaceRegex).trim()
    }

    /** content 的收敛例外：全部值按 `\n` 连接（类 KDoc 第三条口径） */
    private fun contentToText(result: RuleResult): String = when (result) {
        RuleResult.Miss -> ""
        is RuleResult.Texts -> result.values.joinToString("\n")
        is RuleResult.Nodes -> result.elements.joinToString("\n") { it.text() }
        // Matches 只取每条目的 group 0（整段匹配文本），$1+ 捕获组静默丢弃：分组引用是字段
        // 提取语义（§2.4 的 `chapterName: "$2"` 用捕获组拼字段值），content 的段落性语义下
        // 分组没有明确所指，group 0 恰是段落全文。与 ReplacementApplier 对 Matches+替换段
        // 抛类型化异常的口径不同——那边压平丢结构是作者用法错误（条目×捕获组二维一旦压平，
        // 逐字段规则无从落地）；这边 content 的收敛本来就以段落为单元，取 group 0 是语义对齐。
        is RuleResult.Matches -> result.items.mapNotNull { it.firstOrNull() }.joinToString("\n")
        is RuleResult.Jsons -> result.items.joinToString("\n") { it.jsonText() }
    }

    /**
     * `replaceRegex` 以净化形态生效（§2.4）：值可能带或不带前导 `##`（独立使用等价 `all##…`），
     * 缺前缀补一个再交 [RegexReplacement.parse]；执行复用 [ReplacementApplier]（循环替换全部命中，
     * 与选择器侧的替换同一套引擎——两处各写一遍就会出现「同一条正则两种替换语义」）。
     */
    private fun applyReplaceRegex(content: String, replaceRegex: String): String {
        if (content.isEmpty() || replaceRegex.isBlank()) return content
        val normalized = if (replaceRegex.startsWith("##")) replaceRegex else "##$replaceRegex"
        // 归一化已保证 ## 前缀，此 null 路径当前不可达；留作 parse 未来扩展出 null 路径时的安全降级——按无净化处理而不是崩
        val parsed = RegexReplacement.parse(normalized) ?: return content
        val replaced = ReplacementApplier.apply(RuleResult.Texts(listOf(content)), parsed, replaceRegex)
        return (replaced as RuleResult.Texts).values.firstOrNull().orEmpty()
    }
}
