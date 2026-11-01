package com.ebook.api.service.user

import com.ebook.api.auth.SessionEventBus
import com.ebook.api.auth.TokenRefresher
import com.ebook.api.entity.LoginRequest
import com.ebook.api.entity.ModifyPwdRequest
import com.ebook.api.entity.RefreshTokenRequest
import com.ebook.api.entity.ResetPasswordRequest
import com.ebook.api.entity.SendCodeRequest
import com.ebook.api.entity.UpdateUserRequest
import com.ebook.api.utils.CoroutineAdapter
import com.ebook.api.utils.TestAssetManager
import com.xrn1997.common.di.TokenHolder
import com.xrn1997.common.dto.RespDTO
import java.io.File
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [UserNetworkTest]（mock flavor 与独立调试宿主的账号实现）的资产契约测试，纯 JVM。
 *
 * 类名叠 Test 后缀是因为 Subject 本身就带 Test（历史误称，本次不改名），口径同
 * `CommentNetworkTestTest`。
 *
 * 钉住两条契约：
 *
 * 1. **资产形态即解码类型**：mock 按 `RespDTO<载荷>` 读 JSON，改了资产而不同步改解码会让
 *    整条账号链在 mock 构建里静默失效（评论域踩过：`SerializationException` 被吞成
 *    「未知错误」，页面永远加载失败却不闪退）。
 * 2. **非成功码可达**：`user_rejected.json` 必须真能被每一面上报——没有这条开关，mock
 *    构建里「业务码非成功」那一档永远走不到，失败文案上屏与「刷新被拒 → 会话过期 →
 *    跳登录」都得连真后端才验得动（ADR-0059）。开关就是 [UserNetworkTest.REJECT_PROBE]：
 *    把它填进邮箱 / 密码 / 验证码 / 昵称 / 文件名即可（真实后端不看这些值）。
 */
class UserNetworkTestTest {

    private fun dataSource(): UserNetworkTest = UserNetworkTest(
        networkJson = assetJson,
        assets = TestAssetManager { fileName ->
            val file = File(ASSET_DIR, fileName)
            if (!file.isFile) {
                // 工作目录假设一旦被改动，宁可炸在明确的绝对路径上，也不静默跳过契约校验
                throw IllegalStateException("mock 资产未找到：${file.absolutePath}")
            }
            file.inputStream()
        },
        coroutineAdapter = CoroutineAdapter(
            // mock 资产永不回 A0230，这里的刷新器只是 CoroutineAdapter 的必要协作者
            tokenRefresher = NeverRefreshes,
            sessionEventBus = SessionEventBus(),
            tokenHolder = TokenHolder(),
        ),
    )

    // ===== 契约一：资产形态即解码类型（四面都读得到） =====

    @Test
    fun `登录面从 user_login_json 解出双 token 与身份`() = runTest {
        val session = dataSource().login(LoginRequest("test@example.com", "whatever")).getOrThrow()

        assertTrue("token 为空说明解码没取到载荷", session.token?.isNotEmpty() == true)
        assertTrue(session.refreshToken?.isNotEmpty() == true)
        assertEquals("test@example.com", session.user?.email)
    }

    @Test
    fun `刷新面从 user_refresh_token_json 解出已轮换的双 token`() = runTest {
        val outcome = dataSource().refresh(RefreshTokenRequest("refresh-old"))

        val rotated = outcome as? TokenRefresh.Rotated
        assertNotNull("刷新资产应判为已轮换，实际是 $outcome", rotated)
        assertTrue("刷新资产必须带 access token", rotated?.accessToken?.isNotEmpty() == true)
    }

    @Test
    fun `回执型端点在 data 为 null 的成功资产上仍算成功`() = runTest {
        val source = dataSource()

        // user_logout / user_register / user_send_code / user_reset_password 都是 00000 + data:null
        assertEquals(Unit, source.logout().getOrNull())
        assertEquals(Unit, source.sendRegisterCode(SendCodeRequest("a@b.c")).getOrNull())
        assertEquals(Unit, source.modifyPwd(ModifyPwdRequest("old", "new")).getOrNull())
        assertEquals(Unit, source.resetPassword(ResetPasswordRequest("a@b.c", "123456", "new")).getOrNull())
    }

