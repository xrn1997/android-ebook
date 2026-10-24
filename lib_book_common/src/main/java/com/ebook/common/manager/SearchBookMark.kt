package com.ebook.common.manager

import com.ebook.common.repository.BookShelfEvent
import com.ebook.db.entity.SearchBookEntity

/**
 * 把一个书架事件应用到搜索结果列表的「已加书架」标记。
 *
 * 原先 `SearchViewModel` 与 `ChoiceBookViewModel` 各写一份 `updateBookAddState`
 * （按 `noteUrl` 找下标、`copy(add = …)`、整表回写），两份逐字相同。这里只留一份纯函数：
 * 列表归调用方（它持有基类的 `list`），事件到标记的映射归此处。
 *
 * **无关事件返回同一实例**：调用方据 `list.value.applying(event) !== list.value` 判断要不要
 * `updateList`。新增/移除之外的事件每轮都会到（进度、目录），无条件回写会让列表在阅读时
 * 反复重组。命中与否先扫一遍再决定要不要建新表：每章都到的进度事件不该白配一个整表副本。
 *
 * 不做去重/排序：那是聚合搜索端的职责（`AggregationSession` 按 `noteUrl` 去重），
 * 这里只改标记，不动行的身份与顺序（同一 `noteUrl` 若有重复行，全部一起改）。
 */
fun List<SearchBookEntity>.applying(event: BookShelfEvent): List<SearchBookEntity> {
    val (book, add) = when (event) {
        is BookShelfEvent.Added -> event.bookShelf to true
        is BookShelfEvent.Removed -> event.bookShelf to false
        is BookShelfEvent.ProgressUpdated, is BookShelfEvent.ChaptersUpdated -> return this
    }
    if (indexOfFirst { it.noteUrl == book.noteUrl && it.add != add } == -1) return this
    return map { item ->
        if (item.noteUrl == book.noteUrl && item.add != add) item.copy(add = add) else item
    }
}
