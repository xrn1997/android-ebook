package com.ebook.book.mvvm.viewmodel

import com.ebook.book.R
import com.xrn1997.common.util.Logger
import androidx.lifecycle.viewModelScope
import com.ebook.common.analyze.source.BookSourceManager
import com.ebook.common.repository.BookRepository
import com.ebook.common.repository.BookShelfEvent
import com.ebook.db.entity.BookShelfEntity
import com.ebook.db.entity.SearchBookEntity
import com.xrn1997.common.BaseApplication.Companion.context
import com.xrn1997.common.mvvm.viewmodel.BaseViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
 */
data class BookDetailUiState(
    val bookShelf: BookShelfEntity? = null,
    val inBookShelf: Boolean = false,
    val loading: Boolean = false,
    val loadError: Boolean = false,
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
                }
            }
        }
    }

    /** 书架入口：本地实体数据完整，直接展示，不发网络请求（对齐原实现语义） */
    fun initFromBookShelf(shelf: BookShelfEntity) {
        _detailState.update { it.copy(bookShelf = shelf, inBookShelf = true) }
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

                val inShelf = bookShelfList.any { it.noteUrl == bookShelf.noteUrl }
                if (inShelf) {
                    bookShelfList.find { it.noteUrl == bookShelf.noteUrl }?.let {
                        bookShelf.durChapter = it.durChapter
                        bookShelf.durChapterPage = it.durChapterPage
                    }
                }

                val bookShelfWebChapter = fetchChapterList(bookShelf)
                if (bookShelfWebChapter != null) {
                    _detailState.update {
                        it.copy(bookShelf = bookShelfWebChapter, inBookShelf = inShelf, loading = false)
                    }
                } else {
                    _detailState.update { it.copy(loading = false, loadError = true) }
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
                    sendToast(context.getString(R.string.remove_shelf_failed))
                }
            }
        }
    }

    /**
     * 从 SearchBookEntity 构造 BookShelfEntity（原 BookDetailModel.fetchBookInfo 逻辑）
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
            bookSourceManager.requireParser().getBookInfo(shelf)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 获取章节列表（原 BookDetailModel.fetchChapterList 逻辑）
     */
    private suspend fun fetchChapterList(bookShelf: BookShelfEntity): BookShelfEntity? {
        return try {
            // getChapterList 返回非空包装对象（data 才可能为空），不需要安全调用
            bookSourceManager.requireParser().getChapterList(bookShelf).data
        } catch (e: CancellationException) {
            // 取消不是"取不到章节"：吞掉会让调用方把销毁中的页面渲染成空目录
            throw e
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        const val TAG: String = "BookDetailViewModel"
    }
}
