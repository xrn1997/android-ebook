package com.ebook.common.util

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import com.xrn1997.common.BaseApplication.Companion.context

/**
 * Kotlin 风格 SPUtil
 * 单例 + 多 SP 文件 + 运算符重载
 */
@SuppressLint("ApplySharedPref")
object SPUtil {

    private const val DEFAULT_SP_NAME = "spUtils"
    private val spMap = mutableMapOf<String, SharedPreferences>()

    /**
     * 获取指定名称的 SharedPreferences
     */
    @SuppressLint("WrongConstant")
    fun getSharedPreferences(
        name: String = DEFAULT_SP_NAME,
        mode: Int = Context.MODE_PRIVATE
    ): SharedPreferences {
        val spName = name.takeIf { it.isNotBlank() } ?: DEFAULT_SP_NAME
        return spMap.getOrPut(spName) {
            context.getSharedPreferences(spName, mode)
        }
    }

    /* -------------------- put -------------------- */
    fun put(key: String, value: Any?, spName: String = DEFAULT_SP_NAME, isCommit: Boolean = false) {
        val editor = getSharedPreferences(spName).edit()
        when (value) {
            is String -> editor.putString(key, value)
            is Int -> editor.putInt(key, value)
            is Long -> editor.putLong(key, value)
            is Float -> editor.putFloat(key, value)
            is Boolean -> editor.putBoolean(key, value)
            is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
            null -> editor.remove(key)
            else -> throw IllegalArgumentException("Unsupported type: ${value::class.java}")
        }
        if (isCommit) editor.commit() else editor.apply()
    }

    /* -------------------- get -------------------- */
    @Suppress("UNCHECKED_CAST")
    fun <T> get(key: String, default: T, spName: String = DEFAULT_SP_NAME): T {
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

    /* -------------------- 辅助方法 -------------------- */
    fun contains(key: String, spName: String = DEFAULT_SP_NAME): Boolean =
        getSharedPreferences(spName).contains(key)

    fun remove(key: String, spName: String = DEFAULT_SP_NAME, isCommit: Boolean = false) {
        val editor = getSharedPreferences(spName).edit().remove(key)
        if (isCommit) editor.commit() else editor.apply()
    }

    fun clear(spName: String = DEFAULT_SP_NAME, isCommit: Boolean = false) {
        val editor = getSharedPreferences(spName).edit().clear()
        if (isCommit) editor.commit() else editor.apply()
    }

    fun getAll(spName: String = DEFAULT_SP_NAME): Map<String, *> =
        getSharedPreferences(spName).all
}
