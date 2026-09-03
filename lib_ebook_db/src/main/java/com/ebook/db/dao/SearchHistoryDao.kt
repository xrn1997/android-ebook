package com.ebook.db.dao

import androidx.room3.*
import com.ebook.db.entity.SearchHistoryEntity

@Dao
interface SearchHistoryDao {
    @Query("SELECT * FROM search_history ORDER BY date DESC")
    suspend fun getAll(): List<SearchHistoryEntity>

    /**
     * 取该类型全部历史（面板每次刷新全量历史的读取入口，语义见 ADR-0005）。
     * 历史面板纯展示全部，不做子串过滤，故不带内容过滤参数。
     */
    @Query("SELECT * FROM search_history WHERE type = :type ORDER BY date DESC")
    suspend fun getByType(type: Int): List<SearchHistoryEntity>

    /** 按精确内容查单条：仅供 upsert 查重（同一词条重复搜索时更新时间戳而非新增）。 */
    @Query("SELECT * FROM search_history WHERE type = :type AND content = :content LIMIT 1")
    suspend fun findByTypeAndContent(type: Int, content: String): SearchHistoryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(history: SearchHistoryEntity)

    /** 清除该类型全部历史（与 [getByType] 同范围，保证「清除」删的正是面板展示的集合）。 */
    @Query("DELETE FROM search_history WHERE type = :type")
    suspend fun clearByType(type: Int)

    @Query("DELETE FROM search_history")
    suspend fun clearAll()
}
