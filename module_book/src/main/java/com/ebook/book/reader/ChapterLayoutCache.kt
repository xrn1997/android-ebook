package com.ebook.book.reader

import java.util.Collections

/**
 * 缓存键：同一章 + 同一份正文 + 同一字号 + 同一正文宽度才算同一份排版结果。
 *
 * [contentLength] 是「章节重解析」这一失效条件的实现方式。网络书的 `content_ref` 就是章节
 * URL，「强制刷新缓存」重抓后 URL 不变、正文却换了；键里不带内容指纹的话，重抓完阅读器会把
 * **新正文**配上**旧行偏移**，表现为页数没变但内容接不上——与漏掉字号时同一类错乱，且更难
 * 定位（它只在走过一次强刷之后才出现）。用长度而不是全量哈希：本键每翻一页都要构造一次，
 * O(章长) 的哈希会把缓存省下的排版开销又还回去；长度不等必然重排，等长异文属极端巧合，
 * 且真发生时排版差异也只在字面替换处，不会像旧行偏移那样整页错位。
 *
 * 「改字号字体」这条失效条件由 [fontSizeSp]/[widthPx] 承担，与内容指纹同理——键不同即自动
 * 重算，不需要显式失效入口。
 */
data class ChapterLayoutKey(
    val contentRef: String,
    val contentLength: Int,
    val fontSizeSp: Float,
    val widthPx: Int,
)

/**
 * 「整章排版偏移」的内存缓存（spec §7）。
 *
 * `ReaderTypesetter.lineStartOffsets` 是 O(章长) 且**每页都调一次**——同章 N 页即 N 次整章
 * 重排，该方法的 KDoc 自己承认了这点。缓存后同章翻页只重排一次。
 *
 * 本缓存是 `ReadBookActivity` 的**实例字段**（非单例），生命周期与阅读器页面一致；失效全部
 * 由 [ChapterLayoutKey] 的构成承担（见其 KDoc），故不提供按书剔除的入口——页面级缓存没有
 * 跨页调用方，留一个无人调用的失效方法只会让人误以为重解析已由它兜住。
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

    fun clear() = entries.clear()

    private companion object {
        /** 约覆盖"当前章 + 前后各两章"的两屏余量 */
        const val DEFAULT_CAPACITY = 5
    }
}
