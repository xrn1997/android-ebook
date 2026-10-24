package com.ebook.common.manager

import com.ebook.common.analyze.source.SourceResolver
import com.ebook.common.analyze.source.parserOrThrow
import com.ebook.common.repository.BookRepository
import com.ebook.common.repository.BookShelfEvent
import com.ebook.db.entity.BookShelfEntity
import com.ebook.db.entity.SearchBookEntity
import com.ebook.source.analyze.BookSourceNotFoundException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 书架标记列表的唯一主人：书架快照、书架事件到快照的应用、以及按快照给搜索结果打「已加书架」。
 *
 * 早先这里只有三个无状态函数，快照（`mutableListOf<BookShelfEntity>`）、事件 `when` 块与
 * 「按 `noteUrl` 找下标改 `add`」仍由 `SearchViewModel` / `ChoiceBookViewModel` 各存一份逐字副本——
 * 两个页面各自维护一份书架镜像，谁漏处理一次事件，谁的「已加书架」就停在旧值。
 * 现在快照只在 [shelves] 一处，事件经 [applyEvent] 单点应用；页面只持有自己的结果列表。
 *
 * 标记与列表的组合逻辑是 [com.ebook.common.manager.applying] 这个纯函数：页面据
 * 「返回同一实例」判断要不要回写列表。本类不持有任何页面列表，也不发事件。
 */
@Singleton
class BookShelfManager @Inject constructor(
    private val sourceResolver: SourceResolver,
    private val bookRepository: BookRepository
) {
    private val _shelves = MutableStateFlow<List<BookShelfEntity>>(emptyList())

    /** 当前书架快照；页面在首次刷新后读它做标记，不再自己维护一份。 */
    val shelves: StateFlow<List<BookShelfEntity>> = _shelves.asStateFlow()

    /**
     * 从仓库整体重载快照。
     *
     * 只有这一种装载方式：页面调它一次即可，不要在构造时顺手查库（读库是异步的，
     * 首帧拿到空快照会把所有条目标成「未加书架」，随后也没有第二次刷新）。
     */
    suspend fun refresh() {
        _shelves.value = bookRepository.getAllBooks()
    }

    /**
     * 应用一个书架事件。
     *
     * `Added` 按 `noteUrl` 幂等（同一事件重放不会长出行）；`Removed` 按 `noteUrl` 整行移除；
     * 进度与目录事件与「是否在架」无关，原样返回当前快照。
     * 两个页面都会把同一条事件递进来，幂等是这里的要求，不是调用方的纪律。
     */
    fun applyEvent(event: BookShelfEvent) {
        _shelves.update { current ->
            when (event) {
                is BookShelfEvent.Added ->
                    if (current.any { it.noteUrl == event.bookShelf.noteUrl }) current
                    else current + event.bookShelf

                is BookShelfEvent.Removed ->
                    current.filterNot { it.noteUrl == event.bookShelf.noteUrl }

                is BookShelfEvent.ProgressUpdated, is BookShelfEvent.ChaptersUpdated -> current
            }
        }
    }

    /**
     * 为搜索结果标记已加入书架状态。
     *
     * 读的是本类自己的快照（[shelves]），调用方不再传一份并行的书架列表——
     * 两份列表一旦不同步，标记就会按过期数据给出。
     */
    fun markShelfStatus(searchResults: List<SearchBookEntity>) {
        val shelfUrls = shelves.value.mapTo(HashSet()) { it.noteUrl }
        for (book in searchResults) {
            if (book.noteUrl in shelfUrls) {
                book.add = true
            }
        }
    }

    /**
     * 从搜索结果加入书架（完整流程）
     *
     * **按 `tag` 取 parser，不按「当前默认源」取**（见 ADR-0016）：结果来自哪个站，`getBookInfo` /
     * `getChapterList` 就必须用那个站的规则去解——多书源共存后一条搜索列表里可能混着多个源的条目，
     * 用默认源规则解另一源的 `noteUrl` 不会闪退，只会拉回一本不相干的书或空目录（错数据比崩溃难查得多）。
     * 归属查不到（源被删 / tag 是脏数据）时抛 [BookSourceNotFoundException]，经下面的 catch 变成
     * [Result.failure] 带回调用方，由 ViewModel 统一提示「书源已失效」。
     *
     * 本地书不会走到这里：`loc_book` 的条目不经搜索结果加入书架。
     *
     * @param searchBook 搜索结果
     * @return 成功返回包含完整信息的 BookShelfEntity，失败返回异常
     */
    suspend fun addFromSearch(searchBook: SearchBookEntity): Result<BookShelfEntity> {
        return try {
            val shelf = BookShelfEntity().apply {
                noteUrl = searchBook.noteUrl
                tag = searchBook.tag
            }
            // 两次解析共用同一个 parser 实例：中途用户删了源也不至于一半新一半旧
            val parser = sourceResolver.parserOrThrow(searchBook.tag)
            val bookInfo = parser.getBookInfo(shelf)
            val chapterResult = parser.getChapterList(bookInfo)
            val bookShelf = chapterResult.data
            bookRepository.addToShelf(bookShelf)
            Result.success(bookShelf)
        } catch (e: CancellationException) {
            // 取消不是「加入书架失败」：原样上抛，避免调用方按失败路径弹 Toast
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
