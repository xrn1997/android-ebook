package com.ebook.common.view.profilePhoto

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.util.AttributeSet
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.appcompat.widget.AppCompatImageView
import androidx.core.content.ContextCompat
import androidx.core.content.withStyledAttributes
import androidx.core.graphics.createBitmap
import com.ebook.common.R
import kotlin.math.min
import kotlin.math.pow

/**
 * [CircleImageView](https://github.com/hdodenhof/CircleImageView)
 * kotlin重构版
 */
class CircleImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : AppCompatImageView(context, attrs, defStyle) {
    private val mDrawableRect = RectF()
    private val mBorderRect = RectF()

    private val mShaderMatrix = Matrix()
    private val mBitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
    private val mBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val mFillPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private var mBorderColor = DEFAULT_BORDER_COLOR
    private var mBorderWidth = DEFAULT_BORDER_WIDTH
    private var mFillColor = DEFAULT_FILL_COLOR

    private var mBitmap: Bitmap? = null
    private var mBitmapShader: BitmapShader? = null
    private var mBitmapWidth = 0
    private var mBitmapHeight = 0

    private var mDrawableRadius = 0f
    private var mBorderRadius = 0f

    private var mColorFilter: ColorFilter? = null
    private var mBorderOverlay = DEFAULT_BORDER_OVERLAY
    private var mDisableCircularTransformation = false

    init {
        context.withStyledAttributes(attrs, R.styleable.CircleImageView, defStyle, 0) {
            mBorderWidth = getDimensionPixelSize(
                R.styleable.CircleImageView_civ_border_width,
                DEFAULT_BORDER_WIDTH
            )
            mBorderColor =
                getColor(R.styleable.CircleImageView_civ_border_color, DEFAULT_BORDER_COLOR)
            mBorderOverlay =
                getBoolean(R.styleable.CircleImageView_civ_border_overlay, DEFAULT_BORDER_OVERLAY)
            mFillColor = getColor(R.styleable.CircleImageView_civ_fill_color, DEFAULT_FILL_COLOR)
        }

        super.setScaleType(SCALE_TYPE)

        mBitmapPaint.isAntiAlias = true
        mBitmapPaint.isDither = true
        mBitmapPaint.isFilterBitmap = true
        mBitmapPaint.colorFilter = mColorFilter

        mBorderPaint.style = Paint.Style.STROKE
        mBorderPaint.isAntiAlias = true
        mBorderPaint.color = mBorderColor
        mBorderPaint.strokeWidth = mBorderWidth.toFloat()

        mFillPaint.style = Paint.Style.FILL
        mFillPaint.color = mFillColor

        outlineProvider = CircleOutlineProvider()
        clipToOutline = true
        post { setup() }
    }

    override fun getScaleType(): ScaleType = SCALE_TYPE

    override fun setScaleType(scaleType: ScaleType) {
        require(scaleType == SCALE_TYPE) { "ScaleType $scaleType not supported." }
    }

    override fun setAdjustViewBounds(adjustViewBounds: Boolean) {
        require(!adjustViewBounds) { "adjustViewBounds not supported." }
    }

    override fun onDraw(canvas: Canvas) {
        if (mDisableCircularTransformation) {
            super.onDraw(canvas)
            return
        }

        if (mFillColor != Color.TRANSPARENT) {
            canvas.drawCircle(
                mDrawableRect.centerX(),
                mDrawableRect.centerY(),
                mDrawableRadius,
                mFillPaint
            )
        }

        // 绘制图片（如果有）
        mBitmap?.let {
            canvas.drawCircle(
                mDrawableRect.centerX(),
                mDrawableRect.centerY(),
                mDrawableRadius,
                mBitmapPaint
            )
        }

        // 绘制边框（如果有）
        if (mBorderWidth > 0) {
            canvas.drawCircle(
                mBorderRect.centerX(),
                mBorderRect.centerY(),
                mBorderRadius,
                mBorderPaint
            )
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        setup()
    }

    override fun setPadding(left: Int, top: Int, right: Int, bottom: Int) {
        super.setPadding(left, top, right, bottom)
        setup()
    }

    override fun setPaddingRelative(start: Int, top: Int, end: Int, bottom: Int) {
        super.setPaddingRelative(start, top, end, bottom)
        setup()
    }

    var borderColor: Int
        get() = mBorderColor
        set(value) {
            if (mBorderColor != value) {
                mBorderColor = value
                mBorderPaint.color = value
                invalidate()
            }
        }

    fun setBorderColorResource(@ColorRes resId: Int) {
        borderColor = ContextCompat.getColor(context, resId)
    }

    var fillColor: Int
        get() = mFillColor
        set(value) {
            if (mFillColor != value) {
                mFillColor = value
                mFillPaint.color = value
                invalidate()
            }
        }

    fun setFillColorResource(@ColorRes resId: Int) {
        fillColor = ContextCompat.getColor(context, resId)
    }

    var borderWidth: Int
        get() = mBorderWidth
        set(value) {
            if (mBorderWidth != value) {
                mBorderWidth = value
                mBorderPaint.strokeWidth = value.toFloat()
                setup()
            }
        }

    var isBorderOverlay: Boolean
        get() = mBorderOverlay
        set(value) {
            if (mBorderOverlay != value) {
                mBorderOverlay = value
                setup()
            }
        }

    var isDisableCircularTransformation: Boolean
        get() = mDisableCircularTransformation
        set(value) {
            if (mDisableCircularTransformation != value) {
                mDisableCircularTransformation = value
                initializeBitmap()
            }
        }

    override fun setImageBitmap(bm: Bitmap) {
        super.setImageBitmap(bm)
        initializeBitmap()
    }

    override fun setImageDrawable(drawable: Drawable?) {
        super.setImageDrawable(drawable)
        initializeBitmap()
    }

    override fun setImageResource(@DrawableRes resId: Int) {
        super.setImageResource(resId)
        initializeBitmap()
    }

    override fun setImageURI(uri: Uri?) {
        super.setImageURI(uri)
        initializeBitmap()
    }

    override fun getColorFilter(): ColorFilter? = mColorFilter

    override fun setColorFilter(cf: ColorFilter?) {
        if (mColorFilter != cf) {
            mColorFilter = cf
            mBitmapPaint.colorFilter = cf
            invalidate()
        }
    }

    private fun initializeBitmap() {
        mBitmap = if (mDisableCircularTransformation) null else getBitmapFromDrawable(drawable)
        setup()
    }

    private fun getBitmapFromDrawable(drawable: Drawable?): Bitmap? {
        if (drawable == null) return null
        if (drawable is BitmapDrawable) return drawable.bitmap

        return try {
            val bitmap = if (drawable is ColorDrawable) {
                createBitmap(COLORDRAWABLE_DIMENSION, COLORDRAWABLE_DIMENSION, BITMAP_CONFIG)
            } else {
                createBitmap(drawable.intrinsicWidth, drawable.intrinsicHeight, BITMAP_CONFIG)
            }
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, canvas.width, canvas.height)
            drawable.draw(canvas)
            bitmap
        } catch (e: Exception) {
            Log.e(TAG, "getBitmapFromDrawable: ", e)
            null
        }
    }

