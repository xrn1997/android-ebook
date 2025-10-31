package com.ebook.common.view.popupwindow

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupWindow
import androidx.core.content.ContextCompat
import com.ebook.common.R
import com.ebook.common.view.ReadBookControl
import com.kyleduo.switchbutton.SwitchButton

class MoreSettingPop(
    context: Context
) : PopupWindow(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT) {
    private val mContext: Context = context
    private val view: View =
        LayoutInflater.from(mContext).inflate(R.layout.view_pop_moresetting, null)

    private lateinit var sbKey: SwitchButton
    private lateinit var sbClick: SwitchButton


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
        sbKey.setOnCheckedChangeListener { _, isChecked ->
            ReadBookControl.setCanKeyTurn(isChecked)
        }
        sbClick.setOnCheckedChangeListener { _, isChecked ->
            ReadBookControl.setCanClickTurn(isChecked)
        }
    }

    private fun bindView() {
        sbKey = view.findViewById(R.id.sb_key)
        sbClick = view.findViewById(R.id.sb_click)

        sbKey.setCheckedImmediatelyNoEvent(ReadBookControl.canKeyTurn)
        sbClick.setCheckedImmediatelyNoEvent(ReadBookControl.canClickTurn)
    }

}
