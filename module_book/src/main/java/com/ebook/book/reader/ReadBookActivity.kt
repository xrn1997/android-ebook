package com.ebook.book.reader

import android.content.Intent
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.TextUnit
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.ebook.book.R
import com.ebook.book.download.DownloadManageActivity
import com.ebook.book.importer.BookImportRepository
import com.ebook.book.manager.BitIntentDataManager
import com.ebook.book.reader.BookReadViewModel.Companion.OPEN_FROM_APP
import com.ebook.book.reader.BookReadViewModel.Companion.OPEN_FROM_OTHER
import com.ebook.book.reader.BookReadViewModel.Companion.TAG
import com.ebook.book.view.ReadBookControl
import com.ebook.common.domain.CommentKey
import com.ebook.common.event.KeyCode
import com.ebook.common.event.RouteArgs
import com.ebook.common.repository.BookRepository
import com.ebook.common.store.ReaderResumeStore
import com.ebook.common.util.reportFailure
import com.ebook.db.entity.BookShelfEntity
import com.ebook.db.event.DBCode
import com.therouter.TheRouter
import com.therouter.router.Route
import com.xrn1997.common.mvvm.compose.BaseMvvmActivity
import com.xrn1997.common.ui.LoadingView
import com.xrn1997.common.util.Logger
import com.xrn1997.common.util.ToastUtil
import com.xrn1997.common.util.detectColor
import com.xrn1997.common.util.setStatusBarColor
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
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
 * 配色分两层：正文用「阅读背景主题」（ReadBookControl 的四档纸张色），不随深浅色切换；
 * 菜单/面板（chrome 层）继承全局主题，随外观主题模式走浅色或深色。
 *
 * 三种入口（判定顺序见 ReadBookScreen 的 LaunchedEffect）：
 * - 应用内点书：[Intent] 带 `from=OPEN_FROM_APP` + `data_key`（[BitIntentDataManager] 进程内暂存区）
 * - 应用外打开文本：[Intent] 带 `data`（Uri），导入后加入书架
 * - **启动页恢复**：TheRouter 带 [RouteArgs.RESUME_NOTE_URL]（上次异常关闭时的书），
 *   本条路径跨进程，暂存区为空，按 noteUrl 回 Room 现取实体（见 [openBookForResume]）
 */
@AndroidEntryPoint
@Route(path = KeyCode.Book.READ_PATH)
class ReadBookActivity : BaseMvvmActivity<BookReadViewModel>() {
    override val viewModel: BookReadViewModel by viewModels()

    @Inject
    lateinit var bookImportRepository: BookImportRepository

    @Inject
    lateinit var bookRepository: BookRepository

    /**
     * 阅读器导航：音量键与重分页只经它，不再以「哪个控制器非空」判模式。
     * 由组合期挂入、离开时置空。
     */
    internal var navigation: ReaderNavigation? = null

    /**
     * 分页唯一状态机：视口测量、整章排版缓存、页码换算与切片。
     *
     * 控制器在首次组合就被 `remember` 记住，lambda 捕获的引用不会随字号更新；
     * 样式变化必然伴随一次 [rePaginate]，由 pagination 作为唯一换装点，取值时机
     * 与行数测算严格同步。
     */
    internal val pagination = ReaderPagination()

    /**
     * 装页管线：组合与失败分档归它，本类只接线（见 [loadPage]）。
     *
     * `reportFailure` 走 ViewModel 的共享上报（会话过期只记日志、不重复提示由它收口），
     * 文案取字符串资源而非 `e.message`（异常消息带内部 URL，不适合上屏）。
     */
    private val pageLoader: ReaderPageLoader by lazy {
        ReaderPageLoader(
            loadChapter = { viewModel.session.loadChapter(it) },
            pagination = pagination,
            reportFailure = { e ->
                viewModel.reportFailure(e, getString(R.string.book_source_invalid))
            },
        )
    }

    override fun enableToolbar(): Boolean = false

    override fun enableFitsSystemWindows(): Boolean = false

    override fun initData() {
        // 对齐原实现：进入即保存一次进度（防止异常退出丢失）
        viewModel.saveProgress()
        // 恢复已持久化的手动亮度（窗口亮度不跨生命周期，见 ReaderBrightness.restore）
        ReaderBrightness.restore(this)
    }

    /** 正文区尺寸测量回调：只更新分页状态机持有的视口 */
    fun onBodyMeasured(widthPx: Int, heightPx: Int) {
        pagination.updateViewport(widthPx, heightPx)
    }

