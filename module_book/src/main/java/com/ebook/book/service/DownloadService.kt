package com.ebook.book.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import com.xrn1997.common.util.Logger
import com.xrn1997.common.util.ToastUtil
import androidx.core.app.NotificationCompat
import com.ebook.book.R
import com.ebook.book.repository.DownloadRepository
import com.ebook.book.repository.DownloadState
import com.ebook.common.analyze.local.BookLocation
import com.ebook.common.analyze.local.BookFormat
import com.ebook.common.analyze.local.ChapterEntry
import com.ebook.common.analyze.source.JsoupSourceReader
import com.ebook.common.store.BookStore
import com.ebook.common.store.ChapterContentCache
import com.ebook.db.entity.DownloadChapterEntity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.time.Duration.Companion.milliseconds

/**
 * 离线下载前台服务：逐章抽取正文写入章文件并维护 `download_chapter` 任务队列。
 *
 * 对外契约全部是 Intent（无 binder，[onBind] 返回 null）：
 * - 携带任务启动：[buildStartIntent]（阅读器确认下载范围后直达）
 * - 控制动作：[ACTION_PAUSE] / [ACTION_RESUME] / [ACTION_CANCEL]（通知按钮与下载管理页共用）
 * - 进度回传：[DownloadRepository.downloadState]（Service → UI）+ 常驻通知
 *
 * 为何不用命令总线：原 `DownloadCommand` SharedFlow 通道 replay=0，唯一订阅者就是本服务，
 * 服务未存活/被回收时命令静默丢失（任务不跑、按钮没反应）；Intent 由系统保证送达
 * （START_STICKY 重启后仍能按库中未完成任务续跑）。
 */
@Suppress("unused")
@AndroidEntryPoint
class DownloadService : Service() {
    @Inject lateinit var downloadRepository: DownloadRepository
    @Inject lateinit var jsoupSourceReader: JsoupSourceReader
    @Inject lateinit var bookStore: BookStore

    /**
     * 正文内存缓存（进程级单例）。强制刷新重抓后必须失效它，见 [downloading] 内的调用点。
     */
    @Inject lateinit var contentCache: ChapterContentCache
    private lateinit var notifyManager: NotificationManager
    private var isStartDownload = false
    private var isInit = false

    /**
     * 前台化是否失败（AndroidManifest 声明了 dataSync 类型，配额用尽/后台启动被拒时 startForeground 会抛）。
     *
     * 仅用于“无携带任务”的自动续跑分支：没有前台态的下载服务既跑不久、又会被 START_STICKY
     * 反复拉起刷同一异常，不如直接收尾并提示用户（见 [onStartCommand]）。
     */
    private var fgUnavailable = false
    private var isDownloading = false

    /**
     * 本批下载中「重试耗尽被跳过」的章数。
     *
     * 失败章只出队不入库（见 [downloading]），若不计数，收尾提示会一律说「全部下载完成」，
     * 用户以为失败章也下好了。收尾（[finishDownload]）与取消（[cancelDownload]）时归零。
     */
    private var skippedCount = 0
    private val myHandler = Handler(Looper.getMainLooper())
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onDestroy() {
        super.onDestroy()
        myHandler.removeCallbacksAndMessages(null)  // 清理所有待执行的回调
        serviceScope.cancel()
        isInit = false
    }

