package com.ebook.common.manager

import android.content.Context
import com.xrn1997.common.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

/**
 * 解析失败 URL 的落盘记录（调试辅助：排查书源规则失配/站点改版）。
 *
 * 两个并发与容量约束：
 * - **串行写**：[writeNewErrorUrl] 是「读整份文件 → 判重 → 追加」的复合操作，
 *   并发时两个协程可能同时判为「不存在」而各写一行；
 * - **体积上限**：解析失败是常态（书源改版/反爬，叠加下载重试会持续写入），
 *   只追加的文件会单调增长占满外部存储，故超限轮转。
 */
object ErrorAnalyzeContentManager {
    private const val TAG = "ErrorAnalyzeContentManager"

    /**
     * 进程级 IO 作用域（object 单例，随进程存活，无需也不应取消）。
     *
     * 每次调用只做几次小文件追加即完成，协程不会堆积；需要收敛的是并发写同一文件，见 [writeMutex]。
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 串行化文件读写，避开「读-判-写」竞态与多协程交错追加 */
    private val writeMutex = Mutex()

    /** 单个记录文件的体积上限（超过即轮转）：调试用文件保留最近一批失败 URL 就够 */
    private const val MAX_FILE_BYTES = 512 * 1024L

    /**
     * 记录一个解析失败的章节 URL（明细 + 按站点去重）。
     *
     * 失败不往外抛：调用方（[com.ebook.common.analyze.source.JsoupBookParser]）正在异常处理路径上，
     * 记录失败不得反过来影响正文获取的降级返回。
     */
    fun writeNewErrorUrl(context: Context, url: String) {
        scope.launch {
            try {
                val dir = getExternalFilesDir(context)
                if (dir == null) {
                    Logger.e(TAG, "getExternalFilesDir is null")
                    return@launch
                }
                writeMutex.withLock {
                    val errorDetailFile = File(dir, "ErrorAnalyzeUrlsDetail.txt")
                    appendCapped(errorDetailFile, "$url    \r\n")

                    val errorFile = File(dir, "ErrorAnalyzeUrls.txt")
                    val baseUrl = extractBaseUrl(url)
                    if (!readFileContent(errorFile).contains(baseUrl)) {
                        appendCapped(errorFile, "$baseUrl    \r\n")
                    }
                }
            } catch (ex: Exception) {
                Logger.e(TAG, "Error in writeNewErrorUrl", ex)
            }
        }
    }

    /**
     * 记录一个「可能是网络原因」失败的 URL。
     *
     * 当前无调用方（预留给「区分书源规则失配与网络故障」的归因需求）；
     * 与 [writeNewErrorUrl] 共用串行写与体积上限。
     */
    fun writeMayByNetError(context: Context, url: String) {
        scope.launch {
            try {
                val dir = getExternalFilesDir(context)
                if (dir == null) {
                    Logger.e(TAG, "getExternalFilesDir is null")
                    return@launch
                }
                writeMutex.withLock {
                    val errorNetFile = File(dir, "ErrorNetUrl.txt")
                    appendCapped(errorNetFile, "$url    \r\n")
                }
            } catch (ex: Exception) {
                Logger.e(TAG, "Error in writeMayByNetError", ex)
            }
        }
    }

    private fun getExternalFilesDir(context: Context): File? {
        val dir = context.getExternalFilesDir(null)
        if (dir != null && !dir.exists()) {
            if (!dir.mkdirs()) {
                Logger.e(TAG, "Failed to create directory: ${dir.path}")
                return null
            }
        }
        return dir
    }

    /**
     * 取 URL 的 scheme + host 作为「书源站点」标识。
     *
     * 原实现 `url.take(url.indexOf('/', 8))` 在 URL 没有路径部分时 indexOf 返回 -1，
     * `take(-1)` 抛 StringIndexOutOfBoundsException 被外层 catch 吞掉，结果这条站点记录静默丢失；
     * 现改为无路径时退回整串。先定位 `://` 再找斜杠，避开写死的下标 8（`http://` 与 `https://` 长度不同）。
     */
    private fun extractBaseUrl(url: String): String {
        val hostStart = url.indexOf("://").takeIf { it >= 0 }?.plus(3) ?: 0
        val pathStart = url.indexOf('/', hostStart)
        return if (pathStart > 0) url.substring(0, pathStart) else url
    }

    /**
     * 追加一行，文件超过 [MAX_FILE_BYTES] 时先轮转（截断重写，丢弃旧记录）。
     *
     * 轮转而非删除：本文件只用于人工排查，保留最近一批失败 URL 比无限增长有用。
     */
    @Throws(IOException::class)
    private fun appendCapped(file: File, content: String) {
        val rotate = file.exists() && file.length() >= MAX_FILE_BYTES
        if (rotate) {
            Logger.w(TAG, "${file.name} 超过 $MAX_FILE_BYTES 字节，轮转重写")
        }
        // append = !rotate：轮转时以覆盖模式打开，等价于截断后重写
        FileOutputStream(file, !rotate).use { fos ->
            fos.write(content.toByteArray())
            fos.flush()
        }
    }

    @Throws(IOException::class)
    private fun readFileContent(file: File): String {
        if (!file.exists()) {
            return ""
        }
        FileInputStream(file).use { fis ->
            ByteArrayOutputStream().use { outputStream ->
                val buffer = ByteArray(1024)
                var length: Int
                while (fis.read(buffer).also { length = it } != -1) {
                    outputStream.write(buffer, 0, length)
                }
                return outputStream.toString()
            }
        }
    }
}
