package com.ebook.api;


import com.ebook.api.newstype.entity.NewsType;
import com.ebook.api.config.API;
import com.ebook.api.dto.RespDTO;

import java.util.List;

import io.reactivex.Observable;
import retrofit2.http.Body;
import retrofit2.http.GET;
import retrofit2.http.Header;
import retrofit2.http.POST;
import retrofit2.http.Path;

public interface NewsTypeService {
    @POST(API.URL_HOST_NEWS + "/newstype/query/all")
    Observable<RespDTO<List<NewsType>>> getListNewsType(@Header("Authorization") String tolen);

    @GET(API.URL_HOST_NEWS + "/newstype/{id}/delete")
    Observable<RespDTO> deleteNewsTypeById(@Header("Authorization") String tolen, @Path("id") int id);

    @POST(API.URL_HOST_NEWS + "/newstype/save")
    Observable<RespDTO<NewsType>> addNewsType(@Header("Authorization") String tolen, @Body NewsType type);
}
