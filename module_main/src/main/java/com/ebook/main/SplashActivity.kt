package com.ebook.main

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import android.text.TextUtils
import com.ebook.common.domain.UserSessionManager
import com.ebook.common.event.KeyCode
import com.ebook.common.event.RouteArgs
import com.ebook.common.repository.BookRepository
import com.ebook.common.store.ReaderResumeStore
import com.therouter.TheRouter
import com.xrn1997.common.ui.theme.AppTheme
import com.xrn1997.common.util.Logger
import com.xrn1997.common.util.setStatusBarColor
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.milliseconds

/**
 * 启动页：展示欢迎图，并承担"启动期内容加载"职责（当前为自动登录恢复会话，
 * 未来会迁到后台 service）。
 *
 * 跳转时序（对齐纯 View 时代的职责划分）：
 * - 自动登录与最小展示时长并行进行，**两者都完成后**才决定落点：若上次进程是被划掉/强杀
 *   （见 [tryResumeReading]）就直接落回阅读界面，否则跳 [MainActivity]——
 *   会话在主页首帧渲染前就绪，主页不再出现"先按未登录态渲染、会话到达后再刷新"的二次加载；
 * - 自动登录整体设 [AUTO_LOGIN_TIMEOUT_MS] 超时兜底：弱网/无网不无限阻塞启动；
 * - 「跳过」按钮旁路等待立即决定落点（会话加载随之取消，与原实现一致）。
 *   恢复判定不随跳过被跳掉：它与"等不等会话加载"无关。
 *
 * 独立宿主行为对齐基类（本页不继承 BaseActivity）：
 * 主题经装配点 [AppTheme.Content] 取用（裸 MaterialTheme 是固定浅紫 baseline，会与主页动态取色/深色跟随分裂；
 * 装配点与基类同一主题源，品牌策略变更自动跟随）、
 * `enableEdgeToEdge()` + [setStatusBarColor]（欢迎图延伸到状态栏后的沉浸式观感）。
 */
@SuppressLint("CustomSplashScreen")
@AndroidEntryPoint
class SplashActivity : ComponentActivity() {
    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Inject
    lateinit var userSessionManager: UserSessionManager

    /**
     * 恢复阅读时要按 noteUrl 回查书架条目（只在「上次异常关闭」这条分支上用）。
     * module_main 能直接依赖它是因为仓库住在 lib_book_common；阅读器本身在 module_book，
     * 功能模块互不依赖，故跳转只能走 TheRouter（见 [tryResumeReading]）。
     */
    @Inject
    lateinit var bookRepository: BookRepository

    /** 跳转门控任务：最小展示时长 + 会话预载均完成后进入主页。 */
    private var gateJob: Job? = null

    /** 是否已跳转 [MainActivity]；经 [KEY_NAVIGATED] 持久化，旋转重建后仍生效，兜底防二次跳转。 */
    private var navigated = false

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        installSplashScreen()
        super.onCreate(savedInstanceState)
        // 欢迎图覆盖到状态栏后，图标统一用浅色保证可读
        setStatusBarColor(isLight = false)

        // 旋转重建且此前已跳转主页：直接退出本实例，避免叠开第二个 MainActivity
        // （会话已在旧实例消费并被 UserSessionManager 持久化，无需重跑自动登录）
        if (savedInstanceState?.getBoolean(KEY_NAVIGATED, false) == true) {
            finish()
            return
        }

        // 会话预载与门控跳转：Splash 承担加载职责，主页首帧即就绪。
        // 门控协程切到主线程执行：等待可在任意线程，最终 startActivity 必须在主线程。
        val sessionReady = autoLogin()
        gateJob = activityScope.launch(Dispatchers.Main.immediate) {
            val minSplashShown = async { delay(MIN_SPLASH_MS.milliseconds) }
            // withTimeoutOrNull 超时返回 null：登录慢/失败时放行启动，不卡死闪屏
            val session = async { withTimeoutOrNull(AUTO_LOGIN_TIMEOUT_MS.milliseconds) { sessionReady.await() } }
            minSplashShown.await()
            session.await()
            // 恢复阅读优先于主页：上次异常关闭时用户停在阅读界面，直接落回那里；
            // 恢复不可行（没有标记 / 书已被删 / 路由没落地）时走原主页入口——
            // 后一种情况 tryResumeReading 已经垫好了主页，这次调用被 navigated 挡成空操作
            if (!tryResumeReading()) startMainActivity()
        }

