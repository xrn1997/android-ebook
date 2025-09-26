package com.ebook.api.dto

import java.io.Serializable

/**
 * 响应数据传输类
 * @author xrn1997
 */
data class RespDTO<T>(
    @JvmField
    var code: Int = 0,
    @JvmField
    var error: String = "",
    @JvmField
    var data: T? = null
) : Serializable {
    override fun toString(): String {
        return "RespDTO{code= $code, message=\'${error}\', data=$data }"
    }
}
