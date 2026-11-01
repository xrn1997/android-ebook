package com.ebook.common.repository

import com.ebook.common.analyze.source.SourceResolver
import com.ebook.common.analyze.source.parserOrThrow
import com.ebook.db.entity.BookShelfEntity
import com.ebook.db.entity.SearchBookEntity
import kotlinx.coroutines.CancellationException

/**
 * 「取源建条目」的唯一主人：按书的 `tag` 取 parser、拉详情与目录，并保证新条目必带 `book_info`。
 *
 * 加书架（[BookShelfManager.addFromSearch]）与阅读中换源（[BookRepository.switchSource]）原先各自
 * 拼同一条解析链（stub → `getBookInfo` → `getChapterList`），但「新条目必带 book_info」的守卫只写在
 * 换源那一半——加书架路径遇到 parser 脏返回（`getBookInfo` 原样返回、或规则缺项没填 bookInfo）时会把
 * 「有书架行、无 `book_info`」的半截条目写进库，随后被读路径
 * [BookRepository.getAllBooksWithDetails] 当孤立记录删掉，用户看到的现象是「刚加的书凭空消失」。
 *
 * 守卫收进本类、置于**任何写之前**，两处调用共用这一份；构造方式不得分叉，否则换源拉回来的书会和
 * 「从搜索加书架」拉回来的不一样（入参实体只用 `noteUrl` + `tag` 起头，其余字段交给 parser 填）。
 *
 * 失败经异常上抛——[com.ebook.source.analyze.BookSourceNotFoundException]（源被删/坏行）、
 * [IllegalStateException]（守卫：新条目没解出 bookInfo；或本地书 tag 走了网络取源）、以及解析器自身的
 * 异常——由调用方各自 catch 转 `Result.failure`。[CancellationException] 本类不吞，原样穿透到调用方的
 * catch（取消不是「加书架/换源失败」，吞掉会让调用方把销毁中的调用当成一次失败去弹 Toast）。
 *
 * 守卫抛出的 [IllegalStateException] 的消息会被**加书架路径直接上屏**（`module_find` 的
 * `reportAddBookFailure` 对非 [com.ebook.source.analyze.BookSourceNotFoundException] 取 `e.message` 经
 * `reportFailure` → toast），故其措辞必须中性、不含「换源」或「加书架」字样——两处调用共用同一句，
 * 任何一路把它上屏都不会说错话。
 *
 * 写库不在这里：本类只负责「解析出一条合法条目」，四表落库（同一笔写事务、提交后发事件）仍归
 * [ShelfEntryStore]（经 [BookRepository.addToShelf] / [BookRepository.switchSource]）。
 */
internal class ShelfEntryFetcher(
    private val sourceResolver: SourceResolver,
) {

    /**
     * 按 [newBook] 的 `tag` 取 parser，拉详情与目录，返回一条**必带 `book_info`** 的书架条目。
     *
     * @param newBook 聚合搜索里选中的候选（或换源目标）：`tag` 是它所属书源的 URL，`noteUrl` 是详情页地址
     * @param detail 取源失败时随 [com.ebook.source.analyze.BookSourceNotFoundException] 带回的上下文
     * @param carryOver 条目解析完成、守卫通过后对新条目的一次就地改写（换源用它把旧进度映射过去）；
     *   它在守卫**之后**才被调用，故绝不会在一条脏条目上跑进度映射
     */
    suspend fun fetch(
        newBook: SearchBookEntity,
        detail: String,
        carryOver: ((BookShelfEntity) -> Unit)? = null,
    ): BookShelfEntity {
        val parser = sourceResolver.parserOrThrow(newBook.tag, detail = detail)
        val stub = BookShelfEntity().apply {
            noteUrl = newBook.noteUrl
            tag = newBook.tag
        }
        // 两次解析共用同一个 parser 实例：中途用户删了源也不至于一半新一半旧
        val withInfo = parser.getBookInfo(stub)
        val shelf = parser.getChapterList(withInfo).data
        // 新条目必须自带 book_info：写路径（ShelfEntryStore）一落库，读路径（getAllBooksWithDetails）
        // 就会把「取不到 book_info」的行当孤立记录删掉。真解析器（JsoupBookParser.getBookInfo）总会填上，
        // 这里挡的是「换了个 parser 实现、或规则缺项导致没填」这种脏返回，且挡在任何写之前——
        // 守卫失败 = 一行不写（ShelfEntryStoreTest 与两处调用方的失败用例共同钉住这条）。
        // 消息措辞中性：加书架失败会经 reportAddBookFailure 拿 e.message 直接上屏（toast），
        // 带「换源」字样会让加书架流程说错话，故两路通用、只说「书源没解出书籍信息、放弃本次写入」。
        checkNotNull(shelf.bookInfo) { "书源没有解出书籍信息，放弃本次写入：noteUrl=${newBook.noteUrl}" }
        carryOver?.invoke(shelf)
        return shelf
    }
}
