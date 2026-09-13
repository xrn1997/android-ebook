package com.ebook.book.reader

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.ebook.book.R
import com.ebook.db.event.DBCode
import com.xrn1997.common.util.ToastUtil
import kotlinx.coroutines.CoroutineScope
import kotlin.math.abs

/**
 * 连续滚动列表里的一项：一章的标题，或某章的某个正文块。
 *
 * 章与章在列表里**首尾相接**（本章末块的下一项就是下一章标题），故「跨章」不是一种特殊状态，
 * 只是滚动经过了一个标题项。标题项是它那一章的入口，进度口径上等价于该章第 0 块
 * （见 [ReaderScrollController.anchorBlock]）。
 */
sealed interface ScrollItem {
    val chapterIndex: Int

    /** 章标题项：一章只有一项，随内容滚走 */
    data class Title(override val chapterIndex: Int) : ScrollItem

    /** 正文块项：高恰为一屏（排版实测值），块与块精确无缝拼接 */
    data class Block(override val chapterIndex: Int, val blockIndex: Int) : ScrollItem
}

/**
 * 一次「滚到这里」的要求。
 *
 * [token] 每次要求都自增：同一个落点可能要求两次（跳章时先落章首占位、块数落定后再落到
 * 真正的末块），只按 [itemIndex] 当 key 的话第二次不会触发容器重新滚动。
 * [animate] 区分两种来路——点击/按键滚一屏是动画滚动，跳章与冷启动恢复是瞬移。
 */
data class ScrollJump(val itemIndex: Int, val animate: Boolean, val token: Int)

/**
 * 一章在连续列表里占的段：1 个标题项 + [blockCount] 个块项。
 *
 * `blockCount == 0` 表示这一章的排版还没落定（正文未取回或尚未分块），此时段内只占
 * **1 个占位块**——列表不会因此断开，用户滚过去看到的是加载态/错误态而不是「没有下一章」。
 */
private data class ChapterSection(val chapterIndex: Int, val blockCount: Int) {
    /** 段内 item 数：标题恒在，块数未落定时按 1 个占位块算 */
    val itemCount: Int get() = 1 + blockCount.coerceAtLeast(1)
}

/**
 * 上下滚屏模式的控制器：**整本书的一条连续 item 列表** + 滚动落点 + 进度回报。
 *
 * 与 [ReaderPagerController] 是并列的两个前端，共用 [ReaderPageStore]（加载去重与三态）
 * 与 `loadPage`（分块链），差别只在几何：那边是三页窗口 + 横向拖拽，这边是跨章连续滚动。
 * 两边都上报同一个 `onProgress(chapterIndex, pageIndex)`，因此进度落库口径完全一致
 * （`dur_chapter_page` = 章内第几屏），换翻页方式不必改 schema。
 *
 * ## 列表模型
 *
 * 列表按**章段**（[ChapterSection]）拼成，段内是「标题项 + 块项」，扁平序号与
 * `(章, 块)` 双向映射（[itemAt] / [flatIndexOf]）。章段是**惰性物化**的：进入某章即物化
 * 它的相邻两章（[materializeNeighbors]），于是用户滚到章界时下一章早已在列表里、
 * 排版也早已落定，跨章不需要任何「切章」动作。物化只增不减（章段是纯元数据，
 * 一段两个 int），正文才按章距裁剪（[prune]）。
 *
 * ## 锚点存语义值，不存扁平序号
 *
 * 某章的块数要等它的正文取回并排版完才知道，所以「锚点上方插入 item」是常态
 * （相邻章由 1 个占位块展开成 N 块）。扁平序号会随之整体平移，故 [anchorItem] 存的是
 * `(章, 块)` 而不是序号——否则一次上方展开就会让进度整体偏移。
 *
 * 视觉位置由 **LazyColumn 的按 key 锚定**保住：只要 item 带稳定 key（[itemKey]），
 * 在首个可见项之上增删 item 时 Compose 会在 measure 阶段按 key 找回原锚点项
 * （`LazyListScrollPosition.updateScrollPositionIfTheFirstItemWasMoved`），画面不动、
 * 也不需要手写 `scrollToItem(anchor + delta)` 补偿。两条随之而来的硬约束：
 * **key 必须全局唯一**（重复 key 会锚到另一项，位置静默跳变），
 * **单次上方插入不得超过约 100 项**（key→index 映射只索引锚点附近
 * `[30*(i/30)-100, 30*(i/30)+130)` 这个窗口，超窗即锚定失败且不会自愈）。
 * 一章通常 5～30 块，远在窗内；真要支持超长章（上百屏）得改成分批插入。
 *
 * ## 加载键一律是具体块号，不用哨兵
 *
 * 与翻页前端不同：翻页的「当前页」是一把键，哨兵键与真实页键在它那里是同一件事；
 * 而滚屏按真实块号渲染，哨兵键（-1/-2）永远不会被读到——那样首屏会停在加载态等到
 * 天荒地老。故块数未知时先探第 0 块（加载器按具体页号处理，同样回出整章的 `pageAll`），
 * 块数落定后再把哨兵落点解析成具体块号并补发它的加载。
 */
