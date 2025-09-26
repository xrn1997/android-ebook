package com.ebook.api

import com.ebook.api.intercepter.EncodingInterceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.adapter.rxjava3.RxJava3CallAdapterFactory
import retrofit2.converter.scalars.ScalarsConverterFactory
import java.util.concurrent.TimeUnit

object RetrofitBuilder {
    private var clientBuilder: OkHttpClient.Builder = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .addInterceptor(EncodingInterceptor("UTF-8"))

    /**
     * 获取Retrofit对象
     */
    fun getRetrofitObject(url: String, log: Boolean = false): Retrofit {
        if (log) {
            val logging = HttpLoggingInterceptor()
            logging.setLevel(HttpLoggingInterceptor.Level.BODY)
            clientBuilder.interceptors().add(logging);
        }
        return Retrofit.Builder().baseUrl(url) //增加返回值为字符串的支持(以实体类返回)
            .addConverterFactory(ScalarsConverterFactory.create()) //增加返回值为Observable<T>的支持
            .addCallAdapterFactory(RxJava3CallAdapterFactory.create())
            .client(clientBuilder.build())
            .build()
    }
}