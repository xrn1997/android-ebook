package com.ebook.source.sandbox

import okhttp3.OkHttpClient
import java.util.concurrent.atomic.AtomicReference

/**
 * 桌面（开发机）执行器：给 `ScriptBookParser` 一个**真客户端**的 `JsSandboxHost`，
 * 唯一被替换的是 [JsChannel]——binder 事务换成进程内函数调用，内核（vendored QuickJS）
 * 、垫片、帧协议、客户端的串行锁/重入守卫/限额/任务边界全部原样。
 *
 * 定位：只住 test source set。安全边界（`:js` 隔离进程、零权限）不在覆盖范围，
 * 仍归 androidTest 的 SandboxConnectionTest；这里要的是「同一份内核、同一套协议」。
 *
 * 装配照 AGENTS 那条铁律：**转子先建、同一实例既进 client 又进 host**——两边各 new 一个
 * `HostCallbackRouter` 的症状是每次 `java.ajax` 都报「主进程没有正在进行的脚本任务」。
 * `HostDispatcher.installRelay` 全 JVM 只能装一次，所以本对象是单例，中继经
 * [hostCallback] 指向「当前在飞事务」的回调口。
 *
 * `isMainThread`/`clock` 显式注入而不是用默认值：默认实现引用 `Looper`/`SystemClock`，
 * 在桌面 JVM 上语义不成立（时钟口径也不同——native 的 boot_time_ms 是含休眠的开机毫秒）。
 */
internal object DirectJsRuntime {

    private val limits = JsLimits()
    private val bridge = JsRuntimeBridge(limits)

    /** 当前在飞事务的 host 回调口（帧进帧出）；null 表示没有事务在飞 */
    private val hostCallback = AtomicReference<((String) -> String)?>(null)

    init {
        // 与 SandboxService 同一条接线：relay 收 (api, argsJson)，编成 host 调用帧递给
        // 主进程侧的回调口，回帧解回 HostReply。真机走 binder，这里走函数调用。
        HostDispatcher.installRelay { api, argsJson ->
            val callback = hostCallback.get()
                ?: return@installRelay HostReply(false, null, "桌面执行器没有在飞的 host 回调（$api）")
            JsProtocol.decodeHostReply(callback(JsProtocol.encodeHostCall(api, argsJson)))
        }
    }

    private val channel = object : JsChannel {
        override val isOpen: Boolean get() = true

        override fun execute(requestFrame: String, hostCallback: (String) -> String): String {
            this@DirectJsRuntime.hostCallback.set(hostCallback)
            return try {
                runSandboxTask(
                    requestFrame = requestFrame,
                    limits = limits,
                    now = bootNow,
                    eval = { invocation, deadline -> bridge.evaluate(invocation, deadline) },
                    onTaskBoundary = { bridge.reset() },
                )
            } finally {
                this@DirectJsRuntime.hostCallback.set(null)
            }
        }

        override fun close() = bridge.close()
    }

    /** 生产同款主进程装配面：递给 `ScriptBookParser` 的就是它 */
    val host: JsSandboxHost by lazy {
        val router = HostCallbackRouter()
        JsSandboxHost(
            client = JsSandboxClient(
                openChannel = { channel },
                limits = limits,
                clock = bootNow,
                isMainThread = { false },
                hostHandler = router,
            ),
            baseClient = OkHttpClient(),
            limits = limits,
            router = router,
        )
    }

    /*
     * 时钟：native 侧 boot_time_ms() 是「含休眠的开机毫秒」（Windows=GetTickCount64，
     * Linux=CLOCK_BOOTTIME），Java 拿不到同一时基。办法：二分把「此刻的开机毫秒」校准出来
     * （每次探针是一次空 eval），之后按已流逝的单调时间外推。校准失败退回常数时钟——
     * 那会让 deadline 恒远大于 now（等于失去超时保护），但不会错杀正常执行。
     */
    val bootNow: () -> Long = { bootBase + (System.nanoTime() - calibratedAtNano) / 1_000_000 }

    private val bootBase: Long by lazy { calibrateBootMs() }
    private val calibratedAtNano: Long by lazy { System.nanoTime() }

    /**
     * 判据：native 侧 `boot_time_ms() >= deadline` 即打断，故「deadline=X 的探针 TIMEOUT」⇔ X ≤ boot。
     * **探针必须带循环**：内核只在循环回边/语句边界查中断，单语句程序（`"0"`）一次跑完、
     * 从不触碰检查点——拿它校准会得到「永远 OK」、把 boot 塌缩成 1，之后所有真实求值的
     * deadline 都在过去，多语句脚本一进解析就被打断（现场是 `expecting ';'`+TIMEOUT，极难读）。
     */
    private fun calibrateBootMs(): Long {
        val probe = JsInvocation(JsMode.SEGMENT, BOOT_PROBE, emptyMap())
        var hi = 1L shl 34            // ≈194 天，比绝大多数机器的开机时长都大
        var guard = 0
        while (bridge.evaluate(probe, hi).status == JsStatus.TIMEOUT) {
            hi = hi shl 1
            if (++guard > 8) return 0L
        }
        var lo = 0L
        while (hi - lo > 1 && guard++ < 64) {
            val mid = (lo + hi) ushr 1
            if (bridge.evaluate(probe, mid).status == JsStatus.TIMEOUT) lo = mid else hi = mid
        }
        return hi
    }

    /** 20 万次空转：足够多次触碰中断检查点，又在正常求值预算内一次跑得完 */
    private const val BOOT_PROBE = "for (var i = 0; i < 200000; i++);"
}
