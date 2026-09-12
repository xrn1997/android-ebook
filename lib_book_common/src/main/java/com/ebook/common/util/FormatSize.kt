package com.ebook.common.util

import java.util.Locale

/**
 * 字节数格式化为可读大小（如 "12.3 MB"）。
 *
 * 单位符号（B/KB/MB/GB）是语言无关的 SI 约定，不参与本地化；
 * 数字格式固定 Locale.US（小数点），保证展示与单元测试跨环境一致。
 *
 * 之所以存在：导入页与缓存管理页都要把字节数展示给用户，两处各写一份就会分叉出两种口径。
 * 它是域无关件，真正的家在外部库 `lib_common`——与 [FileTree.treeSize] 同一批待迁移项，
 * 等下次联动窗口上移。
 */
fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> String.format(Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0)
    else -> String.format(Locale.US, "%.2f GB", bytes / 1024.0 / 1024.0 / 1024.0)
}
