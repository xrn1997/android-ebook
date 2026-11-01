package com.ebook.api.service.user

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
import com.ebook.api.utils.CoroutineAdapter
import com.ebook.api.utils.TestAssetManager
import com.xrn1997.common.constant.ErrorCode
import com.xrn1997.common.dto.RespDTO
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream

/**
 * 账号四面的 mock 实现（类名是历史误称：它服务的是 mock flavor 与独立调试宿主，
 * 不是单元测试；本次不改名也不搬走）。载荷来自 `lib_ebook_api/src/main/assets` 的 JSON，
 * 与服务端线上载荷同形，无后端即可跑通全链路。
 *
 * **信封消化与真实后端共用同一出口**（`UserEnvelope.kt` 的 [concluding] /
 * [acknowledged] / [toTokenRefresh]）：两个实现只差「信封从哪来」，「信封怎么折成结论」
 * 必须是一份，否则 mock flavor 演练的是另一套失败语义。
 *
 * **非成功码可达**：任一文本入参等于 [REJECT_PROBE] 时回放 `user_rejected.json`
 * 而不是成功资产。没有这条开关，mock 构建下「业务码非成功」那一档永远走不到——
 * 失败文案上屏、以及「刷新被拒 → 会话过期 → 跳登录」整条链都得连真后端才验得动。
 */
