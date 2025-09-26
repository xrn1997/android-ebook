package com.ebook.common.util

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.PixelCopy
import android.view.WindowManager
import android.view.WindowMetrics
import androidx.appcompat.app.AppCompatActivity

@Suppress("unused")
object ScreenUtils {
    private val TAG = this::class.java.simpleName

    /**
     * 获得屏幕宽度
     */
    @JvmStatic
    fun getScreenWidth(context: Context): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val windowMetrics: WindowMetrics =
                context.getSystemService(WindowManager::class.java).currentWindowMetrics
            windowMetrics.bounds.width()
        } else {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val outMetrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getMetrics(outMetrics)
            outMetrics.widthPixels
        }
    }

    /**
     * 获得屏幕高度
     */
    @JvmStatic
    fun getScreenHeight(context: Context): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val windowMetrics: WindowMetrics =
                context.getSystemService(WindowManager::class.java).currentWindowMetrics
            windowMetrics.bounds.height()
        } else {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val outMetrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getMetrics(outMetrics)
            outMetrics.heightPixels
        }
    }

    /**
     * 获取状态栏高度
     */
    @JvmStatic
    fun getStatusHeight(context: Context): Int {
        val rectangle = Rect()
        val window = (context as Activity).window
        window.decorView.getWindowVisibleDisplayFrame(rectangle)
        return rectangle.top
    }

    /**
     * 使用 PixelCopy 获取当前屏幕截图，包含状态栏
     */
    @JvmStatic
    fun snapShotWithStatusBar(activity: AppCompatActivity, callback: (Bitmap?) -> Unit) {
        val window = activity.window
        val width = getScreenWidth(activity)
        val height = getScreenHeight(activity)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

        // 使用 PixelCopy 进行截图
        PixelCopy.request(window, bitmap, { copyResult ->
            if (copyResult == PixelCopy.SUCCESS) {
                callback(bitmap)
            } else {
                callback(null)
            }
        }, Handler(Looper.getMainLooper()))
    }

    /**
     * 使用 PixelCopy 获取当前屏幕截图，不包含状态栏
     */
    @JvmStatic
    fun snapShotWithoutStatusBar(activity: AppCompatActivity, callback: (Bitmap?) -> Unit) {
        val window = activity.window
        val frame = Rect()
        window.decorView.getWindowVisibleDisplayFrame(frame)
        val statusBarHeight = frame.top
        val width = getScreenWidth(activity)
        val height = getScreenHeight(activity) - statusBarHeight
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

        // 使用 PixelCopy 进行截图
        PixelCopy.request(window, bitmap, { copyResult ->
            if (copyResult == PixelCopy.SUCCESS) {
                callback(bitmap)
            } else {
                callback(null)
            }
        }, Handler(Looper.getMainLooper()))
    }
}

