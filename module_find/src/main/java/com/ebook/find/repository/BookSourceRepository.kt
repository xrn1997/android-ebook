package com.ebook.find.repository

import com.ebook.common.analyze.source.BookSourceManager
import com.ebook.common.analyze.source.LibraryDiskCache
import com.ebook.db.entity.LibraryEntity
import com.ebook.db.entity.SearchBookEntity
import com.ebook.find.entity.BookType
import com.ebook.source.analyze.BookParser
import com.ebook.source.analyze.BookSourceNotFoundException
import com.xrn1997.common.mvvm.model.BaseModel
import com.xrn1997.common.util.Logger
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

/**
 * 书库加载策略：同一份数据的两种要法，差异只在**缓存扮演什么角色**。
 *
 * - [StaleWhileRevalidate] 进页/换源：缓存立刻上屏（哪怕已过期），过期则在**同一次收集内**
 *   接着重抓再发一次（收集被取消，重抓一并中止——没有脱离页面生命周期的后台任务）
 *   （stale-while-revalidate，RFC 5861 的应用层形态）。书库要按分类**串行抓 N 个页面**、
 *   耗时数秒，让用户先看到上一屏、新数据到了再换，比白屏等网络好。
 * - [ForceNetwork] 下拉刷新：跳过缓存读、强制走网络、成功后覆盖回写。**下拉手势的语义
 *   契约就是「给我最新的」**——命中缓存直接返回等于刷新是假的（旧实现正是如此）。
 */
enum class LibraryLoadPolicy {
    StaleWhileRevalidate,
    ForceNetwork,
}

/**
 * 书源数据仓库：分类入口、分类书籍、书库数据。
 *
 * **三个方法都以 `sourceUrl` 为入参，本层不再猜「当前是哪个源」**（ADR-0016 P3-c）：
 * 书城的源是用户在顶部切换器上选的那个，只有 ViewModel 订阅到的那份才知道它，
 * 由这里回头读全局默认源就会出现「页面显示 B 源、请求打的是 A 源」这种两边各自取值的错配。
 *
 * ## 书库缓存策略住在本层（本仓 ADR 的核心决定）
 *
 * 旧实现把缓存读写长在解析器里（`getLibraryData(aCache, ...)`），策略散在两层：结果是
 * 下拉刷新也被缓存命中吞掉（假刷新）、SharedPreferences 缓存永不过期也清不掉。现在解析器
 * 只做纯网络解析（[BookParser.fetchLibraryData]），**要不要用缓存、何时过期、要不要回写
 * 全部收在这里**：[LibraryLoadPolicy] 两档策略 + TTL + 回写守卫，载体是 [LibraryDiskCache]
 * （cacheDir 文件缓存，可被缓存管理页的「其他」档清理）。
 *
 * [getLibraryData] 返回 Flow 而不是单值，就是为 SWR 的**双发射**留的形状：先发缓存（若有）、
 * 再发网络新结果（若过期）。调用方逐发射上屏即可，不必理解策略内部。
 */
