package com.ebook.source.sandbox

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import com.ebook.source.script.SandboxUnavailableException
import com.xrn1997.common.util.Logger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 建一条通道：`bindService` 拉起 `:js`，等 binder，交给 [BinderJsChannel]。
 *
 * 每次 [open] 都是一次独立的绑定，解绑挂在返回通道的 `close()` 上——**不把 Connection 存在本类字段里**：
 * 客户端重连时旧的 Connection 必须与旧通道一起作废，否则旧 binder 的解绑会顺手把新绑定的连接掐掉。
 *
 * 回调投递在调用方的主线程上（`bindService` 带 Executor 的重载是 API 29 才有，本仓 minSdk 26）。
 * 这不构成新风险：[JsSandboxClient] 已有主线程守卫，绝不会有 execute 停在主线程上等这次回调。
 * 代价是主线程长时间占住时连接会等到 [JsLimits.bindTimeoutMs] 超时，症状是一句可命名的「不可用」，
 * 而不是无声卡住。
 */
class JsSandboxConnector(
    context: Context,
    private val limits: JsLimits = JsLimits(),
) {

    private val appContext = context.applicationContext
    private val tag = "JsSandboxConnector"

    /**
     * 连不上时抛 [SandboxUnavailableException] 而不是 [ChannelDeadException]：
     * 客户端对后者会「重连并重放一次」，而一次都没连上的调用没有可重放的副作用，
     * 重放只是再等一遍 [JsLimits.bindTimeoutMs]——把一个 2 秒的坏消息变成 4 秒。
     */
    fun open(): JsChannel {
        val connection = Connection()
        val started = runCatching {
            appContext.bindService(
                Intent(appContext, SandboxService::class.java),
                connection,
                Context.BIND_AUTO_CREATE,
            )
        }.getOrElse {
            Logger.e(tag, "绑定 :js 执行器时抛异常", it)
            throw SandboxUnavailableException("绑定脚本沙箱执行器失败：${it.message?.take(120)}", it)
        }
        if (!started) {
            // 服务不存在/被禁用时系统不会回调任何一次 onServiceConnected，所以这里必须自己收口
            throw SandboxUnavailableException("系统拒绝绑定脚本沙箱执行器（服务不存在或被禁用）")
        }
        val binder = try {
            connection.await(limits.bindTimeoutMs)
        } catch (t: Throwable) {
            unbind(connection)
            throw t
        }
        return BinderJsChannel(binder) { unbind(connection) }
    }

    private fun unbind(connection: Connection) {
        runCatching { appContext.unbindService(connection) }
            .onFailure { Logger.w(tag, "解绑 :js 执行器失败（连接已作废，忽略）：${it.message}") }
    }

    /**
     * 一次绑定的账本。四个回调都可能不来，所以等待必须有期限而不是无限 wait。
     *
     * 闩而不是 `Object.wait`：Kotlin 的 `Any` 不暴露 `wait()`/`notifyAll()`（那是 `java.lang.Object`
     * 的成员，Kotlin 把它映射成 `Any` 时一并挡住了），拿 `synchronized + wait` 写这里会直接编不过。
     * 换成一次性闩不改变语义：一次绑定只等一个结果，`countDown` 在写状态之后调用，
     * 于是 `await` 返回 true 的一侧由闩自身提供 happens-before。
     */
    private class Connection : ServiceConnection {

        /** 两个字段各写一次、由等待方在闩之后读；超时那一路没有闩的可见性保证，故标 @Volatile */
        @Volatile
        private var binder: IBinder? = null

        @Volatile
        private var failure: String? = null

        private val arrived = CountDownLatch(1)

        override fun onServiceConnected(name: ComponentName, service: IBinder?) {
            // service 声明成可空：onNullBinding 是 API 28 才有的投递方式，在 26/27 上 onBind 返回 null
            // 走的是这里，参数为 null。两条路径必须给同一句话术，否则同一台设备的坏消息会随版本变。
            if (service == null) {
                failure = "执行器拒绝提供通道（多半是当前进程未被隔离）"
            } else {
                binder = service
            }
            arrived.countDown()
        }

        override fun onNullBinding(name: ComponentName) {
            failure = "执行器拒绝提供通道（多半是当前进程未被隔离）"
            arrived.countDown()
        }

        override fun onBindingDied(name: ComponentName) {
            failure = "执行器的绑定已死（服务被系统回收）"
            arrived.countDown()
        }

        /**
         * 进程死了但连接还留着：不翻脸、不清 binder。
         *
         * 这里的 binder 已经成了 dead object，下一次 transact 会抛 DeadObjectException，
         * 客户端据此判死并重连——那条路径已经有用例锁住。在此处置空只会让「谁杀的进程」这件事
         * 从两条互相矛盾的日志里出现，反而更难判。
         */
        override fun onServiceDisconnected(name: ComponentName) {
            Logger.w("JsSandboxConnector", ":js 执行器进程断开，交给下一次事务去判死")
        }

        /**
         * 等一个结果，最多 [timeoutMs] 毫秒。
         *
         * 被中断时 `await` 抛 [InterruptedException]：这里按「没等到」处置，因为对调用方来说超时与
         * 中断给的是同一句人话「沙箱不可用」，而把中断异常穿给 [JsSandboxClient] 只会多一条它不认识
         * 的失败形态。但中断标志必须显式复原——吞掉它等于告诉上层线程池「没人要我停」，
         * 那个线程会带着本该中断的姿势继续跑下去。
         */
        fun await(timeoutMs: Long): IBinder {
            val signaled = try {
                arrived.await(timeoutMs, TimeUnit.MILLISECONDS)
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                false
            }
            if (!signaled) {
                Logger.w("JsSandboxConnector", "等待 :js 执行器连接超时（${timeoutMs} ms），检查是否已回调")
            }
            binder?.let { return it }
            throw SandboxUnavailableException(
                failure ?: "等待 :js 执行器连接超时（${timeoutMs} ms）",
            )
        }
    }
}