    private fun setup() {
        if (width == 0 || height == 0) return

        // 无论是否有 bitmap，都要计算边界和半径
        mBorderRect.set(calculateBounds())
        mBorderRadius = min(
            (mBorderRect.height() - mBorderWidth) / 2f,
            (mBorderRect.width() - mBorderWidth) / 2f
        )

        mDrawableRect.set(mBorderRect)
        if (!mBorderOverlay && mBorderWidth > 0) {
            mDrawableRect.inset(mBorderWidth.toFloat(), mBorderWidth.toFloat())
        }
        mDrawableRadius = min(mDrawableRect.height() / 2f, mDrawableRect.width() / 2f)

        // 如果有 bitmap，设置 Shader
        mBitmap?.let {
            mBitmapShader = BitmapShader(it, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            mBitmapPaint.shader = mBitmapShader
            mBitmapWidth = it.width
            mBitmapHeight = it.height
            updateShaderMatrix()
        }

        invalidate()
    }


    private fun calculateBounds(): RectF {
        val availableWidth = width - paddingLeft - paddingRight
        val availableHeight = height - paddingTop - paddingBottom
        val sideLength = min(availableWidth, availableHeight)
        val left = paddingLeft + (availableWidth - sideLength) / 2f
        val top = paddingTop + (availableHeight - sideLength) / 2f
        return RectF(left, top, left + sideLength, top + sideLength)
    }

    private fun updateShaderMatrix() {
        val bitmap = mBitmap ?: return

        val scale: Float
        var dx = 0f
        var dy = 0f

        mShaderMatrix.set(null)

        if (bitmap.width * mDrawableRect.height() > mDrawableRect.width() * bitmap.height) {
            scale = mDrawableRect.height() / bitmap.height.toFloat()
            dx = (mDrawableRect.width() - bitmap.width * scale) * 0.5f
        } else {
            scale = mDrawableRect.width() / bitmap.width.toFloat()
            dy = (mDrawableRect.height() - bitmap.height * scale) * 0.5f
        }

        mShaderMatrix.setScale(scale, scale)
        mShaderMatrix.postTranslate(dx + mDrawableRect.left, dy + mDrawableRect.top)
        mBitmapShader?.setLocalMatrix(mShaderMatrix)
    }

    // 🔹 圆形触摸限制
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (mDisableCircularTransformation) return super.onTouchEvent(event)
        return inTouchableArea(event.x, event.y) && super.onTouchEvent(event)
    }

    private fun inTouchableArea(x: Float, y: Float): Boolean {
        if (mBorderRect.isEmpty) return true
        val cx = mBorderRect.centerX()
        val cy = mBorderRect.centerY()
        return (x - cx).pow(2) + (y - cy).pow(2) <= mBorderRadius.pow(2)
    }

    // 🔹 OutlineProvider 支持圆形阴影裁剪
    private inner class CircleOutlineProvider : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            if (mDisableCircularTransformation) {
                BACKGROUND.getOutline(view, outline)
            } else {
                val bounds = Rect()
                mBorderRect.roundOut(bounds)
                outline.setRoundRect(bounds, bounds.width() / 2f)
            }
        }
    }

    companion object {
        private const val TAG = "CircleImageView"
        private val SCALE_TYPE = ScaleType.CENTER_CROP
        private val BITMAP_CONFIG = Bitmap.Config.ARGB_8888
        private const val COLORDRAWABLE_DIMENSION = 2
        private const val DEFAULT_BORDER_WIDTH = 0
        private const val DEFAULT_BORDER_COLOR = Color.BLACK
        private const val DEFAULT_FILL_COLOR = Color.TRANSPARENT
        private const val DEFAULT_BORDER_OVERLAY = false
    }
}