    /**
     * 前台服务时长配额超时回调（Android 15 / API 35 引入，对 targetSdk 35+ 生效；本项目 targetSdk 37）。
     *
     * 本服务在 manifest 里声明为 `foregroundServiceType="dataSync"`（见 module_book/src/main/AndroidManifest.xml），
     * 系统只允许 dataSync 前台服务在任意 24 小时内累计运行 6 小时——整本书逐章抓取（每章之间还刻意
     * 间隔 800ms）很容易撞上。到点时系统**先摘掉前台态**再回调本方法，并且只留数秒钟让服务自行退出；
     * 没有及时 `stopSelf()` 会被记
     * `RemoteServiceException: A foreground service of type dataSync did not stop within its timeout`，
     * 现象就是"下载跑到后段整个应用闪退"。
     *
     * 所以方法体内只做"数秒内必定完成"的收尾，禁止任何挂起/IO 操作：
     * - 关掉 [isStartDownload]/[isDownloading] 并清空待执行回调，让按章推进的循环就地停住；
     * - 不动 `download_chapter` 队列：表里本就只存未完成任务，用户把应用切回前台（这会重置 24h 配额）
     *   后从下载管理页或通知「继续」即可续跑；
     * - 状态改走 [DownloadRepository.tryEmitState] 同步写入 replay 缓冲：收尾路径不依赖协程
     *   调度与消息队列顺序（`serviceScope` 虽为 Main、launch 块实际能先于 onDestroy 执行，
     *   但此处不赌这个顺序），状态必在 stopSelf 前落入 replay；
     * - 提示以通知为主：超时多发生在应用已退到后台之后，Toast 用户根本看不到。
     */
    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    override fun onTimeout(startId: Int, fgsType: Int) {
        Logger.w(TAG, "前台服务时长配额用尽（fgsType=$fgsType, startId=$startId），停止下载并保留任务")
        isStartDownload = false
        isDownloading = false
        myHandler.removeCallbacksAndMessages(null)
        downloadRepository.tryEmitState(DownloadState.Paused)
        postAttentionNotification(getString(R.string.notification_fgs_timeout_text))
        stopSelf()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!isInit) {
            isInit = true
            notifyManager = getSystemService(NotificationManager::class.java)

            // 前台化初始化：通道 + 驻留通知 + startForeground。
            // 设备侧包归属缓存错乱（"Package xxx is not owned by uid"）时通知类 API 可能抛
            // SecurityException；此处兜底不让初始化失败阻断任务接入——前台化失败时系统可能
            // 后续回收服务，但优于在 onStartCommand 直接崩溃（任务彻底丢失）
            try {
                createNotificationChannels()
                // 必须同步 startForeground：startForegroundService 拉起后 5s 内必须交出前台通知；
                // 此时任务尚未入库，剩余数传 null（不查库），第一条进度通知紧随其后覆盖文案
                startForeground(
                    ONGOING_NOTIFY_ID,
                    buildOngoingNotification(
                        getString(R.string.notification_preparing),
                        remaining = null,
                        paused = false
                    )
                )
            } catch (e: Throwable) {
                // 通知不可用并不走这里（Android 13+ 未授权时 notify 被静默丢弃、startForeground 仍成功），
                // 能走到这里基本就是“没法前台化”：记下该标记，让自动续跑分支知道再跑也没有意义
                fgUnavailable = true
                Logger.e(TAG, "前台服务初始化失败（本次不再自动续跑）: ", e)
            }
        }

        // 控制动作（通知按钮 / 书架弹窗按钮）经 Intent action 直达：原命令通道是 replay=0 的
        // SharedFlow，服务被回收后无订阅者，命令静默丢失（按钮"点了没反应"）
        when (intent?.action) {
            ACTION_PAUSE -> {
                pauseDownload()
                return START_STICKY
            }
            ACTION_RESUME -> {
                // 已在跑则不重复 startDownload：toDownload 会重置 isDownloading 并再取一次任务，
                // 同一章节可能被并发抓取
                if (!isStartDownload) startDownload()
                return START_STICKY
            }
            ACTION_CANCEL -> {
                cancelDownload()
                return START_STICKY
            }
        }

