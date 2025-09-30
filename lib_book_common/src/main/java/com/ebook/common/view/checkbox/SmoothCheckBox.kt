package com.ebook.common.view.checkbox

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.TypedArray
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Point
import android.os.Bundle
import android.os.Parcelable
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.Checkable
import com.ebook.common.R
import com.xrn1997.common.util.DisplayUtil
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

class SmoothCheckBox : View, Checkable {
    companion object {
        private const val KEY_INSTANCE_STATE = "InstanceState"
        private const val COLOR_TICK = Color.WHITE
        private const val COLOR_UNCHECKED = Color.WHITE
        private const val COLOR_CHECKED = 0xFFFB4846.toInt()
        private const val COLOR_FLOOR_UNCHECKED = 0xFFDFDFDF.toInt()
        private const val DEF_DRAW_SIZE = 25
        private const val DEF_ANIM_DURATION = 300

        private fun getGradientColor(startColor: Int, endColor: Int, percent: Float): Int {
            val startA = Color.alpha(startColor)
            val startR = Color.red(startColor)
            val startG = Color.green(startColor)
            val startB = Color.blue(startColor)

            val endA = Color.alpha(endColor)
            val endR = Color.red(endColor)
            val endG = Color.green(endColor)
            val endB = Color.blue(endColor)

            val currentA = (startA * (1 - percent) + endA * percent).toInt()
            val currentR = (startR * (1 - percent) + endR * percent).toInt()
            val currentG = (startG * (1 - percent) + endG * percent).toInt()
            val currentB = (startB * (1 - percent) + endB * percent).toInt()

            return Color.argb(currentA, currentR, currentG, currentB)
        }
    }

    private lateinit var mPaint: Paint
    private lateinit var mTickPaint: Paint
    private lateinit var mFloorPaint: Paint
    private lateinit var mTickPoints: Array<Point>
    private lateinit var mCenterPoint: Point
    private lateinit var mTickPath: Path

    private var mLeftLineDistance = 0f
    private var mRightLineDistance = 0f
    private var mDrewDistance = 0f
    private var mScaleVal = 1.0f
    private var mFloorScale = 1.0f
    private var mWidth = 0
    private var mAnimDuration = DEF_ANIM_DURATION
    private var mStrokeWidth = 0
    private var mCheckedColor = COLOR_CHECKED
    private var mUnCheckedColor = COLOR_UNCHECKED
    private var mFloorColor = COLOR_FLOOR_UNCHECKED
    private var mFloorUnCheckedColor = COLOR_FLOOR_UNCHECKED

    private var mChecked = false
    private var mTickDrawing = false
    private var mListener: OnCheckedChangeListener? = null

