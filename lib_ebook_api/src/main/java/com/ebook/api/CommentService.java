package com.ebook.api;

import com.ebook.api.comment.entity.Comment;
import com.ebook.api.config.API;
import com.ebook.api.dto.RespDTO;
import com.ebook.api.user.LoginDTO;
import com.ebook.api.user.entity.User;

import io.reactivex.Observable;
import retrofit2.http.Body;
import retrofit2.http.GET;
import retrofit2.http.Header;
import retrofit2.http.Headers;
import retrofit2.http.POST;
import retrofit2.http.Path;
import retrofit2.http.Query;

public interface CommentService {
    //添加评论
    @POST(API.URL_HOST_COMMENT + "comments/save")
    @Headers("Content-Type:application/json;charset=UTF-8")
    Observable<RespDTO<LoginDTO>> addComment(@Header("Authorization") String tolen, @Body Comment comment);

    //删除评论
    @POST(API.URL_HOST_COMMENT + "comments/delete/{id}")
    @Headers("Content-Type:application/json;charset=UTF-8")
    Observable<RespDTO<LoginDTO>> deleteComment(@Header("Authorization") String tolen, @Path("id") Long id);

    //获得用户评论
    @GET(API.URL_HOST_COMMENT + "comments/query/name/{username}")
    @Headers("Content-Type:application/json;charset=UTF-8")
    Observable<RespDTO<LoginDTO>> getUserComments(@Header("Authorization") String tolen, @Path("username")String username);

    //获得章节评论
    @GET(API.URL_HOST_COMMENT + "comments/query/chapter/{chapterUrl}")
    @Headers("Content-Type:application/json;charset=UTF-8")
    Observable<RespDTO<LoginDTO>> getChapterComments(@Header("Authorization") String tolen, @Path("chapterUrl")String chapterUrl);
}
