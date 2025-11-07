package com.ebook.book

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.Animation
import android.view.animation.AnimationUtils
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.addCallback
import androidx.activity.viewModels
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import com.ebook.book.databinding.ActivityBookreadBinding
import com.ebook.book.mvvm.viewmodel.BookReadViewModel
import com.ebook.book.mvvm.viewmodel.BookReadViewModel.Companion.OPEN_FROM_APP
import com.ebook.book.mvvm.viewmodel.BookReadViewModel.Companion.OPEN_FROM_OTHER
import com.ebook.book.util.BookImportUtil
import com.ebook.book.view.ChapterListView
import com.ebook.common.analyze.impl.WebBookModelImpl
import com.ebook.common.event.KeyCode
import com.ebook.common.event.RxBusTag
import com.ebook.common.manager.BitIntentDataManager
import com.ebook.common.view.BookContentView
import com.ebook.common.view.ContentSwitchView
import com.ebook.common.view.ContentSwitchView.LoadDataListener
import com.ebook.common.view.ReadBookControl.textBackground
import com.ebook.common.view.modialog.MoProgressHUD
import com.ebook.common.view.mprogressbar.MHorProgressBar
import com.ebook.common.view.mprogressbar.OnProgressListener
import com.ebook.common.view.popupwindow.CheckAddShelfPop
import com.ebook.common.view.popupwindow.FontPop
import com.ebook.common.view.popupwindow.FontPop.OnChangeProListener
import com.ebook.common.view.popupwindow.MoreSettingPop
import com.ebook.common.view.popupwindow.ReadBookMenuMorePop
import com.ebook.common.view.popupwindow.WindowLightPop
import com.ebook.db.ObjectBoxManager.bookContentBox
import com.ebook.db.ObjectBoxManager.chapterListBox
import com.ebook.db.entity.BookContent
import com.ebook.db.entity.BookContent_
import com.ebook.db.entity.BookShelf
import com.ebook.db.entity.DownloadChapter
import com.ebook.db.entity.DownloadChapterList
import com.ebook.db.entity.LocBookShelf
import com.ebook.db.entity.ReadBookContent
import com.ebook.db.event.DBCode
import com.hwangjr.rxbus.RxBus
import com.permissionx.guolindev.PermissionX
import com.therouter.TheRouter.build
import com.trello.rxlifecycle4.android.ActivityEvent
import com.xrn1997.common.BaseApplication.Companion.context
import com.xrn1997.common.event.SimpleObserver
import com.xrn1997.common.mvvm.view.BaseMvvmActivity
import com.xrn1997.common.util.DisplayUtil.dip2px
import com.xrn1997.common.util.ToastUtil.showShort
import com.xrn1997.common.util.detectColor
import com.xrn1997.common.util.setStatusBarColor
import dagger.hilt.android.AndroidEntryPoint
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.core.ObservableEmitter
import io.reactivex.rxjava3.schedulers.Schedulers
import me.grantland.widget.AutofitTextView
import kotlin.math.ceil

@AndroidEntryPoint
class ReadBookActivity : BaseMvvmActivity<ActivityBookreadBinding, BookReadViewModel>() {
    override val mViewModel: BookReadViewModel by viewModels()
    private var openFrom = 0
    private lateinit var flContent: FrameLayout
    private val handler: Handler = Handler(Looper.getMainLooper())
    private lateinit var csvBook: ContentSwitchView

    //主菜单
    private lateinit var flMenu: FrameLayout
    private lateinit var vMenuBg: View
    private lateinit var llMenuTop: LinearLayout
    private lateinit var llMenuBottom: LinearLayout
    private lateinit var ivReturn: ImageButton
    private lateinit var ivMenuMore: ImageView
    private lateinit var atvTitle: AutofitTextView
    private lateinit var tvPre: TextView
    private lateinit var tvNext: TextView
    private lateinit var hpbReadProgress: MHorProgressBar
    private lateinit var llCatalog: LinearLayout
    private lateinit var llLight: LinearLayout
    private lateinit var llFont: LinearLayout
    private lateinit var llSetting: LinearLayout

