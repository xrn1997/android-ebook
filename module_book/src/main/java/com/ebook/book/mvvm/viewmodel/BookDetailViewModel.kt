package com.ebook.book.mvvm.viewmodel

import com.ebook.book.R
import com.xrn1997.common.util.Logger
import androidx.lifecycle.viewModelScope
import com.ebook.common.analyze.source.BookSourceManager
import com.ebook.common.repository.BookRepository
import com.ebook.common.repository.BookShelfEvent
import com.ebook.common.repository.ChapterSyncResult
import com.ebook.common.util.reportFailure
import com.ebook.db.entity.BookShelfEntity
import com.ebook.db.entity.SearchBookEntity
import com.ebook.source.analyze.BookSourceNotFoundException
import com.xrn1997.common.BaseApplication.Companion.context
import com.xrn1997.common.mvvm.viewmodel.BaseViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Collections
import javax.inject.Inject

/**
 * 详情页 UI 状态（可观察，驱动 Compose 重组）。
 *
 * 历史背景：原实现中 mBookShelf/inBookShelf 为普通字段，经 updateViewEvent/
 * bookShelfErrorEvent 通知 Activity 手动刷新；Compose 化后两个事件流无订阅者，
 * 普通字段又不会触发重组，导致网络拉取完成后页面永不刷新（书架状态、章节数据、
 * 失败态全部静默丢失）——故收敛为单一 StateFlow。
 *
 * @property bookShelf 详情实体（含章节列表）；书架入口直接本地填充，搜索入口经网络拉取后回填
 * @property inBookShelf 当前书是否已在书架（书架事件实时修正）
 * @property loading 详情网络拉取中（仅搜索入口）
 * @property loadError 详情网络拉取失败（可点击重试）
 * @property tocDiverged 目录重抓判定为分叉（本地不是远端的前缀），本地目录未作改动，需用户处置
 */
data class BookDetailUiState(
    val bookShelf: BookShelfEntity? = null,
    val inBookShelf: Boolean = false,
    val loading: Boolean = false,
    val loadError: Boolean = false,
    /**
     * 只在本次会话内成立、不持久化：限频挡掉了窗口内的重复检查，所以用户下一次进来
     * 看不到这条提示是可接受的（第一次已经看到了），且到期后会重新检查并重新提示 ——
     * 分叉是持续性故障，重复提示是对的。要做成常驻标记需要落库，属阶段 2。
     */
    val tocDiverged: Boolean = false,
)

