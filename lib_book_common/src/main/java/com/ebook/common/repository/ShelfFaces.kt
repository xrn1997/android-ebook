package com.ebook.common.repository

import com.ebook.common.analyze.local.ChapterContent
import com.ebook.db.entity.BookGroupEntity
import com.ebook.db.entity.BookShelfEntity
import com.ebook.db.entity.ChapterListEntity
import com.ebook.db.entity.SearchBookEntity
import com.xrn1997.common.mvvm.model.BaseModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow

/**
 * `BookRepository` 按调用方切出的四个窄面（ADR-0055；与书源四面 ADR-0047 同形，不推翻）。
 *
 * 生产消费者注入**最窄**的那一面（或几面），只暴露自己真正调用的方法；
 * 聚合 `BookRepository` 实现全部四面，但只留给测试与对账入口
 * （`MyApplication.ContentStoreEntryPoint` 的 `reconcileContentStore()`——对账不进任何面）。
 * 四面全部经 Hilt `@Binds @Singleton` 绑定到同一个 `@Singleton BookRepository`
 * （绑定形态见 `di/RepositoryModule.kt`，理由同 `di/AnalyzeModule.kt`：拆实例会静默复制状态）。
 *
 * 切分判据是**调用方**而不是层次：一个方法归哪一面，看生产上谁在调它；
 * `getBookWithDetails` 同时出现在 [ShelfRepository] 与 [ContentReader] 两个面——
 * 恢复路径（`RepositoryReaderSessionGateway`）与书架路径都要它，实现仍只有
 * `BookRepository` 里那一份。
 */

/**
 * 书架条目读写面：`book_shelf` 四表条目的增删改查、阅读进度、换源与书架事件。
 *
 * 事件留在**写条目这一面**（[bookShelfEvents]）：「事件在提交后才发」是写事务的纪律
 * （`ShelfEntryStore`），发布者与写入者同面才不会出现两份事件流。
 *
 * 继承 [BaseModel] 不是本面的语义，而是泛型约束的落点：`BookListViewModel`（`BaseRefreshViewModel`
 * 的 model 位）、`BookDetailViewModel` 与 `BookReadViewModel`（`BaseViewModel` 的 model 位）
 * 的基类要求 `M : BaseModel`（见 com.xrn1997.common 的基类签名），所以书架面必须扛住这个约束；
 * 其余三面没有 model 位调方，保持纯净接口。**因此本面写成抽象类而不是接口**——
 * Kotlin 的接口不能继承类（`interface ShelfRepository : BaseModel` 编译不过），
 * 抽象类是「继承 BaseModel 的窄面」唯一的合法形态；对注入方而言用法与接口无异。
 */
abstract class ShelfRepository : BaseModel() {

    /** 书架变化事件（Added/Removed/ProgressUpdated/ChaptersUpdated），提交后才发。 */
    abstract val bookShelfEvents: SharedFlow<BookShelfEvent>

    /** 获取所有书籍（简单列表，无关联数据）。 */
    abstract suspend fun getAllBooks(): List<BookShelfEntity>

    /** 观察书架全部数据（含书籍信息，按最后阅读时间倒序），响应式供数。 */
    abstract fun observeBookShelf(): Flow<List<BookShelfEntity>>

    /** 获取所有书籍（含书籍信息和章节列表），孤立条目按「一个条目」清理。 */
    abstract suspend fun getAllBooksWithDetails(): List<BookShelfEntity>

    /** 根据 URL 获取书架条目（裸行，无 bookInfo/chapterList）。 */
    abstract suspend fun getBookByUrl(noteUrl: String): BookShelfEntity?

    /** 按 URL 取「可直接交给阅读器/UI 的完整条目」；同时是 [ContentReader] 的恢复入口。 */
    abstract suspend fun getBookWithDetails(noteUrl: String): BookShelfEntity?

    /** 保存阅读进度（只更新已在书架的行，落点钳到库内行数以内）。 */
    abstract suspend fun saveProgress(bookShelf: BookShelfEntity)

    /** 添加到书架：一个条目的四张表收进一次写事务，事件等提交返回之后再发。 */
    abstract suspend fun addToShelf(bookShelf: BookShelfEntity)

    /** 从书架移除（含关联数据清理：四表行 + 章文件 + 内存缓存）。 */
    abstract suspend fun removeFromShelf(bookShelf: BookShelfEntity)

