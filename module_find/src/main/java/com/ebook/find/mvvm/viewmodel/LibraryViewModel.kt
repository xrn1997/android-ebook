package com.ebook.find.mvvm.viewmodel

import androidx.lifecycle.viewModelScope
import com.ebook.api.entity.BookSourceRule
import com.ebook.api.entity.SourceDefinition
import com.ebook.common.analyze.source.BookSourceManager
import com.ebook.db.entity.LibraryKindBookListEntity
import com.ebook.find.entity.BookType
import com.ebook.find.repository.BookSourceRepository
import com.ebook.find.repository.LibraryLoadPolicy
import com.ebook.source.analyze.BookSourceNotFoundException
import com.xrn1997.common.mvvm.viewmodel.BaseRefreshViewModel
import com.xrn1997.common.util.Logger
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 书城「能不能出数据」的状态档，页面据此决定画什么、说哪句话。
 *
 * 前两档立在这里只为把两件**动作完全不同**的事分开（ADR-0016 决策 5 要求「书源已失效」要提示用户）：
 * - [NoSource]：一条启用源都没有 → 去书源管理**启用或导入**；
 * - [BrokenSource]：有源，但当前这个源解析不出来了（行被删 / `rule_json` 读不出）→
 *   在顶部**换一个源**，或到书源管理**重导**那条源。
 *
 * 把后者误报成前者最坏：用户被指去「启用书源」，可他启用的正是那条坏源，怎么点都没用。
 * 判据分别来自两条不同的事实（[BookSourceManager.observeDefaultSource] 与一次加载抛出的
 * [BookSourceNotFoundException]），合成一处由 [librarySourceStateOf] 负责，页面只读不猜。
 *
 * [Unknown] 是另一类东西：它**不是一种处境，而是「还没有结论」**——只作 [LibraryViewModel.sourceState]
 * 的首帧初值使用（见该属性的 KDoc），[librarySourceStateOf] 永不产出它。
 */
enum class LibrarySourceState {
    /** 有可用源，页面按正常书库渲染 */
    Ready,

    /** 没有任何启用中的源 */
    NoSource,

    /** 有源，但当前源取不到解析器 */
    BrokenSource,

    /**
     * 首帧「还不知道」：默认源由 Room 现算，页面组合的那一刻订阅还没回来。
     *
     * 它的唯一身份就是**首帧占位**，与「没有源」（[NoSource]）是两件事：
     * 有源用户的冷启动若拿 [NoSource] 兜底，就会先闪一帧「还没有可用书源」的引导语与导入按钮，
     * 而 Room 下一秒回来说的其实是「有源」。页面因此对 Unknown **整片留空**——既不画引导语、
     * 也不画切换器（那一刻没有源名可写，画半截切换器同样是在猜）。
     */
    Unknown,
}

/**
 * 两路事实 → 一档状态。**无源优先**：没有源时根本不会发请求，也就无所谓「这个源坏没坏」。
 *
 * 本函数**不产生 [LibrarySourceState.Unknown]**：它吃的是两路已经到手的结论，而 Unknown 表达的是
 * 「结论还没到」（见该档 KDoc）——把它当成第三个出口会让「等待中」与「确认为无源」在同一个函数里
 * 混成一件事。首帧初值由 [LibraryViewModel.sourceState] 直接给定，不流经这里。
 *
 * 独立成纯函数是为了让「两句话不混同」这件事在单测里直接可断，而不必去收集一条 StateFlow。
 */
internal fun librarySourceStateOf(currentSource: SourceDefinition?, sourceUnusable: Boolean): LibrarySourceState = when {
    currentSource == null -> LibrarySourceState.NoSource
    sourceUnusable -> LibrarySourceState.BrokenSource
    else -> LibrarySourceState.Ready
}