    //主菜单动画
    private lateinit var menuTopIn: Animation
    private lateinit var menuTopOut: Animation
    private lateinit var menuBottomIn: Animation
    private lateinit var menuBottomOut: Animation

    private lateinit var checkAddShelfPop: CheckAddShelfPop
    private lateinit var chapterListView: ChapterListView
    private lateinit var windowLightPop: WindowLightPop
    private lateinit var readBookMenuMorePop: ReadBookMenuMorePop
    private lateinit var fontPop: FontPop
    private lateinit var moreSettingPop: MoreSettingPop

    private lateinit var moProgressHUD: MoProgressHUD

    override fun initData() {
        mViewModel.saveProgress()
        menuTopIn =
            AnimationUtils.loadAnimation(this, com.ebook.common.R.anim.anim_readbook_top_in)
        menuTopIn.setAnimationListener(object : Animation.AnimationListener {
            override fun onAnimationStart(animation: Animation) {
            }

            override fun onAnimationEnd(animation: Animation) {
                vMenuBg.setOnClickListener {
                    llMenuTop.startAnimation(menuTopOut)
                    llMenuBottom.startAnimation(menuBottomOut)
                }
            }

            override fun onAnimationRepeat(animation: Animation) {
            }
        })
        menuBottomIn =
            AnimationUtils.loadAnimation(this, com.ebook.common.R.anim.anim_readbook_bottom_in)

        menuTopOut =
            AnimationUtils.loadAnimation(this, com.ebook.common.R.anim.anim_readbook_top_out)
        menuTopOut.setAnimationListener(object : Animation.AnimationListener {
            override fun onAnimationStart(animation: Animation) {
                vMenuBg.setOnClickListener(null)
            }

            override fun onAnimationEnd(animation: Animation) {
                flMenu.visibility = View.INVISIBLE
            }

            override fun onAnimationRepeat(animation: Animation) {
            }
        })
        menuBottomOut =
            AnimationUtils.loadAnimation(this, com.ebook.common.R.anim.anim_readbook_bottom_out)
    }

    override fun initView() {
        bindView()
        bindEvent()
        ViewCompat.setOnApplyWindowInsetsListener(binding.llMenuBar) { v, insets ->
            val stateBars = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            v.setPadding(stateBars.left, stateBars.top, stateBars.right, stateBars.bottom)
            insets
        }
        setStatusBarColor(textBackground.detectColor())//自适应背景色
        onBackPressedDispatcher.addCallback(this) {
            when {
                // 菜单可见，则先关闭菜单
                flMenu.isVisible -> {
                    llMenuTop.startAnimation(menuTopOut)
                    llMenuBottom.startAnimation(menuBottomOut)
                }
                // 未加入书架并且未显示提示，弹出加入书架提示
                !mViewModel.isAdd && !checkAddShelfPop.isShowing -> {
                    checkAddShelfPop.showAtLocation(flContent, Gravity.CENTER, 0, 0)
                }
                // 章节列表可见则关闭章节列表，否则退出阅读
                !chapterListView.dismissChapterList() -> finish()
            }
        }
    }

    override fun enableToolbar(): Boolean {
        return false
    }

    override fun enableFitsSystemWindows(): Boolean {
        return false
    }

    override fun onBindViewBinding(
        inflater: LayoutInflater,
        parent: ViewGroup?,
        attachToParent: Boolean
    ): ActivityBookreadBinding {
        return ActivityBookreadBinding.inflate(inflater, parent, attachToParent)
    }