@HiltViewModel
class BookDetailViewModel @Inject constructor(
    private val bookRepository: BookRepository,
    private val bookSourceManager: BookSourceManager
) : BaseViewModel<BookRepository>(bookRepository) {
    val bookShelfList: MutableList<BookShelfEntity> =
        Collections.synchronizedList(ArrayList())
    var searchBook: SearchBookEntity? = null

    private val _detailState = MutableStateFlow(BookDetailUiState())

    /**
     * 详情页可观察状态（Compose 侧经 collectAsState 订阅）。
     * 命名避开基类 [com.xrn1997.common.mvvm.viewmodel.BaseViewModel.uiState]（覆盖层专用）
     */
    val detailState = _detailState.asStateFlow()

    /** 当前详情实体快照：供 Activity 跳转阅读器/增删书架使用 */
    val mBookShelf: BookShelfEntity? get() = _detailState.value.bookShelf

    /** 当前书是否已在书架 */
    val inBookShelf: Boolean get() = _detailState.value.inBookShelf

    init {
        // 书架事件同步：收进 VM（viewModelScope），旋转重建不重复收集、不重复累积。
        // 原 Activity onCreate 的 lifecycleScope 收集在旋转时会对同一个 VM 的 bookShelfList
        // 重复 add，导致列表元素重复——移入 VM init 后每 VM 只收集一次，天然幂等。
        viewModelScope.launch {
            bookRepository.bookShelfEvents.collect { event ->
                when (event) {
                    is BookShelfEvent.Added -> {
                        synchronized(bookShelfList) { bookShelfList.add(event.bookShelf) }
                        if (mBookShelf?.noteUrl == event.bookShelf.noteUrl ||
                            searchBook?.noteUrl == event.bookShelf.noteUrl
                        ) {
                            _detailState.update { it.copy(inBookShelf = true) }
                            searchBook?.let { it.add = true }
                        }
                    }
                    // 书架中移除书籍时关闭详情页（保持原 Activity 侧行为：任一移除事件即关闭）
                    is BookShelfEvent.Removed -> sendFinish()
                    // 阅读进度与详情页无关
                    is BookShelfEvent.ProgressUpdated -> Unit
                    // 详情页自己就是目录重抓的发起方，状态已就地更新过；再收一遍事件会把
                    // 刚展示的条目整个换掉，反而盖掉用户此刻的浏览位置
                    is BookShelfEvent.ChaptersUpdated -> Unit
                }
            }
        }
    }

    /**
     * 书架入口：先用本地实体立即渲染（页面不空白），随后静默重抓一次目录。
     *
     * 「先渲染后检查」是刻意的次序：静默检查可能耗时数秒（目录要翻好几页），
     * 摆在渲染之前会让一本完全能读的书白转圈。
     *
     * 与搜索入口 [getBookShelfInfo] 的差别：那条是用户主动找书、必须拿网络结果，
     * 失败要进错误态；本条失败时必须什么都不说 —— 本地目录完好，
     * 失败只意味着「这次没查到有没有新章」，置 loadError 会让一本正常显示的书凭空变成加载失败。
     */
    fun initFromBookShelf(shelf: BookShelfEntity) {
        _detailState.update { it.copy(bookShelf = shelf, inBookShelf = true) }
        syncChaptersQuietly(shelf)
    }

    /**
     * 静默目录重抓：只有追加成功时报一句条数、只有判定分叉时留一条常驻提示，
     * 其余结局（`Throttled` / `UpToDate` / `NotNetworkBook` / `Failed`）都不出声。
     */
    private fun syncChaptersQuietly(shelf: BookShelfEntity) {
        viewModelScope.launch {
            when (val result = bookRepository.syncChaptersFromSource(shelf)) {
                is ChapterSyncResult.Appended -> {
                    // 必须 copy 出新实体经 update 提交：BookShelfEntity 装在 StateFlow 里，
                    // 就地改它的 chapterList 不改变对象引用，StateFlow 判等后不会重发，
                    // 页面目录就停在旧长度。
                    // 目录取库里那份，不用「旧 + appended」拼：拼出来的基数是页面自己那份目录，
                    // 不保证等于库内行数，而阅读器据它写回的 dur_chapter 是列表位置（见 getStoredChapters）
                    val stored = bookRepository.getStoredChapters(shelf.noteUrl)
                    _detailState.update { state ->
                        val current = state.bookShelf ?: return@update state
                        state.copy(bookShelf = current.copy(chapterList = stored))
                    }
                    sendToast(context.getString(R.string.chapters_appended, result.appended.size))
                }

                is ChapterSyncResult.Diverged ->
                    _detailState.update { it.copy(tocDiverged = true) }

                is ChapterSyncResult.Failed -> Logger.e(
                    TAG,
                    "目录静默重抓失败，本地目录未受影响因而不打扰用户：${shelf.noteUrl}",
                    result.cause,
                )

                ChapterSyncResult.UpToDate,
                ChapterSyncResult.Throttled,
                ChapterSyncResult.NotNetworkBook -> Unit
            }
        }
    }

    /** 搜索入口：先用传入实体展示基本信息，书架状态取列表页标记，详情待网络拉取 */
    fun initFromSearch(searchBook: SearchBookEntity) {
        this.searchBook = searchBook
        _detailState.update { it.copy(inBookShelf = searchBook.add, loading = true) }
    }

    /**
     * 拉取书籍详情与章节列表（仅搜索入口调用）。
     *
     * 失败语义：只置 [BookDetailUiState.loadError]，不清空已有 [BookDetailUiState.bookShelf]——
     * 原实现的 catch 会把已填充的实体置 null，导致书架入口的书拉取失败后「开始阅读」断链（回归缺陷）。
     */
    fun getBookShelfInfo() {
        viewModelScope.launch {
            _detailState.update { it.copy(loading = true, loadError = false) }
            try {
                val bookShelves = bookRepository.getAllBooks()
                synchronized(bookShelfList) {
                    bookShelfList.clear()
                    bookShelfList.addAll(bookShelves)
                }

                val searchBook = searchBook
                if (searchBook == null) {
                    _detailState.update { it.copy(loading = false, loadError = true) }
                    return@launch
                }
                val bookShelf = fetchBookInfo(searchBook) ?: run {
                    _detailState.update { it.copy(loading = false, loadError = true) }
                    return@launch
                }

                val shelfRow = bookShelfList.find { it.noteUrl == bookShelf.noteUrl }
                shelfRow?.let {
                    // 进度取书架那条记录的：用户读到哪儿是库内的事实
                    bookShelf.durChapter = it.durChapter
                    bookShelf.durChapterPage = it.durChapterPage
                }

                val bookShelfWebChapter = fetchChapterList(bookShelf)
                if (bookShelfWebChapter == null) {
                    _detailState.update { it.copy(loading = false, loadError = true) }
                    return@launch
                }
                val displayShelf = if (shelfRow != null) {
                    // **已在书架：落库先认归属，页面与阅读器一律改用库里那份。**
                    // 直接展示源上那份的话，页面上点得到、阅读器里翻得到的那几章在 chapter_list
                    // 里并不存在，而阅读器退出时按它写回的 dur_chapter 是**列表位置** ——
                    // 书架再按 getOrNull(durChapter) 读就落空（读至空白 + 点进去提示加载失败）。
                    // 判定与序号重排都在仓库那半截里，这里不为落库再翻一遍目录页。
                    if (shelfRow.tag == bookShelf.tag) {
                        when (val result = bookRepository.appendRemoteChapters(
                            bookShelf,
                            bookShelfWebChapter.chapterList,
                        )) {
                            is ChapterSyncResult.Appended ->
                                sendToast(context.getString(R.string.chapters_appended, result.appended.size))

                            // 分叉：库里那份追不上远端，更不能把库里没有的章交给阅读器 ——
                            // 落到下面的回读，页面显示的就是实际可读的那些章，另给一条常驻提示
                            is ChapterSyncResult.Diverged ->
                                _detailState.update { it.copy(tocDiverged = true) }

                            is ChapterSyncResult.Failed -> Logger.e(
                                TAG,
                                "搜索入口的目录落库未完成，页面改用库里那份：${bookShelf.noteUrl}",
                                result.cause,
                            )

                            ChapterSyncResult.UpToDate,
                            ChapterSyncResult.Throttled,
                            ChapterSyncResult.NotNetworkBook -> Unit
                        }
                    }
                    // 归属不是同一条源时上面整段跳过。别小看这一挡：diff 按 content_ref 定位，
                    // 确实一行都不会写，但落库那半截会把「这次检查得出分叉结论」写进
                    // `final_refresh_data` 并弹一条本地目录已失效的提示条 ——
                    // 那是**另一条源**的目录与这本书的本地目录在比，不是这本书的事实。
                    bookShelfWebChapter.copy(
                        chapterList = bookRepository.getStoredChapters(bookShelf.noteUrl),
                    )
                } else {
                    // 未加书架：库里没有这本书的任何目录行，源上这份就是唯一可渲染、可阅读的一份
                    // （它的进度也就无从持久化 —— saveProgress 写出的书架行没有 book_info 配对，
                    // 会被 getAllBooksWithDetails 当孤立记录清掉，这是加书架前阅读的一贯行为）
                    bookShelfWebChapter
                }
                _detailState.update {
                    it.copy(bookShelf = displayShelf, inBookShelf = shelfRow != null, loading = false)
                }
            } catch (e: Exception) {
                Logger.e(TAG, "subscribe onError: ", e)
                _detailState.update { it.copy(loading = false, loadError = true) }
            }
        }
    }

    fun addToBookShelf() {
        mBookShelf?.let { shelf ->
            viewModelScope.launch {
                try {
                    bookRepository.addToShelf(shelf)
                    Logger.d(TAG, "addToBookShelf: ${shelf.noteUrl}")
                } catch (e: Exception) {
                    Logger.e(TAG, "addToBookShelf failed: ${shelf.noteUrl}", e)
                    // 一次性命令通道（sendToast）而非直调 Toast：MvvmBinder 在主线程消费，
                    // 文案走字符串资源（对齐 lib_common ViewModelNoDirectToastTest 门禁约定）
                    sendToast(context.getString(R.string.import_add_failed))
                }
            }
        }
    }

    fun removeFromBookShelf() {
        mBookShelf?.let { shelf ->
            viewModelScope.launch {
                try {
                    bookRepository.removeFromShelf(shelf)
                } catch (e: Exception) {
                    Logger.e(TAG, "removeFromBookShelf failed: ${shelf.noteUrl}", e)
                    sendToast(context.getString(R.string.remove_shelf_failed))
                }
            }
        }
    }

    /**
     * 从 SearchBookEntity 构造 BookShelfEntity 并拉详情（原 BookDetailModel.fetchBookInfo 逻辑）。
     *
     * 解析器按 `searchBook.tag`（书源归属）取，不按「当前默认源」取（见 ADR-0016）：详情页展示的这本
     * 书来自哪个站，就必须用那个站的规则解它的 `noteUrl`。归属查不到时抛
     * [BookSourceNotFoundException]，这里经 [reportFailure] 提示后返回 null——
     * 调用方据此收掉 loading 并进 [BookDetailUiState.loadError]，绝不让覆盖层永远转下去。
     */
    private suspend fun fetchBookInfo(searchBook: SearchBookEntity): BookShelfEntity? {
        return try {
            val shelf = BookShelfEntity(
                noteUrl = searchBook.noteUrl,
                finalDate = System.currentTimeMillis(),
                durChapter = 0,
                durChapterPage = 0,
                tag = searchBook.tag
            )
            val parser = bookSourceManager.getParserFor(searchBook.tag)
                ?: throw BookSourceNotFoundException(searchBook.tag)
            parser.getBookInfo(shelf)
        } catch (e: CancellationException) {
            // 取消不是「拉不到详情」：吞掉会让销毁中的页面渲染成错误态
            throw e
        } catch (e: BookSourceNotFoundException) {
            reportFailure(e, context.getString(R.string.book_source_invalid))
            Logger.e(TAG, "fetchBookInfo 书源已失效: ${searchBook.tag} / ${searchBook.noteUrl}", e)
            null
        } catch (e: Exception) {
            Logger.e(TAG, "fetchBookInfo failed: ${searchBook.noteUrl}", e)
            null
        }
    }

    /**
     * 获取章节列表（原 BookDetailModel.fetchChapterList 逻辑）
     *
     * 归属同样按 `bookShelf.tag` 取（详情页的刷新目录/书架入口都走这里）。取不到时与
     * [fetchBookInfo] 同一处置：提示 + 返回 null 让调用方置错误态——「静默返回成功」会把
     * 一本没有目录的书当成加载完成摆在页面上。
     */
    private suspend fun fetchChapterList(bookShelf: BookShelfEntity): BookShelfEntity? {
        return try {
            val parser = bookSourceManager.getParserFor(bookShelf.tag)
                ?: throw BookSourceNotFoundException(bookShelf.tag)
            // getChapterList 返回非空包装对象（data 才可能为空），不需要安全调用
            parser.getChapterList(bookShelf).data
        } catch (e: CancellationException) {
            // 取消不是"取不到章节"：吞掉会让调用方把销毁中的页面渲染成空目录
            throw e
        } catch (e: BookSourceNotFoundException) {
            reportFailure(e, context.getString(R.string.book_source_invalid))
            Logger.e(TAG, "fetchChapterList 书源已失效: ${bookShelf.tag} / ${bookShelf.noteUrl}", e)
            null
        } catch (e: Exception) {
            Logger.e(TAG, "fetchChapterList failed: ${bookShelf.noteUrl}", e)
            null
        }
    }
}
