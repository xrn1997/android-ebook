package com.ebook.common.util

import android.app.Activity
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.text.TextUtils
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.FileProvider
import java.io.BufferedReader
import java.io.File
import java.io.IOException

/**
 * 环境工具类：提供与应用运行状态、存储、APK管理等相关的功能。
 */
@Suppress("unused")
object EnvironmentUtil {

    private val TAG = this::class.java.simpleName

    /**
     * 应用相关操作
     */
    object AppStatus {
        private var isAppInForeground: Boolean = false

        @JvmStatic
        fun registerActivityLifecycleCallbacks(application: Application) {
            application.registerActivityLifecycleCallbacks(object :
                Application.ActivityLifecycleCallbacks {
                override fun onActivityResumed(activity: Activity) {
                    isAppInForeground = true
                }

                override fun onActivityPaused(activity: Activity) {
                    isAppInForeground = false
                }

                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
                override fun onActivityStarted(activity: Activity) {}
                override fun onActivityStopped(activity: Activity) {}
                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
                override fun onActivityDestroyed(activity: Activity) {}
            })
        }

        /**
         * 判断当前应用是否运行在前台。
         */
        @JvmStatic
        fun isAppRunning(): Boolean {
            return isAppInForeground
        }

        /**
         * 获取应用当前状态：
         * 1: 前台运行
         * 2: 后台运行
         * 3: 未启动
         */
        @JvmStatic
        fun getAppStatus(): Int {
            return if (isAppInForeground) 1 else 2
        }
    }

    /**
     * 存储相关操作
     */
    object Storage {
        /**
         * 外部存储是否可读写
         */
        @JvmStatic
        val isExternalStorageWritable: Boolean
            get() = Environment.getExternalStorageState() == Environment.MEDIA_MOUNTED

        /**
         * 获取外部缓存目录
         */
        @JvmStatic
        fun getExternalCacheDir(context: Context): File? {
            return context.externalCacheDir?.takeIf { it.exists() || it.mkdirs() }
        }

        /**
         * 获取缓存路径
         */
        @JvmStatic
        fun getCachePath(context: Context): String {
            val file = if (isExternalStorageWritable) getExternalCacheDir(context) else null
            return file?.absolutePath ?: context.cacheDir.absolutePath
        }
    }

    /**
     * APK 相关操作
     */
    object Package {
        /**
         * 安装 APK 文件，注意，文件必须满足FileProvider中的路径设置
         */
        @JvmStatic
        fun installApp(context: Context, file: File) {
            val fileUri =
                FileProvider.getUriForFile(context, "${context.packageName}.provider.apk", file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(fileUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }

        /**
         * 卸载 APK
         */
        @JvmStatic
        fun unInstallApp(context: Context, packageName: String) {
            val intent = Intent(Intent.ACTION_DELETE, Uri.parse("package:$packageName")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }

        /**
         * 获取 APK 包名
         */
        @JvmStatic
        fun getApkFilePackage(context: Context, apkFile: File): String? {
            return context.packageManager.getPackageArchiveInfo(
                apkFile.path,
                PackageManager.GET_ACTIVITIES
            )?.applicationInfo?.packageName
        }

        /**
         * 检查应用是否已安装
         */
        @JvmStatic
        fun isAppInstalled(context: Context, packageName: String): Boolean {
            return if (!TextUtils.isEmpty(packageName)) {
                try {
                    context.packageManager.getApplicationInfo(
                        packageName,
                        PackageManager.GET_META_DATA
                    )
                    true
                } catch (e: PackageManager.NameNotFoundException) {
                    false
                }
            } else false
        }
    }

    /**
     * 应用信息获取操作
     */
    object Info {
        /**
         * 获取包名
         */
        @JvmStatic
        fun getPackageName(context: Context): String = context.packageName

        /**
         * 获取应用版本号 (需要 Android P 以上)
         */
        @RequiresApi(Build.VERSION_CODES.P)
        @JvmStatic
        fun getAppVersionCode(context: Context): Long {
            return try {
                context.packageManager.getPackageInfo(getPackageName(context), 0).longVersionCode
            } catch (e: PackageManager.NameNotFoundException) {
                Log.e(TAG, "getAppVersionCode: ", e)
                0L
            }
        }

        /**
         * 获取应用版本名称
         */
        @JvmStatic
        fun getAppVersionName(context: Context): String {
            return try {
                context.packageManager.getPackageInfo(getPackageName(context), 0).versionName ?: ""
            } catch (e: PackageManager.NameNotFoundException) {
                Log.e(TAG, "getAppVersionName: ", e)
                ""
            }
        }

        /**
         * 获取应用名称
         */
        @JvmStatic
        fun getApplicationName(context: Context): String? {
            return try {
                val appInfo = context.packageManager.getApplicationInfo(getPackageName(context), 0)
                context.packageManager.getApplicationLabel(appInfo).toString()
            } catch (e: PackageManager.NameNotFoundException) {
                Log.e(TAG, "getApplicationName: ", e)
                null
            }
        }

        /**
         * 获取系统语言
         */
        @JvmStatic
        fun getSystemLanguage(context: Context): String {
            return context.resources.configuration.locales[0].language
        }

        /**
         * 读取 assets 下的资源文件
         */
        @JvmStatic
        fun getAssetsString(context: Context, fileName: String): String {
            return try {
                context.assets.open(fileName).bufferedReader().use(BufferedReader::readText)
            } catch (e: IOException) {
                Log.e(TAG, "getAssetsString: ", e)
                ""
            }
        }
    }

    /**
     * 启动应用的主活动
     */
    @JvmStatic
    fun openApp(context: Context, className: String) {
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
            component = ComponentName(context.packageName, className)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        context.startActivity(intent)
    }
}
