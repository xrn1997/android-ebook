package com.ebook.book.reader

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.ebook.book.R
import com.ebook.db.event.DBCode
import com.xrn1997.common.util.ToastUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 阅读页页面标识。
 *
 * [pageIndex] 语义与 DBCode.BookContentView 对齐：>=0 为已解析页码；
 * [DBCode.BookContentView.DUR_PAGE_INDEX_BEGIN] / DUR_PAGE_INDEX_END 为
 * "章节首页/末页"的加载请求哨兵（加载后由分页结果解析为具体页码）。
 */
data class ReaderPageKey(val chapterIndex: Int, val pageIndex: Int)

/**
 * 单页渲染状态：加载中 / 加载失败 / 已加载（含分页后的页文本）。
 *
 * [ReaderPageUi.Loaded.text] 是章节原文的一个**连续子串**（结尾换行已去掉），
 * 边界由 [ReaderTypesetter] 按渲染引擎自身的断行偏移切出——页与页首尾相接、
 * 段落分隔符原样保留的前提。
 */
sealed interface ReaderPageUi {
    data object Loading : ReaderPageUi
    data object Error : ReaderPageUi
    data class Loaded(
        val title: String,
        val chapterIndex: Int,
        val durPageIndex: Int,
        val pageAll: Int,
        val text: String
    ) : ReaderPageUi
}

/**
 * 翻页控制器：1:1 移植原 ContentSwitchView 的三页窗口状态机。
 *
 * 窗口模型（与原实现对齐）：
 * - 当前页（dur）正常显示；下一页（next）绘制在当前页之下（翻页时当前页左移露出）；
 *   上一页（prev）绘制在当前页之上（翻页时从左侧滑入覆盖）
 * - [drag] 为横向位移（px）：负值=向后翻（当前页左移），正值=向前翻（上一页滑入）
 * - 翻页成功/失败动画、30dp 成功阈值、[isMoving] 动画期手势锁，均复刻原语义
 *
 * 竞态说明：页面加载以 [ReaderPageKey] 为键去重（在途 job 与已 Loaded 的页都不重复请求）；翻页提交后
 * 尚未完成的加载任务自动归属新窗口（完成回调按 key==durKey 判断是否触发窗口重算），
 * 无需原实现的 qTag 时间戳过期校验。跳转（[setInitData]）时取消全部在途任务。
 *
 * 窗口收敛规则（[commitNext]/[commitPrev]）：提交翻页时目标页未必是
 * [ReaderPageUi.Loaded]（仍在途或已失败），此时算不出它的前后页，于是**保留来路页
 * 作为相邻方向、未知方向收敛为 null**，对齐原实现翻页后 `state = ONLYPRE / ONLYNEXT`
 * 的中间态语义。两条必须守住的不变量：窗口三键互不相同（nextKey/prevKey 不得停在
 * 当前页上——自指会让翻页空转、动画演完仍停在同一页），且不得双方向同时为空
 * （否则失败页成为只能靠中央重试按钮脱身的死页，回不去刚读过的那页）。
 */
