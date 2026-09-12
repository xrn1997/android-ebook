package com.ebook.source.analyze

import com.ebook.db.entity.BookShelfEntity
import com.ebook.db.entity.LibraryEntity
import com.ebook.db.entity.SearchBookEntity
import com.ebook.db.entity.WebChapterEntity

/**
 * 书源解析器接口
 * 根据 BookSourceRule 规则解析 HTML，支持搜索、书籍信息、章节列表、分类书籍、书库数据
 */
interface BookParser {
    suspend fun searchBook(content: String, page: Int): List<SearchBookEntity>
    suspend fun getBookInfo(bookShelf: BookShelfEntity): BookShelfEntity
    suspend fun getChapterList(bookShelf: BookShelfEntity): WebChapterEntity<BookShelfEntity>
    suspend fun getKindBook(url: String, page: Int): List<SearchBookEntity>

    /**
     * 拉取本源的书库数据（书城首屏各分类书目）——**纯网络解析，不碰缓存**。
     *
     * 按 `ruleFind.kinds` 逐分类抓首页并拼装；该源没配 kinds 时返回空实体（`kindBooks` 为 null），
     * 由调用方按「书库无数据」处理。缓存读什么、何时过期、要不要回写，全部是编排层
     * （`module_find` 的 `BookSourceRepository`）的策略——解析器不知道缓存的存在，也就不可能出现
     * 「下拉刷新被缓存吞掉」这类刷新语义在两层之间说不清的问题。
     *
     * 网络故障在实现里**不抛异常**：单个分类抓取失败按空区块计入结果（成因与理由见
     * `JsoupBookParser.fetchLibraryData` 的 KDoc），「全部分类都空」就是调用方可见的失败形态。
     */
    suspend fun fetchLibraryData(): LibraryEntity
}