        setContent {
            // 主题经装配点取用：启动页与全 App 同一主题源，品牌策略变更自动跟随
            AppTheme.Content {
                SplashScreen(
                    onSkip = {
                        gateJob?.cancel()
                        // 跳过跳过的是「等会话加载」，不是恢复判定：要不要恢复只取决于
                        // 「上次是否异常关闭」，与用户愿不愿意等会话无关。
                        // 与门控分支共用 tryResumeReading，两条入口的落点判据才不会分裂
                        activityScope.launch(Dispatchers.Main.immediate) {
                            if (!tryResumeReading()) startMainActivity()
                        }
                    }
                )
            }
        }
    }

    /**
     * 会话预载：检查本地持久化的会话，有会话即就绪，不再打登录接口。
     *
     * 返回 [Deferred] 供跳转门控等待；无持久化会话时立即完成（视为就绪）。
     */
    private fun autoLogin(): Deferred<Unit> = activityScope.async {
        val currentUser = userSessionManager.currentUser.value
        val username = currentUser?.username ?: ""

        if (TextUtils.isEmpty(username)) {
            return@async
        }
        // 有持久化会话即就绪，不再打登录接口
        // Token 已在 AndroidUserSessionManager.init 中恢复到 TokenHolder
        Logger.d(TAG, "会话预载完成：用户 $username")
    }

    /**
     * 尝试恢复上次的阅读界面（只在「上次进程没正常收尾」时成立）。
     *
     * 判据来自 [ReaderResumeStore]：阅读器打开书籍时写下 noteUrl，正常退出时清除；
     * 进程被划掉/强杀/崩溃时不会有任何清除动作，标记便残留下来。因此
     * 「有标记」== 「上次异常关闭且当时在读某本书」。
     *
     * 标记与实体存在性缺一不可：标记是个**可能过期的**断言——用户完全可能在那之后
     * 从书架把这本书删掉（或换源换掉了 noteUrl），所以还要回 Room 复核。
     * 复核不通过就顺手清标记：留着它只会让之后每次启动都白跑一趟恢复流程。
     *
     * 为什么**不**先用 `matchRouteMap` 探测阅读器路由是否存在：TheRouter 的路由表是
     * **异步**加载的（`RouteMapKt.asyncInitRouteMap` 走 `TheRouterThreadPool`），
     * 「此刻查不到」既可能是真没这条路由，也可能只是还没加载完。用它当判据会把后者
     * 一并判成「不可恢复」而静默跳过——功能看起来没生效，日志里还写着一句误导的
     * 「没有阅读器路由」。改为「先垫主页、再自己启动阅读器」：路由没落地时
     * 自然停在主页（不白屏），落地了就落回阅读器，两条路都不依赖探测时机。
     *
     * 主页垫底（先 startActivity(MainActivity) 再开阅读器）：视觉上仍直接落在阅读器，
     * 但用户按返回是回到书架，而不是直接退出应用——阅读器不该是任务栈的根。
     *
     * @return true = 已跳阅读器；false = 未恢复（用户已落在主页），调用方无需再跳主页
     */
    private suspend fun tryResumeReading(): Boolean {
        val noteUrl = ReaderResumeStore.pendingNoteUrl() ?: return false
        // 查库切 IO：Room 调用不能在主线程；外层协程是 Main.immediate，仅供启动 Activity
        val book = withContext(Dispatchers.IO) { bookRepository.getBookWithDetails(noteUrl) }
        if (book == null) {
            Logger.d(TAG, "恢复阅读跳过：条目已不存在 $noteUrl")
            ReaderResumeStore.clear()
            return false
        }
        // 与 startMainActivity 同一套防重入守卫：跳过按钮与门控任务可能先后触发
        if (isFinishing || navigated) return false

        // 先垫主页再开阅读器：恢复成功时它就是阅读器下面那一层（返回即回书架）；
        // 启动失败时用户已经落在主页，不会留在闪屏或空白页
        navigated = true
        startActivity(Intent(this, MainActivity::class.java))
        val resumed = startReaderActivity(book.noteUrl)
        if (!resumed) {
            // 路由没落地：标记留着没意义，之后每次启动都会为它白跑一趟查库
            ReaderResumeStore.clear()
        }
        finish()
        return resumed
    }

    /**
     * 启动阅读器（启动页 → 阅读器，本功能唯一的跨模块跳转）。
     *
     * 用 TheRouter 建 Intent（跨模块路由路径的唯一来源，不自己拼类名），但**自己 startActivity**：
     * `navigation()` 不给「路由是否落地」的反馈，而这里必须能区分成败——失败时（module_main
     * 独立运行没有该路由、或路由表异步加载尚未完成）用户应当安静地留在主页。
     *
     * 先判 component 再启动：路由查不到时 TheRouter 会交回一个没有落地页的 Intent，
     * 直接 startActivity 会抛 [ActivityNotFoundException]；判空比靠异常兜底更直白，
     * 异常分支只作为极端情况（如落地页被裁剪掉）的第二道防线保留。
     *
     * @return true = 阅读器已拉起；false = 路由不可用（调用方按降级处置）
     */
    private fun startReaderActivity(noteUrl: String): Boolean {
        val intent = TheRouter.build(KeyCode.Book.READ_PATH)
            .withString(RouteArgs.RESUME_NOTE_URL, noteUrl)
            .createIntent(this)
        if (intent.component == null) {
            Logger.d(TAG, "阅读器路由未落地：${KeyCode.Book.READ_PATH}")
            return false
        }
        return try {
            startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            Logger.e(TAG, "阅读器路由落地页不可启动：${KeyCode.Book.READ_PATH}", e)
            false
        }
    }

    private fun startMainActivity() {
        // 跳过按钮与门控任务可能先后触发（主线程串行，finish 后不再重复跳转）；
        // 旋转重建会迟到一次 onSaveInstanceState，因此再用持久化的 navigated 标志兜底防二次跳转
        if (isFinishing || navigated) return
        navigated = true
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_NAVIGATED, navigated)
    }

    override fun onDestroy() {
        super.onDestroy()
        activityScope.cancel()
    }

    companion object {
        private val TAG: String = SplashActivity::class.java.simpleName

        /** 欢迎图最小展示时长（与原实现的 3 秒延迟一致）；跳过按钮读条倒计时同步使用。 */
        internal const val MIN_SPLASH_MS = 3000L

        /** [onSaveInstanceState] 保存「是否已跳转主页」的 key，旋转重建据此防二次跳转。 */
        private const val KEY_NAVIGATED = "key_navigated"

        /** 自动登录等待上限：超时放行启动，避免弱网卡死闪屏。 */
        private const val AUTO_LOGIN_TIMEOUT_MS = 8000L
    }
}