    private fun bindView() {
        moProgressHUD = MoProgressHUD(this)

        flContent = findViewById(R.id.fl_content)
        csvBook = findViewById(R.id.csv_book)
        initCsvBook()

        flMenu = findViewById(R.id.fl_menu)
        vMenuBg = findViewById(R.id.v_menu_bg)
        llMenuTop = findViewById(R.id.ll_menu_top)
        llMenuBottom = findViewById(R.id.ll_menu_bottom)
        ivReturn = findViewById(R.id.iv_return)
        ivMenuMore = findViewById(R.id.iv_more)
        atvTitle = findViewById(R.id.atv_title)

        tvPre = findViewById(R.id.tv_pre)
        tvNext = findViewById(R.id.tv_next)
        hpbReadProgress = findViewById(R.id.hpb_read_progress)
        llCatalog = findViewById(R.id.ll_catalog)
        llLight = findViewById(R.id.ll_light)
        llFont = findViewById(R.id.ll_font)
        llSetting = findViewById(R.id.ll_setting)

        chapterListView = findViewById(R.id.clp_chapterlist)
    }

    private fun setHpbReadProgressMax(count: Int) {
        hpbReadProgress.maxProgress = count.toFloat()
    }

    private fun initCsvBook() {
        csvBook.bookReadInit {
            openFrom = intent.getIntExtra("from", OPEN_FROM_OTHER)
            if (openFrom == OPEN_FROM_APP) {
                openBookFromApp()
            } else {
                openBookFromOther()
            }
        }
    }

    private fun openBookFromApp() {
        val key = intent.getStringExtra("data_key")
        if (key == null) {
            Log.e(TAG, "initCsvBook: key is null")
            return
        }
        BitIntentDataManager.getData(key)?.let {
            val bookShelf = it as BookShelf
            if (bookShelf.tag != BookShelf.LOCAL_TAG) {
                showDownloadMenu()
            }
            mViewModel.bookShelf = bookShelf
            BitIntentDataManager.cleanData(key)
            mViewModel.checkInShelf()
        }
    }

    private fun openBookFromOther() {
        //APP外部打开
        val uri = intent.data ?: return
        showLoadBook()
        BookImportUtil.importBook(this, uri)
            .observeOn(AndroidSchedulers.mainThread())
            .subscribeOn(Schedulers.io())
            .subscribe(object : SimpleObserver<LocBookShelf>() {
                override fun onNext(value: LocBookShelf) {
                    if (value.new) RxBus.get().post(RxBusTag.HAD_ADD_BOOK, value)
                    mViewModel.bookShelf = value.bookShelf
                    dimissLoadBook()
                    mViewModel.checkInShelf()
                }

                override fun onError(e: Throwable) {
                    Log.e(TAG, "onError: ", e)
                    dimissLoadBook()
                    loadLocationBookError()
                    showShort(context, "文本打开失败！")
                }
            })
    }

    /**
     * 所有需要的权限
     */
    private fun allNeedPermissions(): List<String> {
        val permissions: MutableList<String> = ArrayList()
        permissions.add(PermissionX.permission.POST_NOTIFICATIONS)
        return permissions
    }

    override fun initBaseViewObservable() {
        super.initBaseViewObservable()
        mViewModel.nextInShelfEvent.observe(this) {
            initPop()
            setHpbReadProgressMax(mViewModel.bookShelf!!.bookInfo.target.chapterList.size)
            startLoadingBook()
        }
    }

