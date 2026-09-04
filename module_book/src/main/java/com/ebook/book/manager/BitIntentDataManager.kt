package com.ebook.book.manager

import java.util.concurrent.atomic.AtomicLong

/**
 * Activity 间传递「带目录/书籍信息的书架实体」的进程内暂存区。
 *
 * **为什么不能直接走 Intent extra**：要传递的 [com.ebook.db.entity.BookShelfEntity] 虽带
 * `@Parcelize`，但其 `bookInfo`/`chapterList` 两个字段只标了 Room 的 `@Ignore`——该注解对
 * Parcelize 无效，序列化仍会带上整份章节列表。上千章的书目录过 Binder 极易撞
 * `TransactionTooLargeException`（Binder 缓冲约 1MB 且全进程共享），因此改用本暂存区。
 * 不要因为「实体是 Parcelable」就把它塞回 Intent——那正是本类存在的原因。
 *
 * 用法：写入方调 [putData] 取得 key，把 key 经 Intent 的 `data_key` 透传给消费方；
 * 消费方 [getData] 取实体后必须 [cleanData]。进程死亡时暂存内容随进程消失，故消费方不得
 * 依赖本暂存区跨进程存活，取不到数据时须自行收尾。当前两处兜底强度不同：`ReadBookActivity`
 * 判空即提示并 finish（key 本身为 null 的分支只记日志后 finish，不弹提示）；`BookDetailActivity`
 * 的书架入口分支取不到实体时静默跳过初始化（页面会停在无内容状态，尚无提示）。
 * key 为进程内 AtomicLong 自增，杜绝旧实现的 `System.currentTimeMillis()` 同毫秒碰撞；
 * 跳转被放弃（写入后未跳转/消费方未走到 onCreate）会留下未消费条目，但写入点都紧跟跳转、
 * 消费点在 onCreate，数量有界且随进程死亡清空，无泄漏风险。
 * 全部访问都在主线程（Compose 点击回调与 Activity onCreate），普通 HashMap 足够。
 */
object BitIntentDataManager {
    private val bigData = HashMap<String, Any?>()
    private val keySequence = AtomicLong(0)

    /** 存入实体，返回本次传递用的 key；调用方须把它放进 Intent 的 `data_key` */
    fun putData(data: Any?): String {
        val key = keySequence.incrementAndGet().toString()
        bigData[key] = data
        return key
    }

    /** 取实体；取到后调用方必须 [cleanData]，避免残留 */
    fun getData(key: String): Any? {
        return bigData[key]
    }

    fun cleanData(key: String) {
        bigData.remove(key)
    }
}
