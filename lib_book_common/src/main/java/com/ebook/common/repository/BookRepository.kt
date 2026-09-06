package com.ebook.common.repository

import com.ebook.common.analyze.local.BookFormat
import com.ebook.common.analyze.local.BookLocation
import com.ebook.common.analyze.local.ChapterContent
import com.ebook.common.analyze.local.ChapterEntry
import com.ebook.common.analyze.local.ChapterReader
import com.ebook.common.domain.CommentKey
import com.ebook.common.store.BookStore
import com.ebook.common.store.ChapterContentCache
import com.ebook.common.store.WriteTransactionRunner
import com.ebook.common.text.TextNormalizer
import com.ebook.db.dao.BookGroupDao
import com.ebook.db.dao.BookInfoDao
import com.ebook.db.dao.BookShelfDao
import com.ebook.db.dao.ChapterListDao
import com.ebook.db.entity.BookGroupEntity
import com.ebook.db.entity.BookShelfEntity
import com.ebook.db.entity.ChapterListEntity
import com.xrn1997.common.mvvm.model.BaseModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 书架仓库 - 封装书架数据操作和事件通知
 *
 * 职责：
 * - 书架 CRUD 操作
 * - 阅读进度保存
 * - 章节正文统一读取（经 [ChapterReader] 路由，本地书与网络书走同一管线；
 *   规范化也收口在 [loadChapter]——存储层不清洗）
 * - 评论聚合键（M2）：读并集 / 写主键 / 合并、拆分、修键（`book_group` 的唯一业务入口）
 * - 内容仓库对账（[reconcileContentStore]，启动时调用）
 * - 书架变化事件发布（替代原 RxBus 的书籍相关事件）
 */
