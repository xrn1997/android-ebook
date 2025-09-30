package com.ebook.common.view.flowlayout

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import androidx.core.content.withStyledAttributes
import androidx.core.view.isGone
import com.ebook.common.R

open class FlowLayout : ViewGroup {
    companion object {
        private const val TAG = "FlowLayout"
        private const val LEFT = -1
        private const val CENTER = 0
        private const val RIGHT = 1
    }

    private var mGravity: Int = LEFT
    protected val mAllViews = mutableListOf<MutableList<View>>()
    protected val mLineHeight = mutableListOf<Int>()
    protected val mLineWidth = mutableListOf<Int>()
    private var lineViews = mutableListOf<View>()

    constructor(context: Context, attrs: AttributeSet?, defStyle: Int) : super(
        context,
        attrs,
        defStyle
    ) {
        context.withStyledAttributes(attrs, R.styleable.TagFlowLayout) {
            mGravity = getInt(R.styleable.TagFlowLayout_gravity, LEFT)
        }
    }

    constructor(context: Context, attrs: AttributeSet?) : this(context, attrs, 0)
    constructor(context: Context) : this(context, null)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val sizeWidth = MeasureSpec.getSize(widthMeasureSpec)
        val modeWidth = MeasureSpec.getMode(widthMeasureSpec)
        val sizeHeight = MeasureSpec.getSize(heightMeasureSpec)
        val modeHeight = MeasureSpec.getMode(heightMeasureSpec)

        // wrap_content
        var width = 0
        var height = 0

        var lineWidth = 0
        var lineHeight = 0

        val cCount = childCount

        for (i in 0 until cCount) {
            val child = getChildAt(i)
            if (child.isGone) {
                if (i == cCount - 1) {
                    width = maxOf(lineWidth, width)
                    height += lineHeight
                }
                continue
            }
            measureChild(child, widthMeasureSpec, heightMeasureSpec)
            val lp = child.layoutParams as MarginLayoutParams

            val childWidth = child.measuredWidth + lp.leftMargin + lp.rightMargin
            val childHeight = child.measuredHeight + lp.topMargin + lp.bottomMargin

            if (lineWidth + childWidth > sizeWidth - paddingLeft - paddingRight) {
                width = maxOf(width, lineWidth)
                lineWidth = childWidth
                height += lineHeight
                lineHeight = childHeight
            } else {
                lineWidth += childWidth
                lineHeight = maxOf(lineHeight, childHeight)
            }
            if (i == cCount - 1) {
                width = maxOf(lineWidth, width)
                height += lineHeight
            }
        }
        setMeasuredDimension(
            if (modeWidth == MeasureSpec.EXACTLY) sizeWidth else width + paddingLeft + paddingRight,
            if (modeHeight == MeasureSpec.EXACTLY) sizeHeight else height + paddingTop + paddingBottom
        )
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        mAllViews.clear()
        mLineHeight.clear()
        mLineWidth.clear()
        lineViews.clear()

        val width = r - l

        var lineWidth = 0
        var lineHeight = 0

        val cCount = childCount

        for (i in 0 until cCount) {
            val child = getChildAt(i)
            if (child.isGone) continue
            val lp = child.layoutParams as MarginLayoutParams

            val childWidth = child.measuredWidth
            val childHeight = child.measuredHeight

            if (childWidth + lineWidth + lp.leftMargin + lp.rightMargin > width - paddingLeft - paddingRight) {
                mLineHeight.add(lineHeight)
                mAllViews.add(lineViews)
                mLineWidth.add(lineWidth)

                lineWidth = 0
                lineHeight = childHeight + lp.topMargin + lp.bottomMargin
                lineViews = mutableListOf()
            }
            lineWidth += childWidth + lp.leftMargin + lp.rightMargin
            lineHeight = maxOf(lineHeight, childHeight + lp.topMargin + lp.bottomMargin)
            lineViews.add(child)
        }
        mLineHeight.add(lineHeight)
        mLineWidth.add(lineWidth)
        mAllViews.add(lineViews)

        var left = paddingLeft
        var top = paddingTop

        val lineNum = mAllViews.size

        for (i in 0 until lineNum) {
            val currentLineViews = mAllViews[i]
            val currentLineHeight = mLineHeight[i]

            // set gravity
            val currentLineWidth = mLineWidth[i]
            left = when (mGravity) {
                LEFT -> paddingLeft
                CENTER -> (width - currentLineWidth) / 2 + paddingLeft
                RIGHT -> width - currentLineWidth + paddingLeft
                else -> left
            }

            for (j in currentLineViews.indices) {
                val child = currentLineViews[j]
                if (child.isGone) {
                    continue
                }

                val lp = child.layoutParams as MarginLayoutParams

                val lc = left + lp.leftMargin
                val tc = top + lp.topMargin
                val rc = lc + child.measuredWidth
                val bc = tc + child.measuredHeight

                child.layout(lc, tc, rc, bc)

                left += child.measuredWidth + lp.leftMargin + lp.rightMargin
            }
            top += currentLineHeight
        }
    }

    override fun generateLayoutParams(attrs: AttributeSet): LayoutParams {
        return MarginLayoutParams(context, attrs)
    }

    override fun generateDefaultLayoutParams(): LayoutParams {
        return MarginLayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
    }

    override fun generateLayoutParams(p: LayoutParams): LayoutParams {
        return MarginLayoutParams(p)
    }
}