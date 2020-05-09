package com.ebook.api;

import com.ebook.api.dto.RespDTO;
import com.ebook.api.entity.LoginDTO;
import com.ebook.api.entity.User;


import io.reactivex.Observable;
import okhttp3.MultipartBody;
import retrofit2.http.Body;
import retrofit2.http.Header;
import retrofit2.http.Headers;
import retrofit2.http.Multipart;
import retrofit2.http.POST;
import retrofit2.http.Part;
import retrofit2.http.Path;
import retrofit2.http.Query;

public interface UserService {
    //登录
    @POST("user/login")
    @Headers("Content-Type:application/json;charset=UTF-8")
    Observable<RespDTO<LoginDTO>> login(@Body User user);

    //注册
    @POST("user/registry")
    @Headers("Content-Type:application/json;charset=UTF-8")
    Observable<RespDTO<LoginDTO>> register(@Body User user);

    //修改密码
    @POST("user/modify/password")
    @Headers("Content-Type:application/json;charset=UTF-8")
    Observable<RespDTO<Integer>> modifyPwd(@Body User user);

    //修改昵称
    @POST("user/modify/nickname")
    @Headers("Content-Type:application/json;charset=UTF-8")
    Observable<RespDTO<Integer>> modifyNickname(@Header("Authorization") String tolen, @Query("username") String username, @Query("nickname") String nickname);
//
    //修改头像
    @Multipart
    @POST("user/modify/image/u{username}")
    Observable<RespDTO<String>> modifyProfiePhoto(@Header("Authorization") String tolen,@Path("username")String username,@Part MultipartBody.Part file);
}