    private fun initPop() {
        val bookInfo = mViewModel.bookShelf!!.bookInfo.target
        checkAddShelfPop =
            CheckAddShelfPop(this, bookInfo.name, object : CheckAddShelfPop.OnItemClickListener {
                override fun clickExit() {
                    finish()
                }

                override fun clickAddShelf() {
                    mViewModel.addToShelf(null)
                    checkAddShelfPop.dismiss()
                }
            })
        chapterListView.setData(
            mViewModel.bookShelf!!,
            object : ChapterListView.OnItemClickListener {
                override fun itemClick(index: Int) {
                    csvBook.setInitData(
                        index,
                        bookInfo.chapterList.size,
                        DBCode.BookContentView.DUR_PAGE_INDEX_BEGIN
                    )
                }
            })

        windowLightPop = WindowLightPop(this)
        windowLightPop.initLight()

        fontPop = FontPop(this, object : OnChangeProListener {
            override fun textChange(index: Int) {
                csvBook.changeTextSize()
            }

            override fun bgChange(index: Int) {
                setStatusBarColor(textBackground.detectColor())//自适应背景色
                csvBook.changeBg()
            }
        })

        readBookMenuMorePop = ReadBookMenuMorePop(this)
        readBookMenuMorePop.setOnClickDownload {
            //检查通知权限
            PermissionX
                .init(this)
                .permissions(allNeedPermissions())
                .request { allGranted: Boolean, _: List<String?>?, _: List<String?>? ->
                    if (allGranted) {
                        readBookMenuMorePop.dismiss()
                        if (flMenu.isVisible) {
                            llMenuTop.startAnimation(menuTopOut)
                            llMenuBottom.startAnimation(menuBottomOut)
                        }
                        //弹出离线下载界面
                        var endIndex = mViewModel.bookShelf!!.durChapter + 50
                        if (endIndex >= mViewModel.bookShelf!!.bookInfo.target.chapterList.size) {
                            endIndex = mViewModel.bookShelf!!.bookInfo.target.chapterList.size - 1
                        }
                        moProgressHUD.showDownloadList(
                            mViewModel.bookShelf!!.durChapter,
                            endIndex,
                            mViewModel.bookShelf!!.bookInfo.target.chapterList.size
                        ) { start: Int, end: Int ->
                            moProgressHUD.dismiss()
                            mViewModel.addToShelf(object : BookReadViewModel.OnAddListener {
                                override fun addSuccess() {
                                    val result: MutableList<DownloadChapter> =
                                        ArrayList()
                                    for (i in start..end) {
                                        val item = DownloadChapter()
                                        item.noteUrl = mViewModel.bookShelf!!.noteUrl
                                        item.durChapterIndex =
                                            mViewModel.bookShelf!!.bookInfo.target.chapterList[i].durChapterIndex
                                        item.durChapterName =
                                            mViewModel.bookShelf!!.bookInfo.target.chapterList[i].durChapterName
                                        item.durChapterUrl =
                                            mViewModel.bookShelf!!.bookInfo.target.chapterList[i].durChapterUrl
                                        item.tag = mViewModel.bookShelf!!.tag
                                        item.bookName = mViewModel.bookShelf!!.bookInfo.target.name
                                        item.coverUrl =
                                            mViewModel.bookShelf!!.bookInfo.target.coverUrl
                                        result.add(item)
                                    }
                                    RxBus.get()
                                        .post(
                                            RxBusTag.START_DOWNLOAD_SERVICE,
                                            DownloadChapterList(result)
                                        )
                                }
                            })
                        }
                    }
                }
        }
        readBookMenuMorePop.setOnClickComment {
            readBookMenuMorePop.dismiss()
            val path =
                mViewModel.bookShelf!!.bookInfo.target.chapterList[mViewModel.bookShelf!!.durChapter]
            val bundle = Bundle()
            bundle.putString("chapterUrl", path.durChapterUrl)
            bundle.putString("chapterName", path.durChapterName)
            bundle.putString("bookName", mViewModel.bookShelf!!.bookInfo.target.name)
            build(KeyCode.Book.COMMENT_PATH)
                .with(bundle)
                .navigation(this@ReadBookActivity)
        }

        moreSettingPop = MoreSettingPop(this)
    }

