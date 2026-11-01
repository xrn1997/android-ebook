package com.ebook.api.service.user

import com.ebook.api.entity.LoginDTO
import com.ebook.api.utils.CoroutineAdapter
import com.xrn1997.common.constant.ErrorCode
import com.xrn1997.common.dto.RespDTO
import com.xrn1997.common.util.Logger

/**
 * 账号面唯一的**信封消化**出口（ADR-0059）：`RespDTO` 只在本文件与 [UserNetwork] /
 * [UserNetworkTest] 的方法体里出现，一步都不许外流到调用方。
 *
 * 消化分三段，顺序不可倒：
 *
 * 1. [CoroutineAdapter.safeApiCall] 已经把「业务码非成功 → `ApiException`（携带服务端
 *    文案）」「A0230 → 单飞静默刷新 + 重放一次」「传输异常 → 翻译后的异常」都折成
 *   `Result`，所以这里**不再判码**；`CancellationException` 由那一层原样上抛，不折成失败。
 * 2. 掏 `data`：成功码却没有载荷是一档独立的失败（[MissingPayloadException]），
 *    不能与服务端明确报出来的业务码混成一句话——前者是「服务端说成功了但没给东西」，
 *    排查方向是契约/序列化，后者是用户真的做错了什么。
 * 3. 成功码 + `data: null` 对**回执型端点**（`RespDTO<Unit>`：登出、发码、注册…）是
 *   正常形态（服务端本来就 `data: null`），所以 [acknowledged] 把 null 收成 `Unit`；
 *   只有回载荷的端点才走 [concluding]。**两者不许互换**：把回执也当缺载荷失败会让
 *    每个 `data: null` 的成功端点永远报错，把载荷端点当回执则会把「没返回用户」装成成功。
 *
 * 会话刷新面另走 [toTokenRefresh]：它不经 `safeApiCall`（失败自触发刷新会死循环）。
 */

/** 回载荷端点的消化：成功码 → 载荷本身；`data` 为空（或 [extract] 挑不出值）→ 缺载荷。 */
internal suspend fun <T : Any, R : Any> CoroutineAdapter.concluding(
    what: String,
    apiCall: suspend () -> RespDTO<T>,
    extract: (T) -> R?,
): Result<R> = safeApiCall(apiCall).mapCatching { resp ->
    resp.data?.let(extract) ?: throw MissingPayloadException("服务端未返回$what")
}

/** [concluding] 的直接取载荷形态（`data` 本身就是结论）。 */
internal suspend fun <T : Any> CoroutineAdapter.concluding(
    what: String,
    apiCall: suspend () -> RespDTO<T>,
): Result<T> = concluding(what, apiCall) { it }

/** 回执型端点的消化（`RespDTO<Unit>`）：成功即 `Unit`，`data: null` 是正常形态。 */
internal suspend fun CoroutineAdapter.acknowledged(
    apiCall: suspend () -> RespDTO<Unit>,
): Result<Unit> = safeApiCall(apiCall).mapCatching { it.data ?: Unit }

/**
 * 刷新信封 → [TokenRefresh]：成功码且有 access token 才是 [TokenRefresh.Rotated]；
 * 业务码非成功是 [TokenRefresh.Rejected]（服务端明确拒绝续期）；成功码却没有 access token
 * 是 [TokenRefresh.NotEstablished]（本次没能把请求成立）。
 *
 * 后两者的判据是**服务端有没有把「不给续」作为答复内容说出来**：说了才是拒绝；说成功了却没给
 * 必需字段是刷新端的「载荷缺失」（结论未定），不许冒充拒绝——`CONTEXT.md`「续期结论」的
 * `_Avoid_` 与 ADR-0059 钉的就是这条。两档对 `SessionTokenRefresher` 是同一个处置
 * （没有新凭证可用），分档只服务归因与日志，不改变行为。
 * `refresh_token` 缺省不算失败——[TokenRefresh.Rotated.refreshToken] 留 null，
 * 「保留旧凭据」的处置归 `SessionTokenRefresher`（写空串会抹掉唯一可恢复的凭据）。
 */
internal fun RespDTO<LoginDTO>.toTokenRefresh(): TokenRefresh {
    if (code != ErrorCode.SUCCESS.code) {
        Logger.w(TAG, "刷新被服务端拒绝：code=$code, error=$error")
        return TokenRefresh.Rejected(code = code, reason = error)
    }
    val accessToken = data?.token
    if (accessToken.isNullOrEmpty()) {
        Logger.w(TAG, "刷新成功码却没有 access token：code=$code, error=$error")
        return TokenRefresh.NotEstablished(reason = error.ifEmpty { "响应缺少 access token" })
    }
    return TokenRefresh.Rotated(accessToken = accessToken, refreshToken = data?.refreshToken)
}

private const val TAG = "UserEnvelope"
