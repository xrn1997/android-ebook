package com.ebook.me.mvvm.viewmodel

import com.ebook.api.entity.BookSourceRule
import com.ebook.api.entity.SourceDefinition
import com.ebook.api.entity.SourceFormat
import com.ebook.common.analyze.source.BookSourceItem
import com.ebook.common.analyze.source.SourceCatalog
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * 设置页只读清单面用的最小假件。
 *
 * 书源管理页迁出后，module_me 只剩「入口行副标题显示几个源」这一处读依赖；完整的
 * 清单/写面/编解码假件已随页面与测试迁到 module_find。这里只实现设置页实际调用的一面，
 * 避免为一个数字拖进整套书源管理替身。
 */
internal class FakeSourceCatalog(
    initial: List<BookSourceRule> = emptyList(),
) : SourceCatalog {

    private val rows = LinkedHashMap<String, BookSourceRule>()

    /** 清单版本戳：本假件只读，保留单值流即可让两条订阅面各推一次 */
    private val tick = MutableStateFlow(0)

    init {
        initial.forEach { rows[it.url] = it }
    }

    private fun sortedRows(): List<BookSourceRule> =
        rows.values.sortedWith(compareBy({ it.weight }, { it.url }))

    private fun defaultRule(): BookSourceRule? =
        sortedRows().firstOrNull { it.enabled }

    override suspend fun getAllSources(): List<BookSourceRule> = sortedRows()

    override suspend fun getFormatByUrl(url: String): SourceFormat? =
        rows[url]?.let { SourceFormat.NATIVE }

    override fun observeSources(): Flow<List<BookSourceItem>> =
        tick.map { sortedRows().map { BookSourceItem(rule = it) } }

    override fun observeDefaultSource(): Flow<SourceDefinition?> =
        tick.map { defaultRule()?.let { SourceDefinition.Native(it) } }
}
