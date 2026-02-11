package com.ebook.common.view

import android.animation.Animator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Paint
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.Toast
import com.ebook.common.util.ScreenUtils
import com.ebook.db.event.DBCode
import com.xrn1997.common.util.DisplayUtil
import kotlin.math.abs

class ContentSwitchView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
    defStyleRes: Int = 0
) : FrameLayout(context, attrs, defStyleAttr, defStyleRes), BookContentView.SetDataListener {

    private val screenWidth = ScreenUtils.getScreenWidth(context)
    private val animDuration = 300L
    private var state = NONE // 0是有上一页也有下一页; 2是只有下一页; 1是只有上一页; -1是没有上一页也没有下一页
    private val scrollX: Float = DisplayUtil.dip2px(30f)
    private var isMoving = false
    private var durPageView: BookContentView
    private var viewContents: MutableList<BookContentView>
    private var bookReadInitListener: OnBookReadInitListener? = null
    private var readBookControl: ReadBookControl = ReadBookControl
    private var startX = -1f
    var loadDataListener: LoadDataListener? = null
    private var durHeight = 0

    private val layoutListener = ViewTreeObserver.OnGlobalLayoutListener {
        val height = durPageView.getTvContent().height
        if (height > 0) {
            if (loadDataListener != null && durHeight != height) {
                durHeight = height
                loadDataListener?.initData(durPageView.getLineCount(height))
            }
        }
    }

    init {
        durPageView = BookContentView(context)
        durPageView.setReadBookControl(readBookControl)
        viewContents = ArrayList()
        viewContents.add(durPageView)
        addView(durPageView)
    }

    fun bookReadInit(bookReadInitListener: OnBookReadInitListener) {
        this.bookReadInitListener = bookReadInitListener
        durPageView.getTvContent().viewTreeObserver.addOnGlobalLayoutListener(layoutInitListener)
    }

    fun startLoading() {
        val height = durPageView.getTvContent().height
        if (height > 0) {
            if (loadDataListener != null && durHeight != height) {
                durHeight = height
                loadDataListener?.initData(durPageView.getLineCount(height))
            }
        }
        durPageView.getTvContent().viewTreeObserver.addOnGlobalLayoutListener(layoutListener)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val action = event.action
        if (!isMoving) {
            val durWidth = if (screenWidth > 1400) 10 else 0 // 当分辨率过大时，添加横向滑动冗余值
            when (action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.x
                }
                MotionEvent.ACTION_MOVE -> {
                    if (viewContents.size > 1) {
                        if (startX == -1f) {
                            startX = event.x
                        }

                        // 处理分辨率过大，移动冗余值，当横向滑动值超过冗余值则开始滑动
                        var durX = (event.x - startX).toInt()
                        durX = when {
                            durX > durWidth -> durX - durWidth
                            durX < -durWidth -> durX + durWidth
                            else -> 0
                        }

                        if (durX > 0 && (state == PREANDNEXT || state == ONLYPRE)) {
                            var tempX = durX - width
                            if (tempX < -width) {
                                tempX = -width
                            } else if (tempX > 0) {
                                tempX = 0
                            }
                            viewContents[0].layout(tempX, viewContents[0].top, tempX + width, viewContents[0].bottom)
                        } else if (durX < 0 && (state == PREANDNEXT || state == ONLYNEXT)) {
                            var tempX = durX
                            if (tempX < -width) {
                                tempX = -width
                            }
                            val tempIndex = if (state == PREANDNEXT) 1 else 0
                            viewContents[tempIndex].layout(tempX, viewContents[tempIndex].top, tempX + width, viewContents[tempIndex].bottom)
                        }
                    }
                }
                MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_UP -> { // 小米8长按传送门会引导手势进入action_cancel
                    if (startX == -1f) {
                        startX = event.x
                    }
                    if (event.x - startX > durWidth) {
                        if (state == PREANDNEXT || state == ONLYPRE) {
                            // 注意冗余值
                            if (event.x - startX + durWidth > scrollX) {
                                // 向前翻页成功
                                initMoveSuccessAnim(viewContents[0], 0)
                            } else {
                                initMoveFailAnim(viewContents[0], -width)
                            }
                        } else {
                            // 没有上一页
                            noPre()
                        }
                    } else if (event.x - startX < -durWidth) {
                        if (state == PREANDNEXT || state == ONLYNEXT) {
                            val tempIndex = if (state == PREANDNEXT) 1 else 0
                            // 注意冗余值
                            if (startX - event.x - durWidth > scrollX) {
                                // 向后翻页成功
                                initMoveSuccessAnim(viewContents[tempIndex], -width)
                            } else {
                                initMoveFailAnim(viewContents[tempIndex], 0)
                            }
                        } else {
                            // 没有下一页
                            noNext()
                        }
                    } else {
                        // 点击事件
                        if (readBookControl.canClickTurn && event.x <= width.toFloat() / 3) {
                            // 点击向前翻页
                            if (state == PREANDNEXT || state == ONLYPRE) {
                                initMoveSuccessAnim(viewContents[0], 0)
                            } else {
                                noPre()
                            }
                        } else if (readBookControl.canClickTurn && event.x >= width.toFloat() / 3 * 2) {
                            // 点击向后翻页
                            if (state == PREANDNEXT || state == ONLYNEXT) {
                                val tempIndex = if (state == PREANDNEXT) 1 else 0
                                initMoveSuccessAnim(viewContents[tempIndex], -width)
                            } else {
                                noNext()
                            }
                        } else {
                            // 点击中间部位
                            loadDataListener?.showMenu()
                        }
                    }
                    startX = -1f
                }
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        if (viewContents.isNotEmpty()) {
            when (state) {
                NONE -> viewContents[0].layout(0, top, width, bottom)
                PREANDNEXT -> if (viewContents.size >= 3) {
                    viewContents[0].layout(-width, top, 0, bottom)
                    viewContents[1].layout(0, top, width, bottom)
                    viewContents[2].layout(0, top, width, bottom)
                }
                ONLYPRE -> if (viewContents.size >= 2) {
                    viewContents[0].layout(-width, top, 0, bottom)
                    viewContents[1].layout(0, top, width, bottom)
                }
                else -> if (viewContents.size >= 2) {
                    viewContents[0].layout(0, top, width, bottom)
                    viewContents[1].layout(0, top, width, bottom)
                }
            }
        } else {
            super.onLayout(changed, left, top, right, bottom)
        }
    }

    private fun initMoveSuccessAnim(view: View?, orderX: Int) {
        view?.let {
            val temp = abs(it.left - orderX) / (width / animDuration)
            val tempAnim = ValueAnimator.ofInt(it.left, orderX).setDuration(temp)
            tempAnim.addUpdateListener { animation ->
                val value = animation.animatedValue as Int
                it.layout(value, it.top, value + width, it.bottom)
            }
            tempAnim.addListener(object : Animator.AnimatorListener {
                override fun onAnimationStart(animation: Animator) {
                    isMoving = true
                }

                override fun onAnimationEnd(animation: Animator) {
                    isMoving = false
                    if (orderX == 0) {
                        // 翻向前一页
                        durPageView = viewContents[0]
                        if (state == PREANDNEXT) {
                            this@ContentSwitchView.removeView(viewContents[viewContents.size - 1])
                            viewContents.removeAt(viewContents.size - 1)
                        }
                        state = ONLYNEXT
                        if (durPageView.durChapterIndex - 1 >= 0 || durPageView.durPageIndex - 1 >= 0) {
                            addPrePage(durPageView.durChapterIndex, durPageView.chapterAll, durPageView.durPageIndex, durPageView.pageAll)
                            state = if (state == NONE) ONLYPRE else PREANDNEXT
                        }
                    } else {
                        // 翻向后一页
                        durPageView = if (state == ONLYNEXT) {
                            viewContents[1]
                        } else {
                            this@ContentSwitchView.removeView(viewContents[0])
                            viewContents.removeAt(0)
                            viewContents[1]
                        }
                        state = ONLYPRE
                        if (durPageView.durChapterIndex + 1 <= durPageView.chapterAll - 1 || durPageView.durPageIndex + 1 <= durPageView.pageAll - 1) {
                            addNextPage(durPageView.durChapterIndex, durPageView.chapterAll, durPageView.durPageIndex, durPageView.pageAll)
                            state = if (state == NONE) ONLYNEXT else PREANDNEXT
                        }
                    }
                    loadDataListener?.updateProgress(durPageView.durChapterIndex, durPageView.durPageIndex)
                }

                override fun onAnimationCancel(animation: Animator) {}

                override fun onAnimationRepeat(animation: Animator) {}
            })
            tempAnim.start()
        }
    }

    private fun initMoveFailAnim(view: View?, orderX: Int) {
        view?.let {
            val temp = abs(it.left - orderX) / (width / animDuration)
            val tempAnim = ValueAnimator.ofInt(it.left, orderX).setDuration(temp)
            tempAnim.addUpdateListener { animation ->
                val value = animation.animatedValue as Int
                it.layout(value, it.top, value + width, it.bottom)
            }
            tempAnim.start()
        }
    }

    fun setInitData(durChapterIndex: Int, chapterAll: Int, durPageIndex: Int) {
        updateOtherPage(durChapterIndex, chapterAll, durPageIndex, -1)
        durPageView.setLoadDataListener(loadDataListener, this)
        durPageView.loadData(
            loadDataListener?.getChapterTitle(durChapterIndex) ?: "",
            durChapterIndex,
            chapterAll,
            durPageIndex
        )
        loadDataListener?.updateProgress(durPageView.durChapterIndex, durPageView.durPageIndex)
    }

    private fun updateOtherPage(durChapterIndex: Int, chapterAll: Int, durPageIndex: Int, pageAll: Int) {
        if (chapterAll > 1 || pageAll > 1) {
            when {
                (durChapterIndex == 0 && pageAll == -1) || (durChapterIndex == 0 && durPageIndex == 0) -> {
                    // ONLYNEXT
                    addNextPage(durChapterIndex, chapterAll, durPageIndex, pageAll)
                    if (state == ONLYPRE || state == PREANDNEXT) {
                        removeView(viewContents[0])
                        viewContents.removeAt(0)
                    }
                    state = ONLYNEXT
                }
                (durChapterIndex == chapterAll - 1 && pageAll == -1) || (durChapterIndex == chapterAll - 1 && durPageIndex == pageAll - 1 && pageAll != -1) -> {
                    // ONLYPRE
                    addPrePage(durChapterIndex, chapterAll, durPageIndex, pageAll)
                    if (state == ONLYNEXT || state == PREANDNEXT) {
                        removeView(viewContents[2])
                        viewContents.removeAt(2)
                    }
                    state = ONLYPRE
                }
                else -> {
                    // PREANDNEXT
                    addNextPage(durChapterIndex, chapterAll, durPageIndex, pageAll)
                    addPrePage(durChapterIndex, chapterAll, durPageIndex, pageAll)
                    state = PREANDNEXT
                }
            }
        } else {
            // NONE
            when (state) {
                ONLYPRE -> {
                    removeView(viewContents[0])
                    viewContents.removeAt(0)
                }
                ONLYNEXT -> {
                    removeView(viewContents[1])
                    viewContents.removeAt(1)
                }
                PREANDNEXT -> {
                    removeView(viewContents[0])
                    removeView(viewContents[2])
                    viewContents.removeAt(2)
                    viewContents.removeAt(0)
                }
            }
            state = NONE
        }
    }

    private fun addNextPage(durChapterIndex: Int, chapterAll: Int, durPageIndex: Int, pageAll: Int) {
        if (state == ONLYNEXT || state == PREANDNEXT) {
            val temp = if (state == ONLYNEXT) 1 else 2
            if (pageAll > 0 && durPageIndex >= 0 && durPageIndex < pageAll - 1) {
                viewContents[temp].loadData(
                    loadDataListener?.getChapterTitle(durChapterIndex) ?: "",
                    durChapterIndex,
                    chapterAll,
                    durPageIndex + 1
                )
            } else {
                viewContents[temp].loadData(
                    loadDataListener?.getChapterTitle(durChapterIndex + 1) ?: "",
                    durChapterIndex + 1,
                    chapterAll,
                    DBCode.BookContentView.DUR_PAGE_INDEX_BEGIN
                )
            }
        } else if (state == ONLYPRE || state == NONE) {
            val next = BookContentView(context)
            next.setReadBookControl(readBookControl)
            next.setLoadDataListener(loadDataListener, this)
            if (pageAll > 0 && durPageIndex >= 0 && durPageIndex < pageAll - 1) {
                next.loadData(
                    loadDataListener?.getChapterTitle(durChapterIndex) ?: "",
                    durChapterIndex,
                    chapterAll,
                    durPageIndex + 1
                )
            } else {
                next.loadData(
                    loadDataListener?.getChapterTitle(durChapterIndex + 1) ?: "",
                    durChapterIndex + 1,
                    chapterAll,
                    DBCode.BookContentView.DUR_PAGE_INDEX_BEGIN
                )
            }
            viewContents.add(next)
            addView(next, 0)
        }
    }

    private fun addPrePage(durChapterIndex: Int, chapterAll: Int, durPageIndex: Int, pageAll: Int) {
        if (state == ONLYNEXT || state == NONE) {
            val pre = BookContentView(context)
            pre.setReadBookControl(readBookControl)
            pre.setLoadDataListener(loadDataListener, this)
            if (pageAll > 0 && durPageIndex > 0) {
                pre.loadData(
                    loadDataListener?.getChapterTitle(durChapterIndex) ?: "",
                    durChapterIndex,
                    chapterAll,
                    durPageIndex - 1
                )
            } else {
                pre.loadData(
                    loadDataListener?.getChapterTitle(durChapterIndex - 1) ?: "",
                    durChapterIndex - 1,
                    chapterAll,
                    DBCode.BookContentView.DUR_PAGE_INDEX_END
                )
            }
            viewContents.add(0, pre)
            addView(pre)
        } else if (state == ONLYPRE || state == PREANDNEXT) {
            if (pageAll > 0 && durPageIndex > 0) {
                viewContents[0].loadData(
                    loadDataListener?.getChapterTitle(durChapterIndex) ?: "",
                    durChapterIndex,
                    chapterAll,
                    durPageIndex - 1
                )
            } else {
                viewContents[0].loadData(
                    loadDataListener?.getChapterTitle(durChapterIndex - 1) ?: "",
                    durChapterIndex - 1,
                    chapterAll,
                    DBCode.BookContentView.DUR_PAGE_INDEX_END
                )
            }
        }
    }

    override fun setDataFinish(bookContentView: BookContentView?, durChapterIndex: Int, chapterAll: Int, durPageIndex: Int, pageAll: Int, fromPageIndex: Int) {
        if (bookContentView == durContentView && chapterAll > 0 && pageAll > 0) {
            updateOtherPage(durChapterIndex, chapterAll, durPageIndex, pageAll)
        }
    }

    val durContentView: BookContentView
        get() = durPageView

    private fun noPre() {
        Toast.makeText(context, "没有上一页", Toast.LENGTH_SHORT).show()
    }

    private fun noNext() {
        Toast.makeText(context, "没有下一页", Toast.LENGTH_SHORT).show()
    }

    val textPaint: Paint
        get() = durPageView.getTvContent().paint

    val contentWidth: Int
        get() = durPageView.getTvContent().width

    fun changeBg() {
        for (item in viewContents) {
            item.setBg(readBookControl)
        }
    }

    fun changeTextSize() {
        for (item in viewContents) {
            item.setTextKind(readBookControl)
        }
        loadDataListener?.initData(durPageView.getLineCount(durHeight))
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (readBookControl.canKeyTurn && keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (state == PREANDNEXT || state == ONLYNEXT) {
                val tempIndex = if (state == PREANDNEXT) 1 else 0
                initMoveSuccessAnim(viewContents[tempIndex], -width)
            } else {
                noNext()
            }
            return true
        } else if (readBookControl.canKeyTurn && keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            if (state == PREANDNEXT || state == ONLYPRE) {
                initMoveSuccessAnim(viewContents[0], 0)
            } else {
                noPre()
            }
            return true
        }
        return false
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        return if (readBookControl.canKeyTurn && keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            true
        } else readBookControl.canKeyTurn && keyCode == KeyEvent.KEYCODE_VOLUME_UP
    }

    private val layoutInitListener: ViewTreeObserver.OnGlobalLayoutListener = ViewTreeObserver.OnGlobalLayoutListener {
        bookReadInitListener?.success()
        durPageView.getTvContent().viewTreeObserver.removeOnGlobalLayoutListener(layoutInitListener)
    }

    fun loadError() {
        durPageView.loadError()
    }

    fun interface OnBookReadInitListener {
        fun success()
    }

    interface LoadDataListener {
        fun loadData(bookContentView: BookContentView, tag: Long, chapterIndex: Int, pageIndex: Int)
        fun updateProgress(chapterIndex: Int, pageIndex: Int)
        fun getChapterTitle(chapterIndex: Int): String
        fun initData(lineCount: Int)
        fun showMenu()
    }

    companion object {
        const val NONE = -1
        const val PREANDNEXT = 0
        const val ONLYPRE = 1
        const val ONLYNEXT = 2
    }
}
