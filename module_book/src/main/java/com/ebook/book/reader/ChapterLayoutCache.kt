package com.ebook.book.reader

import java.util.Collections

/** 缓存键：同一章 + 同一字号 + 同一正文宽度才算同一份排版结果 */
data class ChapterLayoutKey(val contentRef: String, val fontSizeSp: Float, val widthPx: Int)

/**
 * 「整章排版偏移」的内存缓存（spec §7）。
 *
 * `ReaderTypesetter.lineStartOffsets` 是 O(章长) 且**每页都调一次**——同章 N 页即 N 次整章
 * 重排，该方法的 KDoc 自己承认了这点。缓存后同章翻页只重排一次。
 *
 * 键里必须带字号与宽度：漏掉就会在用户改字号后拿到旧偏移，表现为"页数没变但内容接不上"
 * 这种极难定位的错乱。
 *
 * 线程安全：[getOrCompute] 可能被并发调用（预加载上下页），底层 [LinkedHashMap] 在并发写
 * 下会结构损坏。这里用 [Collections.synchronizedMap] 包装，所有访问经同步 map 的锁保护。
 */
class ChapterLayoutCache(private val capacity: Int = DEFAULT_CAPACITY) {

    private val entries = Collections.synchronizedMap(
        object : LinkedHashMap<ChapterLayoutKey, List<Int>>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<ChapterLayoutKey, List<Int>>): Boolean =
                size > capacity
        }
    )

    fun getOrCompute(key: ChapterLayoutKey, computer: () -> List<Int>): List<Int> {
        // 必须在 synchronizedMap 的锁内完成 check-then-act：getOrPut 拆成 get + put 两步，
        // 不加锁会让并发预加载同时 miss、同时计算，缓存形同虚设
        synchronized(entries) {
            return entries.getOrPut(key) { computer() }
        }
    }

    fun invalidateBook(bookId: String) {
        val marker = "/$bookId/"
        entries.keys.filter { it.contentRef.contains(marker) }.toList().forEach { entries.remove(it) }
    }

    fun clear() = entries.clear()

    private companion object {
        /** 约覆盖"当前章 + 前后各两章"的两屏余量 */
        const val DEFAULT_CAPACITY = 5
    }
}
