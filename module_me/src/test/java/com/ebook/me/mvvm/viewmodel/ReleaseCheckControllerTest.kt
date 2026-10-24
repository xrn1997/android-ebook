package com.ebook.me.mvvm.viewmodel

import com.ebook.me.repository.ReleaseCheckResult
import com.ebook.me.repository.ReleaseState
import com.ebook.me.util.AppVersion
import com.ebook.me.util.isOlderThan
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ReleaseCheckController] 的测试：单飞、静默→弹窗升级、关窗取消与「判不出不落盘」。
 *
 * 这些判据原先长在 `SettingViewModel` 里（`checkJob` / `resultToDialog` 两个私有字段 +
 * 三处分支），只能经 433 行 Robolectric 测接线。抽成纯 JVM 会话后，检查请求与本地状态
 * 都以接口/函数注入，边界可以直接锁死。
 */
class ReleaseCheckControllerTest {

    private class FakeState(
        var due: Boolean = true,
        var currentName: String? = "1.0.0",
    ) : ReleaseState {
        var remoteTag: String? = null
        val markedTags = mutableListOf<String>()

        override val currentVersion: AppVersion? get() = currentName?.let(AppVersion::parse)
        override val hasUpdateAvailable: Boolean
            get() {
                val remote = AppVersion.parse(remoteTag ?: return false) ?: return false
                val current = currentVersion ?: return false
                return current.isOlderThan(remote)
            }

        override fun shouldAutoRefresh(): Boolean = due

        override fun markCheckSuccess(remoteTag: String) {
            markedTags += remoteTag
            this.remoteTag = remoteTag
        }
    }

    private fun result(tag: String) = ReleaseCheckResult(
        remoteTag = tag,
        body = "notes",
        apkDownloadUrl = null,
    )

    @Test
    fun `静默检查在途时点击版本行只升级弹窗不并发第二个请求`() = runTest {
        val state = FakeState()
        val gate = CompletableDeferred<ReleaseCheckResult?>()
        var calls = 0
        val controller = ReleaseCheckController(state, { calls++; gate.await() }, this)

        controller.startSilentRefresh()
        runCurrent()
        assertEquals("静默刷新不该自己弹窗", UpdateState.Idle, controller.uiState.value)

        controller.check(manual = true)
        runCurrent()

        assertEquals("单飞：第二次点击不另发请求", 1, calls)
        assertEquals("点击当场要进入检查中", UpdateState.Checking, controller.uiState.value)

        gate.complete(result("V1.1.0"))
        advanceUntilIdle()

        assertTrue("在途结论要改道进弹窗", controller.uiState.value is UpdateState.HasUpdate)
        assertEquals(listOf("V1.1.0"), state.markedTags)
    }

    @Test
    fun `关闭检查中弹窗会取消在途检查且不落结论`() = runTest {
        val state = FakeState()
        val gate = CompletableDeferred<ReleaseCheckResult?>()
        val controller = ReleaseCheckController(state, { gate.await() }, this)

        controller.check(manual = true)
        runCurrent()
        assertEquals(UpdateState.Checking, controller.uiState.value)

        controller.consumeDialog()
        assertEquals(UpdateState.Idle, controller.uiState.value)

        gate.complete(result("V1.1.0"))
        advanceUntilIdle()

        assertEquals("关窗即放弃本次检查", UpdateState.Idle, controller.uiState.value)
        assertTrue("被取消的检查不该写成功时间戳", state.markedTags.isEmpty())
        assertFalse(controller.hasUpdateAvailable.value)
    }

    @Test
    fun `远端 tag 判不出时按 CheckError 且不写成功时间戳`() = runTest {
        val state = FakeState()
        val controller = ReleaseCheckController(state, { result("not-a-version") }, this)

        controller.check(manual = true)
        advanceUntilIdle()

        assertEquals(UpdateState.CheckError, controller.uiState.value)
        assertTrue("判不出结论不算检查成功：限频窗口不能被占满", state.markedTags.isEmpty())
    }

    @Test
    fun `检查请求返回 null 时按 CheckError`() = runTest {
        val state = FakeState()
        val controller = ReleaseCheckController(state, { null }, this)

        controller.check(manual = true)
        advanceUntilIdle()

        assertEquals(UpdateState.CheckError, controller.uiState.value)
        assertTrue(state.markedTags.isEmpty())
    }

    @Test
    fun `同一次成功检查后角标由 tag 与当前版本现场派生`() = runTest {
        val state = FakeState()
        val controller = ReleaseCheckController(state, { result("V1.1.0") }, this)
        assertFalse(controller.hasUpdateAvailable.value)

        controller.check(manual = true)
        advanceUntilIdle()

        assertTrue("1.0.0 落后于 V1.1.0", controller.hasUpdateAvailable.value)
        assertEquals(listOf("V1.1.0"), state.markedTags)
    }

    @Test
    fun `静默检查在限频窗口内时不发请求`() = runTest {
        val state = FakeState(due = false)
        var calls = 0
        val controller = ReleaseCheckController(state, { calls++; null }, this)

        controller.startSilentRefresh()
        advanceUntilIdle()

        assertEquals(0, calls)
        assertEquals(UpdateState.Idle, controller.uiState.value)
    }

    @Test
    fun `静默检查成功只更新角标不改弹窗状态`() = runTest {
        val state = FakeState()
        val controller = ReleaseCheckController(state, { result("V1.1.0") }, this)

        controller.startSilentRefresh()
        advanceUntilIdle()

        assertEquals(UpdateState.Idle, controller.uiState.value)
        assertTrue(controller.hasUpdateAvailable.value)
    }

    @Test
    fun `刷新角标按当前持久化结论重新派生`() = runTest {
        val state = FakeState()
        state.remoteTag = "V1.2.0"
        val controller = ReleaseCheckController(state, { null }, this)

        controller.refreshBadge()

        assertTrue(controller.hasUpdateAvailable.value)
    }
}
