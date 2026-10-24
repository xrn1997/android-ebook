package com.ebook.book.importer

import java.io.File

/**
 * 扫描一个根目录，产出可导入的候选文件。
 *
 * 纯 JVM：只做目录遍历与资格判断，不判断挂载状态、不申请权限、不做线程调度——这些归调用方。
 * 结果按文件系统遍历顺序返回，不做排序（调用方按集合语义使用）。
 */
class ImportScanner {

    /**
     * 递归扫描 [root]。
     *
     * [isCancelled] 在进入每个目录前与每个子条目前各查一次，命中即返回已收集的结果；因此取消
     * 是「不再产出更多」而不是「丢弃已找到的」，调用方拿到的是取消前的前缀。
     * 根不存在或不可读（`listFiles()` 返回 null）时返回空，不抛异常。
     */
    fun scan(root: File, isCancelled: () -> Boolean = { false }): List<File> {
        val result = mutableListOf<File>()
        if (!root.isDirectory) return result
        walk(root, root, result, isCancelled)
        return result
    }

    private fun walk(
        dir: File,
        root: File,
        out: MutableList<File>,
        isCancelled: () -> Boolean,
    ) {
        if (isCancelled()) return
        val children = dir.listFiles() ?: return
        for (child in children) {
            if (isCancelled()) return
            if (child.isDirectory) {
                // 无权限目录整棵跳过：按相对根的路径比较，与挂载点无关
                if (child.relativeTo(root).invariantSeparatorsPath in EXCLUDED_RELATIVE_PATHS) continue
                walk(child, root, out, isCancelled)
            } else if (child.isFile && isEligible(child)) {
                // 只收普通文件：与旧扫描的 childFile.isFile 门保持一致，特殊条目（如 FIFO）不入围
                out += child
            }
        }
    }

    private fun isEligible(file: File): Boolean = when (file.extension.lowercase()) {
        "epub" -> true
        "txt" -> file.length() > TXT_MIN_BYTES
        else -> false
    }

    private companion object {
        /** 严格大于的下限：恰好 100KB 的 txt 不入围（保留导入页原口径） */
        const val TXT_MIN_BYTES = 100L * 1024

        /**
         * 无权限目录按相对扫描根的路径（`/` 分隔）比较，而不是绝对路径——扫描根由调用方给出
         * （真机是外部存储根，测试是临时目录），资格规则不绑定具体挂载点。
         */
        val EXCLUDED_RELATIVE_PATHS = setOf("Android/data", "Android/obb")
    }
}
