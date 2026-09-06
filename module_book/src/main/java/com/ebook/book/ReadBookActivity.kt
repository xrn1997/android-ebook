package com.ebook.book

import android.content.Context
import android.content.res.Resources
import android.os.Bundle
import android.text.TextPaint
import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.TextUnit
import androidx.lifecycle.lifecycleScope
import com.ebook.book.manager.BitIntentDataManager
import com.ebook.book.mvvm.viewmodel.BookReadViewModel
import com.ebook.book.mvvm.viewmodel.BookReadViewModel.Companion.OPEN_FROM_APP
import com.ebook.book.mvvm.viewmodel.BookReadViewModel.Companion.OPEN_FROM_OTHER
import com.ebook.book.reader.AddShelfDialog
import com.ebook.book.reader.ChapterLayoutCache
import com.ebook.book.reader.ChapterLayoutKey
import com.ebook.book.reader.ChapterDownloadSheet
import com.ebook.book.reader.ChapterListDrawer
import com.ebook.book.reader.FontPanel
import com.ebook.book.reader.LightPanel
import com.ebook.book.reader.MoreSettingPanel
import com.ebook.book.reader.ReaderBottomBar
import com.ebook.book.reader.ReaderPager
import com.ebook.book.reader.ReaderPagerController
import com.ebook.book.reader.ReaderPanel
import com.ebook.book.reader.ReaderTopBar
import com.ebook.book.reader.ReaderTypesetter
import com.ebook.book.reader.applyReaderBrightness
import com.ebook.book.reader.rememberReaderTypesetter
import com.ebook.book.repository.BookImportRepository
import com.ebook.book.view.ReadBookControl
import com.ebook.common.domain.CommentKey
import com.ebook.common.event.KeyCode
import com.ebook.common.event.RouteArgs
import com.ebook.common.repository.BookRepository
import com.ebook.db.entity.BookShelfEntity
import com.ebook.db.entity.DownloadChapterEntity
import com.ebook.db.event.DBCode
import com.permissionx.guolindev.PermissionX
import com.therouter.TheRouter
import com.xrn1997.common.mvvm.compose.BaseMvvmActivity
import com.xrn1997.common.ui.LoadingView
import com.xrn1997.common.util.Logger
import com.xrn1997.common.util.ToastUtil
import com.xrn1997.common.util.detectColor
import com.xrn1997.common.util.setStatusBarColor
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import kotlin.math.ceil

/**
 * 阅读页（Compose 版，替代原 ViewBinding + ContentSwitchView 体系，见 ADR-0001）。
 *
 * 结构：
 * - 翻页核心：[ReaderPager] + [ReaderPagerController]（三页窗口状态机 1:1 移植）
 * - 页面渲染：ReaderPageCard（原 BookContentView）
 * - 菜单/面板：[ReaderTopBar]/[ReaderBottomBar]/章节目录/亮度/字体/设置（原五个 PopupWindow）
 * - 数据加载：[loadPage]（原 loadContent：DB 缓存 → 网络 → 存库 → StaticLayout 重分行）
 *
 * 配色豁免：阅读界面使用「阅读背景主题」（ReadBookControl），不跟随系统深色模式。
 */
@AndroidEntryPoint
class ReadBookActivity : BaseMvvmActivity<BookReadViewModel>() {
    override val viewModel: BookReadViewModel by viewModels()

    @Inject
    lateinit var bookImportRepository: BookImportRepository

    @Inject
    lateinit var bookRepository: BookRepository

    /** 翻页控制器引用：音量键翻页由 Activity.onKeyUp 转发（组合外入口） */
    var pagerController: ReaderPagerController? = null

    /** 正文区实测宽度（px）：StaticLayout 分行宽度，由页面测量回调写入 */
    var readerContentWidthPx: Int = 0
        private set

    /** 正文区实测高度（px）：每页行数测算依据，由页面测量回调写入 */
    var readerBodyHeightPx: Int = 0
        private set

    /**
     * 当前分页排版上下文（测量器 + 正文样式 + 密度），由 [rePaginate] 落定、[loadPage] 取用。
     *
     * 为什么挂在 Activity 上而不是随参数传进控制器：翻页控制器在首次组合就被 `remember`
     * 记住，其 lambda 捕获的引用不会随字号更新；而「样式变了」必然伴随一次 [rePaginate]，
     * 让它作为唯一的换装点，取值时机就与行数测算严格同步了。
     */
    internal var readerTypesetter: ReaderTypesetter? = null
        private set

    /** 排版偏移缓存：同章翻页不重复整章重排（见 ChapterLayoutCache） */
    private val layoutCache = ChapterLayoutCache()

