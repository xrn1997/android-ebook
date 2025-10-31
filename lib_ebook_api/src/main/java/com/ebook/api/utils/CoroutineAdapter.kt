package com.ebook.api.utils

import android.util.Log
import com.xrn1997.common.constant.ErrorCode
import com.xrn1997.common.dto.RespDTO
import com.xrn1997.common.http.ExceptionHandler.handleException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object CoroutineAdapter {

    private const val TAG = "CoroutineAdapter"

    /**
     * 通用网络请求封装
     * @param apiCall suspend 函数执行网络请求
     * @return Result<RespDTO<T>> 成功或失败
     */
    suspend fun <T> safeApiCall(apiCall: suspend () -> RespDTO<T>): Result<RespDTO<T>> {
        return try {
            // 网络请求在 IO 线程
            val resp = withContext(Dispatchers.IO) { apiCall() }

            // 业务异常处理
            if (resp.code == ErrorCode.SUCCESS.code) {
                Result.success(resp)
            } else {
                Result.failure(ApiException(resp.code, resp.error))
            }
        } catch (e: Exception) {
            // 网络或未知异常处理
            val exception = handleException(e)
            Log.e(TAG, "网络请求异常", exception)
            Result.failure(exception)
        }
    }

    /**
     * 简化调用，直接获取 data，如果失败返回 null
     */
    suspend fun <T> callData(apiCall: suspend () -> RespDTO<T>): T? {
        val result = safeApiCall(apiCall)
        return result.getOrNull()?.data
    }

    class ApiException(val code: String, override val message: String) : Exception(message) {
        fun message(): String {
            return "ApiException(${code}: ${message})"
        }
    }
}
