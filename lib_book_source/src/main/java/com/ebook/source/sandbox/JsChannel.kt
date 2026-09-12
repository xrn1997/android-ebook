package com.ebook.source.sandbox

/**
 * 一条能跑 JS 的通道。**禁止出现任何 android 类型**——真实现（Task 8 的 `BinderJsChannel`）
 * 只是它的一个适配器，于是重连、重放、回调中继这些只在设备上难造的失败路径全能在 JVM 上测。
 */
interface JsChannel {

    /** 只报告本地状态，不探测远端（探测要发一次 binder 调用，那不属于「便宜」） */
    val isOpen: Boolean

    /**
     * 同步执行一帧。
     *
     * @param hostCallback 执行器跑脚本期间回调主进程的唯一入口；入参是 host 调用帧，返回是 host 回复帧
     * @throws ChannelDeadException 通道断了（对端死、进程被杀、连接失效）——与「脚本跑坏了」两回事
     */
    fun execute(requestFrame: String, hostCallback: (String) -> String): String

    fun close()
}

/** 通道死亡：可以重连，在没有副作用时可以重放；脚本自己抛的异常绝不会走到这里 */
class ChannelDeadException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * 主进程侧处理一次 host 调用。
 *
 * 契约：**不得抛出**是实现方（Task 9 的代理）的责任，客户端只是兜底。兜底存在的原因不是礼貌：
 * 异常若从回调里穿出去，落在执行器进程的 JNI 栈上，症状是整个 `:js` 进程没。
 *
 * 回复类型用 [HostReply]（Task 3 定义为 public）：`JsSandboxClient` 要能被 `lib_book_common`
 * 的 Hilt 模块构造，公开签名上不许出现 internal 类型。
 */
fun interface HostHandler {
    fun handle(api: String, argsJson: String): HostReply
}