    override fun enableToolbar(): Boolean = false

    override fun enableFitsSystemWindows(): Boolean = false

    override fun initData() {
        // 对齐原实现：进入即保存一次进度（防止异常退出丢失）
        viewModel.saveProgress()
        // 恢复已持久化的手动亮度（窗口亮度不跨生命周期，见 applyReaderBrightness KDoc）
        applyReaderBrightness(this)
    }

    /** 正文区尺寸测量回调：更新分行宽度与行数测算高度 */
    fun onBodyMeasured(widthPx: Int, heightPx: Int) {
        if (widthPx > 0) readerContentWidthPx = widthPx
        if (heightPx > 0) readerBodyHeightPx = heightPx
    }

    /**
     * 应用内打开书籍（对齐原 openBookFromApp）：
     * 经 BitIntentDataManager 取书架实体；非本地书显示"更多"下载入口；随后发起书架归属检查。
     *
     * 快速失败：数据键缺失或数据为空/类型不符时直接提示并退出——否则 bookShelf 恒为 null，
     * checkInShelf 不会触发，阅读器将停在永久空白页无任何反馈（上游详情页已做前置守卫，
     * 此处为兜底）。
     */
    fun openBookFromApp(onShowMore: () -> Unit) {
        val key = intent.getStringExtra("data_key")
        if (key == null) {
            Logger.e(TAG, "openBookFromApp: key is null")
            finish()
            return
        }
        val data = BitIntentDataManager.getData(key)
        BitIntentDataManager.cleanData(key)
        val bookShelf = data as? BookShelfEntity
        if (bookShelf == null) {
            Logger.e(TAG, "openBookFromApp: data missing or type mismatch")
            ToastUtil.showShort(this, getString(R.string.reader_load_failed))
            finish()
            return
        }
        if (bookShelf.tag != BookShelfEntity.LOCAL_TAG) {
            onShowMore()
        }
        viewModel.bookShelf = bookShelf
        viewModel.checkInShelf()
    }

