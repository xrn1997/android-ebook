package com.ebook.common.store

import com.ebook.common.analyze.local.ChapterContent
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 章节正文的内存缓存（spec §7）。
 *
 * 为什么必须有：`ReadBookActivity.loadPage` 是**每翻一页都取一次正文**。原先正文在
 * `book_content` 里，那只是一次主键查询；改成章文件后若无缓存，同一章翻 20 页就是
 * 20 次 open + read + 解码，性能不升反降。容量取 3（当前章 + 前后各一），正好覆盖
 * 预加载上一页/下一页。
 *
 * 键用 `content_ref` 而不是 (书, 章) 二元组：`content_ref` 本身是持久定位符且内含 bookId，
 * 于是 [invalidateBook] 按 `/<bookId>/` 片段剔除即可，不必再维护反向索引。
 *
 * 失效入口对应真实事件：重解析、删书、合并来源 → [invalidateBook]；改字号字体**不**失效
 * 本缓存（那只影响排版偏移，另在 Task 15 处理）。
 */
class ChapterContentCache(private val capacity: Int = DEFAULT_CAPACITY) {

    private val mutex = Mutex()

    // accessOrder = true 使 LinkedHashMap 按访问序排列，头部即最久未使用
    private val entries = object : LinkedHashMap<String, ChapterContent>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ChapterContent>): Boolean =
            size > capacity
    }

    /**
     * 命中即返回；未命中时执行 [loader] 并放入缓存。loader 在锁外跑，避免读盘阻塞其他章。
     *
     * loader 可返回 null（代表内容缺失/格式无对应 reader）——null **不**入缓存，下次再调会重新跑 loader，
     * 这样 reader 注册表补齐后能自然恢复，不需要显式失效。
     */
    suspend fun getOrLoad(contentRef: String, loader: suspend () -> ChapterContent?): ChapterContent? {
        mutex.withLock { entries[contentRef] }?.let { return it }
        val loaded = loader() ?: return null
        mutex.withLock { entries[contentRef] = loaded }
        return loaded
    }

    suspend fun invalidateBook(bookId: String) {
        val marker = "/$bookId/"
        mutex.withLock {
            entries.keys.filter { it.contains(marker) }.forEach { entries.remove(it) }
        }
    }

    suspend fun clear() {
        mutex.withLock { entries.clear() }
    }

    private companion object {
        /** 当前章 + 前后各一章 */
        const val DEFAULT_CAPACITY = 3
    }
}
