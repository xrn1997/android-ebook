package com.ebook.common.domain

import com.ebook.api.auth.SessionEvent
import com.ebook.api.auth.SessionEventBus
import com.ebook.api.entity.RefreshTokenRequest
import com.ebook.api.service.user.SessionRefresh
import com.ebook.api.service.user.TokenRefresh
import com.ebook.api.utils.CoroutineAdapter
import com.xrn1997.common.constant.ErrorCode
import com.xrn1997.common.di.TokenHolder
import com.xrn1997.common.dto.RespDTO
import dagger.Lazy
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SessionTokenRefresher]（双 token 静默刷新）的回归测试，纯 JVM。
 *
 * 锁住的三个缺陷：
 * - (a) 单飞复用守卫此前额外要求 `expiredAccessToken` 非空，而 access token 只驻内存、
 *   冷启动恒为空（见 ADR-0011）——并发 A0230 恰恰只发生在冷启动这条路上，守卫在最需要
 *   它的场景里永不生效，退化成 N 次串行刷新（服务端刷新会硬删旧 refresh，后者必失败）。
 * - (b) 刷新响应缺 `refresh_token` 时此前 `orEmpty()` 会把唯一可恢复的凭据写成空串。
 * - (c) 内部 `catch (e: Exception)` 曾把 [CancellationException] 吞成「刷新失败」，
 *   上层据此误发 SessionExpired、把用户踢到登录页。
 *
 * (d) 组不是缺陷而是**接缝的两条出口**（ADR-0059 把刷新切成窄面后必须重新钉一遍）：
 * 刷新成功后原请求只重放一次即止；刷新被拒时会话过期事件只发一次且不重放。
 *
 * 边界说明：日志不属本层职责；会话过期事件（`SessionEventBus`）由 lib_ebook_api 的
 * `CoroutineAdapter` 发，因此 (d) 组只在**生产装配**上直断它的两条出口，本层单测不覆盖，
 * 也不为此引入 Robolectric/Android 依赖。
 * 同样不覆盖的是**信封怎么折成结论**：自 ADR-0059 起本层只消费窄面 [SessionRefresh] 的
 * [TokenRefresh]，业务码与 `data` 空判归 `lib_ebook_api`（读数在 `UserFacesDigestTest`）。
 * 因此下面的假件回的是结论而不是 `RespDTO`——它测的是「拿到某一档结论时本层怎么处置」，
 * 单飞、重放次数与「旧凭据不被写空」这些时序断言一字未改（窄面只有一个方法，原先按整张
 * 宽接口写的九个 `error("unused in this test")` 占位实现随之消失）。
 */
class SessionTokenRefresherTest {

    // ===== (a) 单飞复用守卫 =====

    /**
     * 冷启动（TokenHolder 里无 access token）并发 5 个 refresh(null)：真实刷新接口只能被打一次。
     *
     * 用 [SharedTokenSessionManager] 而不是已有的 `FakeUserSessionManager`：后者自己 new 了一个
     * TokenHolder，与注入给 refresher 的那个**不是同一实例**，守卫读的 tokenHolder 永远不会被
     * 刷新结果更新，拿它来测守卫只会得到假的错误结果。
     */
    @Test
    fun `冷启动无 access token 时并发刷新只打一次真实刷新接口`() = runTest {
        val tokenHolder = TokenHolder()
        val sessionManager = SharedTokenSessionManager(tokenHolder)
        // 冷启动状态：已登录且有 refresh token，access token 为空串
        // （TokenHolder 把空串归一化为 null，与生产 loadSessionFromSp 的结果一致）
        sessionManager.saveSession(session(token = ""), REFRESH_TOKEN)
        assertNull(tokenHolder.token)
        val dataSource = CountingSessionRefresh()
        val refresher = SessionTokenRefresher(sessionManager, tokenHolder, Lazy { dataSource })

        val results = coroutineScope {
            List(5) { async { refresher.refresh(null) } }.map { it.await() }
        }

        assertEquals(1, dataSource.refreshCallCount)
        assertEquals(List(5) { NEW_ACCESS_TOKEN }, results)
        // 新 access token 已写进那个共享的 TokenHolder，后续请求直接带上它
        assertEquals(NEW_ACCESS_TOKEN, tokenHolder.token)
    }