        // 任务随 Intent 直达：阅读器确认下载范围后用 buildStartIntent 携带章节列表启动本服务。
        // 原链路靠 SharedFlow 命令中转（唯一订阅者绑在书架页生命周期上），页面不存活时命令被丢弃、
        // 下载根本不启动；Intent extra 由系统直达，无时序依赖（见本类 KDoc 的"为何不用命令总线"）
        val chapters = intent?.let { extractChapters(it) }.orEmpty()
        if (chapters.isNotEmpty()) {
            addNewTask(chapters)
        } else if (!isStartDownload && !isDownloading) {
            // 无携带任务（书架弹窗打开时拉起 / START_STICKY 重启）：有未完成任务则续跑，无则收尾退出，
            // 避免前台服务空转。弹窗/通知上的"继续"按钮另走上方 ACTION_RESUME 分支
            if (fgUnavailable) {
                // 前台态拿不到（dataSync 配额用尽、后台启动被拒、或通知链路异常）：以普通后台服务
                // 续跑既跑不久、又会被系统反复重启刷同一异常，故直接收尾并留一条可点回应用的提示。
                // 回 START_NOT_STICKY：配额/可见性未恢复前，让系统别重建这个注定空转的服务，
                // 任务仍在库里，用户回到前台重试即可
                Logger.w(TAG, "前台态不可用，跳过自动续跑（任务保留）")
                postAttentionNotification(getString(R.string.notification_fgs_timeout_text))
                // 与 onTimeout 同一写法：tryEmitState 同步落 replay，不赌 serviceScope 调度顺序
                downloadRepository.tryEmitState(DownloadState.Paused)
                stopService(Intent(application, DownloadService::class.java))
                return START_NOT_STICKY
            }
            serviceScope.launch {
                try {
                    val next = findNextDownloadChapter()
                    if (next != null && next.noteUrl.isNotEmpty()) {
                        isStartDownload = true
                        toDownload()
                    } else {
                        // 无任务：静默退出（不能走 finishDownload，其"下载全部完成"提示仅限真正跑完任务）
                        stopService(Intent(application, DownloadService::class.java))
                    }
                } catch (e: Throwable) {
                    Logger.e(TAG, "onError: ", e)
                }
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder? {
        return null
    }

    private fun addNewTask(newData: List<DownloadChapterEntity>) {
        isStartDownload = true
        serviceScope.launch {
            try {
                downloadRepository.addTasks(newData)
                if (!isDownloading) {
                    toDownload()
                }
            } catch (e: Throwable) {
                Logger.e(TAG, "onError: ", e)
            }
        }
    }

    private fun toDownload() {
        isDownloading = true
        if (isStartDownload) {
            serviceScope.launch {
                try {
                    val nextChapter = findNextDownloadChapter()
                    if (nextChapter != null && nextChapter.noteUrl.isNotEmpty()) {
                        downloading(nextChapter)
                    } else {
                        downloadRepository.clearAllTasks()
                        isDownloading = false
                        finishDownload()
                    }
                } catch (e: Throwable) {
                    Logger.e(TAG, "onError: ", e)
                    isDownloading = false
                }
            }
        } else {
            isPause()
        }
    }

    /**
     * 查找下一个待下载章节
     */
    private suspend fun findNextDownloadChapter(): DownloadChapterEntity? {
        return downloadRepository.getNextDownloadTask()
    }

    /**
     * 抓取单章正文并写入章文件，失败重试至多 [RETRY_TIMES] 次。
     *
     * **重试耗尽必须把任务出队**：[findNextDownloadChapter] 取的是「书架第一本非本地书里
     * dur_chapter_index 最小」的任务，失败任务留在表里会让下一轮 [toDownload] 拿到同一章再重试——
     * 外层既无退避也无上限，等于无限循环（常驻通知永不消失、前台服务永不停止、持续耗电），
     * 且队头被堵住后该书后续章节一章也下不了。
     *
     * 失败章按「跳过」而非「永久失败」处理：只出队、不写正文，用户重新发起下载或直接阅读该章
     * 仍会重抓；本批结束时把跳过章数带进提示（见 [finishDownload]），避免静默丢章。
     *
     * 暂停（[isStartDownload] 置 false）会中断重试循环，此时**不出队**：任务保留，
     * 点「继续」后从头重试，符合暂停语义。
     */
    private fun downloading(data: DownloadChapterEntity) {
        if (!isStartDownload) {
            isPause()
            return
        }
        isProgress(data)
        serviceScope.launch {
            var attempt = 0
            var success = false
            while (attempt < RETRY_TIMES && isStartDownload && !success) {
                attempt++
                try {
                    val location = BookLocation(data.noteUrl, BookFormat.NETWORK)

                    // 已有章文件且非强制刷新：命中即视为完成，直接出队
                    if (!data.forceRefresh && bookStore.hasChapter(location, data.durChapterIndex)) {
                        downloadRepository.deleteTask(data)
                        success = true
                        continue
                    }

                    // 强制刷新时只删这一章的章文件：不删的话 JsoupSourceReader.readChapter 命中
                    // 旧文件，重抓结果永远不生效。注意不能图省事调 deleteBook——那是整本书目录，
                    // 重下第 N 章会把其余已缓存章节一并清掉
                    if (data.forceRefresh && bookStore.hasChapter(location, data.durChapterIndex)) {
                        bookStore.deleteChapter(location, data.durChapterIndex)
                    }

                    // 从网络抓取正文并写入章文件（JsoupSourceReader 内部完成文件写入）
                    val entry = ChapterEntry(
                        index = data.durChapterIndex,
                        title = data.durChapterName,
                        contentRef = data.durChapterUrl,
                    )
                    val content = jsoupSourceReader.readChapter(entry, location)

                    // 空正文判失败：正文选择器失配（反爬页/改版页）时站点回的是 HTTP 200 空壳页，
                    // 抓取侧此时**不写章文件**（见 JsoupSourceReader.fetchAndStore），这里抛错走
                    // 重试；重试耗尽只出队不入库。若照单当成功，任务被删而缓存里空着——离线打开
                    // 就是空白页，且后续 hasChapter 也救不回来
                    if (content.paragraphs.none { it.isNotBlank() }) {
                        throw IllegalStateException("章节内容解析失败: ${data.durChapterUrl}")
                    }

                    downloadRepository.deleteTask(data)
                    // 强制刷新重抓成功后失效正文内存缓存（spec §7 的「章节重解析」失效条件）：
                    // ChapterContentCache 是进程级单例、键为 content_ref（网络书即章节 URL，
                    // 重抓前后不变），不失效则已打开的阅读器会继续供给旧正文直到 LRU 挤出或进程重启。
                    // 放在空正文校验之后——抓取失败时章文件未被重写，缓存里的旧正文仍是磁盘真相
                    if (data.forceRefresh) {
                        contentCache.invalidateBook(data.noteUrl)
                    }
                    Logger.d(TAG, "downloading: ${data.durChapterUrl}")
                    success = true
                } catch (e: Throwable) {
                    // 服务销毁/作用域取消时必须放行取消异常：吞掉它会让循环在已取消的作用域里
                    // 再空转几轮（每个挂起点都立即抛取消），还会把「取消」误记成「抓取失败被跳过」
                    if (e is CancellationException) throw e
                    Logger.e(TAG, "章节下载失败（第 $attempt/$RETRY_TIMES 次）: ${data.durChapterUrl}", e)
                    if (attempt < RETRY_TIMES) {
                        delay(RETRY_DELAY_MS.milliseconds) // 重试前等待，避开瞬时限流/抖动
                    }
                }
            }

            // 重试**真的耗尽**（而非被暂停打断）才出队并计数，见方法 KDoc 的暂停语义
            if (!success && attempt >= RETRY_TIMES) {
                skippedCount++
                Logger.w(TAG, "重试 $RETRY_TIMES 次仍失败，跳过本章并出队: ${data.durChapterUrl}")
                try {
                    downloadRepository.deleteTask(data)
                } catch (e: Throwable) {
                    Logger.e(TAG, "失败任务出队异常: ", e)
                }
            }

            // 继续下载下一个
            if (isStartDownload) {
                myHandler.postDelayed({
                    if (isStartDownload) {
                        toDownload()
                    } else {
                        isPause()
                    }
                }, CHAPTER_INTERVAL_MS)
            } else {
                isPause()
            }
        }
    }

    /** 恢复/开始下载（仅供 [ACTION_RESUME] 分支调用：对外契约统一为 Intent action） */
    private fun startDownload() {
        isStartDownload = true
        toDownload()
    }

    /** 暂停当前批次（任务保留），供 [ACTION_PAUSE] 分支调用 */
    private fun pauseDownload() {
        isStartDownload = false
        // 不能 cancelAll：那会把前台服务常驻通知一并清掉——服务还活着（暂停态）但用户
        // 以为任务没了。这里只把常驻通知改写为"已暂停"。
        updateOngoingNotification(
            getString(R.string.notification_paused_text),
            paused = true
        )
    }

    /** 清空队列并收尾退出，供 [ACTION_CANCEL] 分支调用 */
    private fun cancelDownload() {
        isStartDownload = false
        skippedCount = 0
        // 终态走 tryEmitState 同步落 replay（与 finishDownload、前台态不可用分支同一写法）：
        // 不依赖协程调度，界面立刻收 Finished、通知即刻撤下，不用等 DB 清队耗时
        downloadRepository.tryEmitState(DownloadState.Finished)
        // 只撤常驻通知（不用 cancelAll）：保留同时存在于其它 id 上的结果通知
        cancelNotify(ONGOING_NOTIFY_ID)
        serviceScope.launch {
            try {
                downloadRepository.clearAllTasks()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // 清队失败不阻断停服（取消意图优先），但必须留痕：否则残留任务会在下次批次里
                // 复活已取消的书，且无日志无从排查
                Logger.w(TAG, "取消下载清空队列失败，任务可能残留", e)
            }
            // stopService 必须跟在清队之后（同一协程内）：onDestroy → serviceScope.cancel
            // 是清队协程唯一的取消源，停服先发出会让清队被吞 → 取消的书复活
            stopService(Intent(application, DownloadService::class.java))
        }
    }

    private fun isPause() {
        isDownloading = false
        serviceScope.launch {
            try {
                val nextChapter = findNextDownloadChapter()
                if (nextChapter != null && nextChapter.noteUrl.isNotEmpty()) {
                    downloadRepository.emitState(DownloadState.Paused)
                } else {
                    downloadRepository.emitState(DownloadState.Finished)
                }
            } catch (e: Throwable) {
                Logger.e(TAG, "onError: ", e)
            }
        }
    }

    /**
     * 每章开抓时推进度：回传 UI 状态 + 刷新常驻通知。
     *
     * 两者都不得抛回调用链：[downloading] 是同步调用本方法的，原实现里通知异常会
     * 沿 downloading → toDownload 的 catch 吞掉并置 isDownloading=false，
     * **整条下载流水线静默死亡**（后续章节不下载、也不再发 Progress）；
     * 现收敛到 [updateOngoingNotification] 内部整体兜底。
     */
    private fun isProgress(downloadChapter: DownloadChapterEntity) {
        serviceScope.launch {
            downloadRepository.emitState(DownloadState.Progress(downloadChapter))
        }
        updateOngoingNotification(
            getString(
                R.string.notification_progress_format,
                downloadChapter.bookName,
                downloadChapter.durChapterName
            ),
            paused = false
        )
    }

    // ---------------- 通知 ----------------

    /**
     * 创建下载通知通道，并清理历史通道。
     *
     * 分两条：进行中/已暂停的常驻通知走 LOW（不弹横幅、不响铃，只占状态栏），
     * 完成通知走 DEFAULT（值得弹一次横幅）。通道重要性创建后就由系统/用户固化，
     * 改现有通道无效，所以新建后用新 id；旧通道 "40"（"App Service"）同步删除，
     * 否则用户通知设置里会留下重复且无用的项。
     */
    private fun createNotificationChannels() {
        val ongoing = NotificationChannel(
            CHANNEL_ONGOING,
            getString(R.string.notification_channel_ongoing),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_ongoing_desc)
            setShowBadge(false)
        }
        val done = NotificationChannel(
            CHANNEL_DONE,
            getString(R.string.notification_channel_done),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = getString(R.string.notification_channel_done_desc)
        }
        notifyManager.createNotificationChannels(listOf(ongoing, done))
        notifyManager.deleteNotificationChannel(LEGACY_CHANNEL)
    }

    /**
     * 构造常驻（进行中/已暂停）通知。
     *
     * [NotificationCompat.Builder.setOngoing] 是关键：原实现用不带 ongoing、还带 `setAutoCancel(true)` 的进度通知
     * 覆盖与 startForeground 同一 id 的常驻通知，等于把它降级成可划走的临时通知；
     * Android 13+ 本身就允许用户消除前台服务通知，一划掉后就再也看不到进度。
     * [NotificationCompat.Builder.setOnlyAlertOnce] 避免每章更新都响一次。动作按钮直接指向本服务的 Intent action，
     * 暂停/取消不必回到书架弹窗才能操作。
     */
    private fun buildOngoingNotification(
        contentText: String,
        remaining: Int?,
        paused: Boolean
    ): Notification {
        val actionTextRes = if (paused) R.string.notification_action_resume else R.string.notification_action_pause
        val actionName = if (paused) ACTION_RESUME else ACTION_PAUSE
        return NotificationCompat.Builder(this, CHANNEL_ONGOING)
            .setSmallIcon(R.drawable.ic_notification_download)
            .setContentTitle(getString(R.string.offline_download))
            .setContentText(contentText)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .apply { remaining?.let {
                setSubText(getString(R.string.notification_remaining_format, it))
            } }
            .addAction(0, getString(actionTextRes), commandPendingIntent(actionName))
            .addAction(0, getString(R.string.notification_action_cancel), commandPendingIntent(ACTION_CANCEL))
            .setContentIntent(launcherPendingIntent())
            .build()
    }

    /**
     * 发送/更新常驻通知（尽力而为）。
     *
     * 通知不可用时（Android 13+ 未授予 POST_NOTIFICATIONS，或用户关了开关）直接跳过：
     * 此时 notify 会被系统静默丢弃，而前台服务本身不受影响——**下载不得因为通知发不出去而停摆**。
     * 查库与发送整体兜底，异常一律不回抛（调用链在同步下载循环上，见 [isProgress]）。
     */
    private fun updateOngoingNotification(contentText: String, paused: Boolean) {
        serviceScope.launch {
            try {
                if (!notifyManager.areNotificationsEnabled()) {
                    Logger.d(TAG, "通知不可用，跳过下载通知更新")
                    return@launch
                }
                notifyManager.notify(
                    ONGOING_NOTIFY_ID,
                    buildOngoingNotification(contentText, downloadRepository.countTasks(), paused)
                )
            } catch (e: Throwable) {
                Logger.e(TAG, "下载通知更新失败（不影响下载流程）: ", e)
            }
        }
    }

    /**
     * 发送"下载完成"通知（可点、可划走；失败不影响服务收尾）。
     *
     * [skipped] > 0 时换文案：失败章是被跳过的（见 [downloading]），继续说"全部已入库"
     * 会让用户误以为离线能读到那几章。
     */
    private fun postCompletedNotification(skipped: Int) {
        try {
            if (!notifyManager.areNotificationsEnabled()) return
            val contentText = if (skipped > 0) {
                getString(R.string.notification_done_with_skipped_format, skipped)
            } else {
                getString(R.string.notification_done_text)
            }
            val notification = NotificationCompat.Builder(this, CHANNEL_DONE)
                .setSmallIcon(R.drawable.ic_notification_download)
                .setContentTitle(getString(R.string.notification_done_title))
                .setContentText(contentText)
                .setOngoing(false)
                .setAutoCancel(true)
                .setContentIntent(launcherPendingIntent())
                .build()
            notifyManager.notify(DONE_NOTIFY_ID, notification)
        } catch (e: Throwable) {
            Logger.e(TAG, "完成通知发送失败（不影响下载流程）: ", e)
        }
    }

    /**
     * 发送一条"需要用户回到应用"的提示通知（尽力而为）。
     *
     * 不复用常驻通知：服务一停，前台服务通知会被系统一并移除，配额超时恰恰是"服务已经不在了"
     * 的场景，必须留一条可点回应用的独立通知（复用 [DONE_NOTIFY_ID] 与 DEFAULT 通道，允许弹横幅）。
     */
    private fun postAttentionNotification(text: String) {
        try {
            if (!notifyManager.areNotificationsEnabled()) return
            val notification = NotificationCompat.Builder(this, CHANNEL_DONE)
                .setSmallIcon(R.drawable.ic_notification_download)
                .setContentTitle(getString(R.string.offline_download))
                .setContentText(text)
                .setOngoing(false)
                .setAutoCancel(true)
                .setContentIntent(launcherPendingIntent())
                .build()
            notifyManager.notify(DONE_NOTIFY_ID, notification)
        } catch (e: Throwable) {
            Logger.e(TAG, "超时提示通知发送失败（不影响收尾）: ", e)
        }
    }

    /** 撤销指定通知（不用 cancelAll：会连带抹掉其他用途的通知） */
    private fun cancelNotify(id: Int) {
        try {
            notifyManager.cancel(id)
        } catch (e: Throwable) {
            Logger.e(TAG, "取消通知失败: ", e)
        }
    }

    /**
     * 通知点击意图：跳 launcher 主页（= module_main 的 MainActivity）。
     *
     * module_book 不能依赖 module_main，故用 launcher Intent 间接跳转（原实现误用
     * Fragment::class.java 构造 Intent，PendingIntent.getActivity 点击即崩溃）；
     * launcher 解析失败返回 null，调用侧降级为无点击意图。下载管理页落地后应改为直达该页。
     */
    private fun launcherPendingIntent(): PendingIntent? =
        packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(
                this, 0, it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

    /**
     * 动作按钮意图：以 Intent action 直达 [onStartCommand]。
     *
     * 用 [PendingIntent.getForegroundService] 而非 getActivity：服务已被回收时也能先拉起再执行动作
     * （已存活时仅多一次 onStartCommand，各 action 分支均幂等）。
     * requestCode 按 action 区分，避免同 Intent 不的 PendingIntent 相互覆盖。
     */
    private fun commandPendingIntent(action: String): PendingIntent =
        PendingIntent.getForegroundService(
            this, action.hashCode(),
            Intent(this, DownloadService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    /**
     * 队列跑空：发完成态 + 完成通知 + Toast，然后停服务。
     *
     * 有跳过章时换带章数的文案（[skippedCount]）：一律说"全部下载完成"会让用户
     * 以为重试耗尽被跳过的章节也下好了。
     */
    private fun finishDownload() {
        val skipped = skippedCount
        // 终态走 tryEmitState 同步落 replay（与 onTimeout、前台态不可用分支同一写法）：
        // 不起协程就不赌调度顺序，避免紧随的 stopService → onDestroy 取消 serviceScope 把它吞掉，
        // 那会让界面停在"正在下载"（同下方通知"必须同步发送"的理由）
        downloadRepository.tryEmitState(DownloadState.Finished)
        // 完成通知走独立 id + 可弹横幅的通道：常驻通知会随服务停止被系统移除，
        // 若继续复用同一 id，用户回头看不到"下完了"（原先只有一条易错过的 Toast）。
        // 必须同步发送：紧接的 stopService → onDestroy 会 cancel serviceScope，异步发送可能被吞
        postCompletedNotification(skipped)
        Handler(Looper.getMainLooper()).post {
            val message = if (skipped > 0) {
                getString(R.string.download_finished_with_skipped_format, skipped)
            } else {
                getString(R.string.download_all_complete)
            }
            ToastUtil.showShort(applicationContext, message)
            skippedCount = 0
            stopService(Intent(application, DownloadService::class.java))
        }
    }

    companion object {
        private val TAG: String = DownloadService::class.java.simpleName
        const val RETRY_TIMES: Int = 3

        /** 单章重试前的等待：避开瞬时限流/网络抖动 */
        private const val RETRY_DELAY_MS = 1_000L

        /** 章与章之间的间隔：降低对书源站点的请求压力 */
        private const val CHAPTER_INTERVAL_MS = 800L

        /** 常驻（进行中/已暂停）通知通道：LOW = 不弹横幅、不打扰 */
        private const val CHANNEL_ONGOING = "download_ongoing"

        /** 完成通知通道：DEFAULT = 允许弹一次横幅 */
        private const val CHANNEL_DONE = "download_done"

        /** 历史通道 id（原名 "App Service"）：重要性创建后改不动，故新建通道并删旧的 */
        private const val LEGACY_CHANNEL = "40"

        /** 常驻通知 id：必须与 [startForeground] 用同一 id，否则前台服务通知与进度通知会共存两条 */
        private const val ONGOING_NOTIFY_ID = 19931118

        /** 完成通知 id：与常驻分离，服务销毁后仍留在通知栏 */
        private const val DONE_NOTIFY_ID = 19931119

        /** Intent action：暂停当前下载批次（保留任务） */
        const val ACTION_PAUSE = "com.ebook.book.action.PAUSE_DOWNLOAD"

        /** Intent action：继续下载库中未完成任务 */
        const val ACTION_RESUME = "com.ebook.book.action.RESUME_DOWNLOAD"

        /** Intent action：清空队列并取消下载 */
        const val ACTION_CANCEL = "com.ebook.book.action.CANCEL_DOWNLOAD"

        /** Intent extra 键：随启动/重启动作携带的待下载章节列表（Parcelable） */
        private const val EXTRA_CHAPTERS = "extra_download_chapters"

        /**
         * 以前台服务方式启动本服务，并把系统"不允许启动"收口成返回值。
         *
         * 不要直接调 [ContextCompat.startForegroundService]：targetSdk 35+ 下它可能抛运行时异常
         * 让调用点直接闪退，两条现实路径——
         * - dataSync 类型 24 小时内累计 6 小时的前台配额已用尽（Android 15 起，见 [onTimeout]）；
         * - 应用处于后台且无后台启动豁免（Android 12 起）。
         * 返回 false 表示服务未起来，调用方须提示用户；此时任务已在库里，用户回到前台重试即可。
         */
        fun start(context: Context, intent: Intent): Boolean = try {
            ContextCompat.startForegroundService(context, intent)
            true
        } catch (e: RuntimeException) {
            if (!isStartNotAllowed(e)) throw e
            Logger.e(TAG, "前台服务启动被拒（后台限制或 dataSync 配额用尽）: ", e)
            false
        }

        /**
         * 是否为"前台服务不允许启动"异常。
         *
         * 用类名比对而不是 `catch (e: ForegroundServiceStartNotAllowedException)`：该类 API 31 才引入，
         * minSdk 26 下 catch 子句要解析这个类（低版本设备上有 NoClassDefFoundError 风险），类名比对
         * 则任何版本都能安全判定，并顺带覆盖其父类 ServiceStartNotAllowedException。
         */
        private fun isStartNotAllowed(e: Throwable): Boolean =
            e.javaClass.name.startsWith("android.app.") &&
                e.javaClass.name.endsWith("ServiceStartNotAllowedException")

        /**
         * 构造控制动作 Intent（暂停/继续/取消）。
         *
         * 页面侧用 [start] 发送，服务已存活时也能直达 onStartCommand，不再依赖 SharedFlow 命令通道。
         */
        fun buildActionIntent(context: Context, action: String): Intent =
            Intent(context, DownloadService::class.java).setAction(action)

        /**
         * 构造携带下载任务的启动 Intent。
         *
         * 调用方用 [start] 启动（内含启动被拒的兜底，勿直接调 startForegroundService），
         * 任务经 onStartCommand 直达服务，不依赖任何页面存活的命令通道。
         */
        fun buildStartIntent(context: Context, chapters: List<DownloadChapterEntity>): Intent =
            Intent(context, DownloadService::class.java)
                .putParcelableArrayListExtra(EXTRA_CHAPTERS, ArrayList<DownloadChapterEntity>(chapters))

        /** 从启动 Intent 提取章节列表；版本分支规避 API 33 废弃 API（避免编译警告） */
        private fun extractChapters(intent: Intent): List<DownloadChapterEntity> {
            val list = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableArrayListExtra(EXTRA_CHAPTERS, DownloadChapterEntity::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableArrayListExtra(EXTRA_CHAPTERS)
            }
            return list.orEmpty()
        }
    }
}
