package com.ebook.api.testfixture

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences

/**
 * 可控的 [SharedPreferences]：记录 commit 次数与同笔键集合，并可让 commit 返回失败。
 *
 * 用于验证「落盘失败」这条 [SharedPreferences] 真实实现很难在 JVM 下制造的分支。
 * 对齐 Android 实现的关键语义：**commit 先改内存、再写盘**，写盘失败时内存已经是新值——
 * 「写后回读判不出失败」这件事就靠这条语义复现（ADR-0052）。
 *
 * 住 `lib_ebook_api` 的 testFixtures：lib_ebook_api / lib_book_common / module_me
 * 三处测试都要制造 SP 写盘失败，各留一份就会漂移；`lib_ebook_api` 是依赖图最底层的公共模块。
 * 生产代码不增加测试专用参数——被测对象经 Context/SP 取依赖，用 [FakeSpContext] 换掉那一个依赖。
 */
class FakeSharedPreferences : SharedPreferences {
    private val values = mutableMapOf<String, Any?>()

    var commitSucceeds = true
    var commitCount = 0
        private set
    var lastCommittedKeys: Set<String> = emptySet()
        private set

    override fun getAll(): MutableMap<String, *> = values.toMutableMap()

    override fun getString(key: String?, defValue: String?): String? =
        values[key] as? String ?: defValue

    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
        (values[key] as? Set<String>)?.toMutableSet() ?: defValues

    override fun getInt(key: String?, defValue: Int): Int = values[key] as? Int ?: defValue

    override fun getLong(key: String?, defValue: Long): Long = values[key] as? Long ?: defValue

    override fun getFloat(key: String?, defValue: Float): Float = values[key] as? Float ?: defValue

    override fun getBoolean(key: String?, defValue: Boolean): Boolean =
        values[key] as? Boolean ?: defValue

    override fun contains(key: String?): Boolean = values.containsKey(key)

    override fun edit(): SharedPreferences.Editor = FakeEditor()

    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) = Unit

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) = Unit

    private inner class FakeEditor : SharedPreferences.Editor {
        private val puts = mutableMapOf<String, Any?>()
        private val removals = mutableSetOf<String>()
        private var clear = false

        override fun putString(key: String, value: String?) = apply { puts[key] = value }

        override fun putStringSet(key: String, values: MutableSet<String>?) =
            apply { puts[key] = values?.toSet() }

        override fun putInt(key: String, value: Int) = apply { puts[key] = value }

        override fun putLong(key: String, value: Long) = apply { puts[key] = value }

        override fun putFloat(key: String, value: Float) = apply { puts[key] = value }

        override fun putBoolean(key: String, value: Boolean) = apply { puts[key] = value }

        override fun remove(key: String) = apply { removals += key }

        override fun clear() = apply { clear = true }

        override fun commit(): Boolean {
            commitCount++
            lastCommittedKeys = puts.keys + removals
            applyToValues()
            return commitSucceeds
        }

        override fun apply() {
            applyToValues()
        }

        private fun applyToValues() {
            if (clear) values.clear()
            removals.forEach(values::remove)
            puts.forEach { (key, value) ->
                if (value == null) values.remove(key) else values[key] = value
            }
        }
    }
}

/** 只替换 getSharedPreferences 的 Context：其余行为与被包装的 Context 一致。 */
class FakeSpContext(
    base: Context,
    private val sp: SharedPreferences,
) : ContextWrapper(base) {
    override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = sp
}
