package com.ebook.common.view.popupwindow

import android.content.Context
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.PopupWindow
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.ebook.common.R
import com.ebook.common.view.ReadBookControl
import com.ebook.common.view.profilePhoto.CircleImageView

class FontPop(
    mContext: Context,
    private val changeProListener: OnChangeProListener
) : PopupWindow(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT) {

    private val view: View = LayoutInflater.from(mContext).inflate(R.layout.view_pop_font, null)
    private lateinit var flSmaller: FrameLayout
    private lateinit var flBigger: FrameLayout
    private lateinit var tvTextSizedDefault: TextView
    private lateinit var tvTextSize: TextView
    private lateinit var civBgWhite: CircleImageView
    private lateinit var civBgYellow: CircleImageView
    private lateinit var civBgGreen: CircleImageView
    private lateinit var civBgBlack: CircleImageView

    init {
        contentView = view
        bindView()
        bindEvent()

        setBackgroundDrawable(
            ContextCompat.getDrawable(mContext, R.drawable.shape_pop_checkaddshelf_bg)
        )
        isFocusable = true
        isTouchable = true
        animationStyle = R.style.anim_pop_windowlight
    }

    private fun bindEvent() {
        flSmaller.setOnClickListener {
            updateText(ReadBookControl.textKindIndex - 1)
            changeProListener.textChange(ReadBookControl.textKindIndex)
        }
        flBigger.setOnClickListener {
            updateText(ReadBookControl.textKindIndex + 1)
            changeProListener.textChange(ReadBookControl.textKindIndex)
        }
        tvTextSizedDefault.setOnClickListener {
            updateText(ReadBookControl.DEFAULT_TEXT)
            changeProListener.textChange(ReadBookControl.textKindIndex)
        }

        civBgWhite.setOnClickListener {
            updateBg(0)
            changeProListener.bgChange(ReadBookControl.textDrawableIndex)
        }
        civBgYellow.setOnClickListener {
            updateBg(1)
            changeProListener.bgChange(ReadBookControl.textDrawableIndex)
        }
        civBgGreen.setOnClickListener {
            updateBg(2)
            changeProListener.bgChange(ReadBookControl.textDrawableIndex)
        }
        civBgBlack.setOnClickListener {
            updateBg(3)
            changeProListener.bgChange(ReadBookControl.textDrawableIndex)
        }
    }

    private fun bindView() {
        flSmaller = view.findViewById(R.id.fl_smaller)
        flBigger = view.findViewById(R.id.fl_bigger)
        tvTextSizedDefault = view.findViewById(R.id.tv_textsize_default)
        tvTextSize = view.findViewById(R.id.tv_dur_textsize)
        updateText(ReadBookControl.textKindIndex)

        civBgWhite = view.findViewById(R.id.civ_bg_white)
        civBgYellow = view.findViewById(R.id.civ_bg_yellow)
        civBgGreen = view.findViewById(R.id.civ_bg_green)
        civBgBlack = view.findViewById(R.id.civ_bg_black)
        updateBg(ReadBookControl.textDrawableIndex)
    }

    private fun updateText(textKindIndex: Int) {
        when (textKindIndex) {
            0 -> {
                flSmaller.isEnabled = false
                flBigger.isEnabled = true
            }

            ReadBookControl.getTextKindList().size - 1 -> {
                flSmaller.isEnabled = true
                flBigger.isEnabled = false
            }

            else -> {
                flSmaller.isEnabled = true
                flBigger.isEnabled = true
            }
        }
        tvTextSizedDefault.isEnabled = textKindIndex != ReadBookControl.DEFAULT_TEXT
        tvTextSize.text = ReadBookControl.getTextKindList()[textKindIndex].textSize.toString()
        ReadBookControl.updateTextKindIndex(textKindIndex)
    }

    private fun updateBg(index: Int) {
        val transparent = Color.parseColor("#00000000")
        civBgWhite.borderColor = transparent
        civBgYellow.borderColor = transparent
        civBgGreen.borderColor = transparent
        civBgBlack.borderColor = transparent

        val highlight = Color.parseColor("#F3B63F")
        when (index) {
            0 -> civBgWhite.borderColor = highlight
            1 -> civBgYellow.borderColor = highlight
            2 -> civBgGreen.borderColor = highlight
            else -> civBgBlack.borderColor = highlight
        }
        ReadBookControl.updateTextDrawableIndex(index)
    }


    interface OnChangeProListener {
        fun textChange(index: Int)
        fun bgChange(index: Int)
    }
}