/**
 * 书城主 Tab VM（ADR-0016 P3-c：书城顶部书源切换器 + 书库缓存按源分区）。
 *
 * **整页现在是「响应书源」的，不再有一次性快照**：分类入口（[bookTypeList]）与书库数据
 * （[list]）都由 [currentSource] 驱动，用户在顶部切换器换一个源，两件事自动跟着换。
 * 旧写法是 `val bookTypeList = repository.getBookTypeList()` 在构造时同步读一次全局默认源——
 * 那种写法在单书源下没事，多书源下会留下两类毛病：① 切源之后分类胶囊还是上一个源的
 * （点进去拿 A 源的分类 url 让 B 源解析，得到的是不相干的书目）；② 「当前源是谁」被固化在
 * 构造期那一次读取上，用户之后在书源管理页改的东西（换默认源、禁用）不会回流到这一页。
 *
 * 三条流的分工照 [BookSourceManager] 接口的口径来：**切换器候选吃条目、当前源吃定义**——[sources]
 * 把订阅到的 [com.ebook.common.analyze.source.BookSourceItem] 当场塌成 `rule`，只挡禁用项
 * （2e 起脚本行**进**候选，脚本书源已是合法默认源，理由见 [sources]），
 * 「当前源是谁」以 [currentSource] 为唯一事实源，
 * 本 VM 不自行判空回落（回落规则住在 Manager，两处各算一份就会口径分裂）。
 *
 * 页面「该说什么」由 [sourceState] 一次给全，各档各说各的话（见 [LibrarySourceState]）：
 * 无源（[currentSource] 为 null）不发任何请求、不白屏、不无限转圈；
 * 而「有源但那个源坏了」既不是引导态也不该被说成「没源」——那是 [LibrarySourceState.BrokenSource]，
 * 用户的动作是换一个源或重导那条源。两档**不得**互相误报，判据也因此分成两路事实再合成一处。
 * 首帧则落在 [LibrarySourceState.Unknown]：那一刻两路事实都还没到手（见该档 KDoc）。
 *
 * 继承 [BaseRefreshViewModel]：书库是一份列表数据，下拉刷新走基类的刷新信号族。
 */
