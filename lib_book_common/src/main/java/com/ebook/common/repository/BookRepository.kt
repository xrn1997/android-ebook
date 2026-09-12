package com.ebook.common.repository

import com.ebook.common.analyze.local.BookFormat
import com.ebook.common.analyze.local.BookLocation
import com.ebook.common.analyze.local.ChapterContent
import com.ebook.common.analyze.local.ChapterEntry
import com.ebook.common.analyze.local.ChapterReader
import com.ebook.common.analyze.source.BookSourceManager
import com.ebook.common.domain.CommentKey
import com.ebook.common.store.BookStore
import com.ebook.common.store.ChapterContentCache
import com.ebook.common.store.WriteTransactionRunner
import com.ebook.common.text.TextNormalizer
import com.ebook.db.dao.BookGroupDao
import com.ebook.db.dao.BookInfoDao
import com.ebook.db.dao.BookShelfDao
import com.ebook.db.dao.ChapterListDao
import com.ebook.db.dao.DownloadChapterDao
import com.ebook.db.entity.BookGroupEntity
import com.ebook.db.entity.BookShelfEntity
import com.ebook.db.entity.ChapterListEntity
import com.ebook.db.entity.SearchBookEntity
import com.ebook.db.event.DBCode
import com.ebook.source.analyze.BookSourceNotFoundException
import com.xrn1997.common.mvvm.model.BaseModel
import kotlinx.coroutines.CancellationException
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
 * - 阅读中换源（[switchSource]，见 ADR-0016）：跨书源原地替换一条书架条目，章序号映射进度
 * - 内容仓库对账（[reconcileContentStore]，启动时调用）
 * - 书架变化事件发布（替代原 RxBus 的书籍相关事件）
 */
