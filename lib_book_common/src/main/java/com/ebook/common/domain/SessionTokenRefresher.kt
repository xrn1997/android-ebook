package com.ebook.common.domain

import com.ebook.api.auth.TokenRefresher
import com.ebook.api.entity.RefreshTokenRequest
import com.ebook.api.service.user.UserDataSource
import com.xrn1997.common.constant.ErrorCode
import com.xrn1997.common.di.TokenHolder
import com.xrn1997.common.util.Logger
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * [TokenRefresher] 的实现：双 token 静默刷新（对齐服务端刷新契约：`POST /api/auth/refresh`
 * 只返回新双 token、不含用户资料）。
 *
 * 设计要点：
 * - **单飞互斥**：[Mutex] 串行化刷新；进锁后先比对「触发过期时的 token」与
 *   [TokenHolder] 当前 token——不同则说明并发请求已完成刷新，直接复用。
 *   复用判定**不要求触发 token 非空**：access token 只驻内存、冷启动恒为空
 *   （见 ADR-0011），而并发 A0230 恰恰只发生在冷启动这条路上，要求非空会让守卫
 *   在最需要它的场景里永不生效，退化成 N 次串行刷新。
 *   串行化本身也是正确性屏障：服务端刷新时硬删除旧 refresh（旧值立即失效），
 *   两个请求并发用同一份 refresh 去刷会让后者直接失败。
 * - **不套 CoroutineAdapter**：直接调 [UserDataSource.refreshToken] 拿裸 [com.xrn1997.common.dto.RespDTO]，
 *   不经 `safeApiCall`——否则刷新自身失败（A0230）会再次触发刷新，形成死循环。
 *   仍走 DataSource 而不是自建 Retrofit：一是基址拼接不在这里重复一份，二是
 *   独立调试宿主的 mock 绑定（UserNetworkTest）才覆盖得到这条链路。
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
    private val userDataSource: UserDataSource
) : TokenRefresher {

    private val mutex = Mutex()

    override suspend fun refresh(expiredAccessToken: String?): String? {
        return mutex.withLock {
            // 进锁后看到的 token 已不是触发过期的那个 → 别的请求已完成刷新，直接复用。
            // expiredAccessToken 为 null（冷启动无 access token）时同样成立：
            // 只要当前有 token 且它与触发值不同，就是刚被刷出来的
            val current = tokenHolder.token
            if (!current.isNullOrEmpty() && current != expiredAccessToken) {
                return@withLock current
            }

            val refreshToken = userSessionManager.getRefreshToken()
            if (refreshToken.isNullOrEmpty()) {
                // 未登录或无 refresh token：会话本就不可恢复
                return@withLock null
            }

            try {
                val resp = userDataSource.refreshToken(RefreshTokenRequest(refreshToken))
                val data = if (resp.code == ErrorCode.SUCCESS.code) resp.data else null
                val newToken = data?.token
                if (newToken.isNullOrEmpty()) {
                    Logger.w(TAG, "静默刷新被服务端拒绝或缺失 access token：code=${resp.code}, error=${resp.error}")
                    return@withLock null
                }
                // 轮换：只更双 token（rotateCredentials 不触碰身份字段）。refresh 端点
                // 契约不再返回 user（见 ADR-0011），身份在登录时保存，此处不重建会话。
                // 响应缺 refresh_token 时保留旧值而不是写空串：清空会抹掉唯一可恢复的
                // 凭据，症状是用户莫名被踢下线且只能重新登录，极难归因。
                val rotatedRefreshToken = data.refreshToken?.takeIf { it.isNotEmpty() } ?: refreshToken
                userSessionManager.rotateCredentials(newToken, rotatedRefreshToken)
                Logger.d(TAG, "静默刷新成功，access token 已轮换")
                newToken
            } catch (e: CancellationException) {
                // 取消不是刷新失败：原样上抛。在这里吞成 null 会被上层判成
                // 「会话救不回来」而发 SessionExpired，把用户踢到登录页
                throw e
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
