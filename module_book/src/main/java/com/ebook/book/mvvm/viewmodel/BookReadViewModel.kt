package com.ebook.book.mvvm.viewmodel

import android.content.Context
import androidx.lifecycle.viewModelScope
import com.ebook.book.R
import com.ebook.book.repository.DownloadRepository
import com.ebook.book.service.DownloadService
import com.ebook.common.analyze.source.BookSourceManager
import com.ebook.common.repository.BookRepository
import com.ebook.db.entity.BookContentEntity
import com.ebook.db.entity.BookShelfEntity
import com.ebook.db.entity.ChapterListEntity
import com.ebook.db.entity.DownloadChapterEntity
import com.xrn1997.common.util.ToastUtil
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.xrn1997.common.mvvm.viewmodel.BaseViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class BookReadViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val bookRepository: BookRepository,
    private val bookSourceManager: BookSourceManager,
    private val downloadRepository: DownloadRepository
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

    /**
     * 发起一批章节下载：先入库，再拉起前台服务。
     *
     * 顺序很关键：前台服务启动在 targetSdk 35+ 可能被系统直接拒绝（dataSync 类型 24 小时内共
     * 6 小时的配额用尽，或应用已处于后台，见 [DownloadService.start]），而任务原先只躲在 Intent
     * extra 里，一旦启动被拒这批选择就彻底丢了。先入库后，服务任何一次拉起（页面重试、
     * 下载管理页、START_STICKY 重启）都能按库中未完成任务续跑；[DownloadRepository.addTasks] 按章节
     * URL 去重，服务收到同批 Intent 再入一次也是幂等的（见其构造分支）。
     */
    fun startDownload(chapters: List<DownloadChapterEntity>) {
        if (chapters.isEmpty()) return
        viewModelScope.launch {
            downloadRepository.addTasks(chapters)
            if (!DownloadService.start(context, DownloadService.buildStartIntent(context, chapters))) {
                ToastUtil.showShort(context, context.getString(R.string.download_start_restricted))
            }
        }
    }

    /**
     * 从数据库加载章节内容
     */
    suspend fun loadBookContent(chapterUrl: String): BookContentEntity? {
        return bookRepository.loadBookContent(chapterUrl)
    }

    /**
     * 保存章节内容到数据库
     */
    suspend fun saveBookContent(content: BookContentEntity) {
        bookRepository.saveBookContent(content)
    }

    /**
     * 更新章节缓存状态
     */
    suspend fun updateChapterCache(chapterUrl: String, hasCache: Boolean) {
        bookRepository.updateChapterCache(chapterUrl, hasCache)
    }

    /**
     * 从网络获取章节内容
     */
    suspend fun fetchBookContent(chapterUrl: String, chapterIndex: Int): BookContentEntity {
        return withContext(Dispatchers.IO) {
            bookSourceManager.requireParser().getBookContent(
                context,
                chapterUrl,
                chapterIndex
            )
        }
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
