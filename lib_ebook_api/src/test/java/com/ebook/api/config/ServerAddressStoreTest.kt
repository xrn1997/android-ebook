package com.ebook.api.config

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.ebook.api.testfixture.FakeSpContext
import com.ebook.api.testfixture.FakeSharedPreferences
import com.ebook.api.utils.NetworkModule
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [ServerAddressStore] 的行为回归（Robolectric：需要真实 SharedPreferences）。
 *
 * 语义基准（ADR-0052/0053）：**不内置任何默认地址**，状态只有「未配置（null）/ 用户自填」两档；
 * [effective] 是构造时冻结的进程快照、[current] 只随成功落盘推进；换址/清除配置与待清标记同一笔 commit。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ServerAddressStoreTest {

    private fun context(): Context = ApplicationProvider.getApplicationContext()

    private fun newStore() = ServerAddressStore(context())

    // ==================== 未配置：应用不带默认地址（ADR-0053） ====================

    @Test
    fun `未落盘地址时为未配置`() {
        val store = newStore()

        assertNull("没有落盘地址就是未配置，没有可回落的默认值", store.current())
        assertFalse(store.isConfigured)
        assertNull("生效面同为未配置", store.effective())
        assertFalse(store.pendingRestart)
    }

    @Test
    fun `未配置时认证白名单为空集`() {
        assertEquals(
            "未配置时没有任何 host 能拿到 token",
            emptySet<String>(),
            NetworkModule.provideAuthAllowedHosts(newStore()),
        )
    }

    @Test
    fun `清除配置后回到未配置`() = runTest {
        val store = newStore()
        store.save(ServerAddress.parse("192.168.1.8:8080")!!)

        assertTrue(store.reset())

        assertNull("清除配置回到未配置（没有默认值可回）", store.current())
        assertFalse(store.isConfigured)
    }

    // ==================== 两个读面：配置面 vs 生效面（进程快照） ====================

    /**
     * 网络面（Retrofit 基址 + token 白名单）读的是**进程启动快照**，不是 SP 现值。
     *
     * 「重启后才一致」这句话只有在这里冻结才成立：若白名单跟着 `save()` 走，那么保存后
     * 才被创建的 Retrofit 会打到新地址，而先建好的白名单仍是旧主机——token 从此不附加，
     * 且没有任何机制会纠正它（用户完全可以不重启）。这正是 ADR-0052 决策 1 要防的错配。
     */
    @Test
    fun `保存后配置面更新而生效面仍是启动快照`() = runTest {
        val store = newStore() // 启动快照 = 未配置

        store.save(ServerAddress.parse("192.168.1.8:8080")!!)

        assertTrue(store.isConfigured)
        assertEquals("http://192.168.1.8:8080/", store.current()!!.baseUrl)
        assertNull("本进程启动时未配置，生效面保持未配置", store.effective())
        assertTrue("两者不一致这件事必须能被判出来", store.pendingRestart)
    }

    /**
     * 同进程新实例 = 重新注入（Robolectric 没有独立进程，不能真正模拟进程重启；这里锁的是
     * 「重新构造的 store 从 SP 读快照」这一读法，而不是 commit 的同步落盘保证）。
     */
    @Test
    fun `同进程新实例的生效面与白名单同取已存地址`() = runTest {
        newStore().save(ServerAddress.parse("192.168.1.8:8080")!!)

        val reinjected = newStore()
        assertEquals("http://192.168.1.8:8080/", reinjected.effective()!!.baseUrl)
        assertEquals(setOf("192.168.1.8"), NetworkModule.provideAuthAllowedHosts(reinjected))
        assertFalse(reinjected.pendingRestart)
    }

    // ==================== pendingRestart：重启提示的进程级判据 ====================

    @Test
    fun `刚启动时不需要重启`() {
        assertFalse(newStore().pendingRestart)
    }

    @Test
    fun `保存自定义地址后需要重启`() = runTest {
        val store = newStore()
        store.save(ServerAddress.parse("192.168.1.8:8080")!!)

        assertTrue("已改地址、本进程尚未重启", store.pendingRestart)
    }

    @Test
    fun `同进程新实例读到同一份已存地址时不需要重启`() = runTest {
        newStore().save(ServerAddress.parse("192.168.1.8:8080")!!)

        // 同进程新实例 = 重新注入：构造时的快照就是落盘值，于是没有待重启项
        assertFalse(newStore().pendingRestart)
    }

    /**
     * 落盘必须用 `display` 而不是 `baseUrl`：`baseUrl` 会省略 scheme 默认端口（`:80`），
     * 回读时无法区分「显式 80」与「未写端口」，会静默变成别的值。
     */
    @Test
    fun `显式 scheme 默认端口保存后原样读回`() = runTest {
        newStore().save(ServerAddress.parse("http://host.example:80")!!)

        assertEquals(80, newStore().current()!!.port)
    }

    // ==================== 落盘值损坏：按未配置处理并自愈 ====================

    @Test
    fun `落盘值损坏时按未配置处理`() {
        context().getSharedPreferences("server_address", Context.MODE_PRIVATE)
            .edit().putString("address", "not a url").commit()

        val store = newStore()
        assertNull("解析不了的落盘串按未配置处理（没有默认值可猜）", store.current())
    }

    @Test
    fun `落盘值损坏时不算已配置`() {
        context().getSharedPreferences("server_address", Context.MODE_PRIVATE)
            .edit().putString("address", "not a url").commit()

        assertFalse(newStore().isConfigured)
    }

    /**
     * 脏值自愈：解析不了的落盘串已按未配置生效（见上两例），留在盘上只会永远占着键。
     * 自愈在注入时发生、且**不是换址**——不置待清标记：行为上从头到尾都是未配置，没有旧地址的会话要清。
     */
    @Test
    fun `落盘值损坏时注入自愈清掉脏键且不置待清标记`() {
        val sp = context().getSharedPreferences("server_address", Context.MODE_PRIVATE)
        sp.edit().putString("address", "not a url").commit()

        val store = newStore()

        assertFalse("解析不了的脏键应被自愈清掉", sp.contains("address"))
        assertFalse("自愈不是换址，不该触发启动补清", store.hasSessionResetPending())
    }

    // ==================== session_reset_pending：启动兜底清会话的落盘标记 ====================

    @Test
    fun `刚启动时没有待清的换址会话`() {
        assertFalse(newStore().hasSessionResetPending())
    }

    @Test
    fun `保存地址时同笔落盘待清标记且新实例可见`() = runTest {
        newStore().save(ServerAddress.parse("192.168.1.8:8080")!!)

        // 新实例 = 模拟重启后的新进程：标记必须已经同步落盘
        assertTrue("地址与标记必须同一笔 commit 落盘", newStore().hasSessionResetPending())
    }

    @Test
    fun `清除配置同样置待清标记`() = runTest {
        val store = newStore()
        store.save(ServerAddress.parse("192.168.1.8:8080")!!)
        store.clearSessionResetPending()
        assertFalse(store.hasSessionResetPending())

        store.reset()

        assertTrue("清除配置也是换址，旧地址的会话同样要清", newStore().hasSessionResetPending())
    }

    @Test
    fun `清除待清标记后读回 false`() = runTest {
        val store = newStore()
        store.save(ServerAddress.parse("192.168.1.8:8080")!!)

        store.clearSessionResetPending()

        assertFalse(store.hasSessionResetPending())
        assertFalse(newStore().hasSessionResetPending())
    }

    // ==================== commit 失败面：返回值必须能被调用方看见 ====================

    @Test
    fun `保存成功返回 true 且地址与待清标记同笔 commit`() = runTest {
        val fake = FakeSharedPreferences()
        val store = storeWith(fake)

        assertTrue(store.save(ServerAddress.parse("192.168.1.8:8080")!!))

        assertEquals("两次写只允许一笔 commit", 1, fake.commitCount)
        assertEquals(
            "地址与待清标记必须同一笔落盘",
            setOf("address", "session_reset_pending"),
            fake.lastCommittedKeys,
        )
    }

    @Test
    fun `保存 commit 失败时返回 false 且配置面与待重启保持原状`() = runTest {
        val fake = FakeSharedPreferences().apply { commitSucceeds = false }
        val store = storeWith(fake)

        assertFalse(store.save(ServerAddress.parse("192.168.1.8:8080")!!))

        assertNull("失败不得让配置面看起来已生效", store.current())
        assertFalse(store.isConfigured)
        assertFalse("失败不得留下待清标记", store.hasSessionResetPending())
        assertFalse("失败不得催用户重启", store.pendingRestart)
    }

    @Test
    fun `清除配置把删除地址与待清标记写在同一笔 commit`() = runTest {
        val fake = FakeSharedPreferences()
        val store = storeWith(fake)
        store.save(ServerAddress.parse("192.168.1.8:8080")!!)

        assertTrue(store.reset())

        assertEquals("保存 + 清除配置各一笔 commit", 2, fake.commitCount)
        assertEquals(
            "删除地址与待清标记必须同一笔落盘",
            setOf("address", "session_reset_pending"),
            fake.lastCommittedKeys,
        )
    }

    @Test
    fun `清除配置 commit 失败时返回 false 并保留原地址`() = runTest {
        val fake = FakeSharedPreferences()
        val store = storeWith(fake)
        val custom = ServerAddress.parse("192.168.1.8:8080")!!
        store.save(custom)

        fake.commitSucceeds = false
        assertFalse(store.reset())

        assertEquals("失败后仍按原地址展示", custom, store.current())
        assertTrue(store.isConfigured)
    }

    @Test
    fun `清除待清标记失败时返回 false 且标记仍可重试`() = runTest {
        val fake = FakeSharedPreferences()
        val store = storeWith(fake)
        store.save(ServerAddress.parse("192.168.1.8:8080")!!)

        fake.commitSucceeds = false
        assertFalse(store.clearSessionResetPending())

        assertTrue("落盘失败时标记必须留给下次启动重试", store.hasSessionResetPending())
    }

    // ==================== 测试接缝：可控 SharedPreferences ====================

    private fun storeWith(fake: FakeSharedPreferences) =
        ServerAddressStore(FakeSpContext(context(), fake))
}
