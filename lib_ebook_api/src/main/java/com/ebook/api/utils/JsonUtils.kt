package com.ebook.api.utils

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.serializer
import java.nio.file.Files
import java.nio.file.Path

// 文件：JsonUtils.kt
object JsonUtils {
    val json = NetworkModule.providesNetworkJson()

    @OptIn(ExperimentalSerializationApi::class, InternalSerializationApi::class)
    @JvmStatic
    fun <T : Any> parseJson(path: Path, clazz: Class<T>): T {
        Files.newInputStream(path).use { inputStream ->
            val deserializer = clazz.kotlin.serializer() // 获取序列化策略
            return json.decodeFromStream(deserializer, inputStream)
        }
    }

}