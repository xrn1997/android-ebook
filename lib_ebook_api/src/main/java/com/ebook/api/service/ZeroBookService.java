package com.ebook.api.service;

import io.reactivex.Observable;
import retrofit2.http.GET;
import retrofit2.http.Headers;
import retrofit2.http.Query;
import retrofit2.http.Url;

/**
 * @author xrn1997
 * @date 2021/6/19
 */
public interface ZeroBookService {

    String URL = "http://www.bbzayy.com";
    String COVER_URL = "http://www.sundung.com";
    String SEARCH_URL = "http://sou.bbzayy.com";

    @GET
    @Headers({"Accept:text/html,application/xhtml+xml,application/xml",
            "User-Agent:Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.101 Safari/537.36 Edg/91.0.864.48",
            "Accept-Charset:UTF-8",
            "Connection:close",
            "Cache-Control:no-cache"})
    Observable<String> getLibraryData(@Url String url);

    @GET
    @Headers({"Accept:text/html,application/xhtml+xml,application/xml",
            "User-Agent:Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.101 Safari/537.36 Edg/91.0.864.48",
            "Accept-Charset:UTF-8",
            "Connection:close",
            "Cache-Control:no-cache"})
    Observable<String> getBookInfo(@Url String url);

    @GET("/search.aspx")
    @Headers({"Accept:text/html,application/xhtml+xml,application/xml",
            "User-Agent:Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.101 Safari/537.36 Edg/91.0.864.48",
            "Content-Type:application/x-www-form-urlencoded; charset=UTF-8",
            "Accept-Charset:UTF-8",
            "Connection:close",
            "Cache-Control:no-cache"})
    Observable<String> searchBook(@Query(value = "key", encoded = true) String content, @Query("page") int page, @Query("siteid") String siteid);

    @GET
    @Headers({"Accept:text/html,application/xhtml+xml,application/xml",
            "User-Agent:Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.101 Safari/537.36 Edg/91.0.864.48",
            "Accept-Charset:UTF-8",
            "Connection:close",
            "Cache-Control:no-cache"})
    Observable<String> getBookContent(@Url String url);

    @GET
    @Headers({"Accept:text/html,application/xhtml+xml,application/xml",
            "User-Agent:Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.101 Safari/537.36 Edg/91.0.864.48",
            "Accept-Charset:UTF-8",
            "Connection:close",
            "Cache-Control:no-cache"})
    Observable<String> getChapterList(@Url String url);

    @GET
    @Headers({"Accept:text/html,application/xhtml+xml,application/xml",
            "User-Agent:Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.101 Safari/537.36 Edg/91.0.864.48",
            "Accept-Charset:UTF-8",
            "Connection:close",
            "Cache-Control:no-cache"})
    Observable<String> getKindBooks(@Url String url);
}
