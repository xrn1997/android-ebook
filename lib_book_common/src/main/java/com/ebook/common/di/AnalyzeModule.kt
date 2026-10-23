package com.ebook.common.di

import com.ebook.common.analyze.source.BookSourceManagerImpl
import com.ebook.common.analyze.source.SourceAdmin
import com.ebook.common.analyze.source.SourceCatalog
import com.ebook.common.analyze.source.SourceCodec
import com.ebook.common.analyze.source.SourceResolver
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class AnalyzeModule {

    // 四个窄面必须都绑定到同一个 `@Singleton BookSourceManagerImpl`：拆到不同实例会静默复制
    // parser LRU 与默认源状态（缓存各看各的、默认源来回跳），而 Hilt 图本身照样校验通过。
    // 聚合类型刻意没有绑定——留着就等于给宽接口留一条生产注入口。

    @Binds
    @Singleton
    abstract fun bindSourceResolver(impl: BookSourceManagerImpl): SourceResolver

    @Binds
    @Singleton
    abstract fun bindSourceCatalog(impl: BookSourceManagerImpl): SourceCatalog

    @Binds
    @Singleton
    abstract fun bindSourceAdmin(impl: BookSourceManagerImpl): SourceAdmin

    @Binds
    @Singleton
    abstract fun bindSourceCodec(impl: BookSourceManagerImpl): SourceCodec
}
