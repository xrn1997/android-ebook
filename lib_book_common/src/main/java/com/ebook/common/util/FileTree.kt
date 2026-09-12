package com.ebook.common.util

import java.io.File

/**
 * 目录树占用字节数的唯一计算点。
 *
 * 递归求本目录下所有**普通文件**的字节和：目录本身不计（inode 大小不是用户意义上的「内容」），
 * 列举失败或目录不存在按 0 处理，因此展示层不必为「还没有任何文件」写特判。
 *
 * 之所以存在：内容仓库（`filesDir/books` 下的章文件）与应用缓存（`cacheDir`）都要算总量，
 * 两处各写一份递归 walk 就会分叉出两个口径的「占用」。它是域无关件，真正的家在外部库
 * `lib_common`——与 [DateUtil] 同一批待迁移项，等下次联动窗口上移。
 */
fun File.treeSize(): Long =
    listFiles()?.sumOf { entry -> if (entry.isDirectory) entry.treeSize() else entry.length() } ?: 0L
