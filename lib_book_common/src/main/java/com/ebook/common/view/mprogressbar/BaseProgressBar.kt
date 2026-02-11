package com.ebook.common.view.mprogressbar

import android.content.Context
import android.content.res.TypedArray
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.graphics.drawable.StateListDrawable
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.util.Log
import android.view.View
import androidx.core.content.withStyledAttributes
import androidx.core.graphics.drawable.toDrawable
import com.ebook.common.R
import com.xrn1997.common.util.DisplayUtil

abstract class BaseProgressBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // ==================== Progress Properties ====================

    /**
     * Whether the progress bar can be touched to change progress
     */
    var canTouch: Boolean = true

    /**
     * Animation speed for progress changes. Must be > 0.
     */
    open var speed: Float = 1f
        set(value) {
            if (value > 0) {
                field = value
            } else {
                throw RuntimeException("speed must > 0")
            }
        }

    /**
     * Maximum progress value
     */
    var maxProgress: Float = 100f
        set(value) {
            field = value
            refreshView()
        }

    /**
     * Current progress value
     */
    var durProgress: Float = 0f
        set(value) {
            val durValue = value.coerceIn(0f, maxProgress)
            durProgressFinal = durValue
            field = durProgressFinal // 直接赋值给 backing field
            refreshView() // 刷新视图
            onProgressChanged(durValue) // 回调监听
        }

    /**
     * Target progress value for animation
     */
    protected var durProgressFinal: Float = 0f

    // ==================== Drawable Properties ====================

    /**
     * Background drawable
     */
    var bgDrawable: Drawable? = null
        set(value) {
            field = value
            onBgDrawableChanged()
            refreshView()
        }

    /**
     * Foreground/font drawable showing progress
     */
    var fontDrawable: Drawable? = null
        set(value) {
            field = value
            onFontDrawableChanged()
            refreshView()
        }

    // ==================== Border Properties ====================

    /**
     * Border color
     */
    var bgBorderColor: Int = 0x00FFFFFF
        set(value) {
            field = value
            refreshView()
        }

    /**
     * Border width in pixels
     */
    var bgBorderWidth: Int = 0
        set(value) {
            field = value
            refreshView()
        }

    /**
     * Corner radius
     */
    var radius: Int = 0
        set(value) {
            field = value
            refreshView()
        }

    // ==================== Cursor Properties ====================

    /**
     * Cursor drawable for indicating current progress
     */
    var cursorDrawable: StateListDrawable? = null
        set(value) {
            field = value
            refreshView()
        }

    /**
     * Cursor width in pixels
     */
    var cursorDrawableWidth: Int = DisplayUtil.dip2px(15.0f).toInt()
        set(value) {
            field = value
            refreshView()
        }

    /**
     * Cursor height in pixels
     */
    var cursorDrawableHeight: Int =  DisplayUtil.dip2px(15.0f).toInt()
        set(value) {
            field = value
            refreshView()
        }

    // ==================== Paint Objects ====================

    /**
     * Paint for drawing border
     */
    protected val bgBorderPaint: Paint

    /**
     * Paint for drawing background
     */
    protected val bgPaint: Paint

    /**
     * Paint for drawing foreground/progress
     */
    protected val fontPaint: Paint

    // ==================== Handler ====================

    /**
     * Handler for posting UI updates to main thread
     */
    protected val mHandler: Handler = Handler(Looper.getMainLooper())

    init {

        bgBorderPaint = createBgBorderPaint()
        bgPaint = createBgPaint()
        fontPaint = createFontPaint()

        initializeFromAttributes(attrs)
    }

    // ==================== Initialization ====================

    /**
     * Initialize view attributes from XML
     */
    private fun initializeFromAttributes(attrs: AttributeSet?) {
        context.withStyledAttributes(attrs, R.styleable.BaseProgressBar) {
            canTouch = getBoolean(R.styleable.BaseProgressBar_cantouch, canTouch)
            bgBorderColor = getColor(R.styleable.BaseProgressBar_bgbordercolor, bgBorderColor)
            bgBorderWidth =
                getDimensionPixelSize(R.styleable.BaseProgressBar_bgborderwidth, bgBorderWidth)

            bgDrawable = getDrawable(R.styleable.BaseProgressBar_bgdrawable) ?: 0xFFC1C1C1.toInt()
                .toDrawable()

            fontDrawable = getDrawable(R.styleable.BaseProgressBar_fontdrawable) ?: 0xFF00CCFF.toInt()
                .toDrawable()

            maxProgress = getFloat(R.styleable.BaseProgressBar_maxprogress, maxProgress)
            durProgress = getFloat(R.styleable.BaseProgressBar_durprogress, durProgress)
            durProgressFinal = durProgress
            radius = getDimensionPixelSize(R.styleable.BaseProgressBar_radius, radius)

            try {
                getDrawable(R.styleable.BaseProgressBar_cursordrawable)?.let { drawable ->
                    cursorDrawable = drawable as? StateListDrawable
                        ?: StateListDrawable().apply {
                            addState(intArrayOf(), drawable)
                        }
                }
            } catch (e: Exception) {
                Log.e(TAG, "initializeFromAttributes: ", e)
            }

            cursorDrawableWidth = getDimensionPixelSize(
                R.styleable.BaseProgressBar_cursordrawable_width,
                cursorDrawableWidth
            )
            cursorDrawableHeight = getDimensionPixelSize(
                R.styleable.BaseProgressBar_cursordrawable_height,
                cursorDrawableHeight
            )

            initializeSubclassAttributes(this)
        }
    }

    /**
     * Allow subclasses to initialize their own attributes from XML.
     * This is called during initialization after common attributes are processed.
     *
     * @param typedArray The styled attributes context
     */
    protected open fun initializeSubclassAttributes(typedArray: TypedArray) {
        // Override in subclass if needed
    }

    /**
     * Called when background drawable is changed.
     * Subclasses can override to clear cached resources.
     */
    protected open fun onBgDrawableChanged() {
        // Override in subclass if needed
    }

    /**
     * Called when foreground drawable is changed.
     * Subclasses can override to clear cached resources.
     */
    protected open fun onFontDrawableChanged() {
        // Override in subclass if needed
    }

    // ==================== Paint Creation ====================

    /**
     * Create paint for border drawing
     */
    protected open fun createBgBorderPaint(): Paint {
        return Paint().apply {
            isFilterBitmap = true
            isAntiAlias = true
        }
    }

    /**
     * Create paint for background drawing
     */
    protected open fun createBgPaint(): Paint {
        return Paint().apply {
            isFilterBitmap = true
            isAntiAlias = true
            style = Paint.Style.FILL
            strokeWidth = 1f
        }
    }

    /**
     * Create paint for foreground/progress drawing
     */
    protected open fun createFontPaint(): Paint {
        return Paint().apply {
            isFilterBitmap = true
            isAntiAlias = true
            style = Paint.Style.FILL
            strokeWidth = 1f
        }
    }

    // ==================== Progress Management ====================
    /**
     * Set progress with animation
     */
    fun setDurProgressWithAnim(dur: Float) {
        val durValue = dur.coerceIn(0f, maxProgress)
        durProgressFinal = durValue
        refreshDurProgress(durProgress)
        onProgressChanged(durValue)
    }

    /**
     * Refresh progress display
     */
    protected fun refreshDurProgress(durProgress: Float) {
        this.durProgress = durProgress
        refreshView()
    }

    /**
     * Called when progress changes. Subclasses can override to respond to progress changes.
     *
     * @param newProgress The new progress value
     */
    protected open fun onProgressChanged(newProgress: Float) {
        // Override in subclass if needed
    }

    // ==================== View Refresh ====================

    /**
     * Request view redraw
     */
    protected fun refreshView() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            invalidate()
        } else {
            postInvalidate()
        }
    }

    // ==================== Utility Functions ====================

    /**
     * Update animation progress
     */
    protected fun updateAnimationProgress() {
        if (durProgress != durProgressFinal) {
            if (durProgress > durProgressFinal) {
                durProgress -= speed
                if (durProgress < durProgressFinal) {
                    durProgress = durProgressFinal
                }
            } else {
                durProgress += speed
                if (durProgress > durProgressFinal) {
                    durProgress = durProgressFinal
                }
            }
            invalidate()
        }
    }

    // ==================== Abstract Methods ====================

    /**
     * Subclasses must implement to return the real progress width/height dimension
     */
    protected abstract val realProgressDimension: Int

    companion object {
        const val TAG = "BaseProgressBar"
    }
}

