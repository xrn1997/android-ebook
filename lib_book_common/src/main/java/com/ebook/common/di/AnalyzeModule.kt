package com.ebook.common.di

import com.ebook.common.analyze.source.BookSourceManager
import com.ebook.common.analyze.source.BookSourceManagerImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class AnalyzeModule {

    @Binds
    @Singleton
    abstract fun bindBookSourceManager(impl: BookSourceManagerImpl): BookSourceManager
}
