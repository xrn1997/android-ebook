package com.ebook.find.repository

import android.content.Context
import com.ebook.api.cache.ACache
import com.ebook.common.analyze.source.BookSourceManager
import com.ebook.db.entity.LibraryEntity
import com.ebook.db.entity.SearchBookEntity
import com.ebook.find.entity.BookType
import com.xrn1997.common.mvvm.model.BaseModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 书源数据仓库：分类入口、分类书籍、书库数据。
 *
 * 书库缓存（ACache）由本层持有，调用方（ViewModel）不感知缓存细节。
 */
@Singleton
class BookSourceRepository @Inject constructor(
    @ApplicationContext context: Context,
    private val bookSourceManager: BookSourceManager
) : BaseModel() {

    /** 书库数据磁盘缓存（解析器读取/写入，见 JsoupBookParser.getLibraryData） */
    private val cache: ACache = ACache.get(context)

    /** 获取书籍类型列表：从当前书源规则的 `ruleFind.kinds` 映射，无书源时返回空列表。 */
    fun getBookTypeList(): List<BookType> {
        val source = bookSourceManager.currentSource ?: return emptyList()
        val kinds = source.ruleFind.kinds
        return kinds.map { BookType(it.title, it.url) }
    }

    /** 获取分类书籍列表（IO 线程），[page] 从 1 开始。 */
    suspend fun getKindBook(url: String, page: Int): List<SearchBookEntity> = withContext(Dispatchers.IO) {
        bookSourceManager.requireParser().getKindBook(url, page)
    }

    /** 获取书库数据（IO 线程）：含缓存读取与失效重抓，缓存策略由解析器内部处理。 */
    suspend fun getLibraryData(): LibraryEntity = withContext(Dispatchers.IO) {
        bookSourceManager.requireParser().getLibraryData(cache)
    }
}
