package com.ebook.source.sandbox

/**
 * `js_bridge.cpp` 的 Kotlin 侧影子。四个函数名与 C++ 里的
 * `Java_com_ebook_source_sandbox_QuickJsNative_nativeXxx` 一一对应。
 *
 * 刻意是 `object` 的**实例** external 函数（不是 `@JvmStatic`）：JNI 的第二参数因此是 `jobject`
 * 而不是 `jclass`，两侧都按这个来。这个选择不是风格——`external` 与 `@JvmStatic` 的组合在 Kotlin
 * 里生成不出可解析的静态 native 方法，写错了要等到设备上第一次调用才炸
 * （`UnsatisfiedLinkError` 在编译期与链接期都不露头）。
 *
 * 加载失败不在这里抛：整条沙箱链路的要求是「失败是有名字的结果」。`loaded=false` 时
 * [JsRuntimeBridge] 直接回 [JsStatus.UNAVAILABLE]，一次 `external` 调用都不会发出。
 */
internal object QuickJsNative {

    /** 加载只做一次：`loaded` 与 [loadError] 由同一个结果派生，两处不各调一次 loadLibrary */
    private val loadResult: Result<Unit> = runCatching { System.loadLibrary("ebook_js") }

    val loaded: Boolean = loadResult.isSuccess

    /**
     * 加载失败的原因（成功时为 null）。
     *
     * 存在的理由是**可观测性**：真机用例以「跳过」表达「本机没有可用的 .so」（ABI 未随
     * androidTest 打包是合法情形），但只判 [loaded] 会让「压根没跑」与「跑过并通过」在报告里
     * 长得一样。把原因带进跳过信息，至少能一眼看出是没打包还是真跑过了。
     */
    val loadError: String? =
        loadResult.exceptionOrNull()?.let { "${it.javaClass.simpleName}: ${it.message}" }

    /**
     * 只建 runtime：装好堆限、栈限、`JS_SetCanBlock(rt,0)` 与中断器。
     *
     * context 与 `__host_call` 不在这里——它们住在 `nativeReset` 里，而 `ensureRuntime()` 紧接着
     * 就调它一次。分开是为了让「每次执行前重建 context」只有一条代码路径。失败返回 0（句柄一律当 int64 传）。
     */
    external fun nativeCreate(heapBytes: Long, stackBytes: Long): Long

    /** 丢掉 context 重建并重放垫片：任务边界的强制重置（ADR-0028 决策 5），runtime 本身留着 */
    external fun nativeReset(handle: Long, preludeSource: String): Boolean

    /**
     * 跑一次。
     *
     * @param source 已含 `QuickJsPrelude.BOOTSTRAP` 前缀的完整脚本文本
     * @param bindingsJson 绑定的 JSON 对象；C++ 把它挂成 `__bindings` 交给 BOOTSTRAP 去落成全局量
     * @param deadlineMonoMs `CLOCK_BOOTTIME` 毫秒绝对值，与 `SystemClock.elapsedRealtime()` 同口径
     * @return 结果描述符（见 Task 7 开头），任何情况下都是合法 JSON
     */
    external fun nativeEval(
        handle: Long,
        bindingsJson: String,
        source: String,
        deadlineMonoMs: Long,
    ): String

    external fun nativeDestroy(handle: Long)
}
