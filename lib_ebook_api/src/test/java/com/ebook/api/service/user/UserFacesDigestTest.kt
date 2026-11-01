package com.ebook.api.service.user

import com.ebook.api.auth.SessionEventBus
import com.ebook.api.auth.TokenRefresher
import com.ebook.api.entity.LoginDTO
import com.ebook.api.entity.UploadResponse
import com.ebook.api.utils.CoroutineAdapter
import com.xrn1997.common.constant.ErrorCode
import com.xrn1997.common.di.TokenHolder
import com.xrn1997.common.dto.RespDTO
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 账号面**信封消化**的读数（ADR-0059）：业务码在缝内翻成什么、`data` 为空算不算失败。
 *
 * 这些读数原先不存在——「拆信封」散在两个模块的十个调用点里，谁也钉不住整体；收进缝内
 * 之后一处实现配一组读数。三档各要成立，不许互相冒充：
 *
 * - 成功码 + 载荷 → 结论就是载荷本身；
 * - 非成功码 → [CoroutineAdapter.ApiException]，**服务端文案与五位码都留着**（文案上屏、
 *   码供排查，见 ADR-0007），不能折成「请求失败」这种查不出根因的话；
 * - 成功码但 `data` 缺失 → [MissingPayloadException]，与上一档**不合并**：那是「服务端说
 *   成功了却没给东西」，排查方向是契约/序列化，不是用户做错了什么。
 *
 * 回执型端点（`RespDTO<Unit>`）的 `data` 为空是**正常形态**（服务端就这么出货），所以它走
 * [acknowledged] 折成 `Unit`。两条兜底方向弄反的症状都不闪退：把回执当缺载荷会让每个
 * `data: null` 的成功端点永远报错；把载荷端点当回执会让页面拿到 null 还当成功。
 */
class UserFacesDigestTest {

    private val adapter = CoroutineAdapter(
        // 本组用例不经 A0230（那两条出口的读数在 SessionTokenRefresherTest 的 (d) 组），
        // 刷新器在这里只要如实报「救不回来」，把断言钉在消化上
        tokenRefresher = UnrecoverableRefresher,
        sessionEventBus = SessionEventBus(),
        tokenHolder = TokenHolder(),
    )

    // ===== 载荷面：三档 =====

    @Test
    fun `成功码带载荷时结论就是载荷本身，信封不外流`() = runTest {
        val outcome = adapter.concluding("登录会话") {
            RespDTO(ErrorCode.SUCCESS.code, "", LoginDTO(token = NEW_TOKEN))
        }

        assertEquals(NEW_TOKEN, outcome.getOrNull()?.token)
    }

    @Test
    fun `非成功码时失败并留着服务端文案与五位码`() = runTest {
        val outcome = adapter.concluding("登录会话") {
            RespDTO<LoginDTO>(ErrorCode.USER_ERROR_A0210.code, "旧密码错误", null)
        }

        val error = outcome.exceptionOrNull()
        assertTrue("业务码应翻成 ApiException，实际是 $error", error is CoroutineAdapter.ApiException)
        assertEquals("旧密码错误", error?.message)
        assertEquals(ErrorCode.USER_ERROR_A0210.code, (error as CoroutineAdapter.ApiException).code)
    }

    @Test
    fun `成功码但载荷缺失时折成缺载荷失败而不是服务端报错`() = runTest {
        val outcome = adapter.concluding("登录会话") {
            RespDTO<LoginDTO>(ErrorCode.SUCCESS.code, "", null)
        }

        assertTrue(
            "缺载荷必须是可分档的 MissingPayloadException，实际是 ${outcome.exceptionOrNull()}",
            outcome.exceptionOrNull() is MissingPayloadException,
        )
    }

    @Test
    fun `载荷里必要的字段为空串同样按缺载荷处置`() = runTest {
        // 上传契约：成功码 + url="" 等于「没给可访问地址」，不能让页面拿空 URL 去更新头像
        val outcome = adapter.concluding("头像地址", {
            RespDTO(ErrorCode.SUCCESS.code, "", UploadResponse(url = ""))
        }) { upload -> upload.url.takeIf { it.isNotEmpty() } }

        assertTrue(outcome.exceptionOrNull() is MissingPayloadException)
    }

    // ===== 回执面：data=null 的兜底方向相反 =====

    @Test
    fun `回执型端点的空 data 是正常形态，折成成功而不是缺载荷`() = runTest {
        val outcome = adapter.acknowledged { RespDTO(ErrorCode.SUCCESS.code, "", null) }

        assertEquals(Unit, outcome.getOrNull())
    }