class ReaderPagerController internal constructor(
    private val scope: CoroutineScope,
    private val context: Context,
    private val chapterSize: () -> Int,
    private val chapterTitle: (Int) -> String,
    private val loadPage: suspend (chapterIndex: Int, pageIndex: Int) -> ReaderPageUi.Loaded?,
    private val onProgress: (chapterIndex: Int, pageIndex: Int) -> Unit
) {
    /** 当前页标识 */
    var durKey by mutableStateOf(ReaderPageKey(0, DBCode.BookContentView.DUR_PAGE_INDEX_BEGIN))
        private set

    /** 上一页标识（无上一页为 null） */
    var prevKey by mutableStateOf<ReaderPageKey?>(null)
        private set

    /** 下一页标识（无下一页为 null） */
    var nextKey by mutableStateOf<ReaderPageKey?>(null)
        private set

    /** 横向拖拽位移（px），动画期间由 [settle]/[turnPrev]/[turnNext] 驱动 */
    val drag = Animatable(0f)

    /** 翻页动画进行中：锁定手势与程序化翻页，避免并发动画 */
    var isMoving by mutableStateOf(false)
        private set

    /** 翻页容器宽度（px），由 [ReaderPager] 测量后写入，供程序化翻页使用 */
    var pageWidthPx: Float = 0f

    /** 翻页成功阈值（px），30dp 换算，由 [ReaderPager] 写入 */
    var turnThresholdPx: Float = 0f

    private val pages = mutableStateMapOf<ReaderPageKey, ReaderPageUi>()
    private val jobs = mutableMapOf<ReaderPageKey, Job>()

    /** 取指定页渲染状态（未入窗口按加载中兜底） */
    fun uiOf(key: ReaderPageKey?): ReaderPageUi = key?.let { pages[it] } ?: ReaderPageUi.Loading

    /** 章节标题（供加载中的页面预显标题，对齐原 loadData(title, ...) 语义） */
    fun titleOf(chapterIndex: Int): String = chapterTitle(chapterIndex)

    /**
     * 初始化/跳转：取消在途加载，窗口收敛到指定章节页（哨兵页码由加载结果解析）。
     * 对齐原 setInitData：立即回调一次进度（驱动菜单标题与章节滑条）。
     */
    fun setInitData(chapterIndex: Int, durPageIndex: Int) {
        jobs.values.forEach { it.cancel() }
        jobs.clear()
        pages.clear()
        prevKey = null
        nextKey = null
        durKey = ReaderPageKey(chapterIndex, durPageIndex)
        scope.launch { drag.snapTo(0f) }
        ensureLoad(durKey)
        onProgress(chapterIndex, durPageIndex)
    }

    /** 加载失败重试 */
    fun reload(key: ReaderPageKey) {
        jobs.remove(key)?.cancel()
        ensureLoad(key)
    }

    private fun ensureLoad(key: ReaderPageKey) {
        if (jobs.containsKey(key)) return
        // 已就绪的页不重抓：job 完成即从 [jobs] 注销，只看 jobs 去重会让窗口重算把仍是
        // Loaded 的来路页打回 Loading 再抓一遍（快速回翻时刚读过的那页会闪一下转圈，
        // 白跑一次 DB/网络 + 整章重排）。翻页只改窗口、不改排版，Loaded 的正文不会失效；
        // 真正的换装点（字号/跳章）走 [setInitData]，那里已清空 [pages]，不受本短路影响；
        // [reload] 只挂在错误态重试按钮上（Error 不是 Loaded），也不会被挡。
        if (pages[key] is ReaderPageUi.Loaded) return
        pages[key] = ReaderPageUi.Loading
        jobs[key] = scope.launch {
            val myJob = coroutineContext[Job]
            val loaded = loadPage(key.chapterIndex, key.pageIndex)
            // 仅当本协程仍是该 key 的当前登记任务时注销：若中途被 setInitData/reload/prune
            // 取消并移出 jobs，后继任务可能已用同 key 重新登记，此处无条件删除会把后继任务
            // 误删成孤儿（完成后用陈旧页覆盖已清空的窗口）。身份比对 + isActive 双保险。
            if (myJob?.isActive == true && jobs[key] === myJob) jobs.remove(key)
            if (isActive.not()) return@launch
            if (loaded != null) {
                pages[key] = loaded
                // 完成时若该页已是当前页（翻页途中加载完成），立即重算窗口
                if (key == durKey) refreshWindow(loaded)
            } else {
                pages[key] = ReaderPageUi.Error
            }
        }
    }

    /** 当前页加载完成后重算前后页窗口（对齐原 setDataFinish → updateOtherPage） */
    private fun refreshWindow(loaded: ReaderPageUi.Loaded) {
        val c = loaded.chapterIndex
        val p = loaded.durPageIndex
        val all = loaded.pageAll
        val total = chapterSize()
        prevKey = if (c > 0 || p > 0) {
            if (p > 0) ReaderPageKey(c, p - 1)
            else ReaderPageKey(c - 1, DBCode.BookContentView.DUR_PAGE_INDEX_END)
        } else null
        nextKey = if (total > 0 && (c < total - 1 || p < all - 1)) {
            if (p < all - 1) ReaderPageKey(c, p + 1)
            else ReaderPageKey(c + 1, DBCode.BookContentView.DUR_PAGE_INDEX_BEGIN)
        } else null
        prevKey?.let(::ensureLoad)
        nextKey?.let(::ensureLoad)
    }

    /** 手势拖拽增量（布局坐标，右滑为正）。方向可达性由窗口状态决定 */
    fun dragBy(deltaPx: Float, widthPx: Float) {
        if (isMoving) return
        val hasPre = prevKey != null
        val hasNext = nextKey != null
        if (!hasPre && !hasNext) return
        scope.launch {
            val clamped = (drag.value + deltaPx).coerceIn(
                if (hasNext) -widthPx else 0f,
                if (hasPre) widthPx else 0f
            )
            drag.snapTo(clamped)
        }
    }

    /**
     * 手势抬起后的收尾动画：超过 [turnThresholdPx]（原 30dp）判翻页成功，否则回弹。
     * 对齐原 initMoveSuccessAnim / initMoveFailAnim。
     */
    fun settle(totalDx: Float) {
        if (isMoving) return
        val widthPx = pageWidthPx
        scope.launch {
            isMoving = true
            val toPrev = totalDx > 0 && prevKey != null
            val toNext = totalDx < 0 && nextKey != null
            val success = abs(totalDx) > turnThresholdPx
            when {
                toPrev -> {
                    drag.animateTo(if (success) widthPx else 0f)
                    if (success) commitPrev()
                }
                toNext -> {
                    drag.animateTo(if (success) -widthPx else 0f)
                    if (success) commitNext()
                }
                else -> drag.animateTo(0f)
            }
            drag.snapTo(0f)
            isMoving = false
        }
    }

    /** 程序化向前翻页（点击左三分区 / 音量键上）。对齐原 initMoveSuccessAnim(viewContents[0], 0) */
    fun turnPrev() {
        if (isMoving) return
        if (prevKey == null) {
            ToastUtil.showShort(context, context.getString(R.string.no_prev_page))
            return
        }
        scope.launch {
            isMoving = true
            drag.animateTo(pageWidthPx)
            commitPrev()
            drag.snapTo(0f)
            isMoving = false
        }
    }

    /** 程序化向后翻页（点击右三分区 / 音量键下）。对齐原 initMoveSuccessAnim(next, -width) */
    fun turnNext() {
        if (isMoving) return
        if (nextKey == null) {
            ToastUtil.showShort(context, context.getString(R.string.no_next_page))
            return
        }
        scope.launch {
            isMoving = true
            drag.animateTo(-pageWidthPx)
            commitNext()
            drag.snapTo(0f)
            isMoving = false
        }
    }

    /**
     * 翻页提交（向后）：下一页升为当前页并重算窗口（对齐原动画 onAnimationEnd 分支）。
     *
     * 目标页非 [ReaderPageUi.Loaded] 时**不能只跳过重算**：那样 nextKey 会停在刚升为当前页
     * 的这一页上（自指 → 向后翻永远空转），prevKey 又被清空（向前翻被判成"没有上一页"），
     * 而失败页的 job 早已注销、不会再有完成回调来救窗口——用户被困在一张抓不到内容的页上，
     * 连刚读过的那页都回不去。故按类 KDoc 的收敛规则处置：来路页留作上一页，去向收敛为 null。
     *
     * 后续两条恢复路径都会把窗口补全：本页加载完成（在途）时走 [ensureLoad] 的
     * `key == durKey` 分支；翻回上一页再翻过来时 [refreshWindow] 会对该页重新 [ensureLoad]
     * （job 已注销，故必然重发请求），等价于一次自动重试。
     */
    private fun commitNext() {
        val from = durKey
        val nk = nextKey ?: return
        durKey = nk
        val loaded = pages[nk] as? ReaderPageUi.Loaded
        if (loaded != null) {
            refreshWindow(loaded)
        } else {
            prevKey = from
            nextKey = null
        }
        prune()
        onProgress(nk.chapterIndex, nk.pageIndex)
    }

    /** 翻页提交（向前）：上一页升为当前页并重算窗口（非 Loaded 时的收敛同 [commitNext]，方向对称） */
    private fun commitPrev() {
        val from = durKey
        val pk = prevKey ?: return
        durKey = pk
        val loaded = pages[pk] as? ReaderPageUi.Loaded
        if (loaded != null) {
            refreshWindow(loaded)
        } else {
            nextKey = from
            prevKey = null
        }
        prune()
        onProgress(pk.chapterIndex, pk.pageIndex)
    }

    /** 清理窗口外页面状态与在途任务，防止内存累积 */
    private fun prune() {
        val keep = setOfNotNull(durKey, prevKey, nextKey)
        pages.keys.toList().filter { it !in keep }.forEach {
            pages.remove(it)
            jobs.remove(it)?.cancel()
        }
    }
}

