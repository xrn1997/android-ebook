package com.ebook.basebook.base.impl;


import androidx.annotation.NonNull;

import java.io.IOException;
import java.lang.reflect.Field;

import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * <pre>
 *     time   : 2019/05/23
 *     desc   :
 *     version: 1.0
 * </pre>
 */
public class EncodingInterceptor implements Interceptor {


    /**
     * 自定义编码
     */
    private final String encoding;

    public EncodingInterceptor(String encoding) {
        this.encoding = encoding;
    }

    @NonNull
    @Override
    public Response intercept(Chain chain) throws IOException {
        Request request = chain.request();

        Response response = chain.proceed(request);
        settingClientCustomEncoding(response);
        return response;
    }

    /**
     * setting client custom encoding when server not return encoding
     */
    private void settingClientCustomEncoding(Response response) throws IOException {
        setBodyContentType(response);
    }


    /**
     * set body contentType
     */
    private void setBodyContentType(Response response) throws IOException {
        ResponseBody body = response.body();
        // setting body contentTypeString using reflect
        assert body != null;
        Class<? extends ResponseBody> aClass = body.getClass();
        try {
            Field field = aClass.getDeclaredField("contentTypeString");
            field.setAccessible(true);
            field.set(body, "application/rss+xml;charset=" + encoding);

        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new IOException("use reflect to setting header occurred an error", e);
        }
    }
}