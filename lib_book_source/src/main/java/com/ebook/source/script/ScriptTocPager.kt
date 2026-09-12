package com.ebook.source.script

import com.ebook.db.entity.ChapterListEntity
import com.xrn1997.common.util.Logger

/**
 * 脚本书源的目录翻页链（§7.1）。终止判据与上限策略**与原生 `TocPager` 同一套**（§12）：
 * contentRef 跨页去重、零新增即到底（软 404 与末页同判）、回环即停、[MAX_TOC_CHAPTERS]
 * 触顶按截断记日志不抛。
 *
 * 与原生 `TocPager` 的差异只有驱动方式：原生是 `nextPage` 选择器/`pageUrl` 模板，
 * 脚本是 `nextTocUrl` 的字符串规则/URL 数组（经 [ScriptPageChain]）。判定「这页属不属于
 * 目录」的依据两边各自独立，不得互相移植口径。
 *
 * 取文经 [fetchPage] 闭包注入：单测用假页表覆盖分页/终止/触顶各形态，无需 transport。
 */
internal class ScriptTocPager(
    private val extractor: ScriptFieldExtractor,
    private val rules: ScriptRuleSet,
    private val fetchPage: suspend (String) -> ScriptPage,
) {
    companion object {
        /** 目录防御上限（章），与原生 `TocPager.MAX_TOC_CHAPTERS` 同值同理由 */
        const val MAX_TOC_CHAPTERS = 20_000

        private const val TAG = "ScriptTocPager"
    }

    /**
     * 从目录入口 [entryUrl]（可含选项尾段，取文时由 URL 解析侧消费）开始逐页抓取章节，
     * 返回按出现顺序编号的完整章节列表。[noteUrl]/[tag] 逐章回填（与所在书、所属书源绑定）。
     * [ctx] 是本次解析任务的上下文：每取回一页就把 `baseUrl` 推进到该页，让下一页字段
     * 结果里的相对链接按「写在哪页、就相对哪页」落位（§6.5）。
     */
    suspend fun collect(
        entryUrl: String,
        ctx: EvalContext,
        noteUrl: String,
        tag: String,
    ): MutableList<ChapterListEntity> {
        val chapters = mutableListOf<ChapterListEntity>()
        // contentRef 跨页去重集：软 404（越界页以 HTTP 200 重复返回首页内容）靠它判零新增，
        // 同页重复条目顺带去重——与原生 TocPager 的 seenRefs 同一职责
        val seenRefs = mutableSetOf<String>()
        val chain = ScriptPageChain(
            entryUrl = entryUrl,
            nextRule = rules.rule(RuleObjectKind.TOC, "nextTocUrl").orEmpty(),
            nextArray = rules.nextUrlArray(RuleObjectKind.TOC, "nextTocUrl"),
            extractor = extractor,
        )
        var current: String? = entryUrl
        while (current != null) {
            val sizeBefore = chapters.size
            val page = fetchPage(current)
            ctx.baseUrl = page.url  // 字段相对落位推进到当前页（§6.5「链接写在哪页就相对哪页」）
            val input = RuleValue.Page(page.text)
            val items = extractor.listItems(
                extractor.evaluateListField(rules.rule(RuleObjectKind.TOC, "chapterList").orEmpty(), input)
            )
            for (item in items) {
                if (chapters.size >= MAX_TOC_CHAPTERS) break
                val urlRaw = extractor.fieldText(item, rules.rule(RuleObjectKind.TOC, "chapterUrl").orEmpty())
                if (urlRaw.isBlank()) continue
                val contentRef = extractor.resolveUrl(urlRaw, page.url)
                if (!seenRefs.add(contentRef)) continue  // 跨页去重：软 404 重复内容靠它判零新增
                chapters += ChapterListEntity(
                    noteUrl = noteUrl,
                    durChapterIndex = chapters.size,
                    contentRef = contentRef,   // 含选项尾段：正文取文时消费
                    durChapterName = extractor.fieldText(item, rules.rule(RuleObjectKind.TOC, "chapterName").orEmpty()),
                    tag = tag,
                )
            }
            if (chapters.size >= MAX_TOC_CHAPTERS) {
                Logger.w(TAG, "章节索引触及防御上限 $MAX_TOC_CHAPTERS 章，按截断处理: $entryUrl")
                break
            }
            // 零新增即到底——优先于翻页判定（§12：软 404 页与末页同判）
            current = if (chapters.size == sizeBefore) null else chain.nextOf(input, page.url)
        }
        return chapters
    }
}
