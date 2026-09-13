package com.ebook.book.reader

import com.ebook.db.event.DBCode
import kotlinx.coroutines.CompletableDeferred

/**
 * 假书：每章块数由入参给定，`load` 的哨兵解析与越界钳位与生产 `ReadBookActivity.loadPage`
 * 同一套语义（BEGIN → 首块、END → 末块、其余钳到章内），回出的 `pageAll` 即该章块数。
 *
 * 控制器测试与容器渲染测试共用：两边必须看到同一份「排版何时落定、块数从哪来」的时序，
 * 各写一份就会出现「控制器测的假书与容器测的假书行为不同」这种查不出来的偏差。
 *
 * - [gates]：给某个 key 挂一道门，用来构造「块数尚未落定」的中间态
 * - [requests]：每个 key 被请求过几次，用来断言重复加载
 * - [failures]：这些 key 返回 null（生产侧即加载失败 → 错误态）
 */
internal class FakeScrollBook(val blocksPerChapter: List<Int>) {
    val requests = mutableMapOf<ReaderPageKey, Int>()
    val gates = mutableMapOf<ReaderPageKey, CompletableDeferred<Unit>>()
    val failures = mutableSetOf<ReaderPageKey>()

    fun fail(vararg keys: ReaderPageKey) {
        failures += keys
    }

    suspend fun load(chapterIndex: Int, pageIndex: Int): ReaderPageUi.Loaded? {
        val key = ReaderPageKey(chapterIndex, pageIndex)
        requests[key] = (requests[key] ?: 0) + 1
        gates[key]?.await()
        if (key in failures) return null
        val resolved = when (pageIndex) {
            DBCode.BookContentView.DUR_PAGE_INDEX_BEGIN -> 0
            DBCode.BookContentView.DUR_PAGE_INDEX_END -> blocksPerChapter[chapterIndex] - 1
            else -> pageIndex.coerceIn(0, blocksPerChapter[chapterIndex] - 1)
        }
        return ReaderPageUi.Loaded(
            title = "第${chapterIndex + 1}章",
            chapterIndex = chapterIndex,
            durPageIndex = resolved,
            pageAll = blocksPerChapter[chapterIndex],
            text = "正文 $chapterIndex-$resolved"
        )
    }
}
