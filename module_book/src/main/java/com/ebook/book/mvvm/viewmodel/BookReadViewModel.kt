package com.ebook.book.mvvm.viewmodel

import androidx.lifecycle.viewModelScope
import com.ebook.common.analyze.local.ChapterContent
import com.ebook.common.repository.BookRepository
import com.ebook.common.repository.ChapterSyncResult
import com.ebook.db.entity.BookShelfEntity
import com.ebook.db.entity.ChapterListEntity
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import com.xrn1997.common.mvvm.viewmodel.BaseViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class BookReadViewModel @Inject constructor(
    private val bookRepository: BookRepository
) : BaseViewModel<BookRepository>(bookRepository) {
    var isAdd = false
    var bookShelf: BookShelfEntity? = null

    var pageLineCount = 5
    val nextInShelfEvent = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    fun updateProgress(chapterIndex: Int, pageIndex: Int) {
        bookShelf?.let {
            it.durChapter = chapterIndex
            it.durChapterPage = pageIndex
        }
    }

    fun saveProgress() {
        bookShelf?.let {
            viewModelScope.launch {
                bookRepository.saveProgress(it)
            }
        }
    }

    fun getChapterTitle(chapterIndex: Int): String {
        val chapters = bookShelf?.chapterList
        return if (chapters.isNullOrEmpty()) {
            "无章节"
        } else chapters.getOrNull(chapterIndex)?.durChapterName ?: "无章节"
    }

    fun checkInShelf() {
        val noteUrl = bookShelf?.noteUrl ?: return
        viewModelScope.launch {
            isAdd = bookRepository.getBookByUrl(noteUrl) != null
            nextInShelfEvent.tryEmit(Unit)
        }
    }

    fun addToShelf(addListener: OnAddListener?) {
        bookShelf?.let {
            viewModelScope.launch {
                bookRepository.addToShelf(it)
                isAdd = true
                addListener?.addSuccess()
            }
        }
    }

    /** 统一章节正文读取（本地书与网络书同路径） */
    suspend fun loadChapter(chapter: ChapterListEntity): ChapterContent? =
        bookShelf?.let {
            bookRepository.loadChapter(
                it, chapter.durChapterIndex, chapter.durChapterName, chapter.contentRef,
            )
        }

    /**
     * 刷新当前章节缓存（仅网络书）：删除章文件 + 失效内存缓存。
     * 返回当前章节索引和页码，供 UI 重新加载页面。
     */
    suspend fun refreshCurrentChapter(): Pair<Int, Int>? {
        val shelf = bookShelf ?: return null
        val chapterIndex = shelf.durChapter
        val pageIndex = shelf.durChapterPage
        bookRepository.refreshChapter(shelf, chapterIndex)
        return chapterIndex to pageIndex
    }

    /**
     * 读到末章时静默查一次目录更新。
     *
     * 追加成功时**就地更新** `bookShelf.chapterList`：阅读器的一切都现取这个字段
     * （正文走 `loadChapter`、目录走 [getChapter] / [getChapterListSize]），故替换掉它
     * 持有的那个 List 即可生效，**不需要整体替换实体** —— 换源那条路径要替换是因为
     * 换了另一本书，这里还是同一本、只是目录变长。
     *
     * 返回值只区分「有新章」（交回新章，宿主据此重分页并跟上滑条与标题）与
     * 「页面不用动」（其余全部结局，包括失败 —— 那是静默路径，见目录重抓的处置口径）。
     *
     * **单飞**：宿主的进度回调在末章每一页翻动时都会调本方法，限频只能保证
     * 「窗口内一次网络」，挡不住同一批页快速来回翻时并发起来。本方法只在主线程
     * （Compose 回调）被调，故普通 [Boolean] 足够，不需要原子量。
     */
    suspend fun appendChaptersIfAny(): List<ChapterListEntity>? {
        val shelf = bookShelf ?: return null
        if (syncInFlight) return null
        syncInFlight = true
        try {
            val result = bookRepository.syncChaptersFromSource(shelf)
            val appended = (result as? ChapterSyncResult.Appended)?.appended ?: return null
            bookShelf = shelf.copy(chapterList = shelf.chapterList + appended)
            return appended
        } finally {
            syncInFlight = false
        }
    }

    /** 目录检查是否正在进行（单飞标志，见 [appendChaptersIfAny]） */
    private var syncInFlight = false

    /**
     * 获取章节列表大小
     */
    fun getChapterListSize(): Int {
        return bookShelf?.chapterList?.size ?: 0
    }

    /**
     * 获取指定索引的章节
     */
    fun getChapter(index: Int): ChapterListEntity? {
        return bookShelf?.chapterList?.getOrNull(index)
    }

    interface OnAddListener {
        fun addSuccess()
    }

    companion object {
        const val OPEN_FROM_OTHER: Int = 0
        const val OPEN_FROM_APP: Int = 1
        const val TAG: String = "BookReadViewModel"
    }
}
