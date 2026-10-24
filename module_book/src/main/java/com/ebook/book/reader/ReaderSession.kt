package com.ebook.book.reader

import com.ebook.common.analyze.local.ChapterContent
import com.ebook.common.repository.ChapterSyncResult
import com.ebook.db.entity.BookShelfEntity
import com.ebook.db.entity.ChapterListEntity
import com.ebook.db.event.DBCode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 阅读器状态唯一事实源。
 *
 * [shelf] 只是打开时的快照；[chapterIndex]/[pageIndex] 才是实时位置。
 * `shelf.durChapter`/`durChapterPage` 是持久化字段，不参与实时定位，只在 [ReaderSession.saveProgress]
 * 落库时合并：`BookShelfEntity.copy()` 是浅拷贝、不复制目录，但每翻一页都合并仍要多分配一份
 * 实体与状态，没有必要。
 */
internal data class ReaderSessionState(
    val shelf: BookShelfEntity? = null,
    val chapterIndex: Int = 0,
    val pageIndex: Int = DBCode.BookContentView.DUR_PAGE_INDEX_BEGIN,
    val inShelf: Boolean = false,
    /** 书架归属查询是否已落定；宿主据此放行首屏，不再另设事件。 */
    val shelfChecked: Boolean = false,
) {
    val chapterCount: Int get() = shelf?.chapterList?.size ?: 0
    val chapterTitle: String? get() = shelf?.chapterList?.getOrNull(chapterIndex)?.durChapterName

    /** 滑条位置以章数封顶：越界的实时章不应把滑条拖出轨道。 */
    val sliderValue: Float
        get() = (chapterIndex + 1).coerceIn(1, chapterCount.coerceAtLeast(1)).toFloat()
}

/** 一章的正文及其定位信息；宿主用 [contentRef] 做分页缓存键。 */
internal data class ReaderChapterText(
    val title: String,
    val contentRef: String,
    val text: String,
)

/**
 * [ReaderSession.appendIfAtTail] 的结局。
 *
 * [NotAtTail] 是常态（读者还在中间章）；[Skipped] 收拢五种对用户不可见的静默结局
 * （追更已在途、本地行缺失、`tag` 归属不符、同步没有追加、回读目录期间会话已换书）；
 * 只有 [Appended] 需要宿主开口。
 */
internal sealed class ReaderAppendResult {
    /** 不在末章，未发任何网络请求 */
    data object NotAtTail : ReaderAppendResult()

    /** 静默跳过，不提示用户 */
    data object Skipped : ReaderAppendResult()

    /** 追加成功；[count] 是本次新增的章数 */
    data class Appended(val count: Int) : ReaderAppendResult()
}

/**
 * [ReaderSession] 的数据出口，由仓库适配器实现；测试用假件替换。
 *
 * 数据动作（正文读取、追更同步、落库与书架查询）都是 suspend：要进 IO，会话自身不持线程。
 * [markReading] 例外地是同步的：它只写一条本地标记（SharedPreferences），
 * 与这些 IO 动作没有先后依赖，也没有让会话为它变成 suspend 的理由。
 */
internal interface ReaderSessionGateway {
    suspend fun getBookWithDetails(noteUrl: String): BookShelfEntity?
    suspend fun getBookByUrl(noteUrl: String): BookShelfEntity?
    suspend fun saveProgress(shelf: BookShelfEntity)
    suspend fun loadChapter(shelf: BookShelfEntity, chapter: ChapterListEntity): ChapterContent?
    suspend fun refreshChapter(shelf: BookShelfEntity, index: Int)
    suspend fun syncChapters(shelf: BookShelfEntity): ChapterSyncResult
    suspend fun getStoredChapters(noteUrl: String): List<ChapterListEntity>
    suspend fun addToShelf(shelf: BookShelfEntity)

    /**
     * 记下「阅读会话进行中」的书（见 `ReaderResumeStore`）。
     *
     * 归会话而不是页面：它与「收下了哪本书」是同一个事实。[ReaderSession.adopt] 与
     * `adoptStored`（[ReaderSession.openFromStore] / [ReaderSession.adoptAfterSourceSwitch] 共用）
     * 各写一次；页面漏写一处就会在异常关闭后恢复到上一本书。
     */
    fun markReading(noteUrl: String)
}

/**
 * 阅读位置的单一所有者：持有书架快照、当前 `(章, 页)`，并派生标题/章数/滑条值。
 *
 * 宿主只经 [state] 读，位置经 [adopt]/[openFromStore]/[adoptAfterSourceSwitch]/[updateProgress] 写，
 * 不再各自维护位置副本；进度仍由宿主在 `onPause` 调 [saveProgress] 落库。
 */
internal class ReaderSession(private val gateway: ReaderSessionGateway) {

    private val mutableState = MutableStateFlow(ReaderSessionState())
    val state: StateFlow<ReaderSessionState> = mutableState.asStateFlow()