/**
 * 阅读器翻页容器（替代原 ContentSwitchView）。
 *
 * 层级（z 序）：下一页（底）→ 当前页 → 上一页（顶），与原 FrameLayout
 * addView 顺序一致；位移在布局期经 offset {} 读取 [ReaderPagerController.drag]，
 * 动画期间只触发重布局不触发重组。
 *
 * @param onCenterTap 点击中间三分之一区域（唤出菜单）
 * @param onBodySizeChanged 正文区域尺寸回调（供外部测算每页行数）
 */
@Composable
fun ReaderPager(
    controller: ReaderPagerController,
    textColor: Color,
    bgColor: Color,
    textSizeSp: Float,
    lineHeight: TextUnit,
    canClickTurn: Boolean,
    onCenterTap: () -> Unit,
    onBodySizeChanged: (widthPx: Int, heightPx: Int) -> Unit,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val widthPx = with(density) { maxWidth.toPx() }
        val touchSlop = LocalViewConfiguration.current.touchSlop

        // 测量结果回写控制器：程序化翻页（音量键/点击三分区）与阈值判定需要宽度。
        // LaunchedEffect(widthPx) 仅在尺寸变化时执行，不在组合期写状态。
        LaunchedEffect(widthPx) {
            controller.pageWidthPx = widthPx
            controller.turnThresholdPx = with(density) { 30.dp.toPx() }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(controller, widthPx, canClickTurn) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = true)
                        val startX = down.position.x
                        var moved = false
                        var lastX = startX
                        var pointerX = startX
                        var pressed = true
                        while (pressed) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull() ?: break
                            pressed = change.pressed
                            pointerX = change.position.x
                            val delta = pointerX - lastX
                            lastX = pointerX
                            val totalDx = pointerX - startX
                            if (!moved && abs(totalDx) > touchSlop) moved = true
                            if (moved) {
                                change.consume()
                                controller.dragBy(delta, widthPx)
                            }
                        }
                        val totalDx = pointerX - startX
                        if (controller.isMoving) return@awaitEachGesture
                        if (!moved) {
                            // 点击：左右三分区翻页（受"点击翻页"开关控制），中间唤菜单
                            when {
                                canClickTurn && startX <= widthPx / 3 ->
                                    controller.turnPrev()
                                canClickTurn && startX >= widthPx / 3 * 2 ->
                                    controller.turnNext()
                                else -> onCenterTap()
                            }
                        } else {
                            // 拖动：30dp 阈值判定成败（宽度与阈值已回写控制器）
                            controller.settle(totalDx)
                        }
                    }
                }
        ) {
            // 下一页：固定绘制在当前页之下（翻页时当前页左移露出）
            ReaderPageSlot(
                controller = controller,
                key = controller.nextKey,
                offsetX = { 0f },
                textColor = textColor,
                bgColor = bgColor,
                textSizeSp = textSizeSp,
                lineHeight = lineHeight,
                measureBody = false
            )
            // 当前页：向后翻时随手势左移（drag 为负）
            ReaderPageSlot(
                controller = controller,
                key = controller.durKey,
                offsetX = { min(controller.drag.value, 0f) },
                textColor = textColor,
                bgColor = bgColor,
                textSizeSp = textSizeSp,
                lineHeight = lineHeight,
                onBodySizeChanged = onBodySizeChanged,
                measureBody = true
            )
            // 上一页：绘制在最上层，向前翻时从 -width 滑入（drag 为正）
            ReaderPageSlot(
                controller = controller,
                key = controller.prevKey,
                offsetX = { -widthPx + max(controller.drag.value, 0f) },
                textColor = textColor,
                bgColor = bgColor,
                textSizeSp = textSizeSp,
                lineHeight = lineHeight,
                measureBody = false
            )
        }
    }
}

