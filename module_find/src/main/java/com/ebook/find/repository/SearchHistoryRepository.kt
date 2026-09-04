package com.ebook.find.repository

import com.ebook.db.dao.SearchHistoryDao
import com.ebook.db.entity.SearchHistoryEntity
import com.xrn1997.common.mvvm.model.BaseModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 搜索历史仓库（IO 线程）。
 *
 * upsert 语义：同一 type+content 组合仅更新时间戳，不产生重复记录。
 * 所有方法经 [withContext] 切到 IO 调度器，调用方（ViewModel）直接在主线程调用即可。
 */
@Singleton
class SearchHistoryRepository @Inject constructor(
    private val searchHistoryDao: SearchHistoryDao
) : BaseModel() {

    /** 保存搜索记录：存在则更新时间戳，不存在则新建。返回插入/更新后的实体。 */
    suspend fun insertSearchHistory(type: Int, content: String): SearchHistoryEntity = withContext(Dispatchers.IO) {
        val existing = searchHistoryDao.findByTypeAndContent(type, content)
        val searchHistory = existing?.copy(date = System.currentTimeMillis())
            ?: SearchHistoryEntity(type = type, content = content, date = System.currentTimeMillis())
        searchHistoryDao.insert(searchHistory)
        searchHistory
    }

    /** 清除该类型全部历史，返回被删除的记录数。 */
    suspend fun cleanSearchHistory(type: Int): Int = withContext(Dispatchers.IO) {
        val histories = searchHistoryDao.getByType(type)
        searchHistoryDao.clearByType(type)
        histories.size
    }

    /** 查询该类型全部历史（按时间戳倒序，最新在前）。 */
    suspend fun querySearchHistory(type: Int): List<SearchHistoryEntity> = withContext(Dispatchers.IO) {
        searchHistoryDao.getByType(type)
    }
}
