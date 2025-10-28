package com.ebook.common.util

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import com.xrn1997.common.BaseApplication.Companion.context
import androidx.core.content.edit

/**
 * Kotlin 风格 SPUtil
 * 单例 + 多 SP 文件 + 运算符重载
 */
object SPUtil {

    private const val DEFAULT_SP_NAME = "spUtils"
    private val spMap = mutableMapOf<String, SharedPreferences>()

    /**
     * 获取指定名称的 SharedPreferences
     */
    @SuppressLint("WrongConstant")
    fun getSharedPreferences(name: String = DEFAULT_SP_NAME): SharedPreferences {
        val spName = name.takeIf { it.isNotBlank() } ?: DEFAULT_SP_NAME
        return spMap.getOrPut(spName) {
            context.getSharedPreferences(spName, Context.MODE_PRIVATE)
        }
    }


    /* -------------------- 运算符重载 put/get -------------------- */
    operator fun set(key: String, value: Any?) {
        put(key, value)
    }

    fun put(key: String, value: Any?, spName: String = DEFAULT_SP_NAME) {
        getSharedPreferences(spName).edit {
            when (value) {
                is String -> putString(key, value)
                is Int -> putInt(key, value)
                is Long -> putLong(key, value)
                is Float -> putFloat(key, value)
                is Boolean -> putBoolean(key, value)
                is Set<*> -> putStringSet(key, value.filterIsInstance<String>().toSet())
                null -> remove(key) // null 表示删除该 key
                else -> throw IllegalArgumentException("Unsupported type: ${value::class.java}")
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    operator fun <T> get(key: String, default: T, spName: String = DEFAULT_SP_NAME): T {
        val sp = getSharedPreferences(spName)
        return when (default) {
            is String -> sp.getString(key, default) as T
            is Int -> sp.getInt(key, default) as T
            is Long -> sp.getLong(key, default) as T
            is Float -> sp.getFloat(key, default) as T
            is Boolean -> sp.getBoolean(key, default) as T
            is Set<*> -> sp.getStringSet(key, default as Set<String>) as T
            else -> throw IllegalArgumentException("Unsupported type: ${default!!::class.java}")
        }
    }

    /* -------------------- 其他工具方法 -------------------- */
    fun contains(key: String, spName: String = DEFAULT_SP_NAME): Boolean =
        getSharedPreferences(spName).contains(key)

    fun remove(key: String, spName: String = DEFAULT_SP_NAME) {
        getSharedPreferences(spName).edit { remove(key) }
    }

    fun clear(spName: String = DEFAULT_SP_NAME) {
        getSharedPreferences(spName).edit { clear() }
    }

    fun getAll(spName: String = DEFAULT_SP_NAME): Map<String, *> =
        getSharedPreferences(spName).all
}
