package com.ebook.common.analyze.source

import com.ebook.db.entity.BookShelfEntity
import com.ebook.db.entity.LibraryEntity
import com.ebook.db.entity.SearchBookEntity
import com.ebook.db.entity.WebChapterEntity
import com.ebook.source.analyze.AggregateSearchEvent
import com.ebook.source.analyze.BookParser
import kotlinx.coroutines.flow.Flow

/**
 * [SourceResolver] 的内存假件，锁「按 `tag` 找 parser」这条契约。
 *
 * 本模块的用例只经取源面触达书源（`BookRepository` / `BookShelfManager` / `JsoupSourceReader`
 * 都只注入 [SourceResolver]），故只实现这一面；清单/管理/编解码那三面由真实现的
 * `BookSourceManagerImplTest` 锁，不在这里重做。同一面内测试不调的方法仍一律 `error(...)`：
 * 假件把未实现成员静默返回空值，会让「其实没走到那条路径」的断言假绿，宁可炸在测试里。
 *
 * `resolveSource` **照抄真实现的三档短路**（`loc_book` → Local、空白/库中无行 → Missing、
 * 有 parser → Found）；`Broken` 与 `Missing` 在 `parserOrThrow` 走同一异常通道，其「原生坏行」
 * 分支由 `BookSourceManagerImplTest` 锁真实现。否则用它测出来的「本地书不会被报成书源失效」是假命题——真实现返回 Local
 * 而假件返回了缓存值，被测代码的分支就白测了。module_find 另有一份同名假件：它按测试实际调用的
 * 清单/管理/编解码三面重做了一份，改动接口时两处都要跟。
 *
 * @param parsersBySourceUrl 可被 [resolveSource] 取到的 parser，键为书源 URL
 */
class FakeBookSourceManager(
    parsersBySourceUrl: Map<String, BookParser> = emptyMap(),
) : SourceResolver {

    private val parsers = LinkedHashMap(parsersBySourceUrl)

    /** [resolveSource] 收到过哪些 URL（按调用顺序）——断言「用的是这本书的归属」就靠它 */
    val parserForCalls = mutableListOf<String>()

    fun putParser(sourceUrl: String, parser: BookParser) {
        parsers[sourceUrl] = parser
    }

    override suspend fun resolveSource(sourceUrl: String): SourceBinding {
        parserForCalls += sourceUrl
        if (sourceUrl == BookShelfEntity.LOCAL_TAG) return SourceBinding.Local
        if (sourceUrl.isBlank()) return SourceBinding.Missing(sourceUrl)
        return parsers[sourceUrl]?.let { SourceBinding.Found(it) }
            ?: SourceBinding.Missing(sourceUrl)
    }

    /**
     * 聚合搜索的编排由 `BookSourceManagerImplTest` 锁真实现（配假 parser 工厂）。
     * 本假件的用例只关心「按 tag 能不能取到 parser」，不在此重做一份并发语义。
     */
    override fun searchAcross(
        keyword: String,
        page: Int,
        skipSourceUrls: Set<String>,
    ): Flow<AggregateSearchEvent> = unsupported("searchAcross")

    /** 分类条目由书城页的用例锁（`BookSourceRepository` 那两个测试）；本假件的用例不读它 */
    override suspend fun getExploreEntries(sourceUrl: String): List<SourceExploreEntry> =
        unsupported("getExploreEntries")

    private fun unsupported(who: String): Nothing =
        throw UnsupportedOperationException("FakeBookSourceManager 未实现 $who")
}

/**
 * [BookParser] 的记录型替身：记住自己被谁、用什么入参调过，返回构造时给的固定结果。
 *
 * 「这本书用的是它自己书源的 parser」这句断言，靠的就是不同 tag 配不同实例，
 * 然后看请求落到了哪个实例上（见 [FakeBookSourceManager.parserForCalls] 与本类的 [bookInfoCalls]）。
 *
 * @param ownedSourceUrl 这个 parser 代表的书源 URL，仅用于断言时说得清「是谁在解析」
 * @param chapterListData [getChapterList] 的返回载荷
 */
class RecordingBookParser(
    val ownedSourceUrl: String,
    private val bookInfoResult: BookShelfEntity = BookShelfEntity(tag = ownedSourceUrl),
    private val chapterListData: BookShelfEntity = bookInfoResult,
) : BookParser {

    val bookInfoCalls = mutableListOf<BookShelfEntity>()
    val chapterListCalls = mutableListOf<BookShelfEntity>()
    val searchCalls = mutableListOf<Pair<String, Int>>()

    override suspend fun getBookInfo(bookShelf: BookShelfEntity): BookShelfEntity {
        bookInfoCalls += bookShelf
        return bookInfoResult
    }

    override suspend fun getChapterList(bookShelf: BookShelfEntity): WebChapterEntity<BookShelfEntity> {
        chapterListCalls += bookShelf
        return WebChapterEntity(data = chapterListData, next = false)
    }

    override suspend fun searchBook(content: String, page: Int): List<SearchBookEntity> {
        searchCalls += content to page
        return emptyList()
    }

    override suspend fun getKindBook(url: String, page: Int): List<SearchBookEntity> =
        unsupported("getKindBook")

    override suspend fun fetchLibraryData(): LibraryEntity = unsupported("fetchLibraryData")

    private fun unsupported(who: String): Nothing =
        throw UnsupportedOperationException("RecordingBookParser 未实现 $who")
}
