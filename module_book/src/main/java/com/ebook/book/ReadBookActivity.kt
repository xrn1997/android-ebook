package com.ebook.book

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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.TextUnit
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.lifecycleScope
import com.ebook.book.manager.BitIntentDataManager
import com.ebook.book.mvvm.viewmodel.BookReadViewModel
import com.ebook.book.mvvm.viewmodel.BookReadViewModel.Companion.OPEN_FROM_APP
import com.ebook.book.mvvm.viewmodel.BookReadViewModel.Companion.OPEN_FROM_OTHER
import com.ebook.book.mvvm.viewmodel.SourceSwitchViewModel
import com.ebook.book.reader.AddShelfDialog
import com.ebook.book.reader.ChapterLayoutCache
import com.ebook.book.reader.ChapterLayoutKey
import com.ebook.book.reader.ChapterListDrawer
import com.ebook.book.reader.FontPanel
import com.ebook.book.reader.LightPanel
import com.ebook.book.reader.MoreSettingPanel
import com.ebook.book.reader.ReaderBottomBar
import com.ebook.book.reader.ReaderPager
import com.ebook.book.reader.ReaderPagerController
import com.ebook.book.reader.ReaderPanel
import com.ebook.book.reader.ReaderScroll
import com.ebook.book.reader.ReaderScrollController
import com.ebook.book.reader.ReaderTopBar
import com.ebook.book.reader.ReaderTypesetter
import com.ebook.book.reader.SourceSwitchSheet
import com.ebook.book.reader.SwitchFeedback
import com.ebook.book.reader.applyReaderBrightness
import com.ebook.book.reader.rememberReaderTypesetter
import com.ebook.book.reader.switchFeedbackOf
import com.ebook.book.repository.BookImportRepository
import com.ebook.book.view.ReadBookControl
import com.ebook.common.domain.CommentKey
import com.ebook.common.event.KeyCode
import com.ebook.common.event.RouteArgs
import com.ebook.common.repository.BookRepository
import com.ebook.common.store.ReaderResumeStore
import com.ebook.common.util.reportFailure
import com.ebook.db.entity.BookShelfEntity
import com.ebook.db.event.DBCode
import com.ebook.source.analyze.BookSourceNotFoundException
import com.therouter.TheRouter
import com.therouter.router.Route
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

    /** 翻页控制器引用：音量键翻页由 Activity.onKeyUp 转发（组合外入口） */
    var pagerController: ReaderPagerController? = null

    /**
     * 滚屏控制器引用：音量键滚一屏由 Activity.onKeyUp 转发（组合外入口）。
     * 与 [pagerController] **同时只有一个非空**——「哪个非空」就是当前翻页方式的判据。
     */
    var scrollController: ReaderScrollController? = null

    /** 滚屏模式的块高（px），由 [rePaginate] 按实测落定；0 = 尚未测算 */
    internal var readerBlockHeightPx: Int = 0
        private set

    /** 上一次落定的每屏行数，供翻页方式切换时做落点换算（见 [convertPageIndex]） */
    internal var lastLineCount: Int = 0

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
        viewModel.bookShelf = bookShelf
        // 记下「阅读会话进行中」，供启动页在异常关闭后恢复（见 ReaderResumeStore）
        ReaderResumeStore.markReading(bookShelf.noteUrl)
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
                // 应用外导入后同样记标记：这一类会话被划掉时也该恢复到刚导入的这本书上
                ReaderResumeStore.markReading(result.bookShelf.noteUrl)
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
            // 必须用 getBookWithDetails 而不是 getBookByUrl：后者只给一行 book_shelf，
            // bookInfo/chapterList 是 @Ignore 字段不会被带出来，章节列表为空会让阅读器
            // 停在空白页（loadPage 在 chapterSize == 0 时直接返回 null）
            val shelf = withContext(Dispatchers.IO) { bookRepository.getBookWithDetails(noteUrl) }
            if (shelf == null) {
                Logger.e(TAG, "openBookForResume: 条目已不存在 $noteUrl")
                ToastUtil.showShort(this@ReadBookActivity, getString(R.string.reader_load_failed))
                finish()
                return@launch
            }
            viewModel.bookShelf = shelf
            // 恢复进来后重新落一次标记：用户可能继续读很久再被划掉，标记必须仍是这本书
            ReaderResumeStore.markReading(shelf.noteUrl)
            viewModel.checkInShelf()
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
        val chapter = viewModel.getChapter(bookShelf.durChapter)
        // 章级聚合键：每个 bookKey 都拼 "#" + chapterIndex，逗号分隔传给接收方
        val chapterKeys = bookKeys.joinToString(",") { "$it#${bookShelf.durChapter}" }
        val bundle = Bundle().apply {
            putString(RouteArgs.COMMENT_KEY, chapterKeys)
            putString(RouteArgs.PRIMARY_COMMENT_KEY, writeKey?.let { "$it#${bookShelf.durChapter}" })
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
        // 一次实测同时给出「放得下几行」与「这几行的实测总高」：翻页模式要前者、
        // 滚屏模式还要后者当块高（块与块要精确无缝拼接，不能用心算的行高）。
        // 两种翻页方式共用同一条判据（fitLines），对「一屏几行」只可能同解。
        val metrics = typesetter.measureBlock(width, height) ?: return
        if (metrics.lineCount <= 0) return
        // 样式与行数一起落定：随后的 loadPage 取的就是这份样式，测算与分页不可能错身
        readerTypesetter = typesetter
        viewModel.pageLineCount = metrics.lineCount
        readerBlockHeightPx = metrics.heightPx
        // 换算要用「切换前那个模式的行数」与新行数做对比：这份记录必须早于任何模式切换
        lastLineCount = metrics.lineCount
        val shelf = viewModel.bookShelf ?: return
        if (startFromCurrent) {
            // 两个控制器都收敛：只有当前模式那个会被渲染，但两边共用一个落点，
            // 换模式时不必再补一次初始化（换模式的落点换算见 ReadBookScreen 的 pendingTurnModeSwitch）
            pagerController?.setInitData(shelf.durChapter, shelf.durChapterPage)
            scrollController?.setInitData(shelf.durChapter, shelf.durChapterPage)
        }
    }

    /**
     * 翻页方式切换时的落点换算：按**行号**而不是屏号跨模式对齐。
     *
     * 两种模式的正文视口高度不同（滚屏模式没有块内标题行，视口更高），因此
     * `pageLineCount` 不同、同一个屏号指向的不是同一段字。行号是两边共通的量：
     * 旧模式读到第 oldPage 屏 = 读到了第 `oldPage × oldLineCount` 行，
     * 新模式下含该行的屏是 `lineOffset / newLineCount`。
     *
     * 只在阅读器内切换时调用：冷启动时 `durChapterPage` 与已持久化的模式天然同口径，
     * 不需要也不应该换算。
     */
    internal fun convertPageIndex(oldPageIndex: Int, oldLineCount: Int, newLineCount: Int): Int {
        if (oldLineCount <= 0 || newLineCount <= 0) return 0
        val lineOffset = oldPageIndex.coerceAtLeast(0) * oldLineCount
        return lineOffset / newLineCount
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
     *
     * 失败分两档处置：**书源已失效**（[BookSourceNotFoundException]）除错误态外还要经
     * [reportFailure] 弹一条用户可见提示（ADR-0016 要求「书源已失效，请重新导入或换源」），
     * 因为这条路径用户能自己处置（重导源/换源），静默等于让他对着一页空白摸不着根因；
     * 其余异常（解析失败、排版未就绪等）保持既有的静默降级——只记日志 + 错误态重试按钮。
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

            // 3. 按当前排版求渲染行起始偏移。排版结果走 [layoutCache] 缓存，同章翻页只重排一次；
            //    键的构成（含重解析的内容指纹）见 ChapterLayoutKey
            val width = readerContentWidthPx
            if (width <= 0) return null
            val content = chapterText
            val layoutKey = ChapterLayoutKey(
                contentRef = chapter.contentRef,
                contentLength = content.length,
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
        } catch (e: BookSourceNotFoundException) {
            // 书源失效是**可展示的业务失败**，不能跟着下面的降级一起吞成 null：
            // 那样只剩一行 ERROR 日志，用户侧既没有提示也没有错误态，正是本仓最忌讳的
            // 「页面不闪退、数据永远加载不出来」形态。ADR-0016 明确要求这条路径提示用户
            // 「书源已失效，请重新导入或换源」。
            // 提示一律走共享的 reportFailure（会话过期只记日志、不重复提示由它收口）：
            // 它是 BaseViewModel 的扩展函数，这里经 viewModel 调用，文案进基类的命令通道
            // （MvvmBinder 在主线程消费），不在 Activity 里补 Toast。
            // 文案取字符串资源而非 e.message：异常消息带内部 URL，不适合直接上屏。
            viewModel.reportFailure(e, getString(R.string.book_source_invalid))
            Logger.e(TAG, "loadPage 书源已失效: ${bookShelf.tag} / ${chapter.contentRef}", e)
            null
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

    /** 音量键翻页/滚一屏（受"按键翻页"开关控制），其余按键走系统默认 */
    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (ReadBookControl.canKeyTurn) {
            when (keyCode) {
                KeyEvent.KEYCODE_VOLUME_DOWN -> {
                    // 两个控制器只有一个非空（方式互斥），非空的那个就是当前方式。
                    // 用 when 而不是 `?:`：本类里 `pagerController?.turnNext()` 的返回值
                    // 只为 Unit?，Elvis 的右侧读起来像「失败的兜底」而不是「换个分支」
                    when {
                        pagerController != null -> pagerController?.turnNext()
                        else -> scrollController?.scrollOneScreen(forward = true)
                    }
                    return true
                }
                KeyEvent.KEYCODE_VOLUME_UP -> {
                    when {
                        pagerController != null -> pagerController?.turnPrev()
                        else -> scrollController?.scrollOneScreen(forward = false)
                    }
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
        ReadBookScreen(this, viewModel)
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

    // ---------------- 阅读主题（ReadBookControl 原始色值，正文层豁免深浅色切换） ----------------
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
    // 正文区测量尺寸（Compose 状态，驱动首屏分页启动）；
    // activity.onBodyMeasured 同步存非状态字段供 loadPage 分行使用。
    var bodyWidth by remember { mutableIntStateOf(0) }
    var bodyHeight by remember { mutableIntStateOf(0) }

    val bookShelf = viewModel.bookShelf
    val chapterAll = viewModel.getChapterListSize()

    /**
     * 到达末章时静默查一次目录更新。
     *
     * 挂在 `onProgress` 而不是别处：它是两种翻页方式（左右翻页 / 上下滚屏）**唯一共用**的
     * 进度回调，翻页方式与容器无关，挂在这里就不必各接一遍。
     *
     * 追加成功后复用换源那条已验证的重载路径，差别有三点：不需要整体替换
     * `viewModel.bookShelf`（VM 已就地更新它的 chapterList，阅读器现取该字段）、
     * `startFromCurrent = true`（换源是 false，因为页级进度不跨源）、不调 `gotoPage`
     * （纯追加不改既有章的序号，位置没动）。
     * `sliderValue` 与 `chapterTitle` 是页面本地状态、不随 VM 重组，必须一并跟上，
     * 否则滑条仍按旧的「共 M 章」计算、重组成后读到的章数也停在旧值。
     *
     * 声明必须早于下面两个控制器：它们的 lambda 要引用本函数，而 Kotlin 不允许
     * lambda 前向引用后面才声明的局部 `val`。
     */
    val syncAtTailChapter: (Int) -> Unit = { chapterIndex ->
        if (chapterIndex == viewModel.getChapterListSize() - 1) {
            scope.launch {
                val appended = viewModel.appendChaptersIfAny()
                if (!appended.isNullOrEmpty()) {
                    activity.rePaginate(typesetter, startFromCurrent = true)
                    chapterTitle = viewModel.getChapterTitle(chapterIndex)
                    sliderValue = (chapterIndex + 1).toFloat()
                    ToastUtil.showShort(
                        context,
                        context.getString(R.string.chapters_appended, appended.size),
                    )
                }
            }
        }
    }

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
                syncAtTailChapter(c)
            }
        )
    }
    // 翻页方式的页面级镜像：ReadBookControl.turnModeIndex 不是 Compose State，写入不触发重组。
    // 与 clickTurnEnabled 同一套理由（见其注释）——不能让「换模式是否生效」依赖面板恰好重组。
    var turnModeIndex by remember { mutableIntStateOf(ReadBookControl.turnModeIndex) }

    val scrollController = remember {
        ReaderScrollController(
            scope = scope,
            context = context,
            chapterSize = { viewModel.getChapterListSize() },
            chapterTitle = { viewModel.getChapterTitle(it) },
            loadPage = { c, p -> activity.loadPage(c, p) },
            onProgress = { c, p ->
                // 与翻页模式同一个回调口径：进度落 ViewModel + 菜单标题 + 章节滑条
                viewModel.updateProgress(c, p)
                chapterTitle = viewModel.getChapterTitle(c)
                sliderValue = (c + 1).toFloat()
                syncAtTailChapter(c)
            }
        )
    }
    // 两个控制器都建、只把当前模式那个挂到 Activity：音量键分派靠「哪个非空」判方式（见 onKeyUp）
    DisposableEffect(turnModeIndex) {
        val isPage = turnModeIndex == 0
        activity.pagerController = if (isPage) controller else null
        activity.scrollController = if (isPage) null else scrollController
        onDispose {
            activity.pagerController = null
            activity.scrollController = null
        }
    }

    /**
     * 跳转统一入口：按当前翻页方式路由到对应控制器（目录跳章、进度条跳章、上下章、换页刷新、换源都用它）。
     *
     * 两种控制器的 `setInitData(章号, 页号/哨兵)` 语义相同，故调用方不必知道当前是哪种方式。
     * 直接调某一个控制器的后果不是崩溃而是**静默失效**：滚屏模式下翻页控制器没挂到视图上，
     * 跳章只改了它的状态、页面上什么都不会发生。
     */
    val gotoPage: (chapterIndex: Int, pageIndex: Int) -> Unit = { c, p ->
        if (turnModeIndex == 0) controller.setInitData(c, p) else scrollController.setInitData(c, p)
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
    LaunchedEffect(bgVersion, menuVisible, panel, chromeSurface) {
        val colorUnderStatusBar = if (menuVisible || panel == ReaderPanel.CHAPTER) {
            chromeSurface.toArgb()
        } else {
            ReadBookControl.textBackground
        }
        activity.setStatusBarColor(colorUnderStatusBar.detectColor())
    }

    // 书架归属检查完成事件（原 initBaseViewObservable 的 nextInShelfEvent 收集）
    LaunchedEffect(Unit) {
        viewModel.nextInShelfEvent.collect { bookReady = true }
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

    // 书籍就绪 + 正文区完成测量 → 测算行数并启动翻页（两者先后顺序不定，均在此汇合）
    LaunchedEffect(bookReady, bodyHeight) {
        if (bookReady && !pagerStarted && bodyHeight > 0) {
            pagerStarted = true
            sliderValue = ((viewModel.bookShelf?.durChapter ?: 0) + 1).toFloat()
            val shelf = viewModel.bookShelf
            activity.rePaginate(typesetter, startFromCurrent = false)
            val initChapter = shelf?.durChapter ?: 0
            val initPage = shelf?.durChapterPage ?: DBCode.BookContentView.DUR_PAGE_INDEX_BEGIN
            controller.setInitData(initChapter, initPage)
            // 滚屏控制器同时喂：它此刻没有渲染，但不初始化就会带着「章号 0、块数 0」的
            // 初值躺在那里；用户一换模式，pendingTurnModeSwitch 会在换算后重新定位它，
            // 中间那一帧的落点便是错的
            scrollController.setInitData(initChapter, initPage)
        }
    }

    // 排版上下文换实例（字号/段距变化，或密度变化）→ 按新行高重分页并停在当前页。
    // 由 typesetter 驱动而不是面板回调：面板里改完 ReadBookControl 只是自增版本号，
    // 新样式要等这次重组才生效，直接在回调里重分页就会拿旧样式去量新字号的行数。
    LaunchedEffect(typesetter) {
        if (pagerStarted) activity.rePaginate(typesetter, startFromCurrent = true)
    }

    // 翻页方式切换：新容器要等重组后才成形、新视口尺寸才回报得上来，因此换算不能在面板回调里
    // 立刻做（那会拿旧视口量新布局），而是等 bodyHeight 落到新模式的那一份之后再算。
    // 与「字号变化由 LaunchedEffect(typesetter) 接力」是同一条时序纪律。
    var pendingTurnModeSwitch by remember { mutableStateOf(false) }
    LaunchedEffect(bodyHeight, pendingTurnModeSwitch) {
        if (!pendingTurnModeSwitch) return@LaunchedEffect
        // 先清标记、再判前置条件：首屏尚未启动时这次切换不需要换算（启动流程会把两个控制器
        // 一起收敛到持久化落点），但标记必须清掉——留着它会让此后任意一次 bodyHeight 变化
        // （例如改字号引起的重排）误触发一次换算，把用户当前的位置按行号又搬一次。
        pendingTurnModeSwitch = false
        if (!pagerStarted) return@LaunchedEffect
        val shelf = viewModel.bookShelf ?: return@LaunchedEffect
        val oldLineCount = activity.lastLineCount
        activity.rePaginate(typesetter, startFromCurrent = false)
        val newLineCount = viewModel.pageLineCount
        val target = activity.convertPageIndex(shelf.durChapterPage, oldLineCount, newLineCount)
        viewModel.updateProgress(shelf.durChapter, target)
        if (turnModeIndex == 0) {
            controller.setInitData(shelf.durChapter, target)
        } else {
            scrollController.setInitData(shelf.durChapter, target)
        }
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

    // ---------------- 下载入口（打开「下载中心」直达该书选章二级页） ----------------
    // 下载任务须挂在书架行上才能被 DownloadService 拉取，且二级页按 note_url 读章目录，
    // 故先确保该书在架（原「确认下载时加架」语义提前到入口），成功后再带参打开。
    val openDownloadCenter = {
        menuVisible = false
        viewModel.addToShelf(object : BookReadViewModel.OnAddListener {
            override fun addSuccess() {
                val shelf = viewModel.bookShelf ?: return
                context.startActivity(
                    Intent(context, DownloadManageActivity::class.java).apply {
                        putExtra(DownloadManageActivity.EXTRA_NOTE_URL, shelf.noteUrl)
                        putExtra(DownloadManageActivity.EXTRA_TAG, shelf.tag)
                        putExtra(DownloadManageActivity.EXTRA_FOCUS_CHAPTER, shelf.durChapter)
                        putExtra(DownloadManageActivity.EXTRA_OPEN_PICK, true)
                    }
                )
            }
        })
    }

    // ---------------- 布局 ----------------
    // 章节列表取 bookShelf.chapterList（书架页经 getAllBooksWithDetails() 填充；
    // 本地导入书由 LocalBookImporter 回填）；不用 bookInfo.chapterList（仅网络书解析时填充）
    val chapters = bookShelf?.chapterList ?: emptyList()

    Box(modifier = Modifier.fillMaxSize()) {
        if (turnModeIndex == 0) {
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
        } else {
            ReaderScroll(
                controller = scrollController,
                textColor = textColor,
                bgColor = bgColor,
                textSizeSp = textSizeSp,
                lineHeight = lineHeight,
                blockHeightPx = activity.readerBlockHeightPx,
                canClickTurn = clickTurnEnabled,
                onCenterTap = { menuVisible = !menuVisible },
                // 滚屏的视口尺寸不比翻页模式：块内没有标题行、也没有块内页码行，
                // 所以视口更高、每屏行数更多——这正是两模式 pageLineCount 不同、
                // 切模式要按行号换算落点的原因（见 convertPageIndex）
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
                    if (!viewModel.isAdd) {
                        addShelfDialogVisible = true
                    } else {
                        activity.finish()
                    }
                },
                onDownload = openDownloadCenter,
                onRefresh = {
                    menuVisible = false
                    scope.launch {
                        val position = viewModel.refreshCurrentChapter()
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
                onSliderChange = { sliderValue = it },
                onSliderFinished = {
                    // 对齐原 moveStopProgress：抬手取整跳章
                    var realDur = ceil(sliderValue.toDouble()).toInt()
                    if (realDur < 1) realDur = 1
                    val shelf = viewModel.bookShelf
                    if (shelf != null && realDur - 1 != shelf.durChapter) {
                        gotoPage(realDur - 1, DBCode.BookContentView.DUR_PAGE_INDEX_BEGIN)
                    }
                    if (sliderValue != realDur.toFloat()) sliderValue = realDur.toFloat()
                },
                prevEnabled = sliderValue > 1f,
                nextEnabled = sliderValue < chapterAll.toFloat(),
                onPrevChapter = {
                    viewModel.bookShelf?.let { shelf ->
                        gotoPage(shelf.durChapter - 1, DBCode.BookContentView.DUR_PAGE_INDEX_BEGIN)
                    }
                },
                onNextChapter = {
                    viewModel.bookShelf?.let { shelf ->
                        gotoPage(shelf.durChapter + 1, DBCode.BookContentView.DUR_PAGE_INDEX_BEGIN)
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
            durChapter = bookShelf?.durChapter ?: 0,
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
            onClickTurnChanged = { clickTurnEnabled = it },
            onTurnModeChanged = { index ->
                if (index != turnModeIndex) {
                    turnModeIndex = index
                    // 切换前先记下旧模式的行数：换算要用两个模式的行数。
                    // rePaginate 也会刷新 lastLineCount，故这里必须**早于**那次重分页
                    activity.lastLineCount = viewModel.pageLineCount
                    pendingTurnModeSwitch = true
                }
            }
        )
        // 换源（ADR-0016 决策 8，P3-d）：候选来自跨源聚合搜索，点中即执行仓库那条「先插新、后删旧」事务
        ReaderPanel.SOURCE_SWITCH -> bookShelf?.let { shelf ->
            val switchViewModel: SourceSwitchViewModel = hiltViewModel()
            // 三句反馈在组合期解析好交给回调：回调不是 Composable，在那儿调 context.getString
            // 会踩 lint LocalContextGetResourceValueCall（同上方 chapterTitle 初值的理由）
            val movedFormat = stringResource(R.string.source_switch_moved_format)
            val clampedText = stringResource(R.string.source_switch_clamped)
            val emptyCatalogText = stringResource(R.string.source_switch_empty_catalog)
            SourceSwitchSheet(
                viewModel = switchViewModel,
                oldShelf = shelf,
                onDismiss = { panel = ReaderPanel.NONE },
                onSwitched = { outcome ->
                    panel = ReaderPanel.NONE
                    menuVisible = false
                    // 「当前读的是哪一本」只有一个状态源：BookReadViewModel.bookShelf。
                    // 阅读器的一切都现取这个字段——正文（loadChapter 用它拿 noteUrl/tag/归属）、
                    // 目录（getChapter / getChapterListSize）、进度（updateProgress 写它的章页、
                    // saveProgress 按它的 noteUrl 落库）。所以**整体替换**即让新条目成为唯一事实源：
                    // 旧实体随替换不再被任何地方引用，也就不可能再把旧 noteUrl 的进度写回去
                    // （旧行已被换源事务删掉，写回去等于凭空造一本不存在的书的行）。
                    // 反面做法是「另存一份新条目 + 各处继续读旧条目」——两处各自演进，早晚写错书。
                    viewModel.bookShelf = outcome.newShelf
                    // 换源即换 noteUrl，恢复标记必须跟着换：旧条目的行已被换源事务删掉，
                    // 下次按旧 noteUrl 恢复只会查不到实体、白跑一趟恢复流程
                    ReaderResumeStore.markReading(outcome.newShelf.noteUrl)
                    // 换源是「先插新、后删旧」，此刻新条目确实已在架上；不跟着置真就残留
                    // 「未加入书架」的旧判定，返回时弹一次无意义的加架确认
                    viewModel.isAdd = true
                    // 目录长度已变、页级进度不跨源（仓库把 durChapterPage 复位为「第一页」）：
                    // 按当前样式重分页后从目标章第一页起排，与首屏同一条启动路径
                    activity.rePaginate(typesetter, startFromCurrent = false)
                    gotoPage(outcome.targetChapter, DBCode.BookContentView.DUR_PAGE_INDEX_BEGIN)
                    // 顶栏章节标题与底栏滑条是页面本地状态（不随 VM 重组），必须一并跟上，
                    // 否则换源后标题仍写着旧源「第 N 章 · 共 M 章」
                    chapterTitle = viewModel.getChapterTitle(outcome.targetChapter)
                    sliderValue = (outcome.targetChapter + 1).toFloat()
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
                viewModel.addToShelf(null)
                addShelfDialogVisible = false
            },
            onDismiss = { addShelfDialogVisible = false }
        )
    }
}
