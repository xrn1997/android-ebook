package com.xrn1997.common.http

import android.util.MalformedJsonException
import com.google.gson.JsonParseException
import org.json.JSONException
import retrofit2.HttpException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.text.ParseException
import javax.net.ssl.SSLException

/**
 * 异常Handler
 * @author xrn1997
 */
object ExceptionHandler {
    //TODO 这个文件的错误码还没有修改
    @JvmStatic
    fun handleException(e: Throwable): ResponseException {
        val ex: ResponseException
        return if (e is HttpException) {
            ex = ResponseException(e, SystemError.HTTP_ERROR)
            when (e.code()) {
                SystemError.UNAUTHORIZED -> {
                    ex.message = "操作未授权"
                    ex.code = SystemError.UNAUTHORIZED
                }

                SystemError.FORBIDDEN -> ex.message = "请求被拒绝"
                SystemError.NOT_FOUND -> ex.message = "资源不存在"
                SystemError.REQUEST_TIMEOUT -> ex.message = "服务器执行超时"
                SystemError.INTERNAL_SERVER_ERROR -> ex.message = "服务器内部错误"
                SystemError.SERVICE_UNAVAILABLE -> ex.message = "服务器不可用"
                else -> ex.message = "网络错误"
            }
            ex
        } else if (e is JsonParseException || e is JSONException || e is ParseException || e is MalformedJsonException) {
            ex = ResponseException(e, SystemError.PARSE_ERROR)
            ex.message = "解析错误"
            ex
        } else if (e is ConnectException) {
            ex = ResponseException(e, SystemError.NETWORK_ERROR)
            ex.message = "连接失败"
            ex
        } else if (e is SSLException) {
            ex = ResponseException(e, SystemError.SSL_ERROR)
            ex.message = "证书验证失败"
            ex
        } else if (e is SocketTimeoutException) {
            ex = ResponseException(e, SystemError.TIMEOUT_ERROR)
            ex.message = "连接超时"
            ex
        } else if (e is UnknownHostException) {
            ex = ResponseException(e, SystemError.TIMEOUT_ERROR)
            ex.message = "主机地址未知"
            ex
        } else {
            ex = ResponseException(e, SystemError.UNKNOWN)
            ex.message = "未知错误"
            ex
        }
    }

    object SystemError {
        const val UNAUTHORIZED = 401
        const val FORBIDDEN = 403
        const val NOT_FOUND = 404
        const val REQUEST_TIMEOUT = 408
        const val INTERNAL_SERVER_ERROR = 500
        const val SERVICE_UNAVAILABLE = 503

        /**
         * 未知错误
         */
        const val UNKNOWN = 1000

        /**
         * 解析错误
         */
        const val PARSE_ERROR = 1001

        /**
         *  网络错误
         */
        const val NETWORK_ERROR = 1002

        /**
         * 协议出错
         */
        const val HTTP_ERROR = 1003

        /**
         * 证书出错
         */
        const val SSL_ERROR = 1005

        /**
         * 连接超时
         */
        const val TIMEOUT_ERROR = 1006
    }

    interface AppError {
        companion object {
            const val SUCCESS = 0 //    处理成功，无错误
            const val INTERFACE_PROCESSING_TIMEOUT = 1 //    接口处理超时
            const val INTERFACE_INTERNAL_ERROR = 2 //    接口内部错误
            const val PARAMETERS_EMPTY = 3 //    必需的参数为空
            const val AUTHENTICATION_FAILED = 4 //   鉴权失败，用户没有使用该项功能（服务）的权限。
            const val PARAMETERS_ERROR = 5 //    参数错误
        }
    }
}