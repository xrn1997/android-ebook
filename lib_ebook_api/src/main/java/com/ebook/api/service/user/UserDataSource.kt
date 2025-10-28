package com.ebook.api.service.user

import com.xrn1997.common.dto.RespDTO
import com.ebook.api.entity.LoginDTO
import com.ebook.api.entity.User
import okhttp3.MultipartBody

interface UserDataSource {

    //登录
    suspend fun login(user: User): RespDTO<LoginDTO>

    //注册
    suspend fun register(user: User): RespDTO<LoginDTO>

    //修改密码
    suspend fun modifyPwd(user: User): RespDTO<Int>

    //修改昵称
    suspend fun modifyNickname(token: String?, username: String, nickname: String): RespDTO<Int>

    //修改头像
    suspend fun modifyProfilePhoto(
        token: String?,
        username: String,
        file: MultipartBody.Part
    ): RespDTO<String>
}