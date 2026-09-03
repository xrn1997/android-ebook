package com.ebook.common.repository

import com.ebook.db.dao.BookContentDao
import com.ebook.db.dao.BookInfoDao
import com.ebook.db.dao.BookShelfDao
import com.ebook.db.dao.ChapterListDao
import com.ebook.db.entity.BookContentEntity
import com.ebook.db.entity.BookShelfEntity
import com.xrn1997.common.mvvm.model.BaseModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 书架仓库 - 封装书架数据操作和事件通知
 *
 * 职责：
 * - 书架 CRUD 操作
 * - 阅读进度保存
 * - 章节内容缓存管理
 * - 书架变化事件发布（替代原 RxBus 的书籍相关事件）
 */
@Singleton
class BookRepository @Inject constructor(
    private val bookShelfDao: BookShelfDao,
    private val bookInfoDao: BookInfoDao,
    private val chapterListDao: ChapterListDao,
    private val bookContentDao: BookContentDao
) : BaseModel() {
    // ===== 事件 =====
    private val _bookShelfEvents = MutableSharedFlow<BookShelfEvent>(extraBufferCapacity = 64)
    val bookShelfEvents: SharedFlow<BookShelfEvent> = _bookShelfEvents.asSharedFlow()

    // ===== 数据操作 =====

    /** 获取所有书籍（简单列表，无关联数据） */
    suspend fun getAllBooks(): List<BookShelfEntity> = withContext(Dispatchers.IO) {
        bookShelfDao.getAllBooks()
    }

    /**
     * 观察书架全部数据（含书籍信息，按最后阅读时间倒序），响应式供数。
     *
     * 与 [getAllBooksWithDetails] 的差异：Flow 版本只做关联填充、不清理孤立记录
     * （清理有写副作用，不适合放在每次失效都重发的观察流里，由一次性查询负责）。
     * info 为 null 的孤立条目直接过滤，保证下游拿到的 bookInfo 非空。
     */
    fun observeBookShelf(): Flow<List<BookShelfEntity>> =
        bookShelfDao.getAllBooksFullInfoFlow().map { fullInfoList ->
            fullInfoList.mapNotNull { fullInfo ->
                fullInfo.info?.let { info ->
                    fullInfo.bookShelf.apply { bookInfo = info }
                }
            }
        }

    /** 获取所有书籍（含书籍信息和章节列表） */
    suspend fun getAllBooksWithDetails(): List<BookShelfEntity> = withContext(Dispatchers.IO) {
        val result = mutableListOf<BookShelfEntity>()
        val toDelete = mutableListOf<String>()

        bookShelfDao.getAllBooksFullInfo().forEach { fullInfo ->
            if (fullInfo.info == null) {
                // 孤立记录，需要清理
                toDelete.add(fullInfo.bookShelf.noteUrl)
            } else {
                result.add(fullInfo.bookShelf.apply {
                    bookInfo = fullInfo.info
                    // 按章节序号显式排序：@Relation 关联查询无 ORDER BY、按物理 rowid 返回，
                    // 历史上被 REPLACE（先删后插）过的行 rowid 会跳表尾，导致已下载/缓存章节错序；
                    // 显式排序既修正存量乱序数据，也兜底任何未来可能扰动 rowid 的写入
                    chapterList = fullInfo.chapters.sortedBy { it.durChapterIndex }
                })
            }
        }

        // 清理孤立记录
        toDelete.forEach { noteUrl ->
            bookShelfDao.deleteByUrl(noteUrl)
        }

        result
    }

    /** 根据 URL 获取书架条目 */
    suspend fun getBookByUrl(noteUrl: String): BookShelfEntity? = withContext(Dispatchers.IO) {
        bookShelfDao.getBookByUrl(noteUrl)
    }

    /** 保存阅读进度 */
    suspend fun saveProgress(bookShelf: BookShelfEntity) = withContext(Dispatchers.IO) {
        bookShelf.finalDate = System.currentTimeMillis()
        bookShelfDao.insert(bookShelf)
        _bookShelfEvents.emit(BookShelfEvent.ProgressUpdated(bookShelf))
    }

    /** 添加到书架 */
    suspend fun addToShelf(bookShelf: BookShelfEntity) = withContext(Dispatchers.IO) {
        // 先保存 bookInfo（如果存在）
        bookShelf.bookInfo?.let { bookInfo ->
            bookInfo.noteUrl = bookShelf.noteUrl
            bookInfoDao.insert(bookInfo)
        }
        // 保存 bookShelf
        bookShelfDao.insert(bookShelf)
        // 保存 chapterList：实体上是非空 List（默认空集），只需判空集，不用安全调用
        val chapters = bookShelf.chapterList
        if (chapters.isNotEmpty()) {
            // 设置 noteUrl 关联
            chapters.forEach { it.noteUrl = bookShelf.noteUrl }
            chapterListDao.insertAll(chapters)
        }
        _bookShelfEvents.emit(BookShelfEvent.Added(bookShelf))
    }

    /** 从书架移除（含关联数据清理） */
    suspend fun removeFromShelf(bookShelf: BookShelfEntity) = withContext(Dispatchers.IO) {
        val chapters = chapterListDao.getChaptersForBook(bookShelf.noteUrl)
        val chapterUrls = chapters.map { it.durChapterUrl }
        if (chapterUrls.isNotEmpty()) {
            bookContentDao.deleteByChapterUrls(chapterUrls)
        }
        chapterListDao.deleteChaptersForBook(bookShelf.noteUrl)
        bookInfoDao.deleteByUrl(bookShelf.noteUrl)
        bookShelfDao.deleteByUrl(bookShelf.noteUrl)
        _bookShelfEvents.emit(BookShelfEvent.Removed(bookShelf))
    }

    // ===== 章节内容缓存操作 =====

    /** 从数据库加载章节内容 */
    suspend fun loadBookContent(chapterUrl: String): BookContentEntity? = withContext(Dispatchers.IO) {
        bookContentDao.getContentByChapterUrl(chapterUrl)
    }

    /** 保存章节内容到数据库 */
    suspend fun saveBookContent(content: BookContentEntity) = withContext(Dispatchers.IO) {
        bookContentDao.insert(content)
    }

    /** 删除章节内容（带强制刷新标记的下载任务重抓前清理旧正文，保证重抓结果真正生效） */
    suspend fun deleteBookContent(chapterUrl: String) = withContext(Dispatchers.IO) {
        bookContentDao.deleteByChapterUrl(chapterUrl)
    }

    /**
     * 批量查询已有正文缓存的章节 URL 集合。
     *
     * 供下载面板绘制"已缓存"徽章：以内容表为事实源，不依赖 ChapterListEntity.hasCache
     * 内存快照（阅读器内存列表可能滞后于阅读/下载的实际入库）。空入参短路，避免空 IN 查询。
     */
    suspend fun getCachedChapterUrls(chapterUrls: List<String>): Set<String> = withContext(Dispatchers.IO) {
        if (chapterUrls.isEmpty()) return@withContext emptySet()
        bookContentDao.getExistingChapterUrls(chapterUrls).toSet()
    }

    /** 更新章节缓存状态 */
    suspend fun updateChapterCache(chapterUrl: String, hasCache: Boolean) = withContext(Dispatchers.IO) {
        // 走 UPDATE 原地改写；不能用 insertAll(REPLACE)——先删后插会把行 rowid 移到表尾，
        // 而书架章节关联查询按 rowid 返回（无 ORDER BY），会造成目录错序（见 ChapterListDao.updateHasCache）
        chapterListDao.updateHasCache(chapterUrl, hasCache)
    }

}

/**
 * 书架事件定义
 */
sealed class BookShelfEvent {
    /** 书籍添加到书架 */
    data class Added(val bookShelf: BookShelfEntity) : BookShelfEvent()

    /** 书籍从书架移除 */
    data class Removed(val bookShelf: BookShelfEntity) : BookShelfEvent()

    /** 阅读进度更新 */
    data class ProgressUpdated(val bookShelf: BookShelfEntity) : BookShelfEvent()
}
