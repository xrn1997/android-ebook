package com.ebook.api.cache

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * 本地缓存   因本缓存只缓存书库主页 所以使用SP有条件可以替换成别的
 */
class ACache private constructor(context: Context) {
    private val preference: SharedPreferences = context.getSharedPreferences("ACache", 0)

    fun put(key: String, value: String) {
        try {
            preference.edit { putString(key, value) }
        } catch (_: Exception) {
        }
    }

    /**
     * 读取 String数据
     *
     * @return String 数据
     */
    fun getAsString(key: String): String? {
        return try {
            preference.getString(key, null)
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        fun get(context: Context): ACache {
            return ACache(context)
        }
    }
}