    /**
     * 追更单飞标记，把并发的追更折叠成一次网络请求。
     *
     * 会话不持线程，宿主在主线程序列化调用，故用普通 Boolean 而非原子量；只在
     * [appendIfAtTail] 的挂起窗口内置 true。
     */
    private var syncInFlight = false

    /**
     * 收下书架快照并按其中记录的章页起读；归属未知，待宿主再查。
     *
     * 存的是独立副本，调用方之后改 [BookShelfEntity] 的字段不会无声改动会话状态；
     * 副本是浅拷贝，[BookShelfEntity.chapterList] 仍与原实体共享同一列表实例。
     */
    fun adopt(shelf: BookShelfEntity) {
        mutableState.value = ReaderSessionState(
            shelf = shelf.copy(),
            chapterIndex = shelf.durChapter,
            pageIndex = shelf.durChapterPage,
        )
        gateway.markReading(shelf.noteUrl)
    }

    /** 从存储重读一行并落定在架状态；取不到时返回 false 且不改动任何状态。 */
    suspend fun openFromStore(noteUrl: String): Boolean {
        val stored = gateway.getBookWithDetails(noteUrl) ?: return false
        adoptStored(stored)
        return true
    }

    /**
     * 换源后整行收下新源的书架条目，并把位置复位到新条目记录的章页。
     *
     * 新旧目录的章序不可比（换源可能整体重排），沿用旧 `(章, 页)` 会指到别的章；换源写的就是
     * 书架行本身，故同时落定 [ReaderSessionState.inShelf] 与 [ReaderSessionState.shelfChecked]。
     */
    fun adoptAfterSourceSwitch(shelf: BookShelfEntity) {
        adoptStored(shelf)
    }

    /** 只改实时位置；不复制 [BookShelfEntity]，持久化留给 [saveProgress]。 */
    fun updateProgress(chapterIndex: Int, pageIndex: Int) {
        mutableState.update { it.copy(chapterIndex = chapterIndex, pageIndex = pageIndex) }
    }

    /** 把当前位置合并进书架副本后落库；章页钳制仍由仓库负责。 */
    suspend fun saveProgress() {
        val shelf = withLivePosition(mutableState.value) ?: return
        gateway.saveProgress(shelf)
    }

    /**
     * 查询书架归属，命中与否都落定 [ReaderSessionState.shelfChecked]。
     *
     * 结论只对发起查询时的那本书成立：挂起期间会话若已换书（[adopt] / [openFromStore] /
     * [adoptAfterSourceSwitch]），标记不落定，新书的归属由它自己的查询决定。追更回读目录只换
     * `chapterList`、仍是同一本书，不在此列（见 [isSameBook]）。
     */
    suspend fun checkInShelf() {
        val current = mutableState.value
        val shelf = current.shelf
        if (shelf == null) {
            mutableState.update { it.copy(inShelf = false, shelfChecked = true) }
            return
        }
        val inShelf = gateway.getBookByUrl(shelf.noteUrl) != null
        if (isSameBook(mutableState.value.shelf, current.shelf)) {
            mutableState.update { it.copy(inShelf = inShelf, shelfChecked = true) }
        }
    }

    /**
     * 把实时位置合并进书架副本后写入，并置 [ReaderSessionState.inShelf] 与
     * [ReaderSessionState.shelfChecked]——写成功即证明在架。
     *
     * 写入的是当前位置而非书架里记录的旧位置，避免「读了几章再点加书架」把进度抹回开头。
     * 写入时间戳取当前时刻：加架是「此刻在读」的动作，快照里的 `finalDate` 停在打开那一刻，
     * 照抄会把书架排序时间写旧。
     * 返回 false 有两种情形：未 adopt，以及挂起期间会话已换书——写入仍按捕获的那本书完成，
     * 但标记不再记到新书上，当前会话也不再是那本书，故不宣称这次操作属于当前书。
     * 追更回读目录会换掉 shelf 实例但仍是同一本书（[isSameBook] 按归属比较），不算换书。
     */
    suspend fun addToShelf(): Boolean {
        val current = mutableState.value
        val shelf = withLivePosition(current)?.copy(finalDate = System.currentTimeMillis())
            ?: return false
        gateway.addToShelf(shelf)
        if (!isSameBook(mutableState.value.shelf, current.shelf)) return false
        mutableState.update { it.copy(inShelf = true, shelfChecked = true) }
        return true
    }

    /** 书架目录里索引处的章；越界或无书架为 null。 */
    fun chapterAt(index: Int): ChapterListEntity? =
        mutableState.value.shelf?.chapterList?.getOrNull(index)

