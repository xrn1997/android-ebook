package com.ebook.db.dao

import androidx.room3.*
import com.ebook.db.entity.BookSourceEntity
import kotlinx.coroutines.flow.Flow

/**
 * 书源表（book_source）访问器：多书源共存的数据底座（见 ADR-0016）。
 *
 * 一行 = 一个书源站点，主键是自然键 `url`（见 [BookSourceEntity]），因此**没有"按 id 更新"这回事**，
 * 写入一律是整行 REPLACE upsert；`url` 既是匹配键也是业务表 `tag` 列指向的目标，覆盖导入不会改变归属。
 *
 * 本表由 `lib_book_common` 的 `BookSourceManager` 独占消费（挂起面一律现查本表）：由它把行
 * 反序列化成 `BookSourceRule` 对外暴露，业务模块不直接依赖本 DAO——想加新方法前先确认这个
 * 边界是否真的要破。
 *
 * 本表是书源清单的**唯一事实源**：应用不随包携带任何书源，表里的每一行都由用户导入而来，
 * 因此不存在「内置源受保护」这类特殊行，也无法从别处（assets）回填清单。
 *
 * 排序口径全表统一为 `weight ASC, added_at ASC, url ASC`（权重小的在前，同权重按导入先后，再同则按 url）：
 * 三个取全量的查询必须一致，否则书源管理页看到的顺序与聚合搜索/书城取"第一个可用源"的顺序会不一致。
 * 末位 `url` 不是凑数：批量导入整包社区书源时各行 `weight` 普遍同为默认值、`added_at` 又极可能落在
 * 同一毫秒，少了这个唯一键 SQLite 就不保证顺序，列表每次刷新都可能换排列。
 */
@Dao
interface BookSourceDao {
    /**
     * 观察全部书源（含被禁用的），按统一排序口径。
     *
     * Room 的失效追踪使增删改后自动重推新列表，供书源管理页直接订阅；
     * 与 [getAll] 同 SQL，区别只在"要不要跟随变化"——需要即时反映用户增删改的界面（书源管理页）
     * 订阅本方法，不需要跟随变化的一次性场合走 [getAll]；别为了省事在 Flow 上 `first()`，
     * 那等于白挂一层失效监听。
     */
    @Query("SELECT * FROM book_source ORDER BY weight ASC, added_at ASC, url ASC")
    fun observeAll(): Flow<List<BookSourceEntity>>

    /** 全量书源快照（含被禁用的），一次性读取用：`BookSourceManager.getAllSources` 的背后就是它 */
    @Query("SELECT * FROM book_source ORDER BY weight ASC, added_at ASC, url ASC")
    suspend fun getAll(): List<BookSourceEntity>

    /**
     * 按 URL 精确取单本书源；未收录返回 null（调用方据此判定「书源已被删除」并给失效提示）。
     *
     * 但这个 null 不能当成「凡按 `tag` 查不到就是书源被删」的通用结论：本地书行的 `tag` 是常量
     * [com.ebook.db.entity.BookShelfEntity.LOCAL_TAG]（`"loc_book"`），它不参与「按书源 URL 找 parser」，
     * 本表里永无这一行，取不到行不代表书源被删。
     *
     * 禁用与否不参与过滤：书架里绑着该源的书仍要能解析，禁用只表示它不参与新的搜索/书城候选。
     */
    @Query("SELECT * FROM book_source WHERE url = :url")
    suspend fun getByUrl(url: String): BookSourceEntity?

    /**
     * 启用中的书源，按统一排序口径：供规则类型化读面（`getEnabledSources`）与聚合搜索（并发逐源）取用。
     *
     * 默认源回落**不查本方法**——它读 [getAll] 后在条目面筛 `enabled`，因为候选要先经解码挡下坏行；
     * 书城切换候选走 [observeAll] 的 enabled 过滤。
     */
    @Query("SELECT * FROM book_source WHERE enabled = 1 ORDER BY weight ASC, added_at ASC, url ASC")
    suspend fun getEnabled(): List<BookSourceEntity>

    /**
     * 整行写入：主键 `url` 命中既有行时 REPLACE 先删后插，即「同站点重新导入 = 更新规则」。
     *
     * 两点后果，调用方必须知情：
     * - **整行替换**，未赋值字段会按实体默认值一起写回。改启用状态请用 [setEnabled]；
     *   想保留用户既有的启用/权重配置，覆盖导入前要先 [getByUrl] 把旧行的这些字段带过来。
     * - 覆盖导入不改变行数：主键命中即原地换规则，表里不会出现同一站点的第二行。
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(source: BookSourceEntity)

    /**
     * [upsert] 的批量版本（同事务一次写入多条书源），写入语义与 [upsert] 逐字相同。
     *
     * 当前无调用方，保留为批量写入的入口——需要一次落多条时用它比逐条 [upsert] 少几次事务，
     * 逐条调用亦无正确性问题。
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(sources: List<BookSourceEntity>)

    /**
     * 只改启用状态。
     *
     * 用 UPDATE 而非 [upsert] 是有意的：Switch 拨一下不该把 `weight`/`rule_json` 按实体默认值冲掉。
     * `url` 不存在时静默不写（与 [com.ebook.db.dao.BookShelfDao.update] 同构），调用方应已由
     * [getAll] 拿到该行才发起。
     */
    @Query("UPDATE book_source SET enabled = :enabled WHERE url = :url")
    suspend fun setEnabled(url: String, enabled: Boolean)

    /**
     * 按 URL 删除书源，返回受影响行数。
     *
     * **没有任何保护条件**：应用不随包携带书源，表里每一行都是用户自己导入的，都可删。
     * 返回 0 只有一种成因——这行本就不存在。
     */
    @Query("DELETE FROM book_source WHERE url = :url")
    suspend fun deleteByUrl(url: String): Int
}
