package com.ebook.common.util.log.klog

import android.text.TextUtils
import android.util.Log

object KLogUtil {
    @JvmStatic
    fun isEmpty(line: String): Boolean {
        return TextUtils.isEmpty(line) || line == "\n" || line == "\t" || TextUtils.isEmpty(line.trim { it <= ' ' })
    }

    @JvmStatic
    fun printLine(isTop: Boolean = true, tag: String? = "KLogUtil") {
        if (isTop) {
            Log.d(
                tag,
                "╔═══════════════════════════════════════════════════════════════════════════════════════"
            )
        } else {
            Log.d(
                tag,
                "╚═══════════════════════════════════════════════════════════════════════════════════════"
            )
        }
    }
}
