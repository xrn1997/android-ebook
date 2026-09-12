package com.ebook.source.sandbox

import android.os.Looper
import android.os.SystemClock
import com.ebook.source.script.SandboxProtocolException
import com.xrn1997.common.util.Logger

/**
 * 主进程侧的沙箱客户端，也是全部传输策略的唯一落点。
 *
 * 执行入口只有 [execute] 一个方法，不另铺便捷入口：[JsInvocation] 已经把「跑哪种载荷、带哪些变量、
 * 按哪种模式取结果」说全了，再铺四个便捷入口就是四个会各自漂移的口径。也不设 `isAvailable`：
 * 「没接线」由调用方手里的 null 表达（`JsSandboxHost?` 可空注入），而「接了但这次跑不起来」只在
 * 结果里才有意义——真探测本身就是一次跨进程调用，为一个布尔值付它不划算。
 * 实现**不得抛未类型化异常**：一切失败都进 [JsOutcome.status]，由调用方分类处置
 * （唯一的例外见 [execute] 的 [SandboxProtocolException] 分支：那是两侧代码不同步的接线缺陷，
 * 降级成「不可用」会把排查方向支到设备上）。
 *
 * 只做四件事：
 *
 * 1. **算截止日期**。时钟取 `SystemClock.elapsedRealtime()`——机器级单调钟，跨进程同一读数，
 *    所以主进程算出的期限在执行器里可比。`System.nanoTime()` 的原点是各进程随机的，
 *    用它就会变成「主进程以为自己还剩 3 秒、执行器以为还剩 10 秒」，超时形同虚设。
 * 2. **决定要不要重放**。只在本次调用还没转发过 host 回调时重放（见 [afterDisconnect]）。
 * 3. **中继 host 回调**。把执行器要的两类事（求值一条规则、代取一个 URL）交给 [HostHandler]，
 *    并且绝不把异常抛回执行器——那条异常会落在 `.so` 的调用栈上，症状是整个 `:js` 进程没。
 * 4. **挡住两类自伤**：主线程调用（阻塞至多 [JsLimits.wallClockMs] 毫秒，表现是 ANR 而不是解析失败）
 *    与回调里重入（双向死锁）。
 *
 * 一把非重入锁把 execute 串行化：一个执行器进程只有一个 runtime，多个源并发解析时排队比互抢干净；
 * 它同时保证「同一时刻只有一帧在飞」，[JsLimits.maxRequestBytes] 那 900 KB 的取值前提就是这条。
 * 排队的代价由截止日期兜住：排在别人后面的请求会带着同一个期限进场，等不到就报 TIMEOUT——
 * 这是真话，比让它悄悄多跑五秒要好。
 */