    /**
     * 应用外打开文本（对齐原 openBookFromOther）：
     * 导入本地文件 → 加入书架 → 归属检查；失败提示并置错误页。
     */
    fun openBookFromOther(onImporting: (Boolean) -> Unit) {
        val uri = intent.data ?: return
        onImporting(true)
        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    bookImportRepository.import(uri)
                }
                viewModel.bookShelf = result.bookShelf
                onImporting(false)
                viewModel.checkInShelf()
            } catch (e: Exception) {
                Logger.e(TAG, "openBookFromOther error: ", e)
                onImporting(false)
                ToastUtil.showShort(this@ReadBookActivity, getString(R.string.text_open_failed))
            }
        }
    }

    /**
     * 下载入口的通知权限请求（对齐原 readBookMenuMorePop 下载分支）。
     *
     * 无论授予与否都回调 [onResult]：通知只是进度的展示渠道，把它当成下载的前置门槛，
     * 会造成"用户拒绝过一次通知 → 点下载完全没反应"（原实现 `if (allGranted) onGranted()` 的缺陷）；
     * 前台服务与落库本身不需要该权限，Service 侧发不出通知时自行降级（见 DownloadService）。
     */
    fun requestDownloadPermission(onResult: () -> Unit) {
        PermissionX
            .init(this)
            .permissions(PermissionX.permission.POST_NOTIFICATIONS)
            .request { _: Boolean, _: List<String?>?, _: List<String?>? -> onResult() }
    }

    /**
     * 跳转章节评论区（M2：跨源评论合并——同一作品多个书源各有 book_group 行，
     * [bookKeys] 为所有关联的书级聚合键，逐一拼章索引后逗号分隔传给评论区做并集查询）。
     */
    fun navToComment(bookShelf: BookShelfEntity, bookKeys: List<String>) {
        val chapter = viewModel.getChapter(bookShelf.durChapter)
        // 章级聚合键：每个 bookKey 都拼 "#" + chapterIndex，逗号分隔传给接收方
        val chapterKeys = bookKeys.joinToString(",") { "$it#${bookShelf.durChapter}" }
        val bundle = Bundle().apply {
            putString(RouteArgs.COMMENT_KEY, chapterKeys)
            putString(RouteArgs.CHAPTER_URL, chapter?.contentRef ?: "")
            putString(RouteArgs.CHAPTER_NAME, chapter?.durChapterName ?: getString(R.string.unknown_chapter))
            putString(RouteArgs.BOOK_NAME, bookShelf.bookInfo?.name ?: getString(R.string.unknown_book))
        }
        TheRouter.build(KeyCode.Book.COMMENT_PATH)
            .with(bundle)
            .navigation(this)
    }

    /**
     * 测算每页行数并启动/重分页（原 startLoading → initData(lineCount) → setInitData 链）。
     *
     * 行数由 [ReaderTypesetter.fitRenderLineCount] 向渲染引擎本身实测得出：正文区高度
     * 放得下几行，一页就切几行。同时把这份排版上下文存下来给 [loadPage] 用——
     * 「测算行数的样式」与「切行、渲染用的样式」必须是同一份，否则又会回到两套判定。
     *
     * 禁止回到「用字体度量估行数」的老路（(高度-段距)/(字高+段距)）：那是拿平台度量
     * 猜 Compose 的几何，每行差零点几像素、25 行就累计出近 20px 的误差。
     *
     * [readerBodyHeightPx] 是正文区的实测高度，只在首屏与字号变化时重算，因此**正文区高度
     * 必须与页面状态无关**：页码行等内容若在 Loading/Loaded 两态占位不同，这里就会按虚高
     * 的高度多算行，正文渲染时溢出到页码行并被其盖住（占位契约见 ReaderPageCard）。
     */
    internal fun rePaginate(typesetter: ReaderTypesetter, startFromCurrent: Boolean = true) {
        val width = readerContentWidthPx
        val height = readerBodyHeightPx
        if (width <= 0 || height <= 0) return
        val lineCount = typesetter.fitRenderLineCount(width, height)
        if (lineCount <= 0) return
        // 样式与行数一起落定：随后的 loadPage 取的就是这份样式，测算与分页不可能错身
        readerTypesetter = typesetter
        viewModel.pageLineCount = lineCount
        val shelf = viewModel.bookShelf ?: return
        if (startFromCurrent) {
            pagerController?.setInitData(shelf.durChapter, shelf.durChapterPage)
        }
    }

    /**
     * 加载单页内容（原 loadContent 的 suspend 化）：
     * 1. 按来源取正文（本地书走章文件、网络书走 DB 缓存→网络） → 2. 排版偏移缓存 → 3. 分页切片取原文子串。
     *
     * 断行走 [readerTypesetter]（与页面渲染同一引擎、同一份样式）——见
     * [ReaderTypesetter] 里「分页与渲染必须同源」的契约：两套引擎判定的行数不一致时，
     * 多出来的行会被静默裁掉，表现为上一页和下一页内容接不上。
     *
     * 哨兵页码（DUR_PAGE_INDEX_BEGIN/END）在分页结果出来后解析；页码越界钳到末页。
     * 返回 null 表示失败（控制器置为错误态）。
     */
    suspend fun loadPage(chapterIndex: Int, pageIndex: Int): com.ebook.book.reader.ReaderPageUi.Loaded? {
        val bookShelf = viewModel.bookShelf
        val chapterSize = viewModel.getChapterListSize()
        if (bookShelf == null || chapterSize == 0) return null
        val chapter = viewModel.getChapter(chapterIndex) ?: return null
        val typesetter = readerTypesetter ?: return null

        return try {
            // 取正文：本地书与网络书统一走 BookRepository.loadChapter（章文件 + 内存缓存）
            val chapterText: String? = viewModel.loadChapter(chapter)?.displayText
            if (chapterText.isNullOrEmpty()) return null

            // 3. 按当前排版求渲染行起始偏移。排版结果走 [layoutCache] 按（章, 字号, 宽度）缓存，
            //    同章翻页只重排一次；改字号或宽度会因键不同而自动重算。
            val width = readerContentWidthPx
            if (width <= 0) return null
            // 排版结果按（章, 字号, 宽度）缓存：同章翻页不再整章重排（见 ChapterLayoutCache）
            val content = chapterText
            val layoutKey = ChapterLayoutKey(
                contentRef = chapter.contentRef,
                fontSizeSp = ReadBookControl.textSize.toFloat(),
                widthPx = width,
            )
            val lineStarts = withContext(Dispatchers.Default) {
                layoutCache.getOrCompute(layoutKey) { typesetter.lineStartOffsets(content, width) }
            }

            // 4. 分页切片
            val pageLineCount = viewModel.pageLineCount
            if (pageLineCount <= 0) return null
            val tempCount = ceil(lineStarts.size * 1.0 / pageLineCount).toInt() - 1
            if (tempCount < 0) return null
            val resolved = when (pageIndex) {
                DBCode.BookContentView.DUR_PAGE_INDEX_BEGIN -> 0
                DBCode.BookContentView.DUR_PAGE_INDEX_END -> tempCount
                else -> pageIndex.coerceAtMost(tempCount).coerceAtLeast(0)
            }
            val start = resolved * pageLineCount
            val end = if (resolved == tempCount) lineStarts.size else start + pageLineCount
            // 页文本 = 原文的连续子串：从本页首行偏移取到「下一页首行偏移」（末页取到文末），
            // 段落分隔符（\r\n）原样保留；再去掉结尾换行——段末换行留在结尾会让渲染引擎
            // 多排一个空行，白占一行高度（内容不丢，但会顶掉最后一行）。
            // 不能用「行子串拼接」：见 ReaderTypesetter.lineStartOffsets 的 CRLF 说明。
            val from = lineStarts[start]
            val to = if (end < lineStarts.size) lineStarts[end] else content.length
            val pageText = content.substring(from, to).trimEnd('\r', '\n')
            com.ebook.book.reader.ReaderPageUi.Loaded(
                title = chapter.durChapterName,
                chapterIndex = chapterIndex,
                durPageIndex = resolved,
                pageAll = tempCount + 1,
                text = pageText
            )
        } catch (e: Exception) {
            Logger.e(TAG, "loadPage error: ", e)
            null
        }
    }

    /**
     * 音量键按下拦截：系统在 key down 即触发音量调整，必须在此消费音量键事件，
     * 否则翻页的同时还会调整音量；翻页动作放在 [onKeyUp]（对齐原实现，避免长按重复翻页）。
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (ReadBookControl.canKeyTurn) {
            when (keyCode) {
                KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_UP -> return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    /** 音量键翻页（受"按键翻页"开关控制），其余按键走系统默认 */
    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (ReadBookControl.canKeyTurn) {
            when (keyCode) {
                KeyEvent.KEYCODE_VOLUME_DOWN -> {
                    pagerController?.turnNext()
                    return true
                }
                KeyEvent.KEYCODE_VOLUME_UP -> {
                    pagerController?.turnPrev()
                    return true
                }
            }
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onPause() {
        super.onPause()
        viewModel.saveProgress()
    }

    @Composable
    override fun PageContent() {
        // 阅读器整片豁免系统深色：作用域内固定 lightColorScheme，使顶/底栏、面板、
        // 弹窗的 MaterialTheme.colorScheme.* 一律解析到浅色，与正文阅读背景主题（
        // ReadBookControl）保持一致、不随系统深色切换（ADR-0001 记载的豁免情形）。
        MaterialTheme(colorScheme = ReaderLightColorScheme) {
            ReadBookScreen(this, viewModel)
        }
    }
}

