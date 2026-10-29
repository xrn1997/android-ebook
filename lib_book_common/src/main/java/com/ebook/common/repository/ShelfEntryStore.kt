package com.ebook.common.repository

import com.ebook.common.domain.CommentKey
import com.ebook.common.store.WriteTransactionRunner
import com.ebook.db.dao.BookGroupDao
import com.ebook.db.dao.BookInfoDao
import com.ebook.db.dao.BookShelfDao
import com.ebook.db.dao.ChapterListDao
import com.ebook.db.entity.BookGroupEntity
import com.ebook.db.entity.BookShelfEntity
import com.ebook.db.entity.ChapterListEntity
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * 「一个书架条目」的库内事实源：四张表由哪些行组成、何时提交、何时发事件。
 *
 * 加书架、换源、导入、智能合并原先各自拼这份清单：`BookRepository.writeEntry` 与
 * `LocalBookImporter` 各写一遍四表，事件也分两条路径发。这里收成一处后，新增附属表
 * 或调整提交顺序只改这个模块；调用方只表达「加 / 替换 / 删 / 补尾」的意图。
 *
 * 事务边界由本类拥有：每个公开方法只开一笔写事务，事件一律在 `transactions.run`
 * 返回之后发——未提交的写不是事实。
 */
internal class ShelfEntryStore(
    private val bookShelfDao: BookShelfDao,
    private val bookInfoDao: BookInfoDao,
    private val chapterListDao: ChapterListDao,
    private val bookGroupDao: BookGroupDao,
    private val transactions: WriteTransactionRunner,
    private val events: MutableSharedFlow<BookShelfEvent>,
) {

    suspend fun add(entry: BookShelfEntity) {
        transactions.run { writeEntry(entry) }
        events.emit(BookShelfEvent.Added(entry))
    }

    /**
     * 换源：同一笔事务里插新条目、吸收旧条目的评论键、删旧条目，并执行调用方的
     * [extraWrite]（换源用于在同一笔事务内清旧下载队列）。
     *
     * 顺序与 ADR-0016 一致：先插新、后删旧，最坏是两条目并存而不是丢书；
     * 吸收必须先于删旧，否则旧条目历次合并攒下的 secondary 键会随行一起消失。
     */
    suspend fun replaceRows(
        newEntry: BookShelfEntity,
        oldEntry: BookShelfEntity,
        extraWrite: suspend () -> Unit,
    ) {
        transactions.run {
            writeEntry(newEntry)
            absorbKeysInto(newEntry.noteUrl, oldEntry.noteUrl)
            deleteEntryRows(oldEntry.noteUrl)
            // 换源特有的旧队列清理必须与四表同笔提交；否则删队列失败会让调用方以为换源没成，
            // 而新条目其实已经落库。
            extraWrite()
        }
        events.emit(BookShelfEvent.Removed(oldEntry))
        events.emit(BookShelfEvent.Added(newEntry))
    }

    /** 从书架移除时的库内半边；章文件与内存缓存由调用方清理。 */
    suspend fun remove(entry: BookShelfEntity) {
        transactions.run { deleteEntryRows(entry.noteUrl) }
        events.emit(BookShelfEvent.Removed(entry))
    }

    /**
     * 清理一条「有 `book_shelf`、没有 `book_info`」的孤立条目。
     *
     * 与 [remove] 一样删四表，但**不发事件**：这条记录从未作为有效条目出现在书架上，
     * 书架读面本来就会把它过滤掉；发 `Removed` 反而会让监听方以为用户删了一本书。
     * 由 [com.ebook.common.repository.BookRepository.getAllBooksWithDetails] 的对账调用。
     *
     * **删之前在事务内复核仍是孤立**：对账的读面（`getAllBooksFullInfo`）与这次删除之间，
     * 加书架可能刚把四表补齐——不复核就会把刚落库的整条目连书带目录一起删掉。
     */
    suspend fun deleteOrphan(noteUrl: String) {
        transactions.run {
            if (bookInfoDao.getBookInfoByUrl(noteUrl) != null) return@run
            deleteEntryRows(noteUrl)
        }
    }

    /**
     * 智能合并收尾：把补进旧书的尾部章节与新条目的四表删除收在同一笔事务里。
     *
     * 章文件已由调用方先写好（先文件、后库，见 [BookRepository.mergeTailChapters]）；
     * [tail] 的 `noteUrl` 必须已指向旧书，删除只按 [newEntry] 的 `noteUrl` 走。
     */
    suspend fun appendTailAndRemove(
        newEntry: BookShelfEntity,
        tail: List<ChapterListEntity>,
    ) {
        transactions.run {
            if (tail.isNotEmpty()) chapterListDao.insertAll(tail)
            deleteEntryRows(newEntry.noteUrl)
        }
        events.emit(BookShelfEvent.Removed(newEntry))
    }

    suspend fun absorbGroupKeys(targetNoteUrl: String, sourceNoteUrl: String) {
        transactions.run { absorbKeysInto(targetNoteUrl, sourceNoteUrl) }
    }

    /**
     * 写「一个书架条目」的全部库内行：book_info → book_shelf → chapter_list → book_group 主键行。
     *
     * 本方法不开事务、不发事件；调用方负责事务边界（见各公开方法）。
     */
    private suspend fun writeEntry(bookShelf: BookShelfEntity) {
        // 先保存 bookInfo（如果存在）。章节时间戳顺手写成「现在」：调用方都在刚才那次解析里
        // 抓过目录了，不写就等于让用户紧接着进详情页时白爬一遍多页目录。已有非零值不覆盖，
        // 保留「上次得出结论」的事实。
        bookShelf.bookInfo?.let { bookInfo ->
            bookInfo.noteUrl = bookShelf.noteUrl
            if (bookInfo.finalRefreshData == 0L) {
                bookInfo.finalRefreshData = System.currentTimeMillis()
            }
            bookInfoDao.insert(bookInfo)
        }
        bookShelfDao.insert(bookShelf)
        val chapters = bookShelf.chapterList
        if (chapters.isNotEmpty()) {
            chapters.forEach { it.noteUrl = bookShelf.noteUrl }
            chapterListDao.insertAll(chapters)
        }
        // book_group 关联行：用当前匹配信息算评论键，本地书导入器不再自己写一份；
        // REPLACE 语义保证幂等。网络书此前不写，这里补上，M2 并集读才有数据可查。
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

    /**
     * 删「一个书架条目」的全部库内行：chapter_list → book_info → book_group → book_shelf。
     *
     * 本表库无外键级联（见 BookShelfDao.deleteByUrl 的说明），少删一行就是永久孤儿行；
     * 新增条目附属表时这里与 [writeEntry] 要同时跟。
     */
    private suspend fun deleteEntryRows(noteUrl: String) {
        chapterListDao.deleteChaptersForBook(noteUrl)
        bookInfoDao.deleteByUrl(noteUrl)
        bookGroupDao.deleteFor(noteUrl)
        bookShelfDao.deleteByUrl(noteUrl)
    }

    private suspend fun absorbKeysInto(targetNoteUrl: String, sourceNoteUrl: String) {
        bookGroupDao.getAllForNoteUrl(sourceNoteUrl).forEach { row ->
            bookGroupDao.addSecondary(
                BookGroupEntity(commentKey = row.commentKey, noteUrl = targetNoteUrl, isPrimary = false)
            )
        }
    }
}
