package com.ebook.common.view.modialog

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View.OnClickListener
import android.view.ViewGroup
import android.view.animation.Animation
import android.view.animation.AnimationUtils
import android.widget.FrameLayout
import com.ebook.common.R
import androidx.core.graphics.toColorInt

class MoProgressHUD(private val context: Context) {
    private var isFinishing = false
    private lateinit var decorView: ViewGroup // activity的根View
    private lateinit var rootView: ViewGroup // mSharedView 的 根View
    private lateinit var mSharedView: MoProgressView

    private val outAnimListener = object : Animation.AnimationListener {
        override fun onAnimationStart(animation: Animation) {
            isFinishing = true
        }

        override fun onAnimationEnd(animation: Animation) {
            dismissImmediately()
        }

        override fun onAnimationRepeat(animation: Animation) {
            // 不需要实现
        }
    }

    private lateinit var inAnim: Animation
    private lateinit var outAnim: Animation
    private var canBack = false

    init {
        initViews()
        initCenter()
        initAnimation()
    }

    private fun initAnimation() {
        inAnim = getInAnimation()
        outAnim = getOutAnimation()
    }

    private fun initFromTopRight() {
        inAnim = AnimationUtils.loadAnimation(context, R.anim.moprogress_in_top_right)
        outAnim = AnimationUtils.loadAnimation(context, R.anim.moprogress_out_top_right)
    }

    private fun initFromBottomRight() {
        inAnim = AnimationUtils.loadAnimation(context, R.anim.moprogress_in_bottom_right)
        outAnim = AnimationUtils.loadAnimation(context, R.anim.moprogress_out_bottom_right)
    }

    private fun initFromBottomAnimation() {
        inAnim = AnimationUtils.loadAnimation(context, R.anim.moprogress_bottom_in)
        outAnim = AnimationUtils.loadAnimation(context, R.anim.moprogress_bottom_out)
    }

    private fun initCenter() {
        mSharedView.gravity = Gravity.CENTER
        val layoutParams = mSharedView.layoutParams as? FrameLayout.LayoutParams
        layoutParams?.setMargins(0, 0, 0, 0)
        mSharedView.setPadding(0, 0, 0, 0)
    }

    private fun initBottom() {
        mSharedView.gravity = Gravity.BOTTOM
        val layoutParams = mSharedView.layoutParams as? FrameLayout.LayoutParams
        layoutParams?.setMargins(0, 0, 0, 0)
        mSharedView.setPadding(0, 0, 0, 0)
    }

    private fun initMarRightTop() {
        mSharedView.gravity = Gravity.END or Gravity.TOP
        val layoutParams = mSharedView.layoutParams as? FrameLayout.LayoutParams
        layoutParams?.setMargins(0, 0, 0, 0)
        mSharedView.setPadding(0, 0, 0, 0)
    }

    private fun initViews() {
        decorView = (context as Activity).window.decorView.findViewById(android.R.id.content)
        rootView = FrameLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            isClickable = true
        }
        mSharedView = MoProgressView(context)
    }

    private fun getInAnimation(): Animation {
        return AnimationUtils.loadAnimation(context, R.anim.moprogress_in)
    }

    private fun getOutAnimation(): Animation {
        return AnimationUtils.loadAnimation(context, R.anim.moprogress_out)
    }

    fun isShowing(): Boolean {
        return rootView.parent != null
    }

    private fun onAttached() {
        decorView.addView(rootView)
        (mSharedView.parent as? ViewGroup)?.removeView(mSharedView)
        rootView.addView(mSharedView)
        isFinishing = false
    }

    fun dismiss() {
        // 消失动画
        if (::mSharedView.isInitialized && ::rootView.isInitialized && mSharedView.parent != null) {
            if (!isFinishing) {
                Handler(Looper.getMainLooper()).post {
                    outAnim.setAnimationListener(outAnimListener)
                    mSharedView.getChildAt(0)?.startAnimation(outAnim)
                }
            }
        }
    }

    fun isShow(): Boolean {
        return ::mSharedView.isInitialized && mSharedView.parent != null
    }

    fun dismissImmediately() {
        if (::mSharedView.isInitialized && ::rootView.isInitialized && mSharedView.parent != null) {
            Handler(Looper.getMainLooper()).post {
                rootView.removeView(mSharedView)
                decorView.removeView(rootView)
            }
        }
        isFinishing = false
    }

    // 转圈的载入
    fun showLoading() {
        showLoading(null)
    }

    // 同上
    fun showLoading(msg: String?) {
        initCenter()
        initAnimation()
        canBack = false
        rootView.setBackgroundColor("#00000000".toColorInt())
        rootView.setOnClickListener(null)
        if (!isShowing()) {
            onAttached()
        }
        mSharedView.showLoading(msg)
        mSharedView.getChildAt(0)?.startAnimation(inAnim)
    }

    // 单个按钮的提示信息
    fun showInfo(msg: String) {
        initCenter()
        initAnimation()
        canBack = true
        rootView.setBackgroundColor("#00000000".toColorInt())
        rootView.setOnClickListener(null)
        mSharedView.showInfo(msg) { dismiss() }
        if (!isShowing()) {
            onAttached()
        }
        mSharedView.getChildAt(0)?.startAnimation(inAnim)
    }

    // 单个按钮的提示信息
    fun showInfo(msg: String, btnText: String, listener: OnClickListener) {
        initCenter()
        initAnimation()
        canBack = true
        rootView.setBackgroundColor("#CC000000".toColorInt())
        rootView.setOnClickListener(null)
        mSharedView.showInfo(msg, btnText, listener)
        if (!isShowing()) {
            onAttached()
        }
        mSharedView.getChildAt(0)?.startAnimation(inAnim)
    }

    // 两个不同等级的按钮
    fun showTwoButton(
        msg: String,
        firstBtn: String,
        firstListener: OnClickListener,
        secondBtn: String,
        secondListener: OnClickListener
    ) {
        initCenter()
        initAnimation()
        canBack = true
        rootView.setBackgroundColor("#CC000000".toColorInt())
        rootView.setOnClickListener(null)
        mSharedView.showTwoButton(msg, firstBtn, firstListener, secondBtn, secondListener)
        if (!isShowing()) {
            onAttached()
        }
        mSharedView.getChildAt(0)?.startAnimation(inAnim)
    }

    fun showDownloadList(startIndex: Int, endIndex: Int, all: Int, clickDownload: OnClickDownload) {
        initCenter()
        initAnimation()
        canBack = true
        rootView.setBackgroundColor("#00000000".toColorInt())
        rootView.setOnClickListener { dismiss() }
        mSharedView.showDownloadList(startIndex, endIndex, all, clickDownload) { dismiss() }
        if (!isShowing()) {
            onAttached()
        }
        mSharedView.getChildAt(0)?.startAnimation(inAnim)
    }

    fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (isShowing() && canBack) {
                dismiss()
                return true
            }
        }
        return false
    }

    fun getCanBack(): Boolean {
        return canBack
    }

    fun onPressBack(): Boolean {
        if (isShowing() && canBack) {
            dismiss()
            return true
        }
        return false
    }

    // 离线章节选择
    fun interface OnClickDownload {
        fun download(start: Int, end: Int)
    }
}