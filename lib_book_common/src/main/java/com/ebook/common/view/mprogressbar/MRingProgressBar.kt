package com.ebook.common.view.mprogressbar

import android.content.Context
import android.content.res.TypedArray
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import androidx.core.graphics.createBitmap
import androidx.core.graphics.toColorInt
import com.ebook.common.R
import com.xrn1997.common.util.DisplayUtil
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class MRingProgressBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : BaseProgressBar(context, attrs, defStyleAttr) {

    var startLeft: Int = 1 // 0逆时针 1顺时针
        set(value) {
            field = value
            refreshView()
        }

    var progressWidth: Int =  DisplayUtil.dip2px(15.0f).toInt()
        set(value) {
            field = value
            refreshView()
        }

    var startAngle: Int = 0
        set(value) {
            field = value
            refreshView()
        }

    private var fontBitmap: Bitmap? = null
    private var bgBitmap: Bitmap? = null
    private var cursorBitmap: Bitmap? = null

    private var paint: Paint = Paint().apply {
        isAntiAlias = true
    }
    var ringProgressListener: OnRingProgressListener? = null

    override var speed: Float = 1f
        set(value) {
            if (value > 0) {
                field = value
            } else {
                throw RuntimeException("speed must > 0")
            }
        }

    override fun initializeSubclassAttributes(typedArray: TypedArray) {
        startAngle = typedArray.getInt(R.styleable.BaseProgressBar_startangle, startAngle) % 360
        startLeft = typedArray.getInt(R.styleable.BaseProgressBar_startLeftOrRight, startLeft)
        progressWidth =
            typedArray.getDimensionPixelSize(R.styleable.BaseProgressBar_progresswidth, progressWidth)
    }

    override fun onBgDrawableChanged() {
        bgBitmap = null
    }

    override fun onFontDrawableChanged() {
        fontBitmap = null
    }

    override fun createBgPaint(): Paint {
        return Paint().apply {
            isAntiAlias = true
        }
    }

    override val realProgressDimension: Int
        get() = progressWidth

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (progressWidth - bgBorderWidth * 2 <= 0) {
            throw RuntimeException("bgBorderWidth超过绘制限度")
        }

        var abc = 0 // 怎么描述？就是如果cursorDrawable图片对角线的大小比progress大的时候 为了让cursorDrawable显示完全 需要将图片半径统一缩小abc
        cursorDrawable?.let {
            val temp = sqrt((cursorDrawableWidth * cursorDrawableWidth + cursorDrawableHeight * cursorDrawableHeight).toDouble()).toInt()
            if (temp > progressWidth) {
                abc = (temp - progressWidth) / 2
            }
        }

        paint.style = Paint.Style.STROKE
        paint.color = bgBorderColor
        if (bgBorderWidth > 0) {
            paint.strokeWidth = bgBorderWidth.toFloat()
            val borderRadioLong = if (measuredHeight < measuredWidth) {
                measuredHeight / 2f
            } else {
                measuredWidth / 2f
            } - bgBorderWidth / 2f - abc
            canvas.drawCircle(measuredWidth / 2f, measuredHeight / 2f, borderRadioLong, paint)
        }

        val bBitmap = createBitmap(measuredWidth, measuredHeight)
        val bCanvas = Canvas(bBitmap)
        if (bgBitmap == null) {
            bgBitmap = getBgBitmap()
        }
        paint.color = "#000000".toColorInt()
        paint.strokeWidth = (progressWidth - bgBorderWidth * 2).toFloat()
        val bgRadio = if (measuredHeight < measuredWidth) {
            measuredHeight / 2f
        } else {
            measuredWidth / 2f
        } - progressWidth / 2f - abc
        bCanvas.drawCircle(measuredWidth / 2f, measuredHeight / 2f, bgRadio, paint)
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        bgBitmap?.let {
            bCanvas.drawBitmap(
                it,
                null,
                Rect(abc + bgBorderWidth, abc + bgBorderWidth, measuredWidth - abc - bgBorderWidth, measuredHeight - abc - bgBorderWidth),
                paint
            )
        }
        paint.xfermode = null
        canvas.drawBitmap(bBitmap, 0f, 0f, null)

        if (bgBorderWidth > 0) {
            paint.style = Paint.Style.STROKE
            paint.color = bgBorderColor
            paint.strokeWidth = bgBorderWidth.toFloat()
            val borderRadioShot = if (measuredHeight < measuredWidth) {
                measuredHeight / 2f
            } else {
                measuredWidth / 2f
            } - (progressWidth - bgBorderWidth / 2f) - abc
            canvas.drawCircle(measuredWidth / 2f, measuredHeight / 2f, borderRadioShot, paint)
        }

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = (progressWidth - bgBorderWidth * 2).toFloat()
        val r = (minOf(measuredHeight, measuredWidth)) / 2 - progressWidth / 2 - abc
        val sweepAngle = (if (startLeft == 1) 1 else -1) * (durProgress / maxProgress * 360)
        val durBitmap = createBitmap(measuredWidth, measuredHeight)
        val durCanvas = Canvas(durBitmap)
        if (fontBitmap == null) {
            fontBitmap = getFontBitmap()
        }

        durCanvas.drawArc(
            RectF(
                measuredWidth / 2f - r,
                measuredHeight / 2f - r,
                measuredWidth / 2f + r,
                measuredHeight / 2f + r
            ),
            startAngle.toFloat(),
            sweepAngle,
            false,
            paint
        )
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        fontBitmap?.let {
            durCanvas.drawBitmap(
                it,
                null,
                Rect(abc + bgBorderWidth, abc + bgBorderWidth, measuredWidth - abc - bgBorderWidth, measuredHeight - abc - bgBorderWidth),
                paint
            )
        }
        paint.xfermode = null
        canvas.drawBitmap(durBitmap, 0f, 0f, null)

        cursorDrawable?.let {
            val cursorDrawableX = (measuredWidth / 2 + r * cos((startAngle + sweepAngle) / 180 * Math.PI)).toFloat()
            val cursorDrawableY = (measuredHeight / 2 + r * sin((startAngle + sweepAngle) / 180 * Math.PI)).toFloat()
            val cursorDrawableRect = Rect(
                (cursorDrawableX - cursorDrawableWidth / 2).toInt(),
                (cursorDrawableY - cursorDrawableHeight / 2).toInt(),
                (cursorDrawableX + cursorDrawableWidth / 2).toInt(),
                (cursorDrawableY + cursorDrawableHeight / 2).toInt()
            )
            canvas.drawBitmap(getCursorToBitmap(), null, cursorDrawableRect, paint)
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

        ringProgressListener?.durProgressChange(durProgress)
    }

    private fun getBitmap(cursorD: Drawable, rect: Rect): Bitmap {
        val bitmap = createBitmap(rect.width(), rect.height(), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        cursorD.setBounds(0, 0, bitmap.width, bitmap.height)
        cursorD.draw(canvas)
        return bitmap
    }

    private fun getCursorToBitmap(): Bitmap {
        if (cursorBitmap == null) {
            cursorBitmap = cursorDrawable?.current?.let {
                getBitmap(it, Rect(0, 0, cursorDrawableWidth, cursorDrawableHeight))
            }
        }
        val m = Matrix()
        val orientationDegree = 360 * durProgress / maxProgress
        m.setRotate(orientationDegree, cursorDrawableWidth / 2f, cursorDrawableHeight / 2f)

        val bm1 = createBitmap(cursorBitmap?.height ?: cursorDrawableHeight, cursorBitmap?.width ?: cursorDrawableWidth, Bitmap.Config.ARGB_8888)
        val paint = Paint()
        val canvas = Canvas(bm1)
        cursorBitmap?.let {
            canvas.drawBitmap(it, m, paint)
        }
        return bm1
    }

    private fun getFontBitmap(): Bitmap {
        val bitmap = createBitmap(measuredWidth, measuredHeight, Bitmap.Config.ARGB_8888)
        val fontCanvas = Canvas(bitmap)
        fontDrawable?.setBounds(0, 0, bitmap.width, bitmap.height)
        fontDrawable?.draw(fontCanvas)
        return bitmap
    }

    private fun getBgBitmap(): Bitmap {
        val bitmap = createBitmap(measuredWidth, measuredHeight, Bitmap.Config.ARGB_8888)
        val bgCanvas = Canvas(bitmap)
        bgDrawable?.setBounds(0, 0, bitmap.width, bitmap.height)
        bgDrawable?.draw(bgCanvas)
        return bitmap
    }

    companion object {
        const val TAG = "MRingProgressBar"
    }
}
