package com.ebook.common.view.profilePhoto

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.Xfermode
import android.util.AttributeSet
import android.view.View
import com.ebook.common.util.ScreenUtils

/**
 * 头像上传裁剪框
 */
class ClipView @JvmOverloads constructor(
    context: Context?,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) :
    View(context, attrs, defStyle) {
    private val paint = Paint()

    //画裁剪区域边框的画笔
    private val borderPaint = Paint()
    private val xfermode: Xfermode

    //裁剪框水平方向间距
    private var mHorizontalPadding = 0f

    //裁剪框边框宽度
    private var clipBorderWidth = 0

    //裁剪圆框的半径
    private var clipRadiusWidth = 0

    //裁剪框矩形宽度
    private var clipWidth = 0

    //裁剪框类别，（圆形、矩形），默认为圆形
    private var clipType = ClipType.CIRCLE

    init {
        //去锯齿
        paint.isAntiAlias = true
        borderPaint.style = Paint.Style.STROKE
        borderPaint.color = Color.WHITE
        borderPaint.strokeWidth = clipBorderWidth.toFloat()
        borderPaint.isAntiAlias = true
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        //通过Xfermode 的DST_OUT来产生中间的透明裁剪区域，一定要另起一个Layer（层）
        canvas.saveLayer(0f, 0f, this.width.toFloat(), this.height.toFloat(), null)
        //设置背景
        canvas.drawColor(Color.parseColor("#a8000000"))
        paint.setXfermode(xfermode)
        //绘制圆形裁剪框
        if (clipType == ClipType.CIRCLE) {
            //中间的透明的圆
            canvas.drawCircle(
                this.width.toFloat() / 2,
                this.height.toFloat() / 2, clipRadiusWidth.toFloat(), paint
            )
            //白色的圆边框
            canvas.drawCircle(
                this.width.toFloat() / 2,
                this.height.toFloat() / 2, clipRadiusWidth.toFloat(), borderPaint
            )
        } else if (clipType == ClipType.RECTANGLE) { //绘制矩形裁剪框
            //绘制中间的矩形
            canvas.drawRect(
                mHorizontalPadding, (this.height / 2 - clipWidth / 2).toFloat(),
                this.width - mHorizontalPadding, (this.height / 2 + clipWidth / 2).toFloat(), paint
            )
            //绘制白色的矩形边框
            canvas.drawRect(
                mHorizontalPadding,
                (this.height / 2 - clipWidth / 2).toFloat(),
                this.width - mHorizontalPadding,
                (this.height / 2 + clipWidth / 2).toFloat(),
                borderPaint
            )
        }
        //出栈，恢复到之前的图层，意味着新建的图层会被删除，新建图层上的内容会被绘制到canvas (or the previous layer)
        canvas.restore()
    }

    val clipRect: Rect
        /**
         * 获取裁剪区域的Rect
         */
        get() {
            val rect = Rect()
            //宽度的一半 - 圆的半径
            rect.left = (this.width / 2 - clipRadiusWidth)
            //宽度的一半 + 圆的半径
            rect.right = (this.width / 2 + clipRadiusWidth)
            //高度的一半 - 圆的半径
            rect.top = (this.height / 2 - clipRadiusWidth)
            //高度的一半 + 圆的半径
            rect.bottom = (this.height / 2 + clipRadiusWidth)
            return rect
        }

    /**
     * 设置裁剪框边框宽度
     */
    fun setClipBorderWidth(clipBorderWidth: Int) {
        this.clipBorderWidth = clipBorderWidth
        borderPaint.strokeWidth = clipBorderWidth.toFloat()
        invalidate()
    }

    /**
     * 设置裁剪框水平间距
     */
    fun setHorizontalPadding(mHorizontalPadding: Float) {
        this.mHorizontalPadding = mHorizontalPadding
        this.clipRadiusWidth =
            (ScreenUtils.getScreenWidth(context) - 2 * mHorizontalPadding).toInt() / 2
        this.clipWidth = clipRadiusWidth * 2
    }

    /**
     * 设置裁剪框类别
     */
    fun setClipType(clipType: ClipType) {
        this.clipType = clipType
    }

    /**
     * 裁剪框类别，圆形、矩形
     */
    enum class ClipType {
        CIRCLE, RECTANGLE
    }
}
