package com.ebook.book.importer

import android.content.Context
import com.ebook.common.analyze.local.BookFormat
import com.ebook.common.analyze.local.EpubSourceReader
import com.ebook.common.analyze.local.SourceReader
import com.ebook.common.analyze.local.TxtSourceReader
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton

/**
 * 本地书导入的装配点。
 *
 * 导入引擎、协调器与暂存目录都只服务本模块的导入页，故装配随特性下沉——共享库不再为
 * 一个单消费者特性保留注入图（见 ADR-0015 决策 2）。
 */
@Module
@InstallIn(SingletonComponent::class)
object ImporterModule {

    /** 导入期源文件的落点：把 Uri 变成 File 以便复用同一条"拷贝即哈希"流水线 */
    @Provides
    @Singleton
    @ImportScratch
    fun provideImportScratchDir(@ApplicationContext context: Context): File =
        File(context.cacheDir, "import").also { it.mkdirs() }

    /**
     * 导入链路专用：只含实现了 [SourceReader]（能 `readMetadata` + `buildChapters`）的
     * 本地格式。网络书不走导入流水线，故不在此 map 中。
     */
    @Provides
    @Singleton
    fun provideSourceReaders(
        txt: TxtSourceReader,
        epub: EpubSourceReader,
    ): Map<BookFormat, SourceReader> =
        mapOf(
            BookFormat.TXT to txt,
            BookFormat.EPUB to epub,
        )
}