    @Test
    fun `触发过期的正是当前 token 时守卫不复用必须真的打刷新接口`() = runTest {
        val tokenHolder = TokenHolder()
        val sessionManager = SharedTokenSessionManager(tokenHolder)
        sessionManager.saveSession(session(token = OLD_ACCESS_TOKEN), REFRESH_TOKEN)
        val dataSource = CountingSessionRefresh()
        val refresher = SessionTokenRefresher(sessionManager, tokenHolder, Lazy { dataSource })

        val newToken = refresher.refresh(OLD_ACCESS_TOKEN)

        assertEquals(NEW_ACCESS_TOKEN, newToken)
        assertEquals(1, dataSource.refreshCallCount)
        assertEquals(listOf(REFRESH_TOKEN), dataSource.requestedRefreshTokens)
    }

    @Test
    fun `首个请求真刷其余排队的并发请求复用结果`() = runTest {
        val tokenHolder = TokenHolder()
        val sessionManager = SharedTokenSessionManager(tokenHolder)
        sessionManager.saveSession(session(token = OLD_ACCESS_TOKEN), REFRESH_TOKEN)
        val dataSource = CountingSessionRefresh()
        val refresher = SessionTokenRefresher(sessionManager, tokenHolder, Lazy { dataSource })

        val results = coroutineScope {
            List(3) { async { refresher.refresh(OLD_ACCESS_TOKEN) } }.map { it.await() }
        }

        assertEquals(1, dataSource.refreshCallCount)
        assertEquals(List(3) { NEW_ACCESS_TOKEN }, results)
    }

    // ===== (b) 响应缺 refresh_token 时保留旧值 =====

    @Test
    fun `刷新响应缺 refresh_token 时保留旧的刷新凭据而不是写空串`() = runTest {
        val tokenHolder = TokenHolder()
        val sessionManager = SharedTokenSessionManager(tokenHolder)
        sessionManager.saveSession(session(token = OLD_ACCESS_TOKEN), REFRESH_TOKEN)
        val dataSource = CountingSessionRefresh {
            TokenRefresh.Rotated(accessToken = NEW_ACCESS_TOKEN, refreshToken = null)
        }
        val refresher = SessionTokenRefresher(sessionManager, tokenHolder, Lazy { dataSource })

        assertEquals(NEW_ACCESS_TOKEN, refresher.refresh(OLD_ACCESS_TOKEN))

        // 关键断言：写给持久层的 refresh 是旧值，而不是 orEmpty() 之后的空串
        assertEquals(listOf(REFRESH_TOKEN), sessionManager.rotatedRefreshTokens)
        assertEquals(REFRESH_TOKEN, sessionManager.getRefreshToken())
    }

    @Test
    fun `刷新响应返回空串 refresh_token 时同样保留旧值`() = runTest {
        val tokenHolder = TokenHolder()
        val sessionManager = SharedTokenSessionManager(tokenHolder)
        sessionManager.saveSession(session(token = OLD_ACCESS_TOKEN), REFRESH_TOKEN)
        val dataSource = CountingSessionRefresh {
            TokenRefresh.Rotated(accessToken = NEW_ACCESS_TOKEN, refreshToken = "")
        }
        val refresher = SessionTokenRefresher(sessionManager, tokenHolder, Lazy { dataSource })

        refresher.refresh(OLD_ACCESS_TOKEN)

        assertEquals(listOf(REFRESH_TOKEN), sessionManager.rotatedRefreshTokens)
        assertEquals(REFRESH_TOKEN, sessionManager.getRefreshToken())
    }

    @Test
    fun `刷新响应带新 refresh_token 时轮换为新值且不动身份字段`() = runTest {
        val tokenHolder = TokenHolder()
        val sessionManager = SharedTokenSessionManager(tokenHolder)
        sessionManager.saveSession(session(token = OLD_ACCESS_TOKEN), REFRESH_TOKEN)
        val dataSource = CountingSessionRefresh()
        val refresher = SessionTokenRefresher(sessionManager, tokenHolder, Lazy { dataSource })

        refresher.refresh(OLD_ACCESS_TOKEN)

        assertEquals(listOf(NEW_REFRESH_TOKEN), sessionManager.rotatedRefreshTokens)
        assertEquals(NEW_REFRESH_TOKEN, sessionManager.getRefreshToken())
        // 轮换只更凭证，不重建身份（ADR-0011）
        assertEquals(USER_ID, sessionManager.currentUser.value?.userId)
        assertEquals(NICKNAME, sessionManager.currentUser.value?.nickname)
    }

    // ===== (c) 取消必须上抛 =====

