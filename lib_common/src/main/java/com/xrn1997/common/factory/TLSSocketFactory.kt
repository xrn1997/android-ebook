package com.xrn1997.api.factory

import android.annotation.SuppressLint
import java.io.IOException
import java.net.InetAddress
import java.net.Socket
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.*


/**
 * TLS Socket
 * @author xrn1997
 */
class TLSSocketFactory : SSLSocketFactory {
    companion object {
        /**
         *   https://developer.android.com/about/versions/android-5.0-changes.html#ssl
         *   https://developer.android.com/reference/javax/net/ssl/SSLSocket
         */
        private val PROTOCOL_ARRAY: Array<String> = arrayOf("TLSv1", "TLSv1.1", "TLSv1.2")
        val DEFAULT_TRUST_MANAGERS: X509TrustManager =
            @SuppressLint("CustomX509TrustManager")
            object : X509TrustManager {
                @SuppressLint("TrustAllX509TrustManager")
                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
                    // Trust.
                }

                @SuppressLint("TrustAllX509TrustManager")
                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                    // Trust.
                }

                override fun getAcceptedIssuers(): Array<X509Certificate?> {
                    return arrayOfNulls(0)
                }
            }

        private fun setSupportProtocolAndCipherSuites(socket: Socket) {
            if (socket is SSLSocket) {
                socket.enabledProtocols = PROTOCOL_ARRAY
            }
        }

    }

    private val delegate: SSLSocketFactory

    constructor() {
        delegate = try {
            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(null, arrayOf<TrustManager>(DEFAULT_TRUST_MANAGERS), SecureRandom())
            sslContext.socketFactory
        } catch (e: GeneralSecurityException) {
            throw AssertionError() // The system has no TLS. Just give up.
        }
    }

    constructor(factory: SSLSocketFactory) {
        delegate = factory
    }

    override fun getDefaultCipherSuites(): Array<String> {
        return delegate.defaultCipherSuites
    }

    override fun getSupportedCipherSuites(): Array<String> {
        return delegate.supportedCipherSuites
    }

    @Throws(IOException::class)
    override fun createSocket(s: Socket, host: String, port: Int, autoClose: Boolean): Socket {
        val ssl = delegate.createSocket(s, host, port, autoClose)
        setSupportProtocolAndCipherSuites(ssl)
        return ssl
    }

    @Throws(IOException::class)
    override fun createSocket(host: String, port: Int): Socket {
        val ssl = delegate.createSocket(host, port)
        setSupportProtocolAndCipherSuites(ssl)
        return ssl
    }

    @Throws(IOException::class)
    override fun createSocket(
        host: String,
        port: Int,
        localHost: InetAddress,
        localPort: Int
    ): Socket {
        val ssl = delegate.createSocket(host, port, localHost, localPort)
        setSupportProtocolAndCipherSuites(ssl)
        return ssl
    }

    @Throws(IOException::class)
    override fun createSocket(host: InetAddress, port: Int): Socket {
        val ssl = delegate.createSocket(host, port)
        setSupportProtocolAndCipherSuites(ssl)
        return ssl
    }

    @Throws(IOException::class)
    override fun createSocket(
        address: InetAddress,
        port: Int,
        localAddress: InetAddress,
        localPort: Int
    ): Socket {
        val ssl = delegate.createSocket(address, port, localAddress, localPort)
        setSupportProtocolAndCipherSuites(ssl)
        return ssl
    }

    @Throws(IOException::class)
    override fun createSocket(): Socket {
        val ssl = delegate.createSocket()
        setSupportProtocolAndCipherSuites(ssl)
        return ssl
    }
}