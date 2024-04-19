package com.xrn1997.common.util

import okhttp3.HttpUrl
import java.lang.reflect.Field

/**
 * HttpUrl工具类
 * @author xrn1997
 * @date 2022/3/17 13:52.
 */
class HttpUrlUtil(private val httpUrl: HttpUrl) {

    companion object {
        private var hostField: Field? = null
        private var portField: Field? = null

        init {
            var tempHostField: Field? = null
            var tempPortField: Field? = null
            try {
                tempHostField = HttpUrl::class.java.getDeclaredField("host")
                tempHostField.isAccessible = true
                tempPortField = HttpUrl::class.java.getDeclaredField("port")
                tempPortField.isAccessible = true
            } catch (e: NoSuchFieldException) {
                e.printStackTrace()
            }
            hostField = tempHostField
            portField = tempPortField
        }
    }

    /**
     * 设置网址
     * @param host String?
     */
    fun setHost(host: String?) {
        try {
            hostField?.set(httpUrl, host)
        } catch (e: IllegalAccessException) {
            e.printStackTrace()
        }
    }

    /**
     * 设置端口号
     * @param port Int
     */
    fun setPort(port: Int) {
        try {
            portField?.set(httpUrl, port)
        } catch (e: IllegalAccessException) {
            e.printStackTrace()
        }
    }

    /**
     * 获取httpUrl
     * @return HttpUrl
     */
    fun getHttpUrl(): HttpUrl {
        return httpUrl
    }
}