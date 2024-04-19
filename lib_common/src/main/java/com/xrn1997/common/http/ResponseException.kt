package com.xrn1997.common.http

/**
 * 响应异常处理类
 * @author xrn1997
 */
class ResponseException(throwable: Throwable?, var code: Int) : Exception(throwable) {
    override var message: String? = null
    override fun toString(): String {
        return "ResponseThrowable{code=$code, message='$message'}"
    }
}