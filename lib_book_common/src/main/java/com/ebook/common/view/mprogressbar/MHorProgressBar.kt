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

class MHorProgressBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : BaseProgressBar(context, attrs, defStyleAttr) {

    var startLeft: Int = 0 // 0从左开始到右 1进度从右到左
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
        startLeft = typedArray.getInt(R.styleable.BaseProgressBar_startLeftOrRight, startLeft)
        val readValue = typedArray.getDimensionPixelSize(R.styleable.BaseProgressBar_progresswidth, progressWidth)
        progressWidth = readValue
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
        get() = rectFBg?.width()?.toInt() ?: 0

    override fun createBgBorderPaint(): Paint {
        return Paint().apply {
            isFilterBitmap = true
            isAntiAlias = true
            style = Paint.Style.STROKE
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // 计算实际绘制使用的进度条宽度（不修改成员变量）
        val actualProgressWidth = if (progressWidth <= 0) {
            if (measuredHeight - bgBorderWidth * 2 <= 0) {
                throw RuntimeException("bgBorderWidth超过绘制限度")
            }
            measuredHeight
        } else {
            if (progressWidth - bgBorderWidth * 2 <= 0) {
                throw RuntimeException("bgBorderWidth超过绘制限度")
            }
            progressWidth
        }
        val bx: Float
        val bxf: Float
        val fx: Float
        val fxf: Float

        if (cursorDrawable != null && cursorDrawableWidth / 2 > bgBorderWidth) {
            bx = cursorDrawableWidth / 2f
            bxf = measuredWidth - cursorDrawableWidth / 2f
            if (startLeft == 0) {
                fx = cursorDrawableWidth / 2f
                fxf = (measuredWidth - cursorDrawableWidth) * (durProgress / maxProgress) + cursorDrawableWidth / 2f
            } else {
                fx = (measuredWidth - cursorDrawableWidth) * ((maxProgress - durProgress) / maxProgress) + cursorDrawableWidth / 2f
                fxf = measuredWidth - cursorDrawableWidth / 2f
            }
        } else {
            bx = bgBorderWidth.toFloat()
            bxf = measuredWidth - bgBorderWidth.toFloat()
            if (startLeft == 0) {
                fx = bgBorderWidth.toFloat()
                fxf = (measuredWidth - bgBorderWidth * 2) * (durProgress / maxProgress) + bgBorderWidth
            } else {
                fx = (measuredWidth - bgBorderWidth * 2) * ((maxProgress - durProgress) / maxProgress) + bgBorderWidth
                fxf = measuredWidth - bgBorderWidth.toFloat()
            }
        }

        // 边框绘制
        if (bgBorderWidth > 0) {
            bgBorderPaint.color = bgBorderColor
            bgBorderPaint.strokeWidth = bgBorderWidth.toFloat()
            canvas.drawRoundRect(
                RectF(
                    bx - bgBorderWidth / 2f,
                    measuredHeight / 2f - actualProgressWidth / 2f + bgBorderWidth / 2f,
                    bxf + bgBorderWidth / 2f,
                    measuredHeight / 2f + actualProgressWidth / 2f - bgBorderWidth / 2f
                ),
                radius - bgBorderWidth / 2f,
                radius - bgBorderWidth / 2f,
                bgBorderPaint
            )
        }

        // BG绘制
        rectFBg = RectF(
            bx,
            measuredHeight / 2f - actualProgressWidth / 2f + bgBorderWidth,
            bxf,
            measuredHeight / 2f + actualProgressWidth / 2f - bgBorderWidth
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
        val durBitmap = createBitmap(measuredWidth, measuredHeight)
        val durCanvas = Canvas(durBitmap)
        fontPaint.color = "#ff000000".toColorInt()
        durCanvas.drawRoundRect(rectFBg!!, radius - bgBorderWidth.toFloat(), radius - bgBorderWidth.toFloat(), fontPaint)

        rectFFont = RectF(
            fx,
            measuredHeight / 2f - actualProgressWidth / 2f + bgBorderWidth,
            fxf,
            measuredHeight / 2f + actualProgressWidth / 2f - bgBorderWidth
        )
        fontPaint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)

        fontDrawable?.let { drawable ->
            if (drawable is ColorDrawable) {
                val bP = Paint()
                val bB = createBitmap(measuredWidth, measuredHeight)
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
                        val bitmap = createBitmap(measuredWidth, measuredHeight)
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
        canvas.drawBitmap(durBitmap, 0f, 0f, Paint())

        // 绘制游标图标
        cursorDrawable?.let { cursor ->
            val cursorD = cursor.current
            val cursorDrawableRect: Rect = if (startLeft == 0) {
                Rect(
                    (fxf - cursorDrawableWidth / 2).toInt(),
                    measuredHeight / 2 - cursorDrawableHeight / 2,
                    (fxf + cursorDrawableWidth / 2).toInt(),
                    measuredHeight / 2 + cursorDrawableHeight / 2
                )
            } else {
                Rect(
                    (fx - cursorDrawableWidth / 2).toInt(),
                    measuredHeight / 2 - cursorDrawableHeight / 2,
                    (fx + cursorDrawableWidth / 2).toInt(),
                    measuredHeight / 2 + cursorDrawableHeight / 2
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
        bgShader = BitmapShader(toBgBitmap(rectF), Shader.TileMode.REPEAT, Shader.TileMode.CLAMP)
    }

    private fun toBgBitmap(rectF: RectF): Bitmap {
        val bitmap: Bitmap = bgDrawable?.let { drawable ->
            if (drawable.intrinsicWidth > 0 && drawable.intrinsicHeight > 0) {
                createBitmap(
                    (rectF.height() * 1.0f * drawable.intrinsicWidth / drawable.intrinsicHeight).toInt(),
                    measuredHeight
                )
            } else {
                createBitmap(rectF.height().toInt(), measuredHeight)
            }
        } ?: createBitmap(rectF.height().toInt(), measuredHeight)

        val canvas = Canvas(bitmap)
        if (progressWidth == -1) {
            bgDrawable?.setBounds(0, bgBorderWidth, bitmap.width, bitmap.height - bgBorderWidth)
        } else {
            bgDrawable?.setBounds(
                0,
                bitmap.height / 2 - progressWidth / 2 + bgBorderWidth,
                bitmap.width,
                height / 2 + progressWidth / 2 - bgBorderWidth
            )
        }
        bgDrawable?.draw(canvas)
        return bitmap
    }

    private fun toBgBitmapNormal(rectF: RectF): Bitmap {
        val bitmap = createBitmap(measuredWidth, measuredHeight)
        val canvas = Canvas(bitmap)
        bgDrawable?.setBounds(rectF.left.toInt(), rectF.top.toInt(), rectF.right.toInt(), rectF.bottom.toInt())
        bgDrawable?.draw(canvas)
        return bitmap
    }

    private fun updateFontShader(rectF: RectF) {
        fontShader = BitmapShader(toFontBitmap(rectF), Shader.TileMode.REPEAT, Shader.TileMode.CLAMP)
    }

    private fun toFontBitmap(rectF: RectF): Bitmap {
        val bitmap: Bitmap = fontDrawable?.let { drawable ->
            if (drawable.intrinsicWidth > 0 && drawable.intrinsicHeight > 0) {
                createBitmap(
                    (rectF.height() * 1.0f * drawable.intrinsicWidth / drawable.intrinsicHeight).toInt(),
                    measuredHeight
                )
            } else {
                createBitmap(rectF.height().toInt(), measuredHeight)
            }
        } ?: createBitmap(rectF.height().toInt(), measuredHeight)

        val canvas = Canvas(bitmap)
        fontDrawable?.setBounds(
            0,
            bitmap.height / 2 - progressWidth / 2 + bgBorderWidth,
            bitmap.width,
            height / 2 + progressWidth / 2 - bgBorderWidth
        )
        fontDrawable?.draw(canvas)
        return bitmap
    }

    private fun toFontBitmapNormal(rectF: RectF): Bitmap {
        val bitmap = createBitmap(measuredWidth, measuredHeight)
        val canvas = Canvas(bitmap)
        fontDrawable?.setBounds(rectF.left.toInt(), rectF.top.toInt(), rectF.right.toInt(), rectF.bottom.toInt())
        fontDrawable?.draw(canvas)
        return bitmap
    }

    private fun toFontBitmapCover(rectF: RectF): Bitmap {
        val bitmap: Bitmap = fontDrawable?.let { drawable ->
            if (drawable.intrinsicWidth > 0 && drawable.intrinsicHeight > 0) {
                createBitmap(drawable.intrinsicWidth, drawable.intrinsicHeight)
            } else {
                createBitmap(rectF.height().toInt(), rectF.height().toInt())
            }
        } ?: createBitmap(rectF.height().toInt(), rectF.height().toInt())

        val canvas = Canvas(bitmap)
        fontDrawable?.setBounds(0, 0, bitmap.width, bitmap.height)
        fontDrawable?.draw(canvas)

        val result = createBitmap(measuredWidth, measuredHeight)
        val resultCanvas = Canvas(result)
        val a: Rect = if (startLeft == 0) {
            Rect(0, 0, (bitmap.width * durProgress / maxProgress).toInt(), bitmap.height)
        } else {
            Rect((bitmap.width * (maxProgress - durProgress) / maxProgress).toInt(), 0, bitmap.width, bitmap.height)
        }
        resultCanvas.drawBitmap(bitmap, a, rectF, Paint())
        return result
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (canTouch) {
            val action = event.action
            var x = event.x
            x -= cursorDrawable?.let { cursorDrawableWidth / 2f } ?: 0f
            val progressWidth = cursorDrawable?.let { measuredWidth - cursorDrawableWidth } ?: measuredWidth

            x = when {
                x < 0 -> 0f
                x > progressWidth -> progressWidth.toFloat()
                else -> x
            }

            when (action) {
                MotionEvent.ACTION_DOWN -> {
                    cursorDrawable?.state = intArrayOf(android.R.attr.state_pressed)
                    if (startLeft == 0) {
                        durProgressFinal = x / progressWidth * maxProgress
                        refreshDurProgress(durProgressFinal)
                    } else {
                        durProgressFinal = (progressWidth - x) / progressWidth * maxProgress
                        refreshDurProgress(durProgressFinal)
                    }
                    progressListener?.moveStartProgress(durProgress)
                }
                MotionEvent.ACTION_MOVE -> {
                    if (startLeft == 0) {
                        durProgressFinal = x / progressWidth * maxProgress
                        refreshDurProgress(durProgressFinal)
                    } else {
                        durProgressFinal = (progressWidth - x) / progressWidth * maxProgress
                        refreshDurProgress(durProgressFinal)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    cursorDrawable?.state = intArrayOf()
                    if (startLeft == 0) {
                        durProgressFinal = x / progressWidth * maxProgress
                        refreshDurProgress(durProgressFinal)
                    } else {
                        durProgressFinal = (progressWidth - x) / progressWidth * maxProgress
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
}
