package com.ebook.common.util

import android.content.Context
import android.view.View
import android.view.inputmethod.InputMethodManager

/**
 * Description: <软键盘的显示与隐藏>
 *
 *  * 1.显示软键盘
 *  * 2.隐藏软键盘
 *
 */
object SoftInputUtil {
    /**
     * 显示软键盘
     */
    @JvmStatic
    fun showSoftInput(context: Context, view: View) {
        val imm = context.getSystemService(
            InputMethodManager::class.java
        ) // 显示软键盘
        imm.showSoftInput(view, 0)
    }

    /**
     * 隐藏软键盘
     */
    @JvmStatic
    fun hideSoftInput(context: Context, view: View) {
        val immHide = context.getSystemService(
            InputMethodManager::class.java
        ) // 隐藏软键盘
        immHide.hideSoftInputFromWindow(view.windowToken, 0)
    }
}