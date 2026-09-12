package com.ebook.source.sandbox

/**
 * 执行器侧一次事务的裁决：进不进内核、回什么帧、什么时候走任务边界。
 *
 * 参数取两个函数（[eval]、[onTaskBoundary]）而不是一个 `JsRuntimeBridge`，为的是让这一层
 * 在内核不在场时也能被完整测到——真机上「内核抛出未类型化异常」这种路径造不出来，
 * 在这里只是一个 lambda。
 *
 * [requestFrame] 可空是照搬 `Parcel.readString()` 的形态：对端写空是合法的，
 * 这一层必须给出一种有名字的失败，而不是在解引用处抛 NPE。
 */
internal fun runSandboxTask(
    requestFrame: String?,
    limits: JsLimits,
    now: () -> Long,
    eval: (JsInvocation, Long) -> JsOutcome,
    onTaskBoundary: () -> Unit,
): String {
    if (requestFrame == null) {
        return JsProtocol.encodeOutcome(
            JsOutcome(JsStatus.UNAVAILABLE, error = "execute 事务里没有请求帧"),
        )
    }
    val request = runCatching { JsProtocol.decodeRequest(requestFrame) }.getOrElse {
        // 请求帧是主进程自己编的，解不出来就是两侧不是同一次构建。
        // 抛出去只会让主进程收到一句事务失败，真话留在被丢掉的栈里，所以换成一帧读得出来的失败
        return JsProtocol.encodeOutcome(
            JsOutcome(JsStatus.UNAVAILABLE, error = "沙箱与主进程协议不合：请求帧解不出来"),
        )
    }
    if (now() >= request.deadlineMonoMs) {
        return JsProtocol.encodeOutcome(
            JsOutcome(JsStatus.TIMEOUT, error = "排队到期限之后才开始执行（本次未进内核）"),
        )
    }
    // 边界放在每一次执行的最前面：这样「上一个任务留下了什么」根本不构成问题，
    // 不需要记住哪些失败要清、哪些不用（那是最容易漏一条的写法）
    onTaskBoundary()
    val outcome = runCatching { eval(request.invocation, request.deadlineMonoMs) }.getOrElse { error ->
        return JsProtocol.encodeOutcome(
            JsOutcome(
                JsStatus.UNAVAILABLE,
                error = "执行器侧失败：${error.message?.take(120) ?: error.javaClass.simpleName}",
            ),
        )
    }
    return gateOutcomeSize(outcome, limits)
}

/**
 * 回帧大小闸门。
 *
 * 超限时多付一次 stringify（编码完再丢），换到的是「闸门只有一处」与「超限原文绝不进主进程」。
 * 这里防的是 binder 事务缓冲与主进程的内存，**不防执行器自己**：执行器由 8 MB 堆限兜住，
 * 所以 `heapBytes` 绝不能跟着帧上限一起放大——那样超限前先在执行器里堆出一份巨串，
 * 而现场只会看到 `:js` 进程没。
 */
private fun gateOutcomeSize(outcome: JsOutcome, limits: JsLimits): String {
    val frame = JsProtocol.encodeOutcome(outcome)
    val bytes = utf8Length(frame)
    if (bytes <= limits.maxOutcomeBytes) return frame
    return JsProtocol.encodeOutcome(
        JsOutcome(
            JsStatus.TOO_LARGE,
            error = "沙箱响应超出 ${limits.maxOutcomeBytes} 字节上限（实际 $bytes 字节）",
        ),
    )
}