@Singleton
class BookRepository @Inject constructor(
    private val bookShelfDao: BookShelfDao,
    private val bookInfoDao: BookInfoDao,
    private val chapterListDao: ChapterListDao,
    private val bookGroupDao: BookGroupDao,
    /**
     * 下载队列（`download_chapter`）的访问器。
     *
     * 只有一处用到：[switchSource] 收尾时把**旧条目**名下的在队任务一起删掉（见 [commitSwitch]）。
     * 它不属于「一个书架条目由哪些行组成」那份清单（[deleteEntryRows]），因为队列是**流水**而不是
     * 条目的附属数据：一行任务记的是「某站某一章的 URL」，书没了它也只是没人取的残留。
     */
    private val downloadChapterDao: DownloadChapterDao,
    private val chapterReaders: @JvmSuppressWildcards Map<BookFormat, ChapterReader>,
    /**
     * 书源清单的唯一入口。换源事务要按**目标条目**的 `tag` 取 parser 拉详情与目录（见 [switchSource]）；
     * 本仓库其余读面（[loadChapter]）不需要它——那条路径的归属已随 [BookLocation] 交给 reader。
     */
    private val bookSourceManager: BookSourceManager,
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
        writeEntry(bookShelf)
        _bookShelfEvents.emit(BookShelfEvent.Added(bookShelf))
    }

    /**
     * 写「一个书架条目」的全部库内行：book_info → book_shelf → chapter_list → book_group 主键行。
     *
     * 从 [addToShelf] 抽出而不复制：[switchSource] 要在**同一个写事务**里落下完整的新条目，
     * 而 `addToShelf` 自带的 `withContext` 与事件发射都不能嵌进事务（前者会跳出 Room 的事务线程、
     * 后者会把「未提交的写」当成事实广播出去）。两处共用同一份「一个条目由哪些行组成」的事实，
     * 将来加一张表 / 一列时不会只改到一边、留下半个条目。
     *
     * 本方法**不发事件**，且假设调用方负责线程与事务边界。
     */
    private suspend fun writeEntry(bookShelf: BookShelfEntity) {
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
    }

    /** 从书架移除（含关联数据清理） */
    suspend fun removeFromShelf(bookShelf: BookShelfEntity) = withContext(Dispatchers.IO) {
        deleteEntryRows(bookShelf.noteUrl)
        // 章文件清理：本地书与网络书统一走 BookStore（book_content 表已在 v3→v4 删除）
        val format = resolveFormat(bookShelf)
        bookStore.deleteBook(BookLocation(bookShelf.noteUrl, format, bookShelf.tag))
        contentCache.invalidateBook(bookShelf.noteUrl)
        _bookShelfEvents.emit(BookShelfEvent.Removed(bookShelf))
    }

    /**
     * 删「一个书架条目」的全部库内行：chapter_list → book_info → book_group → book_shelf。
     *
     * 与 [removeFromShelf] 的差别只在**范围**：本方法不删章文件、不失效内存缓存、不发事件，
     * 因此它服务的是「条目要消失但内容要留下」的场景（[switchSource] 换源，旧章文件正是留给
     * 用户切回时复用的）。真正的移除仍走 [removeFromShelf]——那里删文件是有意的：用户长按
     * 「移出书架」就是不要这本书了，留着只会白占磁盘。
     *
     * 与 [writeEntry] 同理从 removeFromShelf 抽出，让「一个条目由哪些行组成」只有一个事实源。
     * 注意本表库**无外键级联**（见 [com.ebook.db.dao.BookShelfDao.deleteByUrl] 的说明），
     * 少删一行就是永久孤儿行，新增条目附属表时两侧都要跟。
     */
    private suspend fun deleteEntryRows(noteUrl: String) {
        chapterListDao.deleteChaptersForBook(noteUrl)
        bookInfoDao.deleteByUrl(noteUrl)
        // book_group 行随书删：评论路由失去依据，留着只会让后续合并/拆分逻辑误判
        bookGroupDao.deleteFor(noteUrl)
        bookShelfDao.deleteByUrl(noteUrl)
    }

    // ===== 阅读中换源（ADR-0016 决策 8） =====

    /**
     * 阅读中换源：把书架条目 [oldShelf] 原地替换成同一作品、另一书源的 [newBook]，
     * 成功时返回已落在 `book_shelf` 上的新条目（其 `chapterList` 是本次刚抓到的目录，
     * 未落库字段仍在内存里，调用方可据此告诉用户「新源共多少章」）。
     *
     * ### 结构：网络在事务外，库内在事务内
     * 一次换源 = 两次网络解析（详情 + 目录）+ 四张表的写。解析刻意留在事务之外
     * （见 [fetchEntryFromSource]），库内写全塞进 [transactions] 的**一个**写事务
     * （见 [commitSwitch]）：Room 的写事务线程受限且越短越好，把第三方站点的响应时间关进事务，
     * 等于让全库的写在最慢的那条源上排队。
     *
     * ### 章序号映射是**有损**转换
     * `durChapter = min(旧 durChapter, 新章节总数 - 1)`。不同书源的分章/合章与番外顺序不一致，
     * 同一个序号可能落在正文的偏前或偏后位置，极端情况偏 1-3 章。**接受的代价**是这比「从第一章
     * 重开」体验好、比「全文匹配定位」便宜——精确对齐要拿新源每章标题与旧源当前章做模糊匹配、
     * 还要抓正文比对，成本是数十次请求。新源章节更少时截到末章；新源一章都没解出来时取 0
     * （那种源本不该被选中，但序号不能留成 -1：阅读器按 index 取章会立刻越界）。
     * `durChapterPage` 复位为 [DBCode.BookContentView.DUR_PAGE_INDEX_BEGIN]——**页级进度不跨源**：
     * 同一章在两个站的正文长度与分页规则都不同，旧页码在新章里既可能越过文末也可能落回中段，
     * 从第一页开始是唯一不会读错内容的选择。`finalDate` 保留旧值：换源换的是载体不是阅读时间，
     * 「最后阅读」排序不该被一次换源推到最前（下一次 [saveProgress] 自然会刷新）。
     * `matchName`/`matchAuthor` 一并从旧条目带过来：那是用户可能在修键面板上手工校准过的事实，
     * 新源书名不同就换回旧写法，评论桶才不会因换源而漂移——漂移的用户感知是「我的评论不见了」。
     *
     * ### 旧源缓存留不留：与 ADR 处方有意偏离的一处取舍
     * ADR 决策 8 要求「旧书源的 ChapterList/BookContent 保留，用户可切回且秒开」。写那条时正文还住在
     * `book_content` 表（按章节 URL 全局唯一，天然不属于任何一本书）；v3→v4 之后正文是
     * `filesDir/books/<noteUrl>/cNNNNN.txt`、目录是 `chapter_list` 里按 `note_url` 归属的行，
     * 两者都成了**书架条目的附属物**。「条目删了而行留着」造出的因此不是缓存而是孤儿行：
     * `book_info` / `chapter_list` 的反向孤立行**没有任何清理路径**（[getAllBooksWithDetails] 只清
     * 「有 shelf 行却缺 info」那一个方向，`@Relation` 也不会去捞没有书架行的章节），留一次就永久躺在库里。
     *
     * 本方法的折中：**库内行一律删净（[deleteEntryRows]），章文件与内存缓存一律不删**——
     * 也就是 [removeFromShelf] 里那两行 `bookStore.deleteBook` / `contentCache.invalidateBook`
     * 在换源路径上刻意不做。效果与代价：
     * - 切回旧源**必然重拉一次目录**（一次 HTTP，不是整本正文），但**已读章节的正文命中盘上文件**：
     *   reader 的存在性判定是 `BookStore.hasChapter(location, index)`，目录行只是索引，
     *   同一站点的章序稳定，重抓目录得到的 index 与既有文件名对得上，「秒开」仍成立；
     * - 这份秒开只在**同一次进程内**成立——旧目录此刻正是 [reconcileContentStore] 眼里的无主目录
     *   （`liveBookIds` 取自 `book_shelf`），下一次启动对账会把它回收。这是**有意的**：`BookStore.reconcile`
     *   就是这套目录既有的回收器，换源留下的恰是它设计要收的形态，不必再造一个「保留区」；
     *   反过来，为保住缓存给 `book_shelf` 加一列「隐藏条目」要走 schema 迁移 + 全链路查询都带过滤，
     *   换来的只是省一次目录请求，不值。
     * - 「把目录行也留住」为什么否掉：那等价于「书架上有一本看不见的书」，会同时污染
     *   [com.ebook.common.store.BookStore.storageUsage] 的册数、[getAllBooks] 的计数与评论并集的归属判断，
     *   代价远大于收益。
     *
     * ### 失败窗口
     * - **解析阶段失败**（取不到 parser、详情/目录抛错、网络断）：一行库都没动，书架保持原样，
     *   用户可另选候选或重试。这是 [Result.failure] 最常见的一条路径，调用方须保留弹层。
     * - **事务内失败**：整个写事务回滚，库里仍是旧条目（新行不生效），事件也不发。
     *   诚实交代：原子性来自生产实现（Room 的 `withTransaction`），[WriteTransactionRunner] 这个接缝
     *   本身不保证——单测里的替身就是直接执行 block、不回滚，所以「失败后旧行仍在」的用例锁的是
     *   「失败发生在任何写之前」这一条，不是「半途能回滚」。
     * - **事务已提交、事件还没发出去**（进程被杀，唯一的真窗口）：库里已是新条目，事件丢了。
     *   自愈路径是书架的事实源本来就是 `book_shelf` 的 Room 失效流（[observeBookShelf]）——书架页与
     *   阅读统计下一次查询自然纠正；只有「自己攒一份内存快照、只按事件增删」的页面（如搜索页的
     *   书架标记）会短暂偏差，收到下一次任意书架事件即纠正。**不做自动补偿**（把事件记进「待广播」
     *   重放）：事件从不是事实源、库才是，同样的窗口在 [addToShelf] / [removeFromShelf] 上一致存在，
     *   单给换源加一套补偿只会让三条路径的语义各不相同。
     *
     * @param oldShelf 书架上正在读的条目（调用方持有的快照，其 `noteUrl` 即待删的旧条目）
     * @param newBook 聚合搜索里选中的候选，`tag` 是它所属书源的 URL
     * @return 成功为换源后的新条目；失败为 [BookSourceNotFoundException]（目标源已被删/是脏数据）、
     *   [BookAlreadyOnShelfException]（目标那条 `noteUrl` 本是书架上另一个条目）、
     *   [IllegalArgumentException]（本地书没有源可换、目标就是当前条目本身）或解析与写库抛出的原异常
     */
    suspend fun switchSource(
        oldShelf: BookShelfEntity,
        newBook: SearchBookEntity,
    ): Result<BookShelfEntity> {
        // 本地书没有「源」可换：它的 tag 恒为 loc_book，正文在自己导入的文件里。
        // 拿 loc_book 去问 book_source 表永远查不到行（getParserFor 的 null 成因第 1 条），
        // 报成「书源已失效」会把完全正常的状态说成故障，所以按参数错误挡在前面。
        if (oldShelf.tag == BookShelfEntity.LOCAL_TAG) {
            return Result.failure(
                IllegalArgumentException("本地书没有可换的书源：noteUrl=${oldShelf.noteUrl}")
            )
        }
        if (newBook.noteUrl == oldShelf.noteUrl) {
            // 目标就是当前条目本身：下面「先插新后删旧」会先写同一主键、再把刚写的那行删掉，
            // 结局是这本书从书架上消失。UI 侧已按 excludeSourceUrl 排除当前源，这里仍要挡死——
            // 一边是省一次无谓换源，一边是丢用户数据，代价不对称。
            return Result.failure(
                IllegalArgumentException("换源目标与当前条目相同：noteUrl=${newBook.noteUrl}")
            )
        }
        // 目标是**书架上另一本已有的书**（同一 noteUrl，只是从另一个源加进来的那条）：
        // 必须挡在两次网络解析之前，理由见 [BookAlreadyOnShelfException]。
        if (bookShelfDao.getBookByUrl(newBook.noteUrl) != null) {
            return Result.failure(BookAlreadyOnShelfException(newBook.noteUrl))
        }

        val newShelf = try {
            fetchEntryFromSource(newBook, oldShelf)
        } catch (e: CancellationException) {
            // 取消不是「换源失败」：原样上抛，避免调用方按失败路径弹 Toast（同 BookShelfManager.addFromSearch）
            throw e
        } catch (e: Exception) {
            return Result.failure(e)
        }

        return try {
            withContext(Dispatchers.IO) {
                transactions.run { commitSwitch(oldShelf.noteUrl, newShelf) }
            }
            // 事件只在提交成功之后发（未提交的写不是事实）
            publishSwitched(oldShelf, newShelf)
            Result.success(newShelf)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 换源的「网络那一半」：按 [newBook] 的归属取 parser，拉详情与目录，再把 [oldShelf] 的进度映射过去。
     *
     * 入参实体的构造照 `BookShelfManager.addFromSearch` 的既有写法（只用 `noteUrl` + `tag` 起头，
     * 其余字段交给 parser 填），不另立一套：两处走的是同一条解析链，构造方式一旦分叉，
     * 换源拉回来的书就会和「从搜索加书架」拉回来的不一样。
     *
     * 取不到 parser 一律抛 [BookSourceNotFoundException]（null 的四种成因见
     * [BookSourceManager.getParserFor]，前三种在此都该报错中止）。
     */
    private suspend fun fetchEntryFromSource(
        newBook: SearchBookEntity,
        oldShelf: BookShelfEntity,
    ): BookShelfEntity {
        val parser = bookSourceManager.getParserFor(newBook.tag)
            ?: throw BookSourceNotFoundException(
                newBook.tag,
                detail = "换源目标 noteUrl=${newBook.noteUrl}",
            )
        val stub = BookShelfEntity().apply {
            noteUrl = newBook.noteUrl
            tag = newBook.tag
        }
        // 两次解析共用同一个 parser 实例（同 addFromSearch 的理由）：中途用户删了源也不至于一半新一半旧
        val withInfo = parser.getBookInfo(stub)
        val newShelf = parser.getChapterList(withInfo).data
        // 新条目必须自带 book_info：换源紧接着就删旧条目的全部行，少了这一行等于往 book_shelf 里
        // 塞一条「有书架、无书籍信息」的孤立行——getAllBooksWithDetails 每次启动都会把它当垃圾清掉，
        // 用户看到的现象是这本书凭空消失。真解析器（JsoupBookParser.getBookInfo）总会填上，
        // 这里挡的是「换了个 parser 实现、或规则缺项导致没填」这种脏返回，且挡在任何写之前。
        checkNotNull(newShelf.bookInfo) { "新源没有解出书籍信息，放弃换源：noteUrl=${newBook.noteUrl}" }

        val newChapterCount = newShelf.chapterList.size
        newShelf.durChapter = if (newChapterCount == 0) {
            0
        } else {
            minOf(oldShelf.durChapter, newChapterCount - 1)
        }
        newShelf.durChapterPage = DBCode.BookContentView.DUR_PAGE_INDEX_BEGIN
        newShelf.finalDate = oldShelf.finalDate
        newShelf.matchName = oldShelf.matchName
        newShelf.matchAuthor = oldShelf.matchAuthor
        return newShelf
    }

    /**
     * 换源的「库内那一半」，被调用方放在**同一个写事务**里：插新 → 吸收旧键 → 删旧 → 清旧队列。
     *
     * 顺序两条，都不是随手写的：
     * - **先插新、后删旧**是本仓「先导入新条目、后处置旧条目」的同一条纪律
     *   （同一判据见 [mergeTailChapters]）。反过来一旦中途失败，这本书就从书架上消失了（丢数据）；
     *   现在最坏是两条目并存（看得见、可手动删）。
     * - **吸收必须先于删旧**：`book_group` 行随条目删（见 [deleteEntryRows]），旧条目身上挂着它历次
     *   合并攒下的 secondary 键，先删就把那些评论桶的读并集一起丢了。
     *
     * 最后一步清 [com.ebook.db.dao.DownloadChapterDao.deleteByNoteUrl] 是换源特有的：**这本书还在架上**，
     * 只是换了个 noteUrl。留着旧那批任务的后果见 [downloadChapterDao] 上的说明与
     * `BookRepositorySwitchSourceTest` 的那条用例——服务按书架遍历取任务，旧 noteUrl 已不在架上，
     * 那些行从此谁也取不到，却仍然撑着下载管理页一个 `totalChapters = 0` 的分组与书架角标上不归零的计数。
     * 为什么是**删**而不是「把 noteUrl 改成新条目」：任务里的 `durChapterUrl` 是旧站的章节地址，
     * 按新源重跑等于拿旧 URL 打新站（`JsoupSourceReader` 按任务的 `tag` 取 parser、按该 URL 取正文），
     * 最坏解回一章不相干的内容——宁可不下载，也不能下错。用户想让新源离线，
     * 在阅读器里对换好后的那本重新发起下载即可。
     *
     * 为什么 [removeFromShelf] 不做这一步：那是「用户不要这本书了」，队列里剩下的行由服务本轮收尾时
     * 的 [com.ebook.db.dao.DownloadChapterDao.clearAll] 兜底清掉（其 KDoc 明写了这一条），
     * 属既有行为；换源没有那个兜底窗口（用户可能此后再也不发起下载），故必须在这里删。
     *
     * 本方法刻意不做任何 `withContext`：Room 的写事务线程受限，事务块里换线程就是伪事务。
     */
    private suspend fun commitSwitch(oldNoteUrl: String, newShelf: BookShelfEntity) {
        writeEntry(newShelf)
        absorbKeysInto(newShelf.noteUrl, oldNoteUrl)
        deleteEntryRows(oldNoteUrl)
        downloadChapterDao.deleteByNoteUrl(oldNoteUrl)
    }

    /**
     * 换源完成后广播两个事件：先 [BookShelfEvent.Removed]（旧条目）再 [BookShelfEvent.Added]（新条目）。
     *
     * 两个事件指向两行不同的 `note_url`，最终态与顺序无关；选「先撤旧、后补新」是给
     * **靠事件维护内存快照**的收集方看的（如搜索页的书架标记）：反过来会让它一瞬间同时持有同一作品
     * 的两个条目，按书名或评论键聚合的页面就会闪一次「重复书」。书架页本身以
     * [observeBookShelf] 为准，两个事件对它只是「该重查了」的信号。
     */
    private suspend fun publishSwitched(
        oldShelf: BookShelfEntity,
        newShelf: BookShelfEntity,
    ) {
        _bookShelfEvents.emit(BookShelfEvent.Removed(oldShelf))
        _bookShelfEvents.emit(BookShelfEvent.Added(newShelf))
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
     *
     * **书源归属跟着定位值一起下去**（见 ADR-0016）：[BookLocation] 带 `bookShelf.tag`，故网络书各按
     * 自己那本书的源抓正文。书源被删时 `JsoupSourceReader` 抛 [com.ebook.source.analyze.BookSourceNotFoundException]，
     * 本层**不吞**（仓库层吞掉就分不清「解析失败」与「没源了」），一路上抛由 ViewModel 统一处置；
     * 已缓存的章命中 reader 的读盘分支，不取 parser，因此不受影响（离线仍可读）。
     */
    suspend fun loadChapter(
        bookShelf: BookShelfEntity,
        index: Int,
        title: String,
        chapterContentRef: String = "",
    ): ChapterContent? {
        val format = resolveFormat(bookShelf)
        val reader = chapterReaders[format] ?: return null
        val location = BookLocation(bookShelf.noteUrl, format, bookShelf.tag)
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
     * 刷新单章缓存（仅网络书）：删除章文件 + 失效内存缓存，下次读取时重新从网络抓取。

     * 本地书不走网络，章文件即最终源，无需刷新。
     */
    suspend fun refreshChapter(bookShelf: BookShelfEntity, index: Int) {
        if (bookShelf.tag == BookShelfEntity.LOCAL_TAG) return
        withContext(Dispatchers.IO) {
            val format = resolveFormat(bookShelf)
            val location = BookLocation(bookShelf.noteUrl, format, bookShelf.tag)
            bookStore.deleteChapter(location, index)
            contentCache.invalidateBook(bookShelf.noteUrl)
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
        val location = BookLocation(bookShelf.noteUrl, format, bookShelf.tag)
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
            absorbKeysInto(targetNoteUrl, sourceNoteUrl)
        }

    /**
     * [absorbGroupKeys] 的不换线程版本：[switchSource] 要在同一个写事务里「先吸收旧键、后删旧条目」，
     * 而 Room 的写事务是线程受限的——事务块里再 `withContext(Dispatchers.IO)` 会跳出事务线程，
     * 留下「吸收没在事务内、删旧在事务内」这种比不加事务更糟的形态。
     */
    private suspend fun absorbKeysInto(targetNoteUrl: String, sourceNoteUrl: String) {
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
     * 另一个失败窗口在补章事务提交**之后**：[removeFromShelf] 删新条目失败（磁盘级故障）时，
     * 旧书已含补章（可用），新条目可能仍在书架或只剩残留章文件目录（后者由对账回收）。不做
     * 自动补偿——回滚已提交的补章要反向删索引行和章文件，一旦中途再失败就是两头空，恰是
     * 「先导入新条目、后处置旧条目」铁律要防的形态；自愈路径即用户手动删掉新条目（走正常
     * removeFromShelf）。
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
            // 归属显式取自各自书架条目：本方法只对本地书生效（上面已 guard），值即 LOCAL_TAG，
            // 但仍不省成空串——BookLocation 的 sourceUrl 没有默认值就是为了让每个构造点表态
            val oldLocation = BookLocation(oldNoteUrl, resolveFormat(oldShelf), oldShelf.tag)
            val newLocation = BookLocation(newNoteUrl, resolveFormat(newShelf), newShelf.tag)
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
        if (newKey == oldPrimary) {
            // 键等价（新写法归一化后同键）：键行不动，但用户输入的写法必须落库——
            // 修键面板的回显来自这两列，不写回来「改了却显示旧值」看起来就像保存失败
            persistMatchMeta(noteUrl, newMatchName, newMatchAuthor)
            return@withContext oldPrimary to newKey
        }

        // 元数据与主键是同一个 comment_key 的两半，必须同事务提交：分开写会在中途失败时
        // 留下「匹配名已改、主键仍是旧键」的静默不一致（下次导入判重比的正是旧键）
        transactions.run {
            persistMatchMeta(noteUrl, newMatchName, newMatchAuthor)
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

    /** 把用户输入的匹配写法落到 book_shelf 两列（不动 book_group 键行；行缺失则无事可做） */
    private suspend fun persistMatchMeta(noteUrl: String, matchName: String, matchAuthor: String) {
        val shelf = bookShelfDao.getBookByUrl(noteUrl) ?: return
        shelf.matchName = matchName
        shelf.matchAuthor = matchAuthor
        bookShelfDao.update(shelf)
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

/**
 * 换源要换过去的那条 `noteUrl` **本来就是书架上另一个条目**。
 *
 * 单独一档而不是含糊的 `IllegalArgumentException`：这是一个用户能立刻听懂并自己处置的情形
 * （「这本书你已经在书架上了，去读那一本即可」），而调用方只有拿到类型才给得出这句话——
 * 异常消息带着内部 URL，从不上屏（同 [com.ebook.source.analyze.BookSourceNotFoundException]）。
 *
 * 为什么必须挡（不挡就是丢数据，不是「白换一次源」）：
 * - [writeEntry] 走 [com.ebook.db.dao.BookShelfDao.insert]，那是 `OnConflictStrategy.REPLACE`
 *   且主键就是自然键 `note_url` → 那本已有书的**阅读进度被整行换成旧条目映射过来的章序号**，
 *   两本书并成一册；
 * - `chapter_list` 的主键是 `content_ref`（章节 URL），两个站的同一章主键不同，谁也不覆盖谁
 *   → 这一册里混着新旧两份目录，序号还互相撞。
 *
 * 继承 [IllegalStateException]（与 `BookSourceNotFoundException` 同一做法：只为把根因说清楚，
 * 不参与控制流分发），失败经 [BookRepository.switchSource] 的 `Result` 交回调用方。
 */
class BookAlreadyOnShelfException(
    /** 已在书架上的那条目标地址（`book_shelf.note_url`） */
    val noteUrl: String,
) : IllegalStateException("换源目标已在书架上：noteUrl=$noteUrl")
