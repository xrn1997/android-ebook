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
import retrofit2.http.Body
import retrofit2.http.Headers
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Part

interface UserService {
    //登录（邮箱 + 密码，对齐 ebook-server ADR-0002）
    @POST("/api/auth/login")
    @Headers("Content-Type:application/json;charset=UTF-8")
    suspend fun login(@Body request: LoginRequest): RespDTO<LoginDTO>

    //注册发码（目标契约端点，服务端待实现；注册/找回是两个独立发码端点）
    @POST("/api/auth/send-code")
    @Headers("Content-Type:application/json;charset=UTF-8")
    suspend fun sendRegisterCode(@Body request: SendCodeRequest): RespDTO<Unit>

    //注册（邮箱 + 验证码 + 密码，注册即激活、不发 token，用户需主动登录）
    @POST("/api/auth/register")
    @Headers("Content-Type:application/json;charset=UTF-8")
    suspend fun register(@Body request: RegisterRequest): RespDTO<Unit>

    //刷新token
    @POST("/api/auth/refresh")
    @Headers("Content-Type:application/json;charset=UTF-8")
    suspend fun refreshToken(@Body refreshToken: RefreshTokenRequest): RespDTO<LoginDTO>

    //登出
    @POST("/api/auth/logout")
    @Headers("Content-Type:application/json;charset=UTF-8")
    suspend fun logout(): RespDTO<Unit>

    //已登录改密
    @PUT("/api/users/me/password")
    @Headers("Content-Type:application/json;charset=UTF-8")
    suspend fun modifyPwd(@Body request: ModifyPwdRequest): RespDTO<Unit>

    //发送邮箱验证码
    @POST("/api/auth/forgot-password/send-code")
    @Headers("Content-Type:application/json;charset=UTF-8")
    suspend fun sendForgotPasswordCode(@Body request: SendCodeRequest): RespDTO<Unit>

    //验证码重置密码
    @POST("/api/auth/forgot-password/reset")
    @Headers("Content-Type:application/json;charset=UTF-8")
    suspend fun resetPassword(@Body request: ResetPasswordRequest): RespDTO<Unit>

    //更新当前用户信息（昵称/头像/邮箱部分更新，见后端 ADR-0011：昵称/头像独立端点已废弃）
    @PUT("/api/users/me")
    @Headers("Content-Type:application/json;charset=UTF-8")
    suspend fun updateMe(@Body request: UpdateUserRequest): RespDTO<User>

    //上传头像（multipart，文件字段名 avatar，jpg/png/webp ≤5MB），返回可访问 URL
    @Multipart
    @POST("/api/uploads/avatar")
    suspend fun uploadAvatar(@Part avatar: MultipartBody.Part): RespDTO<UploadResponse>
}
