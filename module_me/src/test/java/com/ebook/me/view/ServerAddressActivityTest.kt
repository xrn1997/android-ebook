package com.ebook.me.view

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [ServerAddressActivity] 的清单声明回归。
 *
 * 锁的坑：页面写完、编译与渲染测试全绿，但**忘了在两份 AndroidManifest 里声明**——
 * TheRouter 的 `navigation()` 解析到显式 Intent 后 `ActivityNotFoundException` 当场闪退
 * （2026-11-01 装机时踩到的正是它）。清单缺失在编译期毫无信号：assemble 照样成功，
 * 渲染测试直接组合 `ServerAddressScreen` 也碰不到 Activity 壳层。
 *
 * Robolectric 吃的是合并后的清单，声明缺位时 `resolveActivity` 解析为 null——
 * 本用例在同款回归上必红。声明要在 `src/main/AndroidManifest.xml`（集成态）与
 * `src/main/module/AndroidManifest.xml`（独立态）两处同步（见前者文件头注释）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ServerAddressActivityTest {

    @Test
    fun `服务器地址页已声明在清单中可以被启动`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()

        val resolved = context.packageManager.resolveActivity(
            Intent(context, ServerAddressActivity::class.java),
            0,
        )

        assertNotNull("页面没在 AndroidManifest 声明，跳转会当场 ActivityNotFoundException", resolved)
    }
}