@Singleton
class BookRepository @Inject constructor(
    private val bookShelfDao: BookShelfDao,
    private val bookInfoDao: BookInfoDao,
    private val chapterListDao: ChapterListDao,
    private val bookGroupDao: BookGroupDao,
    private val chapterReaders: @JvmSuppressWildcards Map<BookFormat, ChapterReader>,
    private val bookStore: BookStore,
    private val contentCache: ChapterContentCache,
    private val transactions: WriteTransactionRunner,
) : BaseModel() {
    // ===== 事件 =====
    private val _bookShelfEvents = MutableSharedFlow<BookShelfEvent>(extraBufferCapacity = 64)
    val bookShelfEvents: SharedFlow<BookShelfEvent> = _bookShelfEvents.asSharedFlow()

    // ===== 数据操作 =====

    /** 获取所有书籍（简单列表，无关联数据） */
    suspend fun getAllBooks(): List<BookShelfEntity> = withContext(Dispatchers.IO) {
        bookShelfDao.getAllBooks()
    }

    /**
     * 观察书架全部数据（含书籍信息，按最后阅读时间倒序），响应式供数。
     *
     * 与 [getAllBooksWithDetails] 的差异：Flow 版本只做关联填充、不清理孤立记录
     * （清理有写副作用，不适合放在每次失效都重发的观察流里，由一次性查询负责）。
     * info 为 null 的孤立条目直接过滤，保证下游拿到的 bookInfo 非空。
     */
    fun observeBookShelf(): Flow<List<BookShelfEntity>> =
        bookShelfDao.getAllBooksFullInfoFlow().map { fullInfoList ->
            fullInfoList.mapNotNull { fullInfo ->
                fullInfo.info?.let { info ->
                    fullInfo.bookShelf.apply { bookInfo = info }
                }
            }
        }

    /** 获取所有书籍（含书籍信息和章节列表） */
    suspend fun getAllBooksWithDetails(): List<BookShelfEntity> = withContext(Dispatchers.IO) {
        val result = mutableListOf<BookShelfEntity>()
        val toDelete = mutableListOf<String>()

        bookShelfDao.getAllBooksFullInfo().forEach { fullInfo ->
            if (fullInfo.info == null) {
                // 孤立记录，需要清理
                toDelete.add(fullInfo.bookShelf.noteUrl)
            } else {
                result.add(fullInfo.bookShelf.apply {
                    bookInfo = fullInfo.info
                    // 按章节序号显式排序：@Relation 关联查询无 ORDER BY、按物理 rowid 返回，
                    // 历史上被 REPLACE（先删后插）过的行 rowid 会跳表尾，导致已下载/缓存章节错序；
                    // 显式排序既修正存量乱序数据，也兜底任何未来可能扰动 rowid 的写入
                    chapterList = fullInfo.chapters.sortedBy { it.durChapterIndex }
                })
            }
        }

        // 清理孤立记录
        toDelete.forEach { noteUrl ->
            bookShelfDao.deleteByUrl(noteUrl)
        }

        result
    }

    /** 根据 URL 获取书架条目 */
    suspend fun getBookByUrl(noteUrl: String): BookShelfEntity? = withContext(Dispatchers.IO) {
        bookShelfDao.getBookByUrl(noteUrl)
    }

    /** 保存阅读进度 */
    suspend fun saveProgress(bookShelf: BookShelfEntity) = withContext(Dispatchers.IO) {
        bookShelf.finalDate = System.currentTimeMillis()
        bookShelfDao.insert(bookShelf)
        _bookShelfEvents.emit(BookShelfEvent.ProgressUpdated(bookShelf))
    }

    /** 添加到书架 */
    suspend fun addToShelf(bookShelf: BookShelfEntity) = withContext(Dispatchers.IO) {
        // 先保存 bookInfo（如果存在）
        bookShelf.bookInfo?.let { bookInfo ->
            bookInfo.noteUrl = bookShelf.noteUrl
            bookInfoDao.insert(bookInfo)
        }
        // 保存 bookShelf
        bookShelfDao.insert(bookShelf)
        // 保存 chapterList：实体上是非空 List（默认空集），只需判空集，不用安全调用
        val chapters = bookShelf.chapterList
        if (chapters.isNotEmpty()) {
            // 设置 noteUrl 关联
            chapters.forEach { it.noteUrl = bookShelf.noteUrl }
            chapterListDao.insertAll(chapters)
        }
        // book_group 关联行：用当前匹配信息算评论键，本地书导入器已写过同样的行，
        // REPLACE 语义保证幂等；网络书此前不写，这里补上，M2 并集读才有数据可查
        val name = bookShelf.matchName ?: bookShelf.bookInfo?.name
        val author = bookShelf.matchAuthor ?: bookShelf.bookInfo?.author
        if (!name.isNullOrEmpty()) {
            bookGroupDao.insert(
                BookGroupEntity(
                    commentKey = CommentKey.compute(name, author),
                    noteUrl = bookShelf.noteUrl,
                    isPrimary = true,
                )
            )
        }
        _bookShelfEvents.emit(BookShelfEvent.Added(bookShelf))
    }

    /** 从书架移除（含关联数据清理） */
    suspend fun removeFromShelf(bookShelf: BookShelfEntity) = withContext(Dispatchers.IO) {
        chapterListDao.deleteChaptersForBook(bookShelf.noteUrl)
        bookInfoDao.deleteByUrl(bookShelf.noteUrl)
        // book_group 行随书删：评论路由失去依据，留着只会让后续合并/拆分逻辑误判
        bookGroupDao.deleteFor(bookShelf.noteUrl)
        bookShelfDao.deleteByUrl(bookShelf.noteUrl)
        // 章文件清理：本地书与网络书统一走 BookStore（book_content 表已在 v3→v4 删除）
        val format = resolveFormat(bookShelf)
        bookStore.deleteBook(BookLocation(bookShelf.noteUrl, format))
        contentCache.invalidateBook(bookShelf.noteUrl)
        _bookShelfEvents.emit(BookShelfEvent.Removed(bookShelf))
    }

    // ===== 章节正文统一读取 =====

    /**
     * 章节正文统一读取入口（spec §7 §10 M1b）。
     *
     * 本地书与网络书走同一条路径：按 bookFormat 路由到对应 ChapterReader → 规范化 →
     * 经 ChapterContentCache 内存缓存 → reader 内部判章文件存在性（存在则读盘，不存在则网络抓取并写文件）。
     *
     * **规范化就在这一层**（spec §4 §8：存储层不清洗）：章文件存的是"切分后、清洗前"的原文，
     * 这里过一次 [TextNormalizer.cleanParagraphs] 才交给渲染与段评锚点。放在缓存之前，
     * 于是每章只清洗一次、缓存里存的就是可直接排版的数据；改规范化规则也只改这一处，
     * 不必重导书籍。
     *
     * 旧实现的 loadBookContent / saveBookContent / deleteBookContent / updateChapterCache 全部删除：
     * book_content 表已在 v3→v4 迁移中删除，缓存存在性由 BookStore 章文件存在性判定。
     */
    suspend fun loadChapter(
        bookShelf: BookShelfEntity,
        index: Int,
        title: String,
        chapterContentRef: String = "",
    ): ChapterContent? {
        val format = resolveFormat(bookShelf)
        val reader = chapterReaders[format] ?: return null
        val location = BookLocation(bookShelf.noteUrl, format)
        val cacheKey = bookStore.chapterRef(bookShelf.noteUrl, index)
        return contentCache.getOrLoad(cacheKey) {
            val entryContentRef = if (format == BookFormat.NETWORK) chapterContentRef else cacheKey
            val content = reader.readChapter(
                ChapterEntry(index = index, title = title, contentRef = entryContentRef),
                location,
            )
            val normalized = content.copy(paragraphs = TextNormalizer.cleanParagraphs(content.paragraphs))
            // 全空白的一章按"内容缺失"处理（不落缓存），否则页面会拿到一个空页而不是错误态
            normalized.takeIf { it.paragraphs.isNotEmpty() }
        }
    }

    /**
     * 批量判定哪些章节已有缓存（章文件存在）。
     *
     * 供下载面板绘制"已缓存"徽章：以 BookStore 章文件为事实源。
     */
    suspend fun getCachedChapterIndices(
        bookShelf: BookShelfEntity,
        chapters: List<ChapterListEntity>,
    ): Set<Int> = withContext(Dispatchers.IO) {
        val format = resolveFormat(bookShelf)
        val location = BookLocation(bookShelf.noteUrl, format)
        chapters.filter { bookStore.hasChapter(location, it.durChapterIndex) }
            .map { it.durChapterIndex }
            .toSet()
    }

    /**
     * 解析书架的 BookFormat：本地书按 bookFormat 列（缺省 TXT），网络书固定 NETWORK。
     */
    private fun resolveFormat(bookShelf: BookShelfEntity): BookFormat {
        if (bookShelf.tag != BookShelfEntity.LOCAL_TAG) return BookFormat.NETWORK
        val rawFormat = bookShelf.bookFormat
        return if (rawFormat == null) {
            BookFormat.TXT
        } else {
            runCatching { BookFormat.valueOf(rawFormat) }.getOrDefault(BookFormat.TXT)
        }
    }

    /**
     * 取某本书关联的全部评论聚合键（M2：跨源评论合并查询）。
     *
     * 同一作品可能从多个书源加入书架（不同 noteUrl），每条在 [addToShelf] 时写入一行
     * `book_group`；此方法返回该 noteUrl 对应的所有 `commentKey`，供评论区做并集查询。
     */
    suspend fun getCommentKeysForBook(noteUrl: String): List<String> =
        withContext(Dispatchers.IO) {
            bookGroupDao.getKeysForNoteUrl(noteUrl)
        }

    /**
     * 取某本书的**写入键**（`is_primary` 那行的键），供发评论用。
     *
     * 不能拿 [getCommentKeysForBook] 的首元素代替：`getKeysForNoteUrl` 的 SELECT 没有
     * ORDER BY，返回顺序不保证；修键后新主键是后插入的那行，取首元素会把新评论写进旧桶
     * （spec §9.2「读评论 = 并集、写评论 = 只用主键」）。无 book_group 行时返回 null，
     * 由调用方决定是否回落。
     */
    suspend fun getPrimaryKeyForBook(noteUrl: String): String? =
        withContext(Dispatchers.IO) {
            bookGroupDao.getPrimaryForNoteUrl(noteUrl)
        }

    // ===== M2：合并/拆分/修键 =====

    /**
     * 取某本书的全部 book_group 行（含 isPrimary 标记），供修键面板展示。
     */
    suspend fun getBookGroupRows(noteUrl: String): List<BookGroupEntity> =
        withContext(Dispatchers.IO) {
            bookGroupDao.getAllForNoteUrl(noteUrl)
        }

    /**
     * 把 sourceNoteUrl 的全部关联键并入 targetNoteUrl 的并集（覆盖处置用）。
     *
     * 语义仍是 spec §9.2 的「合并 = 加一行」：source 自身的行不动，target 的并集每个键多一行
     * secondary。需要它的场景只有一个——覆盖会删掉旧条目，而旧条目身上可能挂着它自己历次
     * 合并攒下的 secondary 行；`book_group` 行随书删（见 [removeFromShelf]），不先吸收就把
     * 那些桶的读并集一起丢了。
     *
     * 与 target 主键同名的那行会被 [BookGroupDao.addSecondary] 的 IGNORE 挡掉（复合主键
     * `(comment_key, note_url)` 已存在），不必在此特判。
     */
    suspend fun absorbGroupKeys(targetNoteUrl: String, sourceNoteUrl: String) =
        withContext(Dispatchers.IO) {
            bookGroupDao.getAllForNoteUrl(sourceNoteUrl).forEach { row ->
                bookGroupDao.addSecondary(
                    BookGroupEntity(commentKey = row.commentKey, noteUrl = targetNoteUrl, isPrimary = false)
                )
            }
        }

    /**
     * 智能合并（导入时判重命中的「补章」处置）：把新条目多出来的**尾部章节**补进旧条目，
     * 然后删掉新条目。
     *
     * 只补尾，不插中间。判据是「旧书的归一化章名序列是新书的前缀」：同一本书的两份文件
     * 通常是同一套切分规则的结果，前缀相等即两边对齐，多出来的部分必然落在书尾（旧那份
     * 没下全）。前缀一旦分叉，就说明两本的章节命名或切分不一致——这时按位置补章会把正文
     * 错位插进旧书，代价远大于「少补几章」，所以整笔放弃（[ImportMergeResult.Diverged]），
     * 两个条目继续共存。与正文分页那条「宁漏页不串章」是同一取舍。
     *
     * 旧条目身上的一切都不动：`noteUrl`、阅读进度、批注、评论键都在，只是末尾多了几章。
     * 评论侧也不需要 [absorbGroupKeys]——判重命中本身就意味着两本算出同一个键，删掉新条目
     * 不会让旧条目的读并集少一个键。这与"覆盖"不同：覆盖活下来的是新条目，得先把旧条目身上
     * 的 secondary 键搬过去。
     *
     * 提交顺序仍是「先写章文件、后一个事务写库」（对齐 [LocalBookImporter] 的理由：反过来会
     * 留下指向不存在文件的索引行）。中途失败最坏是旧目录多几个没有索引行的章文件——读取路径
     * 按索引行走，碰不到它们，也不会被对账误删（对账只回收库里已无书的整目录）。
     *
     * @param newNoteUrl 刚导入完成的那本（会被删除）
     * @param oldNoteUrl 书架上已有的那本（被补章，保留）
     */
    suspend fun mergeTailChapters(newNoteUrl: String, oldNoteUrl: String): ImportMergeResult =
        withContext(Dispatchers.IO) {
            val oldShelf = bookShelfDao.getBookByUrl(oldNoteUrl)
                ?: return@withContext ImportMergeResult.EntryMissing
            val newShelf = bookShelfDao.getBookByUrl(newNoteUrl)
                ?: return@withContext ImportMergeResult.EntryMissing

            // 章正文只有本地书归本机文件管；网络书的"补一章"没有可补的载体（见 ADR-0023）
            if (oldShelf.tag != BookShelfEntity.LOCAL_TAG) return@withContext ImportMergeResult.TargetNotLocal

            val oldChapters = chapterListDao.getChaptersForBook(oldNoteUrl).sortedBy { it.durChapterIndex }
            val newChapters = chapterListDao.getChaptersForBook(newNoteUrl).sortedBy { it.durChapterIndex }
            val oldNames = oldChapters.map { CommentKey.normalize(it.durChapterName) }
            val newNames = newChapters.map { CommentKey.normalize(it.durChapterName) }
            if (newNames.size < oldNames.size || newNames.subList(0, oldNames.size) != oldNames) {
                return@withContext ImportMergeResult.Diverged
            }

            val extras = newChapters.drop(oldNames.size)
            // 有洞（历史删章）时 size 会与末位索引不相等，拿它当下一个索引会覆写既有章文件
            var nextIndex = (oldChapters.maxOfOrNull { it.durChapterIndex } ?: -1) + 1
            val oldLocation = BookLocation(oldNoteUrl, resolveFormat(oldShelf))
            val newLocation = BookLocation(newNoteUrl, resolveFormat(newShelf))
            val rows = extras.map { chapter ->
                bookStore.writeChapter(
                    oldLocation,
                    nextIndex,
                    bookStore.readParagraphs(newLocation, chapter.durChapterIndex),
                )
                ChapterListEntity(
                    noteUrl = oldNoteUrl,
                    durChapterIndex = nextIndex,
                    contentRef = bookStore.chapterRef(oldNoteUrl, nextIndex),
                    durChapterName = chapter.durChapterName,
                    tag = oldShelf.tag,
                ).also { nextIndex++ }
            }
            transactions.run { chapterListDao.insertAll(rows) }

            // 新条目整本退场（DB 行 + 章文件 + book_group 随删 + 缓存失效）
            removeFromShelf(newShelf)
            // 旧条目多了章节：内存缓存里那本必须重取，否则读到补章前的旧页序
            contentCache.invalidateBook(oldNoteUrl)
            ImportMergeResult.Merged(appendedChapters = rows.size)
        }

    /**
     * 拆分：从某本书的并集里删掉一个特定键行。
     *
     * 语义见 spec §9.2：拆分 = 删一行。不得删主键行（主键行是这本书自身的身份，
     * 删了就没法写评论了），只能删 secondary 行。
     */
    suspend fun splitBook(noteUrl: String, commentKeyToRemove: String) =
        withContext(Dispatchers.IO) {
            val primary = bookGroupDao.getPrimaryForNoteUrl(noteUrl)
            if (commentKeyToRemove == primary) return@withContext // 不允许删主键
            bookGroupDao.deleteSpecific(noteUrl, commentKeyToRemove)
        }

    /**
     * 修键：改主匹配名/作者 → 重算键 → 旧主键降级、新键成为主键。
     *
     * 旧行保留（spec §9.3）：旧评论不丢，读并集时仍可见。新评论进新键桶。
     * 调用方负责决定是否迁移本人旧评论（经 CommentRepository.migrateMyComments）。
     *
     * @return Pair(oldPrimaryKey, newPrimaryKey) 供调用方做评论迁移
     */
    suspend fun updateMatchMeta(
        noteUrl: String,
        newMatchName: String,
        newMatchAuthor: String,
    ): Pair<String, String> = withContext(Dispatchers.IO) {
        val oldPrimary = bookGroupDao.getPrimaryForNoteUrl(noteUrl)
            ?: throw IllegalStateException("no book_group row for $noteUrl")
        val newKey = CommentKey.compute(newMatchName, newMatchAuthor)
        if (newKey == oldPrimary) return@withContext oldPrimary to newKey

        // 元数据与主键是同一个 comment_key 的两半，必须同事务提交：分开写会在中途失败时
        // 留下「匹配名已改、主键仍是旧键」的静默不一致（下次导入判重比的正是旧键）
        transactions.run {
            val shelf = bookShelfDao.getBookByUrl(noteUrl)
            if (shelf != null) {
                shelf.matchName = newMatchName
                shelf.matchAuthor = newMatchAuthor
                bookShelfDao.update(shelf)
            }
            // 旧主键降级，新键成为主键（旧行保留）。SQLite 表达不了「一个 note_url 恰好一行
            // is_primary」（无部分唯一索引），裸调中途失败会留下零主键行——此后这本书写评论
            // 取不到主键（spec §5 §9.2）
            bookGroupDao.clearPrimary(noteUrl)
            bookGroupDao.insert(
                BookGroupEntity(commentKey = newKey, noteUrl = noteUrl, isPrimary = true)
            )
        }
        oldPrimary to newKey
    }

    /**
     * 内容仓库对账（spec §4）：回收"DB 里已无书"的目录、导入中断的 `.tmp` 残留与散落文件。
     *
     * 为什么必须由人调：删书与导入中断都会留下无主文件（`removeFromShelf` 只在正常路径删目录，
     * 进程被杀时来不及），而对账是唯一的回收手段——不跑就是"占了空间却看不见书"。
     *
     * **调用时机的不变式：一个进程只在全机启动时跑一次，导入进行中不得调用。**
     * 两处会误删：①`[com.ebook.common.importer.LocalBookImporter]` 在 `commitImport` 与落库
     * 之间存在"目录已改名、DB 还没有行"的窗口；②正在写入的暂存目录带 `.tmp` 后缀，
     * 在对账眼里就是残留。启动点两条都不可能撞上：导入只由界面操作发起。
     */
    suspend fun reconcileContentStore() = withContext(Dispatchers.IO) {
        val liveBookIds = bookShelfDao.getAllBooks().map { it.noteUrl }.toSet()
        bookStore.reconcile(liveBookIds)
    }

    /**
     * 只发"已加入书架"事件、不重复写任何表。
     *
     * 存在的理由：[addToShelf] 会级联写 book_info / book_shelf / chapter_list，而导入器已经
     * 在自己的事务里写完这些了——旧实现导入后仍调 `addToShelf`，把 N 条章节行又 REPLACE 了
     * 一遍。事件是书架刷新与"换源"提示的唯一依据，不能省，所以把"发事件"从"写数据"里拆出来。
     */
    suspend fun publishAdded(bookShelf: BookShelfEntity) {
        _bookShelfEvents.emit(BookShelfEvent.Added(bookShelf))
    }

}

