package com.ebook.book.reader

import androidx.lifecycle.viewModelScope
import com.ebook.common.repository.ChapterTocSync
import com.ebook.common.repository.ContentReader
import com.ebook.common.repository.ShelfRepository
import com.xrn1997.common.mvvm.viewmodel.BaseViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 阅读页 ViewModel：只持有一个 [ReaderSession]，本身不再存书架副本或阅读位置。
 *
 * 位置、目录、在架状态与追更都归会话所有；页面经 [readerState] 读，经 [session] 写。
 * 本类只剩三个生命周期动作：进入/离开时落进度、打开后查在架、下载入口前确保在架。
 * 仓库能力经三个窄面（[ShelfRepository] / [ContentReader] / [ChapterTocSync]）注入，
 * 原样转交给 [RepositoryReaderSessionGateway]，自身不直接调用。
 */
@HiltViewModel
class BookReadViewModel @Inject constructor(
    bookShelfRepository: ShelfRepository,
    contentReader: ContentReader,
    chapterTocSync: ChapterTocSync,
) : BaseViewModel<ShelfRepository>(bookShelfRepository) {

    /** 阅读位置的单一所有者；页面只经它读写，不再持第二份 `(章, 页)`。 */
    internal val session = ReaderSession(
        RepositoryReaderSessionGateway(bookShelfRepository, contentReader, chapterTocSync),
    )

    /** 页面渲染状态（书架快照、实时章页、在架结论及其派生标题/章数/滑条值）。 */
    internal val readerState = session.state

    /** 进入与离开时落一次进度；落库值是会话里的实时位置，不是打开时的快照。 */
    fun saveProgress() {
        viewModelScope.launch { session.saveProgress() }
    }

    /** 查询书架归属；结论由会话落定到 `readerState.shelfChecked`，不再另发事件。 */
    fun checkInShelf() {
        viewModelScope.launch { session.checkInShelf() }
    }

    /**
     * 加入书架，写入的是会话里的实时位置。
     *
     * 只有当前会话确实因这次写入而在架才回调：会话在写库期间换了书时返回 false，
     * 不为另一本书打开下载中心。
     */
    fun addToShelf(onSuccess: () -> Unit) {
        viewModelScope.launch {
            if (session.addToShelf()) onSuccess()
        }
    }

    companion object {
        const val OPEN_FROM_OTHER: Int = 0
        const val OPEN_FROM_APP: Int = 1
        const val TAG: String = "BookReadViewModel"
    }
}
