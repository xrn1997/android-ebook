package com.ebook.common.view.mprogressbar

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.TypedArray
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.ColorDrawable
import android.util.AttributeSet
import android.view.MotionEvent
import androidx.core.graphics.createBitmap
import androidx.core.graphics.toColorInt
import com.ebook.common.R

class MVerProgressBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : BaseProgressBar(context, attrs, defStyleAttr) {

    var startBottom: Int = 0 // 0从下到上 1进度从上到下
        set(value) {
            field = value
            refreshView()
        }

    // 不在声明时初始化，避免覆盖父类构造函数中设置的值
    var progressWidth: Int = 0 // 进度条宽度 -1默认填充全部，0表示未初始化
        set(value) {
            field = value
            refreshView()
        }
    
    init {
        // 在 init 块中设置默认值（此时父类构造已完成）
        if (progressWidth == 0) {
            progressWidth = -1
        }
    }

    private var fontDrawableType: Int = 0
    private var fontShader: BitmapShader? = null

    private var bgDrawableType: Int = 0
    private var bgShader: BitmapShader? = null

    private var rectFFont: RectF? = null
    private var rectFBg: RectF? = null

    var progressListener: OnProgressListener? = null

    override fun initializeSubclassAttributes(typedArray: TypedArray) {
        startBottom = typedArray.getInt(R.styleable.BaseProgressBar_startTopOrBottom, startBottom)
        progressWidth = typedArray.getDimensionPixelSize(R.styleable.BaseProgressBar_progresswidth, progressWidth)
        bgDrawableType = typedArray.getInt(R.styleable.BaseProgressBar_bgdrawable_type, bgDrawableType)
        fontDrawableType = typedArray.getInt(R.styleable.BaseProgressBar_fontdrawable_type, fontDrawableType)
    }

    override fun onBgDrawableChanged() {
        bgShader = null
    }

    override fun onFontDrawableChanged() {
        fontShader = null
    }

    override val realProgressDimension: Int
        get() = rectFBg?.height()?.toInt() ?: 0

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        // 计算实际绘制使用的进度条宽度（不修改成员变量）
        val actualProgressWidth = if (progressWidth <= 0) {
            if (measuredWidth - bgBorderWidth * 2 <= 0) {
                throw RuntimeException("bgBorderWidth超过绘制限度")
            }
            measuredWidth
        } else {
            if (progressWidth - bgBorderWidth * 2 <= 0) {
                throw RuntimeException("bgBorderWidth超过绘制限度")
            }
            progressWidth
        }

        val by: Float
        val byf: Float
        val fy: Float
        val fyf: Float

        if (cursorDrawable != null && cursorDrawableHeight / 2 > bgBorderWidth) {
            by = cursorDrawableHeight / 2f
            byf = measuredHeight - cursorDrawableWidth / 2f
            if (startBottom == 0) {
                fy = (measuredHeight - cursorDrawableHeight) * ((maxProgress - durProgress) / maxProgress) + cursorDrawableHeight / 2f
                fyf = measuredHeight - cursorDrawableHeight / 2f
            } else {
                fy = cursorDrawableHeight / 2f
                fyf = (measuredHeight - cursorDrawableHeight) * (durProgress / maxProgress) + cursorDrawableHeight / 2f
            }
        } else {
            by = bgBorderWidth.toFloat()
            byf = measuredHeight - bgBorderWidth.toFloat()
            if (startBottom == 0) {
                fy = (measuredHeight - bgBorderWidth * 2) * ((maxProgress - durProgress) / maxProgress) + bgBorderWidth
                fyf = measuredHeight - bgBorderWidth.toFloat()
            } else {
                fy = bgBorderWidth.toFloat()
                fyf = (measuredHeight - bgBorderWidth * 2) * (durProgress / maxProgress) + bgBorderWidth
            }
        }

        // 边框绘制
        if (bgBorderWidth > 0) {
            bgBorderPaint.color = bgBorderColor
            bgBorderPaint.strokeWidth = bgBorderWidth.toFloat()
            canvas.drawRoundRect(
                RectF(
                    0 + bgBorderWidth / 2f,
                    by - bgBorderWidth / 2f,
                    measuredWidth - bgBorderWidth / 2f,
                    byf + bgBorderWidth / 2f
                ),
                radius - bgBorderWidth / 2f,
                radius - bgBorderWidth / 2f,
                bgBorderPaint
            )
        }

