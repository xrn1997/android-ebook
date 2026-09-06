package com.ebook.book

import android.os.Debug

/**
 * 基线计时用的堆快照，单位 KB（native heap，含 SQLite 页缓存）。
 *
 * 为什么单独立一个类型而不内联一行调用：导入链路的内存增量主要落在 native 堆
 * （Room 3 用 `BundledSQLiteDriver`，页缓存与语句 prepared 分配都在 Java 堆之外），
 * 取数口径必须固定且可注释，避免改前/改后两次测量各用一种堆快照而得出不可比的结论。
 */
object DebugMemory {

    /** 当前进程已分配的 native 堆字节数折算成 KB；同一进程内两次取值的差即为链路增量。 */
    fun snapshotKb(): Long = Debug.getNativeHeapAllocatedSize() / 1024
}
