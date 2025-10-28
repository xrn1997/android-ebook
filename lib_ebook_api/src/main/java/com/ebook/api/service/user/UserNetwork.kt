package com.ebook.api.service.user

import com.ebook.api.config.API
import com.xrn1997.common.dto.RespDTO
import com.ebook.api.entity.LoginDTO
import com.ebook.api.entity.User
import com.xrn1997.common.manager.RetrofitManager
import okhttp3.MultipartBody
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UserNetwork @Inject constructor() : UserDataSource {
    init {
        // 通过反射动态修改 BaseUrl
        RetrofitManager.mHttpUrl.setHost(API.URL_HOST_USER)
        RetrofitManager.mHttpUrl.setPort(API.URL_PORT_USER)
    }

    private val networkApi = RetrofitManager.create(UserService::class.java)

    override suspend fun login(user: User): RespDTO<LoginDTO> {
        return networkApi.login(user)
    }

    override suspend fun register(user: User): RespDTO<LoginDTO> {
        return networkApi.register(user)
    }

    override suspend fun modifyPwd(user: User): RespDTO<Int> {
        return networkApi.modifyPwd(user)
    }

    override suspend fun modifyNickname(
        token: String?,
        username: String,
        nickname: String
    ): RespDTO<Int> {
        return networkApi.modifyNickname(token, username, nickname)
    }

    override suspend fun modifyProfilePhoto(
        token: String?,
        username: String,
        file: MultipartBody.Part
    ): RespDTO<String> {
        return networkApi.modifyProfilePhoto(token, username, file)
    }
}