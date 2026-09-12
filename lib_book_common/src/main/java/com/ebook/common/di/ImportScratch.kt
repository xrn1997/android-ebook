package com.ebook.common.di

import javax.inject.Qualifier

/**
 * 限定 `File` 注入：导入期源文件暂存目录（`cacheDir/import`）。
 *
 * 为什么必须有：`LocalBookImporter` 的构造参数需要 `File`，而 Hilt 里将来可能还有别的
 * `File` 参数（如 `BookStore` 的 `filesDir/books`）。没有 `@Qualifier`，Dagger 不知道给哪个。
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ImportScratch
