package com.ebook.find.di

import android.content.Context
import com.ebook.common.analyze.source.LibraryDiskCache
import com.xrn1997.common.util.Logger
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton

/**
 * 书库缓存的装配点。
 *
 * [LibraryDiskCache] 只接 `cacheDir` 下的一个目录参数而不接 `Context`——留下 `Context` 就等于把
 * 文件读写全部推到设备上才能验。这里做唯一一次 `Context → File` 的转换，与 module_me 的
 * `CacheModule` 给 `CacheModel` 传 `cacheDir` 是同一手法。
 *
 * 目录取 `cacheDir/library_cache`：落在缓存管理页「其他」档的口径里（ADR-0026——该页的
 * 「缓存」只指 cacheDir），用户能清、系统低存储时能回收；这也正是书库缓存从旧的
 * SharedPreferences 搬出来的动机之一。
 */
@Module
@InstallIn(SingletonComponent::class)
object LibraryCacheModule {

    /**
     * 顺手清掉旧实现（ACache）留在 SharedPreferences 里的书库缓存：那是它唯一的用途，
     * 类已删除，这份文件从此无人能读也无人能删，不清就成了几百 KB 的永久暗占用。
     * 文件不存在时调用是无害的 no-op，每次启动都跑一遍也不亏。
     *
     * 删除失败**只记日志、不向上抛**：这份文件已无任何读者，删不掉的唯一后果是继续占着那点存储，
     * 而抛出去会把整个书城装配带崩。记一行日志是为了「清理策略真失效」时有人能看见——
     * 原先的 `runCatching {}` 把失败吞得一点痕迹都没有。
     */
    @Provides
    @Singleton
    fun provideLibraryDiskCache(@ApplicationContext context: Context): LibraryDiskCache {
        runCatching { context.deleteSharedPreferences("ACache") }
            .onFailure { Logger.w(TAG, "清理旧书库缓存（ACache）失败，该文件可能仍占用存储", it) }
        return LibraryDiskCache(File(context.cacheDir, "library_cache"))
    }

    private const val TAG = "LibraryCacheModule"
}