class ReaderScrollController internal constructor(
    private val scope: CoroutineScope,
    private val context: Context,
    private val chapterSize: () -> Int,
    private val chapterTitle: (Int) -> String,
    loadPage: suspend (chapterIndex: Int, pageIndex: Int) -> ReaderPageUi.Loaded?,
    private val onProgress: (chapterIndex: Int, pageIndex: Int) -> Unit,
) {

    /** 已物化的章段，按章索引升序且连续（只在 [materialize] 里插入） */
    private val sections = mutableStateListOf<ChapterSection>()

    /**
     * 当前读到的项（语义值，理由见类 KDoc）。
     *
     * 容器把它换算成扁平序号去滚动；进度上报与预取都以它给出的 `(章, 块)` 为基准。
     */
    var anchorItem: ScrollItem by mutableStateOf(ScrollItem.Block(0, 0))
        private set

    /** 当前章索引 */
    val anchorChapter: Int get() = anchorItem.chapterIndex

    /** 当前读到的块号；标题项算该章第 0 块（它就是一章的入口） */
    val anchorBlock: Int get() = (anchorItem as? ScrollItem.Block)?.blockIndex ?: 0

    /** 要求容器滚到的位置；null = 还没有落点（排版未落定且尚未跳转过） */
    var jump: ScrollJump? by mutableStateOf(null)
        private set

    private var jumpToken = 0

    /** 尚未解析的落点（可能是哨兵），所属章的块数落定后解析进 [anchorItem] */
    private var pendingTarget: Int = DBCode.BookContentView.DUR_PAGE_INDEX_BEGIN
    private var pendingChapter: Int = 0

    // 仓库必须声明在状态属性之后：它的 onLoaded 直接读写这些状态，
    // 反过来（先声明 store）会形成属性初始化顺序上的循环依赖
    private val store: ReaderPageStore = ReaderPageStore(scope, loadPage).apply {
        onLoaded = onLoaded@{ key, loaded ->
            val index = sections.indexOfFirst { it.chapterIndex == key.chapterIndex }
            if (index < 0) return@onLoaded
            // 块数只在首次落定时变；同章内后续块的 pageAll 必然相同（同一份排版），
            // 故这里早退既省一次重算，也避免「迟到的加载把用户拽回落点」
            if (sections[index].blockCount == loaded.pageAll) return@onLoaded
            sections[index] = sections[index].copy(blockCount = loaded.pageAll)
            if (key.chapterIndex != pendingChapter) return@onLoaded
            // 落点是哨兵（END）或越界页号时，此刻才知道它具体是第几块。落定前只能暂记章首，
            // 故「变了」必须补报一次进度：不然跳章到章末会把 dur_chapter_page 存成 0，
            // 冷启动重进又回到章首（块数对而落点错）
            val resolved = resolveTarget(pendingTarget, loaded.pageAll)
            val item = itemForBlock(pendingChapter, resolved)
            moveTo(item, reportProgress = !isSameScreen(item))
            requestJump(item, animate = false)
            // 探测块（0）通常就是要落的那块，此时这条 ensureLoad 被 Loaded 短路挡下；
            // 落点在章末时它会真正补发末块的加载
            store.ensureLoad(ReaderPageKey(pendingChapter, resolved))
        }
    }

    /** 列表 item 总数（容器 `items(count = ...)` 用它） */
    val itemCount: Int get() = sections.sumOf { it.itemCount }

    /** 已物化的章索引，升序（章段只增不减，故这是「往回滚不必重新排版」的范围） */
    val materializedChapters: List<Int> get() = sections.map { it.chapterIndex }

    /**
     * 扁平序号 → 列表项；越界返回 null。
     *
     * 每次线性扫章段：段数是「本次会话读过的章数」，扫一遍是几十个整数加法，
     * 换成缓存前缀和就要在每处改动章段后同步失效，不划算。
     */
    fun itemAt(flatIndex: Int): ScrollItem? {
        if (flatIndex < 0) return null
        var rest = flatIndex
        for (section in sections) {
            if (rest < section.itemCount) {
                return if (rest == 0) {
                    ScrollItem.Title(section.chapterIndex)
                } else {
                    ScrollItem.Block(section.chapterIndex, rest - 1)
                }
            }
            rest -= section.itemCount
        }
        return null
    }

    /** 列表项 → 扁平序号；该章未物化（或块号超出已落定的块数）返回 -1 */
    fun flatIndexOf(item: ScrollItem): Int {
        var start = 0
        for (section in sections) {
            if (section.chapterIndex == item.chapterIndex) {
                return when (item) {
                    is ScrollItem.Title -> start
                    is ScrollItem.Block ->
                        if (item.blockIndex < section.blockCount.coerceAtLeast(1)) {
                            start + 1 + item.blockIndex
                        } else {
                            -1
                        }
                }
            }
            start += section.itemCount
        }
        return -1
    }

    /**
     * item 的稳定 key（LazyColumn 的按 key 锚定全靠它，见类 KDoc）。
     *
     * 只由 `(章, 块)` 构成、不含扁平序号：序号会随上方插入平移，拿它当 key 就等于
     * 每次展开都换一遍身份，锚定直接失效。
     */
    fun itemKey(flatIndex: Int): Any = when (val item = itemAt(flatIndex)) {
        is ScrollItem.Title -> "t${item.chapterIndex}"
        is ScrollItem.Block -> "b${item.chapterIndex}-${item.blockIndex}"
        null -> "o$flatIndex"
    }

    /** 某章的块总数；0 = 该章未物化或排版未落定 */
    fun blockCountOf(chapterIndex: Int): Int =
        sections.firstOrNull { it.chapterIndex == chapterIndex }?.blockCount ?: 0

    /** 取某块的渲染状态 */
    fun uiOf(chapterIndex: Int, blockIndex: Int): ReaderPageUi =
        store.uiOf(ReaderPageKey(chapterIndex, blockIndex))

    /** 章标题（标题项文案） */
    fun titleOf(chapterIndex: Int): String = chapterTitle(chapterIndex)

    /**
     * 初始化/跳转：取消在途加载，列表收敛到指定章（相邻章随即物化），落点由哨兵解析。
     * 与 [ReaderPagerController.setInitData] 同一语义，故两边可共用调用点。
     */
    fun setInitData(chapterIndex: Int, pageIndex: Int) {
        store.clear()
        sections.clear()
        pendingChapter = chapterIndex
        pendingTarget = pageIndex
        materialize(chapterIndex)
        // 相邻章一并物化：跨章滚动才不必在章界等排版（这也是原设计里「预热」该起的作用）
        materializeNeighbors(chapterIndex)
        val item = itemForBlock(chapterIndex, resolveTarget(pageIndex, blockCountOf(chapterIndex)))
        moveTo(item, reportProgress = true)
        requestJump(item, animate = false)
    }

    /**
     * 滚动位置变化：容器把 `firstVisibleItemIndex` 原样报进来，换算由本类做（它才持有章段）。
     *
     * 锚点上方插入 item 会让容器报来一个**平移后的序号**，但它指的仍是同一屏，
     * 故「同一屏」判据是 `(章, 块)` 而不是序号——否则每次相邻章展开都会多写一次进度。
     */
    fun onScrolledToItem(flatIndex: Int) {
        val item = itemAt(flatIndex) ?: return
        moveTo(item, reportProgress = !isSameScreen(item))
    }

    /**
     * 块进入组合时自行请求加载（由 [ReaderScroll] 的块 item 调）。
     *
     * 为什么不能只靠 [prefetch]：fling 时 `firstVisibleItemIndex` 会**跳过中间块号**，
     * 那些块被 LazyColumn 组合上屏却从没人替它们发过请求，于是一路都是加载态。让「画出来的块
     * 自己请求」才是兜底（与 Paging/Coil 的形态一致），预取随之退化成纯平滑优化。
     *
     * 重复调用是空操作：已 Loaded 的块被 [ReaderPageStore.ensureLoad] 的短路挡下，
     * 在途的块被它的 job 登记挡下，故 item 反复进出组合不会重复抓。
     */
    fun ensureLoaded(chapterIndex: Int, blockIndex: Int) {
        if (blockIndex !in 0 until blockCountOf(chapterIndex).coerceAtLeast(1)) return
        store.ensureLoad(ReaderPageKey(chapterIndex, blockIndex))
    }

    /** 加载失败重试（块数未落定时重试的就是该章的探测块） */
    fun reload(chapterIndex: Int, blockIndex: Int) =
        store.reload(ReaderPageKey(chapterIndex, blockIndex))

    /**
     * 滚一屏（点击左右三分区 / 音量键）。
     *
     * 列表是跨章连续的，所以「下一屏」就是下一项——它可能是下一章的标题项，
     * 于是不需要任何章界特例：标题矮于一屏，滚过去正好露出标题加下一章首块。
     * 只有走到列表尽头且确实没有相邻章（整本书的首末）才提示「没有上一页/下一页」，
     * 判据与 [ReaderPagerController] 的 prevKey/nextKey 为 null 同源。
     */
    fun scrollOneScreen(forward: Boolean) {
        val current = flatIndexOf(anchorItem)
        if (current < 0) return
        val next = itemAt(if (forward) current + 1 else current - 1)
        if (next != null) {
            // 标题项 ↔ 该章第 0 块是同一屏，来回滚一屏不该各写一次进度
            moveTo(next, reportProgress = !isSameScreen(next))
            requestJump(next, animate = true)
            return
        }
        val edge = if (forward) anchorChapter + 1 else anchorChapter - 1
        if (edge !in 0 until chapterSize()) {
            ToastUtil.showShort(
                context,
                context.getString(if (forward) R.string.no_next_page else R.string.no_prev_page),
            )
            return
        }
        // 走到列表尽头但还有相邻章：说明它尚未物化（探测失败，或刚被 clear 掉）。
        // 先物化，落点按哨兵记着，等它的块数落定再由 onLoaded 解析并补一次滚动
        materialize(edge)
        pendingChapter = edge
        pendingTarget =
            if (forward) DBCode.BookContentView.DUR_PAGE_INDEX_BEGIN else DBCode.BookContentView.DUR_PAGE_INDEX_END
        val item = itemForBlock(edge, resolveTarget(pendingTarget, blockCountOf(edge)))
        moveTo(item, reportProgress = !isSameScreen(item))
        requestJump(item, animate = true)
    }

    /**
     * 移动锚点：写进度、按需物化相邻章与裁剪正文、预取邻域块。
     *
     * @param reportProgress 标题项 ↔ 该章第 0 块之间来回不算阅读推进，传 false 只更新锚点
     */
    private fun moveTo(item: ScrollItem, reportProgress: Boolean) {
        val chapterChanged = item.chapterIndex != anchorChapter
        anchorItem = item
        if (reportProgress) onProgress(item.chapterIndex, item.blockOrZero())
        if (chapterChanged) {
            materializeNeighbors(item.chapterIndex)
            prune(item.chapterIndex)
        }
        prefetch(item.chapterIndex, item.blockOrZero())
    }

    /** 目标项与当前锚点是否同一屏（标题项与第 0 块同口径） */
    private fun isSameScreen(item: ScrollItem): Boolean =
        item.chapterIndex == anchorChapter && item.blockOrZero() == anchorBlock

    /** 物化一章：插入章段（保持升序）并探它的第 0 块，探测结果回出的 pageAll 即该章块数 */
    private fun materialize(chapterIndex: Int) {
        if (chapterIndex !in 0 until chapterSize()) return
        if (sections.any { it.chapterIndex == chapterIndex }) return
        val at = sections.indexOfFirst { it.chapterIndex > chapterIndex }
        sections.add(if (at < 0) sections.size else at, ChapterSection(chapterIndex, 0))
        store.ensureLoad(ReaderPageKey(chapterIndex, 0))
    }

    private fun materializeNeighbors(chapterIndex: Int) {
        materialize(chapterIndex - 1)
        materialize(chapterIndex + 1)
    }

    /**
     * 预取锚点邻域的块：块内容是原文子串，同章内命中 ChapterLayoutCache 后很便宜。
     * 这一圈只是**平滑优化**，不是唯一的请求来源（兜底见 [ensureLoaded]）。
     */
    private fun prefetch(chapterIndex: Int, blockIndex: Int) {
        val count = blockCountOf(chapterIndex)
        if (count == 0) return
        (blockIndex - PREFETCH..blockIndex + PREFETCH).forEach { i ->
            if (i in 0 until count) store.ensureLoad(ReaderPageKey(chapterIndex, i))
        }
    }

    /**
     * 按**章距**裁剪正文保留集：只留锚点章 ±[RETAIN_CHAPTERS] 的块。
     *
     * 半径的单位必须是章而不是块：章段跨章连续后已加载正文会一路累积，不裁就没有内存上界；
     * 而按块半径裁（曾试过 ±2 块）会把同章刚读过的块丢掉，回滚时重走加载并在 UI 上闪一下
     * 转圈（实测 0→6→0 往返，7 块里 4 块被请求两次）。按章距裁两全：同章内怎么滚都不丢，
     * 跨出几章之外才释放，往回滚的重新加载由 [ensureLoaded] 兜。
     *
     * 章段本身不裁（纯元数据），故列表始终连续、往回滚不必重新排版。
     */
    private fun prune(anchorChapter: Int) {
        val keep = mutableSetOf<ReaderPageKey>()
        sections.forEach { section ->
            if (abs(section.chapterIndex - anchorChapter) > RETAIN_CHAPTERS) return@forEach
            (0 until section.blockCount.coerceAtLeast(1)).forEach {
                keep += ReaderPageKey(section.chapterIndex, it)
            }
        }
        store.retain(keep)
    }

    private fun requestJump(item: ScrollItem, animate: Boolean) {
        val index = flatIndexOf(item)
        if (index < 0) return
        jumpToken += 1
        jump = ScrollJump(index, animate, jumpToken)
    }

    /** 块号 → 列表项：第 0 块用标题项（跳章落在章首时该露出章名），其余用块项 */
    private fun itemForBlock(chapterIndex: Int, blockIndex: Int): ScrollItem =
        if (blockIndex <= 0) ScrollItem.Title(chapterIndex) else ScrollItem.Block(chapterIndex, blockIndex)

    /** 哨兵/越界解析：BEGIN → 首块，END → 末块，块数未知时一律先落 0，其余钳到章内 */
    private fun resolveTarget(pageIndex: Int, blockCount: Int): Int = when {
        pageIndex == DBCode.BookContentView.DUR_PAGE_INDEX_BEGIN -> 0
        pageIndex == DBCode.BookContentView.DUR_PAGE_INDEX_END -> (blockCount - 1).coerceAtLeast(0)
        blockCount == 0 -> 0
        else -> pageIndex.coerceIn(0, blockCount - 1)
    }

    private companion object {
        /** 预取的邻域半径（块） */
        const val PREFETCH = 2

        /** 正文保留半径（章）：锚点章 ±2，共 5 章的正文留在内存里 */
        const val RETAIN_CHAPTERS = 2
    }
}

private fun ScrollItem.blockOrZero(): Int = (this as? ScrollItem.Block)?.blockIndex ?: 0
