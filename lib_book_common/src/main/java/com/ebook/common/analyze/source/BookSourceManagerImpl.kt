package com.ebook.common.analyze.source

import android.content.Context
import androidx.core.content.edit
import com.ebook.api.entity.BookSourceRule
import com.xrn1997.common.util.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * 书源管理器实现
 * 负责加载、切换、导入导出书源规则
 *
 * 使用纯净 OkHttpClient（@Named("source")），不携带登录凭证，避免 token 泄漏给第三方书源。
 */
@Singleton
class BookSourceManagerImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    @Named("source") private val okHttpClient: OkHttpClient
) : BookSourceManager {
    companion object {
        private const val TAG = "BookSourceManager"
        private const val DEFAULT_SOURCES_FILE = "default_sources.json"
        private const val PREFS_NAME = "book_source_prefs"
        private const val KEY_CURRENT_SOURCE = "current_source_url"
    }

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    private val sources = mutableListOf<BookSourceRule>()
    private var currentSourceRule: BookSourceRule? = null
    override val currentSource: BookSourceRule? get() = currentSourceRule

    private var parser: BookParser? = null
    override val currentParser: BookParser? get() = parser

    init {
        loadDefaultSources()
        val savedUrl = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_CURRENT_SOURCE, null)
        val target = if (savedUrl != null) {
            sources.find { it.url == savedUrl && it.enabled }
        } else {
            sources.firstOrNull { it.enabled }
        }
        if (target != null) {
            switchSource(target)
        }
    }

    private fun loadDefaultSources() {
        try {
            val jsonStr = context.assets.open(DEFAULT_SOURCES_FILE).bufferedReader().use { it.readText() }
            val list = json.decodeFromString<List<BookSourceRule>>(jsonStr)
            sources.clear()
            sources.addAll(list.filter { it.enabled })
            Logger.d(TAG, "加载了 ${sources.size} 个默认书源")
        } catch (e: Exception) {
            Logger.e(TAG, "加载默认书源失败", e)
        }
    }

    override fun getAllSources(): List<BookSourceRule> = sources.toList()

    override fun getEnabledSources(): List<BookSourceRule> = sources.filter { it.enabled }

    override fun switchSource(rule: BookSourceRule) {
        currentSourceRule = rule
        parser = JsoupBookParser(rule, okHttpClient)
        Logger.d(TAG, "切换书源: ${rule.name} (${rule.url})")
    }

    override fun requireParser(): BookParser = parser
        ?: throw IllegalStateException("未配置书源，请先初始化 BookSourceManager")

    override fun importFromJson(jsonStr: String): BookSourceRule? {
        return try {
            val rule = json.decodeFromString<BookSourceRule>(jsonStr)
            if (rule.name.isNotEmpty() && rule.url.isNotEmpty()) {
                sources.add(rule)
                rule
            } else {
                null
            }
        } catch (e: Exception) {
            Logger.e(TAG, "导入书源失败", e)
            null
        }
    }

    override fun exportToJson(rule: BookSourceRule): String {
        return json.encodeToString(BookSourceRule.serializer(), rule)
    }

    override fun saveCurrentSource(context: Context) {
        currentSourceRule?.let {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit {
                    putString(KEY_CURRENT_SOURCE, it.url)
                }
        }
    }
}