/**
 * 单个页面槽位：按窗口位置应用布局期水平偏移。
 *
 * @param offsetX 布局期求值的水平偏移（px），读取 drag 状态只触发重布局
 * @param measureBody 是否回报正文区尺寸（仅当前页需要，避免三页重复回调）
 */
@Composable
private fun ReaderPageSlot(
    controller: ReaderPagerController,
    key: ReaderPageKey?,
    offsetX: () -> Float,
    textColor: Color,
    bgColor: Color,
    textSizeSp: Float,
    lineHeight: TextUnit,
    measureBody: Boolean,
    onBodySizeChanged: ((widthPx: Int, heightPx: Int) -> Unit)? = null
) {
    if (key == null) return
    val ui = controller.uiOf(key)
    val title = (ui as? ReaderPageUi.Loaded)?.title ?: controller.titleOf(key.chapterIndex)

    // offset 在布局期读取 drag：翻页动画期间零重组（参考 module_find 性能修复经验）
    Box(
        modifier = Modifier
            .fillMaxSize()
            .offset { IntOffset(offsetX().roundToInt(), 0) }
            .background(bgColor)
    ) {
        ReaderPageCard(
            ui = ui,
            title = title,
            textColor = textColor,
            textSizeSp = textSizeSp,
            lineHeight = lineHeight,
            onRetry = { controller.reload(key) },
            onBodySizeChanged = if (measureBody) onBodySizeChanged else null
        )
    }
}

