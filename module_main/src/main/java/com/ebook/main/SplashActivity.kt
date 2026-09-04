package com.ebook.main

import android.annotation.SuppressLint
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
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.milliseconds

/**
 * 启动页：展示欢迎图，并承担"启动期内容加载"职责（当前为自动登录恢复会话，
 * 未来会迁到后台 service）。
 *
 * 跳转时序（对齐纯 View 时代的职责划分）：
 * - 自动登录与最小展示时长并行进行，**两者都完成后**才跳转 [MainActivity]——
 *   会话在主页首帧渲染前就绪，主页不再出现"先按未登录态渲染、会话到达后再刷新"的二次加载；
 * - 自动登录整体设 [AUTO_LOGIN_TIMEOUT_MS] 超时兜底：弱网/无网不无限阻塞启动；
 * - 「跳过」按钮旁路等待立即进入主页（会话加载随之取消，与原实现一致）。
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
            startMainActivity()
        }

        setContent {
            // 主题经装配点取用：启动页与全 App 同一主题源，品牌策略变更自动跟随
            AppTheme.Content {
                SplashScreen(
                    onSkip = {
                        gateJob?.cancel()
                        startMainActivity()
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