        // BG绘制
        rectFBg = RectF(
            measuredWidth / 2f - actualProgressWidth / 2f + bgBorderWidth,
            by,
            measuredWidth / 2f + actualProgressWidth / 2f - bgBorderWidth,
            byf
        )

        bgDrawable?.let { drawable ->
            if (drawable is ColorDrawable) {
                bgPaint.color = drawable.color
                canvas.drawRoundRect(rectFBg!!, radius - bgBorderWidth.toFloat(), radius - bgBorderWidth.toFloat(), bgPaint)
            } else {
                if (bgDrawableType == 0) {
                    val durBitmap = createBitmap(measuredWidth, measuredHeight)
                    val durCanvas = Canvas(durBitmap)
                    bgPaint.color = "#ff000000".toColorInt()
                    durCanvas.drawRoundRect(rectFBg!!, radius - bgBorderWidth.toFloat(), radius - bgBorderWidth.toFloat(), bgPaint)
                    bgPaint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
                    durCanvas.drawBitmap(toBgBitmapNormal(rectFBg!!), 0f, 0f, bgPaint)
                    bgPaint.xfermode = null
                    canvas.drawBitmap(durBitmap, 0f, 0f, null)
                } else {
                    if (bgShader == null) {
                        updateBgShader(rectFBg!!)
                    }
                    bgPaint.shader = bgShader
                    canvas.drawRoundRect(rectFBg!!, radius - bgBorderWidth.toFloat(), radius - bgBorderWidth.toFloat(), bgPaint)
                    bgPaint.shader = null
                }
            }
        }

        // Font绘制
        val durBitmap = createBitmap(measuredWidth, measuredHeight, Bitmap.Config.ARGB_8888)
        val durCanvas = Canvas(durBitmap)
        fontPaint.color = "#ff000000".toColorInt()
        durCanvas.drawRoundRect(rectFBg!!, radius - bgBorderWidth.toFloat(), radius - bgBorderWidth.toFloat(), fontPaint)

