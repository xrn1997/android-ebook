package com.ebook.book

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.Animation
import android.view.animation.AnimationUtils
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.viewModels
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.request.RequestOptions
import com.ebook.book.databinding.ActivityDetailBinding
import com.ebook.book.mvvm.viewmodel.BookDetailViewModel
import com.ebook.book.mvvm.viewmodel.BookReadViewModel.Companion.OPEN_FROM_APP
import com.ebook.common.event.FROM_BOOKSHELF
import com.ebook.common.event.FROM_SEARCH
import com.ebook.common.event.KeyCode
import com.ebook.common.event.RxBusTag
import com.ebook.common.manager.BitIntentDataManager
import com.ebook.db.entity.BookShelf
import com.ebook.db.entity.SearchBook
import com.hwangjr.rxbus.RxBus
import com.hwangjr.rxbus.annotation.Subscribe
import com.hwangjr.rxbus.annotation.Tag
import com.hwangjr.rxbus.thread.EventThread
import com.therouter.TheRouter
import com.therouter.router.Autowired
import com.therouter.router.Route
import com.xrn1997.common.mvvm.view.BaseMvvmActivity
import dagger.hilt.android.AndroidEntryPoint
import jp.wasabeef.glide.transformations.BlurTransformation

@AndroidEntryPoint
@Route(path = KeyCode.Book.DETAIL_PATH)
class BookDetailActivity : BaseMvvmActivity<ActivityDetailBinding, BookDetailViewModel>() {
    override val mViewModel: BookDetailViewModel by viewModels()
    @Autowired(name = "from")
    var openFrom = FROM_BOOKSHELF

    @Autowired(name = "data")
    var searchBook: SearchBook? = null

    @Autowired(name = "data_key")
    var dataKey: String? = null

    private lateinit var ivBlurCover: ImageView
    private lateinit var ivCover: ImageView
    private lateinit var tvName: TextView
    private lateinit var tvAuthor: TextView
    private lateinit var tvOrigin: TextView
    private lateinit var tvChapter: TextView
    private lateinit var tvIntro: TextView
    private lateinit var tvShelf: TextView
    private lateinit var tvRead: TextView
    private lateinit var tvLoading: TextView

    private lateinit var animHideLoading: Animation
    private lateinit var animShowInfo: Animation

