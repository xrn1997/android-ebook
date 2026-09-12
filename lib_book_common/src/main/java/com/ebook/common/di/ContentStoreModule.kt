package com.ebook.common.di

import android.content.Context
import com.ebook.common.analyze.local.BookFormat
import com.ebook.common.analyze.local.ChapterReader
import com.ebook.common.analyze.local.ChapterSplitter
import com.ebook.common.analyze.local.EpubSourceReader
import com.ebook.common.analyze.local.SourceReader
import com.ebook.common.analyze.local.TxtSourceReader
import com.ebook.common.analyze.source.JsoupSourceReader
import com.ebook.common.store.BookStore
import com.ebook.common.store.ChapterContentCache
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton

/**
 * 本地内容仓库的装配点。
 *
 * `BookStore` 只接 `filesDir/books` 这个目录参数而不接 `Context`——留下 `Context` 会让整个
 * 内容基座只能在仪器测试里跑。这里做唯一一次 `Context` 到 `File` 的转换。
 */
@Module
@InstallIn(SingletonComponent::class)
object ContentStoreModule {

    @Provides
    @Singleton
    fun provideBookStore(@ApplicationContext context: Context): BookStore =
        BookStore(File(context.filesDir, BookStore.DIR_NAME))

    @Provides
    @Singleton
    fun provideChapterSplitter(): ChapterSplitter = ChapterSplitter()

    @Provides
    @Singleton
    fun provideChapterContentCache(): ChapterContentCache = ChapterContentCache()

    /** 导入期源文件的落点：把 Uri 变成 File 以便复用同一条"拷贝即哈希"流水线 */
    @Provides
    @Singleton
    @ImportScratch
    fun provideImportScratchDir(@ApplicationContext context: Context): File =
        File(context.cacheDir, "import").also { it.mkdirs() }

    /**
     * 按格式路由到对应 reader。TXT/EPUB 走本地解析，NETWORK 走 jsoup（仅导入链路以外的正文读取）。
     *
     * `Map<BookFormat, ChapterReader>` 注入 `BookRepository`——读取正文时按格式分发，
     * 新增格式时只改这一处，仓库侧的分支代码一行不动（spec §7 开闭原则）。
     *
     * 注意与 [provideSourceReaders] 的分界：本 map 包含所有能读正文的来源（含网络），
     * 后者只含能走导入流水线的本地格式。
     */
    @Provides
    @Singleton
    fun provideChapterReaders(
        txt: TxtSourceReader,
        jsoup: JsoupSourceReader,
        epub: EpubSourceReader,
    ): Map<BookFormat, ChapterReader> =
        mapOf(
            BookFormat.TXT to txt,
            BookFormat.NETWORK to jsoup,
            BookFormat.EPUB to epub,
        )

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
