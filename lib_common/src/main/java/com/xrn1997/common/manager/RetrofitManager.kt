package com.xrn1997.common.manager

import android.annotation.SuppressLint
import android.text.TextUtils
import android.util.Log.VERBOSE
import com.blankj.utilcode.util.PathUtils
import com.ihsanbal.logging.Level
import com.ihsanbal.logging.LoggingInterceptor
import com.xrn1997.api.factory.TLSSocketFactory
import com.xrn1997.common.util.FileUtil
import com.xrn1997.common.util.HttpUrlUtil
import com.xrn1997.common.util.SSLUtil
import okhttp3.Cache
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.adapter.rxjava3.RxJava3CallAdapterFactory
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.converter.scalars.ScalarsConverterFactory
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * API Manager
 * @author xrn1997
 */
@SuppressLint("unused")
object RetrofitManager {
    /**
     *本地局域网默认IP地址(真机测试)
     */
    const val URL_LOCALHOST = "http://127.0.0.1:8080/"

    /**
     * 本地局域网默认IP地址(模拟器测试)
     */
    const val URL_ANDROID_VIRTUAL_LOCALHOST = "http://10.0.2.2:8080"
    private val mRetrofit: Retrofit
    var TOKEN: String? = null
    private val okHttpBuilder: OkHttpClient.Builder?
    val mHttpUrl = HttpUrlUtil(URL_ANDROID_VIRTUAL_LOCALHOST.toHttpUrl())
    var cachePath: String =
        PathUtils.getInternalAppFilesPath() + File.separator + "cache" + File.separator

    /**
     * 创建 Service接口动态代理对象。
     * 在调用create创建服务之前需要设置mHttpUrl，即http地址。
     * @see RetrofitManager.mHttpUrl
     * @see HttpUrlUtil
     */
    fun <T> create(serviceClass: Class<T>): T {
        return mRetrofit.create(serviceClass)
    }

    init {
        okHttpBuilder = OkHttpClient.Builder()
        //超时
        okHttpBuilder.connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
        //日志打印
        okHttpBuilder.interceptors().add(
            LoggingInterceptor
                .Builder()
                .setLevel(Level.BASIC)
                .log(VERBOSE)
                .build()
        )
        //添加Token
        okHttpBuilder.addInterceptor(Interceptor { chain ->
            var request = chain.request()
            if (!TextUtils.isEmpty(TOKEN)) {
                val requestBuilder = request.newBuilder()
                    .header("Authorization", "Bearer $TOKEN")
                request = requestBuilder.build()
            }
            chain.proceed(request)
        })
        FileUtil.checkDirPath(cachePath)
        //使用缓存
        okHttpBuilder
            .cache(Cache(File(cachePath, "okhttp-cache"), 10 * 1024 * 1024L))
            .addNetworkInterceptor { chain ->
                val request = chain.request()
                val response = chain.proceed(request)
                //在线的时候的缓存过期时间，如果想要不缓存，直接时间设置为0
                val onlineCacheTime = 60
                response.newBuilder()
                    .header("Cache-Control", "public, max-age=$onlineCacheTime")
                    .removeHeader("Pragma")
                    .build()
            }
        //给client的builder添加了一个socketFactory
        okHttpBuilder.sslSocketFactory(
            SSLUtil.defaultSSLSocketFactory(),
            TLSSocketFactory.DEFAULT_TRUST_MANAGERS
        )
        okHttpBuilder.hostnameVerifier(SSLUtil.defaultHostnameVerifier())
        //创建client
        mRetrofit = Retrofit.Builder()
            .client(okHttpBuilder.build())
            .baseUrl(mHttpUrl.getHttpUrl())
            .addCallAdapterFactory(RxJava3CallAdapterFactory.create()) //增加返回值为Observable<T>的支持
            .addConverterFactory(GsonConverterFactory.create())//增加返回值为字符串的支持(以实体类返回)
            .addConverterFactory(ScalarsConverterFactory.create())
            .build()
    }
}