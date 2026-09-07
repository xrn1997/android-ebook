package com.ebook.me.di

import android.content.Context
import com.ebook.me.repository.CacheModel
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * 缓存管理的装配点。
 *
 * [CacheModel] 只接 `cacheDir` 这一个目录参数而不接 `Context`——留下 `Context` 就等于把
 * 分类统计、差值与清理规则全部推到设备上才能验。这里做唯一一次 `Context → File` 的转换，
 * 与 `ContentStoreModule` 给 `BookStore` 传 `filesDir/books` 是同一手法。
 */
@Module
@InstallIn(SingletonComponent::class)
object CacheModule {

    @Provides
    @Singleton
    fun provideCacheModel(@ApplicationContext context: Context): CacheModel =
        CacheModel(context.cacheDir)
}
