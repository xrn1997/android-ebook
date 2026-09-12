package com.ebook.source.sandbox

import android.os.Build
import android.os.Process

/**
 * 「当前进程是不是被系统隔离的沙箱进程」的唯一入口。
 *
 * 单独一个文件是因为判据有四个调用点、分属三个模块（这里的执行器自检、Step 8 的 `BookApplication`
 * 与 `MyApplication` 进程门、Step 9 的设备用例）。`API 28` 门槛写四遍，漏一遍就是 minSdk 26 的设备上
 * 一句 `NoSuchMethodError`；写成 public 对象是因为 `internal` 跨不到别的模块（AGENTS.md 的可见性口径：
 * 依赖方的 source set 不是 friend module）。
 *
 * 短路的次序是刻意的：`SDK_INT` 在前、`Process.isIsolated()` 在后。后者在 API 28 以下不存在，
 * 顺序写反等于在 26/27 的设备上第一次调用即崩。`SDK_INT` 是静态字段，在 JVM 单测里读作 0，
 * 于是整条判据在单测里恒为 false——**不会**去碰 mockable jar 里那些「Method … not mocked」的桩，
 * Step 8 改过的 `Application` 因此仍能在 JVM 上被实例化。
 *
 * 为什么拿「是不是隔离的」而不是「进程名是不是 `:js`」做判据：进程名由系统生成、随版本变，
 * 而我们要问的正是「这个进程有没有被削减能力」。判错的代价也不对称——名字比对万一在主进程误判，
 * 产出的是「整个 App 起来但没初始化」，比沙箱多跑几行初始化严重得多。
 */
object SandboxProcess {

    /** true 只可能出现在 manifest 上带 `android:isolatedProcess="true"` 的进程里 */
    val isInIsolatedProcess: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && Process.isIsolated()
}