@Singleton
class UserNetworkTest @Inject constructor(
    private val networkJson: Json,
    private val assets: TestAssetManager,
    private val coroutineAdapter: CoroutineAdapter,
) : AccountSignIn, AccountPassword, AccountProfile, SessionRefresh {

    override suspend fun login(request: LoginRequest): Result<LoginDTO> =
        coroutineAdapter.concluding("登录会话") {
            if (probeRejected(request.email, request.password)) {
                rejectedEnvelope()
            } else {
                getDataFromJsonFile(USER_LOGIN)
            }
        }

    override suspend fun sendRegisterCode(request: SendCodeRequest): Result<Unit> =
        coroutineAdapter.acknowledged {
            if (probeRejected(request.email)) rejectedEnvelope() else getDataFromJsonFile(USER_SEND_CODE)
        }

    override suspend fun register(request: RegisterRequest): Result<Unit> =
        coroutineAdapter.acknowledged {
            if (probeRejected(request.email, request.code, request.password)) {
                rejectedEnvelope()
            } else {
                getDataFromJsonFile(USER_REGISTER)
            }
        }

    override suspend fun logout(): Result<Unit> =
        coroutineAdapter.acknowledged { getDataFromJsonFile(USER_LOGOUT) }

    override suspend fun modifyPwd(request: ModifyPwdRequest): Result<Unit> =
        coroutineAdapter.acknowledged {
            if (probeRejected(request.oldPassword, request.newPassword)) {
                rejectedEnvelope()
            } else {
                getDataFromJsonFile(USER_MODIFY_PWD)
            }
        }

    override suspend fun sendForgotPasswordCode(request: SendCodeRequest): Result<Unit> =
        coroutineAdapter.acknowledged {
            if (probeRejected(request.email)) rejectedEnvelope() else getDataFromJsonFile(USER_SEND_CODE)
        }

    override suspend fun resetPassword(request: ResetPasswordRequest): Result<Unit> =
        coroutineAdapter.acknowledged {
            if (probeRejected(request.email, request.code, request.newPassword)) {
                rejectedEnvelope()
            } else {
                getDataFromJsonFile(USER_RESET_PASSWORD)
            }
        }

    /**
     * 更新当前用户信息：以登录态用户为基础做部分更新（非空即更新），
     * 模拟后端 PUT /api/users/me 的部分更新语义。
     */
    override suspend fun updateMe(request: UpdateUserRequest): Result<User> =
        coroutineAdapter.concluding("更新后的用户资料") {
            if (probeRejected(request.nickname, request.avatar, request.email, request.username)) {
                rejectedEnvelope()
            } else {
                val current = getDataFromJsonFile<LoginDTO>(USER_LOGIN).data?.user ?: User()
                RespDTO(
                    code = ErrorCode.SUCCESS.code,
                    error = "",
                    data = current.copy(
                        nickname = request.nickname ?: current.nickname,
                        image = request.avatar ?: current.image,
                        email = request.email ?: current.email,
                        username = request.username ?: current.username,
                    ),
                )
            }
        }

    /**
     * 上传头像：mock 不真存文件，直接回占位 URL（字节与 multipart 都归传输适配器，
     * 本处只按 [REJECT_PROBE] 这条开关演练非成功码）。
     */
    override suspend fun uploadAvatar(bytes: ByteArray, fileName: String): Result<String> =
        coroutineAdapter.concluding("头像地址", {
            if (probeRejected(fileName)) {
                rejectedEnvelope()
            } else {
                RespDTO(
                    code = ErrorCode.SUCCESS.code,
                    error = "",
                    data = UploadResponse(url = MOCK_AVATAR_URL),
                )
            }
        }) { upload -> upload.url.takeIf { it.isNotEmpty() } }

    /**
     * 会话刷新：与真实实现一样**不经** `safeApiCall`（刷新失败自触发刷新会死循环，
     * ADR-0010），只把裸信封折成分档结论。`refreshToken` 等于 [REJECT_PROBE] 即回放
     * 非成功码，让 mock 构建不接后端也能走「刷新被拒 → SessionExpired → 跳登录」。
     * mock 不打通网络，因此这一面只会回 [TokenRefresh.Rotated] 与
     * [TokenRefresh.Rejected]（服务端明确拒绝那一档）。
     */
    override suspend fun refresh(refreshToken: RefreshTokenRequest): TokenRefresh =
        if (probeRejected(refreshToken.refreshToken)) {
            rejectedEnvelope<LoginDTO>().toTokenRefresh()
        } else {
            getDataFromJsonFile<LoginDTO>(USER_REFRESH_TOKEN).toTokenRefresh()
        }

    /** 非成功码开关：任一文本入参去掉首尾空白后等于 [REJECT_PROBE]（忽略大小写）。 */
    private fun probeRejected(vararg inputs: String?): Boolean =
        inputs.any { it?.trim()?.equals(REJECT_PROBE, ignoreCase = true) == true }

    /**
     * Get data from the given JSON [fileName].
     */
    @OptIn(ExperimentalSerializationApi::class)
    private suspend inline fun <reified T> getDataFromJsonFile(fileName: String): RespDTO<T> =
        withContext(Dispatchers.IO) {
            assets.open(fileName).use { inputStream ->
                networkJson.decodeFromStream(inputStream)
            }
        }

    /** 非成功码信封：`data` 为空，与真实服务端拒绝时的形态一致。 */
    private suspend inline fun <reified T> rejectedEnvelope(): RespDTO<T> =
        getDataFromJsonFile(USER_REJECTED)

    companion object {
        /**
         * mock 专用的「让服务端拒绝这次请求」开关：填进邮箱/密码/验证码/昵称/文件名即可
         * 触发非成功码（仅 mock flavor 与独立调试宿主有此语义，真实后端不看它）。
         */
        const val REJECT_PROBE = "fail"

        /** mock 头像上传回的占位 URL（文件不真实存储）。 */
        const val MOCK_AVATAR_URL = "file:///android_asset/avatar.png"

        private const val USER_LOGIN = "user_login.json"
        private const val USER_REGISTER = "user_register.json"
        private const val USER_REFRESH_TOKEN = "user_refresh_token.json"
        private const val USER_LOGOUT = "user_logout.json"
        private const val USER_MODIFY_PWD = "user_modify_pwd.json"
        private const val USER_SEND_CODE = "user_send_code.json"
        private const val USER_RESET_PASSWORD = "user_reset_password.json"
        private const val USER_REJECTED = "user_rejected.json"
    }
}
