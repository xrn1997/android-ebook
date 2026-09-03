package com.ebook.book.repository

import com.ebook.db.dao.BookShelfDao
import com.ebook.db.dao.ChapterListDao
import com.ebook.db.dao.DownloadChapterDao
import com.ebook.db.entity.BookShelfEntity
import com.ebook.db.entity.DownloadChapterEntity
import com.xrn1997.common.mvvm.model.BaseModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 下载仓库 - 管理下载任务数据和事件（兼作下载管理页 ViewModel 的 Model，见 BaseModel）。
 *
 * 职责：
 * - 下载任务的 CRUD 操作
 * - 下载状态的事件通道（Service → UI）
 *
 * 控制动作（开始/暂停/取消/新增任务）不再经本仓库中转：原命令通道是 replay=0 的
 * SharedFlow，唯一订阅者 DownloadService 不存活时命令直接丢失（按钮"点了没反应"），
 * 现统一由 [com.ebook.book.service.DownloadService] 的 Intent 直达。
 *
 * 注意本类是 @Singleton 且被 DownloadService 与多个 ViewModel 共享：继承的 [BaseModel.release]
 * 为默认空实现，VM 清理时的 releaseAsync 不会破坏共享状态。
 */
@Singleton
class DownloadRepository @Inject constructor(
    private val downloadChapterDao: DownloadChapterDao,
    private val bookShelfDao: BookShelfDao,
    private val chapterListDao: ChapterListDao
) : BaseModel() {
    // ===== 事件通道 =====

    // replay=1：下载管理页是晚开的（用户在阅读器发起下载后才打开管理页），
    // 无回放时收集方只能从 initial=Finished 起步、错过已发出的 Progress，导致页面
    // 显示与真实下载状态脱节；保留最新一条状态使晚开的收集方立即对齐当前进度。
    // 缓冲仅存活于进程内，冷启动后自然回到 Finished 初值，无残留风险；
    // 服务各终态路径（完成/取消）均须发 Finished 避免旧 Progress 被回放成"幽灵进度"
    private val _downloadState = MutableSharedFlow<DownloadState>(replay = 1, extraBufferCapacity = 64)
    val downloadState: SharedFlow<DownloadState> = _downloadState.asSharedFlow()

    // ===== 数据操作 =====

    /** 获取下一章待下载任务 */
    suspend fun getNextDownloadTask(): DownloadChapterEntity? = withContext(Dispatchers.IO) {
        for (shelf in bookShelfDao.getAllBooks()) {
            if (shelf.tag != BookShelfEntity.LOCAL_TAG) {
                val task = downloadChapterDao.getFirstByNoteUrl(shelf.noteUrl)
                if (task != null) return@withContext task
            }
        }
        null
    }

    /**
     * 查找书架上第一个非本地书的最近下载章节。
     *
     * 与 [getNextDownloadTask] 同构（遍历书架 → 排除本地书 → 按书取下载章节），
     * 仅排序方向不同（取最新一章）；弹窗初始化用它判断「是否有待下载任务」。
     */
    suspend fun findLatestDownloadTask(): DownloadChapterEntity? = withContext(Dispatchers.IO) {
        for (shelf in bookShelfDao.getAllBooks()) {
            if (shelf.tag != BookShelfEntity.LOCAL_TAG) {
                val task = downloadChapterDao.getLastByNoteUrl(shelf.noteUrl)
                if (task != null) return@withContext task
            }
        }
        null
    }

    /** 添加下载任务（去重） */
    suspend fun addTasks(chapters: List<DownloadChapterEntity>) = withContext(Dispatchers.IO) {
        val entities = chapters.map { chapter ->
            val existing = downloadChapterDao.getChapterByUrl(chapter.durChapterUrl)
            DownloadChapterEntity(
                id = existing?.id ?: 0L,
                noteUrl = chapter.noteUrl,
                durChapterIndex = chapter.durChapterIndex,
                durChapterUrl = chapter.durChapterUrl,
                durChapterName = chapter.durChapterName,
                tag = chapter.tag,
                bookName = chapter.bookName,
                coverUrl = chapter.coverUrl,
                // 透传强制刷新标记：服务端据此先删旧内容再重抓（见 DownloadService.downloading）
                forceRefresh = chapter.forceRefresh
            )
        }
        downloadChapterDao.insertAll(entities)
    }

    /** 删除指定下载任务 */
    suspend fun deleteTask(chapter: DownloadChapterEntity) = withContext(Dispatchers.IO) {
        downloadChapterDao.delete(chapter)
    }

    /** 清空所有下载任务 */
    suspend fun clearAllTasks() = withContext(Dispatchers.IO) {
        downloadChapterDao.clearAll()
    }

    /**
     * 待下载任务总数（表内只存未完成任务，故等价"队列剩余章数"）。
     *
     * 供下载通知展示剩余量：原先只报"当前章节名"，用户看不出这批任务有多大、还剩多少。
     */
    suspend fun countTasks(): Int = withContext(Dispatchers.IO) {
        downloadChapterDao.count()
    }

    /**
     * 队列剩余数的响应式观察（书架下载图标角标）：任务增删时自动重推。
     */
    fun observeRemainingCount(): Flow<Int> = downloadChapterDao.observeRemainingCount()

    /**
     * 全部待下载任务（按书名升序）：下载管理页据此按书分组展示剩余章数。
     */
    suspend fun getAllTasks(): List<DownloadChapterEntity> = withContext(Dispatchers.IO) {
        downloadChapterDao.getAllTasks()
    }

    /**
     * 删除某本书的全部待下载任务（下载管理页"取消本书"）。
     *
     * 注意：若服务此刻正在下载该书的章节，删除不会打断已发出的网络请求（
     * 请求完成后会重新入库——见 DownloadService 保存分支），仅保证后续队列不再拉该书。
     */
    suspend fun deleteTasksForBook(noteUrl: String) = withContext(Dispatchers.IO) {
        downloadChapterDao.deleteByNoteUrl(noteUrl)
    }

    /**
     * 某书的全书缓存覆盖率（已缓存 / 总章节）。
     *
     * 与"队列剩余"是两个口径：队列是"本次还没下完的"，覆盖率是"全书已可离线的比例"，
     * 后者不随批次变化、随阅读/下载增长，适合当进度条。
     */
    suspend fun getCacheCoverage(noteUrl: String): CacheCoverage = withContext(Dispatchers.IO) {
        CacheCoverage(
            total = chapterListDao.countChaptersForBook(noteUrl),
            cached = chapterListDao.countCachedChaptersForBook(noteUrl)
        )
    }

    suspend fun emitState(state: DownloadState) {
        _downloadState.emit(state)
    }

    /**
     * 非挂起发射下载状态：供服务超时回调（[com.ebook.book.service.DownloadService.onTimeout]）这类
     * "来不及起协程、马上就要 stopSelf"的路径使用。
     *
     * 通道有 extraBufferCapacity，tryEmit 不经挂起队列，故能在服务被销毁前落入 replay 缓冲；
     * 仅当缓冲（64）填满时返回 false，此时状态会被丢弃——超时场景下不致命（服务反正已停）。
     */
    fun tryEmitState(state: DownloadState): Boolean = _downloadState.tryEmit(state)
}

/**
 * 全书缓存覆盖率快照：某书已缓存章节数与总章节数。
 */
data class CacheCoverage(
    val total: Int,
    val cached: Int,
) {
    /** 缓存比例（0..1）；无章节时视为 0，避免除零 */
    val ratio: Float
        get() = if (total > 0) cached.toFloat() / total else 0f
}

/**
 * 下载状态（Service → UI）
 */
sealed class DownloadState {
    /** 下载进度更新 */
    data class Progress(val chapter: DownloadChapterEntity) : DownloadState()

    /** 下载暂停 */
    data object Paused : DownloadState()

    /** 下载完成 */
    data object Finished : DownloadState()
}
