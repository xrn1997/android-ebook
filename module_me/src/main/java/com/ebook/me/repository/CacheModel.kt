package com.ebook.me.repository

import android.app.Application
import com.xrn1997.common.mvvm.model.BaseModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 缓存分类：缓存管理页按类展示与单独清理。
 *
 * 分类依据 cacheDir 内的实际产物（目录名以图片库默认磁盘缓存位置为准）：
 * - IMAGE：Coil（image_cache），另含老版本遗留的 Glide 目录，书籍封面/头像，可安全重下载
 * - TEMP：cacheDir 根目录的松散文件，如头像裁剪产物（cropped_*.jpg）
 * - OTHER：除图片缓存外的其余子目录
 */
enum class CacheType { IMAGE, TEMP, OTHER }

/**
 * 各分类缓存占用（字节）。
 *
 * other 为「总量 - 图片 - 临时」的差值（并发写入时可能轻微偏差，构造前已钳到非负）。
 */
data class CacheBreakdown(
    val imageBytes: Long,
    val tempBytes: Long,
    val otherBytes: Long,
) {
    val totalBytes: Long get() = imageBytes + tempBytes + otherBytes
}

/**
 * 缓存明细条目：分类 BottomSheet 内展示的单个文件/目录。
 *
 * IMAGE/OTHER 以目录为粒度（图片缓存内部文件名为 hash，对用户无意义），
 * TEMP 以文件为粒度（文件名有业务含义，如 cropped_xxx.jpg 头像裁剪产物）。
 */
data class CacheEntry(
    val name: String,
    val sizeBytes: Long,
    val isDirectory: Boolean,
)

/**
 * 设置页/缓存管理页 Model：本地缓存管理（大小计算与清理）。
 *
 * 缓存指应用 cacheDir（Coil 磁盘缓存等均在其中），与账号/网络无关，
 * 归 Model 层做纯文件操作（内部已切 IO 线程），ViewModel 只编排状态。
 */
@Singleton
class CacheModel @Inject constructor(
    private val application: Application,
) : BaseModel() {

    /** 递归计算 cacheDir 大小（字节） */
    suspend fun cacheSizeBytes(): Long = withContext(Dispatchers.IO) {
        dirSize(application.cacheDir)
    }

    /** 清空 cacheDir 下全部子项（供缓存管理页「清理全部」） */
    suspend fun clearCache() = withContext(Dispatchers.IO) {
        application.cacheDir.listFiles()?.forEach { it.deleteRecursively() }
    }

    /**
     * 计算缓存分类明细。
     *
     * 差值法统计 OTHER：总量减图片与临时，避免枚举子目录时与并发写入竞争；
     * 差值为负（极端并发下可能）时钳到 0，保证展示不出现负数。
     */
    suspend fun cacheBreakdown(): CacheBreakdown = withContext(Dispatchers.IO) {
        val total = dirSize(application.cacheDir)
        val image = imageCacheDirs().sumOf { dirSize(it) }
        val temp = tempFiles().sumOf { it.length() }
        CacheBreakdown(
            imageBytes = image,
            tempBytes = temp,
            otherBytes = maxOf(0L, total - image - temp),
        )
    }

    /** 清理图片缓存：删除 Coil 磁盘缓存目录（连带回收老版本遗留的 Glide 目录），清理后按需重新下载 */
    suspend fun clearImageCache() = withContext(Dispatchers.IO) {
        imageCacheDirs().forEach { it.deleteRecursively() }
    }

    /** 清理临时文件：删除 cacheDir 根目录下全部松散文件 */
    suspend fun clearTempFiles() = withContext(Dispatchers.IO) {
        tempFiles().forEach { it.delete() }
    }

    /** 清理其他缓存：删除图片缓存目录以外的子目录（根目录松散文件归 TEMP） */
    suspend fun clearOtherCache() = withContext(Dispatchers.IO) {
        val imageDirs = imageCacheDirs().toSet()
        application.cacheDir.listFiles()?.forEach { file ->
            if (file.isDirectory && file !in imageDirs) file.deleteRecursively()
        }
    }

    /**
     * 列出某分类下的明细条目（供缓存明细 BottomSheet 展示）。
     *
     * IMAGE/OTHER 返回子目录级条目，TEMP 返回根目录松散文件级条目；
     * 条目按大小降序（用户最关心占空间的内容），无内容时返回空列表。
     */
    suspend fun cacheEntries(type: CacheType): List<CacheEntry> = withContext(Dispatchers.IO) {
        val entries = when (type) {
            CacheType.IMAGE -> imageCacheDirs()
                .filter { it.exists() }
                .map { CacheEntry(it.name, dirSize(it), isDirectory = true) }
            CacheType.TEMP -> tempFiles()
                .map { CacheEntry(it.name, it.length(), isDirectory = false) }
            CacheType.OTHER -> {
                val imageDirs = imageCacheDirs().toSet()
                application.cacheDir.listFiles().orEmpty()
                    .filter { it.isDirectory && it !in imageDirs }
                    .map { CacheEntry(it.name, dirSize(it), isDirectory = true) }
            }
        }
        entries.sortedByDescending { it.sizeBytes }
    }

    /** 图片缓存目录：Coil 当前缓存位 + 老版本可能残留的 Glide 缓存位（Glide 已移除，保留清理只为回收磁盘） */
    private fun imageCacheDirs(): List<File> = listOf(
        File(application.cacheDir, "image_cache"),
        File(application.cacheDir, "image_manager_disk_cache"),
    )

    /** 临时文件：cacheDir 根目录下的松散文件（不含子目录） */
    private fun tempFiles(): List<File> =
        application.cacheDir.listFiles()?.filter { it.isFile }.orEmpty()

    /** 递归计算目录大小（字节） */
    private fun dirSize(dir: File): Long =
        dir.listFiles()?.sumOf { file ->
            if (file.isDirectory) dirSize(file) else file.length()
        } ?: 0L
}

/**
 * 字节数格式化为可读大小（如 "12.3 MB"）。
 *
 * 单位符号（B/KB/MB/GB）是语言无关的 SI 约定，不参与本地化；
 * 数字格式固定 Locale.US（小数点），保证展示与单元测试跨环境一致。
 */
fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> String.format(Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0)
    else -> String.format(Locale.US, "%.2f GB", bytes / 1024.0 / 1024.0 / 1024.0)
}
