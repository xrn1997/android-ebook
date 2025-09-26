package com.ebook.book.service

import android.annotation.SuppressLint
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
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.ebook.book.R
import com.ebook.book.fragment.MainBookFragment
import com.ebook.common.analyze.impl.WebBookModelImpl
import com.ebook.common.event.RxBusTag
import com.ebook.db.ObjectBoxManager.bookContentBox
import com.ebook.db.ObjectBoxManager.bookShelfBox
import com.ebook.db.ObjectBoxManager.chapterListBox
import com.ebook.db.ObjectBoxManager.downloadChapterBox
import com.ebook.db.entity.BookContent
import com.ebook.db.entity.BookContent_
import com.ebook.db.entity.BookShelf
import com.ebook.db.entity.BookShelf_
import com.ebook.db.entity.ChapterList
import com.ebook.db.entity.ChapterList_
import com.ebook.db.entity.DownloadChapter
import com.ebook.db.entity.DownloadChapterList
import com.ebook.db.entity.DownloadChapter_
import com.hwangjr.rxbus.RxBus
import com.hwangjr.rxbus.annotation.Subscribe
import com.hwangjr.rxbus.annotation.Tag
import com.hwangjr.rxbus.thread.EventThread
import com.xrn1997.common.BaseApplication
import com.xrn1997.common.event.SimpleObserver
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.core.ObservableEmitter
import io.reactivex.rxjava3.core.ObservableOnSubscribe
import io.reactivex.rxjava3.core.ObservableSource
import io.reactivex.rxjava3.core.Observer
import io.reactivex.rxjava3.disposables.Disposable
import io.reactivex.rxjava3.functions.Function
import io.reactivex.rxjava3.schedulers.Schedulers

