package com.ebook.book.view

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.ebook.book.R
import com.ebook.book.service.DownloadService
import com.ebook.common.event.RxBusTag
import com.ebook.db.ObjectBoxManager
import com.ebook.db.entity.BookShelf
import com.ebook.db.entity.BookShelf_
import com.ebook.db.entity.DownloadChapter
import com.ebook.db.entity.DownloadChapter_
import com.hwangjr.rxbus.RxBus
import com.hwangjr.rxbus.annotation.Subscribe
import com.hwangjr.rxbus.annotation.Tag
import com.hwangjr.rxbus.thread.EventThread
import com.xrn1997.common.event.SimpleObserver
import io.objectbox.query.QueryBuilder
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.schedulers.Schedulers

/**
 * DownloadListPop：下载任务列表弹窗
 *
 * 功能：
 * 1. 显示当前下载任务状态（无任务/下载中/暂停）
 * 2. 提供下载、暂停、取消操作
 * 3. 使用 RxBus 接收下载任务事件
 */
class DownloadListPop @SuppressLint("InflateParams") constructor(
    private val mContext: Context
) : PopupWindow(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT) {

    companion object {
        private const val TAG = "DownloadListPop"
    }

    private val view: View =
        LayoutInflater.from(mContext).inflate(R.layout.view_pop_downloadlist, null)

    private lateinit var tvNone: TextView
    private lateinit var llDownload: LinearLayout
    private lateinit var ivCover: ImageView
    private lateinit var tvName: TextView
    private lateinit var tvChapterName: TextView
    private lateinit var tvCancel: TextView
    private lateinit var tvDownload: TextView

    init {
        contentView = view
        bindView()
        bindEvent()
        initWait()

        // 设置 PopupWindow 属性
        setBackgroundDrawable(
            ContextCompat.getDrawable(mContext, com.ebook.common.R.drawable.shape_pop_checkaddshelf_bg)
        )
        isFocusable = true
        isTouchable = true
        animationStyle = com.ebook.common.R.style.anim_pop_checkaddshelf

        // 注册 RxBus 订阅
        RxBus.get().register(this)
    }

    /** 绑定控件 */
    private fun bindView() {
        tvNone = view.findViewById(R.id.tv_none)
        llDownload = view.findViewById(R.id.ll_download)
        ivCover = view.findViewById(R.id.iv_cover)
        tvName = view.findViewById(R.id.tv_name)
        tvChapterName = view.findViewById(R.id.tv_chapter_name)
        tvCancel = view.findViewById(R.id.tv_cancel)
        tvDownload = view.findViewById(R.id.tv_download)
    }

    /** 绑定点击事件 */
    private fun bindEvent() {
        tvCancel.setOnClickListener {
            RxBus.get().post(RxBusTag.CANCEL_DOWNLOAD, Any())
            tvNone.visibility = View.VISIBLE
        }
        tvDownload.setOnClickListener {
            if (tvDownload.text == "开始下载") {
                RxBus.get().post(RxBusTag.START_DOWNLOAD, Any())
            } else {
                RxBus.get().post(RxBusTag.PAUSE_DOWNLOAD, Any())
            }
        }
    }

    /** 初始化等待状态，检查是否有下载任务 */
    /**
     * 初始化等待状态，检查是否存在未完成下载任务
     */
    private fun initWait() {
        Observable.create { emitter ->
            try {
                // 查询书架列表，按最后更新时间倒序
                val bookShelfList = ObjectBoxManager.bookShelfBox
                    .query()
                    .orderDesc(BookShelf_.finalDate)
                    .build()
                    .find()

                var foundChapter: DownloadChapter? = null

                // 遍历书架，找出第一个非本地书的下载章节
                for (book in bookShelfList) {
                    if (book.tag != BookShelf.LOCAL_TAG) {
                        val chapterList = ObjectBoxManager.downloadChapterBox
                            .query(DownloadChapter_.noteUrl.equal(book.noteUrl))
                            .order(DownloadChapter_.durChapterIndex, QueryBuilder.DESCENDING)
                            .build()
                            .find(0, 1)

                        if (chapterList.isNotEmpty()) {
                            foundChapter = chapterList.first()
                            break
                        }
                    }
                }

                if (foundChapter != null) {
                    emitter.onNext(foundChapter)
                } else {
                    // 没有找到下载任务，清空下载表
                    ObjectBoxManager.downloadChapterBox.removeAll()
                    emitter.onNext(DownloadChapter())
                }
                emitter.onComplete()
            } catch (ex: Exception) {
                emitter.onError(ex)
            }
        }
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe(object : SimpleObserver<DownloadChapter>() {
                override fun onNext(value: DownloadChapter) {
                    if (value.noteUrl.isNotEmpty()) {
                        llDownload.visibility = View.GONE
                        tvNone.visibility = View.GONE
                        tvDownload.text = "开始下载"
                        val intent = Intent(mContext, DownloadService::class.java)
                        ContextCompat.startForegroundService(mContext, intent)
                        mContext.startService(intent)
                    } else {
                        tvNone.visibility = View.VISIBLE
                    }
                }

                override fun onError(e: Throwable) {
                    Log.e(TAG, "initWait onError: ", e)
                    tvNone.visibility = View.VISIBLE
                }
            })
    }


    /** 取消订阅 */
    fun onDestroy() {
        RxBus.get().unregister(this)
    }

    /** RxBus 事件：暂停任务 */
    @Subscribe(thread = EventThread.MAIN_THREAD, tags = [Tag(RxBusTag.PAUSE_DOWNLOAD_LISTENER)])
    fun pauseTask(o: Any) {
        tvNone.visibility = View.GONE
        llDownload.visibility = View.GONE
        tvDownload.text = "开始下载"
    }

    /** RxBus 事件：完成任务 */
    @Subscribe(thread = EventThread.MAIN_THREAD, tags = [Tag(RxBusTag.FINISH_DOWNLOAD_LISTENER)])
    fun finishTask(o: Any) {
        tvNone.visibility = View.VISIBLE
    }

    /** RxBus 事件：下载进度更新 */
    @Subscribe(thread = EventThread.MAIN_THREAD, tags = [Tag(RxBusTag.PROGRESS_DOWNLOAD_LISTENER)])
    fun progressTask(downloadChapter: DownloadChapter) {
        tvNone.visibility = View.GONE
        llDownload.visibility = View.VISIBLE
        tvDownload.text = "暂停下载"

        Glide.with(mContext)
            .load(downloadChapter.coverUrl)
            .dontAnimate()
            .diskCacheStrategy(DiskCacheStrategy.RESOURCE)
            .centerCrop()
            .placeholder(com.ebook.common.R.drawable.img_cover_default)
            .into(ivCover)

        tvName.text = downloadChapter.bookName
        tvChapterName.text = downloadChapter.durChapterName
    }
}