        rectFFont = RectF(
            measuredWidth / 2f - actualProgressWidth / 2f + bgBorderWidth,
            fy,
            measuredWidth / 2f + actualProgressWidth / 2f - bgBorderWidth,
            fyf
        )
        fontPaint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)

        fontDrawable?.let { drawable ->
            if (drawable is ColorDrawable) {
                val bP = Paint()
                val bB = createBitmap(measuredWidth, measuredHeight, Bitmap.Config.ARGB_8888)
                val bC = Canvas(bB)
                bP.color = drawable.color
                bC.drawRect(rectFFont!!, bP)
                durCanvas.drawBitmap(bB, 0f, 0f, fontPaint)
            } else {
                when (fontDrawableType) {
                    0 -> durCanvas.drawBitmap(toFontBitmapNormal(rectFFont!!), 0f, 0f, fontPaint)
                    2 -> durCanvas.drawBitmap(toFontBitmapCover(rectFFont!!), 0f, 0f, fontPaint)
                    else -> {
                        if (fontShader == null) {
                            updateFontShader(rectFFont!!)
                        }
                        val bitmap =
                            createBitmap(measuredWidth, measuredHeight, Bitmap.Config.ARGB_8888)
                        val canvas1 = Canvas(bitmap)
                        val paint1 = Paint()
                        paint1.shader = fontShader
                        canvas1.drawRect(rectFFont!!, paint1)
                        paint1.shader = null
                        durCanvas.drawBitmap(bitmap, 0f, 0f, fontPaint)
                    }
                }
            }
        }
        fontPaint.xfermode = null
        canvas.drawBitmap(durBitmap, 0f, 0f, null)

        // 绘制游标图标
        cursorDrawable?.let { cursor ->
            val cursorD = cursor.current
            val cursorDrawableRect: Rect = if (startBottom == 0) {
                Rect(
                    measuredWidth / 2 - cursorDrawableWidth / 2,
                    (fy - cursorDrawableHeight / 2).toInt(),
                    measuredWidth / 2 + cursorDrawableWidth / 2,
                    (fy + cursorDrawableHeight / 2).toInt()
                )
            } else {
                Rect(
                    measuredWidth / 2 - cursorDrawableWidth / 2,
                    (fyf - cursorDrawableHeight / 2).toInt(),
                    measuredWidth / 2 + cursorDrawableWidth / 2,
                    (fyf + cursorDrawableHeight / 2).toInt()
                )
            }
            cursorD.bounds = cursorDrawableRect
            cursorD.draw(canvas)
        }

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

        progressListener?.durProgressChange(durProgress)
    }

    private fun updateBgShader(rectF: RectF) {
        bgShader = BitmapShader(toBgBitmap(rectF), Shader.TileMode.CLAMP, Shader.TileMode.REPEAT)
    }

    private fun toBgBitmap(rectF: RectF): Bitmap {
        val bitmap: Bitmap = bgDrawable?.let { drawable ->
            if (drawable.intrinsicWidth > 0 && drawable.intrinsicHeight > 0) {
                createBitmap(
                    measuredWidth,
                    (rectF.width() * (drawable.intrinsicHeight / drawable.intrinsicWidth.toFloat())).toInt(),
                    Bitmap.Config.ARGB_8888
                )
            } else {
                createBitmap(measuredWidth, rectF.width().toInt(), Bitmap.Config.ARGB_8888)
            }
        } ?: createBitmap(measuredWidth, rectF.width().toInt(), Bitmap.Config.ARGB_8888)

        val canvas = Canvas(bitmap)
        if (progressWidth == -1) {
            bgDrawable?.setBounds(bgBorderWidth, 0, bitmap.width - bgBorderWidth, bitmap.height)
        } else {
            bgDrawable?.setBounds(
                bitmap.width / 2 - progressWidth / 2 + bgBorderWidth,
                0,
                bitmap.width / 2 + progressWidth / 2 - bgBorderWidth,
                bitmap.height
            )
        }
        bgDrawable?.draw(canvas)
        return bitmap
    }

    private fun toBgBitmapNormal(rectF: RectF): Bitmap {
        val bitmap: Bitmap = bgDrawable?.let { drawable ->
            if (drawable.intrinsicWidth > 0 && drawable.intrinsicHeight > 0) {
                createBitmap(
                    rectF.width().toInt(),
                    (rectF.width() * 1.0f * drawable.intrinsicHeight / drawable.intrinsicWidth).toInt(),
                    Bitmap.Config.ARGB_8888
                )
            } else {
                createBitmap(rectF.width().toInt(), rectF.width().toInt(), Bitmap.Config.ARGB_8888)
            }
        } ?: createBitmap(rectF.width().toInt(), rectF.width().toInt(), Bitmap.Config.ARGB_8888)

        val canvas = Canvas(bitmap)
        bgDrawable?.setBounds(0, 0, bitmap.width, bitmap.height)
        bgDrawable?.draw(canvas)
        return bitmap
    }

    private fun updateFontShader(rectF: RectF) {
        fontShader = BitmapShader(toFontBitmap(rectF), Shader.TileMode.CLAMP, Shader.TileMode.REPEAT)
    }

    private fun toFontBitmap(rectF: RectF): Bitmap {
        val bitmap: Bitmap = fontDrawable?.let { drawable ->
            if (drawable.intrinsicWidth > 0 && drawable.intrinsicHeight > 0) {
                createBitmap(
                    measuredWidth,
                    (rectF.width() * 1.0f * drawable.intrinsicHeight / drawable.intrinsicWidth).toInt(),
                    Bitmap.Config.ARGB_8888
                )
            } else {
                createBitmap(measuredWidth, rectF.width().toInt(), Bitmap.Config.ARGB_8888)
            }
        } ?: createBitmap(measuredWidth, rectF.width().toInt(), Bitmap.Config.ARGB_8888)

        val canvas = Canvas(bitmap)
        fontDrawable?.setBounds(
            bitmap.width / 2 - progressWidth / 2 + bgBorderWidth,
            0,
            bitmap.width / 2 + progressWidth / 2 - bgBorderWidth,
            bitmap.height
        )
        fontDrawable?.draw(canvas)
        return bitmap
    }

    private fun toFontBitmapNormal(rectF: RectF): Bitmap {
        val bitmap = createBitmap(measuredWidth, measuredHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        fontDrawable?.setBounds(rectF.left.toInt(), rectF.top.toInt(), rectF.right.toInt(), rectF.bottom.toInt())
        fontDrawable?.draw(canvas)
        return bitmap
    }

    private fun toFontBitmapCover(rectF: RectF): Bitmap {
        val bitmap: Bitmap = fontDrawable?.let { drawable ->
            if (drawable.intrinsicWidth > 0 && drawable.intrinsicHeight > 0) {
                createBitmap(
                    drawable.intrinsicWidth,
                    drawable.intrinsicHeight,
                    Bitmap.Config.ARGB_8888
                )
            } else {
                createBitmap(rectF.width().toInt(), rectF.width().toInt(), Bitmap.Config.ARGB_8888)
            }
        } ?: createBitmap(rectF.width().toInt(), rectF.width().toInt(), Bitmap.Config.ARGB_8888)

        val canvas = Canvas(bitmap)
        fontDrawable?.setBounds(0, 0, bitmap.width, bitmap.height)
        fontDrawable?.draw(canvas)

        val result = createBitmap(measuredWidth, measuredHeight, Bitmap.Config.ARGB_8888)
        val resultCanvas = Canvas(result)
        val a: Rect = if (startBottom == 0) {
            Rect(
                0,
                (bitmap.height * (maxProgress - durProgress) / maxProgress).toInt(),
                bitmap.width,
                bitmap.height
            )
        } else {
            Rect(0, 0, bitmap.width, (bitmap.height * durProgress / maxProgress).toInt())
        }
        resultCanvas.drawBitmap(bitmap, a, rectF, Paint())
        return result
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (canTouch) {
            val action = event.action
            var y = event.y
            y -= cursorDrawable?.let { cursorDrawableHeight / 2f } ?: 0f
            val progressWidth = cursorDrawable?.let { measuredHeight - cursorDrawableHeight } ?: measuredHeight

            y = when {
                y < 0 -> 0f
                y > progressWidth -> progressWidth.toFloat()
                else -> y
            }

            when (action) {
                MotionEvent.ACTION_DOWN -> {
                    cursorDrawable?.state = intArrayOf(android.R.attr.state_pressed)
                    if (startBottom == 0) {
                        durProgressFinal = (progressWidth - y) / progressWidth * maxProgress
                        refreshDurProgress(durProgressFinal)
                    } else {
                        durProgressFinal = y / progressWidth * maxProgress
                        refreshDurProgress(durProgressFinal)
                    }
                    progressListener?.moveStartProgress(durProgress)
                }
                MotionEvent.ACTION_MOVE -> {
                    if (startBottom == 0) {
                        durProgressFinal = (progressWidth - y) / progressWidth * maxProgress
                        refreshDurProgress(durProgressFinal)
                    } else {
                        durProgressFinal = y / progressWidth * maxProgress
                        refreshDurProgress(durProgressFinal)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    cursorDrawable?.state = intArrayOf()
                    if (startBottom == 0) {
                        durProgressFinal = (progressWidth - y) / progressWidth * maxProgress
                        refreshDurProgress(durProgressFinal)
                    } else {
                        durProgressFinal = y / progressWidth * maxProgress
                        refreshDurProgress(durProgressFinal)
                    }
                    progressListener?.moveStopProgress(durProgress)
                }
            }
            return true
        } else {
            return super.onTouchEvent(event)
        }
    }

    override fun onProgressChanged(newProgress: Float) {
        progressListener?.let { listener ->
            mHandler.post { listener.setDurProgress(newProgress) }
        }
    }

    fun setFontDrawableType(fontDrawableType: Int) {
        this.fontDrawableType = fontDrawableType
        fontShader = null
        refreshView()
    }

    fun setBgDrawableType(bgDrawableType: Int) {
        this.bgDrawableType = bgDrawableType
        bgShader = null
        refreshView()
    }


    companion object {
        const val TAG = "MVerProgressBar"
    }
}
