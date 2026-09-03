package com.ebook.common.domain

import com.ebook.api.RetrofitBuilder
import com.ebook.api.auth.TokenRefresher
import com.ebook.api.config.API
import com.ebook.api.entity.RefreshTokenRequest
import com.ebook.api.service.user.UserService
import com.xrn1997.common.constant.ErrorCode
import com.xrn1997.common.di.TokenHolder
import com.xrn1997.common.util.Logger
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * [TokenRefresher] 的实现：双 token 静默刷新（对齐 ebook-server ADR-0001/0002）。
 *
 * 设计要点：
 * - **单飞互斥**：[Mutex] 串行化刷新；进锁后先比对「触发过期时的 token」与
 *   [TokenHolder] 当前 token——不同则说明并发请求已完成刷新，直接复用，
 *   避免 N 个并发过期请求打 N 次刷新接口（服务端轮换语义下旧 refresh 已作废，
 *   重复刷新反而会误伤）；
 * - **不走 CoroutineAdapter**：刷新直接调 [UserService]，否则刷新失败（A0230）
 *   会再次触发刷新，形成死循环；
 * - **轮换持久化**：刷新成功即 [UserSessionManager.rotateCredentials]——新 access token
 *   只进内存（TokenHolder + 内存态会话），新 refresh token 落盘（服务端旧 refresh 已作废，
 *   必须立即替换，否则下次刷新必失败）；**禁止改回** [UserSessionManager.saveSession]：
 *   那是「建立会话」语义（身份 + 双 token），而 refresh 端点契约已不再返回 user
 *   （见 ADR-0011），复用它会以空身份整段重建会话、抹掉昵称/头像/uid。
 *
 * 为什么放在 lib_book_common：刷新同时依赖会话持久化（本层）与刷新端点
 * （lib_ebook_api，下游），依赖方向在本层交汇；接口定义在 lib_ebook_api，
 * 由 SessionModule 经 Hilt @Binds 注入。
 */
@Singleton
class SessionTokenRefresher @Inject constructor(
    private val userSessionManager: UserSessionManager,
    private val tokenHolder: TokenHolder,
    retrofitBuilder: RetrofitBuilder
) : TokenRefresher {

    private val mutex = Mutex()

    /** 刷新专用 Retrofit 服务实例（与业务请求隔离，不经 CoroutineAdapter）。 */
    private val userService: UserService by lazy {
        retrofitBuilder.getRetrofitObject(
            "http://${API.URL_HOST_USER}:${API.URL_PORT_USER}/"
        ).create(UserService::class.java)
    }

    override suspend fun refresh(expiredAccessToken: String?): String? {
        return mutex.withLock {
            // 并发请求已完成刷新：当前 token 已不是触发过期的那个，直接复用
            val current = tokenHolder.token
            if (!expiredAccessToken.isNullOrEmpty() &&
                !current.isNullOrEmpty() &&
                current != expiredAccessToken
            ) {
                return@withLock current
            }

            val refreshToken = userSessionManager.getRefreshToken()
            if (refreshToken.isNullOrEmpty()) {
                // 未登录或无 refresh token：会话本就不可恢复
                return@withLock null
            }

            try {
                val resp = userService.refreshToken(RefreshTokenRequest(refreshToken))
                val data = if (resp.code == ErrorCode.SUCCESS.code) resp.data else null
                val newToken = data?.token
                if (newToken.isNullOrEmpty()) {
                    Logger.w(TAG, "静默刷新被服务端拒绝或缺失 access token：code=${resp.code}, error=${resp.error}")
                    return@withLock null
                }
                // 轮换：只更双 token（rotateCredentials 不触碰身份字段）。refresh 端点
                // 契约不再返回 user（见 ADR-0011），身份在登录时保存，此处不重建会话
                userSessionManager.rotateCredentials(newToken, data.refreshToken.orEmpty())
                Logger.d(TAG, "静默刷新成功，access token 已轮换")
                newToken
            } catch (e: Exception) {
                Logger.w(TAG, "静默刷新失败：${e.message}")
                null
            }
        }
    }

    companion object {
        private const val TAG = "SessionTokenRefresher"
    }
}