    @Test
    fun `刷新协程被取消时原样上抛而不是当成刷新失败`() = runTest {
        val tokenHolder = TokenHolder()
        val sessionManager = SharedTokenSessionManager(tokenHolder)
        sessionManager.saveSession(session(token = OLD_ACCESS_TOKEN), REFRESH_TOKEN)
        val dataSource = CountingSessionRefresh { throw CancellationException("调用方已取消") }
        val refresher = SessionTokenRefresher(sessionManager, tokenHolder, Lazy { dataSource })

        val outcome = runCatching { refresher.refresh(OLD_ACCESS_TOKEN) }

        // 吞成 null 会被上层判成「会话救不回来」而发 SessionExpired，所以必须是抛出
        val error: Throwable? = outcome.exceptionOrNull()
        assertNotNull(error)
        assertTrue("期望 CancellationException，实际是 $error", error is CancellationException)
    }

    // ===== 失败与不可恢复 =====

    @Test
    fun `服务端返回非成功码时刷新失败返回 null 且不轮换`() = runTest {
        val tokenHolder = TokenHolder()
        val sessionManager = SharedTokenSessionManager(tokenHolder)
        sessionManager.saveSession(session(token = OLD_ACCESS_TOKEN), REFRESH_TOKEN)
        val dataSource = CountingSessionRefresh { TokenRefresh.Rejected("A0230", "用户登录已过期") }
        val refresher = SessionTokenRefresher(sessionManager, tokenHolder, Lazy { dataSource })

        assertNull(refresher.refresh(OLD_ACCESS_TOKEN))

        assertEquals(1, dataSource.refreshCallCount)
        assertTrue(sessionManager.rotatedAccessTokens.isEmpty())
        // 旧 token 原样留在 TokenHolder，交由上层处置
        assertEquals(OLD_ACCESS_TOKEN, tokenHolder.token)
    }

    @Test
    fun `成功码但缺 access token 时返回 null 且不轮换`() = runTest {
        val tokenHolder = TokenHolder()
        val sessionManager = SharedTokenSessionManager(tokenHolder)
        sessionManager.saveSession(session(token = OLD_ACCESS_TOKEN), REFRESH_TOKEN)
        // 缝内消化后，「成功码但缺 access token」是**请求没成立**那一档（结论未定，不是服务端
        // 拒绝；折信封那一步的读数在 lib_ebook_api 的 UserFacesDigestTest）。本层对两档的处置
        // 一字不差：都回 null、都不轮换
        val dataSource = CountingSessionRefresh {
            TokenRefresh.NotEstablished(reason = "响应缺少 access token")
        }
        val refresher = SessionTokenRefresher(sessionManager, tokenHolder, Lazy { dataSource })

        assertNull(refresher.refresh(OLD_ACCESS_TOKEN))

        assertTrue(
            "缺 access token 应作为「请求没成立」那一档送达本层，实际是 ${dataSource.outcomes}",
            dataSource.outcomes.singleOrNull() is TokenRefresh.NotEstablished,
        )
        assertTrue(sessionManager.rotatedAccessTokens.isEmpty())
    }

    @Test
    fun `未登录无 refresh token 时不发起任何请求直接返回 null`() = runTest {
        val tokenHolder = TokenHolder()
        val sessionManager = SharedTokenSessionManager(tokenHolder)
        val dataSource = CountingSessionRefresh()
        val refresher = SessionTokenRefresher(sessionManager, tokenHolder, Lazy { dataSource })

        assertNull(refresher.refresh(null))

        assertEquals(0, dataSource.refreshCallCount)
        assertTrue(sessionManager.rotatedAccessTokens.isEmpty())
    }

    @Test
    fun `刷新接口抛普通异常时收敛为 null 而不外抛`() = runTest {
        val tokenHolder = TokenHolder()
        val sessionManager = SharedTokenSessionManager(tokenHolder)
        sessionManager.saveSession(session(token = OLD_ACCESS_TOKEN), REFRESH_TOKEN)
        val dataSource = CountingSessionRefresh { throw IOException("connection reset") }
        val refresher = SessionTokenRefresher(sessionManager, tokenHolder, Lazy { dataSource })

        // 普通异常仍归「刷新失败」：返回 null，由上层决定是否发会话过期事件
        assertNull(refresher.refresh(OLD_ACCESS_TOKEN))
    }

    // ===== (d) 刷新面两条出口（ADR-0059：信封收在缝内之后，出口仍要直接钉住） =====

