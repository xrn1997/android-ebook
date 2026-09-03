package com.ebook.db.dao

import androidx.room3.*
import com.ebook.db.entity.BookShelfEntity
import com.ebook.db.entity.BookShelfFullInfo
import kotlinx.coroutines.flow.Flow

/**
 * 书架表（book_shelf）访问器：用户主动收藏的书籍及其阅读进度。
 *
 * 一行书架只存「书与书架的关系 + 进度」——自然键 `note_url`、当前章节与页码、最后阅读时间、
 * 书源归属标记 `tag`；书名/作者/封面在 book_info、章节列表在 chapter_list，两表同样以 `note_url`
 * 关联。读取时要么走 [BookShelfFullInfo] 的 `@Relation` 拼装，要么由调用方回填
 * [com.ebook.db.entity.BookShelfEntity] 上的 `@Ignore` 字段。
 *
 * 书架页、阅读器、下载队列与「我的」页阅读统计都以本 DAO 为数据源，排序口径统一为
 * `final_date DESC`（最后阅读优先）。
 */
@Dao
interface BookShelfDao {
    /**
     * 书架全量（含书籍信息与章节列表），按最后阅读时间倒序。书架页的一次性供数入口。
     *
     * [Transaction] 不可省：`@Relation` 在主查询之后还会发子查询，不同一个读事务里就可能拼出
     * 「书已删、章节仍在」的中间态。
     *
     * 关联章节没有 ORDER BY、按物理 rowid 返回，调用方需自行按 durChapterIndex 排序；
     * info 为 null 的孤立行（书架有记录但书籍信息已被删）也由调用方负责清理，
     * 两处都在 BookRepository.getAllBooksWithDetails 收口。
     */
    @Transaction
    @Query("SELECT * FROM book_shelf ORDER BY final_date DESC")
    suspend fun getAllBooksFullInfo(): List<BookShelfFullInfo>

    /**
     * 观察书架全量数据（含书籍信息与章节列表），按最后阅读时间倒序。
     *
     * Flow 版本：Room 基于失效追踪自动推送，书架增删/进度更新时收集方自动刷新，
     * 供「我的」页阅读统计等响应式场景使用。
     *
     * 与 [getAllBooksFullInfo] 同理保留 [Transaction]；差别是本流只做关联填充，
     * 不能顺手清理孤立记录（写副作用会随每次失效反复触发），故清理责任留在一次性查询侧。
     */
    @Transaction
    @Query("SELECT * FROM book_shelf ORDER BY final_date DESC")
    fun getAllBooksFullInfoFlow(): Flow<List<BookShelfFullInfo>>

    /** 单本书的完整信息（书架行 + 书籍元数据 + 章节列表）；不在书架时返回 null */
    @Transaction
    @Query("SELECT * FROM book_shelf WHERE note_url = :noteUrl")
    suspend fun getBookFullInfoByUrl(noteUrl: String): BookShelfFullInfo?

    /** 书架全量的响应式观察，只查书架行、不做关联拼装（用不到书名/章节的场景走它，省掉子查询） */
    @Query("SELECT * FROM book_shelf ORDER BY final_date DESC")
    fun getAllBooksFlow(): Flow<List<BookShelfEntity>>

    /** 书架全量快照（仅书架行），按最后阅读时间倒序；下载队列遍历书架找待下载书时用此入口 */
    @Query("SELECT * FROM book_shelf ORDER BY final_date DESC")
    suspend fun getAllBooks(): List<BookShelfEntity>

    /** 按 `note_url` 取单条书架记录（判定某书是否在架、读当前进度），未加书架返回 null */
    @Query("SELECT * FROM book_shelf WHERE note_url = :noteUrl")
    suspend fun getBookByUrl(noteUrl: String): BookShelfEntity?

    /** 按 `note_url` 批量取书架记录：一次查询判定多本书是否在架，避免逐条 [getBookByUrl] */
    @Query("SELECT * FROM book_shelf WHERE note_url IN (:noteUrls)")
    suspend fun getBooksByUrls(noteUrls: List<String>): List<BookShelfEntity>

    /**
     * 写入书架行：主键是自然键 `note_url`，REPLACE 即「按 URL upsert」，存在则整行替换，
     * 因此保存进度这类更新也直接用它，不必先查后写。
     *
     * 两点约束：
     * - 只作用于 book_shelf 一张表——实体的 `@Ignore` bookInfo/chapterList 不落库，
     *   加入书架时三张表要分别写（见 BookRepository.addToShelf）；
     * - 整行替换意味着未赋值字段会按实体默认值一起写回，调用方必须传完整对象。
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(bookShelf: BookShelfEntity)

    /** [insert] 的批量版本，同样按 `note_url` upsert、同样只碰 book_shelf 一张表 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(books: List<BookShelfEntity>)

    /**
     * 按主键（`note_url`）原地 UPDATE 整行：与 [insert] 的 REPLACE 相比不动 rowid，
     * 但主键不匹配时静默不写任何数据（无返回值可判），故调用方需先确认记录存在。
     */
    @Update
    suspend fun update(bookShelf: BookShelfEntity)

    /** 删除传入实体对应的书架行（`@Delete` 按主键匹配，忽略实体上的其他字段值） */
    @Delete
    suspend fun delete(bookShelf: BookShelfEntity)

    /**
     * 按 URL 删除书架行。
     *
     * 本模块所有实体都未声明 foreignKeys、不存在级联删除，因此只调本方法会在
     * book_info / chapter_list / book_content 留下孤立数据；从书架移除必须由调用方逐表清理
     * （见 BookRepository.removeFromShelf）。
     */
    @Query("DELETE FROM book_shelf WHERE note_url = :noteUrl")
    suspend fun deleteByUrl(noteUrl: String)

    /** 在架书籍总数 */
    @Query("SELECT COUNT(*) FROM book_shelf")
    suspend fun getCount(): Int
}
