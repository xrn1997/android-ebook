package com.ebook.common.domain

import com.ebook.api.auth.TokenRefresher
import com.ebook.api.entity.RefreshTokenRequest
import com.ebook.api.service.user.SessionRefresh
import com.ebook.api.service.user.TokenRefresh
import com.xrn1997.common.di.TokenHolder
import com.xrn1997.common.util.Logger
import dagger.Lazy
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
 * - **只消费结论，不消费信封**：刷新面 [SessionRefresh.refresh] 回 [TokenRefresh]，失败按成因
 *   分两档（服务端明确拒绝 / 本次请求没成立），业务码与 `data` 空判都在缝内做完（ADR-0059）。
 *   **两档在本层是同一个处置**（回 null），分档只进日志、不改变行为。本类因此**不经**
 *   `CoroutineAdapter`——刷新自身失败（A0230）若再进那条管道会被判成「又过期了」而再刷一次，
 *   死循环（ADR-0010）。仍走窄面而不是自建 Retrofit：一是基址拼接不在这里重复一份，二是
 *   独立调试宿主的 mock 绑定（`UserNetworkTest`）才覆盖得到这条链路。
 * - **轮换持久化**：[TokenRefresh.Rotated] 即 [UserSessionManager.rotateCredentials]——新 access token
 *   只进内存（TokenHolder + 内存态会话），新 refresh token 落盘（服务端旧 refresh 已作废，
 *   必须立即替换，否则下次刷新必失败）；**禁止改回** [UserSessionManager.saveSession]：
 *   那是「建立会话」语义（身份 + 双 token），而 refresh 端点契约已不再返回 user
 *   （见 ADR-0011），复用它会以空身份整段重建会话、抹掉昵称/头像/uid。
 *   结论里 `refreshToken` 为 null 时**保留旧值**而不是写空串：清空会抹掉唯一可恢复的
 *   凭据，症状是用户莫名被踢下线且只能重新登录，极难归因。
 * - **[Lazy] 断开构造环**：刷新面与网络适配器是同一个单例（`UserNetwork` 实现账号四面），
 *   而网络适配器要用 [TokenRefresher] 处置 A0230——构造期直连就成
 *   `UserNetwork → CoroutineAdapter → 本类 → UserNetwork` 的环。推迟到真要刷新时再取
 *   （首次刷新前 [CoroutineAdapter] 必然已装配完），运行时不会自引用。
 *
 * 为什么放在 lib_book_common：刷新同时依赖会话持久化（本层）与刷新端点
 * （lib_ebook_api，下游），依赖方向在本层交汇；接口定义在 lib_ebook_api，
 * 由 SessionModule 经 Hilt @Binds 注入。
 */
@Singleton
class SessionTokenRefresher @Inject constructor(
    private val userSessionManager: UserSessionManager,
    private val tokenHolder: TokenHolder,
    private val sessionRefresh: Lazy<SessionRefresh>
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
                when (val outcome = sessionRefresh.get().refresh(RefreshTokenRequest(refreshToken))) {
                    // 两条失败档对本层是同一个处置（没有新凭证可用 → 回 null，由网络层发
                    // 一次 SessionExpired）；分档只服务于日志归因，不许在这里做出行为差异
                    is TokenRefresh.Rejected -> {
                        // 服务端明确拒绝续期：业务码已在缝内记过日志
                        Logger.w(TAG, "静默刷新被服务端拒绝：${outcome.reason}（code=${outcome.code}）")
                        null
                    }
                    is TokenRefresh.NotEstablished -> {
                        // 本次没能把请求成立：传输/未配置，或成功码却没有 access token
                        Logger.w(TAG, "静默刷新未换来新凭证：${outcome.reason}")
                        null
                    }
                    is TokenRefresh.Rotated -> {
                        // 轮换：只更双 token（rotateCredentials 不触碰身份字段）。refresh 端点
                        // 契约不再返回 user（见 ADR-0011），身份在登录时保存，此处不重建会话。
                        val rotatedRefreshToken = outcome.refreshToken?.takeIf { it.isNotEmpty() } ?: refreshToken
                        userSessionManager.rotateCredentials(outcome.accessToken, rotatedRefreshToken)
                        Logger.d(TAG, "静默刷新成功，access token 已轮换")
                        outcome.accessToken
                    }
                }
            } catch (e: CancellationException) {
                // 取消不是刷新失败：原样上抛。在这里吞成 null 会被上层判成
                // 「会话救不回来」而发 SessionExpired，把用户踢到登录页
                throw e
            } catch (e: Exception) {
                // 窄面的契约是「回结论、不抛」；本层仍兜一手：换实现或接线出错时
                // 不能把异常冒给 CoroutineAdapter（那会绕过 SessionExpired 的发射）
                Logger.w(TAG, "静默刷新失败：${e.message}")
                null
            }
        }
    }

    companion object {
        private const val TAG = "SessionTokenRefresher"
    }
}