    fun loadContent(
        bookContentView: BookContentView,
        bookTag: Long,
        chapterIndex: Int,
        index: Int
    ) {
        var pageIndex = index
        if (null != mViewModel.bookShelf && !mViewModel.bookShelf!!.bookInfo.target.chapterList.isEmpty()) {
            val chapterList = mViewModel.bookShelf!!.bookInfo.target.chapterList[chapterIndex]
            val bookContent = chapterList.bookContent.target
            if (bookContent != null && bookContent.durChapterContent.isNotEmpty()) {
                if (bookContent.lineSize == csvBook.textPaint.textSize && bookContent.lineContent.isNotEmpty()) {
                    //已有数据
                    val tempCount =
                        ceil(bookContent.lineContent.size * 1.0 / mViewModel.pageLineCount).toInt() - 1

                    if (pageIndex == DBCode.BookContentView.DUR_PAGE_INDEX_BEGIN) {
                        pageIndex = 0
                    } else if (pageIndex == DBCode.BookContentView.DUR_PAGE_INDEX_END) {
                        pageIndex = tempCount
                    } else {
                        if (pageIndex >= tempCount) {
                            pageIndex = tempCount
                        }
                    }

                    val start = pageIndex * mViewModel.pageLineCount
                    val end =
                        if (pageIndex == tempCount) bookContent.lineContent.size else start + mViewModel.pageLineCount
                    if (bookTag == bookContentView.qTag) {
                        bookContentView.updateData(
                            bookTag, chapterList.durChapterName,
                            bookContent.lineContent.subList(start, end),
                            chapterIndex,
                            mViewModel.bookShelf!!.bookInfo.target.chapterList.size,
                            pageIndex,
                            tempCount + 1
                        )
                    }
                } else {
                    //有元数据  重新分行
                    bookContent.lineSize = csvBook.textPaint.textSize
                    val finalPageIndex = pageIndex
                    separateParagraphToLines(bookContent.durChapterContent)
                        .observeOn(AndroidSchedulers.mainThread())
                        .subscribeOn(Schedulers.io())
                        .compose(bindUntilEvent(ActivityEvent.DESTROY))
                        .subscribe(object : SimpleObserver<List<String>>() {
                            override fun onNext(value: List<String>) {
                                bookContent.lineContent.clear()
                                bookContent.lineContent.addAll(value)
                                loadContent(bookContentView, bookTag, chapterIndex, finalPageIndex)
                            }

                            override fun onError(e: Throwable) {
                                if (bookTag == bookContentView.qTag) bookContentView.loadError()
                            }
                        })
                }
            } else {
                Observable.create { e: ObservableEmitter<ReadBookContent> ->
                    try {
                        bookContentBox
                            .query(BookContent_.durChapterUrl.equal(chapterList.durChapterUrl))
                            .build().use { query ->
                                val tempList = query.find()
                                e.onNext(ReadBookContent(tempList, pageIndex))
                                e.onComplete()
                            }
                    } catch (ex: Exception) {
                        e.onError(ex)
                    }
                }.observeOn(AndroidSchedulers.mainThread())
                    .subscribeOn(Schedulers.io())
                    .compose(bindUntilEvent(ActivityEvent.DESTROY))
                    .subscribe(object : SimpleObserver<ReadBookContent>() {
                        override fun onNext(tempList: ReadBookContent) {
                            if (tempList.bookContentList.isNotEmpty() && tempList.bookContentList[0].durChapterContent.isNotEmpty()) {
                                chapterList.bookContent.target = tempList.bookContentList[0]
                                loadContent(
                                    bookContentView,
                                    bookTag,
                                    chapterIndex,
                                    tempList.pageIndex
                                )
                            } else {
                                WebBookModelImpl.getBookContent(
                                    context,
                                    chapterList.durChapterUrl,
                                    chapterIndex
                                ).map { bookContent: BookContent ->
                                    if (bookContent.right) {
                                        bookContentBox.query(
                                            BookContent_.durChapterUrl.equal(
                                                bookContent.durChapterUrl
                                            )
                                        ).build().use { query ->
                                            val tmp = query.findFirst()
                                            if (tmp != null) {
                                                bookContent.id = tmp.id
                                            }
                                        }
                                        chapterList.hasCache = true
                                        chapterList.bookContent.target = bookContent
                                        chapterListBox.put(chapterList)
                                    }
                                    bookContent
                                }
                                    .observeOn(AndroidSchedulers.mainThread())
                                    .subscribeOn(Schedulers.io())
                                    .compose(bindUntilEvent(ActivityEvent.DESTROY))
                                    .subscribe(object : SimpleObserver<BookContent>() {
                                        override fun onNext(value: BookContent) {
                                            if (value.durChapterUrl.isNotEmpty()) {
                                                chapterList.bookContent.target = value
                                                if (bookTag == bookContentView.qTag) loadContent(
                                                    bookContentView,
                                                    bookTag,
                                                    chapterIndex,
                                                    tempList.pageIndex
                                                )
                                            } else {
                                                if (bookTag == bookContentView.qTag) bookContentView.loadError()
                                            }
                                        }

                                        override fun onError(e: Throwable) {
                                            Log.e(TAG, "onError: ", e)
                                            if (bookTag == bookContentView.qTag) bookContentView.loadError()
                                        }
                                    })
                            }
                        }

                        override fun onError(e: Throwable) {
                        }
                    })
            }
        } else {
            if (bookTag == bookContentView.qTag) bookContentView.loadError()
        }
    }

