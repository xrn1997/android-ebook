package com.ebook.db.dao

import androidx.room3.*
import com.ebook.db.entity.SearchHistoryEntity

/**
 * 搜索历史表（search_history）访问器：用户本地搜索词记录（CONTEXT.md「搜索历史」）。
 *
 * 流水型数据，用自增主键 `id`（见 ADR-0003），`type` 区分搜索类型（当前只有书籍搜索，
 * 值取 SearchViewModel.BOOK），`date` 是写入/重搜时的毫秒时间戳，也是唯一的排序键。
 * 供搜索页历史面板展示与点选快捷搜索，`module_find` 的 SearchHistoryRepository 是唯一调用方。
 *
 * 语义已收敛为「按类型整体展示、整体清除」，不含按关键词过滤历史的能力（见 ADR-0005）：
 * 迁移前用 `content = :content` 精确匹配，导致进入页面时以空串查询永远查不到记录、面板首开空白；
 * 迁移中途一度改用 LIKE 通配来让"空串=全量"，但那属于临时妥协。
 */
@Dao
interface SearchHistoryDao {
    /**
     * 全表按时间倒序（跨类型，不做 `type` 隔离）。
     *
     * 历史面板的取数入口是按类型的 [getByType]，本方法仅供需要全量数据的场景使用。
     */
    @Query("SELECT * FROM search_history ORDER BY date DESC")
    suspend fun getAll(): List<SearchHistoryEntity>

    /**
     * 取该类型全部历史（面板每次刷新全量历史的读取入口，语义见 ADR-0005）。
     * 历史面板纯展示全部，不做子串过滤，故不带内容过滤参数。
     *
     * 这里刻意不用 `LIKE '%' || :content || '%'`：面板的角色是"展示全部 + 点选快捷搜索"，
     * 输入只用于发起新搜索，"按内容过滤历史"无人使用，却还要处理 `%`/`_` 通配符转义，
     * 得不偿失（详见 ADR-0005 的权衡）。
     */
    @Query("SELECT * FROM search_history WHERE type = :type ORDER BY date DESC")
    suspend fun getByType(type: Int): List<SearchHistoryEntity>

    /**
     * 按精确内容查单条：仅供 upsert 查重（同一词条重复搜索时更新时间戳而非新增）。
     *
     * 与 [getByType] 的"全量展示"语义正交：这里的 `content = :content` 必须是精确匹配，
     * 匹配上了才能定位到既有那行的 `id` 并覆盖它；换成 LIKE 会把不同词条误判为重复。
     */
    @Query("SELECT * FROM search_history WHERE type = :type AND content = :content LIMIT 1")
    suspend fun findByTypeAndContent(type: Int, content: String): SearchHistoryEntity?

    /**
     * 写入一条历史：`id = 0` 由 SQLite 分配新行（新词条）；带上 [findByTypeAndContent]
     * 查到的旧 `id` 时 REPLACE 即覆盖同一行，实现"重搜只更新时间戳、不产生重复记录"。
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(history: SearchHistoryEntity)

    /**
     * 清除该类型全部历史（与 [getByType] 同范围，保证「清除」删的正是面板展示的集合）。
     *
     * 与 [getByType] 一样不带内容条件：按输入框内容子集删除会让"清除"按钮删的东西
     * 少于面板上看得见的东西（ADR-0005）。
     */
    @Query("DELETE FROM search_history WHERE type = :type")
    suspend fun clearByType(type: Int)

    /** 清空所有类型的历史；面板的「清除」只针对当前类型，走 [clearByType] */
    @Query("DELETE FROM search_history")
    suspend fun clearAll()
}