/**
 * 阅读页卡片专属尺寸常量。
 *
 * 这里的值不是纯视觉参数，而是**分页契约**的一部分：页码行必须"无论有没有文字都占同样高度"，
 * 正文区高度才能在 Loading / Error / Loaded 三态间保持恒定（每页行数由正文区实测高度算出，
 * 见 [ReaderPageCard] 与 ReadBookActivity.rePaginate）。
 */
private object ReaderPageTokens {
    /** 页码行常驻高度：14sp 文本行高 + 底部留白（对齐原 tv_page 的占位节奏） */
    val pageNumberRowHeight = 30.dp
}

/**
 * 页面内容卡片（替代原 BookContentView + adapter_content_switch_item.xml）。
 *
 * 三态互斥：加载中 / 错误（含重试）/ 正文（章节标题 + 整页文本 + 页码）。
 * 配色来自「阅读背景主题」（ReadBookControl），豁免系统深色模式。
 *
 * 两条契约（都直接影响"内容会不会丢"）：
 * 1. **正文区高度与页面状态无关**——每页行数按正文区实测高度算出，Loading/Loaded 两态
 *    占位不同就会多算一行，正文溢出到页码行；
 * 2. **正文样式与分页测量同一份**（[readerBodyTextStyle]）——"切几行"与"画几行"必须
 *    由同一引擎按同一样式回答，否则多画出来的行会被上面的裁剪静默吃掉。
 */
