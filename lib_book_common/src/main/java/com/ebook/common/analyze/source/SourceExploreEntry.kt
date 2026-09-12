package com.ebook.common.analyze.source

/**
 * 书城分类条目：[BookSourceManager.getExploreEntries] 的返回元素。
 *
 * 两种格式共用这一个形状——原生行来自 `ruleFind.kinds`，脚本行来自 `exploreUrl` 条目切分，
 * [url] 对两种出身都承载「分类地址/URL 规则串」，由对应解析器在 `getKindBook` 里渲染
 * （原生 `JsoupBookParser` 做 `{{kind}}` 替换与页码换算；脚本 `ScriptBookParser` 走脚本 URL 语义）。
 * 与 `module_find` 的 `BookType` 的分工：本类是 Manager 读面的契约类型，BookType 是页面 UI 模型
 * （「url 只对生成它的源有意义」的语义挂在那边）；空白标题的过滤归 UI 侧，本面原样递出。
 */
data class SourceExploreEntry(
    /** 分类标题（书城分类胶囊文案）。空白 = 书源规则漏写字段，调用方过滤 */
    val title: String,
    /** 分类地址：原生为 KindItem.url；脚本为 exploreUrl 条目的 URL 规则串（可含 {{page}}） */
    val url: String,
)