class JsSandboxClient(
    /** 建一条通道。真实现是 Task 8 的连接器；测试里给假通道，这就是本类能在 JVM 上测透的原因 */
    private val openChannel: () -> JsChannel,
    private val limits: JsLimits = JsLimits(),
    /** 单调毫秒钟，跨进程可比（见类 KDoc 第 1 条） */
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
    private val hostHandler: HostHandler = HostHandler { _, _ ->
        HostReply(ok = false, data = null, error = "主进程未接线 host 代理")
    },

    /**
     * 主线程判据。取「两个 Looper 同一实例」而不是 `myLooper() == null` 之类的近似：
     * 后者在没 Looper 的线程上会把后台线程误判成主线程，那等于把所有解析都禁掉。
     */
    private val isMainThread: () -> Boolean = {
        val main = Looper.getMainLooper()
        main != null && main === Looper.myLooper()
    },
) {

    private val tag = "JsSandboxClient"

    /** execute 串行锁，同时护住 [channel] 字段（两侧都在同一把锁里读写，不需要第二把） */
    private val inFlight = Any()

    private var channel: JsChannel? = null

    /** 深度 0 = 不在回调里。用 ThreadLocal 而不是全局计数：回调在 binder 线程上跑，与发起 execute 的线程不同 */
    private val insideHostCall = ThreadLocal<Int>()

    fun execute(invocation: JsInvocation): JsOutcome {
        check(!isMainThread()) {
            "脚本沙箱不能在主线程执行：一次执行最长阻塞 ${limits.wallClockMs} ms，症状会是 ANR 而不是解析失败"
        }
        // 重入守卫必须在拿锁之前。唯一的 runtime 正攥在回调的发起方手里，
        // 这里等下去是「我等它返回、它等我完成」的双向死锁，只能靠超时收场。
        if ((insideHostCall.get() ?: 0) > 0) {
            return unavailable("不支持嵌套执行：host 回调里又发起了一次沙箱调用")
        }
        val frame = JsProtocol.encodeRequest(invocation, clock() + limits.wallClockMs)
        if (utf8Length(frame) > limits.maxRequestBytes) {
            return JsOutcome(
                JsStatus.TOO_LARGE,
                error = "沙箱请求超出 ${limits.maxRequestBytes} 字节上限：绑定里的整页太大",
            )
        }
        val attempt = Attempt()
        return try {
            synchronized(inFlight) { runOnce(frame, attempt) }
        } catch (protocol: SandboxProtocolException) {
            // 帧读不出来 = 两侧代码不同步（.so 与 Kotlin 不是同一次构建）。这是接线缺陷，
            // 不能降级成「不可用」——那会把排查方向支到设备上，而根因在构建。
            synchronized(inFlight) { dropChannel() }
            throw protocol
        } catch (dead: ChannelDeadException) {
            synchronized(inFlight) { dropChannel() }
            afterDisconnect(frame, attempt, dead)
        } catch (t: Throwable) {
            // 通道实现抛出了没预料的东西（Parcel 写失败、对端 SDK 里的 RuntimeException）。
            // 接缝的契约是「不抛未类型化异常」，所以这里收下、换成一次可命名的不可用。
            Logger.e(tag, "沙箱通道抛出了未预期的异常", t)
            synchronized(inFlight) { dropChannel() }
            unavailable("通道异常：${t.message}")
        }
    }

    /**
     * 一次 execute 的私有账本：重放预算只看它。看客户端全局的话，并发时会把 A 的副作用算到 B 头上。
     *
     * [relayed] 必须 `@Volatile`：写它的 [relay] 由 `HostCallbackBinder.onTransact` 落在
     * **binder 线程池的那一条**上执行，读它的 [afterDisconnect] 在发起本次 execute 的调用线程上，
     * 两者之间没有任何共同锁（`inFlight` 只护通道句柄，[relay] 不在其临界区内）。
     * 缺可见性时调用线程可能读到陈旧的 0，把「本次已转发过回调、重放会让同一页被请求两次」
     * 判成可以重放——那正是这段 KDoc 承诺已经关掉的那条路径。
     * 姊妹件 [JsCallbackProxy] 的同名账本字段标了同一注解，理由相同；单写单读，故不必上原子类。
     */
    private class Attempt {
        @Volatile
        var relayed: Int = 0
    }

    /** 断连处置：本次没有副作用就重连重放一次，有副作用就停手 */
    private fun afterDisconnect(frame: String, attempt: Attempt, dead: ChannelDeadException): JsOutcome {
        if (attempt.relayed > 0) {
            Logger.w(tag, "沙箱在第 ${attempt.relayed} 次 host 回调之后断连，本次不重放：${dead.message}")
            return unavailable("断连且本次已转发 ${attempt.relayed} 次主进程回调，重放会让同一页被请求两次")
        }
        return try {
            Logger.w(tag, "沙箱通道已断，重连重放一次：${dead.message}")
            synchronized(inFlight) { runOnce(frame, attempt) }
        } catch (again: ChannelDeadException) {
            synchronized(inFlight) { dropChannel() }
            Logger.w(tag, "沙箱重连后仍不可用：${again.message}")
            unavailable("断连且重连失败")
        } catch (protocol: SandboxProtocolException) {
            throw protocol
        } catch (t: Throwable) {
            synchronized(inFlight) { dropChannel() }
            unavailable("重连后通道异常：${t.message}")
        }
    }

    /** 一次投递：拿通道（必要时新建）、执行、按上限解帧。只允许在 [inFlight] 锁内调用 */
    private fun runOnce(frame: String, attempt: Attempt): JsOutcome {
        val live = channel
        val ch = if (live != null && live.isOpen) live else openChannel().also { channel = it }
        val replyFrame = ch.execute(frame) { hostFrame -> relay(hostFrame, attempt) }
        return JsProtocol.decodeOutcome(replyFrame, limits)
    }

    /** 丢掉这条通道。close() 自己抛（对端已经没了）不能把处置打断，所以包在 runCatching 里 */
    private fun dropChannel() {
        val stale = channel
        channel = null
        runCatching { stale?.close() }
    }

    /**
     * 转发一次 host 调用。
     *
     * [attempt] 在这里加一：从这一刻起本次调用**有了不可重来的副作用**（脚本已经看到了一页内容、
     * 主进程已经替它请求过一次 URL），断连之后的重放窗口就此关掉。
     *
     * 回调期间给本线程打标记：Task 9 的代理会在这段里求值嵌套规则，那条路径可能又想要一次 JS——
     * [execute] 开头的守卫读的就是它。
     */
    private fun relay(callFrame: String, attempt: Attempt): String {
        attempt.relayed++
        val depth = (insideHostCall.get() ?: 0) + 1
        insideHostCall.set(depth)
        val reply = try {
            val (api, args) = JsProtocol.decodeHostCall(callFrame)
            hostHandler.handle(api, args)
        } catch (t: Throwable) {
            Logger.e(tag, "主进程 host 代理失败，已按 ok=false 回给脚本", t)
            HostReply(ok = false, data = null, error = "主进程处理失败：${t.message}")
        } finally {
            insideHostCall.set(depth - 1)
        }
        val frame = JsProtocol.encodeHostReply(reply.ok, reply.data, reply.error)
        return if (utf8Length(frame) > limits.maxHostReplyBytes) {
            Logger.w(tag, "host 回复超出 ${limits.maxHostReplyBytes} 字节上限，按失败回给脚本")
            JsProtocol.encodeHostReply(ok = false, data = null, error = "主进程回复超出字节上限")
        } else {
            frame
        }
    }

    private fun unavailable(reason: String): JsOutcome =
        JsOutcome(JsStatus.UNAVAILABLE, error = "沙箱执行器当前不可用：${reason.take(120)}")
}
