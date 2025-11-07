package com.ebook.common.view

import android.animation.Animator
import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.os.CountDownTimer
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ViewTreeObserver
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.ebook.common.R
import com.xrn1997.common.util.DisplayUtil.dip2px
import kotlin.math.roundToInt

/**
 * RecyclerViewBar：带滑块的 RecyclerView 快速滚动条
 *
 * 功能：
 * 1. 显示可拖动滑块，滑块拖动同步 RecyclerView 滚动
 * 2. RecyclerView 滚动同步滑块位置
 * 3. 滑块自动隐藏，用户交互时显示
 */
class RecyclerViewBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    /** 滑块自动隐藏定时器 */
    private val timeCountDown = AutoHideTimer()

    /** 滑块 ImageView */
    private val slider by lazy { ImageView(context) }

    /** 滑块高度（可通过 XML 属性修改） */
    private var sliderHeight = dip2px(35f).toInt()

    /** 绑定的 RecyclerView */
    private var recyclerView: RecyclerView? = null

    /** 滑块显示/隐藏动画 */
    private var slideIn: Animator? = null
    private var slideOut: Animator? = null

    /** 滑块最后触摸 Y 值，用于拖动计算 */
    private var lastTouchY = INVALID_TOUCH_Y

    /** 上次布局高度，用于布局变化时按比例调整滑块位置 */
    private var lastHeight = 0

    /** 布局初始化监听器，处理父布局大小变化 */
    private val layoutInitListener = ViewTreeObserver.OnGlobalLayoutListener {
        if (height <= 0) return@OnGlobalLayoutListener
        if (lastHeight == 0) {
            lastHeight = height
        } else {
            val diff = lastHeight - height
            if (diff != 0) {
                val params = slider.layoutParams as LayoutParams
                params.topMargin = ((params.topMargin * 1f / (lastHeight - sliderHeight)) *
                        (height - sliderHeight)).toInt()
                slider.layoutParams = params
                lastHeight = height
            }
        }
    }

    companion object {
        private const val INVALID_TOUCH_Y = -10000f
        private const val SLIDE_ANIM_TIME = 800L
    }

    init {
        orientation = VERTICAL
        setupAttributes(attrs)   // 初始化滑块属性
        setupSlider()            // 设置滑块触摸事件
        viewTreeObserver.addOnGlobalLayoutListener(layoutInitListener)
    }

    /**
     * 初始化滑块属性（高度、padding、图片等）
     */
    private fun setupAttributes(attrs: AttributeSet?) {
        context.obtainStyledAttributes(attrs, R.styleable.RecyclerViewBar).apply {
            sliderHeight = getDimensionPixelSize(
                R.styleable.RecyclerViewBar_slider_height, sliderHeight
            )
            val paddingLeft =
                getDimensionPixelSize(R.styleable.RecyclerViewBar_slider_paddingLeft, 0)
            val paddingRight =
                getDimensionPixelSize(R.styleable.RecyclerViewBar_slider_paddingRight, 0)

            slider.apply {
                setPadding(paddingLeft, 0, paddingRight, 0)
                alpha = 0f
                isClickable = true
                setImageResource(R.drawable.icon_slider)
                scaleType = ImageView.ScaleType.FIT_XY
                layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, sliderHeight)
            }

            recycle()
        }
        addView(slider)
    }

    /**
     * 设置滑块触摸事件，用于拖动 RecyclerView
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun setupSlider() {
        slider.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    lastTouchY = event.y
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    if (lastTouchY != INVALID_TOUCH_Y) {
                        val dy = event.y - lastTouchY
                        updateSliderPosition(dy)
                        showSlider()
                    }
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (lastTouchY != INVALID_TOUCH_Y) {
                        lastTouchY = INVALID_TOUCH_Y
                        restartAutoHideTimer()
                    }
                    true
                }

                else -> false
            }
        }
    }

    /**
     * 更新滑块位置，并同步 RecyclerView 滚动
     */
    private fun updateSliderPosition(deltaY: Float) {
        val params = slider.layoutParams as LayoutParams
        val newMargin = (params.topMargin + deltaY).coerceIn(0f, (height - sliderHeight).toFloat())
        params.topMargin = newMargin.roundToInt()
        slider.layoutParams = params

        // 同步 RecyclerView 滚动
        recyclerView?.let { rv ->
            val itemCount = rv.adapter?.itemCount ?: return
            val position = ((newMargin / (height - sliderHeight)) * (itemCount - 1)).roundToInt()
            (rv.layoutManager as? LinearLayoutManager)
                ?.scrollToPositionWithOffset(position, 0)
        }
    }

    /**
     * 绑定 RecyclerView，并监听滚动事件同步滑块
     */
    fun setRecyclerView(rv: RecyclerView) {
        recyclerView = rv.apply {
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                    super.onScrollStateChanged(recyclerView, newState)
                    if (newState != RecyclerView.SCROLL_STATE_IDLE) {
                        showSlider()
                    } else restartAutoHideTimer()
                }

                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    super.onScrolled(recyclerView, dx, dy)
                    val position = (recyclerView.layoutManager as? LinearLayoutManager)
                        ?.findFirstVisibleItemPosition() ?: return
                    syncSliderPosition(position)
                }
            })
        }
    }

    /**
     * 根据 RecyclerView 当前位置同步滑块位置
     */
    private fun syncSliderPosition(position: Int) {
        recyclerView?.adapter?.let { adapter ->
            val fraction = position.toFloat() / adapter.itemCount
            val params = slider.layoutParams as LayoutParams
            params.topMargin = ((height - sliderHeight) * fraction).roundToInt()
            slider.layoutParams = params
        }
    }

    /** 显示滑块动画 */
    private fun showSlider() {
        if (slider.alpha >= 1f) return
        slideOut?.cancel()
        if (slideIn == null) {
            slideIn = ObjectAnimator.ofFloat(slider, "alpha", slider.alpha, 1f).apply {
                duration = (SLIDE_ANIM_TIME * (1f - slider.alpha)).toLong()
            }
        }
        if (!(slideIn?.isRunning ?: false)) slideIn?.start()
    }

    /** 隐藏滑块动画 */
    private fun hideSlider() {
        if (slider.alpha <= 0f) return
        slideIn?.cancel()
        if (slideOut == null) {
            slideOut = ObjectAnimator.ofFloat(slider, "alpha", slider.alpha, 0f).apply {
                duration = (SLIDE_ANIM_TIME * slider.alpha).toLong()
            }
        }
        if (!(slideOut?.isRunning ?: false)) slideOut?.start()
    }

    /** 重启滑块自动隐藏定时器 */
    private fun restartAutoHideTimer() {
        timeCountDown.cancel()
        timeCountDown.start()
    }

    /** 移除全局布局监听器，避免内存泄漏 */
    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        viewTreeObserver.removeOnGlobalLayoutListener(layoutInitListener)
    }

    /**
     * 滑块自动隐藏计时器
     * CountDownTimer 每 1000ms 隐藏一次
     */
    private inner class AutoHideTimer :
        CountDownTimer(1000L, 1000L) {
        override fun onTick(millisUntilFinished: Long) {}
        override fun onFinish() = hideSlider()
    }
}