/**
 * 书架事件定义
 */
sealed class BookShelfEvent {
    /** 书籍添加到书架 */
    data class Added(val bookShelf: BookShelfEntity) : BookShelfEvent()

    /** 书籍从书架移除 */
    data class Removed(val bookShelf: BookShelfEntity) : BookShelfEvent()

    /** 阅读进度更新 */
    data class ProgressUpdated(val bookShelf: BookShelfEntity) : BookShelfEvent()
}

/**
 * [mergeTailChapters] 的结局。四个分支都得让 UI 说得出人话——判重处置是破坏性操作，
 * 「静默没做成」比「做成 0 章」更让用户困惑。
 */
sealed class ImportMergeResult {
    /** 补章成功：新条目已并入旧条目并退场，[appendedChapters] 可以为 0（两份内容等价） */
    data class Merged(val appendedChapters: Int) : ImportMergeResult()

    /** 旧条目不是本地书：正文不在本机文件里，没有可补的章节，两本继续共存 */
    data object TargetNotLocal : ImportMergeResult()

    /** 归一化章名序列分叉：切分规则不一致，按位置补章会错位，整笔放弃（两本共存） */
    data object Diverged : ImportMergeResult()

    /** 任一条目已不在书架上（用户在弹窗期间手动删除，或导入本身没落库） */
    data object EntryMissing : ImportMergeResult()
}