    /**
     * 按索引读一章正文，标题与 [ChapterListEntity.contentRef] 随正文一并返回。
     *
     * 三种情况返回 null：章索引越界、[ReaderSessionGateway.loadChapter] 取不到正文、正文为空。
     * 读取异常（如书源解析失败）原样上抛，由宿主按既有口径报错。
     */
    suspend fun loadChapter(index: Int): ReaderChapterText? {
        val shelf = mutableState.value.shelf ?: return null
        val chapter = shelf.chapterList.getOrNull(index) ?: return null
        val content = gateway.loadChapter(shelf, chapter) ?: return null
        val text = content.displayText
        if (text.isEmpty()) return null
        return ReaderChapterText(chapter.durChapterName, chapter.contentRef, text)
    }

    /** 重读当前章正文；返回调用前的 `(章, 页)`，未 adopt 时返回 null。 */
    suspend fun refreshCurrentChapter(): Pair<Int, Int>? {
        val current = mutableState.value
        val shelf = current.shelf ?: return null
        gateway.refreshChapter(shelf, current.chapterIndex)
        return current.chapterIndex to current.pageIndex
    }

    /**
     * 只在读者停在末章时尝试追更。
     *
     * [ReaderAppendResult.NotAtTail] 表示位置不在末章，未发任何网络请求；会话尚未收下书架时
     * 也走这一档——没有目录就无从判断末章；
     * [ReaderAppendResult.Skipped] 收拢五种静默结局：追更已在途（单飞）、本地行缺失、
     * `tag` 归属不符、同步没有追加、回读目录期间会话已换书（目录替换作废，不能把旧书目录装进新书）。
     * 成功时回读 [ReaderSessionGateway.getStoredChapters] 整体替换书架目录——不能用「旧目录 + appended」
     * 拼，`appended` 只是新增的那几行。
     *
     * 追更不移动阅读位置：目录变了，当前 `(章, 页)` 原样保留。
     * 仓库把网络/解析失败折进 [ChapterSyncResult]（非 Appended → [ReaderAppendResult.Skipped]）；
     * 能逃出本方法的只有 DB/IO 失败与取消，二者原样上抛给宿主——[ReaderAppendResult] 只覆盖
     * 不抛异常的结局。
     */
    suspend fun appendIfAtTail(): ReaderAppendResult {
        val snapshot = mutableState.value
        val shelf = snapshot.shelf ?: return ReaderAppendResult.NotAtTail
        if (snapshot.chapterIndex != shelf.chapterList.lastIndex) return ReaderAppendResult.NotAtTail
        if (syncInFlight) return ReaderAppendResult.Skipped
        syncInFlight = true
        return try {
            val stored = gateway.getBookByUrl(shelf.noteUrl) ?: return ReaderAppendResult.Skipped
            if (stored.tag != shelf.tag) return ReaderAppendResult.Skipped
            val appended = (gateway.syncChapters(shelf) as? ChapterSyncResult.Appended)
                ?.appended ?: return ReaderAppendResult.Skipped
            val fresh = gateway.getStoredChapters(shelf.noteUrl)
            // 挂起期间若已换书，目录替换作废：不能把旧书目录装进新书，也不能谎报当前书已追加。
            if (!isSameBook(mutableState.value.shelf, snapshot.shelf)) return ReaderAppendResult.Skipped
            mutableState.update { it.copy(shelf = shelf.copy(chapterList = fresh)) }
            ReaderAppendResult.Appended(appended.size)
        } finally {
            syncInFlight = false
        }
    }

    /**
     * 会话是否仍停在 [expected] 这本书上。
     *
     * 按归属（`noteUrl` + `tag`）比较而不是实例身份：追更回读目录会用 `copy(chapterList = …)`
     * 换掉 shelf 实例、仍是同一本书，按身份比较会把它误判成「已换书」，于是加架不置在架、
     * 在架结论被丢弃（下载入口点了没反应）。
     */
    private fun isSameBook(current: BookShelfEntity?, expected: BookShelfEntity?): Boolean =
        current != null && expected != null &&
            current.noteUrl == expected.noteUrl && current.tag == expected.tag

    /** 把实时 `(章, 页)` 合并进书架副本；未 adopt 时为 null。 */
    private fun withLivePosition(current: ReaderSessionState): BookShelfEntity? =
        current.shelf?.copy(durChapter = current.chapterIndex, durChapterPage = current.pageIndex)

    /**
     * 收下一行已持久化的书架条目：存副本，按行内 `(章, 页)` 起读，并落定两个归属标记。
     *
     * [openFromStore] 与 [adoptAfterSourceSwitch] 共用：两者的来源都是已经写进书架的整行，
     * 差别只在取行方式。
     */
    private fun adoptStored(shelf: BookShelfEntity) {
        mutableState.value = ReaderSessionState(
            shelf = shelf.copy(),
            chapterIndex = shelf.durChapter,
            pageIndex = shelf.durChapterPage,
            inShelf = true,
            shelfChecked = true,
        )
        // 换源与冷启动恢复都走这里：换书即重记标记，页面不必各自补写（见 ReaderSessionGateway.markReading）
        gateway.markReading(shelf.noteUrl)
    }
}
