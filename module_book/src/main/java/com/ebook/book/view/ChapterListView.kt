package com.ebook.book.view

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.animation.Animation
import android.view.animation.AnimationUtils
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.ebook.book.adapter.ChapterListAdapter
import com.ebook.common.R
import com.ebook.common.view.RecyclerViewBar
import com.ebook.db.entity.BookShelf

class ChapterListView(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {
    private lateinit var tvName: TextView
    private lateinit var tvListCount: TextView
    private lateinit var rvList: RecyclerView
    private lateinit var rvbSlider: RecyclerViewBar

    private lateinit var flBg: FrameLayout
    private lateinit var llContent: LinearLayout

    private val chapterListAdapter = ChapterListAdapter(context)

    private lateinit var animIn: Animation
    private lateinit var animOut: Animation
    private var itemClickListener: OnItemClickListener? = null

    init {
        visibility = INVISIBLE
        LayoutInflater.from(context).inflate(R.layout.view_chapterlist, this, true)
        initData()
        initView()
    }

    private fun initData() {
        animIn = AnimationUtils.loadAnimation(context, R.anim.anim_pop_chapterlist_in)
        animIn.setAnimationListener(object : Animation.AnimationListener {
            override fun onAnimationStart(animation: Animation) {
                flBg.setOnClickListener(null)
            }

            override fun onAnimationEnd(animation: Animation) {
                flBg.setOnClickListener { dismissChapterList() }
            }

            override fun onAnimationRepeat(animation: Animation) {
            }
        })
        animOut = AnimationUtils.loadAnimation(context, R.anim.anim_pop_chapterlist_out)
        animOut.setAnimationListener(object : Animation.AnimationListener {
            override fun onAnimationStart(animation: Animation) {
                flBg.setOnClickListener(null)
            }

            override fun onAnimationEnd(animation: Animation) {
                llContent.visibility = INVISIBLE
                visibility = INVISIBLE
            }

            override fun onAnimationRepeat(animation: Animation) {
            }
        })
    }

    fun show(durChapter: Int) {
        chapterListAdapter.setIndex(durChapter)
        val manager = rvList.layoutManager as LinearLayoutManager?
        manager?.scrollToPositionWithOffset(durChapter, 0)
        if (visibility != VISIBLE) {
            visibility = VISIBLE
            animOut.cancel()
            animIn.cancel()
            llContent.visibility = VISIBLE
            llContent.startAnimation(animIn)
        }
    }

    private fun initView() {
        flBg = findViewById(R.id.fl_bg)
        llContent = findViewById(R.id.ll_content)
        tvName = findViewById(R.id.tv_name)
        tvListCount = findViewById(R.id.tv_listcount)
        rvList = findViewById(R.id.rv_list)
        rvList.setLayoutManager(LinearLayoutManager(context))
        rvList.setItemAnimator(null)
        rvbSlider = findViewById(R.id.rvb_slider)
    }

    fun setData(bookShelf: BookShelf, clickListener: OnItemClickListener?) {
        this.itemClickListener = clickListener
        val bookInfo = bookShelf.bookInfo.target
        tvName.text = bookInfo.name
        tvListCount.text = "共" + bookInfo.chapterList.size + "章"
        chapterListAdapter.setOnItemClickListener { _, position ->
            itemClickListener?.let {
                it.itemClick(position)
                rvbSlider.scrollToPositionWithOffset(position)
            }
            chapterListAdapter.setIndex(position)
        }
        chapterListAdapter.submitList(bookInfo.chapterList)
        rvList.adapter = chapterListAdapter
        rvbSlider.setRecyclerView(rvList)

    }

    fun dismissChapterList(): Boolean {
        if (visibility != VISIBLE) {
            return false
        } else {
            animOut.cancel()
            animIn.cancel()
            llContent.startAnimation(animOut)
            return true
        }
    }

    interface OnItemClickListener {
        fun itemClick(index: Int)
    }
}