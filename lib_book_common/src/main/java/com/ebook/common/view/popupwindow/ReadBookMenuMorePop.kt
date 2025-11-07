package com.ebook.common.view.popupwindow

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.PopupWindow
import androidx.core.content.ContextCompat
import com.ebook.common.R

/**
 * ReadBookMenuMorePop：阅读页“更多”菜单弹窗
 *
 * 功能：
 * 1. 提供下载和评论操作入口
 * 2. 可设置点击事件
 * 3. 自带背景和动画
 */
class ReadBookMenuMorePop(context: Context) :
    PopupWindow(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT) {

    /** 弹窗内容布局 */
    private val view: View = LayoutInflater.from(context).inflate(R.layout.view_pop_menumore, null)

    /** 下载按钮容器 */
    private lateinit var llDownload: LinearLayout

    /** 评论按钮容器 */
    private lateinit var llComment: LinearLayout

    init {
        // 设置布局
        contentView = view

        // 初始化子控件
        initView()

        // 设置背景、焦点、触摸和动画
        setBackgroundDrawable(ContextCompat.getDrawable(context, R.drawable.shape_pop_checkaddshelf_bg))
        isFocusable = true
        isTouchable = true
        animationStyle = R.style.anim_pop_windowmenumore
    }

    /** 初始化控件引用 */
    private fun initView() {
        llDownload = view.findViewById(R.id.ll_download)
        llComment = view.findViewById(R.id.ll_comment)
    }

    /**
     * 设置下载点击事件
     * @param clickDownload 点击监听器
     */
    fun setOnClickDownload(clickDownload: View.OnClickListener?) {
        llDownload.setOnClickListener(clickDownload)
    }

    /**
     * 设置评论点击事件
     * @param clickComment 点击监听器
     */
    fun setOnClickComment(clickComment: View.OnClickListener?) {
        llComment.setOnClickListener(clickComment)
    }
}
