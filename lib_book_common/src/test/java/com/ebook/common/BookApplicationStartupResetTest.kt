package com.ebook.common

import androidx.test.core.app.ApplicationProvider
import com.ebook.api.config.ServerAddress
import com.ebook.api.config.ServerAddressStore
import com.ebook.common.domain.UserSession
import com.ebook.common.domain.UserSessionManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
 * [BookApplication] 启动兜底的回归测试（Robolectric JVM，不启动 Application）。
 *
 * 锁定的是「换址后的旧会话在下次启动被补清」的几条不变量：
 * - 有落盘标记才清、没有不清（不能把每次启动都变成一次无谓的登出）；
 * - **先清会话、后清标记**：若进程死在两步之间，标记仍在、下次启动重跑；
 *   反过来先清标记，中途死亡就再也没人清那份旧 refresh token。
 * - 没有标记时**连会话管理器都不取**：干净启动不该为一次空判据把整个会话对象图建到主线程上。
 *
 * 取消窗口一侧（NonCancellable）由 `AndroidUserSessionManagerTest` 的 `endSession` 用例覆盖，
 * 这里刻意不构造真实取消时序。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BookApplicationStartupResetTest {

    private fun newStore() =
        ServerAddressStore(ApplicationProvider.getApplicationContext())

    /**
     * 记录「清会话那一刻」的标记状态：顺序若反了，clear 时标记已经是 false。
     * 未登录时 getRefreshToken 返回 null，与生产语义一致。
     */
    private class MarkerWatchingSessionManager(
        private val store: ServerAddressStore,
        private val clearSucceeds: Boolean = true,
        private val clearThrows: Boolean = false,
    ) : UserSessionManager {
        private val _isLoggedIn = MutableStateFlow(true)
        override val isLoggedIn: StateFlow<Boolean> = _isLoggedIn

        private val _currentUser = MutableStateFlow<UserSession?>(
            UserSession(
                userId = 1L,
                username = "old-user",
                nickname = "旧身份",
                avatar = "",
                token = "old-access-token",
            )
        )
        override val currentUser: StateFlow<UserSession?> = _currentUser

        var clearCount = 0
            private set
        var markerAtClear: Boolean? = null
            private set

        override suspend fun saveSession(session: UserSession, refreshToken: String) = Unit

        override suspend fun rotateCredentials(accessToken: String, refreshToken: String) = Unit

        override fun clearSession(): Boolean {
            clearCount++
            // 顺序断言点：clear 执行时标记必须仍然在盘上
            markerAtClear = store.hasSessionResetPending()
            if (clearThrows) throw IllegalStateException("磁盘不可写")
            _isLoggedIn.value = false
            _currentUser.value = null
            return clearSucceeds
        }

        override suspend fun endSession() {
            clearSession()
        }

        override fun getRefreshToken(): String? =
            if (_isLoggedIn.value) "old-refresh-token" else null
    }

    @Test
    fun `存在待清标记时先清会话再清标记`() = runTest {
        val store = newStore()
        store.save(ServerAddress.parse("192.168.9.9:8080")!!)
        val session = MarkerWatchingSessionManager(store)

        val reset = applyPendingAddressSessionReset(store) { session }

        assertTrue("成功补清应报告可继续", reset)
        assertEquals(1, session.clearCount)
        assertEquals(
            "清会话时标记必须仍在：顺序反了，进程死在两步之间就丢了凭证清理",
            true,
            session.markerAtClear,
        )
        assertFalse("旧地址的登录态必须失效", session.isLoggedIn.value)
        assertNull("旧地址的 refresh token 不得留到新地址的首个请求", session.getRefreshToken())
        assertFalse("会话清完后才消费标记", newStore().hasSessionResetPending())
    }

    @Test
    fun `没有待清标记时不清会话`() {
        val store = newStore()
        val session = MarkerWatchingSessionManager(store)
        var providerCalls = 0

        applyPendingAddressSessionReset(store) {
            providerCalls++
            session
        }

        assertEquals("干净启动不得把用户登出", 0, session.clearCount)
        assertEquals("干净启动不该为兜底建出会话对象图", 0, providerCalls)
    }

    @Test
    fun `标记只消费一次`() = runTest {
        val store = newStore()
        store.save(ServerAddress.parse("192.168.9.9:8080")!!)
        val session = MarkerWatchingSessionManager(store)

        applyPendingAddressSessionReset(store) { session }
        applyPendingAddressSessionReset(store) { session }

        assertEquals("标记消费后，下次启动不得再清一遍", 1, session.clearCount)
    }

    /**
     * 清会话的 SP 写可能落盘失败（磁盘满/IO 错误）。此时标记**不能**被消费：
     * 否则旧 refresh token 仍在盘上，下次启动会带着它去新地址首个请求。
     */
    @Test
    fun `清会话未落盘成功时保留标记待下次启动重试`() = runTest {
        val store = newStore()
        store.save(ServerAddress.parse("192.168.9.9:8080")!!)
        val session = MarkerWatchingSessionManager(store, clearSucceeds = false)

        val reset = applyPendingAddressSessionReset(store) { session }

        assertFalse("失败必须如实报告，不能当成已补清", reset)
        assertEquals(1, session.clearCount)
        assertTrue("标记必须留给下次启动重试", newStore().hasSessionResetPending())
    }

    /** 异常同样不得让标记消失，更不能把异常抛进 Application.onCreate 形成启动崩溃循环。 */
    @Test
    fun `清会话抛异常时保留标记且不向启动路径抛出`() = runTest {
        val store = newStore()
        store.save(ServerAddress.parse("192.168.9.9:8080")!!)
        val session = MarkerWatchingSessionManager(store, clearThrows = true)

        val reset = applyPendingAddressSessionReset(store) { session }

        assertFalse(reset)
        assertEquals(1, session.clearCount)
        assertTrue("异常路径必须保留标记", newStore().hasSessionResetPending())
    }
}
