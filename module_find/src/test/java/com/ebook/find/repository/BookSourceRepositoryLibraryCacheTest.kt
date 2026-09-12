package com.ebook.find.repository

import com.ebook.api.entity.BookSourceRule
import com.ebook.api.entity.SourceFormat
import com.ebook.common.analyze.source.BookSourceManager
import com.ebook.common.analyze.source.LibraryDiskCache
import com.ebook.common.analyze.source.SourceExploreEntry
import com.ebook.db.entity.BookShelfEntity
import com.ebook.db.entity.LibraryEntity
import com.ebook.db.entity.LibraryKindBookListEntity
import com.ebook.db.entity.SearchBookEntity
import com.ebook.source.analyze.BookParser
import com.ebook.source.analyze.BookSourceNotFoundException
import java.nio.file.Files
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [BookSourceRepository] 书库缓存策略的测试：TTL / SWR 双发射 / 强刷绕缓存 / 回写守卫 /
 * 按源分区 / 取消原样上抛。
 *
 * 这是缓存重构后的**策略主战场**——解析器已不碰缓存（`BookParser.fetchLibraryData` 纯网络），
 * 「读不读、何时过期、要不要回写」全部住进仓库，也全部在这里锁。仓库配**真的**
 * [LibraryDiskCache]（每测独立临时目录，`write` 的 `savedAtMillis` 接缝用来造确定年龄的条目），
 * 假 parser 只负责「被没被调、回什么、要不要炸、要不要演取消」四件事。
 *
 * 真解析器对网络故障**不抛异常**（按空区块兜底），本组用 [FakeParser.failNext] 人为触发异常，
 * 锁的是仓库层对「fetch 抛了」这件事的处置（强刷失败保留旧列表 / 首拉失败上抛）——
 * 这层语义与解析器如何兜底无关。
 *
 * [FakeParser.cancelNext] 同理：真机上的取消来自 ViewModel 换源时的 `loadJob?.cancel()`，
 * 会打在 `fetchLibraryData` 内部的挂起点上，单测够不着那个时机，就直接抛同一个异常类型，
 * 锁住仓库里两条 catch 的顺序（取消必须先于「保留旧屏」的吞异常分支）。
 */
class BookSourceRepositoryLibraryCacheTest {

    private companion object {
        const val SOURCE_A = "https://a.example"
        const val SOURCE_B = "https://b.example"

        /** 比 TTL（6 小时）更老的写入时刻：用它造「已过期」条目 */
        val SEVEN_HOURS_AGO: Long = System.currentTimeMillis() - 7L * 60 * 60 * 1000
    }

    /** 一份带 marker 的最小书库数据：kindName/书名都带 marker，断言「这一屏来自谁」一眼可辨 */
    private fun libraryOf(marker: String) = LibraryEntity().apply {
        kindBooks = listOf(
            LibraryKindBookListEntity(
                "分类@$marker",
                "/kind",
                listOf(SearchBookEntity(noteUrl = "$marker/book/1", name = "书@$marker", tag = marker)),
            )
        )
    }

    /**
     * 可编程的假 parser：[result] 是下一次 fetch 的返回，[failNext] 置位则下一次抛异常，
     * [cancelNext] 置位则下一次抛 [CancellationException]（收集被取消的形态），
     * [calls] 记录被调次数——「缓存新鲜时还发不发网络」就靠它断言。
     */
    private class FakeParser(private val sourceUrl: String) : BookParser {
        var result: LibraryEntity = LibraryEntity()
        var failNext = false
        var cancelNext = false
        var calls = 0
            private set

        override suspend fun fetchLibraryData(): LibraryEntity {
            calls++
            if (cancelNext) {
                cancelNext = false
                throw CancellationException("模拟收集被取消: $sourceUrl")
            }
            if (failNext) {
                failNext = false
                throw IllegalStateException("模拟解析器故障: $sourceUrl")
            }
            return result
        }

        private fun unsupported(who: String): Nothing =
            throw UnsupportedOperationException("FakeParser 未实现 $who")

        override suspend fun searchBook(content: String, page: Int): List<SearchBookEntity> =
            unsupported("searchBook")

        override suspend fun getBookInfo(bookShelf: BookShelfEntity): BookShelfEntity =
            unsupported("getBookInfo")

