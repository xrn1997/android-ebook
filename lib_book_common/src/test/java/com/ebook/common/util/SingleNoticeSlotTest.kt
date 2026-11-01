package com.ebook.common.util

import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * [SingleNoticeSlot] 的槽语义（ADR-0061 的机械支点）。
 *
 * 槽是「宿主只发结论、页面取走并展示」的承载件，页面靠 `LaunchedEffect(notice)` 消费，
 * 以下语义中任何一条写反都不会编译失败：
 * - emit 后当前值必须可读（转屏重建后还能补展示）；
 * - consume 必须清零（否则每次重组重复弹同一条）；
 * - **相等值（含同一实例）在 consume 前重复 emit 会被 StateFlow 按 `equals` 判等吞掉**
 *   （宿主连发同值结论只提示一次）；consume 之后同值必须能再到达（同一失败重试也要提示）。
 */
class SingleNoticeSlotTest {

    private sealed interface Conclusion {
        data class Failed(val cause: Throwable) : Conclusion

        /** 值语义结论：字段相同的独立实例彼此 `equals` */
        data class Migrated(val count: Int) : Conclusion

        data object Saved : Conclusion
    }

    @Test
    fun `emit 后页面能读到结论`() = runTest {
        val slot = SingleNoticeSlot<Conclusion>()
        val conclusion = Conclusion.Failed(IllegalStateException("网络好像不太稳定"))

        slot.emit(conclusion)

        assertSame("槽里应原样保留结论实例（cause 不被换掉）", conclusion, slot.notice.value)
    }

    @Test
    fun `consume 清零后没有待展示的结论`() = runTest {
        val slot = SingleNoticeSlot<Conclusion>()
        slot.emit(Conclusion.Saved)

        slot.consume()

        assertNull(slot.notice.value)
    }

    @Test
    fun `相等值在 consume 前重复 emit 被判等吞掉`() = runTest {
        val slot = SingleNoticeSlot<Conclusion>()
        val seen = mutableListOf<Conclusion?>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            slot.notice.collect { seen += it }
        }
        // 同一实例是最小的相等值样例；值相等的不同实例见下一条用例
        val conclusion = Conclusion.Failed(IllegalStateException("网络好像不太稳定"))

        slot.emit(conclusion)
        slot.emit(conclusion)
        advanceUntilIdle()

        assertEquals(
            "StateFlow 对相等值只发一次：未 consume 前重复 emit 必须在观察面上被吞掉",
            listOf(null, conclusion),
            seen,
        )
        assertSame(conclusion, slot.notice.value)
    }

    @Test
    fun `等值不同实例在 consume 前重复 emit 被吞、consume 后同值可再发`() = runTest {
        val slot = SingleNoticeSlot<Conclusion>()
        val seen = mutableListOf<Conclusion?>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            slot.notice.collect { seen += it }
        }

        // 两个字段相同的独立实例：StateFlow 按 equals 判重，不是按引用
        slot.emit(Conclusion.Migrated(2))
        slot.emit(Conclusion.Migrated(2))
        advanceUntilIdle()

        assertEquals(
            "等值不同实例在 consume 前也只能到达一次",
            listOf(null, Conclusion.Migrated(2)),
            seen,
        )

        slot.consume()
        slot.emit(Conclusion.Migrated(2))
        advanceUntilIdle()

        assertEquals(
            "consume 归 null 后同值应能再次到达",
            listOf(null, Conclusion.Migrated(2), null, Conclusion.Migrated(2)),
            seen,
        )
    }

    @Test
    fun `consume 后可以再发同一个结论`() = runTest {
        val slot = SingleNoticeSlot<Conclusion>()
        val seen = mutableListOf<Conclusion?>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            slot.notice.collect { seen += it }
        }
        val conclusion = Conclusion.Failed(IllegalStateException("网络好像不太稳定"))

        slot.emit(conclusion)
        slot.consume()
        slot.emit(conclusion)
        advanceUntilIdle()

        assertEquals(
            "consume 归 null 后同值应能再次到达（同一失败重试也要提示）",
            listOf(null, conclusion, null, conclusion),
            seen,
        )
    }
}