@Singleton
class BookSourceRepository @Inject constructor(
    private val bookSourceManager: BookSourceManager,
    private val libraryDiskCache: LibraryDiskCache,
) : BaseModel() {

    /**
     * 书库缓存的有效期。
     *
     * 书库一次加载是**每源 N 个分类页的串行请求**（内置源约 6~10 次），TTL 太短等于把第三方
     * 站点当爬虫打；分类首页的内容（新书上榜/排序）以天为粒度变化，6 小时在「内容够新」与
     * 「请求克制」之间取中。调整它不需要迁移（只是改年龄判据），嫌数据旧还可以下拉强刷。
     */
    private companion object {
        private const val TAG = "BookSourceRepository"
        private const val LIBRARY_CACHE_TTL_MILLIS: Long = 6L * 60 * 60 * 1000
    }

    /**
     * 书库数据（IO 线程）：按 [policy] 决定缓存与网络的先后（两档语义见 [LibraryLoadPolicy]）。
     *
     * 发射契约（调用方逐发射上屏）：
     * - 无缓存或 [LibraryLoadPolicy.ForceNetwork]：只发网络结果一次；
     * - 有缓存且 [LibraryLoadPolicy.StaleWhileRevalidate]：先发缓存；过期时再发网络新结果
     *   （新鲜则到此为止，一个请求都不发）。
     *
     * 无源（[sourceUrl] 空白）时发一个**空实体**后正常结束（两个字段都为 null，调用方按
     * 「书库无数据」处理）：这里没有真实失败，只是「当前没有可用书源」，页面据此走引导态。
     *
     * **取 parser 先于读缓存**：书源坏没坏（[BookSourceNotFoundException]）要在每次进页时
     * 检出，不能被一份新鲜缓存掩盖——页面据此说「换源/重导」那句话（ADR-0016 决策 5）。
     * 该异常**直接抛、不先发缓存**：书城页在非 Ready 档位整片换引导语、不渲染列表，
     * 先发缓存既不可见、还会让档位先闪 Ready 再落回失效。
     *
     * @throws BookSourceNotFoundException 有源但取不到 parser（源被删或规则解码失败），
     *   与「无源」（[sourceUrl] 空白，发空实体）分开处置。判据与 [parserFor] 同，但这里**不复用**
     *   它：无源那侧要的是「发一个空实体后正常结束」，[parserFor] 给的是空值，套过来只会多一层绕
     */
    fun getLibraryData(sourceUrl: String, policy: LibraryLoadPolicy): Flow<LibraryEntity> = flow {
        if (sourceUrl.isBlank()) {
            emit(LibraryEntity())
            return@flow
        }
        val parser = bookSourceManager.getParserFor(sourceUrl)
            ?: throw BookSourceNotFoundException(sourceUrl)

        when (policy) {
            LibraryLoadPolicy.ForceNetwork -> {
                val fresh = parser.fetchLibraryData()
                emit(fresh)
                writeToCacheIfWorthCaching(sourceUrl, fresh)
            }

            LibraryLoadPolicy.StaleWhileRevalidate -> {
                val cached = libraryDiskCache.read(sourceUrl)
                if (cached != null) {
                    emit(cached.data)
                    if (System.currentTimeMillis() - cached.savedAtMillis < LIBRARY_CACHE_TTL_MILLIS) {
                        return@flow
                    }
                }
                try {
                    val fresh = parser.fetchLibraryData()
                    emit(fresh)
                    writeToCacheIfWorthCaching(sourceUrl, fresh)
                } catch (e: CancellationException) {
                    // 换源/页面销毁取消收集：必须原样上抛。吞进下面的「保留旧屏」分支会让这次
                    // 收集以「正常结束」收场，调用方分不开「重抓失败、旧屏兜住了」与「已被取消」
                    throw e
                } catch (e: Exception) {
                    if (cached == null) throw e
                    // 过期数据已经上屏：重抓失败不值得把页面打成错误态，保留旧屏即可。
                    // 没有缓存可兜底时（首拉）才把异常交给调用方
                    Logger.w(TAG, "书库重抓失败，继续展示过期缓存: sourceUrl=$sourceUrl", e)
                }
            }
        }
    }.flowOn(Dispatchers.IO)

    /**
     * 回写守卫：**至少一个分类有书**才值得缓存。
     *
     * 解析器对网络故障不抛异常（单个分类失败按空区块计入，见 `fetchLibraryData` 的接口契约），
     * 「区块在、书全空」就是站点挂了/规则失效的可见形态——这种结果一旦落缓存，坏页会被
     * TTL 冻住一个周期，期间每次进页都秒开一个空书城，比白屏更难懂。放行不缓存，
     * 下次进页自然重试。`ruleFind.kinds` 未配置（kindBooks 为 null）同样不缓存。
     */
    private fun writeToCacheIfWorthCaching(sourceUrl: String, entity: LibraryEntity) {
        val kindBooks = entity.kindBooks ?: return
        if (kindBooks.any { it.books.isNotEmpty() }) {
            libraryDiskCache.write(sourceUrl, entity)
        }
    }

    /**
     * 书籍类型列表：按 [sourceUrl] 取分类条目，空白标题过滤后映射成 [BookType]。
     *
     * 读经 [BookSourceManager.getExploreEntries]——格式路由（原生 `ruleFind.kinds` / 脚本
     * `exploreUrl` 条目）在 Manager 一处完成，本方法对两种出身**格式盲**：拿到的都是
     * 「标题 + 分类地址」，脚本条的 url 承载 URL 规则串，由对应解析器在分类页渲染。
     * 空白标题过滤保留在这里：`KindItem`/`exploreUrl` 条目的字段非空带默认值，书源规则
     * 少写字段得到空串，不过滤就会渲染出空白胶囊、并把空 url 传给分类选书页去请求。
     *
     * 空白 [sourceUrl]（当前没有可用书源）与库里已无该行（源刚被删）都返回空列表：
     * 分类入口没有内容可渲染就是正确表现，不必报错；脚本行 `rule_json` 坏掉的类型化异常
     * 由调用方（`LibraryViewModel.bookTypeList` 的 catch）按「加载失败给空列表」兜底。
     */
    suspend fun getBookTypeList(sourceUrl: String): List<BookType> =
        bookSourceManager.getExploreEntries(sourceUrl)
            .filter { it.title.isNotBlank() }
            .map { BookType(it.title, it.url) }

    /**
     * 分类书籍列表（IO 线程），[page] 从 1 开始，请求由 [sourceUrl] 那个源的 parser 发出。
     *
     * 归属由解析器写成 `rule.url`（= [sourceUrl]），所以这条链路上「用哪个源解析」与
     * 「结果 tag 是谁」天然是同一个值，下游（`markShelfStatus`、加书架）不必再传第二次。
     *
     * @throws BookSourceNotFoundException 有源但那个源取不到 parser（源被删或规则解码失败），
     *   与「无源」（[sourceUrl] 空白，返回空列表）分开处置，理由见 [parserFor]
     */
    suspend fun getKindBook(sourceUrl: String, url: String, page: Int): List<SearchBookEntity> =
        withContext(Dispatchers.IO) {
            val parser = parserFor(sourceUrl) ?: return@withContext emptyList()
            parser.getKindBook(url, page)
        }

    /**
     * 按 [sourceUrl] 取该源的 parser；取不到时两条路径分开处置：
     *
     * - **[sourceUrl] 空白 → 返回 null（「无源」）**：调用方给空数据，页面走「请先启用或导入书源」
     *   的引导态。这是 P3-a 定下的契约（旧写法在这里直接抛 IllegalStateException，页面只剩一行
     *   看不出根因的日志），保留不变；空白也照旧交给 [BookSourceManager.getParserFor] 的
     *   「空白 URL 短路」，让那条契约来兜底，不查 Room。
     * - **有源但取不到 parser → 抛 [BookSourceNotFoundException]（「该源坏了」）**：行被删或
     *   `rule_json` 解不出来（成因封闭，见 getParserFor 的 KDoc）。这种情况**不能返回空数据**：
     *   那等于把一条坏掉的源扮成「这个源本来就没书」给用户看，而他要的其实是一句
     *   「书源已失效，请重新导入或换源」。上层要能分辨这两件事，故一种给空值、一种给异常。
     *
     * 禁用中的源照样能取到 parser（`getParserFor` 不看 `enabled`）——书城切换器只列启用中的源，
     * 但「禁用不切断归属」是书源层的既定语义，这里不额外加判断。
     */
    private suspend fun parserFor(sourceUrl: String): BookParser? =
        bookSourceManager.getParserFor(sourceUrl)
            ?: if (sourceUrl.isBlank()) null else throw BookSourceNotFoundException(sourceUrl)
}