    /**
     * A0230 → 刷新成功 → 原请求重放**一次**即止。
     *
     * 重放仍拿到 A0230 时不再刷第二次、也不再重放，而是把那一码透传成普通业务失败：
     * 刷新成功过，所以**不发**会话过期事件（发了会把还在用的会话误踢成过期）。
     * 这条出口原先只能靠读 CoroutineAdapter 的代码确认；接口重切后仍在生产装配上直断。
     */
    @Test
    fun `刷新成功后原请求只重放一次即止且不误发会话过期`() = runTest {
        val tokenHolder = TokenHolder()
        val sessionManager = SharedTokenSessionManager(tokenHolder)
        sessionManager.saveSession(session(token = OLD_ACCESS_TOKEN), REFRESH_TOKEN)
        val dataSource = CountingSessionRefresh()
        val refresher = SessionTokenRefresher(sessionManager, tokenHolder, Lazy { dataSource })
        val bus = SessionEventBus()
        val adapter = CoroutineAdapter(refresher, bus, tokenHolder)

        var attempts = 0
        val outcome = adapter.safeApiCall {
            attempts++
            RespDTO<Unit>(ErrorCode.USER_ERROR_A0230.code, "用户登录已过期", null)
        }
        advanceUntilIdle()

        assertEquals("原请求 + 一次重放", 2, attempts)
        assertEquals("重放那一码不该再触发刷新", 1, dataSource.refreshCallCount)
        assertEquals(1, sessionManager.rotatedAccessTokens.size)
        val error = outcome.exceptionOrNull()
        assertTrue("应透传成业务码失败，实际是 $error", error is CoroutineAdapter.ApiException)
        assertEquals(ErrorCode.USER_ERROR_A0230.code, (error as CoroutineAdapter.ApiException).code)
    }

    /**
     * A0230 → 刷新被拒 → **会话过期事件只发一次**，且原请求不再重放。
     *
     * 「只发一次」是单响不变量的网络层那一半：订阅方据此清会话 + 提示 + 跳登录，
     * 发两遍就是同一件事响两遍。刷新被拒时重放也没有意义（token 没换），必须一次都不重放。
     */
    @Test
    fun `刷新被拒时会话过期事件只发一次且不重放`() = runTest {
        val tokenHolder = TokenHolder()
        val sessionManager = SharedTokenSessionManager(tokenHolder)
        sessionManager.saveSession(session(token = OLD_ACCESS_TOKEN), REFRESH_TOKEN)
        val dataSource = CountingSessionRefresh {
            TokenRefresh.Rejected(code = ErrorCode.USER_ERROR_A0230.code, reason = "用户登录已过期")
        }
        val refresher = SessionTokenRefresher(sessionManager, tokenHolder, Lazy { dataSource })
        val bus = SessionEventBus()
        val adapter = CoroutineAdapter(refresher, bus, tokenHolder)
        val events = mutableListOf<SessionEvent>()
        // 用 Unconfined 调度器把订阅**同步**挂上：SessionEventBus 是 replay=0 的 SharedFlow，
        // 发射时若还没有订上去，事件就被丢掉，断言会假绿/假红（这里要的是如实计数）
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            bus.events.collect { events += it }
        }

        var attempts = 0
        val outcome = adapter.safeApiCall {
            attempts++
            RespDTO<Unit>(ErrorCode.USER_ERROR_A0230.code, "用户登录已过期", null)
        }
        advanceUntilIdle()

        assertTrue(
            "应回会话过期标记，调用方据此只记日志，实际是 ${outcome.exceptionOrNull()}",
            outcome.exceptionOrNull() is CoroutineAdapter.SessionExpiredException,
        )
        assertEquals(1, dataSource.refreshCallCount)
        assertEquals("刷新被拒不重放", 1, attempts)
        // 触发这条出口的**必须是**「服务端明确拒绝」那一档，不是「请求没成立」（ADR-0059 分档；
        // 没有这条断言，把假件换成另一档也叫「被拒」测试照样绿，档位就白分了）
        assertTrue(
            "会话过期事件由拒绝档触发，实际送达的是 ${dataSource.outcomes}",
            dataSource.outcomes.singleOrNull() is TokenRefresh.Rejected,
        )
        assertEquals(listOf<SessionEvent>(SessionEvent.SessionExpired), events)
        assertTrue("会话救不回来时不许轮换", sessionManager.rotatedAccessTokens.isEmpty())
    }

    private fun session(token: String) = UserSession(
        userId = USER_ID,
        username = USERNAME,
        nickname = NICKNAME,
        avatar = AVATAR,
        token = token,
    )
}