    /** 阅读中换源（ADR-0016）：把书架条目原地替换成另一书源的同作品条目。 */
    abstract suspend fun switchSource(
        oldShelf: BookShelfEntity,
        newBook: SearchBookEntity,
    ): Result<BookShelfEntity>

    /** 导入判重命中「补章」处置：把新条目多出的尾部章节并入旧条目，新条目退场。 */
    abstract suspend fun mergeTailChapters(newNoteUrl: String, oldNoteUrl: String): ImportMergeResult
}

/**
 * 章节正文读取面：网络书与本地书统一的单章读取与缓存刷新。
 *
 * 唯一消费者是阅读会话的仓库适配器（`RepositoryReaderSessionGateway`）——
 * 阅读器恢复与翻页都经它读正文。
 */
interface ContentReader {

    /**
     * 章节正文统一读取入口：按 bookFormat 路由 reader → 规范化 → 内存缓存 → 章文件/网络。
     * `chapterContentRef` 默认空串（网络书按 `chapterRef(noteUrl, index)` 兜底定位）。
     */
    suspend fun loadChapter(
        bookShelf: BookShelfEntity,
        index: Int,
        title: String,
        chapterContentRef: String = "",
    ): ChapterContent?

    /** 刷新单章缓存（仅网络书）：删除章文件 + 失效内存缓存，下次读取时重新抓。 */
    suspend fun refreshChapter(bookShelf: BookShelfEntity, index: Int)

    /**
     * 按 URL 取「可直接交给阅读器/UI 的完整条目」（书架行 + 元数据 + 章节列表）。
     * 与 [ShelfRepository.getBookWithDetails] 同一份实现：恢复路径同时要书架面与正文面。
     */
    suspend fun getBookWithDetails(noteUrl: String): BookShelfEntity?
}

/**
 * 目录同步面：远端目录的重抓、纯追加落库与库内目录回读。
 *
 * 消费者是详情页（`BookDetailViewModel`）与阅读会话适配器（`RepositoryReaderSessionGateway`）。
 */
interface ChapterTocSync {

    /**
     * 按需重抓目录并追加新章（限频判窗 + 纯追加判定）；`force` 跳过限频（默认 false）。
     */
    suspend fun syncChaptersFromSource(
        bookShelf: BookShelfEntity,
        force: Boolean = false,
    ): ChapterSyncResult

    /** 把已抓在手上的远端目录按纯追加规则落库（不发网络）。 */
    suspend fun appendRemoteChapters(
        bookShelf: BookShelfEntity,
        remoteChapters: List<ChapterListEntity>,
    ): ChapterSyncResult

    /** 取「库里那份」章节目录，按章序号升序；要写进度或渲染目录一律回读这里。 */
    suspend fun getStoredChapters(noteUrl: String): List<ChapterListEntity>
}

/**
 * 评论聚合键面：`book_group` 表的读并集、写主键、合并/拆分/修键。
 *
 * 消费者是修键面板（`EditBookMetaViewModel`）、阅读页评论入口（`ReadBookActivity`）
 * 与导入覆盖处置（`LocalImportCoordinator` 的 `absorbGroupKeys`）。
 */
interface CommentKeys {

    /** 取某本书关联的全部评论聚合键（读并集）。 */
    suspend fun getCommentKeysForBook(noteUrl: String): List<String>

    /** 取某本书的写入键（`is_primary` 那行），无行时 null。 */
    suspend fun getPrimaryKeyForBook(noteUrl: String): String?

    /** 取某本书的全部 book_group 行（含 isPrimary 标记），供修键面板展示。 */
    suspend fun getBookGroupRows(noteUrl: String): List<BookGroupEntity>

    /** 把 sourceNoteUrl 的全部关联键并入 targetNoteUrl 的并集（覆盖处置前吸收旧条目键）。 */
    suspend fun absorbGroupKeys(targetNoteUrl: String, sourceNoteUrl: String)

    /** 拆分：从并集里删一个 secondary 键行（不许删主键行）。 */
    suspend fun splitBook(noteUrl: String, commentKeyToRemove: String)

    /** 修键：改主匹配名/作者 → 重算键 → 旧主键降级、新键成为主键。 */
    suspend fun updateMatchMeta(
        noteUrl: String,
        newMatchName: String,
        newMatchAuthor: String,
    ): Pair<String, String>
}