    private fun separateParagraphToLines(paragraph: String): Observable<List<String>> {
        return Observable.create { e: ObservableEmitter<List<String>> ->
            val mPaint = csvBook.textPaint as TextPaint
            mPaint.isSubpixelText = true
            val tempLayout: Layout = StaticLayout.Builder.obtain(
                paragraph, 0, paragraph.length, mPaint, csvBook.contentWidth
            )
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(0f, 0f) // 你之前的行距参数：`0f` 为额外的行间距，`1f` 是行间距倍数
                .setIncludePad(false)    // 对应之前的 `includePad = false`
                .build()
            val lines: MutableList<String> =
                ArrayList()
            for (i in 0 until tempLayout.lineCount) {
                lines.add(
                    paragraph.substring(
                        tempLayout.getLineStart(i),
                        tempLayout.getLineEnd(i)
                    )
                )
            }
            e.onNext(lines)
            e.onComplete()
        }
    }

    private fun bindEvent() {
        hpbReadProgress.setProgressListener(object : OnProgressListener {
            override fun moveStartProgress(dur: Float) {
            }

            override fun durProgressChange(dur: Float) {
            }

            override fun moveStopProgress(dur: Float) {
                var realDur = ceil(dur.toDouble()).toInt()
                if (realDur < 1) {
                    realDur = 1
                }
                if ((realDur - 1) != mViewModel.bookShelf!!.durChapter) {
                    csvBook.setInitData(
                        realDur - 1,
                        mViewModel.bookShelf!!.bookInfo.target.chapterList.size,
                        DBCode.BookContentView.DUR_PAGE_INDEX_BEGIN
                    )
                }
                if (hpbReadProgress.durProgress != realDur.toFloat()) hpbReadProgress.durProgress =
                    realDur.toFloat()
            }

            override fun setDurProgress(dur: Float) {
                if (hpbReadProgress.maxProgress == 1f) {
                    tvPre.isEnabled = false
                    tvNext.isEnabled = false
                } else {
                    when (dur) {
                        1f -> {
                            tvPre.isEnabled = false
                            tvNext.isEnabled = true
                        }

                        hpbReadProgress.maxProgress -> {
                            tvPre.isEnabled = true
                            tvNext.isEnabled = false
                        }

                        else -> {
                            tvPre.isEnabled = true
                            tvNext.isEnabled = true
                        }
                    }
                }
            }
        })
        ivReturn.setOnClickListener {
            // finish();
            onBackPressedDispatcher.onBackPressed()
        }
        ivMenuMore.setOnClickListener {
            readBookMenuMorePop.showAsDropDown(
                ivMenuMore,
                0,
                dip2px(-3.5f).toInt()
            )
        }
        csvBook.loadDataListener = object : LoadDataListener {
            override fun loadData(
                bookContentView: BookContentView,
                tag: Long,
                chapterIndex: Int,
                pageIndex: Int
            ) {
                loadContent(bookContentView, tag, chapterIndex, pageIndex)
            }

            override fun updateProgress(chapterIndex: Int, pageIndex: Int) {
                mViewModel.updateProgress(chapterIndex, pageIndex)

                if (!mViewModel.bookShelf!!.bookInfo.target.chapterList.isEmpty()) atvTitle.text =
                    mViewModel.bookShelf!!.bookInfo.target.chapterList[mViewModel.bookShelf!!.durChapter].durChapterName
                else atvTitle.text = "无章节"
                if (hpbReadProgress.durProgress != (chapterIndex + 1).toFloat()) hpbReadProgress.durProgress =
                    (chapterIndex + 1).toFloat()
            }

            override fun getChapterTitle(chapterIndex: Int): String {
                return mViewModel.getChapterTitle(chapterIndex)
            }

            override fun initData(lineCount: Int) {
                mViewModel.pageLineCount = lineCount
                initContentSuccess(
                    mViewModel.bookShelf!!.durChapter,
                    mViewModel.bookShelf!!.bookInfo.target.chapterList.size,
                    mViewModel.bookShelf!!.durChapterPage
                )
            }

            override fun showMenu() {
                flMenu.visibility = View.VISIBLE
                llMenuTop.startAnimation(menuTopIn)
                llMenuBottom.startAnimation(menuBottomIn)
            }
        }

        tvPre.setOnClickListener {
            csvBook.setInitData(
                mViewModel.bookShelf!!.durChapter - 1,
                mViewModel.bookShelf!!.bookInfo.target.chapterList.size,
                DBCode.BookContentView.DUR_PAGE_INDEX_BEGIN
            )
        }
        tvNext.setOnClickListener {
            csvBook.setInitData(
                mViewModel.bookShelf!!.durChapter + 1,
                mViewModel.bookShelf!!.bookInfo.target.chapterList.size,
                DBCode.BookContentView.DUR_PAGE_INDEX_BEGIN
            )
        }

        llCatalog.setOnClickListener {
            llMenuTop.startAnimation(menuTopOut)
            llMenuBottom.startAnimation(menuBottomOut)
            handler.postDelayed(
                { chapterListView.show(mViewModel.bookShelf!!.durChapter) },
                menuTopOut.duration
            )
        }

        llLight.setOnClickListener {
            llMenuTop.startAnimation(menuTopOut)
            llMenuBottom.startAnimation(menuBottomOut)
            handler.postDelayed(
                { windowLightPop.showAtLocation(flContent, Gravity.BOTTOM, 0, 0) },
                menuTopOut.duration
            )
        }

        llFont.setOnClickListener {
            llMenuTop.startAnimation(menuTopOut)
            llMenuBottom.startAnimation(menuBottomOut)
            handler.postDelayed(
                { fontPop.showAtLocation(flContent, Gravity.BOTTOM, 0, 0) },
                menuTopOut.duration
            )
        }

        llSetting.setOnClickListener {
            llMenuTop.startAnimation(menuTopOut)
            llMenuBottom.startAnimation(menuBottomOut)
            handler.postDelayed(
                { moreSettingPop.showAtLocation(flContent, Gravity.BOTTOM, 0, 0) },
                menuTopOut.duration
            )
        }
    }

    fun initContentSuccess(durChapterIndex: Int, chapterAll: Int, durPageIndex: Int) {
        csvBook.setInitData(durChapterIndex, chapterAll, durPageIndex)
    }

    private fun startLoadingBook() {
        csvBook.startLoading()
    }

    override fun onPause() {
        super.onPause()
        mViewModel.saveProgress()
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        val temp = csvBook.onKeyUp(keyCode, event)
        if (temp) return true
        return super.onKeyUp(keyCode, event)
    }

    private fun showLoadBook() {
        moProgressHUD.showLoading("文本导入中...")
    }

    fun dimissLoadBook() {
        moProgressHUD.dismiss()
    }

    fun loadLocationBookError() {
        csvBook.loadError()
    }

    private fun showDownloadMenu() {
        ivMenuMore.visibility = View.VISIBLE
    }
}