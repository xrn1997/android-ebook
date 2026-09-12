package com.ebook.common.di

import com.ebook.common.domain.ThemeModeManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * 外观主题模式 Hilt 模块。
 *
 * [ThemeModeManager] 依赖 [android.app.Application]（读 SharedPreferences），
 * 用 [Provides] 显式提供；Hilt 的 [SingletonComponent] 保证全 App 单例，
 * 与 [com.ebook.common.BookApplication] 的伴生实例同源。
 */
@Module
@InstallIn(SingletonComponent::class)
object ThemeModule {

    @Provides
    @Singleton
    fun provideThemeModeManager(
        application: android.app.Application,
    ): ThemeModeManager = ThemeModeManager(application)
}