/**
 * 下载面板异步参数快照：缓存事实集 + 预勾选集（通知权限/缓存查询完成后才开面板，
 * 避免面板先弹出后闪烁刷新）。
 */
private data class DownloadSheetArgs(
    val cachedIndices: Set<Int>,
    val initialSelected: Set<Int>
)

/**
 * 阅读器固定浅色色彩方案：整片豁免系统深色。
 *
 * 对齐原阅读界面菜单/面板始终为浅色（原 ll_menu_top/ll_menu_bottom 固定 #ffffff）；
 * 正文背景由 [ReadBookControl] 阅读背景主题独立控制，故本方案仅覆盖 chrome 层的
 * [MaterialTheme.colorScheme]，语义色走默认 Material 浅色调板、不逐组件硬编码颜色。
 */
private val ReaderLightColorScheme: ColorScheme = lightColorScheme()

/**
 * 构造阅读正文排版的 TextPaint（字号与 Compose 正文一致）。
 *
 * 只用于取「单行字高」（descent - ascent）：正文行高 = 字高 + 段距，见 [ReadBookScreen]
 * 的 lineHeight 与 [ReaderTypesetter]。分行与行数测算本身已统一到 Compose 排版引擎
 * （[ReaderTypesetter]），不再用平台 StaticLayout，避免两套引擎行数不一致。
 */
private fun readerTextPaint(resources: Resources): TextPaint = TextPaint().apply {
    textSize = spToPx(resources, ReadBookControl.textSize.toFloat())
    isSubpixelText = true
}

/**
 * sp → px（不用已弃用的 displayMetrics.scaledDensity 字段）。
 *
 * 阅读器字号配置以 sp 存储（ReadBookControl.textSize），绘制与测量需要 px：
 * 经 TypedValue.applyDimension 按当前 density 换算，保证字号随系统字体缩放。
 */
private fun spToPx(resources: Resources, sp: Float): Float =
    android.util.TypedValue.applyDimension(
        android.util.TypedValue.COMPLEX_UNIT_SP, sp, resources.displayMetrics
    )