        override suspend fun getChapterList(bookShelf: BookShelfEntity) = unsupported("getChapterList")

        override suspend fun getKindBook(url: String, page: Int): List<SearchBookEntity> =
            unsupported("getKindBook")
    }

    /** 只回答「这个源有没有 parser」的最小假件：`getParserFor` 照抄空白短路，缺项即「源坏了」 */
    private class FakeManager(private val parsers: Map<String, BookParser>) : BookSourceManager {
        val parserForCalls = mutableListOf<String>()

        override suspend fun getParserFor(sourceUrl: String): BookParser? {
            parserForCalls += sourceUrl
            if (sourceUrl.isBlank()) return null
            return parsers[sourceUrl]
        }

        private fun unsupported(who: String): Nothing =
            throw UnsupportedOperationException("FakeManager 未实现 $who")

        override suspend fun getSourceByUrl(url: String): BookSourceRule? = unsupported("getSourceByUrl")
        override suspend fun getAllSources(): List<BookSourceRule> = unsupported("getAllSources")
        override suspend fun getEnabledSources(): List<BookSourceRule> = unsupported("getEnabledSources")
        override suspend fun addSource(rule: BookSourceRule): Result<Unit> = unsupported("addSource")
        override suspend fun addScriptSource(rawJson: String): Result<Unit> = unsupported("addScriptSource")
        override suspend fun getFormatByUrl(url: String): SourceFormat? = unsupported("getFormatByUrl")
        override suspend fun getExploreEntries(sourceUrl: String): List<SourceExploreEntry> =
            unsupported("getExploreEntries")
        override suspend fun removeSource(url: String): Result<Unit> = unsupported("removeSource")
        override suspend fun setEnabled(url: String, enabled: Boolean) = unsupported("setEnabled")
        override suspend fun setDefaultSource(url: String) = unsupported("setDefaultSource")
        override fun searchAcross(keyword: String, page: Int, skipSourceUrls: Set<String>) =
            unsupported("searchAcross")
        override fun observeSources() = unsupported("observeSources")
        override fun observeDefaultSource() = unsupported("observeDefaultSource")
        override fun importFromJson(jsonStr: String): BookSourceRule? = unsupported("importFromJson")
        override fun exportToJson(rule: BookSourceRule): String = unsupported("exportToJson")
    }

    private class Fixture(
        val manager: FakeManager,
        val cache: LibraryDiskCache,
        val repository: BookSourceRepository,
        val parsers: Map<String, FakeParser>,
    )

    private fun fixture(vararg parserUrls: String): Fixture {
        val parsers = parserUrls.associateWith { FakeParser(it) }
        val manager = FakeManager(parsers)
        val cache = LibraryDiskCache(
            Files.createTempDirectory("repo-library-cache").toFile().apply { deleteOnExit() }
        )
        return Fixture(manager, cache, BookSourceRepository(manager, cache), parsers)
    }

    @Test
    fun `无缓存时走网络解析并把结果回写缓存`() {
        val f = fixture(SOURCE_A)
        val network = libraryOf("网络")
        f.parsers.getValue(SOURCE_A).result = network

        val emissions = runBlocking {
            f.repository.getLibraryData(SOURCE_A, LibraryLoadPolicy.StaleWhileRevalidate).toList()
        }

        assertEquals("没有缓存就只有网络那一次发射", 1, emissions.size)
        assertEquals(network.kindBooks, emissions.single().kindBooks)
        assertEquals("parser 被调过一次", 1, f.parsers.getValue(SOURCE_A).calls)
        assertEquals(
            "有内容的成功结果要回写：下次进页才有得秒开",
            network.kindBooks,
            f.cache.read(SOURCE_A)?.data?.kindBooks,
        )
    }

    @Test
    fun `缓存新鲜时不发网络请求`() {
        val f = fixture(SOURCE_A)
        val cached = libraryOf("缓存")
        f.cache.write(SOURCE_A, cached)

        val emissions = runBlocking {
            f.repository.getLibraryData(SOURCE_A, LibraryLoadPolicy.StaleWhileRevalidate).toList()
        }

        assertEquals("新鲜缓存只发一次（缓存本身）", 1, emissions.size)
        assertEquals(cached.kindBooks, emissions.single().kindBooks)
        assertEquals(
            "TTL 内一个请求都不发——书库一次加载是每源 N 个分类页的串行请求，这是省下的整串",
            0,
            f.parsers.getValue(SOURCE_A).calls,
        )
    }

