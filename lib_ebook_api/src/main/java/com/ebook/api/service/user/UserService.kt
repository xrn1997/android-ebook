package com.ebook.api.service.user

import com.xrn1997.common.dto.RespDTO
import com.ebook.api.entity.LoginDTO
import com.ebook.api.entity.User
import okhttp3.MultipartBody
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.Headers
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query

interface UserService {
    //登录
    @POST("/user/login")
    @Headers("Content-Type:application/json;charset=UTF-8")
    suspend fun login(@Body user: User): RespDTO<LoginDTO>

    //注册
    @POST("/user/registry")
    @Headers("Content-Type:application/json;charset=UTF-8")
    suspend fun register(@Body user: User): RespDTO<LoginDTO>

    //修改密码
    @POST("/user/modify/password")
    @Headers("Content-Type:application/json;charset=UTF-8")
    suspend fun modifyPwd(@Body user: User): RespDTO<Int>

    //修改昵称
    @POST("/user/modify/nickname")
    @Headers("Content-Type:application/json;charset=UTF-8")
    suspend fun modifyNickname(
        @Header("Authorization") token: String?,
        @Query("username") username: String,
        @Query("nickname") nickname: String
    ): RespDTO<Int>

    //修改头像
    @Multipart
    @POST("/user/modify/image/u{username}")
    suspend fun modifyProfilePhoto(
        @Header("Authorization") token: String?,
        @Path("username") username: String,
        @Part file: MultipartBody.Part
    ): RespDTO<String>
}




