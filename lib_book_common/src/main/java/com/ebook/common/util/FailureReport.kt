package com.ebook.common.util

import com.ebook.api.utils.CoroutineAdapter
import com.xrn1997.common.mvvm.viewmodel.BaseViewModel
import com.xrn1997.common.util.Logger

/**
 * 一次失败如何变成用户所见：文案口径与提示出口的**唯一**归口点。
 *
 * 收口前「过期就静默」那条判断在三个模块的 **8 处** `onFailure` 分支里各抄一遍，文案选取
 * 只在 `module_me` 抽了个 internal 的 `errorText`（另两个模块把同一段 if/else 内联重写）。
 * 收口时这 8 处塌缩成 **13 个 `reportFailure(...)` 调用点**：`module_me` 4、`module_book` 3、
 * `module_login` 6。被复制的从来不是几行样板，而是一条不变量：**A0230 会话过期已由网络层全局处置**
 * （清会话 + 提示 + 跳登录），调用点必须静默、只记日志，否则用户会看到两次提示。
 * 靠手写维持的不变量，第九个 ViewModel 的作者未必知道它存在——故收成一个接口后面的实现。
 */

/**
 * 异常 → 用户可见文案。
 *
 * 业务异常（[CoroutineAdapter.ApiException]）取服务端下发的原文；其余取异常自身消息，
 * 消息为 null 时归空串（不是字面量 "null"）。纯函数，可 JVM 直测（见 `UserMessageTest`）。
 */
fun Throwable.userMessage(): String =
    (this as? CoroutineAdapter.ApiException)?.message() ?: message.orEmpty()

/**
 * 上报一次失败：会话过期只记日志，其余经基类命令通道弹一条 Toast。
 *
 * 会话过期那条路径的静默是**故意的**，不是漏写提示：全局订阅方已经提示过「登录已过期」，
 * 这里再弹一次就是同一件事响两遍。调用方若还需收尾（收覆盖层、停下拉刷新），
 * 放在本调用之后照常执行即可；确实要按两类失败分流的，用返回值判断，别再抄一遍那条 if。
 *
 * @param exception 待上报的失败
 * @param message 用户可见文案，默认取 [userMessage]；需要固定前缀时由调用方拼好传入
 * @return `true` 表示这是「会话过期已由全局处置」的失败（本处静默、没有弹提示）。
 *   需要按失败类型分流收尾的调用方（如覆盖层形态）据此判断——否则又得把
 *   [CoroutineAdapter.isSessionExpiredHandled] 那条判断抄一遍，收口就白做了。
 */
fun BaseViewModel<*>.reportFailure(
    exception: Throwable,
    message: String = exception.userMessage(),
): Boolean {
    if (CoroutineAdapter.isSessionExpiredHandled(exception)) {
        Logger.w(
            this::class.java.simpleName,
            "会话过期已由全局处置，本调用点静默（仅日志）：${exception.message}",
        )
        return true
    }
    sendToast(message)
    return false
}
