package com.ebook.api.utils

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import java.io.InputStream
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {
    @Provides
    @Singleton
    fun providesNetworkJson(): Json = Json {
        prettyPrint = true       // 美化 JSON 输出格式
        ignoreUnknownKeys = true // 忽略 JSON 中的未知字段
    }

    @Provides
    @Singleton
    fun providesTestAssetManager(
        @ApplicationContext context: Context,
    ): TestAssetManager = TestAssetManager(context.assets::open)

}

fun interface TestAssetManager {
    fun open(fileName: String): InputStream
}