package com.ebook.source.script

/**
 * 翻页计划器：`nextTocUrl`/`nextContentUrl` 共用的「字符串规则 × URL 数组 × 回环防护」取下一页逻辑
 * （§7.1/§7.2 两种形态同一套口径）。收成一处是因为目录与正文各自的判定一旦漂移，
 * 「同一个 nextTocUrl 写法两种翻法」这类错乱无从定位（与 `ChapterPageMatcher` 收口同一理由）。
 *
 * 两种形态：
 * - **数组形态**（字段值是 JSON 数组）：固定页序一次给出——逐个跳过已访问元素（入口常是
 *   数组首元素，跳过而不是终止），耗尽即停；
 * - **字符串规则形态**：每页求值一次，空/`null` 即停（§7.1 停止条件），候选已访问（回环）即停。
 *
 * 访问键**剥掉选项尾段**再比：同一地址带不同选项（`X` 与 `X,{"charset":"gbk"}`）是同一页，
 * 不剥会让数组元素「看似没访问过」而重复请求、零新增又把链掐断。
 *
 * 实例非线程安全（mutable visited/arrayIndex），生命周期 = 单次 collect——每条翻页链
 * 各建一个实例，不跨协程共享。
 */
internal class ScriptPageChain(
    entryUrl: String,
    private val nextRule: String,
    private val nextArray: List<String>,
    private val extractor: ScriptFieldExtractor,
) {
    private val visited = mutableSetOf(visitKeyOf(entryUrl))
    private var arrayIndex = 0

    /** 已访问页数（含入口）：调用方的页数上限判定依据（正文链的 `ScriptContentPager.MAX_CONTENT_PAGES`，Task 6） */
    fun visitedCount(): Int = visited.size

    /**
     * 产出下一个未访问页地址；null = 链终止（无候选 / 回环 / 数组耗尽）。
     * [input] 是当前页的求值输入（字符串规则形态在当前页上求值，§7.1「输入是本页文档」）；
     * [pageUrl] 是当前页地址（相对链接的落位基准，§6.5「链接写在哪页就相对哪页」）。
     */
    fun nextOf(input: RuleValue, pageUrl: String): String? {
        val candidate: String? = when {
            // 数组形态优先：装载层把数组形 nextTocUrl 记为非常规字段、不进规则表，
            // 两种形态理论上互斥；并存时数组是站点实况、规则只是推算，与原生 nextPage/pageUrl 同序
            nextArray.isNotEmpty() -> {
                while (arrayIndex < nextArray.size && visitKeyOf(nextArray[arrayIndex]) in visited) arrayIndex++
                nextArray.getOrNull(arrayIndex++)?.also { visited += visitKeyOf(it) }
            }
            nextRule.isNotBlank() -> {
                // 翻页字段（nextTocUrl/nextContentUrl）是单值字段（§3.2）：走单值口径——
                // 裸词末段按取值器解，取不到即 Miss（不会把链接文本当下一页地址）
                val raw = extractor.evaluateValue(nextRule, input).firstText()
                if (raw.isBlank()) null
                else extractor.resolveUrl(raw, pageUrl).takeIf { visited.add(visitKeyOf(it)) }
            }
            else -> null
        }
        return candidate
    }

    private fun visitKeyOf(url: String): String = ScriptUrlOption.splitTail(url)?.first ?: url
}
