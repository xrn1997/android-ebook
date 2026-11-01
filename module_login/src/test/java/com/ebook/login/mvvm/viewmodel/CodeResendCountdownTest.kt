package com.ebook.login.mvvm.viewmodel

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [CodeResendCountdown] 的虚拟时间测试。
 *
 * 锁的是发码按钮「60 秒频控窗口」的**时间口径**：启动即 60、每秒减 1、60 秒归零；
 * 重发（重启）不叠加——旧任务若没被取消，两套递减会并行推进，按钮提前解禁、
 * 用户连点撞服务端 A0241（尝试超限）；宿主 scope 取消后不再写状态（VM 销毁后旧递减
 * 不得继续改已无人订阅的状态）。真等 60 秒的测试没人会在改动后跑，故全部走虚拟时间。
 */
class CodeResendCountdownTest {

    @Test
    fun `启动即置 60，无需等待第一步调度`() = runTest {
        val countdown = CodeResendCountdown()

        countdown.start(this)

        assertEquals("按钮一按下就要立刻锁住，首帧不能仍显示可点", 60, countdown.seconds.value)
    }

    @Test
    fun `推进 1 秒减到 59`() = runTest {
        val countdown = CodeResendCountdown()
        countdown.start(this)

        advanceTimeBy(1_001)

        assertEquals(59, countdown.seconds.value)
    }

    @Test
    fun `推进 60 秒归零`() = runTest {
        val countdown = CodeResendCountdown()
        countdown.start(this)

        advanceTimeBy(60_001)

        assertEquals("归零后按钮必须恢复可点", 0, countdown.seconds.value)
    }

    @Test
    fun `重启会取消旧任务，不出现双倍递减`() = runTest {
        val countdown = CodeResendCountdown()
        countdown.start(this)
        // 先走到半程（t=30s 时停在 30），再像「成功重发」一样重启
        advanceTimeBy(30_500)
        assertEquals(30, countdown.seconds.value)

        countdown.start(this)
        assertEquals(60, countdown.seconds.value)

        // 旧任务原本的下一拍在新起点后 0.5 秒（t=30.5+0.5），新任务的第一拍在 1 秒后：
        // 若旧任务没被取消，这里会看到两次递减叠加成 58
        advanceTimeBy(1_500)

        assertEquals("旧任务不得与新任务同时递减", 59, countdown.seconds.value)
    }

    @Test
    fun `宿主 scope 取消后不再写状态`() = runTest {
        val countdown = CodeResendCountdown()
        // 独立 Job 模拟宿主 scope（runTest 自身的 Job 不能取消，否则测试框架一起停）
        val hostScope = CoroutineScope(coroutineContext + Job())
        countdown.start(hostScope)
        advanceTimeBy(1_001)
        assertEquals(59, countdown.seconds.value)

        hostScope.cancel()
        advanceTimeBy(30_000)

        assertEquals(
            "宿主销毁/取消后旧递减任务必须随之停止，不能继续改写已无人订阅的状态",
            59,
            countdown.seconds.value,
        )
    }
}
