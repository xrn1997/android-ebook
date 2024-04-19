package com.xrn1997.common.util

import com.xrn1997.api.factory.TLSSocketFactory
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLSession
import javax.net.ssl.SSLSocketFactory

/**
 * SSL工具类
 * @author xrn1997
 * @date 2022/3/2 19:22
 */
@Suppress("unused")
object SSLUtil {
    private val HOSTNAME_VERIFIER =
        HostnameVerifier { _: String?, _: SSLSession? -> true }

    fun defaultSSLSocketFactory(): SSLSocketFactory {
        return TLSSocketFactory()
    }

    fun fixSSLLowerThanLollipop(socketFactory: SSLSocketFactory): SSLSocketFactory {
        return socketFactory
    }

    fun defaultHostnameVerifier(): HostnameVerifier {
        return HOSTNAME_VERIFIER
    }
}