@Composable
fun ReaderPageCard(
    ui: ReaderPageUi,
    title: String,
    textColor: Color,
    textSizeSp: Float,
    lineHeight: TextUnit,
    onRetry: () -> Unit,
    onBodySizeChanged: ((widthPx: Int, heightPx: Int) -> Unit)? = null
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            // 对齐原 main_content 的状态栏 insets padding；底部同样要避让——阅读器
            // enableFitsSystemWindows=false，卡片铺到屏幕边缘，不避让导航条时页码行
            // 会被系统手势条/三键栏压住（与顶栏、底栏"背景延伸、内容避让"口径一致）
            .windowInsetsPadding(WindowInsets.systemBars)
            .padding(start = 15.dp, top = 5.dp, end = 15.dp, bottom = 5.dp)
    ) {
        // 章节标题（对齐原 tv_title：14sp，0.7 透明度）
        Text(
            text = title,
            modifier = Modifier.alpha(0.7f),
            color = textColor,
            fontSize = 14.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(modifier = Modifier.height(10.dp))
        Box(
            modifier = Modifier
                .fillMaxSize()
                .weight(1f)
        ) {
            // 内容骨架常驻（对齐原 ll_content 的 INVISIBLE 策略）：
            // 任何状态下正文区都参与布局与测量，保证首屏即可测算每页行数，
            // 避免"加载完才能测量、测量后才能分页"的死锁。
            // 加载/错误态以覆盖层叠加在骨架之上。
            Column(modifier = Modifier.fillMaxSize()) {
                Text(
                    text = (ui as? ReaderPageUi.Loaded)?.text ?: "",
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(bottom = 4.dp)
                        // 兜底裁剪：页数由 [ReaderTypesetter] 实测得出（正文区放得下几行就切几行），
                        // 切进来的一定画得下，正常情况下这一句不会切到任何字；留着是防排版引擎
                        // 在极端字形下差出零点几行时，溢出的那部分画到页码行上被叠字盖住。
                        .clipToBounds()
                        .then(
                            if (onBodySizeChanged != null) {
                                Modifier.onSizeChanged {
                                    onBodySizeChanged(it.width, it.height)
                                }
                            } else Modifier
                        ),
                    color = textColor,
                    // 样式必须与分页测量同一份（readerBodyTextStyle）：字号/行高/行高对齐方式
                    // 任何一项不一致，"切几行"与"画几行"就会错开，多出来的行被裁成看不见
                    style = readerBodyTextStyle(textSizeSp, lineHeight)
                )
                // 页码（对齐原 tv_page：居中、14sp）
                // 显式固定高度常驻占位，而不是"由内容撑开"：原 View 体系里空 TextView 仍占一行高，
                // Compose 的空 Text 高度会塌陷——若靠内容撑开，Loading/Error 态测得的正文高度就比
                // Loaded 态多出一整行页码高度，据此算出的每页行数虚高，正文加载完成后便溢出到页码行。
                // 固定占位后三态的正文可用高度完全一致，行数测算不再随页面状态漂移。
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(ReaderPageTokens.pageNumberRowHeight),
                    contentAlignment = Alignment.TopCenter
                ) {
                    Text(
                        text = (ui as? ReaderPageUi.Loaded)?.let {
                            stringResource(R.string.page_indicator_format, it.durPageIndex + 1, it.pageAll)
                        } ?: "",
                        color = textColor,
                        fontSize = 14.sp,
                        textAlign = TextAlign.Center
                    )
                }
            }
            when (ui) {
                is ReaderPageUi.Loading -> {
                    // 配色全部由正文色按透明度派生：本页属「阅读背景主题」层（ADR-0012 整片豁免
                    // 深色），在此改用 MaterialTheme 语义色会与四色正文主题打架
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(26.dp),
                            color = textColor.copy(alpha = 0.35f),
                            strokeWidth = 2.5.dp
                        )
                        Spacer(modifier = Modifier.height(14.dp))
                        Text(
                            text = stringResource(R.string.loading),
                            color = textColor.copy(alpha = 0.55f),
                            fontSize = 14.sp
                        )
                    }
                }
                is ReaderPageUi.Error -> {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.CloudOff,
                            contentDescription = null,
                            modifier = Modifier.size(40.dp),
                            tint = textColor.copy(alpha = 0.4f)
                        )
                        Spacer(modifier = Modifier.height(14.dp))
                        Text(
                            text = stringResource(R.string.reader_load_failed),
                            color = textColor.copy(alpha = 0.8f),
                            fontSize = 16.sp
                        )
                        Spacer(modifier = Modifier.height(22.dp))
                        // 重试改为胶囊按钮：底色 + 描边双重表达可点性（原 4dp 直角细边框在
                        // 护眼绿/米黄背景上几乎看不出是个按钮）
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(textColor.copy(alpha = 0.07f))
                                .border(
                                    width = 1.dp,
                                    color = textColor.copy(alpha = 0.3f),
                                    shape = RoundedCornerShape(50)
                                )
                                .clickable(onClick = onRetry)
                                .padding(horizontal = 22.dp, vertical = 10.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.retry),
                                color = textColor.copy(alpha = 0.85f),
                                fontSize = 14.sp
                            )
                        }
                    }
                }
                is ReaderPageUi.Loaded -> {
                    // 正文已绘制在骨架层，此处无额外内容（保留分支完整性）
                }
            }
        }
    }
}