    @Test
    fun `缓存过期时先发缓存再发网络新结果并回写`() {
        val f = fixture(SOURCE_A)
        val stale = libraryOf("旧屏")
        val fresh = libraryOf("新屏")
        // savedAtMillis 接缝造「已过期」条目（7 小时前 > TTL 6 小时）
        f.cache.write(SOURCE_A, stale, savedAtMillis = SEVEN_HOURS_AGO)
        f.parsers.getValue(SOURCE_A).result = fresh

        val emissions = runBlocking {
            f.repository.getLibraryData(SOURCE_A, LibraryLoadPolicy.StaleWhileRevalidate).toList()
        }

        assertEquals("SWR 双发射：先旧屏秒开，再新屏顶上", 2, emissions.size)
        assertEquals("第一发是缓存（用户先看到旧数据，而不是白屏等网络）", stale.kindBooks, emissions[0].kindBooks)
        assertEquals("第二发是网络新结果", fresh.kindBooks, emissions[1].kindBooks)
        assertEquals(
            "重抓成功要覆盖回写：过期时刻被刷新，下次进页重新开始计 TTL",
            fresh.kindBooks,
            f.cache.read(SOURCE_A)?.data?.kindBooks,
        )
    }

    @Test
    fun `缓存过期且网络失败时只保留缓存不发异常`() {
        val f = fixture(SOURCE_A)
        val stale = libraryOf("旧屏")
        f.cache.write(SOURCE_A, stale, savedAtMillis = SEVEN_HOURS_AGO)
        f.parsers.getValue(SOURCE_A).failNext = true

        val emissions = runBlocking {
            f.repository.getLibraryData(SOURCE_A, LibraryLoadPolicy.StaleWhileRevalidate).toList()
        }

        assertEquals("旧屏已上屏，重抓失败不该再发第二个（错误）状态", 1, emissions.size)
        assertEquals(stale.kindBooks, emissions.single().kindBooks)
        assertEquals(
            "失败不回写：过期缓存原样保留（没被空结果覆盖），下次进页还能重试",
            stale.kindBooks,
            f.cache.read(SOURCE_A)?.data?.kindBooks,
        )
    }

    @Test
    fun `SWR 重抓遇取消时原样上抛不被吞成成功`() {
        val f = fixture(SOURCE_A)
        val stale = libraryOf("旧屏")
        f.cache.write(SOURCE_A, stale, savedAtMillis = SEVEN_HOURS_AGO)
        // 真机上取消来自 ViewModel 换源时的 loadJob?.cancel()，打在 fetchLibraryData 内部的挂起点
        // 上；这里让假 parser 直接抛同一个异常，锁的是仓库里两条 catch 的**顺序**（见其 KDoc）
        f.parsers.getValue(SOURCE_A).cancelNext = true

        val emissions = mutableListOf<LibraryEntity>()
        val thrown: Throwable? = try {
            runBlocking {
                f.repository.getLibraryData(SOURCE_A, LibraryLoadPolicy.StaleWhileRevalidate)
                    .collect { emissions += it }
            }
            null
        } catch (e: Throwable) {
            e
        }

        assertTrue(
            "取消必须原样上抛。若它落进下面那条「保留旧屏」的吞异常分支，本次收集会以「正常结束」"
                + "收场，调用方就再也分不开「重抓失败、旧屏兜住了」与「这次加载已被取消」两件事",
            thrown is CancellationException,
        )
        assertEquals("取消前秒开那一发照常发出", 1, emissions.size)
        assertEquals(stale.kindBooks, emissions.single().kindBooks)
    }

