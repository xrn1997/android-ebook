package com.ebook.source.sandbox

import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.RemoteException
import com.ebook.source.script.SandboxProtocolException
import com.xrn1997.common.util.Logger

/**
 * [JsChannel] 的 binder 实现：一条连上的执行器 + 一次事务一帧。
 *
 * public 而非 internal：Task 11 的 Hilt 装配在 `lib_book_common`，跨模块看不见 internal 类型。
 *
 * 失败归类只有一条判据：**「还能不能指望这条通道」而不是「谁错了」**。
 * 传输层的失败（对端没、缓冲炸了）一律 [ChannelDeadException]，客户端据此重连；
 * 帧形态不对（标识/版本不合）一律 [SandboxProtocolException]，重连不会改变它，客户端直接上抛。
 * 把后者混进前者，现场会变成「每次解析都要先断连重连一次再失败」，把根因（两侧代码不同步）埋掉。
 */
class BinderJsChannel(
    /** 连上的执行器 binder。由 [JsSandboxConnector] 给出，本类不负责它的生死 */
    private val executor: IBinder,
    /** 关掉通道时的收尾：连接器给的解绑。多次调用幂等由本类保证，解绑自己抛（已经死了）不外泄 */
    private val onClose: () -> Unit = {},
) : JsChannel {

    private val tag = "BinderJsChannel"

    @Volatile
    private var closed = false

    override val isOpen: Boolean get() = !closed && executor.isBinderAlive

    /**
     * 一帧进、一帧出。
     *
     * 回调 binder **每次执行新建**：它捕获的是这一次 execute 的账本（`JsSandboxClient` 里的
     * `attempt.relayed` 与回调深度标记），复用会让下一次的回调打到上一次的账本上——那正是
     * 「重放预算被上一次污染」那条用例锁住的东西，只是这次跨了进程。
     */
    override fun execute(requestFrame: String, hostCallback: (String) -> String): String {
        if (closed) throw ChannelDeadException("通道已关闭")
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(SandboxContract.INTERFACE_TOKEN)
            data.writeInt(SandboxContract.PROTOCOL_VERSION)
            data.writeString(requestFrame)
            data.writeStrongBinder(HostCallbackBinder(hostCallback))
            if (!executor.transact(SandboxContract.TX_EXECUTE, data, reply, 0)) {
                throw ChannelDeadException("execute 事务未被执行器受理")
            }
            readReplyFrame(reply)
        } catch (remote: RemoteException) {
            // DeadObjectException（执行器被杀）与 TransactionTooLargeException（缓冲被别的在飞事务占满）
            // 都是 RemoteException 的子类，一起按「这条通道不能再用」判。刻意不分别 catch：
            // android-31 起 TransactionTooLargeException 的直接父类是 hidden 的 TransactionFailedException，
            // 在依赖方按类型 catch 它会引入「超类型不可见」的编译告警，而这里的处置两者相同。
            Logger.w(tag, "沙箱事务失败，通道判死：${remote.message}")
            throw ChannelDeadException("执行器不可达：${remote.message}", remote)
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { onClose() }
    }

    /** 读次序与 [SandboxContract.TX_EXECUTE] 的写次序逐字对应；读不动一律判协议不合，不降级成断连 */
    private fun readReplyFrame(reply: Parcel): String {
        val token = runCatching { reply.enforceInterface(SandboxContract.INTERFACE_TOKEN) }
        if (token.isFailure) {
            throw SandboxProtocolException("执行器回帧的接口标识不符", token.exceptionOrNull())
        }
        val version = reply.readInt()
        if (version != SandboxContract.PROTOCOL_VERSION) {
            throw SandboxProtocolException("执行器协议版本不合：对端 $version，本地 ${SandboxContract.PROTOCOL_VERSION}")
        }
        return reply.readString() ?: throw SandboxProtocolException("执行器回了空帧")
    }
}

/**
 * 一次 execute 期间的反向通道：执行器把 host 调用送到这里，主进程中继给 [JsSandboxClient] 的回调。
 *
 * 文件级 private：只有 [BinderJsChannel.execute] 构造它，露出去只会多出一条「绕开 execute 自己绑一个」
 * 的错误用法。它与 execute 事务里 `writeStrongBinder` 写进去的是同一个对象，生命周期同步。
 */
private class HostCallbackBinder(private val relay: (String) -> String) : Binder() {

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean = when (code) {
        // 与执行器侧同一套分诊：管家事务先给框架，否则 enforceInterface 会把它们一起拒掉
        IBinder.PING_TRANSACTION -> true
        IBinder.DUMP_TRANSACTION, IBinder.INTERFACE_TRANSACTION -> super.onTransact(code, data, reply, flags)
        SandboxContract.TX_HOST_CALL -> handleCall(data, reply)
        else -> false
    }

    private fun handleCall(data: Parcel, reply: Parcel?): Boolean {
        val token = runCatching { data.enforceInterface(SandboxContract.CALLBACK_TOKEN) }
        if (token.isFailure) {
            Logger.w("HostCallbackBinder", "host 回调的接口标识不符，已拒绝：${token.exceptionOrNull()?.message}")
            return false
        }
        if (data.readInt() != SandboxContract.PROTOCOL_VERSION) {
            writeCallReply(reply, failure("主进程与执行器协议版本不合"))
            return true
        }
        val callFrame = data.readString()
        if (callFrame == null) {
            writeCallReply(reply, failure("host 调用帧为空"))
            return true
        }
        // relay（JsSandboxClient.relay）自己已经吞掉一切异常；这里再兜一层是因为契约的下一任实现
        // 未必守得住，而异常从这条线程穿出去落在执行器的 JNI 栈上——整个 :js 会当场没
        writeCallReply(reply, runCatching { relay(callFrame) }.getOrElse { failure("主进程中继失败：${it.message?.take(120)}") })
        return true
    }

    private fun failure(message: String): String = JsProtocol.encodeHostReply(ok = false, data = null, error = message)

    private fun writeCallReply(reply: Parcel?, frame: String) {
        if (reply == null) return
        reply.writeInterfaceToken(SandboxContract.CALLBACK_TOKEN)
        reply.writeInt(SandboxContract.PROTOCOL_VERSION)
        reply.writeString(frame)
    }
}
