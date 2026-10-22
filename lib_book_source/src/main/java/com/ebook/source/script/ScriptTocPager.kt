package com.ebook.source.script

import com.ebook.db.entity.ChapterListEntity
import com.ebook.source.analyze.PageWalker
import com.ebook.source.analyze.WalkPage
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
        val chain = ScriptPageChain(
            entryUrl = entryUrl,
            nextRule = rules.rule(RuleObjectKind.TOC, "nextTocUrl").orEmpty(),
            nextArray = rules.nextUrlArray(RuleObjectKind.TOC, "nextTocUrl"),
            extractor = extractor,
        )
        val result = PageWalker<ChapterListEntity, ScriptPage>(
            maxItems = MAX_TOC_CHAPTERS,
            stopOnNoNewItems = true,
            // 回环与数组跳过由 ScriptPageChain 负责；walker 不再叠第二套 visited
            guardLoops = false,
            keyOf = { it.contentRef },
        ).walk(
            entryUrl = entryUrl,
            fetch = { url -> WalkPage(url, fetchPage(url)) },
            items = { page ->
                ctx.baseUrl = page.payload.url  // 字段相对落位推进到当前页（§6.5）
                val input = RuleValue.Page(page.payload.text)
                extractor.listItems(
                    extractor.evaluateListField(rules.rule(RuleObjectKind.TOC, "chapterList").orEmpty(), input)
                ).asSequence().mapNotNull { item ->
                    val urlRaw = extractor.fieldText(item, rules.rule(RuleObjectKind.TOC, "chapterUrl").orEmpty())
                    if (urlRaw.isBlank()) {
                        null
                    } else {
                        ChapterListEntity(
                            noteUrl = noteUrl,
                            // 全局序号在整条链走完后统一回填，不能按页内下标写
                            durChapterIndex = 0,
                            contentRef = extractor.resolveUrl(urlRaw, page.payload.url),
                            durChapterName = extractor.fieldText(
                                item,
                                rules.rule(RuleObjectKind.TOC, "chapterName").orEmpty(),
                            ),
                            tag = tag,
                        )
                    }
                }
            },
            next = { page ->
                chain.nextOf(RuleValue.Page(page.payload.text), page.payload.url)
            },
        )
        // 恰好收满且本页再无条目时 walker 不置 truncated；上限告警仍按收满即报（与原循环一致）
        if (result.truncated || result.items.size >= MAX_TOC_CHAPTERS) {
            Logger.w(TAG, "章节索引触及防御上限 $MAX_TOC_CHAPTERS 章，按截断处理: $entryUrl")
        }
        result.items.forEachIndexed { index, chapter -> chapter.durChapterIndex = index }
        return result.items.toMutableList()
    }
}
