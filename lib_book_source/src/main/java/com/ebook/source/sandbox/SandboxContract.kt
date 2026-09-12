package com.ebook.source.sandbox

/**
 * 主进程与 `:js` 执行器之间**线上形态**的唯一事实源：接口标识、协议版本、事务码。
 *
 * 为什么手写 Binder 而不用 AIDL：协议只有一对方法，而它站在安全关键路径上——
 * 生成的桩里看不见的东西没法审计，多一步代码生成就多一处「改了 .aidl 忘了重编」的缝隙。
 * 把线上形态收在这一个纯 Kotlin 文件里，任何改动都会在这里留下痕迹。
 *
 * 本文件刻意不含 `android.*`：事务码就是两个小整数，两侧对齐即可，不必引
 * `Binder.FIRST_CALL_TRANSACTION`，于是它在 JVM 单测里能直接加载。
 */
object SandboxContract {

    /** 事务数据的第一字段。对端不是本服务时当场判不匹配，而不是把随机内存当帧读 */
    const val INTERFACE_TOKEN: String = "com.ebook.source.sandbox.IJsExecutor"

    /** 控制面协议版本：Parcel 布局一变就 +1，两侧不一致当场报不可用，绝不去猜对方的字段顺序 */
    const val PROTOCOL_VERSION: Int = 1

    /**
     * 一次 execute。
     * 写：token、version、requestFrame、host 回调 binder（可 null）。
     * 回：token、version、outcomeFrame。
     */
    const val TX_EXECUTE: Int = 1

    /**
     * 探活：只验 token 与版本，不建 runtime。用来把「服务在但协议不合」与「服务不在」分开。
     * 回：token、version、ready（1=桥接层能跑，0=`.so` 没装上）。不回帧体——探活不该付一次内核调用的代价
     */
    const val TX_PING: Int = 2

    /**
     * 反方向（执行器 → 主进程）的接口标识。
     *
     * 单独一个 token 而不是复用 [INTERFACE_TOKEN]：两端的实现方不同（一个是执行器、一个是主进程），
     * 传错 binder 时 `enforceInterface` 要能当场拒掉，而不是把对方的帧当成自己的读下去。
     */
    const val CALLBACK_TOKEN: String = "com.ebook.source.sandbox.IHostCallback"

    /**
     * 一次 host 调用。事务码在 [CALLBACK_TOKEN] 这个接口上编号，与 [TX_EXECUTE] 分属两个空间。
     * 写：token、version、callFrame。回：token、version、replyFrame。
     */
    const val TX_HOST_CALL: Int = 1
}
