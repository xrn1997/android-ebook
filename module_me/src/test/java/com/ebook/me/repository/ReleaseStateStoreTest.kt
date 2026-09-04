package com.ebook.me.repository

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 静默刷新限频窗口的纯算术测试（[ReleaseStateStore.isRefreshDue]）。
 *
 * 只测这一个函数，不测 [ReleaseStateStore] 整体：后者要 `@ApplicationContext`，而
 * module_me 的单测只有 JUnit（无 Robolectric），起 SharedPreferences 不划算。
 * 窗口算术恰恰抽成了纯函数，就是为了让「差一小时也不该多发一次请求」这类边界可锁。
 *
 * 语义约定：`lastSuccessMillis == 0L` 表示从未成功检查过（SP 缺省值），一律放行首查；
 * 「检查失败」不会写时间戳，因此不会把下一次静默刷新推后。
 */
class ReleaseStateStoreTest {

    private val day = 24L * 60 * 60 * 1000

    @Test
    fun `从未成功检查过时放行首次静默刷新`() {
        assertTrue(ReleaseStateStore.isRefreshDue(lastSuccessMillis = 0L, nowMillis = 0L))
        assertTrue(
            ReleaseStateStore.isRefreshDue(
                lastSuccessMillis = 0L,
                nowMillis = System.currentTimeMillis(),
            )
        )
    }

    @Test
    fun `不足七天时不发起静默刷新`() {
        val last = 1_700_000_000_000L
        assertFalse(
            ReleaseStateStore.isRefreshDue(
                lastSuccessMillis = last,
                nowMillis = last + 6 * day,
            )
        )
        // 差一小时到点：仍然不放行（限频是硬窗口，不是「大概一周」）
        assertFalse(
            ReleaseStateStore.isRefreshDue(
                lastSuccessMillis = last,
                nowMillis = last + 7 * day - 60 * 60 * 1000,
            )
        )
    }

    @Test
    fun `恰好到七天与超过七天都放行`() {
        val last = 1_700_000_000_000L
        assertTrue(
            ReleaseStateStore.isRefreshDue(
                lastSuccessMillis = last,
                nowMillis = last + ReleaseStateStore.AUTO_REFRESH_INTERVAL_MILLIS,
            )
        )
        assertTrue(
            ReleaseStateStore.isRefreshDue(
                lastSuccessMillis = last,
                nowMillis = last + 30 * day,
            )
        )
    }

    @Test
    fun `时钟回拨时不因此无限放行`() {
        // now < last（用户改系统时间/NTP 校正）：窗口尚未到期，按「不到点」处理
        val last = 1_700_000_000_000L
        assertFalse(
            ReleaseStateStore.isRefreshDue(
                lastSuccessMillis = last,
                nowMillis = last - day,
            )
        )
    }
}