/**
 * 阅读页屏幕：翻页容器 + 菜单覆盖层 + 各类面板/弹窗的编排。
 */
@Composable
private fun ReadBookScreen(
    activity: ReadBookActivity,
    viewModel: BookReadViewModel
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    // ---------------- 阅读主题（ReadBookControl 原始色值，豁免深色模式） ----------------
    // 版本号仅用于触发重组以重读单例最新值（ReadBookControl 非 Compose 状态）
    var textKindVersion by remember { mutableIntStateOf(0) }
    var bgVersion by remember { mutableIntStateOf(0) }
    val textColor = remember(bgVersion) { Color(ReadBookControl.textColor) }
    val bgColor = remember(bgVersion) { Color(ReadBookControl.textBackground) }
    val textSizeSp = remember(textKindVersion) { ReadBookControl.textSize.toFloat() }
    // 行高 = 单行高度 + 段距（对齐原 lineSpacingExtra 语义）
    val lineHeight: TextUnit = remember(textKindVersion) {
        val paint = readerTextPaint(activity.resources)
        val textHeight = paint.descent() - paint.ascent()
        with(density) { (textHeight + ReadBookControl.textExtra).toSp() }
    }
    // 分页排版上下文：行数测算、切行、正文渲染三方共用的唯一样式来源（契约见 ReaderTypesetter）
    val typesetter = rememberReaderTypesetter(textSizeSp, lineHeight)

    // ---------------- 页面级状态 ----------------
    var menuVisible by remember { mutableStateOf(false) }
    var panel by remember { mutableStateOf(ReaderPanel.NONE) }
    // 点击翻页开关的页面级镜像：ReadBookControl.canClickTurn 是普通属性（非 Compose State），
    // 写入不触发重组；此处镜像为 State 并经 MoreSettingPanel.onClickTurnChanged 即时同步，
    // 避免"点击翻页是否生效"依赖 panel 变化恰好触发重组（隐式耦合）。
    // 注意：canKeyTurn 不走此镜像——onKeyDown/onKeyUp 是 Activity 回调，运行时直读单例即最新值。
    var clickTurnEnabled by remember { mutableStateOf(ReadBookControl.canClickTurn) }
    var showMore by remember { mutableStateOf(false) } // 原 iv_menu_more：非本地书显示下载入口
    // 章节标题初值走 stringResource：context.getString 的读取不随 Configuration 变化失效
    // （lint LocalContextGetResourceValueCall 判 Error）。刻意**不**把 noChapter 当 remember 的
    // key——key 一变会把已加载的章节标题重置回占位文案；本页未声明 android:configChanges，
    // 语言切换走 Activity 重建，重建后的新组合自然取到新语言初值。
    val noChapter = stringResource(R.string.no_chapter)
    var chapterTitle by remember { mutableStateOf(noChapter) }
    var sliderValue by remember { mutableFloatStateOf(1f) }
    var bookReady by remember { mutableStateOf(false) } // nextInShelfEvent 已到
    var pagerStarted by remember { mutableStateOf(false) }
    var importingBook by remember { mutableStateOf(false) } // 外部打开文本的导入遮罩
    var addShelfDialogVisible by remember { mutableStateOf(false) }
    // 下载面板异步参数（见 DownloadSheetArgs）；面板显隐由 panel 枚举驱动，与其他面板一致
    var downloadArgs by remember { mutableStateOf<DownloadSheetArgs?>(null) }
    // 正文区测量尺寸（Compose 状态，驱动首屏分页启动）；
    // activity.onBodyMeasured 同步存非状态字段供 loadPage 分行使用。
    var bodyWidth by remember { mutableIntStateOf(0) }
    var bodyHeight by remember { mutableIntStateOf(0) }

    val bookShelf = viewModel.bookShelf
    val chapterAll = viewModel.getChapterListSize()

    // ---------------- 翻页控制器 ----------------
    val controller = remember {
        ReaderPagerController(
            scope = scope,
            context = context,
            chapterSize = { viewModel.getChapterListSize() },
            chapterTitle = { viewModel.getChapterTitle(it) },
            loadPage = { c, p -> activity.loadPage(c, p) },
            onProgress = { c, p ->
                // 对齐原 updateProgress：进度落 ViewModel + 菜单标题 + 章节滑条。
                // p 为翻页目标的页码（可能为哨兵），与原实现一致直接落库。
                viewModel.updateProgress(c, p)
                chapterTitle = viewModel.getChapterTitle(c)
                sliderValue = (c + 1).toFloat()
            }
        )
    }
    DisposableEffect(controller) {
        activity.pagerController = controller
        onDispose { activity.pagerController = null }
    }

    // ---------------- 生命周期与事件 ----------------
    // 屏幕常亮（对齐原 fl_content 的 keepScreenOn）
    DisposableEffect(Unit) {
        activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            activity.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // 状态栏色随阅读背景自适应（对齐原 setStatusBarColor(textBackground.detectColor())）
    LaunchedEffect(bgVersion) {
        activity.setStatusBarColor(ReadBookControl.textBackground.detectColor())
    }

    // 书架归属检查完成事件（原 initBaseViewObservable 的 nextInShelfEvent 收集）
    LaunchedEffect(Unit) {
        viewModel.nextInShelfEvent.collect { bookReady = true }
    }

    // 打开书籍（对齐原 csvBook.bookReadInit 回调）
    LaunchedEffect(Unit) {
        if (activity.intent.getIntExtra("from", OPEN_FROM_OTHER) == OPEN_FROM_APP) {
            activity.openBookFromApp { showMore = true }
        } else {
            activity.openBookFromOther { importingBook = it }
        }
    }

    // 书籍就绪 + 正文区完成测量 → 测算行数并启动翻页（两者先后顺序不定，均在此汇合）
    LaunchedEffect(bookReady, bodyHeight) {
        if (bookReady && !pagerStarted && bodyHeight > 0) {
            pagerStarted = true
            sliderValue = ((viewModel.bookShelf?.durChapter ?: 0) + 1).toFloat()
            val shelf = viewModel.bookShelf
            activity.rePaginate(typesetter, startFromCurrent = false)
            controller.setInitData(
                shelf?.durChapter ?: 0,
                shelf?.durChapterPage ?: DBCode.BookContentView.DUR_PAGE_INDEX_BEGIN
            )
        }
    }

    // 排版上下文换实例（字号/段距变化，或密度变化）→ 按新行高重分页并停在当前页。
    // 由 typesetter 驱动而不是面板回调：面板里改完 ReadBookControl 只是自增版本号，
    // 新样式要等这次重组才生效，直接在回调里重分页就会拿旧样式去量新字号的行数。
    LaunchedEffect(typesetter) {
        if (pagerStarted) activity.rePaginate(typesetter, startFromCurrent = true)
    }

    // 返回键处置链（对齐原 onBackPressedDispatcher 回调）：
    // 章节目录抽屉（自绘覆盖层）→ 关菜单 → 未加入书架弹确认 → 退出。
    // 其余面板（ModalBottomSheet）与弹窗自带返回处理，优先于本回调消费。
    BackHandler {
        when {
            panel == ReaderPanel.CHAPTER -> panel = ReaderPanel.NONE
            menuVisible -> menuVisible = false
            addShelfDialogVisible -> addShelfDialogVisible = false
            !viewModel.isAdd -> addShelfDialogVisible = true
            else -> activity.finish()
        }
    }

    // ---------------- 下载面板（章节多选，缓存感知） ----------------
    // 统一入口：请通知权限 → 从章文件查缓存事实集 → 预勾选 → 开面板。
    // 预勾选沿用原默认范围语义（当前章 +50 章）：默认勾范围内未缓存章节（一键下载习惯）；
    // 想刷新缓存就改勾已缓存章节——任务统一带 forceRefresh（见 startChapterDownload）
    val openDownloadSheet: () -> Unit = {
        menuVisible = false
        activity.requestDownloadPermission {
            val shelf = viewModel.bookShelf
            val chapterList = shelf?.chapterList
            if (shelf == null || chapterList.isNullOrEmpty()) return@requestDownloadPermission
            scope.launch {
                val cachedIndices = activity.bookRepository.getCachedChapterIndices(
                    shelf, chapterList
                )
                val endIndex = (shelf.durChapter + 50).coerceAtMost(chapterList.size - 1)
                val initialSelected = (shelf.durChapter..endIndex).filterTo(mutableSetOf()) { i ->
                    i !in cachedIndices
                }
                downloadArgs = DownloadSheetArgs(cachedIndices, initialSelected)
                panel = ReaderPanel.DOWNLOAD
            }
        }
    }

    // ---------------- 布局 ----------------
    // 章节列表取 bookShelf.chapterList（书架页经 getAllBooksWithDetails() 填充；
    // 本地导入书由 LocalBookImporter 回填）；不用 bookInfo.chapterList（仅网络书解析时填充）
    val chapters = bookShelf?.chapterList ?: emptyList()

    Box(modifier = Modifier.fillMaxSize()) {
        ReaderPager(
            controller = controller,
            textColor = textColor,
            bgColor = bgColor,
            textSizeSp = textSizeSp,
            lineHeight = lineHeight,
            canClickTurn = clickTurnEnabled,
            onCenterTap = { menuVisible = !menuVisible },
            onBodySizeChanged = { w, h ->
                activity.onBodyMeasured(w, h)
                if (w != bodyWidth) bodyWidth = w
                if (h != bodyHeight) bodyHeight = h
            },
            modifier = Modifier.fillMaxSize()
        )

        // 菜单背景（对齐原 v_menu_bg：菜单可见时点击空白关闭）
        if (menuVisible) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable { menuVisible = false }
            )
        }

        // 顶栏（上滑入/出动画对齐原 anim_readbook_top_in/out）
        AnimatedVisibility(
            visible = menuVisible,
            enter = slideInVertically { -it },
            exit = slideOutVertically { -it },
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            ReaderTopBar(
                title = chapterTitle,
                subtitle = bookShelf?.bookInfo?.name ?: "",
                showMore = showMore,
                onBack = {
                    // 返回箭头 = 退出阅读器（未加入书架先弹确认），不能走 onBackPressedDispatcher：
                    // 处置链首位是"菜单可见→关菜单"，会把退出语义降级为隐藏控制界面；
                    // 硬件返回键仍走 BackHandler（菜单可见时先收菜单，符合阅读器习惯）
                    if (!viewModel.isAdd) {
                        addShelfDialogVisible = true
                    } else {
                        activity.finish()
                    }
                },
                onDownload = openDownloadSheet,
                onComment = {
                    menuVisible = false
                    viewModel.bookShelf?.let { shelf ->
                        scope.launch {
                            val keys = activity.bookRepository.getCommentKeysForBook(shelf.noteUrl)
                            // 兜底：book_group 无行时（旧数据未迁移）退回当前书信息算一个键
                            val effectiveKeys = keys.ifEmpty {
                                val name = shelf.matchName ?: shelf.bookInfo?.name
                                if (!name.isNullOrEmpty()) {
                                    listOf(CommentKey.compute(name, shelf.matchAuthor ?: shelf.bookInfo?.author))
                                } else {
                                    emptyList()
                                }
                            }
                            activity.navToComment(shelf, effectiveKeys)
                        }
                    }
                }
            )
        }

        // 底栏（下滑入/出动画对齐原 anim_readbook_bottom_in/out）
        AnimatedVisibility(
            visible = menuVisible,
            enter = slideInVertically { it },
            exit = slideOutVertically { it },
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            ReaderBottomBar(
                chapterAll = chapterAll,
                sliderValue = sliderValue.coerceIn(1f, chapterAll.coerceAtLeast(1).toFloat()),
                activePanel = panel,
                onSliderChange = { sliderValue = it },
                onSliderFinished = {
                    // 对齐原 moveStopProgress：抬手取整跳章
                    var realDur = ceil(sliderValue.toDouble()).toInt()
                    if (realDur < 1) realDur = 1
                    val shelf = viewModel.bookShelf
                    if (shelf != null && realDur - 1 != shelf.durChapter) {
                        controller.setInitData(
                            realDur - 1,
                            DBCode.BookContentView.DUR_PAGE_INDEX_BEGIN
                        )
                    }
                    if (sliderValue != realDur.toFloat()) sliderValue = realDur.toFloat()
                },
                prevEnabled = sliderValue > 1f,
                nextEnabled = sliderValue < chapterAll.toFloat(),
                onPrevChapter = {
                    viewModel.bookShelf?.let { shelf ->
                        controller.setInitData(
                            shelf.durChapter - 1,
                            DBCode.BookContentView.DUR_PAGE_INDEX_BEGIN
                        )
                    }
                },
                onNextChapter = {
                    viewModel.bookShelf?.let { shelf ->
                        controller.setInitData(
                            shelf.durChapter + 1,
                            DBCode.BookContentView.DUR_PAGE_INDEX_BEGIN
                        )
                    }
                },
                onCatalog = { panel = ReaderPanel.CHAPTER },
                onLight = { panel = ReaderPanel.LIGHT },
                onFont = { panel = ReaderPanel.FONT },
                onSetting = { panel = ReaderPanel.SETTING }
            )
        }

        // 外部打开文本的导入遮罩：共享 LoadingView（透明遮罩 + 居中卡片，语义对齐原 MoProgressHUD.showLoading）。
        // 阅读器浅色作用域内 LoadingView 取当前主题语义色，无需自绘 scrim 层
        LoadingView(
            visible = importingBook,
            modifier = Modifier.fillMaxSize(),
            txt = stringResource(R.string.importing_text),
        )

        // 章节目录抽屉（左侧滑入，对齐原 ChapterListView 侧滑面板）：
        // 常驻组合、由 panel 状态驱动进出场动画；自绘覆盖层无内置返回处置，
        // 返回键由上方 BackHandler 收口
        ChapterListDrawer(
            visible = panel == ReaderPanel.CHAPTER,
            bookName = bookShelf?.bookInfo?.name ?: "",
            chapters = chapters,
            durChapter = bookShelf?.durChapter ?: 0,
            onChapterClick = { index ->
                panel = ReaderPanel.NONE
                controller.setInitData(index, DBCode.BookContentView.DUR_PAGE_INDEX_BEGIN)
            },
            onDismiss = { panel = ReaderPanel.NONE }
        )
    }

    // ---------------- 面板与弹窗 ----------------
    when (panel) {
        // CHAPTER 由布局内的 ChapterListDrawer 承载（自绘覆盖层，非 ModalBottomSheet）
        ReaderPanel.CHAPTER -> Unit
        ReaderPanel.LIGHT -> LightPanel(activity = activity, onDismiss = { panel = ReaderPanel.NONE })
        ReaderPanel.FONT -> FontPanel(
            onTextChange = {
                // 只推版本号：新样式要等下一次重组才成形，重分页由上方 LaunchedEffect(typesetter) 接力
                textKindVersion++
            },
            onBgChange = {
                bgVersion++
                // setStatusBarColor 由上方 LaunchedEffect(bgVersion) 统一处理
            },
            onDismiss = { panel = ReaderPanel.NONE }
        )
        ReaderPanel.SETTING -> MoreSettingPanel(
            onDismiss = { panel = ReaderPanel.NONE },
            onClickTurnChanged = { clickTurnEnabled = it }
        )
        // 下载（已含刷新缓存能力：任务统一带 forceRefresh，勾中已缓存章节即重抓）
        ReaderPanel.DOWNLOAD -> downloadArgs?.let { args ->
            ChapterDownloadSheet(
                chapters = chapters,
                cachedIndices = args.cachedIndices,
                initialSelected = args.initialSelected,
                onConfirm = { selected ->
                    panel = ReaderPanel.NONE
                    startChapterDownload(viewModel, context, selected)
                },
                onDismiss = { panel = ReaderPanel.NONE }
            )
        }
        ReaderPanel.NONE -> Unit
    }

    // 加入书架确认（对齐原 CheckAddShelfPop）
    if (addShelfDialogVisible) {
        AddShelfDialog(
            bookName = bookShelf?.bookInfo?.name ?: stringResource(R.string.unknown_book),
            onExit = {
                addShelfDialogVisible = false
                activity.finish()
            },
            onAddShelf = {
                viewModel.addToShelf(null)
                addShelfDialogVisible = false
            },
            onDismiss = { addShelfDialogVisible = false }
        )
    }
}

