package com.ebook.common.view

import android.annotation.SuppressLint
import android.content.Context
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.util.AttributeSet
import android.util.Log
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.RelativeLayout
import com.ebook.common.R
import com.ebook.common.databinding.ViewSettingBarBinding

@Suppress("unused")
class SettingBarView(context: Context, attrs: AttributeSet?) :
    RelativeLayout(context, attrs) {
    private val imgLeftIcon: ImageView
    private val imgRightIcon: ImageView
    private val txtSetContent: EditText
    private val layoutSettingBar: RelativeLayout
    private var mOnClickSettingBarViewListener: ((v: View) -> Unit)? = null
    private var mOnClickRightImgListener: ((v: View) -> Unit)? = null
    private var isEdit = false //是否需要编辑
    private var mOnViewChangeListener: (() -> Unit)? = null
    private val binding: ViewSettingBarBinding
    init {
        val inflater = LayoutInflater.from(context)
        binding = ViewSettingBarBinding.inflate(inflater, this, true)
        layoutSettingBar = binding.root
        imgLeftIcon = binding.imgLeftIcon
        imgRightIcon = binding.imgRightIcon
        txtSetContent = binding.txtSetContent

        val typedArray = context.obtainStyledAttributes(attrs, R.styleable.SettingBarView)
        val isLeftIconVisible =
            typedArray.getBoolean(R.styleable.SettingBarView_set_left_icon_visible, false)
        val isRightIconVisible =
            typedArray.getBoolean(R.styleable.SettingBarView_set_right_icon_visible, false)
        val title = typedArray.getString(R.styleable.SettingBarView_set_title)
        val hint = typedArray.getString(R.styleable.SettingBarView_set_content_hint)
        val content = typedArray.getString(R.styleable.SettingBarView_set_content)
        val rightIcon = typedArray.getResourceId(R.styleable.SettingBarView_set_right_icon, 0)
        val leftIcon = typedArray.getResourceId(R.styleable.SettingBarView_set_left_icon, 0)
        typedArray.recycle()

        imgLeftIcon.visibility = if (isLeftIconVisible) VISIBLE else GONE
        binding.txtSetTitle.text = title
        txtSetContent.hint = hint
        txtSetContent.setText(content)
        imgRightIcon.visibility = if (isRightIconVisible) VISIBLE else GONE
        if (leftIcon > 0) {
            imgLeftIcon.setImageResource(leftIcon)
        }
        if (rightIcon > 0) {
            imgRightIcon.setImageResource(rightIcon)
        }
        imgRightIcon.setOnClickListener { v ->
            mOnClickRightImgListener?.invoke(v)
        }
        layoutSettingBar.setOnClickListener { v ->
            mOnClickSettingBarViewListener?.invoke(v)
        }
        txtSetContent.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {
            }

            override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {
                Log.v(TAG, "onTextChanged start....")
                mOnViewChangeListener?.invoke()
            }

            override fun afterTextChanged(s: Editable) {
            }
        })
    }

    fun setOnClickRightImgListener(onClickRightImgListener: (v: View) -> Unit) {
        mOnClickRightImgListener = onClickRightImgListener
    }

    fun setOnClickSettingBarViewListener(onClickSettingBarViewListener: (v: View) -> Unit) {
        mOnClickSettingBarViewListener = onClickSettingBarViewListener
    }

    var content: String
        get() {
            return txtSetContent.text.toString()
        }
        set(value) {
            if (!TextUtils.isEmpty(value)) {
                txtSetContent.setText(value)
            }
        }

    private fun setViewChangeListener(listener: () -> Unit) {
        this.mOnViewChangeListener = listener
    }

    fun setEnableEditContext(b: Boolean) {
        isEdit = b
        txtSetContent.isEnabled = b
    }

    fun showImgLeftIcon(show: Boolean) {
        imgLeftIcon.visibility = if (show) VISIBLE else GONE
    }

    fun showImgRightIcon(show: Boolean) {
        imgRightIcon.visibility = if (show) VISIBLE else GONE
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        return !isEdit
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        return layoutSettingBar.onTouchEvent(event)
    }

    companion object {
        private val TAG: String = SettingBarView::class.java.simpleName
    }
}
