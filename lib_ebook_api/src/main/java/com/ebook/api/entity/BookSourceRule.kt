package com.ebook.api.entity

import kotlinx.serialization.Serializable

/**
 * 书源规则配置（JSON 驱动）
 * 用 JSON 定义小说网站的解析规则，支持动态添加和切换书源
 * 目标：仅通过 JSON 配置即可适配新网站，无需修改代码
 */
@Serializable
data class BookSourceRule(
    /** 书源名称 */
    val name: String = "",
    /** 书源 URL */
    val url: String = "",
    /** 是否启用 */
    val enabled: Boolean = true,
    /** 书源分组（如：小说、漫画） */
    val group: String = "小说",
    /** 书源排序权重（数字越小越靠前） */
    val weight: Int = 0,
    /** 字符编码（默认 UTF-8） */
    val charset: String = "utf-8",
    /** 请求头配置 */
    val headers: Map<String, String> = emptyMap(),
    /** 请求方法（GET/POST） */
    val method: String = "GET",
    /** 请求体模板（POST 时使用，支持 {{keyword}} 等占位符） */
    val body: String = "",

    // ========== 搜索规则 ==========
    /** 搜索 URL 模板，支持 {{keyword}}、{{page}} 占位符 */
    val searchUrl: String = "",
    /** 搜索请求方法（覆盖 method） */
    val searchMethod: String = "",
    /** 搜索请求体（覆盖 body） */
    val searchBody: String = "",
    /** 搜索分页规则 */
    val searchPage: PageRule = PageRule(),
    /** 搜索结果规则 */
    val ruleSearch: SearchRule = SearchRule(),

    // ========== 书籍详情规则 ==========
    val ruleBookInfo: BookInfoRule = BookInfoRule(),

    // ========== 目录规则 ==========
    val ruleToc: TocRule = TocRule(),

    // ========== 正文规则 ==========
    val ruleContent: ContentRule = ContentRule(),

    // ========== 发现/分类规则 ==========
    val ruleFind: FindRule = FindRule(),

    // ========== 排行榜规则 ==========
    val ruleRank: RankRule = RankRule()
)

/**
 * 分页规则
 */
@Serializable
data class PageRule(
    /** 分页参数名（如：page、p） */
    val param: String = "page",
    /** 起始页码 */
    val start: Int = 1,
    /** 页码步长 */
    val step: Int = 1,
    /** 是否使用章节 URL 列表翻页（有些网站目录页有分页） */
    val tocPage: Boolean = false
)

/**
 * 搜索规则
 */
@Serializable
data class SearchRule(
    /** 搜索结果列表选择器 */
    val list: String = "",
    /** 书名选择器 */
    val name: String = "",
    /** 作者选择器 */
    val author: String = "",
    /** 分类/类型选择器 */
    val kind: String = "",
    /** 最新章节选择器 */
    val lastChapter: String = "",
    /** 封面 URL 选择器 */
    val coverUrl: String = "",
    /** 详情页 URL 选择器 */
    val bookUrl: String = "",
    /** 简介选择器 */
    val intro: String = ""
)

/**
 * 书籍详情规则
 */
@Serializable
data class BookInfoRule(
    /** 书名选择器 */
    val name: String = "",
    /** 作者选择器 */
    val author: String = "",
    /** 封面 URL 选择器 */
    val coverUrl: String = "",
    /** 简介选择器 */
    val intro: String = "",
    /** 分类/类型选择器 */
    val kind: String = "",
    /** 最新章节选择器 */
    val lastChapter: String = "",
    /** 目录页 URL 选择器（为空则使用详情页 URL） */
    val tocUrl: String = "",
    /** 作者文本前缀（用于去除前缀，如 "作者："） */
    val authorPrefix: String = "",
    /** 简介文本前缀 */
    val introPrefix: String = "",
    /** 是否反转章节顺序 */
    val reverseToc: Boolean = false
)

/**
 * 目录规则
 */
@Serializable
data class TocRule(
    /** 章节列表选择器（支持多级目录，用 || 分隔） */
    val list: String = "",
    /** 章节名选择器 */
    val name: String = "",
    /** 章节 URL 选择器 */
    val url: String = "",
    /** 章节列表页 URL 模板（支持 {{page}} 占位符，用于目录分页） */
    val pageUrl: String = "",
    /** 章节列表页下一页选择器 */
    val nextPage: String = "",
    /** 是否反转章节顺序 */
    val reverse: Boolean = false
)

/**
 * 正文规则
 */
@Serializable
data class ContentRule(
    /** 正文容器选择器（支持多页，用 || 分隔） */
    val content: String = "",
    /** 下一页 URL 选择器（为空则不分页） */
    val nextPage: String = "",
    /** 正文内容替换规则 */
    val replaceRules: List<ReplaceRule> = emptyList(),
    /** 正文图片选择器（有些网站正文是图片） */
    val image: String = ""
)

/**
 * 正文清理规则
 */
@Serializable
data class ReplaceRule(
    /** 匹配正则 */
    val pattern: String = "",
    /** 替换文本 */
    val replacement: String = "",
    /** 是否启用 */
    val enabled: Boolean = true
)

/**
 * 发现/分类规则
 */
@Serializable
data class FindRule(
    /** 发现页 URL 模板，支持 {{kind}}、{{page}} 占位符 */
    val url: String = "",
    /** 分类列表 */
    val kinds: List<KindItem> = emptyList(),
    /** 发现结果规则（复用搜索规则） */
    val ruleSearch: SearchRule = SearchRule()
)

/**
 * 分类项
 */
@Serializable
data class KindItem(
    /** 分类标题 */
    val title: String = "",
    /** 分类 URL 或标识（用于替换 {{kind}}） */
    val url: String = "",
    /** 子分类列表 */
    val children: List<KindItem> = emptyList()
)

/**
 * 排行榜规则
 */
@Serializable
data class RankRule(
    /** 排行榜 URL 模板，支持 {{page}} 占位符 */
    val url: String = "",
    /** 排行榜列表 */
    val ranks: List<RankItem> = emptyList(),
    /** 排行榜结果规则（复用搜索规则） */
    val ruleSearch: SearchRule = SearchRule()
)

/**
 * 排行榜项
 */
@Serializable
data class RankItem(
    /** 排行榜标题 */
    val title: String = "",
    /** 排行榜 URL */
    val url: String = ""
)
