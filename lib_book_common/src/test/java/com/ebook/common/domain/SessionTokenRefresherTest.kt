package com.ebook.common.domain

import com.ebook.api.entity.LoginDTO
import com.ebook.api.entity.LoginRequest
import com.ebook.api.entity.ModifyPwdRequest
import com.ebook.api.entity.RefreshTokenRequest
import com.ebook.api.entity.RegisterRequest
import com.ebook.api.entity.ResetPasswordRequest
import com.ebook.api.entity.SendCodeRequest
import com.ebook.api.entity.UpdateUserRequest
import com.ebook.api.entity.UploadResponse
import com.ebook.api.entity.User
import com.ebook.api.service.user.UserDataSource
import com.xrn1997.common.constant.ErrorCode
import com.xrn1997.common.di.TokenHolder
import com.xrn1997.common.dto.RespDTO
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import okhttp3.MultipartBody
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
 * 边界说明：日志与会话过期事件（`SessionEventBus`）不属本层职责（由 lib_ebook_api 的
 * `CoroutineAdapter` 发），本测试不覆盖，也不为此引入 Robolectric/Android 依赖。
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
        val dataSource = CountingUserDataSource()
        val refresher = SessionTokenRefresher(sessionManager, tokenHolder, dataSource)

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
        val dataSource = CountingUserDataSource()
        val refresher = SessionTokenRefresher(sessionManager, tokenHolder, dataSource)

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
        val dataSource = CountingUserDataSource()
        val refresher = SessionTokenRefresher(sessionManager, tokenHolder, dataSource)

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
        val dataSource = CountingUserDataSource {
            RespDTO(ErrorCode.SUCCESS.code, "", LoginDTO(token = NEW_ACCESS_TOKEN, refreshToken = null))
        }
        val refresher = SessionTokenRefresher(sessionManager, tokenHolder, dataSource)

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
        val dataSource = CountingUserDataSource {
            RespDTO(ErrorCode.SUCCESS.code, "", LoginDTO(token = NEW_ACCESS_TOKEN, refreshToken = ""))
        }
        val refresher = SessionTokenRefresher(sessionManager, tokenHolder, dataSource)

        refresher.refresh(OLD_ACCESS_TOKEN)

        assertEquals(listOf(REFRESH_TOKEN), sessionManager.rotatedRefreshTokens)
        assertEquals(REFRESH_TOKEN, sessionManager.getRefreshToken())
    }

    @Test
    fun `刷新响应带新 refresh_token 时轮换为新值且不动身份字段`() = runTest {
        val tokenHolder = TokenHolder()
        val sessionManager = SharedTokenSessionManager(tokenHolder)
        sessionManager.saveSession(session(token = OLD_ACCESS_TOKEN), REFRESH_TOKEN)
        val dataSource = CountingUserDataSource()
        val refresher = SessionTokenRefresher(sessionManager, tokenHolder, dataSource)

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
        val dataSource = CountingUserDataSource { throw CancellationException("调用方已取消") }
        val refresher = SessionTokenRefresher(sessionManager, tokenHolder, dataSource)

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
        val dataSource = CountingUserDataSource { RespDTO("A0230", "用户登录已过期", null) }
        val refresher = SessionTokenRefresher(sessionManager, tokenHolder, dataSource)

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
        val dataSource = CountingUserDataSource {
            RespDTO(ErrorCode.SUCCESS.code, "", LoginDTO(token = null, refreshToken = NEW_REFRESH_TOKEN))
        }
        val refresher = SessionTokenRefresher(sessionManager, tokenHolder, dataSource)

        assertNull(refresher.refresh(OLD_ACCESS_TOKEN))

        assertTrue(sessionManager.rotatedAccessTokens.isEmpty())
    }

    @Test
    fun `未登录无 refresh token 时不发起任何请求直接返回 null`() = runTest {
        val tokenHolder = TokenHolder()
        val sessionManager = SharedTokenSessionManager(tokenHolder)
        val dataSource = CountingUserDataSource()
        val refresher = SessionTokenRefresher(sessionManager, tokenHolder, dataSource)

        assertNull(refresher.refresh(null))

        assertEquals(0, dataSource.refreshCallCount)
        assertTrue(sessionManager.rotatedAccessTokens.isEmpty())
    }

    @Test
    fun `刷新接口抛普通异常时收敛为 null 而不外抛`() = runTest {
        val tokenHolder = TokenHolder()
        val sessionManager = SharedTokenSessionManager(tokenHolder)
        sessionManager.saveSession(session(token = OLD_ACCESS_TOKEN), REFRESH_TOKEN)
        val dataSource = CountingUserDataSource { throw IOException("connection reset") }
        val refresher = SessionTokenRefresher(sessionManager, tokenHolder, dataSource)

        // 普通异常仍归「刷新失败」：返回 null，由上层决定是否发会话过期事件
        assertNull(refresher.refresh(OLD_ACCESS_TOKEN))
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

private const val USER_ID = 1001L
private const val USERNAME = "login_name"
private const val NICKNAME = "展示昵称"
private const val AVATAR = "https://example.com/avatar.png"
private const val REFRESH_TOKEN = "refresh-old"
private const val NEW_REFRESH_TOKEN = "refresh-new"
private const val OLD_ACCESS_TOKEN = "access-old"
private const val NEW_ACCESS_TOKEN = "access-new"

// ===== 测试假件 =====

/**
 * 与注入对象**共享同一个 [TokenHolder] 实例**的 [UserSessionManager] 假件。
 *
 * 生产实现（`AndroidUserSessionManager` 与 lib_common 的 `TokenHolder`）都是 @Singleton，
 * 两者指向同一实例；已有的 `FakeUserSessionManager` 自己 new 了一个 TokenHolder，
 * 用它测守卫会失真——守卫读的永远是没人写入的那个实例。
 */
private class SharedTokenSessionManager(
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

    override fun clearSession() {
        _currentUser.value = null
        _isLoggedIn.value = false
        tokenHolder.clear()
        refreshToken = null
    }

    override fun getToken(): String? = tokenHolder.token

    override fun getRefreshToken(): String? = if (!_isLoggedIn.value) null else refreshToken
}

/**
 * 只关心 `refreshToken` 的 [UserDataSource] 假件：计数真实调用次数，其余方法未被用到。
 *
 * [delayMillis] 提供挂起点，保证并发调用真的在 [SessionTokenRefresher] 的 Mutex 上排队
 * （没有挂起点的话，首个调用会在让出线程前一路跑完，守卫就测不出来）。
 */
private class CountingUserDataSource(
    private val delayMillis: Long = 100L,
    private val response: () -> RespDTO<LoginDTO> = {
        RespDTO(
            ErrorCode.SUCCESS.code,
            "",
            LoginDTO(token = NEW_ACCESS_TOKEN, refreshToken = NEW_REFRESH_TOKEN),
        )
    },
) : UserDataSource {

    var refreshCallCount = 0
        private set

    /** 记录每次真实请求携带的 refresh token，用于验证没拿错凭据 */
    val requestedRefreshTokens = mutableListOf<String>()

    override suspend fun refreshToken(refreshToken: RefreshTokenRequest): RespDTO<LoginDTO> {
        refreshCallCount++
        requestedRefreshTokens += refreshToken.refreshToken
        delay(delayMillis)
        return response()
    }

    override suspend fun login(request: LoginRequest): RespDTO<LoginDTO> = error("unused in this test")

    override suspend fun sendRegisterCode(request: SendCodeRequest): RespDTO<Unit> = error("unused in this test")

    override suspend fun register(request: RegisterRequest): RespDTO<Unit> = error("unused in this test")

    override suspend fun logout(): RespDTO<Unit> = error("unused in this test")

    override suspend fun modifyPwd(request: ModifyPwdRequest): RespDTO<Unit> = error("unused in this test")

    override suspend fun sendForgotPasswordCode(request: SendCodeRequest): RespDTO<Unit> =
        error("unused in this test")

    override suspend fun resetPassword(request: ResetPasswordRequest): RespDTO<Unit> = error("unused in this test")

    override suspend fun updateMe(request: UpdateUserRequest): RespDTO<User> = error("unused in this test")

    override suspend fun uploadAvatar(file: MultipartBody.Part): RespDTO<UploadResponse> = error("unused in this test")
}
