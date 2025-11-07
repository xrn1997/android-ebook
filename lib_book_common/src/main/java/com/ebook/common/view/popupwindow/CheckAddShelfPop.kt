package com.ebook.common.view.popupwindow

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupWindow
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.ebook.common.R

/**
 * CheckAddShelfPop：添加书架确认弹窗
 *
 * 功能：
 * 1. 显示书名提示用户是否加入书架
 * 2. 提供退出和加入书架操作
 * 3. 可自定义点击事件回调
 */
class CheckAddShelfPop(
    private val mContext: Context,
    private val bookName: String,
    private val itemClick: OnItemClickListener
) : PopupWindow(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT) {

    /** 弹窗布局 */
    private val view: View =
        LayoutInflater.from(mContext).inflate(R.layout.view_pop_checkaddshelf, null)

    init {
        contentView = view

        initView()

        // 设置背景、焦点、可触摸及动画
        setBackgroundDrawable(ContextCompat.getDrawable(mContext, R.drawable.shape_pop_checkaddshelf_bg))
        isFocusable = true
        isTouchable = true
        animationStyle = R.style.anim_pop_checkaddshelf
    }

    /** 初始化控件及点击事件 */
    private fun initView() {
        // 书名文本
        val tvBookName = view.findViewById<TextView>(R.id.tv_book_name)
        tvBookName.text = mContext.getString(R.string.tv_pop_checkaddshelf, bookName)

        // 退出按钮
        val tvExit = view.findViewById<TextView>(R.id.tv_exit)
        tvExit.setOnClickListener {
            dismiss()
            itemClick.clickExit()
        }

        // 加入书架按钮
        val tvAddShelf = view.findViewById<TextView>(R.id.tv_addshelf)
        tvAddShelf.setOnClickListener {
            itemClick.clickAddShelf()
        }
    }

    /** 点击事件回调接口 */
    interface OnItemClickListener {
        /** 点击退出 */
        fun clickExit()

        /** 点击加入书架 */
        fun clickAddShelf()
    }
}
