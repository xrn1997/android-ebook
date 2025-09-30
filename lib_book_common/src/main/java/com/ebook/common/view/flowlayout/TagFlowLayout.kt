package com.ebook.common.view.flowlayout

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.os.Bundle
import android.os.Parcelable
import android.util.AttributeSet
import android.util.Log
import android.view.MotionEvent
import android.view.View
import androidx.core.content.withStyledAttributes
import androidx.core.view.isGone
import com.ebook.common.R
import java.util.*

class TagFlowLayout : FlowLayout, TagAdapter.OnDataChangedListener {
    private var mTagAdapter: TagAdapter<*>? = null
    private var mAutoSelectEffect: Boolean = true
    private val mSelectedView: MutableSet<Int> = HashSet()
    private var mMotionEvent: MotionEvent? = null
    private var mSelectedMax = 0//-1为不限制数量

    private var mOnSelectListener: OnSelectListener? = null
    private var mOnTagClickListener: OnTagClickListener? = null

    constructor(context: Context, attrs: AttributeSet?, defStyle: Int) : super(
        context,
        attrs,
        defStyle
    ) {
        context.withStyledAttributes(attrs, R.styleable.TagFlowLayout) {
            mAutoSelectEffect = getBoolean(R.styleable.TagFlowLayout_auto_select_effect, true)
            mSelectedMax = getInt(R.styleable.TagFlowLayout_max_select, -1)
        }
        if (mAutoSelectEffect) {
            isClickable = true
        }
    }

    constructor(context: Context, attrs: AttributeSet?) : this(context, attrs, 0)
    constructor(context: Context) : this(context, null)

