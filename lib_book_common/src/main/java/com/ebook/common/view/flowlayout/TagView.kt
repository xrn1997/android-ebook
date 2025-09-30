package com.ebook.common.view.flowlayout

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.Checkable
import android.widget.FrameLayout
import androidx.core.view.isNotEmpty

class TagView : FrameLayout, Checkable {
    constructor(context: Context) : super(context)
    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)
    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(context, attrs, defStyleAttr)

    private var isChecked: Boolean = false

    fun getTagView(): View? {
        return if (isNotEmpty()) getChildAt(0) else null
    }

    override fun onCreateDrawableState(extraSpace: Int): IntArray {
        val states = super.onCreateDrawableState(extraSpace + 1)
        if (isChecked) {
            mergeDrawableStates(states, CHECK_STATE)
        }
        return states
    }

    /**
     * @return The current checked state of the view
     */
    override fun isChecked(): Boolean = isChecked

    /**
     * Change the checked state of the view
     *
     * @param checked The new checked state
     */
    override fun setChecked(checked: Boolean) {
        if (this.isChecked != checked) {
            this.isChecked = checked
            refreshDrawableState()
        }
    }

    /**
     * Change the checked state of the view to the inverse of its current state
     */
    override fun toggle() {
        isChecked = !isChecked
        refreshDrawableState()
    }

    companion object {
        private val CHECK_STATE = intArrayOf(android.R.attr.state_checked)
    }
}