// ===== 用例常量（文件级私有：假件的默认响应也要用） =====

const val USER_ID = 1001L
const val USERNAME = "login_name"
const val NICKNAME = "展示昵称"
const val AVATAR = "https://example.com/avatar.png"
const val REFRESH_TOKEN = "refresh-old"
const val NEW_REFRESH_TOKEN = "refresh-new"
const val OLD_ACCESS_TOKEN = "access-old"
const val NEW_ACCESS_TOKEN = "access-new"

// ===== 测试假件 =====

/**
 * 与注入对象**共享同一个 [TokenHolder] 实例**的 [UserSessionManager] 假件。
 *
 * 生产实现（`AndroidUserSessionManager` 与 lib_common 的 `TokenHolder`）都是 @Singleton，
 * 两者指向同一实例；已有的 `FakeUserSessionManager` 自己 new 了一个 TokenHolder，
 * 用它测守卫会失真——守卫读的永远是没人写入的那个实例。
 */
class SharedTokenSessionManager(
    private val tokenHolder: TokenHolder,
) : UserSessionManager {

    private val _isLoggedIn = MutableStateFlow(false)
    override val isLoggedIn: StateFlow<Boolean> = _isLoggedIn

    private val _currentUser = MutableStateFlow<UserSession?>(null)
    override val currentUser: StateFlow<UserSession?> = _currentUser

    /** 轮换记录：生产语义是 access 进内存、refresh 落盘，这里按序留存供断言 */
    val rotatedAccessTokens = mutableListOf<String>()
    val rotatedRefreshTokens = mutableListOf<String>()

    private var refreshToken: String? = null

    override suspend fun saveSession(session: UserSession, refreshToken: String) {
        _currentUser.value = session
        _isLoggedIn.value = true
        tokenHolder.setToken(session.token)
        this.refreshToken = refreshToken
    }

    override suspend fun rotateCredentials(accessToken: String, refreshToken: String) {
        val current = _currentUser.value ?: return
        _currentUser.value = current.copy(token = accessToken)
        tokenHolder.setToken(accessToken)
        this.refreshToken = refreshToken
        rotatedAccessTokens += accessToken
        rotatedRefreshTokens += refreshToken
    }

    override fun clearSession(): Boolean {
        _currentUser.value = null
        _isLoggedIn.value = false
        tokenHolder.clear()
        refreshToken = null
        return true
    }

    override suspend fun endSession() {
        clearSession()
    }

    override fun getToken(): String? = tokenHolder.token

    override fun getRefreshToken(): String? = if (!_isLoggedIn.value) null else refreshToken
}

/**
 * 会话刷新面 [SessionRefresh] 的假件：计数真实调用次数，其余什么都不必带——
 * 面收窄到只剩一个方法之后，原先那九个 `error("unused in this test")` 的占位实现整批消失
 * （ADR-0059：宽接口让测试替身也只能按整张面接）。
 *
 * [delayMillis] 提供挂起点，保证并发调用真的在 [SessionTokenRefresher] 的 Mutex 上排队
 * （没有挂起点的话，首个调用会在让出线程前一路跑完，守卫就测不出来）。
 *
 * [outcomes] 留存本层实际收到的那一档结论：失败分成两档之后，用例必须能说清自己钉的是哪一档
 * （否则把假件换成另一档、用例名仍写「被拒」，测试照样绿）。
 */
class CountingSessionRefresh(
    private val delayMillis: Long = 100L,
    private val response: () -> TokenRefresh = {
        TokenRefresh.Rotated(accessToken = NEW_ACCESS_TOKEN, refreshToken = NEW_REFRESH_TOKEN)
    },
) : SessionRefresh {

    var refreshCallCount = 0
        private set

    /** 记录每次真实请求携带的 refresh token，用于验证没拿错凭据 */
    val requestedRefreshTokens = mutableListOf<String>()

    /** 记录刷新面交回本层的结论档位（抛出的用例不收进这里） */
    val outcomes = mutableListOf<TokenRefresh>()

    override suspend fun refresh(refreshToken: RefreshTokenRequest): TokenRefresh {
        refreshCallCount++
        requestedRefreshTokens += refreshToken.refreshToken
        delay(delayMillis)
        return response().also { outcomes += it }
    }
}
