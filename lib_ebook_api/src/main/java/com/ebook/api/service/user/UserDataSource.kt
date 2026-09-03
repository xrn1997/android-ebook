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
import okhttp3.MultipartBody

interface UserDataSource {

    //登录（邮箱 + 密码）
    suspend fun login(request: LoginRequest): RespDTO<LoginDTO>

    //注册发码（按邮箱发 6 位验证码，注册专用端点）
    suspend fun sendRegisterCode(request: SendCodeRequest): RespDTO<Unit>

    //注册（邮箱 + 验证码 + 密码；注册即激活、不发 token）
    suspend fun register(request: RegisterRequest): RespDTO<Unit>

    //刷新token
    suspend fun refreshToken(refreshToken: RefreshTokenRequest): RespDTO<LoginDTO>

    //登出
    suspend fun logout(): RespDTO<Unit>

    //已登录改密
    suspend fun modifyPwd(request: ModifyPwdRequest): RespDTO<Unit>

    //发送邮箱验证码
    suspend fun sendForgotPasswordCode(request: SendCodeRequest): RespDTO<Unit>

    //验证码重置密码
    suspend fun resetPassword(request: ResetPasswordRequest): RespDTO<Unit>

    //更新当前用户信息（昵称/头像/邮箱部分更新）
    suspend fun updateMe(request: UpdateUserRequest): RespDTO<User>

    //上传头像文件（multipart，字段名 avatar），返回可访问 URL
    suspend fun uploadAvatar(file: MultipartBody.Part): RespDTO<UploadResponse>
}