/** 跳过按钮读条的倒计时步长（文件级私有，供顶层 [SplashScreen] 使用）。 */
private const val COUNTDOWN_STEP_MS = 50L

/** 将毫秒转换为向上取整的秒数（如 2999ms → 3s）。 */
private fun Long.toCeilingSeconds(): Int = ((this + 999) / 1000).toInt()

/**
 * 启动页内容：全屏欢迎图（复刻 activity_splash.xml 的 welcome 背景）+ 右下角「跳过」。
 *
 * 跳过按钮自带 [SplashActivity.MIN_SPLASH_MS] 读条倒计时（剩余秒数 + 进度条递减），
 * 读条仅反映最小展示时长；实际自动跳转由 Activity 的门控任务决定（还需会话就绪），
 * 读条归零但会话未就绪时，按钮文案固定在「跳过 0s」、进度条停空等待，按钮仍可点击旁路。
 */
@Composable
fun SplashScreen(
    onSkip: () -> Unit
) {
    var remainingMs by remember { mutableLongStateOf(SplashActivity.MIN_SPLASH_MS) }

    // 倒计时驱动：小步长递减保证读条平滑；归零后不再消耗帧调度（while 退出）
    LaunchedEffect(Unit) {
        while (remainingMs > 0L) {
            delay(COUNTDOWN_STEP_MS.milliseconds)
            remainingMs = (remainingMs - COUNTDOWN_STEP_MS).coerceAtLeast(0L)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Image(
            painter = painterResource(R.drawable.welcome),
            contentDescription = stringResource(R.string.start_background),
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )
        // 浮在图片上的半透明胶囊：用 inverseSurface 语义色，深浅色模式下均与内容形成对比。
        // 读条到 0 时按钮保持可见（会话未就绪场景），文案固定为「跳过 0s」、进度停空不再变化。
        val seconds = remainingMs.toCeilingSeconds()
        Button(
            onClick = onSkip,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 32.dp, bottom = 64.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.6f),
                contentColor = MaterialTheme.colorScheme.inverseOnSurface
            )
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = stringResource(R.string.skip_countdown, seconds),
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(modifier = Modifier.height(4.dp))
                LinearProgressIndicator(
                    progress = { remainingMs / SplashActivity.MIN_SPLASH_MS.toFloat() },
                    modifier = Modifier
                        .width(56.dp)
                        .height(2.dp),
                    color = MaterialTheme.colorScheme.inverseOnSurface,
                    trackColor = MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = 0.3f)
                )
            }
        }
    }
}