    /**
     * 应用内打开书籍（对齐原 openBookFromApp）：
     * 经 BitIntentDataManager 取书架实体；随后发起书架归属检查。
     *
     * 快速失败：数据键缺失或数据为空/类型不符时直接提示并退出——否则 bookShelf 恒为 null，
     * checkInShelf 不会触发，阅读器将停在永久空白页无任何反馈（上游详情页已做前置守卫，
     * 此处为兜底）。
     */
    fun openBookFromApp() {
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
        viewModel.session.adopt(bookShelf)
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
                viewModel.session.adopt(result.bookShelf)
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
     * 恢复阅读（启动页入口）：按 noteUrl 从库里取回书架条目，走与书架点击同一条打开链路。
     *
     * 与 [openBookFromApp] 的差别只在「实体从哪来」：本条路径跨进程冷启动，
     * [BitIntentDataManager] 的进程内暂存区是空的，且 module_main 也拿不到它（暂存区在 module_book 内），
     * 故只能由阅读器自己回 Room 现取——实体只在一处构造，不会出现两份可能不一致的快照。
     *
     * 取不到（书已被删）即提示并退出：阅读器没有可渲染的内容，停在空白页等于让用户对着死页发呆。
     * 启动栈里已垫了主页（见 SplashActivity.tryResumeReading），finish 后自然落回书架。
     */
    fun openBookForResume(noteUrl: String?) {
        if (noteUrl.isNullOrBlank()) {
            Logger.e(TAG, "openBookForResume: noteUrl 缺失")
            finish()
            return
        }
        lifecycleScope.launch {
            // 会话的 openFromStore 走 getBookWithDetails 而不是 getBookByUrl：后者只给一行
            // book_shelf，bookInfo/chapterList 是 @Ignore 字段不会被带出来，章节列表为空会让
            // 阅读器停在空白页（loadChapter 在无目录时直接返回 null）
            if (!viewModel.session.openFromStore(noteUrl)) {
                Logger.e(TAG, "openBookForResume: 条目已不存在 $noteUrl")
                ToastUtil.showShort(this@ReadBookActivity, getString(R.string.reader_load_failed))
                finish()
                return@launch
            }
        }
    }

    /**
     * 跳转章节评论区（M2：跨源评论合并——同一作品多个书源各有 book_group 行，
     * [bookKeys] 为所有关联的书级聚合键，逐一拼章索引后逗号分隔传给评论区做并集查询）。
     *
     * [writeKey] 是这本书的**主键**（`is_primary` 行），单独传：新评论只能写进主键桶，
     * 不能让接收方拿并集列表首元素猜（`getKeysForNoteUrl` 无 ORDER BY，修键后主键是
     * 后插入的那行，猜首元素会把评论写进旧桶，见 spec §9.2）。
     */
    fun navToComment(bookShelf: BookShelfEntity, bookKeys: List<String>, writeKey: String?) {
        // 章级定位取会话的实时位置：`bookShelf` 只是打开时的快照，`durChapter` 不随阅读前进
        val chapterIndex = viewModel.readerState.value.chapterIndex
        val chapter = viewModel.session.chapterAt(chapterIndex)
        // 章级聚合键：每个 bookKey 都拼 "#" + chapterIndex，逗号分隔传给接收方
        val chapterKeys = bookKeys.joinToString(",") { "$it#$chapterIndex" }
        val bundle = Bundle().apply {
            putString(RouteArgs.COMMENT_KEY, chapterKeys)
            putString(RouteArgs.PRIMARY_COMMENT_KEY, writeKey?.let { "$it#$chapterIndex" })
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
     * 行数由 [ReaderTypesetter.measureBlock] 向渲染引擎本身实测得出：正文区高度
     * 放得下几行，一页就切几行。同时把这份排版上下文存下来给 [loadPage] 用——
     * 「测算行数的样式」与「切行、渲染用的样式」必须是同一份，否则又会回到两套判定。
     *
     * 禁止回到「用字体度量估行数」的老路（(高度-段距)/(字高+段距)）：那是拿平台度量
     * 猜 Compose 的几何，每行差零点几像素、25 行就累计出近 20px 的误差。
     *
     * 正文区高度只在首屏与字号变化时重算，因此**正文区高度必须与页面状态无关**：
     * 页码行等内容若在 Loading/Loaded 两态占位不同，这里就会按虚高的高度多算行，
     * 正文渲染时溢出到页码行并被其盖住（占位契约见 ReaderPageCard）。
     */
    internal fun rePaginate(typesetter: ReaderTypesetter, startFromCurrent: Boolean = true) {
        // 一次实测同时给出「放得下几行」与「这几行的实测总高」：翻页模式要前者、
        // 滚屏模式还要后者当块高（块与块要精确无缝拼接，不能用心算的行高）。
        // 两种翻页方式共用同一条判据（fitLines），对「一屏几行」只可能同解。
        pagination.measure(typesetter) ?: return
        val state = viewModel.readerState.value
        if (state.shelf == null) return
        if (startFromCurrent) {
            // 两侧一起收敛（另一侧只预热）：换模式后的落点换算见 ReadBookScreen 的
            // pendingTurnModeSwitch，而那一帧之前另一侧必须已有正确落点
            navigation?.converge(state.chapterIndex, state.pageIndex)
        }
    }

    /**
     * 装一页正文：组合归 [ReaderPageLoader]，本类只提供「按章索引取正文」与失败上报的接线。
     *
     * 取正文 → 后台分页 → 哨兵页码解析 → 切片 → 失败分档都在 loader 里；失败分档的口径
     * （书源失效开口、其余静默降级、取消原样上抛）见其 KDoc。断行仍走 [ReaderPagination]
     * （与页面渲染同一引擎、同一份样式，见 [ReaderTypesetter] 的「分页与渲染必须同源」契约）。
     * 返回 null 表示这一页没装成（控制器置为错误态）。
     */
    suspend fun loadPage(chapterIndex: Int, pageIndex: Int): ReaderPageUi.Loaded? =
        pageLoader.load(chapterIndex, pageIndex)

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

    /** 音量键翻页/滚一屏（受"按键翻页"开关控制），其余按键走系统默认 */
    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (ReadBookControl.canKeyTurn) {
            when (keyCode) {
                KeyEvent.KEYCODE_VOLUME_DOWN -> {
                    navigation?.turnNext()
                    return true
                }
                KeyEvent.KEYCODE_VOLUME_UP -> {
                    navigation?.turnPrev()
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

    /**
     * 正常退出即清掉「阅读会话进行中」标记（见 [ReaderResumeStore]）。
     *
     * 不清的后果是下次启动把这次正常退出误判成异常关闭，每次都强行把用户拉回阅读界面。
     *
     * 判据必须是 `isFinishing`：旋转重建、系统回收（内存压力）同样会走 onDestroy，
     * 但那不属于「用户离开了阅读界面」——重建后用户还在读同一本书，标记必须留下。
     * 反之，进程被划掉/强杀时本方法根本不会执行，标记自然残留，这正是恢复的判据来源。
     */
    override fun onDestroy() {
        if (isFinishing) ReaderResumeStore.clear()
        super.onDestroy()
    }

    @Composable
    override fun PageContent() {
        // 阅读控制器（顶/底栏、目录抽屉、亮度/字体/设置面板、弹窗）跟随外观主题模式的深浅色：
        // 不在本页内层再包一层固定浅色 MaterialTheme，直接继承基类 AppTheme 装配点的主题，
        // chrome 层所有 colorScheme.* 语义色随浅色/深色解析。
        // 正文层与 chrome 层各管一段：纸张配色由 ReadBookControl 显式给出，深浅色切换不动它。
        // 阅读状态由会话持有，此处只收一份快照交给下面渲染；回调里的「当前值」仍读 readerState.value，
        // 免得被首次组合的旧快照钉死。
        val readerState by viewModel.readerState.collectAsStateWithLifecycle()
        ReadBookScreen(this, viewModel, readerState)
    }
}

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
 * 翻页方式索引 → 导航判据：0 = 左右翻页，其余一律按上下滚屏。
 *
 * 索引是设置与持久化的口径（[ReadBookControl.turnModeIndex]），越界值到不了这里——
 * `ReadBookControl` 在读取与写入时都已回落/拒绝。枚举只有 [ReaderTurnMode] 一份，
 * 页面只在这里做一次换算，不要在调用点再写 `index == 0`。
 */
private fun turnModeOf(index: Int): ReaderTurnMode =
    if (index == 0) ReaderTurnMode.PAGED else ReaderTurnMode.SCROLL

/**
 * 阅读页屏幕：翻页容器 + 菜单覆盖层 + 各类面板/弹窗的编排。
 */
@Composable
private fun ReadBookScreen(
    activity: ReadBookActivity,
    viewModel: BookReadViewModel,
    readerState: ReaderSessionState,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    // ---------------- 阅读主题（ReadBookControl 原始色值，正文层豁免深浅色切换） ----------------
    // 设置是可观察快照：面板写入即重组，页面不再维护版本号镜像（见 ReadBookControl.settings）
    val settings by ReadBookControl.settings.collectAsStateWithLifecycle()
    val textColor = Color(settings.textColor)
    val bgColor = Color(settings.textBackground)
    val textSizeSp = settings.textSize.toFloat()
    // 行高 = 单行高度 + 段距（对齐原 lineSpacingExtra 语义）
    val lineHeight: TextUnit = remember(settings.textSize, settings.textExtra) {
        val paint = readerTextPaint(activity.resources)
        val textHeight = paint.descent() - paint.ascent()
        with(density) { (textHeight + settings.textExtra).toSp() }
    }
    // 分页排版上下文：行数测算、切行、正文渲染三方共用的唯一样式来源（契约见 ReaderTypesetter）
    val typesetter = rememberReaderTypesetter(textSizeSp, lineHeight)

    // ---------------- 页面级状态 ----------------
    var menuVisible by remember { mutableStateOf(false) }
    var panel by remember { mutableStateOf(ReaderPanel.NONE) }
    // 点击翻页开关直接读 settings.canClickTurn：面板写入即推进快照，页面不再需要即时同步的镜像。
    // canKeyTurn 同样不镜像——onKeyDown/onKeyUp 是 Activity 回调，运行时直读单例即最新值。
    // 章节标题与滑条都由会话状态派生，页面不再各自镜像：位置一变它们随 readerState 重组。
    // 占位文案走 stringResource：context.getString 的读取不随 Configuration 变化失效
    // （lint LocalContextGetResourceValueCall 判 Error）；本页未声明 android:configChanges，
    // 语言切换走 Activity 重建，重建后的新组合自然取到新语言文案。
    val noChapter = stringResource(R.string.no_chapter)
    // 仅拖动期间存滑条预览值；抬手即清空，非拖动时位置一律取会话派生的 sliderValue
    var sliderPreview by remember { mutableStateOf<Float?>(null) }
    // 菜单收起时清掉预览：拖动中被手势取消时结束回调不会来，半途值会一直压着会话派生的位置，
    // 下次打开底栏就显示一个并不存在的位置。
    LaunchedEffect(menuVisible) {
        if (!menuVisible) sliderPreview = null
    }
    var pagerStarted by remember { mutableStateOf(false) }
    var importingBook by remember { mutableStateOf(false) } // 外部打开文本的导入遮罩
    var addShelfDialogVisible by remember { mutableStateOf(false) }
    // 正文区测量尺寸（Compose 状态，驱动首屏分页启动）；
    // activity.onBodyMeasured 同步存进分页状态机，供 loadPage 分行使用。
    var bodyWidth by remember { mutableIntStateOf(0) }
    var bodyHeight by remember { mutableIntStateOf(0) }

    val bookShelf = readerState.shelf
    val chapterAll = readerState.chapterCount
    val chapterTitle = readerState.chapterTitle ?: noChapter
    val sliderValue = sliderPreview ?: readerState.sliderValue
    // 追更提示在组合期取模板与资源 locale、回调里再 format：syncAtTailChapter 的回调不是
    // Composable，在那儿调 context.getString 会踩 lint LocalContextGetResourceValueCall（同
    // noChapter 的理由）；显式带 locale 是为了保持 context.getString(id, args) 的资源格式化口径
    val chaptersAppendedFormat = stringResource(R.string.chapters_appended)
    val chaptersAppendedLocale = LocalConfiguration.current.locales[0]

    /**
     * 静默查一次目录更新。
     *
     * 挂在 `onProgress` 而不是别处：它是两种翻页方式（左右翻页 / 上下滚屏）**唯一共用**的
     * 进度回调，翻页方式与容器无关，挂在这里就不必各接一遍。是否在末章、是否该发网络，
     * 都由会话的 [ReaderSession.appendIfAtTail] 一手判定（不在末章时直接返回、不发请求），
     * 页面不再持有自己的「第几章 / 共几章」副本。
     *
     * 只有 [ReaderAppendResult.Appended] 需要宿主开口：重分页并提示新增章数。
     * 其余结局（不在末章、归属不符等静默跳过）都不改动页面，`NotAtTail` 是翻页的常态。
     * 追加成功后复用换源那条已验证的重载路径，差别有两点：`startFromCurrent = true`
     * （换源是 false，因为页级进度不跨源）、不调 `gotoPage`（纯追加不改既有章的序号，位置没动）。
     * 标题、滑条与章数都从会话状态派生，目录变了自然重组到新值，不再手工跟进。
     *
     * 声明必须早于下面两个控制器：它们的 lambda 要引用本函数，而 Kotlin 不允许
     * lambda 前向引用后面才声明的局部 `val`。
     */
    val syncAtTailChapter: () -> Unit = {
        scope.launch {
            // 宿主合同：仓库已把网络/解析失败折成非 Appended 结果；逃出来的只有 DB/IO 失败与取消。
            // 取消必须原样上抛，其余记日志即可——追更是静默后台动作，不向用户报错。
            val result = try {
                viewModel.session.appendIfAtTail()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Logger.e(TAG, "追更异常", e)
                null
            }
            if (result is ReaderAppendResult.Appended) {
                activity.rePaginate(typesetter, startFromCurrent = true)
                ToastUtil.showShort(
                    context,
                    chaptersAppendedFormat.format(chaptersAppendedLocale, result.count),
                )
            }
        }
    }

    // ---------------- 翻页导航 ----------------
    // 翻页方式直接读 settings：面板写入即推进快照，页面不再维护索引镜像。
    val turnModeIndex = settings.turnModeIndex

    // navigation 与两个控制器都只 remember 一次；直接捕获 syncAtTailChapter 会把首次组合的
    // typesetter 钉死，改字号后末章追更仍按旧样式重分页（切行与渲染两套样式）。
    val currentSyncAtTailChapter by rememberUpdatedState(syncAtTailChapter)
    val navigation = remember {
        ReaderNavigation(
            initialMode = turnModeOf(turnModeIndex),
            onProgress = { c, p ->
                // 两种方式唯一的进度出口：位置落会话（标题、滑条、章数都从它派生）+ 末章追更。
                // p 为翻页目标的页码（可能为哨兵），与原实现一致直接落库。
                viewModel.session.updateProgress(c, p)
                currentSyncAtTailChapter()
            },
        )
    }
    val controller = remember {
        ReaderPagerController(
            scope = scope,
            context = context,
            chapterSize = { viewModel.readerState.value.chapterCount },
            chapterTitle = { viewModel.session.chapterAt(it)?.durChapterName ?: noChapter },
            loadPage = { c, p -> activity.loadPage(c, p) },
            onProgress = navigation.onProgress,
        )
    }
    val scrollController = remember {
        ReaderScrollController(
            scope = scope,
            context = context,
            chapterSize = { viewModel.readerState.value.chapterCount },
            chapterTitle = { viewModel.session.chapterAt(it)?.durChapterName ?: noChapter },
            loadPage = { c, p -> activity.loadPage(c, p) },
            onProgress = navigation.onProgress,
        )
    }
    // 组合外入口（音量键、重分页）只认识导航；离开阅读器时置空。
    // 装配放在 effect 里：组合期内直接改导航对象的状态会在每次重组重跑，与本文件其余
    // 「写外部对象」的纪律（activity.navigation 同样只在 effect 里赋值）保持一致
    DisposableEffect(navigation, controller, scrollController) {
        navigation.attach(controller, scrollController)
        activity.navigation = navigation
        onDispose { activity.navigation = null }
    }

    /**
     * 跳转统一入口：目录、进度条、上下章、换页刷新、换源都用它。
     *
     * 导航按当前模式路由到对应控制器；直接调某一个会在另一种模式下**静默失效**：滚屏模式下
     * 翻页控制器没挂到视图上，跳章只改了它的状态、页面上什么都不会发生。
     */
    val gotoPage: (chapterIndex: Int, pageIndex: Int) -> Unit = { c, p ->
        navigation.setPosition(c, p)
    }

    // ---------------- 生命周期与事件 ----------------
    // 屏幕常亮（对齐原 fl_content 的 keepScreenOn）
    DisposableEffect(Unit) {
        activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            activity.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // 状态栏图标随「压在它下面的那一层」取色：顶栏与目录抽屉都把背景延伸到状态栏后面
    // （避让 padding 写在底色内层），可见时图标要跟 chrome 的 surface 色而非纸张色，
    // 否则深色控制器 + 素白纸张会把深色图标画在深色栏上。收起菜单后回到纸张色。
    // chrome 两处（顶栏 surfaceContainer / 抽屉 surfaceContainerLow）在任一调板里都落在
    // 阈值同一侧，取一处即够；其余面板是底部弹层，其遮罩按亮度叠在纸张上、不改判据方向。
    val chromeSurface = MaterialTheme.colorScheme.surfaceContainer
    LaunchedEffect(settings.textBackground, menuVisible, panel, chromeSurface) {
        val colorUnderStatusBar = if (menuVisible || panel == ReaderPanel.CHAPTER) {
            chromeSurface.toArgb()
        } else {
            settings.textBackground
        }
        activity.setStatusBarColor(colorUnderStatusBar.detectColor())
    }

    // 打开书籍（对齐原 csvBook.bookReadInit 回调）
    LaunchedEffect(Unit) {
        when {
            // 恢复分支必须**先判**：启动页的恢复跳转不带 `from`，走 getIntExtra 的默认值
            // 会落进「应用外打开文本」分支（那条路要 intent.data，为 null 直接 return，
            // 页面就此停在空白）——判据只能是「有没有恢复参数」，不能靠 from 的缺省值
            activity.intent.hasExtra(RouteArgs.RESUME_NOTE_URL) ->
                activity.openBookForResume(activity.intent.getStringExtra(RouteArgs.RESUME_NOTE_URL))

            activity.intent.getIntExtra("from", OPEN_FROM_OTHER) == OPEN_FROM_APP ->
                activity.openBookFromApp()

            else -> activity.openBookFromOther { importingBook = it }
        }
    }

    // 书籍就绪 + 正文区完成测量 → 测算行数并启动翻页（两者先后顺序不定，均在此汇合）。
    // 就绪判据是会话的 shelfChecked：打开路径落定归属的那一步，不再另设事件。
    LaunchedEffect(readerState.shelfChecked, bodyHeight) {
        if (readerState.shelfChecked && !pagerStarted && bodyHeight > 0) {
            pagerStarted = true
            activity.rePaginate(typesetter, startFromCurrent = false)
            val state = viewModel.readerState.value
            // 两侧一起收敛：另一侧此刻没有渲染，不预热落点就带着「章号 0、块数 0」的初值躺着，
            // 用户一换模式、新容器成形的那一帧落点就是错的（pendingTurnModeSwitch 的换算在那一帧之后）
            navigation.converge(state.chapterIndex, state.pageIndex)
        }
    }

    // 排版上下文换实例（字号/段距变化，或密度变化）→ 按新行高重分页并停在当前页。
    // 由 typesetter 驱动而不是面板回调：面板写入后 settings 快照推进、本次重组才带上新样式，
    // 直接在回调里重分页就会拿旧样式去量新字号的行数。
    LaunchedEffect(typesetter) {
        if (pagerStarted) activity.rePaginate(typesetter, startFromCurrent = true)
    }

    // 翻页方式切换：新容器要等重组后才成形、新视口尺寸才回报得上来，因此换算不能在面板回调里
    // 立刻做（那会拿旧视口量新布局），而是等 bodyHeight 落到新模式的那一份之后再算。
    // 与「字号变化由 LaunchedEffect(typesetter) 接力」是同一条时序纪律。
    var pendingTurnModeSwitch by remember { mutableStateOf(false) }
    // 切换前的行数必须在点击时快照：效应要等新容器的 bodyHeight 回报后才跑，中间可能已经
    // 发生过一次 rePaginate（末章追加/字号变化），那时 pagination.pageLineCount 已是新模式的。
    var modeSwitchOldLineCount by remember { mutableIntStateOf(0) }
    LaunchedEffect(bodyHeight, pendingTurnModeSwitch) {
        if (!pendingTurnModeSwitch) return@LaunchedEffect
        // 先清标记、再判前置条件：首屏尚未启动时这次切换不需要换算（启动流程会把当前模式
        // 收敛到持久化落点），但标记必须清掉——留着它会让此后任意一次 bodyHeight 变化
        // （例如改字号引起的重排）误触发一次换算，把用户当前的位置按行号又搬一次。
        pendingTurnModeSwitch = false
        if (!pagerStarted) return@LaunchedEffect
        val state = viewModel.readerState.value
        if (state.shelf == null) return@LaunchedEffect
        val oldLineCount = modeSwitchOldLineCount
        activity.rePaginate(typesetter, startFromCurrent = false)
        val newLineCount = activity.pagination.pageLineCount
        val target = activity.pagination.convertPageIndex(state.pageIndex, oldLineCount, newLineCount)
        // 定位本身会经控制器的 onProgress 同步写一次进度，不在这里重复 updateProgress
        navigation.setPosition(state.chapterIndex, target)
    }

    // 返回键处置链（对齐原 onBackPressedDispatcher 回调）：
    // 章节目录抽屉（自绘覆盖层）→ 关菜单 → 未加入书架弹确认 → 退出。
    // 其余面板（ModalBottomSheet）与弹窗自带返回处理，优先于本回调消费。
    BackHandler {
        when {
            panel == ReaderPanel.CHAPTER -> panel = ReaderPanel.NONE
            menuVisible -> menuVisible = false
            addShelfDialogVisible -> addShelfDialogVisible = false
            !readerState.inShelf -> addShelfDialogVisible = true
            else -> activity.finish()
        }
    }

    // ---------------- 下载入口（打开「下载中心」直达该书选章二级页） ----------------
    // 下载任务须挂在书架行上才能被 DownloadService 拉取，且二级页按 note_url 读章目录，
    // 故先确保该书在架（原「确认下载时加架」语义提前到入口），成功后再带参打开。
    // 回调只在会话确认这次写入属于当前书时到来（写库期间换书则不回调），不会为另一本书开二级页。
    val openDownloadCenter = {
        menuVisible = false
        viewModel.addToShelf {
            // 焦点章取实时位置：书架快照的 durChapter 停在打开那一刻，不随阅读前进
            val state = viewModel.readerState.value
            val shelf = state.shelf ?: return@addToShelf
            context.startActivity(
                Intent(context, DownloadManageActivity::class.java).apply {
                    putExtra(DownloadManageActivity.EXTRA_NOTE_URL, shelf.noteUrl)
                    putExtra(DownloadManageActivity.EXTRA_TAG, shelf.tag)
                    putExtra(DownloadManageActivity.EXTRA_FOCUS_CHAPTER, state.chapterIndex)
                    putExtra(DownloadManageActivity.EXTRA_OPEN_PICK, true)
                }
            )
        }
    }

    // ---------------- 布局 ----------------
    // 章节列表取会话书架快照的 chapterList（书架页经 getAllBooksWithDetails() 填充；
    // 本地导入书由 LocalBookImporter 回填）；不用 bookInfo.chapterList（仅网络书解析时填充）
    val chapters = bookShelf?.chapterList ?: emptyList()

    Box(modifier = Modifier.fillMaxSize()) {
        if (turnModeOf(turnModeIndex) == ReaderTurnMode.PAGED) {
            ReaderPager(
                controller = controller,
                textColor = textColor,
                bgColor = bgColor,
                textSizeSp = textSizeSp,
                lineHeight = lineHeight,
                canClickTurn = settings.canClickTurn,
                onCenterTap = { menuVisible = !menuVisible },
                onBodySizeChanged = { w, h ->
                    activity.onBodyMeasured(w, h)
                    if (w != bodyWidth) bodyWidth = w
                    if (h != bodyHeight) bodyHeight = h
                },
                modifier = Modifier.fillMaxSize()
            )
        } else {
            ReaderScroll(
                controller = scrollController,
                textColor = textColor,
                bgColor = bgColor,
                textSizeSp = textSizeSp,
                lineHeight = lineHeight,
                blockHeightPx = activity.pagination.blockHeightPx,
                canClickTurn = settings.canClickTurn,
                onCenterTap = { menuVisible = !menuVisible },
                // 滚屏的视口尺寸不比翻页模式：块内没有标题行、也没有块内页码行，
                // 所以视口更高、每屏行数更多——这正是两模式 pageLineCount 不同、
                // 切模式要按行号换算落点的原因（见 ReaderPagination.convertPageIndex）
                onViewportSizeChanged = { w, h ->
                    activity.onBodyMeasured(w, h)
                    if (w != bodyWidth) bodyWidth = w
                    if (h != bodyHeight) bodyHeight = h
                },
                modifier = Modifier.fillMaxSize()
            )
        }

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
                isLocalBook = bookShelf?.tag == BookShelfEntity.LOCAL_TAG,
                onBack = {
                    // 返回箭头 = 退出阅读器（未加入书架先弹确认），不能走 onBackPressedDispatcher：
                    // 处置链首位是"菜单可见→关菜单"，会把退出语义降级为隐藏控制界面；
                    // 硬件返回键仍走 BackHandler（菜单可见时先收菜单，符合阅读器习惯）
                    if (!readerState.inShelf) {
                        addShelfDialogVisible = true
                    } else {
                        activity.finish()
                    }
                },
                onDownload = openDownloadCenter,
                onRefresh = {
                    menuVisible = false
                    scope.launch {
                        val position = viewModel.session.refreshCurrentChapter()
                        if (position != null) {
                            gotoPage(position.first, position.second)
                        }
                    }
                },
                // 换源：收菜单 + 开候选面板，与其他面板共用同一个 panel 状态源
                onSwitchSource = {
                    menuVisible = false
                    panel = ReaderPanel.SOURCE_SWITCH
                },
                onComment = {
                    menuVisible = false
                    readerState.shelf?.let { shelf ->
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
                            activity.navToComment(
                                shelf,
                                effectiveKeys,
                                // 写入键取主键行；无 book_group 行的旧数据回落到并集首元素
                                activity.bookRepository.getPrimaryKeyForBook(shelf.noteUrl)
                                    ?: effectiveKeys.firstOrNull()
                            )
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
                onSliderChange = { sliderPreview = it },
                onSliderFinished = {
                    // 对齐原 moveStopProgress：抬手取整跳章；预览值只活到抬手，随后回到会话派生的位置
                    val preview = sliderPreview ?: readerState.sliderValue
                    var realDur = ceil(preview.toDouble()).toInt()
                    if (realDur < 1) realDur = 1
                    sliderPreview = null
                    val state = viewModel.readerState.value
                    if (state.shelf != null && realDur - 1 != state.chapterIndex) {
                        gotoPage(realDur - 1, DBCode.BookContentView.DUR_PAGE_INDEX_BEGIN)
                    }
                },
                prevEnabled = sliderValue > 1f,
                nextEnabled = sliderValue < chapterAll.toFloat(),
                onPrevChapter = {
                    // 读实时状态：组合快照在同帧连点时还没重组，用它算章号会只步进一次
                    val state = viewModel.readerState.value
                    if (state.shelf != null) {
                        gotoPage(state.chapterIndex - 1, DBCode.BookContentView.DUR_PAGE_INDEX_BEGIN)
                    }
                },
                onNextChapter = {
                    // 同 onPrevChapter：章号必须取会话的实时值，不能用首次组合的旧快照
                    val state = viewModel.readerState.value
                    if (state.shelf != null) {
                        gotoPage(state.chapterIndex + 1, DBCode.BookContentView.DUR_PAGE_INDEX_BEGIN)
                    }
                },
                onCatalog = { panel = ReaderPanel.CHAPTER },
                onLight = { panel = ReaderPanel.LIGHT },
                onFont = { panel = ReaderPanel.FONT },
                onSetting = { panel = ReaderPanel.SETTING }
            )
        }

        // 外部打开文本的导入遮罩：共享 LoadingView（透明遮罩 + 居中卡片，语义对齐原 MoProgressHUD.showLoading）。
        // LoadingView 取当前主题的语义色（chrome 层已随外观主题深浅色），无需自绘 scrim 层
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
            durChapter = readerState.chapterIndex,
            onChapterClick = { index ->
                panel = ReaderPanel.NONE
                gotoPage(index, DBCode.BookContentView.DUR_PAGE_INDEX_BEGIN)
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
            onDismiss = { panel = ReaderPanel.NONE }
        )
        ReaderPanel.SETTING -> MoreSettingPanel(
            onDismiss = { panel = ReaderPanel.NONE },
            onTurnModeChanged = { index ->
                if (index != turnModeIndex) {
                    // 先快照旧模式的行数：效应跑之前若又有一次 rePaginate，现读就拿到新值了
                    modeSwitchOldLineCount = activity.pagination.pageLineCount
                    // 同步改导航判据：pendingTurnModeSwitch 的效应可能在同帧内就调用 setPosition，
                    // 若等 LaunchedEffect 再改 mode，那一次定位会落到旧模式的控制器上
                    navigation.setMode(turnModeOf(index))
                    pendingTurnModeSwitch = true
                }
            }
        )
        // 换源（ADR-0016 决策 8，P3-d）：候选来自跨源聚合搜索，点中即执行仓库那条「先插新、后删旧」事务
        ReaderPanel.SOURCE_SWITCH -> readerState.shelf?.let { shelf ->
            val switchViewModel: SourceSwitchViewModel = hiltViewModel()
            // 三句反馈在组合期解析好交给回调：回调不是 Composable，在那儿调 context.getString
            // 会踩 lint LocalContextGetResourceValueCall（同上方 chapterTitle 初值的理由）
            val movedFormat = stringResource(R.string.source_switch_moved_format)
            val clampedText = stringResource(R.string.source_switch_clamped)
            val emptyCatalogText = stringResource(R.string.source_switch_empty_catalog)
            SourceSwitchSheet(
                viewModel = switchViewModel,
                // 仓库按 `oldShelf.durChapter` 把进度映射到新目录：书架快照里的 durChapter 停在
                // 打开那一刻，直接交出去会把读完几十章的读者拉回开头，故这份「旧条目」带上实时章号
                oldShelf = shelf.copy(durChapter = readerState.chapterIndex),
                onDismiss = { panel = ReaderPanel.NONE },
                onSwitched = { outcome ->
                    panel = ReaderPanel.NONE
                    menuVisible = false
                    // 「当前读的是哪一本」只有一个状态源：会话的书架快照。正文（loadChapter 取它的
                    // noteUrl/tag/归属）、目录（chapterAt / chapterCount）、进度（updateProgress 写实时
                    // 章页，saveProgress 按它的 noteUrl 落库）都从这一份来。所以**整行替换**即让新条目
                    // 成为唯一事实源：旧实体随替换不再被任何地方引用，也就不可能再把旧 noteUrl 的进度
                    // 写回去（旧行已被换源事务删掉，写回去等于凭空造一本不存在的书的行）。
                    // 反面做法是「另存一份新条目 + 各处继续读旧条目」——两处各自演进，早晚写错书。
                    // 换源写的就是书架行本身，故会话同时把 inShelf/shelfChecked 落定为真，
                    // 不会再残留「未加入书架」的旧判定而弹一次无意义的加架确认。
                    viewModel.session.adoptAfterSourceSwitch(outcome.newShelf)
                    // 目录长度已变、页级进度不跨源（仓库把 durChapterPage 复位为「第一页」）：
                    // 按当前样式重分页后从目标章第一页起排，与首屏同一条启动路径
                    activity.rePaginate(typesetter, startFromCurrent = false)
                    gotoPage(outcome.targetChapter, DBCode.BookContentView.DUR_PAGE_INDEX_BEGIN)
                    ToastUtil.showShort(
                        context,
                        when (switchFeedbackOf(outcome.chapterCount, outcome.clamped)) {
                            // 上屏 +1：targetChapter 是 0 基章序号（与 dur_chapter_index 同口径）
                            SwitchFeedback.Moved -> movedFormat.format(outcome.targetChapter + 1)
                            SwitchFeedback.Clamped -> clampedText
                            SwitchFeedback.EmptyCatalog -> emptyCatalogText
                        }
                    )
                }
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
                viewModel.addToShelf { }
                addShelfDialogVisible = false
            },
            onDismiss = { addShelfDialogVisible = false }
        )
    }
}
