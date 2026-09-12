package com.ebook.book.repository

import android.content.Context
import com.ebook.book.R
import com.ebook.book.service.DownloadService
import com.ebook.db.dao.BookShelfDao
import com.ebook.db.dao.ChapterListDao
import com.ebook.db.dao.DownloadChapterDao
import com.ebook.db.dao.PausedBookDao
import com.ebook.db.entity.BookShelfEntity
import com.ebook.db.entity.BookShelfFullInfo
import com.ebook.db.entity.ChapterListEntity
import com.ebook.db.entity.DownloadChapterEntity
import com.ebook.db.entity.PausedBookEntity
import com.ebook.common.analyze.local.BookLocation
import com.ebook.common.analyze.local.BookFormat
import com.ebook.common.store.BookStore
import com.xrn1997.common.mvvm.model.BaseModel
import com.xrn1997.common.util.ToastUtil
import dagger.hilt.android.qualifiers.ApplicationContext
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
    @ApplicationContext private val context: Context,
    private val downloadChapterDao: DownloadChapterDao,
    private val pausedBookDao: PausedBookDao,
    private val bookShelfDao: BookShelfDao,
    private val chapterListDao: ChapterListDao,
    private val bookStore: BookStore,
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

    /**
     * 获取下一章待下载任务（跳过暂停书，见 ADR-0036）。
     *
     * 暂停是**队列策略**，在本层做：遍历前取一次暂停标记集（[PausedBookDao.getAll]，
     * 行数 = 暂停书数，量小），命中即跳过该书——`DownloadChapterDao` 的既有查询与
     * 测试假件（`lib_book_common` 的 FakeDownloadChapterDao）因此零改动。
     * 暂停书的任务保留在表里，继续（[resumeBook]）后按书架顺序轮到即下。
     */
    suspend fun getNextDownloadTask(): DownloadChapterEntity? = withContext(Dispatchers.IO) {
        val paused = pausedBookDao.getAll().toSet()
        for (shelf in bookShelfDao.getAllBooks()) {
            if (shelf.tag != BookShelfEntity.LOCAL_TAG && shelf.noteUrl !in paused) {
                val task = downloadChapterDao.getFirstByNoteUrl(shelf.noteUrl)
                if (task != null) return@withContext task
            }
        }
        null
    }

    /**
     * 查找书架上第一个非本地书的最近下载章节（跳过暂停书）。
     *
     * 与 [getNextDownloadTask] 同构（遍历书架 → 排除本地书与暂停书 → 按书取下载章节），
     * 仅排序方向不同（取最新一章）；弹窗初始化用它判断「是否有待下载任务」。
     * 暂停书不算「待下载」：只剩暂停任务时不应发 RESUME（空转一轮 finishDownload），
     * 也不该让「全部开始」看起来有活可干。
     */
    suspend fun findLatestDownloadTask(): DownloadChapterEntity? = withContext(Dispatchers.IO) {
        val paused = pausedBookDao.getAll().toSet()
        for (shelf in bookShelfDao.getAllBooks()) {
            if (shelf.tag != BookShelfEntity.LOCAL_TAG && shelf.noteUrl !in paused) {
                val task = downloadChapterDao.getLastByNoteUrl(shelf.noteUrl)
                if (task != null) return@withContext task
            }
        }
        null
    }

    /** 暂停某书：插入标记行即生效（幂等，主键 REPLACE）；服务在下一轮取篇时跳过该书（见 ADR-0036）。 */
    suspend fun pauseBook(noteUrl: String) = withContext(Dispatchers.IO) {
        pausedBookDao.insert(PausedBookEntity(noteUrl = noteUrl))
    }

    /**
     * 继续某书：删掉暂停标记。是否要拉起服务由调用方决定（页面的「继续」入口
     * 会补发 RESUME Intent——服务已死时经 getForegroundService 拉起续跑）。
     */
    suspend fun resumeBook(noteUrl: String) = withContext(Dispatchers.IO) {
        pausedBookDao.delete(noteUrl)
    }

    /** 全部暂停书的 note_url：一级分组与二级选章装载暂停态各取一次即够（行数 = 暂停书数，量小）。 */
    suspend fun getPausedBooks(): Set<String> = withContext(Dispatchers.IO) {
        pausedBookDao.getAll().toSet()
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

    /** 清空所有下载任务（顺带清空暂停标记：队列空了，标记留着只会让下次排队静默保持暂停） */
    suspend fun clearAllTasks() = withContext(Dispatchers.IO) {
        downloadChapterDao.clearAll()
        pausedBookDao.clearAll()
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
     * 顺带删掉该书的暂停标记：标记的存续以「还有任务在排队」为前提，取消后留着它，
     * 用户下次为同一本书重新排队时会静默保持暂停（任务不跑、没有「已暂停」入口可解）。
     *
     * 注意：若服务此刻正在下载该书的章节，删除不会打断已发出的网络请求（
     * 请求完成后会重新入库——见 DownloadService 保存分支），仅保证后续队列不再拉该书。
     */
    suspend fun deleteTasksForBook(noteUrl: String) = withContext(Dispatchers.IO) {
        downloadChapterDao.deleteByNoteUrl(noteUrl)
        pausedBookDao.delete(noteUrl)
    }

    /**
     * 清理孤儿任务行：书已不在书架（或为本地书）的任务永远取不到篇，删掉。
     *
     * 承接原「队列跑空时 clearAll」的清理职责（见 ADR-0036）：取篇为空后表里可能还有
     * 两类行——暂停书的任务（**必须保留**，暂停语义就是留着待续跑）与孤儿行（清掉），
     * 故不能再无脑 clearAll，改为只清书架外的行。按书删（[deleteTasksForBook]，
     * 顺带清暂停标记）而非一条 DELETE SQL：孤儿书的暂停标记同样是脏数据。
     */
    suspend fun deleteTasksOutsideShelf() = withContext(Dispatchers.IO) {
        // 与取篇同一候选域：非本地书架行。本地书行不参与取篇（见 getNextDownloadTask），
        // 其任务同样视为孤儿——本地书没有下载链路，正常情况下不存在这种行
        val shelfUrls = bookShelfDao.getAllBooks()
            .filter { it.tag != BookShelfEntity.LOCAL_TAG }
            .mapTo(mutableSetOf()) { it.noteUrl }
        downloadChapterDao.getAllTasks()
            .mapTo(mutableSetOf()) { it.noteUrl }
            .filter { it !in shelfUrls }
            .forEach { deleteTasksForBook(it) }
    }

    /**
     * 某书的全书缓存覆盖率（已缓存 / 总章节）。
     *
     * 与"队列剩余"是两个口径：队列是"本次还没下完的"，覆盖率是"全书已可离线的比例"，
     * 后者不随批次变化、随阅读/下载增长，适合当进度条。
     *
     * 缓存判定改查章文件存在性（[BookStore.hasChapter]），不再依赖已移除的 `has_cache` 列。
     * 分母直接取章行列表长度——它与分母同一次查询、同一份快照，不再单独发 COUNT。
     *
     * @param sourceUrl 该书的书源归属（`book_shelf.tag`，等价于下载任务身上的 `tag` 列）。
     *   本方法只按 [com.ebook.common.analyze.local.BookLocation.bookId] 算章文件路径，用不到归属；
     *   仍要求传入是因为 `BookLocation` 的归属字段刻意无默认值——定位值就该是这本书的完整定位，
     *   留空串会给下一个改造者留一个「不知道是不是漏了」的坑。
     */
    suspend fun getCacheCoverage(noteUrl: String, sourceUrl: String): CacheCoverage =
        withContext(Dispatchers.IO) {
            val location = BookLocation(noteUrl, BookFormat.NETWORK, sourceUrl)
            val chapters = chapterListDao.getChaptersForBook(noteUrl)
            val cached = chapters.count { bookStore.hasChapter(location, it.durChapterIndex) }
            CacheCoverage(total = chapters.size, cached = cached)
        }

    /**
     * 某书完整书架信息（含书名/封面/章节目录）：下载中心二级数据源。
     * 不在书架返回 null（阅读器入口会先把书加进书架再进下载中心）。
     */
    suspend fun getBookFullInfo(noteUrl: String): BookShelfFullInfo? =
        withContext(Dispatchers.IO) { bookShelfDao.getBookFullInfoByUrl(noteUrl) }

    /** 某书全部待下载任务（按章序）：供二级标注每章状态。 */
    suspend fun getTasksByBook(noteUrl: String): List<DownloadChapterEntity> =
        withContext(Dispatchers.IO) { downloadChapterDao.getByNoteUrl(noteUrl) }

    /**
     * 某书已缓存章集合（网络书，章文件存在性为事实源）。
     *
     * 与 [getCacheCoverage] 同一判定口径：二级视图只看网络书（下载任务与书架下载入口
     * 都排除本地书），故定位直接用 `NETWORK`，不做本地书分支。
     */
    suspend fun getCachedIndices(
        noteUrl: String,
        tag: String,
        chapters: List<ChapterListEntity>,
    ): Set<Int> = withContext(Dispatchers.IO) {
        val location = BookLocation(noteUrl, BookFormat.NETWORK, tag)
        chapters.filter { bookStore.hasChapter(location, it.durChapterIndex) }
            .mapTo(mutableSetOf()) { it.durChapterIndex }
    }

    /**
     * 批量下发下载任务：**先入库、再拉起前台服务**（顺序即「发起方先入库再拉服务」——
     * 服务启动被拒时任务已落库不丢；[addTasks] 按章 URL 去重，重入幂等）。
     *
     * 启动 Intent 只作**信号**（[DownloadService.buildStartIntent] 空载）：`download_chapter` 表
     * 是唯一队列事实源（ADR-0035），章节列表不再随 Intent 走——整本大额下载不再接近 Binder
     * 事务 1MB 上限（TransactionTooLargeException 风险根除），冷启动/重启/续跑统一由服务读库取篇。
     *
     * 原 `BookReadViewModel.startDownload` 的实现迁移至此，作为全仓唯一下发入口；
     * 启动被拒（dataSync 配额用尽等）时页内提示，不抛未捕获异常。
     */
    suspend fun startDownload(chapters: List<DownloadChapterEntity>) {
        if (chapters.isEmpty()) return
        addTasks(chapters)
        if (!DownloadService.start(context, DownloadService.buildStartIntent(context))) {
            ToastUtil.showShort(context, context.getString(R.string.download_start_restricted))
        }
    }

    suspend fun emitState(state: DownloadState) {
        _downloadState.emit(state)
    }

    /**
     * 非挂起发射下载状态：供服务「马上就要 stopSelf/stopService，来不及赌协程调度」的收尾路径使用，
     * 例如超时回调（[com.ebook.book.service.DownloadService.onTimeout]）、前台服务启动被拒、队列跑空。
     *
     * 通道有 extraBufferCapacity，tryEmit 不经挂起队列，故能在服务销毁前落入 replay 缓冲；
     * 仅当缓冲（64）填满时返回 false，此时状态会被丢弃——收尾场景下不致命（服务反正已停）。
     * 反之，已在某个协程里顺序执行的中间态（进度、暂停）继续用挂起的 [emitState] 即可：
     * 它跟后续的 stopService 在同一个块里，不存在被取消的竞态。
     */
    fun tryEmitState(state: DownloadState): Boolean = _downloadState.tryEmit(state)
}

/**
 * 全书缓存覆盖率快照：某书已缓存章节数与总章节数。
 */
data class CacheCoverage(
    val total: Int,
    val cached: Int,
)

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
