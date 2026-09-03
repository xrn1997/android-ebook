package com.ebook.common.analyze.source

import com.ebook.api.entity.BookSourceRule

/**
 * 书源管理器接口
 * 负责书源规则的加载、切换、导入导出
 */
interface BookSourceManager {
    val currentSource: BookSourceRule?
    val currentParser: BookParser?

    fun getAllSources(): List<BookSourceRule>
    fun getEnabledSources(): List<BookSourceRule>

    fun switchSource(rule: BookSourceRule)
    fun importFromJson(jsonStr: String): BookSourceRule?
    fun exportToJson(rule: BookSourceRule): String
    fun saveCurrentSource(context: android.content.Context)
    fun requireParser(): BookParser
}