    constructor(context: Context) : super(context) {
        init(null)
    }

    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs) {
        init(attrs)
    }

    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(
        context,
        attrs,
        defStyleAttr
    ) {
        init(attrs)
    }

    constructor(
        context: Context,
        attrs: AttributeSet?,
        defStyleAttr: Int,
        defStyleRes: Int
    ) : super(context, attrs, defStyleAttr, defStyleRes) {
        init(attrs)
    }

    private fun init(attrs: AttributeSet?) {
        val ta: TypedArray = context.obtainStyledAttributes(attrs, R.styleable.SmoothCheckBox)
        val tickColor = ta.getColor(R.styleable.SmoothCheckBox_color_tick, COLOR_TICK)
        mAnimDuration = ta.getInt(R.styleable.SmoothCheckBox_duration, DEF_ANIM_DURATION)
        mFloorColor =
            ta.getColor(R.styleable.SmoothCheckBox_color_unchecked_stroke, COLOR_FLOOR_UNCHECKED)
        mCheckedColor = ta.getColor(R.styleable.SmoothCheckBox_color_checked, COLOR_CHECKED)
        mUnCheckedColor = ta.getColor(R.styleable.SmoothCheckBox_color_unchecked, COLOR_UNCHECKED)
        mStrokeWidth = ta.getDimensionPixelSize(
            R.styleable.SmoothCheckBox_stroke_width,
            DisplayUtil.dip2px(0f).toInt()
        )
        ta.recycle()

        mFloorUnCheckedColor = mFloorColor

        mTickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            color = tickColor
        }

        mFloorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = mFloorColor
        }

        mPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = mCheckedColor
        }

        mTickPath = Path()
        mCenterPoint = Point()
        mTickPoints = arrayOf(Point(), Point(), Point())

        setOnClickListener {
            toggle()
            mTickDrawing = false
            mDrewDistance = 0f
            if (isChecked) {
                startCheckedAnimation()
            } else {
                startUnCheckedAnimation()
            }
        }
    }

    override fun onSaveInstanceState(): Parcelable {
        val bundle = Bundle()
        bundle.putParcelable(KEY_INSTANCE_STATE, super.onSaveInstanceState())
        bundle.putBoolean(KEY_INSTANCE_STATE, isChecked)
        return bundle
    }

    override fun onRestoreInstanceState(state: Parcelable?) {
        if (state is Bundle) {
            val isChecked = state.getBoolean(KEY_INSTANCE_STATE)
            setChecked(isChecked)

            // 兼容所有版本的获取 Parcelable 方法
            val superState = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                state.getParcelable(KEY_INSTANCE_STATE, Parcelable::class.java)
            } else {
                @Suppress("DEPRECATION")
                state.getParcelable(KEY_INSTANCE_STATE)
            }

            super.onRestoreInstanceState(superState)
            return
        }
        super.onRestoreInstanceState(state)
    }

    override fun isChecked(): Boolean = mChecked

    override fun setChecked(checked: Boolean) {
        mChecked = checked
        reset()
        invalidate()
        mListener?.onCheckedChanged(this, mChecked)
    }

    override fun toggle() {
        setChecked(!isChecked)
    }

    /**
     * checked with animation
     *
     * @param checked checked
     * @param animate change with animation
     */
    fun setChecked(checked: Boolean, animate: Boolean) {
        if (animate) {
            mTickDrawing = false
            mChecked = checked
            mDrewDistance = 0f
            if (checked) {
                startCheckedAnimation()
            } else {
                startUnCheckedAnimation()
            }
            mListener?.onCheckedChanged(this, mChecked)
        } else {
            setChecked(checked)
        }
    }

    private fun reset() {
        mTickDrawing = true
        mFloorScale = 1.0f
        mScaleVal = if (isChecked) 0f else 1.0f
        mFloorColor = if (isChecked) mCheckedColor else mFloorUnCheckedColor
        mDrewDistance = if (isChecked) (mLeftLineDistance + mRightLineDistance) else 0f
    }

    private fun measureSize(measureSpec: Int): Int {
        val defSize = DisplayUtil.dip2px(DEF_DRAW_SIZE.toFloat()).toInt()
        val specSize = MeasureSpec.getSize(measureSpec)
        val specMode = MeasureSpec.getMode(measureSpec)

        return when (specMode) {
            MeasureSpec.UNSPECIFIED, MeasureSpec.AT_MOST -> minOf(defSize, specSize)
            MeasureSpec.EXACTLY -> specSize
            else -> 0
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        setMeasuredDimension(measureSize(widthMeasureSpec), measureSize(heightMeasureSpec))
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        mWidth = measuredWidth
        mStrokeWidth = if (mStrokeWidth == 0) measuredWidth / 10 else mStrokeWidth
        mStrokeWidth = minOf(mStrokeWidth, measuredWidth / 5)
        mStrokeWidth = maxOf(mStrokeWidth, 3)

        mCenterPoint.x = mWidth / 2
        mCenterPoint.y = measuredHeight / 2

        mTickPoints[0].x = (measuredWidth / 30f * 7).roundToInt()
        mTickPoints[0].y = (measuredHeight / 30f * 14).roundToInt()
        mTickPoints[1].x = (measuredWidth / 30f * 13).roundToInt()
        mTickPoints[1].y = (measuredHeight / 30f * 20).roundToInt()
        mTickPoints[2].x = (measuredWidth / 30f * 22).roundToInt()
        mTickPoints[2].y = (measuredHeight / 30f * 10).roundToInt()

        mLeftLineDistance = sqrt(
            (mTickPoints[1].x - mTickPoints[0].x).toDouble().pow(2.0) +
                    (mTickPoints[1].y - mTickPoints[0].y).toDouble().pow(2.0)
        ).toFloat()

        mRightLineDistance = sqrt(
            (mTickPoints[2].x - mTickPoints[1].x).toDouble().pow(2.0) +
                    (mTickPoints[2].y - mTickPoints[1].y).toDouble().pow(2.0)
        ).toFloat()

        mTickPaint.strokeWidth = mStrokeWidth.toFloat()
    }

    override fun onDraw(canvas: Canvas) {
        drawBorder(canvas)
        drawCenter(canvas)
        drawTick(canvas)
    }

    private fun drawCenter(canvas: Canvas) {
        mPaint.color = mUnCheckedColor
        val radius = (mCenterPoint.x - mStrokeWidth) * mScaleVal
        canvas.drawCircle(mCenterPoint.x.toFloat(), mCenterPoint.y.toFloat(), radius, mPaint)
    }

    private fun drawBorder(canvas: Canvas) {
        mFloorPaint.color = mFloorColor
        val radius = mCenterPoint.x
        canvas.drawCircle(
            mCenterPoint.x.toFloat(),
            mCenterPoint.y.toFloat(),
            radius * mFloorScale,
            mFloorPaint
        )
    }

    private fun drawTick(canvas: Canvas) {
        if (mTickDrawing && isChecked) {
            drawTickPath(canvas)
        }
    }

    private fun drawTickPath(canvas: Canvas) {
        mTickPath.reset()

        // draw left of the tick
        if (mDrewDistance < mLeftLineDistance) {
            val step = if (mWidth / 20.0f < 3) 3f else mWidth / 20.0f
            mDrewDistance += step

            val stopX =
                mTickPoints[0].x + (mTickPoints[1].x - mTickPoints[0].x) * mDrewDistance / mLeftLineDistance
            val stopY =
                mTickPoints[0].y + (mTickPoints[1].y - mTickPoints[0].y) * mDrewDistance / mLeftLineDistance

            mTickPath.moveTo(mTickPoints[0].x.toFloat(), mTickPoints[0].y.toFloat())
            mTickPath.lineTo(stopX, stopY)
            canvas.drawPath(mTickPath, mTickPaint)

            if (mDrewDistance > mLeftLineDistance) {
                mDrewDistance = mLeftLineDistance
            }
        } else {
            mTickPath.moveTo(mTickPoints[0].x.toFloat(), mTickPoints[0].y.toFloat())
            mTickPath.lineTo(mTickPoints[1].x.toFloat(), mTickPoints[1].y.toFloat())
            canvas.drawPath(mTickPath, mTickPaint)

            // draw right of the tick
            if (mDrewDistance < mLeftLineDistance + mRightLineDistance) {
                val stopX = mTickPoints[1].x + (mTickPoints[2].x - mTickPoints[1].x) *
                        (mDrewDistance - mLeftLineDistance) / mRightLineDistance
                val stopY = mTickPoints[1].y - (mTickPoints[1].y - mTickPoints[2].y) *
                        (mDrewDistance - mLeftLineDistance) / mRightLineDistance

                mTickPath.reset()
                mTickPath.moveTo(mTickPoints[1].x.toFloat(), mTickPoints[1].y.toFloat())
                mTickPath.lineTo(stopX, stopY)
                canvas.drawPath(mTickPath, mTickPaint)

                val step = maxOf(mWidth / 20, 3)
                mDrewDistance += step.toFloat()
            } else {
                mTickPath.reset()
                mTickPath.moveTo(mTickPoints[1].x.toFloat(), mTickPoints[1].y.toFloat())
                mTickPath.lineTo(mTickPoints[2].x.toFloat(), mTickPoints[2].y.toFloat())
                canvas.drawPath(mTickPath, mTickPaint)
            }
        }

        // invalidate
        if (mDrewDistance < mLeftLineDistance + mRightLineDistance) {
            postDelayed({ postInvalidate() }, 10)
        }
    }

    private fun startCheckedAnimation() {
        ValueAnimator.ofFloat(1.0f, 0f).apply {
            duration = (mAnimDuration / 3 * 2).toLong()
            interpolator = LinearInterpolator()
            addUpdateListener { animation ->
                mScaleVal = animation.animatedValue as Float
                mFloorColor = getGradientColor(mUnCheckedColor, mCheckedColor, 1 - mScaleVal)
                postInvalidate()
            }
            start()
        }

        ValueAnimator.ofFloat(1.0f, 0.8f, 1.0f).apply {
            duration = mAnimDuration.toLong()
            interpolator = LinearInterpolator()
            addUpdateListener { animation ->
                mFloorScale = animation.animatedValue as Float
                postInvalidate()
            }
            start()
        }

        drawTickDelayed()
    }

    private fun startUnCheckedAnimation() {
        ValueAnimator.ofFloat(0f, 1.0f).apply {
            duration = mAnimDuration.toLong()
            interpolator = LinearInterpolator()
            addUpdateListener { animation ->
                mScaleVal = animation.animatedValue as Float
                mFloorColor = getGradientColor(mCheckedColor, mFloorUnCheckedColor, mScaleVal)
                postInvalidate()
            }
            start()
        }

        ValueAnimator.ofFloat(1.0f, 0.8f, 1.0f).apply {
            duration = mAnimDuration.toLong()
            interpolator = LinearInterpolator()
            addUpdateListener { animation ->
                mFloorScale = animation.animatedValue as Float
                postInvalidate()
            }
            start()
        }
    }

    private fun drawTickDelayed() {
        postDelayed({
            mTickDrawing = true
            postInvalidate()
        }, mAnimDuration.toLong())
    }

    fun setOnCheckedChangeListener(l: OnCheckedChangeListener) {
        this.mListener = l
    }

    fun interface OnCheckedChangeListener {
        fun onCheckedChanged(checkBox: SmoothCheckBox, isChecked: Boolean)
    }
}