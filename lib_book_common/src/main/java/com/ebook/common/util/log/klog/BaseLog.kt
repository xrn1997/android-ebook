package com.ebook.common.util.log.klog

import android.util.Log

object BaseLog {
    private const val MAX_LENGTH = 4000

    @JvmStatic
    fun printDefault(msg: String, tag: String = "BaseLog", type: Int = KLog.D) {
        var index = 0
        val length = msg.length
        val countOfSub = length / MAX_LENGTH

        if (countOfSub > 0) {
            for (i in 0 until countOfSub) {
                val sub = msg.substring(index, index + MAX_LENGTH)
                printSub(sub, tag, type)
                index += MAX_LENGTH
            }
            printSub(msg.substring(index, length), tag, type)
        } else {
            printSub(msg, tag, type)
        }
    }

    private fun printSub(sub: String, tag: String = "BaseLog", type: Int = KLog.D) {
        when (type) {
            KLog.V -> Log.v(tag, sub)
            KLog.D -> Log.d(tag, sub)
            KLog.I -> Log.i(tag, sub)
            KLog.W -> Log.w(tag, sub)
            KLog.E -> Log.e(tag, sub)
            KLog.A -> Log.wtf(tag, sub)
        }
    }
}
