package com.ebook.source.sandbox

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.SystemClock
import com.xrn1997.common.util.Logger

/**
 * `:js` 隔离进程里唯一的组件：一次 binder 事务换成一次内核执行。
 *
 * 它刻意薄到只有三件事：读 Parcel、把 [runSandboxTask] 的判断结果写回 Parcel、把 host 调用
 * 中继给主进程。策略在 [runSandboxTask]（JVM 可测），内核在 `JsRuntimeBridge`（真机可测），
 * 三者不混在 `onTransact` 里——混在一起的后果是那两条真正会咬人的路径只能在设备上碰运气。
 *
 * **不抛异常出去**：`onTransact` 抛出的东西到了主进程只剩一次事务失败，真话（版本不合、
 * 帧坏掉、内核炸了）留在被丢掉的栈里。所以每一条出口都必须是 [runSandboxTask] 那样的帧。
 *
 * 线程模型：binder 有自己的线程池（默认至多 15 条），**同时只有一帧在飞的保证来自
 * `JsSandboxClient` 的 `inFlight` 锁（Task 6），不是来自这里**。那是 [currentCallback]
 * 能用一个 ThreadLocal 而不用队列 ID 的前提——两侧任一放开并发，这里就得改成按事务记账。
 * host 回调发生在发起它的那条 binder 线程上（`nativeEval` 里同步 transact），
 * 所以 ThreadLocal 读得到；主进程那一侧由它自己的线程池受理，不会与阻塞中的调用线程相抢。
 */
class SandboxService : Service() {

    private val tag = "SandboxService"
    private val limits = JsLimits()
    private val bridge = JsRuntimeBridge(limits)

    /** 本次 execute 带回主进程的回调通道；只在 onTransact 期间有值 */
    private val currentCallback = ThreadLocal<IBinder?>()

    private val executor = object : Binder() {

        /**
         * binder 的管家事务必须在 token 检查**之前**分诊出去。
         *
         * `DUMP_TRANSACTION`、`INTERFACE_TRANSACTION` 的数据里不带我们的接口标识，先 `enforceInterface`
         * 就会把它们一起拒掉；而本机 SDK 里 `Binder.onTransact` 的默认实现是处理这两个码的
         * （`sources/android-35/android/os/Binder.java:1012-1032`）。覆写而不转 `super`，等于顺手丢掉
         * `dumpsys` 与 `queryLocalInterface`——前者是排查沙箱时唯一能从进程外看到它的入口。
         *
         * `PING_TRANSACTION` 相反：Java 侧的默认实现**不**处理它，`pingBinder()` 拿到的就是子类的返回值。
         * 它的契约是「宿主进程没了才 false」（`IBinder.java:207-215`：otherwise the result, always by
         * default true），而执行器活着，所以自己回 true，不去猜 native 那层怎么兜。
         */
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean = when (code) {
            IBinder.PING_TRANSACTION -> true
            IBinder.DUMP_TRANSACTION, IBinder.INTERFACE_TRANSACTION -> super.onTransact(code, data, reply, flags)
            else -> handleContractCall(code, data, reply)
        }
    }

    /**
     * 两个自有事务码的入口：先认接口标识与协议版本，再按码分派。
     *
     * 读的次序必须与 [SandboxContract] 各事务码注释里写的写字次序逐字一致。两侧不一致时这里
     * 读到的是垃圾值，所以标识与版本先判、内容后读——版本不合连帧体都不去看。
     */
    private fun handleContractCall(code: Int, data: Parcel, reply: Parcel?): Boolean {
        // token 不匹配说明递错了 binder：拒掉这一次，不去猜对方的字段次序
        val token = runCatching { data.enforceInterface(SandboxContract.INTERFACE_TOKEN) }
        if (token.isFailure) {
            Logger.w(tag, "沙箱事务的接口标识不符，已拒绝：${token.exceptionOrNull()?.message}")
            return false
        }
        val version = data.readInt()
        if (version != SandboxContract.PROTOCOL_VERSION) {
            Logger.w(tag, "沙箱协议版本不合：对端 $version，本地 ${SandboxContract.PROTOCOL_VERSION}")
            writeFrameReply(reply, JsProtocol.encodeOutcome(JsOutcome(JsStatus.UNAVAILABLE, error = "沙箱协议版本不合")))
            return true
        }
        return when (code) {
            SandboxContract.TX_EXECUTE -> handleExecute(data.readString(), data.readStrongBinder(), reply)
            SandboxContract.TX_PING -> handlePing(reply)
            else -> false
        }
    }

    private fun handleExecute(requestFrame: String?, callback: IBinder?, reply: Parcel?): Boolean {
        currentCallback.set(callback)
        val frame = try {
            runSandboxTask(
                requestFrame = requestFrame,
                limits = limits,
                now = { SystemClock.elapsedRealtime() },
                eval = { invocation, deadline -> bridge.evaluate(invocation, deadline) },
                onTaskBoundary = { bridge.reset() },
            )
        } finally {
            currentCallback.set(null)
        }
        writeFrameReply(reply, frame)
        return true
    }

