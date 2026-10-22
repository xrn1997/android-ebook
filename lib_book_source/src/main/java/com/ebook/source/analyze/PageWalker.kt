package com.ebook.source.analyze

/** 一页的地址与已取回的载荷（原生是 `Document`，脚本是 `ScriptPage`）。 */
data class WalkPage<P>(val url: String, val payload: P)

/** 走页结果：按出现顺序收集的条目，以及是否因页/条目上限被截断。 */
data class WalkResult<T>(val items: List<T>, val truncated: Boolean)

/**
 * 逐页走的共享机制：回环守卫、页数/条目上限、可选的「零新增即停」、可选的条目去重。
 *
 * 四条翻页链（原生/脚本 × 目录/正文）原先各写一份循环。它们真正不同的是
 * 「下一页怎么算」与「何时停」，不是循环本身；这里只收机制，策略仍由调用方以
 * [next] / [stopOnNoNewItems] 注入——尤其**不**把原生正文的 `ChapterPageMatcher`
 * 或脚本正文的 `ScriptPageChain` 合并进来（规格 §7.3 明令两者分道）。
 *
 * @param maxPages 页数上限；触顶且仍有下一页时 [WalkResult.truncated] 为 true
 * @param maxItems 条目上限；本页还有未处理条目时 [WalkResult.truncated] 为 true
 *   （恰好收满且本页已消费完不置位；是否还有下一页不去探测，避免推进调用方的页码状态）
 * @param stopOnNoNewItems true = 本页没有新增条目即停（目录的软 404 / 末页判据）
 * @param guardLoops true = 由本模块维护已访问地址集；脚本链自带回环守卫时传 false
 * @param keyOf 条目去重键；null 表示不去重（正文按页收集，没有条目主键）
 *
 * `items` 返回 [Sequence] 而不是 List：条目上限在消费时生效，单页解析出大量条目时
 * 不会先物化成整页 List 再截断。
 */
class PageWalker<T, P>(
    private val maxPages: Int = Int.MAX_VALUE,
    private val maxItems: Int = Int.MAX_VALUE,
    private val stopOnNoNewItems: Boolean = false,
    private val guardLoops: Boolean = true,
    private val keyOf: (T) -> String? = { null },
) {
    init {
        require(maxPages > 0) { "maxPages 必须为正：$maxPages" }
        require(maxItems > 0) { "maxItems 必须为正：$maxItems" }
    }

    suspend fun walk(
        entryUrl: String,
        fetch: suspend (String) -> WalkPage<P>,
        items: (WalkPage<P>) -> Sequence<T>,
        next: (WalkPage<P>) -> String?,
    ): WalkResult<T> {
        val collected = mutableListOf<T>()
        val seenKeys = mutableSetOf<String>()
        val visited = mutableSetOf(entryUrl)
        var current: String? = entryUrl
        var pages = 0
        var truncated = false

        while (current != null) {
            if (pages >= maxPages) {
                truncated = true
                break
            }
            val page = fetch(current)
            pages++
            var addedThisPage = 0
            for (item in items(page)) {
                if (collected.size >= maxItems) {
                    truncated = true
                    break
                }
                val key = keyOf(item)
                if (key != null && !seenKeys.add(key)) continue
                collected += item
                addedThisPage++
            }
            if (truncated) break
            // 恰好收满且本页已消费完不算截断；只有还有未处理条目时上面的分支才会置 truncated
            if (collected.size >= maxItems) break
            if (stopOnNoNewItems && addedThisPage == 0) break
            val nextUrl = next(page) ?: break
            if (guardLoops && !visited.add(nextUrl)) break
            current = nextUrl
        }
        return WalkResult(items = collected, truncated = truncated)
    }
}
