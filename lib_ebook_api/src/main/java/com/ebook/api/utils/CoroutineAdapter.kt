package com.ebook.api.utils

import com.ebook.api.auth.SessionEvent
import com.ebook.api.auth.SessionEventBus
import com.ebook.api.auth.TokenRefresher
import com.xrn1997.common.constant.ErrorCode
import com.xrn1997.common.di.TokenHolder
import com.xrn1997.common.dto.RespDTO
import com.xrn1997.common.http.ExceptionHandler.handleException
import com.xrn1997.common.util.Logger
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 通用网络请求适配器：所有仓库层 API 调用的统一收口。
 *
 * 职责：
 * - 请求切 IO 线程，并把 [RespDTO] 业务码翻译为 [Result]；
 * - A0230（access token 过期）静默处置：单飞刷新（[TokenRefresher] 保证并发只刷一次）
 *   → 成功则用新 token 重放原请求一次；刷新失败则发射 [SessionEvent.SessionExpired]
 *   由上层统一处置（清会话 + 跳登录），本层不做逐点分支；
 * - 其余异常走 lib_common 的 [handleException] 翻译。
 *
 * 由 Hilt 注入（原 object 静态形态无法携带刷新器/事件总线依赖）。
 */
@Singleton
class CoroutineAdapter @Inject constructor(
    private val tokenRefresher: TokenRefresher,
    private val sessionEventBus: SessionEventBus,
    private val tokenHolder: TokenHolder
) {

    /**
     * 通用网络请求封装
     * @param apiCall suspend 函数执行网络请求
     * @return Result<RespDTO<T>> 成功或失败
     */
    suspend fun <T> safeApiCall(apiCall: suspend () -> RespDTO<T>): Result<RespDTO<T>> {
        return try {
            // 网络请求在 IO 线程
            val resp = withContext(Dispatchers.IO) { apiCall() }

            // 业务异常处理
            when (resp.code) {
                ErrorCode.SUCCESS.code -> Result.success(resp)
                ErrorCode.USER_ERROR_A0230.code -> handleTokenExpired(apiCall, resp)
                else -> Result.failure(ApiException(resp.code, resp.error))
            }
        } catch (e: CancellationException) {
            // 取消不是失败：原样抛出。这一层若吞掉，内层（handleTokenExpired）对取消的
            // 放行就毫无意义——取消会被翻译成一条笼统的网络异常交给调用方
            throw e
        } catch (e: Exception) {
            // 网络或未知异常处理
            val exception = handleException(e)
            Logger.e(TAG, "网络请求异常", exception)
            Result.failure(exception)
        }
    }

    /**
     * A0230 静默处置：单飞刷新 → 成功重放原请求一次；失败发「会话过期」事件。
     *
     * 重放仅一次：重放结果不再参与刷新判定（再次 A0230 直接透传失败），
     * 避免刷新风暴；重放请求会自动携带刷新后的新 token（AuthInterceptor 从
     * TokenHolder 读取，刷新成功时已由会话管理器同步更新）。
     */
    private suspend fun <T> handleTokenExpired(
        apiCall: suspend () -> RespDTO<T>,
        expiredResp: RespDTO<T>
    ): Result<RespDTO<T>> {
        // 传入触发过期时的 token：若并发请求已完成刷新，刷新器可直接返回新 token 免打接口。
        //
        // 单独兜异常（防御性）：[TokenRefresher] 的契约是「失败返回 null」（当前实现
        // SessionTokenRefresher 内部已 try/catch），但接缝是可替换的：一旦将来换成别的实现
        // 或重构掉内部 catch，非取消异常会冒到 [safeApiCall] 的通用 catch 变成一条笼统失败，
        // **且永远不会发 SessionExpired** —— 用户卡在过期会话里（页面反复报错但不跳登录）。
        // 故在此降级为「刷新失败」，保证事件出口唯一且总能发出。
        // 取消必须原样上抛（本层与 [safeApiCall] 都放行）：请求被取消（页面销毁/超时）
        // 不是会话过期，吞掉它会误发 SessionExpired、清会话并把用户踢到登录页
        val newToken = try {
            tokenRefresher.refresh(tokenHolder.token)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.e(TAG, "静默刷新抛异常，按刷新失败处置", e)
            null
        }
        if (newToken == null) {
            // 会话救不回来：交由订阅方统一处置（清会话 + 提示 + 跳登录），
            // 本层只打日志；向原调用方返回带标记的失败，调用方据此「只记日志、不再弹 Toast」
            Logger.w(TAG, "会话过期且静默刷新失败，已转交全局处置：code=${expiredResp.code}")
            sessionEventBus.emit(SessionEvent.SessionExpired)
            return Result.failure(SessionExpiredException(expiredResp.code))
        }
        return try {
            val retry = withContext(Dispatchers.IO) { apiCall() }
            if (retry.code == ErrorCode.SUCCESS.code) {
                Result.success(retry)
            } else {
                Result.failure(ApiException(retry.code, retry.error))
            }
        } catch (e: CancellationException) {
            // 刷新已成功、重放时页面被销毁：取消照常上抛，不记为业务失败
            throw e
        } catch (e: Exception) {
            Result.failure(handleException(e))
        }
    }

    /**
     * 简化调用，直接获取 data，如果失败返回 null
     */
    suspend fun <T> callData(apiCall: suspend () -> RespDTO<T>): T? {
        val result = safeApiCall(apiCall)
        return result.getOrNull()?.data
    }

    class ApiException(val code: String, override val message: String) : Exception(message) {
        /**
         * 用户可见的服务端错误消息。
         *
         * 直接返回服务端下发的原始 [message]——此前拼接 "ApiException(code: msg)"
         * 会原样出现在 Toast 里（内部类名泄漏给用户）。[code] 保留在属性中供日志排查。
         */
        fun message(): String = message
    }

    /**
     * 会话过期且静默刷新失败：**已由全局订阅方统一处置**（清会话 + 提示 + 跳登录页）。
     *
     * 原调用方收到此失败时应「只打日志、不再弹 Toast」，避免与全局「登录过期」提示重复（Q4：事件唯一出口）。
     */
    class SessionExpiredException(val code: String) : Exception("会话过期，已转交全局处置")

    companion object {
        private const val TAG = "CoroutineAdapter"

        /**
         * 是否为「会话过期且已全局处置」的失败标记。
         *
         * 调用方 onFailure 见 [SessionExpiredException] 时只记日志、跳过 Toast。
         */
        fun isSessionExpiredHandled(exception: Throwable): Boolean =
            exception is SessionExpiredException
    }
}
