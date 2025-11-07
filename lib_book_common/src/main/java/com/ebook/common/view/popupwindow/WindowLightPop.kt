package com.ebook.common.view.popupwindow

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.provider.Settings
import android.provider.Settings.SettingNotFoundException
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.PopupWindow
import androidx.core.content.ContextCompat
import com.ebook.common.R
import com.ebook.common.view.checkbox.SmoothCheckBox
import com.ebook.common.view.mprogressbar.MHorProgressBar
import com.ebook.common.view.mprogressbar.OnProgressListener

/**
 * WindowLightPop：阅读页面亮度调节弹窗
 *
 * 功能：
 * 1. 可调整屏幕亮度
 * 2. 可选择跟随系统亮度
 * 3. 自动保存亮度配置
 */
class WindowLightPop(private val mContext: Context) :
    PopupWindow(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT) {

    private val preferences: SharedPreferences =
        mContext.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)

    /** 弹窗布局 */
    private val view: View = LayoutInflater.from(mContext).inflate(R.layout.view_pop_windowlight, null)

    /** 亮度进度条 */
    private lateinit var hpbLight: MHorProgressBar

    /** 跟随系统布局和复选框 */
    private lateinit var llFollowSys: LinearLayout
    private lateinit var scbFollowSys: SmoothCheckBox

    /** 是否跟随系统亮度 */
    private var isFollowSys: Boolean = true

    /** 当前亮度值 0~255 */
    private var light: Int = 0

    init {
        contentView = view
        initView()
        initData()
        bindEvent()

        // 设置背景、焦点、可触摸及动画
        setBackgroundDrawable(ContextCompat.getDrawable(mContext, R.drawable.shape_pop_checkaddshelf_bg))
        isFocusable = true
        isTouchable = true
        animationStyle = R.style.anim_pop_windowlight
    }

    /** 初始化视图控件 */
    private fun initView() {
        hpbLight = view.findViewById(R.id.hpb_light)
        llFollowSys = view.findViewById(R.id.ll_follow_sys)
        scbFollowSys = view.findViewById(R.id.scb_follow_sys)
    }

    /** 初始化亮度数据 */
    private fun initData() {
        isFollowSys = preferences.getBoolean(KEY_FOLLOW_SYS, true)
        light = preferences.getInt(KEY_LIGHT, screenBrightness)
    }

    /** 绑定事件 */
    private fun bindEvent() {
        // 点击跟随系统容器切换复选框
        llFollowSys.setOnClickListener {
            scbFollowSys.setChecked(!scbFollowSys.isChecked(), true)
        }

        // 复选框状态变化
        scbFollowSys.setOnCheckedChangeListener { _, isChecked ->
            isFollowSys = isChecked
            hpbLight.canTouch = !isChecked
            if (isChecked) {
                setScreenBrightnessFollowSystem()
            } else {
                hpbLight.setDurProgress(light.toFloat())
            }
        }

        // 亮度进度条监听
        hpbLight.setProgressListener(object : OnProgressListener {
            override fun moveStartProgress(dur: Float) {}
            override fun durProgressChange(dur: Float) {
                if (!isFollowSys) {
                    light = dur.toInt()
                    screenBrightness = light
                }
            }

            override fun moveStopProgress(dur: Float) {}
            override fun setDurProgress(dur: Float) {}
        })
    }

    /** 设置亮度跟随系统 */
    private fun setScreenBrightnessFollowSystem() {
        val params = (mContext as Activity).window.attributes
        params.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        mContext.window.attributes = params
    }

    /** 屏幕亮度 getter/setter 0~255 */
    var screenBrightness: Int
        get() {
            return try {
                Settings.System.getInt(mContext.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
            } catch (e: SettingNotFoundException) {
                Log.e(TAG, "getScreenBrightness: ", e)
                0
            }
        }
        set(value) {
            val params = (mContext as Activity).window.attributes
            params.screenBrightness = value / 255f
            mContext.window.attributes = params
        }

    /** 保存亮度到 SharedPreferences */
    private fun saveLight() {
        preferences.edit().apply {
            putInt(KEY_LIGHT, light)
            putBoolean(KEY_FOLLOW_SYS, isFollowSys)
            apply()
        }
    }

    /** 弹窗显示时刷新数据 */
    override fun showAtLocation(parent: View?, gravity: Int, x: Int, y: Int) {
        super.showAtLocation(parent, gravity, x, y)
        initData()
        hpbLight.setDurProgress(light.toFloat())
        scbFollowSys.setChecked(isFollowSys, false)
    }

    /** 弹窗消失时保存数据 */
    override fun dismiss() {
        saveLight()
        super.dismiss()
    }

    /** 初始化亮度，如果不跟随系统则设置为当前 light */
    fun initLight() {
        if (!isFollowSys) {
            screenBrightness = light
        }
    }

    companion object {
        const val SP_NAME = "CONFIG"
        private const val TAG = "WindowLightPop"
        private const val KEY_LIGHT = "light"
        private const val KEY_FOLLOW_SYS = "is_follow_sys"
    }
}
