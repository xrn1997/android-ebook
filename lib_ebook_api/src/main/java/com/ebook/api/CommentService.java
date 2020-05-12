package com.ebook.api;

import com.ebook.api.entity.Comment;
import com.ebook.api.config.API;
import com.ebook.api.dto.RespDTO;

import java.util.List;

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
    Observable<RespDTO<Comment>> addComment(@Header("Authorization") String tolen, @Body Comment comment);

    //删除评论
    @POST(API.URL_HOST_COMMENT + "comments/delete/{id}")
    @Headers("Content-Type:application/json;charset=UTF-8")
    Observable<RespDTO<Integer>> deleteComment(@Header("Authorization") String tolen, @Path("id") Long id);

    //获得用户评论
    @GET(API.URL_HOST_COMMENT + "comments/query/name/{username}")
    @Headers("Content-Type:application/json;charset=UTF-8")
    Observable<RespDTO<List<Comment>>> getUserComments(@Header("Authorization") String tolen, @Path("username") String username);

    //获得章节评论
    @GET(API.URL_HOST_COMMENT + "comments/query/chapter")
    @Headers("Content-Type:application/json;charset=UTF-8")
    Observable<RespDTO<List<Comment>>> getChapterComments(@Header("Authorization") String tolen, @Query("chapterUrl") String chapterUrl);
}