@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val bookSourceRepository: BookSourceRepository,
    private val bookSourceManager: BookSourceManager,
) : BaseRefreshViewModel<LibraryKindBookListEntity, BookSourceRepository>(bookSourceRepository) {

    /**
     * 切换器候选清单：**启用中的书源**（排序沿用 Manager 的 `weight ASC, addedAt ASC, url ASC`）。
     *
     * 只过滤 `enabled` 而不给禁用项打标记：切换器要回答的是「我能切到哪个源」，
     * 「有一条被禁用了」是书源管理页的事，摆进城里的下拉只会让人点了才知道切不过去。
     * （禁用中的源照样能被解析——「禁用不切断归属」，但那是书架/详情那条链路的需求，与本页无关。）
     *
     * 2e 起脚本行**进**候选：脚本书源已是合法的默认源候选（`setDefaultSource` 对它生效），
     * 「点了没反应」的成因消失。候选条目的 `rule` 对脚本行是按实体列合成的展示空壳，
     * 本页（切换器）只读它的 name/url/enabled 三件真值——**别把空壳规则当可用规则用**：
     * 需要规则内容的场合一律经 `getParserFor(url)` 拿解析器。
     *
     * [SharingStarted.Eagerly] 而非 `WhileSubscribed`：书城是主 Tab，VM 与宿主同生命周期，
     * 且这条流的值要在页面首次组合前就是热的（切换器首帧就得知道有没有得切）。
     */
    val sources: StateFlow<List<BookSourceRule>> = bookSourceManager.observeSources()
        .map { items -> items.filter { it.rule.enabled }.map { it.rule } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /**
     * 当前书源（书城一切数据的基准）；**null 表示「没有任何启用中的源」**——但这是订阅**落定后**
     * 的含义，首帧的 null 只是「还不知道」，页面判据因此不直接看这条流（见下段）。
     *
     * 初值给 null 是因为**已经没有同步初值可取了**：默认源不再随包携带、也不再有冷启动快照，
     * 唯一的出口是 Room 的 [BookSourceManager.observeDefaultSource] 订阅（首启时要走一次查库）。
     * 故首帧必然是 null，真实值由订阅尽快补上；载体是 [SourceDefinition]：默认源可以是脚本行（2e 起）。
     *
     * **首帧的 null 不等于「没有源」**：页面判据不直接看这条流，而是看 [sourceState]——它首帧是
     * [LibrarySourceState.Unknown]（整片留空），等 Room 回答后才落进 Ready / NoSource / BrokenSource。
     * 直接把 null 当「无源」画出来的话，有源用户的冷启动会先闪一帧「还没有可用书源」的误导文案。
     */
    val currentSource: StateFlow<SourceDefinition?> = bookSourceManager.observeDefaultSource()
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /**
     * 「当前这个源解析不出来」的标记（只由 [BookSourceNotFoundException] 置位）。
     *
     * 三个时点写它，缺一处就会留下说不通的话：
     * - 置位：一次书库加载抛出该异常（清单里那行的规则读不出了）；
     * - **换源即清零**：旧源坏不坏与新源无关，留着就会把新源那一屏也说成「已失效」；
     * - **加载成功也清零**：重导同一条源会让 [currentSource] 推来一个新规则而 URL 不变，
     *   那一刻不重置的话页面会永远停在失效提示上，而它下面其实已经有数据了。
     */
    private val _sourceUnusable = MutableStateFlow(false)

    /**
     * 页面「该画什么、该说哪句」的唯一判据（各档含义见 [LibrarySourceState]）。
     *
     * 由 [currentSource] 与 [_sourceUnusable] 现算，不在页面里各判一次：两路事实分开持有、
     * 合成只在这一处，才不会出现「引导态按 A 判、提示条按 B 判」。
     */
    val sourceState: StateFlow<LibrarySourceState> =
        combine(currentSource, _sourceUnusable) { source, unusable -> librarySourceStateOf(source, unusable) }
            .stateIn(
                viewModelScope,
                SharingStarted.Eagerly,
                // 初值 Unknown：书城首帧既不该闪引导态（默认源还没从 Room 回来，说了就是瞎说），
                // 也不该闪空书库。页面见 Unknown 整片留空，等上面那条 combine 落定
                LibrarySourceState.Unknown,
            )

    /**
     * 分类入口列表：随 [currentSource] 重算（不同书源的 `ruleFind.kinds` 完全不同）。
     *
     * 读的是 Room 里那一行的规则（见 [BookSourceRepository.getBookTypeList]）。
     * 读失败只记日志并给空列表：书城少一排分类胶囊仍是可用的页面，异常抛进 viewModelScope
     * 就是整个主 Tab 崩掉。
     */
    val bookTypeList: StateFlow<List<BookType>> = currentSource
        .map { source -> if (source == null) emptyList() else bookSourceRepository.getBookTypeList(source.sourceUrl) }
        .catch { e ->
            Logger.e(TAG, "分类入口加载失败: ", e)
            emit(emptyList())
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /**
     * 最近一次已发起加载的源（null = 还没拉过，或已回到无源态）。
     *
     * 只为判「这次是不是换源」：换源才先清空列表（见 [loadLibrary]），同源重拉不清，
     * 免得每次下拉刷新都闪一下空页。
     */
    private var loadedSourceUrl: String? = null

    /** 在途的书库加载；换源时取消它，避免慢的那一路把上一源的结果盖到新源的页面上 */
    private var loadJob: Job? = null

    init {
        // 数据拉取由 currentSource 驱动：首帧一次、换源一次，页面不必再自己 triggerRefresh
        viewModelScope.launch {
            currentSource.collect { source -> loadLibrary(source?.sourceUrl) }
        }
    }

    /**
     * 切换书源：只负责把目标源设为默认，剩下的由 [currentSource] 的变化回流完成
     * （换源是持久化后的订阅者先看到、加载随后跟着走，本方法不亲自去拉数据）。
     *
     * [BookSourceManager.setDefaultSource] 会顺带把禁用中的目标源启用（「默认源永不为禁用态」
     * 这条不变式的代价，见其 KDoc），本 VM 因此不需要先调 `setEnabled`、也不判断目标源的状态。
     * 目标 URL 不在清单里时 Manager 记日志忽略，页面保持原样（不会停在「点了没换」的中间态）。
     */
    fun switchSource(url: String) {
        viewModelScope.launch { bookSourceManager.setDefaultSource(url) }
    }

    /**
     * 下拉刷新：按当前源**强制走网络**（[LibraryLoadPolicy.ForceNetwork]，成功后仓库回写缓存）。
     *
     * 下拉手势的语义契约就是「给我最新的」——旧实现里这趟加载会被解析器内的缓存命中
     * 直接吞掉（转一圈拿回的还是同一份），本方法因此显式选择强刷策略，绕开缓存读。
     */
    override fun refreshData() {
        loadLibrary(currentSource.value?.sourceUrl, force = true)
    }

    /**
     * 拉取 [sourceUrl] 那个源的书库数据。三个分支：
     * - **无源（null）**：不发请求（也不该发），清空上一源残留的书目并收掉刷新态，页面渲染引导态；
     * - **换源**：先清空列表再拉。留着上一源的书目会出现「新书源名 + 旧书目」的画面，
     *   而首次拉新源要走网络（数秒），这个错配比「短暂空页」更值得避免；
     * - **同源重拉**：直接在原列表上更新。
     *
     * 缓存策略归仓库（[BookSourceRepository.getLibraryData] 的 [LibraryLoadPolicy]）：
     * 进页/换源走 SWR——缓存先上屏（秒开），过期则在这同一次收集里重抓再发一次；下拉刷新（[force]）
     * 强制网络。本方法只关心「为哪个源拉、按哪种要法、结果归谁」，并**逐发射**上屏：
     * SWR 的双发射（先缓存后新数据）在这里天然就是「先显示旧屏、再换成新屏」。
     *
     * collect 内**禁止提前 return**：非局部返回会终止整个 launch 并取消流收集，SWR 的第二次
     * 发射（重抓结果）就永远到不了。
     */
    private fun loadLibrary(sourceUrl: String?, force: Boolean = false) {
        if (sourceUrl == null) {
            loadJob?.cancel()
            loadedSourceUrl = null
            // 「没源」与「源坏了」是两件事，这一刻只可能是前者：标记必须落下，否则
            // 用户把源全禁用后看到的还是上一轮那句「当前书源已失效」，指错了动作
            _sourceUnusable.value = false
            updateList(emptyList())
            updateStopRefresh()
            return
        }
        if (loadedSourceUrl != sourceUrl) {
            updateList(emptyList())
            // 换源（或首次）：旧源坏没坏与新源无关，先回到「没有结论」
            _sourceUnusable.value = false
        }
        loadedSourceUrl = sourceUrl
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            try {
                val policy = if (force) LibraryLoadPolicy.ForceNetwork else LibraryLoadPolicy.StaleWhileRevalidate
                bookSourceRepository.getLibraryData(sourceUrl, policy).collect { value ->
                    // 能问到数据（哪怕是空）就说明这个源解得开：清掉可能残留的失效标记。
                    // SWR 双发射时这行会跑两遍，重复清零无害
                    _sourceUnusable.value = false
                    value.kindBooks
                        // 空结果不清列表（换源清空只发生在方法开头）：该源没配 ruleFind.kinds
                        // 属成功形态，收掉刷新即可，把列表清了反而像加载失败
                        ?.takeIf { it.isNotEmpty() }
                        ?.let { updateList(it) }
                    updateStopRefresh()
                }
            } catch (e: CancellationException) {
                // 因换源/销毁被取消：不该再动列表，也不该收刷新（下一轮自己会收）
                throw e
            } catch (e: BookSourceNotFoundException) {
                // 「有源但该源坏了」：清单变化与本次请求之间那一行的规则读不出了。
                // 页面必须说得出这句话（ADR-0016 决策 5）：置位后 [sourceState] 落进
                // LibrarySourceState.BrokenSource，引导区换成「换一个源 / 重导这条源」那句。
                // 刻意**不**清列表、也不进 NetworkError 覆盖层：这不是网络不通，
                // 而本页只绑了刷新信号、没绑命令通道，sendToast 到不了用户。
                // 自愈路径仍是清单本身：下一次 currentSource 变化会重选源并重拉。
                Logger.w(TAG, "书库加载跳过：当前源取不到解析器 $sourceUrl", e)
                _sourceUnusable.value = true
                updateStopRefresh()
            } catch (e: Exception) {
                updateStopRefresh()
                Logger.e(TAG, "onError: ", e)
            }
        }
    }

    override fun loadMore() {
        updateStopLoadMore(false)
    }
}
