package com.ebook.me.repository

import com.ebook.common.util.treeSize
import com.xrn1997.common.mvvm.model.BaseModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

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
 * 三档由同一次遍历分档累加得出，[totalBytes] 恒等于三者之和（不再有差值与负数可言）。
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
 *
 * **只接一个 [cacheRoot] 目录参数、不碰 `Context`**：收 `Application` 会让「什么算图片缓存、
 * 什么算临时文件、OTHER 的差值怎么算」这套分类规则只能在设备上验；换成目录参数后整块逻辑
 * 在纯 JVM 下用临时目录即可锁死（见 `CacheModelTest`）。生产环境由
 * [com.ebook.me.di.CacheModule] 传入 `context.cacheDir`——与 `BookStore` 经
 * `ContentStoreModule` 拿 `filesDir/books` 是同一手法。
 */
class CacheModel(
    private val cacheRoot: File,
) : BaseModel() {

    /** 递归计算缓存根目录大小（字节） */
    suspend fun cacheSizeBytes(): Long = withContext(Dispatchers.IO) {
        cacheRoot.treeSize()
    }

    /** 清空缓存根目录下全部子项（供缓存管理页「清理全部」）；根目录本身保留 */
    suspend fun clearCache() = withContext(Dispatchers.IO) {
        cacheRoot.listFiles()?.forEach { it.deleteRecursively() }
    }

    /**
     * 计算缓存分类明细。
     *
     * 一次遍历：按缓存根目录的子项分档累加——图片目录整棵计入 IMAGE，根目录松散文件计入 TEMP，
     * 其余子目录计入 OTHER。此前是「总量减图片与临时」的差值法，代价是图片目录被完整走两遍
     * （缓存图常常是文件数最多的一档），还要为并发写入下的负差值钳位；分档累加后总量恒等于
     * 三档之和，既少一趟遍历也没有负数可言。
     */
    suspend fun cacheBreakdown(): CacheBreakdown = withContext(Dispatchers.IO) {
        val imageDirs = imageCacheDirs().toSet()
        var image = 0L
        var temp = 0L
        var other = 0L
        for (entry in cacheRoot.listFiles().orEmpty()) {
            when {
                entry.isFile -> temp += entry.length()
                entry in imageDirs -> image += entry.treeSize()
                else -> other += entry.treeSize()
            }
        }
        CacheBreakdown(imageBytes = image, tempBytes = temp, otherBytes = other)
    }

    /** 清理图片缓存：删除 Coil 磁盘缓存目录（连带回收老版本遗留的 Glide 目录），清理后按需重新下载 */
    suspend fun clearImageCache() = withContext(Dispatchers.IO) {
        imageCacheDirs().forEach { it.deleteRecursively() }
    }

    /** 清理临时文件：缓存根目录下全部松散文件 */
    suspend fun clearTempFiles() = withContext(Dispatchers.IO) {
        tempFiles().forEach { it.delete() }
    }

    /** 清理其他缓存：删除图片缓存目录以外的子目录（根目录松散文件归 TEMP） */
    suspend fun clearOtherCache() = withContext(Dispatchers.IO) {
        val imageDirs = imageCacheDirs().toSet()
        cacheRoot.listFiles()?.forEach { file ->
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
                .map { CacheEntry(it.name, it.treeSize(), isDirectory = true) }
            CacheType.TEMP -> tempFiles()
                .map { CacheEntry(it.name, it.length(), isDirectory = false) }
            CacheType.OTHER -> {
                val imageDirs = imageCacheDirs().toSet()
                cacheRoot.listFiles().orEmpty()
                    .filter { it.isDirectory && it !in imageDirs }
                    .map { CacheEntry(it.name, it.treeSize(), isDirectory = true) }
            }
        }
        entries.sortedByDescending { it.sizeBytes }
    }

    /** 图片缓存目录：Coil 当前缓存位 + 老版本可能残留的 Glide 缓存位（Glide 已移除，保留清理只为回收磁盘） */
    private fun imageCacheDirs(): List<File> = listOf(
        File(cacheRoot, "image_cache"),
        File(cacheRoot, "image_manager_disk_cache"),
    )

    /** 临时文件：缓存根目录下的松散文件（不含子目录） */
    private fun tempFiles(): List<File> =
        cacheRoot.listFiles()?.filter { it.isFile }.orEmpty()
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