    companion object {
        private const val TAG = "TagFlowLayout"
        private const val KEY_CHOOSE_POS = "key_choose_pos"
        private const val KEY_DEFAULT = "key_default"

        fun dip2px(context: Context, dpValue: Float): Int {
            val scale = context.resources.displayMetrics.density
            return (dpValue * scale + 0.5f).toInt()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val cCount = childCount
        for (i in 0 until cCount) {
            val tagView = getChildAt(i) as TagView
            if (tagView.isGone) continue
            if (tagView.getTagView()?.visibility == GONE) {
                tagView.visibility = GONE
            }
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    fun setOnSelectListener(onSelectListener: OnSelectListener?) {
        mOnSelectListener = onSelectListener
        if (mOnSelectListener != null) isClickable = true
    }

    fun setOnTagClickListener(onTagClickListener: OnTagClickListener?) {
        mOnTagClickListener = onTagClickListener
        if (onTagClickListener != null) isClickable = true
    }
    @Suppress("UNCHECKED_CAST")
    private fun changeAdapter() {
        removeAllViews()
        val adapter = mTagAdapter as? TagAdapter<Any> ?: return
        val preCheckedList = adapter.preCheckedList
        for (i in 0 until adapter.count) {
            val tagView = adapter.getView(this, i, adapter.getItem(i))
            val tagViewContainer = TagView(context)
            tagView.isDuplicateParentStateEnabled = true
            if (tagView.layoutParams != null) {
                tagViewContainer.layoutParams = tagView.layoutParams
            } else {
                val lp = MarginLayoutParams(
                    LayoutParams.WRAP_CONTENT,
                    LayoutParams.WRAP_CONTENT
                )
                val margin = dip2px(context, 5f)
                lp.setMargins(margin, margin, margin, margin)
                tagViewContainer.layoutParams = lp
            }
            tagViewContainer.addView(tagView)
            addView(tagViewContainer)
            if (preCheckedList.contains(i)) {
                tagViewContainer.isChecked = true
            }
        }
        mSelectedView.addAll(preCheckedList)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP) {
            mMotionEvent = MotionEvent.obtain(event)
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        if (mMotionEvent == null) return super.performClick()
        val x = mMotionEvent!!.x.toInt()
        val y = mMotionEvent!!.y.toInt()
        mMotionEvent = null
        val child = findChild(x, y)
        val pos = findPosByView(child)
        if (child != null) {
            doSelect(child, pos)
            if (mOnTagClickListener != null) {
                return mOnTagClickListener!!.onTagClick(child.getTagView(), pos, this)
            }
        }
        return true
    }

    fun setMaxSelectCount(count: Int) {
        if (mSelectedView.size > count) {
            Log.w(TAG, "you has already select more than $count views , so it will be clean .")
            mSelectedView.clear()
        }
        mSelectedMax = count
    }

    val selectedList: Set<Int>
        get() = HashSet(mSelectedView)

    private fun doSelect(child: TagView, position: Int) {
        if (mAutoSelectEffect) {
            if (!child.isChecked) {
                //处理max_select=1的情况
                if (mSelectedMax == 1 && mSelectedView.size == 1) {
                    val iterator: Iterator<Int> = mSelectedView.iterator()
                    val preIndex = iterator.next()
                    val pre = getChildAt(preIndex) as TagView
                    pre.isChecked = false
                    child.isChecked = true
                    mSelectedView.remove(preIndex)
                    mSelectedView.add(position)
                } else {
                    if (mSelectedMax > 0 && mSelectedView.size >= mSelectedMax) return
                    child.isChecked = true
                    mSelectedView.add(position)
                }
            } else {
                child.isChecked = false
                mSelectedView.remove(position)
            }
            mOnSelectListener?.onSelected(HashSet(mSelectedView))
        }
    }

    fun getAdapter(): TagAdapter<*>? {
        return mTagAdapter
    }

    fun setAdapter(adapter: TagAdapter<*>) {
        mTagAdapter = adapter
        mTagAdapter?.setOnDataChangedListener(this)
        mSelectedView.clear()
        changeAdapter()
    }

    override fun onSaveInstanceState(): Parcelable {
        val bundle = Bundle()
        bundle.putParcelable(KEY_DEFAULT, super.onSaveInstanceState())
        val selectPos = StringBuilder()
        if (mSelectedView.isNotEmpty()) {
            for (key in mSelectedView) {
                selectPos.append(key).append("|")
            }
            if (selectPos.isNotEmpty()) {
                selectPos.deleteCharAt(selectPos.length - 1)
            }
        }
        bundle.putString(KEY_CHOOSE_POS, selectPos.toString())
        return bundle
    }

    override fun onRestoreInstanceState(state: Parcelable?) {
        if (state is Bundle) {
            val mSelectPos = state.getString(KEY_CHOOSE_POS)
            if (mSelectPos != null && mSelectPos.isNotEmpty()) {
                val split = mSelectPos.split("\\|".toRegex()).toTypedArray()
                for (pos in split) {
                    val index = pos.toInt()
                    mSelectedView.add(index)
                    val tagView = getChildAt(index) as? TagView
                    tagView?.isChecked = true
                }
            }
            // 兼容所有版本的获取 Parcelable 方法
            val superState = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                state.getParcelable(KEY_DEFAULT, Parcelable::class.java)
            } else {
                @Suppress("DEPRECATION")
                state.getParcelable(KEY_DEFAULT)
            }
            super.onRestoreInstanceState(superState)
            return
        }
        super.onRestoreInstanceState(state)
    }

    private fun findPosByView(child: View?): Int {
        val cCount = childCount
        for (i in 0 until cCount) {
            val v = getChildAt(i)
            if (v === child) return i
        }
        return -1
    }

    private fun findChild(x: Int, y: Int): TagView? {
        val cCount = childCount
        for (i in 0 until cCount) {
            val v = getChildAt(i) as TagView
            if (v.isGone) continue
            val outRect = Rect()
            v.getHitRect(outRect)
            if (outRect.contains(x, y)) {
                return v
            }
        }
        return null
    }

    override fun onChanged() {
        mSelectedView.clear()
        changeAdapter()
    }

    fun interface OnSelectListener {
        fun onSelected(selectPosSet: Set<Int>)
    }

    fun interface OnTagClickListener {
        fun onTagClick(view: View?, position: Int, parent: FlowLayout): Boolean
    }
}