    @Test
    fun `回执型端点的非成功码仍然失败`() = runTest {
        val outcome = adapter.acknowledged {
            RespDTO(ErrorCode.USER_ERROR_A0241.code, "发送过于频繁", null)
        }

        assertEquals("发送过于频繁", outcome.exceptionOrNull()?.message)
    }

    // ===== 缺载荷那一档的说法归调用方 =====

    @Test
    fun `缺载荷可换成页面文案，服务端文案不被顺手盖掉`() = runTest {
        val missing = adapter.concluding("登录会话") { RespDTO<LoginDTO>(ErrorCode.SUCCESS.code, "", null) }
            .missingPayloadAs("登录失败：返回数据为空")
        assertEquals("登录失败：返回数据为空", missing.exceptionOrNull()?.message)
        // 换完仍是可分档的那一档，页面日后按类型分支不必猜字符串
        assertTrue(missing.exceptionOrNull() is MissingPayloadException)

        val rejected = adapter.concluding("登录会话") {
            RespDTO<LoginDTO>(ErrorCode.USER_ERROR_A0201.code, "用户账户不存在", null)
        }.missingPayloadAs("登录失败：返回数据为空")
        assertEquals("用户账户不存在", rejected.exceptionOrNull()?.message)
    }

    // ===== 会话刷新面：一份轮换 + 两档失败（成因不合并） =====

    @Test
    fun `刷新面成功码带 access token 时结论是已轮换`() {
        val outcome = RespDTO(
            ErrorCode.SUCCESS.code,
            "",
            LoginDTO(token = NEW_TOKEN, refreshToken = NEW_REFRESH_TOKEN),
        ).toTokenRefresh()

        val rotated = outcome as? TokenRefresh.Rotated
        assertNotNull("成功码带 token 应判为 Rotated，实际是 $outcome", rotated)
        assertEquals(NEW_TOKEN, rotated?.accessToken)
        assertEquals(NEW_REFRESH_TOKEN, rotated?.refreshToken)
    }

    @Test
    fun `刷新面缺 refresh_token 仍是已轮换，旧凭据留给调用方保留`() {
        // 面不替调用方补旧值：写空串会抹掉唯一可恢复的凭据，但那是持久层的决定（ADR-0011）
        val outcome = RespDTO(ErrorCode.SUCCESS.code, "", LoginDTO(token = NEW_TOKEN)).toTokenRefresh()

        assertEquals(TokenRefresh.Rotated(NEW_TOKEN, null), outcome)
    }

    @Test
    fun `刷新面非成功码折成被拒并留着业务码`() {
        val outcome = RespDTO<LoginDTO>(ErrorCode.USER_ERROR_A0230.code, "用户登录已过期", null)
            .toTokenRefresh()

        // 拒绝档的判据只有一条：业务码非成功（服务端把「不给续」说出来了）
        assertTrue(
            "业务码非成功必须是「服务端明确拒绝」那一档，实际是 $outcome",
            outcome is TokenRefresh.Rejected,
        )
        assertEquals(TokenRefresh.Rejected(ErrorCode.USER_ERROR_A0230.code, "用户登录已过期"), outcome)
    }

    @Test
    fun `刷新面成功码却缺 access token 折成请求没成立，不冒充服务端拒绝`() {
        val outcome = RespDTO(
            ErrorCode.SUCCESS.code,
            "",
            LoginDTO(token = null, refreshToken = NEW_REFRESH_TOKEN),
        ).toTokenRefresh()

        // 服务端没说「不给续」，只是说成功了却没给必需字段——结论未定，不许读成拒绝：
        // 两条档的排查方向相反（一份查凭据，一份查契约/序列化）
        assertTrue(
            "缺 access token 不是轮换成功也不是服务端拒绝，实际是 $outcome",
            outcome is TokenRefresh.NotEstablished,
        )
        assertEquals(TokenRefresh.NotEstablished("响应缺少 access token"), outcome)
    }

    private companion object {
        const val NEW_TOKEN = "access-new"
        const val NEW_REFRESH_TOKEN = "refresh-new"
    }

    /** 一律回答「会话救不回来」的刷新器：把本组用例钉在消化上，不带入 A0230 的重放分支。 */
    private object UnrecoverableRefresher : TokenRefresher {
        override suspend fun refresh(expiredAccessToken: String?): String? = null
    }
}