@Suppress("unused")
class DownloadService : Service() {
    private lateinit var notifyManager: NotificationManager
    private var isStartDownload = false
    private var isInit = false
    private var isDownloading = false
    private val myHandler = Handler(Looper.getMainLooper())
    override fun onCreate() {
        super.onCreate()
        RxBus.get().register(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        RxBus.get().unregister(this)
        isInit = true
    }

    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        if (!isInit) {
            isInit = true
            notifyManager = getSystemService(NotificationManager::class.java)

            // Android 8.0+ 需要创建 NotificationChannel
            val channel =
                NotificationChannel("40", "App Service", NotificationManager.IMPORTANCE_LOW)
            notifyManager.createNotificationChannel(channel)

            // 创建默认通知
            val notification = NotificationCompat.Builder(this, "40")
                .setContentTitle("下载服务运行中")
                .setContentText("正在准备下载任务")
                .setSmallIcon(R.mipmap.ic_launcher)
                .setOngoing(true)
                .build()

            // 前台服务启动
            startForeground(19931118, notification)
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder? {
        return null
    }

    private fun addNewTask(newData: List<DownloadChapter>) {
        isStartDownload = true
        Observable.create { e: ObservableEmitter<Boolean> ->
            for (chapter in newData) {
                downloadChapterBox.query(DownloadChapter_.durChapterUrl.equal(chapter.durChapterUrl))
                    .build().use { query ->
                        val tmp = query.findFirst()
                        if (tmp != null) {
                            chapter.id = tmp.id
                        }
                    }
            }
            downloadChapterBox.put(newData)
            e.onNext(true)
            e.onComplete()
        }
            .observeOn(AndroidSchedulers.mainThread())
            .subscribeOn(Schedulers.io())
            .subscribe(object : SimpleObserver<Boolean>() {
                override fun onNext(value: Boolean) {
                    if (!isDownloading) {
                        toDownload()
                    }
                }

                override fun onError(e: Throwable) {
                    Log.e(TAG, "onError: ", e)
                }
            })
    }

    private fun toDownload() {
        isDownloading = true
        if (isStartDownload) {
            Observable.create(ObservableOnSubscribe { e: ObservableEmitter<DownloadChapter> ->
                bookShelfBox.query().orderDesc(BookShelf_.finalDate).build().use { query ->
                    val bookShelfList = query.find()
                    if (bookShelfList.isNotEmpty()) {
                        for ((noteUrl, _, _, _, tag) in bookShelfList) {
                            if (tag != BookShelf.LOCAL_TAG) {
                                downloadChapterBox.query(DownloadChapter_.noteUrl.equal(noteUrl))
                                    .order(DownloadChapter_.durChapterIndex).build().use { query2 ->
                                        val downloadChapterList = query2.find(0, 1)
                                        if (downloadChapterList.isNotEmpty()) {
                                            e.onNext(downloadChapterList[0])
                                            e.onComplete()
                                            return@ObservableOnSubscribe
                                        }
                                    }
                            }
                        }
                    }
                }
                downloadChapterBox.removeAll()
                e.onNext(DownloadChapter())
                e.onComplete()
            })
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(object : SimpleObserver<DownloadChapter>() {
                    override fun onNext(value: DownloadChapter) {
                        if (value.noteUrl.isNotEmpty()) {
                            downloading(BaseApplication.context, value, 0)
                        } else {
                            Observable.create { e: ObservableEmitter<Any> ->
                                downloadChapterBox.removeAll()
                                e.onNext(Any())
                                e.onComplete()
                            }
                                .subscribeOn(Schedulers.io())
                                .observeOn(AndroidSchedulers.mainThread())
                                .subscribe(object : SimpleObserver<Any>() {
                                    override fun onNext(value: Any) {
                                        isDownloading = false
                                        finishDownload()
                                    }

                                    override fun onError(e: Throwable) {
                                        Log.e(TAG, "onError: ", e)
                                        isDownloading = false
                                    }
                                })
                        }
                    }

                    override fun onError(e: Throwable) {
                        Log.e(TAG, "onError: ", e)
                        isDownloading = false
                    }
                })
        } else {
            isPause()
        }
    }

    private fun downloading(context: Context, data: DownloadChapter, durTime: Int) {
        if (durTime < RETRY_TIMES && isStartDownload) {
            isProgress(data)
            Observable.create { e: ObservableEmitter<BookContent> ->
                try {
                    bookContentBox.query(BookContent_.durChapterUrl.equal(data.durChapterUrl))
                        .build().use { query ->
                            val result = query.findFirst()
                            e.onNext(result ?: BookContent())
                            e.onComplete()
                        }
                } catch (ex: Exception) {
                    e.onError(ex)
                }
            }
                .flatMap(Function<BookContent, ObservableSource<BookContent>> { bookContent: BookContent ->
                    if (bookContent.durChapterUrl.isEmpty()) {
                        //章节内容不存在
                        return@Function WebBookModelImpl
                            .getBookContent(context, data.durChapterUrl, data.durChapterIndex)
                            .map<BookContent> { bookContent1: BookContent ->
                                downloadChapterBox.remove(data)
                                Log.e(
                                    TAG,
                                    "downloading: " + bookContent1.right
                                )
                                if (bookContent1.right) {
                                    val cl = ChapterList(
                                        data.noteUrl,
                                        data.durChapterIndex,
                                        data.durChapterUrl,
                                        data.durChapterName,
                                        data.tag,
                                        true
                                    )
                                    chapterListBox.query(
                                        ChapterList_.durChapterUrl.equal(
                                            bookContent1.durChapterUrl
                                        )
                                    ).build().use { query ->
                                        val tmp = query.findFirst()
                                        if (tmp != null) {
                                            cl.id = tmp.id
                                            val bc = tmp.bookContent.target
                                            if (bc != null) {
                                                bookContent1.id = bc.id
                                            }
                                            val info = tmp.bookInfo.target
                                            if (info != null) {
                                                cl.bookInfo.target = info
                                            }
                                        }
                                    }
                                    cl.bookContent.target = bookContent1
                                    chapterListBox.put(cl)
                                }
                                bookContent1
                            }
                    } else {
                        //存在章节内容
                        return@Function Observable.create<BookContent> { e: ObservableEmitter<BookContent> ->
                            downloadChapterBox.remove(data)
                            e.onNext(bookContent)
                            e.onComplete()
                        }
                    }
                })
                .observeOn(AndroidSchedulers.mainThread())
                .subscribeOn(Schedulers.io())
                .subscribe(object : SimpleObserver<BookContent>() {
                    override fun onNext(value: BookContent) {
                        if (isStartDownload) {
                            myHandler.postDelayed({
                                if (isStartDownload) {
                                    toDownload()
                                } else {
                                    isPause()
                                }
                            }, 800)
                        } else {
                            isPause()
                        }
                    }

                    override fun onError(e: Throwable) {
                        Log.e(TAG, "onError: ", e)
                        val time = durTime + 1
                        downloading(context, data, time)
                    }
                })
        } else {
            if (isStartDownload) {
                Observable.create { e: ObservableEmitter<Boolean> ->
                    downloadChapterBox.remove(data)
                    e.onNext(true)
                    e.onComplete()
                }
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribeOn(Schedulers.io())
                    .subscribe(object : SimpleObserver<Boolean>() {
                        override fun onNext(value: Boolean) {
                            if (isStartDownload) {
                                myHandler.postDelayed({
                                    if (isStartDownload) {
                                        toDownload()
                                    } else {
                                        isPause()
                                    }
                                }, 800)
                            } else {
                                isPause()
                            }
                        }

                        override fun onError(e: Throwable) {
                            Log.e(TAG, "onError: ", e)
                            if (!isStartDownload) isPause()
                        }
                    })
            } else isPause()
        }
    }

    fun startDownload() {
        isStartDownload = true
        toDownload()
    }

    fun pauseDownload() {
        isStartDownload = false
        notifyManager.cancelAll()
    }

    fun cancelDownload() {
        Observable.create { e: ObservableEmitter<Any> ->
            downloadChapterBox.removeAll()
            e.onNext(Any())
            e.onComplete()
        }
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe(object : Observer<Any> {
                override fun onSubscribe(d: Disposable) {
                }

                override fun onNext(value: Any) {
                    isStartDownload = false
                    notifyManager.cancelAll()
                    stopService(Intent(application, DownloadService::class.java))
                }

                override fun onError(e: Throwable) {
                }

                override fun onComplete() {
                }
            })
    }

    private fun isPause() {
        isDownloading = false
        Observable.create(ObservableOnSubscribe { e: ObservableEmitter<DownloadChapter> ->
            var bookShelfList: List<BookShelf>
            bookShelfBox.query().orderDesc(BookShelf_.finalDate).build()
                .use { query ->
                    bookShelfList = query.find()
                }
            if (bookShelfList.isNotEmpty()) {
                for ((noteUrl, _, _, _, tag) in bookShelfList) {
                    if (tag != BookShelf.LOCAL_TAG) {
                        var downloadChapterList: List<DownloadChapter>
                        downloadChapterBox.query(
                            DownloadChapter_.noteUrl.equal(
                                noteUrl
                            )
                        ).order(DownloadChapter_.durChapterIndex).build().use { query ->
                            downloadChapterList = query.find(0, 1)
                        }
                        if (downloadChapterList.isNotEmpty()) {
                            e.onNext(downloadChapterList[0])
                            e.onComplete()
                            return@ObservableOnSubscribe
                        }
                    }
                }
            }
            downloadChapterBox.removeAll()
            e.onNext(DownloadChapter())
            e.onComplete()
        })
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe(object : SimpleObserver<DownloadChapter>() {
                override fun onNext(value: DownloadChapter) {
                    if (value.noteUrl.isNotEmpty()) {
                        RxBus.get().post(RxBusTag.PAUSE_DOWNLOAD_LISTENER, Any())
                    } else {
                        RxBus.get().post(RxBusTag.FINISH_DOWNLOAD_LISTENER, Any())
                    }
                }

                override fun onError(e: Throwable) {
                    Log.e(TAG, "onError: ", e)
                }
            })
    }

    @SuppressLint("UnspecifiedImmutableFlag")
    private fun isProgress(downloadChapter: DownloadChapter) {
        RxBus.get().post(RxBusTag.PROGRESS_DOWNLOAD_LISTENER, downloadChapter)
        val mainIntent = Intent(
            this,
            MainBookFragment::class.java
        )
        val mainPendingIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.getActivity(this, 0, mainIntent, PendingIntent.FLAG_IMMUTABLE)
        } else {
            PendingIntent.getActivity(this, 0, mainIntent, PendingIntent.FLAG_UPDATE_CURRENT)
        }
        //创建 Notification.Builder 对象
        val builder = NotificationCompat.Builder(this, "40")
            .setSmallIcon(R.mipmap.ic_launcher) //点击通知后自动清除
            .setAutoCancel(true)
            .setContentTitle("正在下载：" + downloadChapter.bookName)
            .setContentText(downloadChapter.durChapterName)
            .setContentIntent(mainPendingIntent)
        //发送通知
        val notifyId = 19931118
        notifyManager.notify(notifyId, builder.build())
    }

    private fun finishDownload() {
        RxBus.get().post(RxBusTag.FINISH_DOWNLOAD_LISTENER, Any())
        notifyManager.cancelAll()
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(applicationContext, "全部离线章节下载完成", Toast.LENGTH_SHORT).show()
            stopService(Intent(application, DownloadService::class.java))
        }
    }

    @Subscribe(thread = EventThread.MAIN_THREAD, tags = [Tag(RxBusTag.PAUSE_DOWNLOAD)])
    fun pauseTask(o: Any) {
        pauseDownload()
    }

    @Subscribe(thread = EventThread.MAIN_THREAD, tags = [Tag(RxBusTag.START_DOWNLOAD)])
    fun startTask(o: Any) {
        startDownload()
    }

    @Subscribe(thread = EventThread.MAIN_THREAD, tags = [Tag(RxBusTag.CANCEL_DOWNLOAD)])
    fun cancelTask(o: Any) {
        cancelDownload()
    }

    @Subscribe(thread = EventThread.MAIN_THREAD, tags = [Tag(RxBusTag.ADD_DOWNLOAD_TASK)])
    fun addTask(newData: DownloadChapterList) {
        newData.data?.let {
            addNewTask(it)
        }
    }

    companion object {
        private val TAG: String = DownloadService::class.java.simpleName
        const val RETRY_TIMES: Int = 1
    }
}