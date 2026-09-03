package com.ebook.me.mvvm.viewmodel

import com.ebook.api.utils.CoroutineAdapter

/**
 * 统一错误文案：ApiException 取服务端消息，其余取本地异常消息。
 *
 * 模块内 VM 的失败 Toast 一律经此函数取文案，避免各处重复的 if/else 判断。
 * 取到文案后经基类 [com.xrn1997.common.mvvm.viewmodel.BaseViewModel.sendToast] 下发。
 * 注：服务端消息为动态文本，直接取 ApiException 业务文案；本地异常消息由 Repository 层
 * 经字符串资源生成（见 ModifyRepository），故此处无硬编码文案。
 */
internal fun errorText(exception: Throwable): String =
    if (exception is CoroutineAdapter.ApiException) {
        exception.message()
    } else {
        exception.message.orEmpty()
    }
