package com.ebook.api.service.user

import com.ebook.api.RetrofitBuilder
import com.ebook.api.config.API
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
import okhttp3.MultipartBody
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UserNetwork @Inject constructor(
    retrofitBuilder: RetrofitBuilder
) : UserDataSource {
    private val networkApi = retrofitBuilder.getRetrofitObject(
        "http://${API.URL_HOST_USER}:${API.URL_PORT_USER}/"
    ).create(UserService::class.java)

    override suspend fun login(request: LoginRequest): RespDTO<LoginDTO> {
        return networkApi.login(request)
    }

    override suspend fun sendRegisterCode(request: SendCodeRequest): RespDTO<Unit> {
        return networkApi.sendRegisterCode(request)
    }

    override suspend fun register(request: RegisterRequest): RespDTO<Unit> {
        return networkApi.register(request)
    }

    override suspend fun refreshToken(refreshToken: RefreshTokenRequest): RespDTO<LoginDTO> {
        return networkApi.refreshToken(refreshToken)
    }

    override suspend fun logout(): RespDTO<Unit> {
        return networkApi.logout()
    }

    override suspend fun modifyPwd(request: ModifyPwdRequest): RespDTO<Unit> {
        return networkApi.modifyPwd(request)
    }

    override suspend fun sendForgotPasswordCode(request: SendCodeRequest): RespDTO<Unit> {
        return networkApi.sendForgotPasswordCode(request)
    }

    override suspend fun resetPassword(request: ResetPasswordRequest): RespDTO<Unit> {
        return networkApi.resetPassword(request)
    }

    override suspend fun updateMe(request: UpdateUserRequest): RespDTO<User> {
        return networkApi.updateMe(request)
    }

    override suspend fun uploadAvatar(file: MultipartBody.Part): RespDTO<UploadResponse> {
        return networkApi.uploadAvatar(file)
    }
}
