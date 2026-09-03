package com.ebook.api.service.user

import com.xrn1997.common.dto.RespDTO
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
import com.ebook.api.utils.TestAssetManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import okhttp3.MultipartBody
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UserNetworkTest @Inject constructor(
    private val networkJson: Json,
    private val assets: TestAssetManager,
) : UserDataSource {

    override suspend fun login(request: LoginRequest): RespDTO<LoginDTO> =
        getDataFromJsonFile(USER_LOGIN)

    override suspend fun sendRegisterCode(request: SendCodeRequest): RespDTO<Unit> =
        getDataFromJsonFile(USER_SEND_CODE)

    override suspend fun register(request: RegisterRequest): RespDTO<Unit> =
        getDataFromJsonFile(USER_REGISTER)

    override suspend fun refreshToken(refreshToken: RefreshTokenRequest): RespDTO<LoginDTO> =
        getDataFromJsonFile(USER_REFRESH_TOKEN)

    override suspend fun logout(): RespDTO<Unit> =
        getDataFromJsonFile(USER_LOGOUT)

    override suspend fun modifyPwd(request: ModifyPwdRequest): RespDTO<Unit> =
        getDataFromJsonFile(USER_MODIFY_PWD)

    override suspend fun sendForgotPasswordCode(request: SendCodeRequest): RespDTO<Unit> =
        getDataFromJsonFile(USER_SEND_CODE)

    override suspend fun resetPassword(request: ResetPasswordRequest): RespDTO<Unit> =
        getDataFromJsonFile(USER_RESET_PASSWORD)

    /**
     * 更新当前用户信息：以登录态用户为基础做部分更新（非空即更新），
     * 模拟后端 PUT /api/users/me 的部分更新语义。
     */
    override suspend fun updateMe(request: UpdateUserRequest): RespDTO<User> {
        val current = getDataFromJsonFile<LoginDTO>(USER_LOGIN).data?.user ?: User()
        val updated = current.copy(
            nickname = request.nickname ?: current.nickname,
            image = request.avatar ?: current.image,
            email = request.email ?: current.email,
            username = request.username ?: current.username
        )
        return RespDTO(code = "00000", error = "", data = updated)
    }

    /**
     * 上传头像：mock 直接返回本地资产占位 URL（文件不真实存储）。
     */
    override suspend fun uploadAvatar(file: MultipartBody.Part): RespDTO<UploadResponse> =
        RespDTO(code = "00000", error = "", data = UploadResponse(url = "file:///android_asset/avatar.png"))

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

    companion object {
        private const val USER_LOGIN = "user_login.json"
        private const val USER_REGISTER = "user_register.json"
        private const val USER_REFRESH_TOKEN = "user_refresh_token.json"
        private const val USER_LOGOUT = "user_logout.json"
        private const val USER_MODIFY_PWD = "user_modify_pwd.json"
        private const val USER_SEND_CODE = "user_send_code.json"
        private const val USER_RESET_PASSWORD = "user_reset_password.json"
    }
}
