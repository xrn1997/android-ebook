package com.ebook.common.util

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Looper
import android.os.Process
import android.util.Log
import com.xrn1997.common.util.ToastUtil
import java.io.File
import java.io.FileOutputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.lang.ref.WeakReference
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Properties

class CrashHandler private constructor() : Thread.UncaughtExceptionHandler {

    private var mContext: WeakReference<Context>? = null
    private var mDefaultHandler: Thread.UncaughtExceptionHandler? = null
    private val mDeviceCrashInfo = Properties()

    // 初始化，注册Context，获取系统默认的UncaughtException处理器
    fun init(context: Context) {
        mContext = WeakReference(context.applicationContext)  // 使用弱引用保存context
        mDefaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler(this)
    }

    override fun uncaughtException(thread: Thread, ex: Throwable) {
        if (!handleException(ex) && mDefaultHandler != null) {
            mDefaultHandler?.uncaughtException(thread, ex)
        } else {
            val context = mContext?.get()
            if (context != null) {
                ToastUtil.showShort(context, "发生异常，已重启应用")
                val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
                intent?.apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    context.startActivity(this)
                }
            }

            Process.killProcess(Process.myPid())
        }
    }

    private fun handleException(ex: Throwable?): Boolean {
        if (ex == null) {
            return false
        }
        val context = mContext?.get() ?: return false
        Thread {
            Looper.prepare()
            ToastUtil.showShort(context, "程序异常，请重启应用")
            Looper.loop()
        }.start()
        collectCrashDeviceInfo(context)
        saveCrashInfoToFile(ex)

        return true
    }

    private fun collectCrashDeviceInfo(context: Context) {
        try {
            val pm = context.packageManager
            val pi = pm.getPackageInfo(context.packageName, PackageManager.GET_ACTIVITIES)
            pi?.let {
                mDeviceCrashInfo["versionName"] = it.versionName ?: "not set"
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error while collecting package info: $e")
        }

        for (field in Build::class.java.declaredFields) {
            try {
                field.isAccessible = true
                mDeviceCrashInfo[field.name] = field.get(null)?.toString() ?: "null"
                if (DEBUG) {
                    Log.d(TAG, "${field.name} : ${field.get(null)}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error while collecting crash info: $e")
            }
        }
    }

    private fun saveCrashInfoToFile(ex: Throwable): String? {
        val result = StringWriter().also {
            ex.printStackTrace(PrintWriter(it))
        }.toString()

        mDeviceCrashInfo["STACK_TRACE"] = result

        try {
            val timestamp = SimpleDateFormat("yyyyMMddHHmmss", Locale.CHINA).format(Date())
            val fileName = "crash-$timestamp.txt"
            val context = mContext?.get() ?: return null
            val directory = File(context.externalCacheDir, "logs").apply {
                if (!exists()) mkdirs()
            }

            val file = File(directory, fileName)
            FileOutputStream(file).use {
                it.write(result.toByteArray())
            }

            return fileName
        } catch (e: Exception) {
            Log.e(TAG, "Error while saving crash info to file: $e")
        }
        return null
    }

    companion object {
        private val TAG = CrashHandler::class.java.simpleName
        private const val DEBUG = true

        @Volatile
        private var instance: CrashHandler? = null

        // 获取CrashHandler单例实例，线程安全
        fun getInstance(): CrashHandler {
            return instance ?: synchronized(this) {
                instance ?: CrashHandler().also { instance = it }
            }
        }
    }
}
