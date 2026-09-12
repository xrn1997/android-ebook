package com.ebook.book.mvvm.viewmodel

import androidx.lifecycle.viewModelScope
import com.ebook.common.analyze.local.ChapterContent
import com.ebook.common.repository.BookRepository
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