    @Test
    fun `无缓存且网络失败时抛异常`() {
        val f = fixture(SOURCE_A)
        f.parsers.getValue(SOURCE_A).failNext = true

        // 没有缓存可兜底时，异常必须交给调用方（VM 据此收掉刷新态并记日志），
        // 吞掉的话页面会停在「转圈永不停」或「以为加载完了其实是空的」
        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                f.repository.getLibraryData(SOURCE_A, LibraryLoadPolicy.StaleWhileRevalidate).toList()
            }
        }
        assertNull("失败结果不落缓存", f.cache.read(SOURCE_A))
    }

    @Test
    fun `强制网络时跳过缓存读并在成功后覆盖回写`() {
        val f = fixture(SOURCE_A)
        val cached = libraryOf("旧屏")
        val fresh = libraryOf("强刷新屏")
        // 缓存故意写成**新鲜**的：强刷的意义就在于「哪怕缓存还新鲜也要给我最新的」
        f.cache.write(SOURCE_A, cached)
        f.parsers.getValue(SOURCE_A).result = fresh

        val emissions = runBlocking {
            f.repository.getLibraryData(SOURCE_A, LibraryLoadPolicy.ForceNetwork).toList()
        }

        assertEquals("强刷只有网络那一次发射——先发缓存等于刷新是假的（旧实现的病根）", 1, emissions.size)
        assertEquals(fresh.kindBooks, emissions.single().kindBooks)
        assertEquals("新鲜缓存挡不住强刷的网络请求", 1, f.parsers.getValue(SOURCE_A).calls)
        assertEquals(
            "强刷成功覆盖回写：缓存与用户亲眼确认过的新屏对齐",
            fresh.kindBooks,
            f.cache.read(SOURCE_A)?.data?.kindBooks,
        )
    }

    @Test
    fun `全空分类的结果不回写缓存`() {
        val f = fixture(SOURCE_A)
        // 「区块在、书全空」= 解析器对网络故障的兜底形态（它不抛异常）
        f.parsers.getValue(SOURCE_A).result = LibraryEntity().apply {
            kindBooks = listOf(LibraryKindBookListEntity("玄幻", "/xuanhuan", emptyList()))
        }

        val emissions = runBlocking {
            f.repository.getLibraryData(SOURCE_A, LibraryLoadPolicy.ForceNetwork).toList()
        }

        assertEquals("结果照常发射（页面显示空区块是诚实的失败形态）", 1, emissions.size)
        assertNull(
            "全空结果不落缓存：站点挂了/规则失效的那一屏冻进 TTL，用户每次进页都秒开一个空书城，"
                + "比白屏更难懂",
            f.cache.read(SOURCE_A),
        )
    }

    @Test
    fun `源坏了时书库流抛失效异常且不先发缓存`() {
        // manager 里没有 SOURCE_A 的 parser：模拟「行还在但规则读不出」
        val f = fixture(SOURCE_B)
        f.cache.write(SOURCE_A, libraryOf("A 的缓存"))

        val emissions = mutableListOf<LibraryEntity>()
        assertThrows(BookSourceNotFoundException::class.java) {
            runBlocking {
                f.repository.getLibraryData(SOURCE_A, LibraryLoadPolicy.StaleWhileRevalidate)
                    .collect { emissions += it }
            }
        }

        assertTrue(
            "坏源不许先发缓存：书城页在失效档整片换引导语不渲染列表，先发缓存不可见，"
                + "还会让档位先闪 Ready 再落回失效",
            emissions.isEmpty(),
        )
    }

    @Test
    fun `书库缓存按入参源分区`() {
        val f = fixture(SOURCE_A, SOURCE_B)
        val aData = libraryOf(SOURCE_A)
        val bData = libraryOf(SOURCE_B)
        f.parsers.getValue(SOURCE_A).result = aData
        f.parsers.getValue(SOURCE_B).result = bData

        // A 先加载一次，把 A 的书目写进缓存（新鲜）
        runBlocking { f.repository.getLibraryData(SOURCE_A, LibraryLoadPolicy.StaleWhileRevalidate).toList() }

        // B 走 SWR：若分区失效（共用一个 key），B 会读到 A 的新鲜缓存——
        // 既不会调 B 的 parser，还会把 A 的书目当成 B 的发出去
        val emissions = runBlocking {
            f.repository.getLibraryData(SOURCE_B, LibraryLoadPolicy.StaleWhileRevalidate).toList()
        }

        assertEquals("B 没有缓存可发，只有网络那一次", 1, emissions.size)
        assertEquals("B 的屏是 B 自己的书目", bData.kindBooks, emissions.single().kindBooks)
        assertEquals("B 的 parser 真的被调过（不是拿 A 的缓存充数）", 1, f.parsers.getValue(SOURCE_B).calls)
        assertNotNull("A 的缓存还在自己的槽里", f.cache.read(SOURCE_A))
    }
}
