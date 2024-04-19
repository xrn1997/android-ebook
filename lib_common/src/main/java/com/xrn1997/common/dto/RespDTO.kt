package com.xrn1997.common.dto

import java.io.Serializable

/**
 * 响应数据传输类
 * @author xrn1997
 */
data class RespDTO<T>(
    var code: String = "",
    var message: String = "",
    var data: T? = null
) : Serializable {
    override fun toString(): String {
        return "RespDTO{code= $code, message=\'$message\', data=$data }"
    }
}