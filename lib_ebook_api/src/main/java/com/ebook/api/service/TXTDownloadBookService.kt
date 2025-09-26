package com.ebook.api.service

import io.reactivex.rxjava3.core.Observable
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.Query
import retrofit2.http.Url

/**
 * TXT下载网
 *
 * @author xrn1997
 */
interface TXTDownloadBookService {
    @GET
    @Headers(
        "Accept:text/html,application/xhtml+xml,application/xml",
        "User-Agent:Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.101 Safari/537.36 Edg/91.0.864.48",
        "Accept-Charset:UTF-8",
        "Connection:close",
        "Cache-Control:no-cache"
    )
    fun getLibraryData(@Url url: String?): Observable<String>

    @GET
    @Headers(
        "Accept:text/html,application/xhtml+xml,application/xml",
        "User-Agent:Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.101 Safari/537.36 Edg/91.0.864.48",
        "Accept-Charset:UTF-8",
        "Connection:close",
        "Cache-Control:no-cache"
    )
    fun getBookInfo(@Url url: String): Observable<String>

    @GET("/modules/article/search.php")
    @Headers(
        "Accept:text/html,application/xhtml+xml,application/xml",
        "User-Agent:Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.101 Safari/537.36 Edg/91.0.864.48",
        "Content-Type:text/html; charset=UTF-8",
        "Accept-Charset:UTF-8",
        "Connection:close",
        "Cache-Control:no-cache"
    )
    fun searchBook(
        @Query(
            value = "searchkey",
            encoded = true
        ) content: String
    ): Observable<String>

    @GET
    @Headers(
        "Accept:text/html,application/xhtml+xml,application/xml",
        "User-Agent:Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.101 Safari/537.36 Edg/91.0.864.48",
        "Accept-Charset:UTF-8",
        "Connection:close",
        "Cache-Control:no-cache"
    )
    fun getBookContent(@Url url: String): Observable<String>

    @GET
    @Headers(
        "Accept:text/html,application/xhtml+xml,application/xml",
        "User-Agent:Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.101 Safari/537.36 Edg/91.0.864.48",
        "Accept-Charset:UTF-8",
        "Connection:close",
        "Cache-Control:no-cache"
    )
    fun getChapterList(@Url url: String): Observable<String>

    @GET
    @Headers(
        "Accept:text/html,application/xhtml+xml,application/xml",
        "User-Agent:Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.101 Safari/537.36 Edg/91.0.864.48",
        "Accept-Charset:UTF-8",
        "Connection:close",
        "Cache-Control:no-cache"
    )
    fun getKindBooks(@Url url: String): Observable<String>

    companion object {
        const val URL: String = "https://www.shuangliusc.com"
        const val COVER_URL: String = "https://www.shuangliusc.com/files/article/image"
    }
}