    /**
     * 探活：只回一个 ready 位，不建 runtime、不跑内核。
     *
     * 存在的理由是把「服务在但桥接层没就绪」（`.so` 加载失败）与「服务不在」分开——主进程对前者的
     * 话术是「这台设备的沙箱装不上」，对后者是「重连一次」。回帧里不写 outcome 帧：探活不该付一次
     * JSON 编解码的代价，字段次序见 [SandboxContract.TX_PING] 的注释。
     */
    private fun handlePing(reply: Parcel?): Boolean {
        // 单向事务（FLAG_ONEWAY）下 reply 是 null，任何往它写的代码都必须先判空——这条对两个事务同样成立
        if (reply != null) {
            reply.writeInterfaceToken(SandboxContract.INTERFACE_TOKEN)
            reply.writeInt(SandboxContract.PROTOCOL_VERSION)
            reply.writeInt(if (bridge.isReady) 1 else 0)
        }
        return true
    }

    /** 回帧的统一写法：token、version、outcomeFrame（次序与 [SandboxContract.TX_EXECUTE] 的注释一致） */
    private fun writeFrameReply(reply: Parcel?, frame: String) {
        if (reply == null) return
        reply.writeInterfaceToken(SandboxContract.INTERFACE_TOKEN)
        reply.writeInt(SandboxContract.PROTOCOL_VERSION)
        reply.writeString(frame)
    }

    override fun onCreate() {
        super.onCreate()
        // 执行器侧只有一类能力需要回主进程；COMPUTE 类由 HostDispatcher 就地算，不经 binder
        HostDispatcher.installRelay { api, argsJson -> requestHost(api, argsJson) }
    }

    override fun onDestroy() {
        HostDispatcher.clearRelay()
        currentCallback.remove()
        bridge.close()
        super.onDestroy()
    }

    /**
     * 不给不隔离的进程发通道。
     *
     * 「这个进程真的被隔离了吗」唯一的保证来自 manifest 上那一个属性，而它一旦被改掉（例如 Step 5
     * 说的退路落地成 `android:process` 却没有回头改这里），执行器就带着「脚本能读私有目录」跑起来：
     * 功能上一切正常，安全边界整片没了——这类静默失效必须自己判掉。
     * 返回 null 意味着主进程拿不到可用 binder，最终只会归成一次连接超时（[JsSandboxConnector]，Step 7），
     * 也就是宁可「沙箱不可用」，也不在不设防的进程里跑别人的脚本。
     */
    override fun onBind(intent: Intent?): IBinder? {
        if (!SandboxProcess.isInIsolatedProcess) {
            Logger.e(tag, "SandboxService 运行在非隔离进程中，拒绝提供通道")
            return null
        }
        return executor
    }

    /**
     * 把脚本的一次 host 调用同步搬到主进程。
     *
     * 返回 [HostReply] 而不是帧字符串：`HostDispatcher` 是唯一认识「回复长什么样」的地方，
     * 这里只管搬运。任何异常都换成一帧 `ok=false`——异常若继续外抛，落点是执行器进程的
     * JNI 栈，整个 `:js` 会当场没，而主进程只会把它归成一次断连。
     *
     * 两个方向的报文各用与自己同名的限值：发出去的是**请求**（[JsLimits.maxRequestBytes]，
     * 装的是一个 URL 与请求头），收回来的是**整页 HTML**（[JsLimits.maxHostReplyBytes]）。
     */
    private fun requestHost(api: String, argsJson: String): HostReply {
        val callback = currentCallback.get()
            ?: return HostReply(ok = false, data = null, error = "本次执行没有带回主进程通道")
        val callFrame = JsProtocol.encodeHostCall(api, argsJson)
        if (utf8Length(callFrame) > limits.maxRequestBytes) {
            return HostReply(ok = false, data = null, error = "host 调用帧超出 ${limits.maxRequestBytes} 字节上限")
        }
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(SandboxContract.CALLBACK_TOKEN)
            data.writeInt(SandboxContract.PROTOCOL_VERSION)
            data.writeString(callFrame)
            if (!callback.transact(SandboxContract.TX_HOST_CALL, data, reply, 0)) {
                return HostReply(ok = false, data = null, error = "主进程未受理 host 回调")
            }
            val token = runCatching { reply.enforceInterface(SandboxContract.CALLBACK_TOKEN) }
            if (token.isFailure) {
                return HostReply(ok = false, data = null, error = "主进程回调通道标识不符")
            }
            if (reply.readInt() != SandboxContract.PROTOCOL_VERSION) {
                return HostReply(ok = false, data = null, error = "主进程协议版本不合")
            }
            val frame = reply.readString()
                ?: return HostReply(ok = false, data = null, error = "主进程回了空帧")
            // 主进程侧已按同一口径判过一次（Task 6 的回帧闸门），这里再判是消费侧的自保：
            // 限值的意义是「不让超限的字节进解析」，只信发送侧等于把这句话挂在对端的版本上
            val bytes = utf8Length(frame)
            if (bytes > limits.maxHostReplyBytes) {
                return HostReply(
                    ok = false,
                    data = null,
                    error = "主进程回复超出 ${limits.maxHostReplyBytes} 字节上限（实际 $bytes 字节）",
                )
            }
            runCatching { JsProtocol.decodeHostReply(frame) }
                .getOrElse { HostReply(ok = false, data = null, error = "主进程回复解不出来") }
        } catch (t: Throwable) {
            Logger.w(tag, "host 回调失败：$api", t)
            HostReply(ok = false, data = null, error = "主进程回调失败：${t.message?.take(120)}")
        } finally {
            data.recycle()
            reply.recycle()
        }
    }
}
