package com.ebook.find.mvvm.viewmodel

import com.ebook.db.entity.SearchBookEntity

/**
 * 把一页新结果并入已加载列表（分类页与搜索页共用）。
 *
 * 按 `noteUrl` 去重不是优化而是必需：
 * - 两个列表页的 `LazyColumn` 都以 `noteUrl` 作 item key，同一条目出现两次直接抛异常；
 * - 笔趣阁式站点对越界分类页返回 **HTTP 200 + 首页书目**（软 404：实测 `/xuanhuan/86` 与
 *   `/xuanhuan` 的书目完全一致），「返回空页 = 没有更多」这条守卫在分类链路上永远不成立，
 *   只会把同一页一路追加下去。
 *
 * @return 并入后的列表；本页没带来任何新条目时返回 null，调用方据此置「没有更多」
 */
internal fun mergeBookPage(
    current: List<SearchBookEntity>,
    incoming: List<SearchBookEntity>,
): List<SearchBookEntity>? {
    val loaded = current.mapTo(mutableSetOf()) { it.noteUrl }
    val fresh = incoming.filterNot { it.noteUrl in loaded }.distinctBy { it.noteUrl }
    return if (fresh.isEmpty()) null else current + fresh
}
