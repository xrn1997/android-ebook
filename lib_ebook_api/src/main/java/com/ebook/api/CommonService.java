package com.ebook.api;

import com.ebook.api.dto.RespDTO;
import com.ebook.api.user.LoginDTO;
import com.ebook.api.user.entity.User;

import io.reactivex.Observable;
import retrofit2.http.Body;
import retrofit2.http.Headers;
import retrofit2.http.POST;
import retrofit2.http.Query;

public interface CommonService {
    //登录
    @POST("/user/login")
    @Headers("Content-Type:application/json;charset=UTF-8")
    Observable<RespDTO<LoginDTO>> login(@Body User user);
    //注册
    @POST("/user/registry")
    @Headers("Content-Type:application/json;charset=UTF-8")
    Observable<RespDTO<LoginDTO>> register(@Body User user);

    @POST("/user/modifyPwd")
    @Headers("Content-Type:application/json;charset=UTF-8")
    Observable<RespDTO<Integer>> modifyPwd(@Body User user);
}