    override fun onCreate(savedInstanceState: Bundle?) {
        TheRouter.inject(this)
        super.onCreate(savedInstanceState)
        RxBus.get().register(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        RxBus.get().unregister(this)
    }

    @SuppressLint("SetTextI18n")
    override fun initView() {
        ivBlurCover = binding.ivBlurCover
        ivCover = binding.ivCover
        tvName = binding.tvName
        tvAuthor = binding.tvAuthor
        tvOrigin = binding.tvOrigin
        tvChapter = binding.tvChapter
        tvIntro = binding.tvIntro
        tvShelf = binding.tvShelf
        tvRead = binding.tvRead
        tvLoading = binding.tvLoading
        tvIntro.movementMethod = ScrollingMovementMethod.getInstance()
        animShowInfo = AnimationUtils.loadAnimation(this, android.R.anim.fade_in)
        animHideLoading = AnimationUtils.loadAnimation(this, android.R.anim.fade_out)

        tvRead.setOnClickListener {
            //进入阅读
            val intent = Intent(
                this@BookDetailActivity,
                ReadBookActivity::class.java
            )
            intent.putExtra("from", OPEN_FROM_APP)
            val key = System.currentTimeMillis().toString()
            intent.putExtra("data_key", key)
            BitIntentDataManager.getInstance().putData(key, mViewModel.mBookShelf?.clone())
            startActivity(intent)
            finishActivity()
        }
    }

    override fun initData() {
        if (openFrom == FROM_BOOKSHELF) {
            dataKey?.let {
                mViewModel.mBookShelf = BitIntentDataManager.getInstance().getData(it) as BookShelf
                BitIntentDataManager.getInstance().cleanData(it)
                mViewModel.inBookShelf = true
            }
        } else {
            searchBook?.let {
                mViewModel.searchBook = it
                mViewModel.inBookShelf = it.add
            }
        }
        var coverUrl = ""
        var name = ""
        var author = ""
        if (openFrom == FROM_BOOKSHELF) {
            mViewModel.mBookShelf?.let { bookShelf ->
                val bookInfo = bookShelf.bookInfo.target
                coverUrl = bookInfo.coverUrl
                name = bookInfo.name
                author = bookInfo.author
                if (bookInfo.origin.isNotEmpty()) {
                    tvOrigin.visibility = View.VISIBLE
                    tvOrigin.text = "来源:" + bookInfo.origin
                } else {
                    tvOrigin.visibility = View.GONE
                }
            }
        } else {
            mViewModel.searchBook?.let {
                coverUrl = it.coverUrl
                name = it.name
                author = it.author
                if (it.origin.isNotEmpty()) {
                    tvOrigin.visibility = View.VISIBLE
                    tvOrigin.text = "来源:" + it.origin
                } else {
                    tvOrigin.visibility = View.GONE
                }
            }
        }

        Glide.with(this)
            .load(coverUrl)
            .dontAnimate()
            .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
            .centerCrop()
            .placeholder(com.ebook.common.R.drawable.img_cover_default)
            .into(ivCover)
        Glide.with(this)
            .load(coverUrl)
            .dontAnimate()
            .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
            .centerCrop()
            .apply(RequestOptions.bitmapTransform(BlurTransformation(6)))
            .into(ivBlurCover)
        tvName.text = name
        tvAuthor.text = author

        updateView()

        animHideLoading.setAnimationListener(object : Animation.AnimationListener {
            override fun onAnimationStart(animation: Animation) {
            }

            override fun onAnimationEnd(animation: Animation) {
                tvLoading.visibility = View.GONE
            }

            override fun onAnimationRepeat(animation: Animation) {
            }
        })
        if (openFrom == FROM_SEARCH && mViewModel.mBookShelf == null) {
            //网络请求
            mViewModel.getBookShelfInfo()
        }
    }

    @SuppressLint("SetTextI18n")
    fun updateView() {
        val bookShelf = mViewModel.mBookShelf
        if (null != bookShelf) {
            val bookInfo = bookShelf.bookInfo.target
            Glide.with(this)
                .load(bookInfo.coverUrl)
                .dontAnimate()
                .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                .centerCrop()
                .placeholder(com.ebook.common.R.drawable.img_cover_default)
                .into(ivCover)
            Glide.with(this)
                .load(bookInfo.coverUrl)
                .dontAnimate()
                .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                .centerCrop()
                .apply(RequestOptions.bitmapTransform(BlurTransformation(6)))
                .into(ivBlurCover)
            if (mViewModel.inBookShelf) {
                if (!bookInfo.chapterList.isEmpty()) tvChapter.text =
                    String.format(
                        getString(com.ebook.common.R.string.tv_read_durprogress),
                        bookInfo.chapterList[bookShelf.durChapter].durChapterName
                    )
                else tvChapter.text = "无章节"
                tvShelf.text = "移出书架"
                tvRead.text = "继续阅读"
                tvShelf.setOnClickListener {
                    //从书架移出
                    mViewModel.removeFromBookShelf()
                }
            } else {
                if (bookInfo.chapterList.isEmpty()) {
                    tvChapter.text = "无章节"
                } else {
                    tvChapter.text =
                        String.format(
                            getString(com.ebook.common.R.string.tv_searchbook_lastest),
                            bookInfo.chapterList[bookInfo.chapterList.size - 1].durChapterName
                        )
                }
                tvShelf.text = "放入书架"
                tvRead.text = "开始阅读"
                tvShelf.setOnClickListener {
                    //放入书架
                    mViewModel.addToBookShelf()
                }
            }
            if (tvIntro.text.toString().trim { it <= ' ' }.isEmpty()) {
                tvIntro.text = bookShelf.bookInfo.target.introduce
            }
            if (tvIntro.visibility != View.VISIBLE) {
                tvIntro.visibility = View.VISIBLE
                tvIntro.startAnimation(animShowInfo)
                tvLoading.startAnimation(animHideLoading)
            }
            if (bookInfo.origin.isNotEmpty()) {
                tvOrigin.visibility = View.VISIBLE
                tvOrigin.text = "来源:" + bookInfo.origin
            } else {
                tvOrigin.visibility = View.GONE
            }
        } else {
            tvChapter.text =
                String.format(
                    getString(com.ebook.common.R.string.tv_searchbook_lastest),
                    mViewModel.searchBook?.lastChapter
                )
            tvIntro.visibility = View.INVISIBLE
            tvLoading.visibility = View.VISIBLE
            tvLoading.text = "加载中..."
        }
        tvLoading.setOnClickListener(null)
    }

    private fun getBookShelfError() {
        tvLoading.visibility = View.VISIBLE
        tvLoading.text = "加载失败,点击重试"
        tvLoading.setOnClickListener {
            tvLoading.text = "加载中..."
            tvLoading.setOnClickListener(null)
            mViewModel.getBookShelfInfo()
        }
    }

    override fun enableToolbar(): Boolean {
        return false
    }

    override fun enableFitsSystemWindows(): Boolean {
        return false
    }

    override fun initBaseViewObservable() {
        super.initBaseViewObservable()
        mViewModel.updateViewEvent.observe(this) {
            updateView()
        }
        mViewModel.bookShelfErrorEvent.observe(this) {
            getBookShelfError()
        }
    }

    override fun onBindViewBinding(
        inflater: LayoutInflater,
        parent: ViewGroup?,
        attachToParent: Boolean
    ): ActivityDetailBinding {
        return ActivityDetailBinding.inflate(inflater, parent, attachToParent)
    }

    @Subscribe(thread = EventThread.MAIN_THREAD, tags = [Tag(RxBusTag.HAD_ADD_BOOK)])
    fun hadAddBook(value: BookShelf) {
        mViewModel.bookShelfList.add(value)
        if ((null != mViewModel.mBookShelf && value.noteUrl == mViewModel.mBookShelf!!.noteUrl) ||
            (null != mViewModel.searchBook && value.noteUrl == mViewModel.searchBook!!.noteUrl)
        ) {
            mViewModel.inBookShelf = true
            if (null != mViewModel.searchBook) {
                mViewModel.searchBook!!.add = true
            }
            updateView()
        }
    }

    @Subscribe(thread = EventThread.MAIN_THREAD, tags = [Tag(RxBusTag.HAD_REMOVE_BOOK)])
    fun hadRemoveBook(value: BookShelf) {
        finishActivity()
    }

    companion object {
        const val TAG: String = "BookDetailActivity"
    }
}
