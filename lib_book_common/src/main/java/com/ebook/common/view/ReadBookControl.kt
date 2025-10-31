package com.ebook.common.view

import androidx.core.graphics.toColorInt
import com.ebook.common.R
import com.ebook.common.util.SPUtil
import com.xrn1997.common.util.DisplayUtil

/**
 * ReadBookControl
 * 内存缓存 + SP 持久化，避免频繁 I/O。
 */
object ReadBookControl {

    private const val SP_NAME = "CONFIG"
    const val DEFAULT_TEXT = 2
    const val DEFAULT_BG = 1

    /** 字体样式 */
    data class TextKind(val textSize: Int, val textExtra: Int)

    /** 背景主题 */
    data class TextDrawable(val textColor: Int, val textBackground: Int)

    /** 字体样式列表（初始化一次） */
    private val textKindList: List<TextKind> = listOf(
        TextKind(14, DisplayUtil.dip2px(6.5f).toInt()),
        TextKind(16, DisplayUtil.dip2px(8f).toInt()),
        TextKind(17, DisplayUtil.dip2px(9f).toInt()),
        TextKind(20, DisplayUtil.dip2px(11f).toInt()),
        TextKind(22, DisplayUtil.dip2px(13f).toInt()),
        TextKind(24, DisplayUtil.dip2px(15f).toInt()),
        TextKind(26, DisplayUtil.dip2px(17f).toInt()),
        TextKind(30, DisplayUtil.dip2px(21f).toInt())
    )

    /** 背景主题列表（初始化一次） */
    private val textDrawableList: List<TextDrawable> = listOf(
        TextDrawable("#3E3D3B".toColorInt(), "#F3F3F3".toColorInt()),//白
        TextDrawable("#5E432E".toColorInt(), "#DCD1BC".toColorInt()),//黄
        TextDrawable("#22482C".toColorInt(), "#E1F0D9".toColorInt()),//绿
        TextDrawable("#808080".toColorInt(), "#2D2D33".toColorInt())//黑
    )

    // ----------------------------
    // 内存缓存属性
    // ----------------------------
    var textKindIndex: Int
        private set
    var textDrawableIndex: Int
        private set
    var textSize: Int
        private set
    var textExtra: Int
        private set
    var textColor: Int
        private set
    var textBackground: Int
        private set
    var canClickTurn: Boolean
        private set
    var canKeyTurn: Boolean
        private set

    init {
        // 从 SP 初始化（只读一次）
        textKindIndex = SPUtil.get("textKindIndex", DEFAULT_TEXT, SP_NAME)
        if (textKindIndex !in textKindList.indices) textKindIndex = DEFAULT_TEXT
        textSize = textKindList[textKindIndex].textSize
        textExtra = textKindList[textKindIndex].textExtra

        textDrawableIndex = SPUtil.get("textDrawableIndex", DEFAULT_BG, SP_NAME)
        if (textDrawableIndex !in textDrawableList.indices) textDrawableIndex = DEFAULT_BG
        textColor = textDrawableList[textDrawableIndex].textColor
        textBackground = textDrawableList[textDrawableIndex].textBackground

        canClickTurn = SPUtil.get("canClickTurn", true, SP_NAME)
        canKeyTurn = SPUtil.get("canKeyTurn", true, SP_NAME)
    }

    // ----------------------------
    // 更新方法（避免 setter 与属性名冲突）
    // ----------------------------

    fun updateTextKindIndex(index: Int) {
        if (index !in textKindList.indices) return
        textKindIndex = index
        textSize = textKindList[index].textSize
        textExtra = textKindList[index].textExtra
        SPUtil.put("textKindIndex", index, SP_NAME)
    }

    fun updateTextDrawableIndex(index: Int) {
        if (index !in textDrawableList.indices) return
        textDrawableIndex = index
        textColor = textDrawableList[index].textColor
        textBackground = textDrawableList[index].textBackground
        SPUtil.put("textDrawableIndex", index, SP_NAME)
    }

    fun setCanClickTurn(enable: Boolean) {
        canClickTurn = enable
        SPUtil.put("canClickTurn", enable, SP_NAME)
    }

    fun setCanKeyTurn(enable: Boolean) {
        canKeyTurn = enable
        SPUtil.put("canKeyTurn", enable, SP_NAME)
    }

    // ----------------------------
    // 工具方法
    // ----------------------------
    fun getCurrentTextKind(): TextKind = textKindList[textKindIndex]
    fun getCurrentTextDrawable(): TextDrawable = textDrawableList[textDrawableIndex]
    fun getTextKindList(): List<TextKind> = textKindList
    fun getTextDrawableList(): List<TextDrawable> = textDrawableList
}