/**
 * 发起章节下载：先加入书架 → 按选中索引构建任务列表 → 交给 ViewModel 入库并拉起服务。
 *
 * 任务统一携带 [DownloadChapterEntity.forceRefresh]：下载入口已合并原"强制刷新缓存"入口，
 * 用户显式勾中已缓存章节时必须真正重抓（先删旧内容）；未缓存章节该标记为空操作，
 * 行为与普通下载一致。任务列表按索引升序，保证下载顺序与目录一致。
 */
private fun startChapterDownload(
    viewModel: BookReadViewModel,
    context: Context,
    selected: Set<Int>
) {
    val shelf = viewModel.bookShelf ?: return
    val bookInfo = shelf.bookInfo
    viewModel.addToShelf(object : BookReadViewModel.OnAddListener {
        override fun addSuccess() {
            val result = selected.sorted().mapNotNull { i ->
                viewModel.getChapter(i)?.let { chapter ->
                    DownloadChapterEntity(
                        noteUrl = shelf.noteUrl,
                        durChapterIndex = chapter.durChapterIndex,
                        durChapterName = chapter.durChapterName,
                        durChapterUrl = chapter.contentRef,
                        tag = shelf.tag,
                        bookName = bookInfo?.name ?: context.getString(R.string.unknown_book),
                        coverUrl = bookInfo?.coverUrl ?: "",
                        forceRefresh = true
                    )
                }
            }
            if (result.isEmpty()) return
            // 入库与前台服务拉起统一交给 BookReadViewModel.startDownload：任务先落库，再经
            // DownloadService.start 启动（启动被系统拒绝时任务不丢，见那里的注释）；通知权限在入口
            // 已顺带申请，但拒绝不影响下载（仅看不到进度通知，见 requestDownloadPermission）
            viewModel.startDownload(result)
        }
    })
}
