package com.ebook.common.di

import com.ebook.common.repository.BookRepository
import com.ebook.common.repository.ChapterTocSync
import com.ebook.common.repository.CommentKeys
import com.ebook.common.repository.ContentReader
import com.ebook.common.repository.ShelfRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    // 四个窄面必须都绑定到同一个 `@Singleton BookRepository`（ADR-0055）：拆到不同实例会静默复制
    // 书架事件流与四表写口径，而 Hilt 图本身照样校验通过（绑定形态同 AnalyzeModule 的书源四面）。
    // 聚合类型刻意没有绑定——留着就等于给宽接口留一条生产注入口；聚合只用于测试与
    // MyApplication 的对账 EntryPoint（reconcileContentStore 不进任何面）。

    @Binds
    @Singleton
    abstract fun bindShelfRepository(impl: BookRepository): ShelfRepository

    @Binds
    @Singleton
    abstract fun bindContentReader(impl: BookRepository): ContentReader

    @Binds
    @Singleton
    abstract fun bindChapterTocSync(impl: BookRepository): ChapterTocSync

    @Binds
    @Singleton
    abstract fun bindCommentKeys(impl: BookRepository): CommentKeys
}