    @Test
    fun `资料面以登录资产为种子做部分更新，回更新后的用户`() = runTest {
        val updated = dataSource().updateMe(UpdateUserRequest(nickname = "新昵称")).getOrThrow()

        assertEquals("新昵称", updated.nickname)
        // 未被本次更新触碰的字段仍来自 user_login.json（mock 的部分更新语义）
        assertTrue(updated.image.isNotEmpty())
    }

    @Test
    fun `上传面回非空 URL，页面才拿得到可访问地址`() = runTest {
        val url = dataSource().uploadAvatar(ByteArray(3), "avatar.jpg").getOrThrow()

        assertTrue("空 URL 会被缝内判成缺载荷，不该走到成功", url.isNotEmpty())
    }

    // ===== 契约二：非成功码可达 =====

    @Test
    fun `拒绝资产是信封形态、带服务端文案且不带载荷`() {
        val envelope: RespDTO<Unit> = assetJson.decodeFromString(
            File(ASSET_DIR, REJECTED_ASSET).readText()
        )

        assertEquals(REJECT_CODE, envelope.code)
        assertTrue("非成功码必须带一句服务端文案（它会直接上屏）", envelope.error.isNotEmpty())
        assertEquals("拒绝时不带载荷", null, envelope.data)
    }

    @Test
    fun `探针开关让载荷面回非成功码而不是成功`() = runTest {
        val outcome = dataSource().login(LoginRequest(UserNetworkTest.REJECT_PROBE, "whatever"))

        val error = outcome.exceptionOrNull()
        assertTrue("应失败并留着服务端文案，实际是 $error", error is CoroutineAdapter.ApiException)
        assertEquals(REJECT_CODE, (error as CoroutineAdapter.ApiException).code)
        assertTrue("探针命中时不许带着载荷冒充成功", outcome.getOrNull() == null)
    }

    @Test
    fun `探针开关让回执面与资料面也回非成功码`() = runTest {
        val source = dataSource()

        assertEquals(
            REJECT_CODE,
            codeOf(source.sendForgotPasswordCode(SendCodeRequest(UserNetworkTest.REJECT_PROBE))),
        )
        assertEquals(
            REJECT_CODE,
            codeOf(source.modifyPwd(ModifyPwdRequest(UserNetworkTest.REJECT_PROBE, "new"))),
        )
        assertEquals(
            REJECT_CODE,
            codeOf(source.updateMe(UpdateUserRequest(nickname = UserNetworkTest.REJECT_PROBE))),
        )
        assertEquals(
            REJECT_CODE,
            codeOf(source.uploadAvatar(ByteArray(3), UserNetworkTest.REJECT_PROBE)),
        )
    }

    @Test
    fun `探针开关让刷新面回被拒而不是已轮换`() = runTest {
        val outcome = dataSource().refresh(RefreshTokenRequest(UserNetworkTest.REJECT_PROBE))

        val rejected = outcome as? TokenRefresh.Rejected
        assertNotNull("刷新被拒必须是可分档的 Rejected，实际是 $outcome", rejected)
        assertEquals(REJECT_CODE, rejected?.code)
    }

    /** 取失败里的业务码：不是 ApiException 的失败返回 null，让断言直接指出走岔了哪一档。 */
    private fun codeOf(outcome: Result<*>): String? =
        (outcome.exceptionOrNull() as? CoroutineAdapter.ApiException)?.code

    private companion object {
        object NeverRefreshes : TokenRefresher {
            override suspend fun refresh(expiredAccessToken: String?): String? = null
        }

        /** 资产解码统一用这一份配置（与 NetworkModule.providesNetworkJson 同关键项） */
        val assetJson = Json { ignoreUnknownKeys = true }

        /** 相对模块目录的资产路径：AGP 单元测试的工作目录是模块目录 */
        const val ASSET_DIR = "src/main/assets"

        const val REJECTED_ASSET = "user_rejected.json"

        /** `user_rejected.json` 里的业务码（A0001 = 用户端错误，宏观档，不冒充具体错） */
        const val REJECT_CODE = "A0001"
    }
}
