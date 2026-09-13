package com.ebook.book.view

import androidx.core.graphics.toColorInt
import com.ebook.book.R
import com.ebook.common.util.SPUtil
import com.xrn1997.common.BaseApplication.Companion.context
import com.xrn1997.common.util.DisplayUtil

/**
 * ReadBookControl
 * 内存缓存 + SP 持久化，避免频繁 I/O。
 */
object ReadBookControl {

    private const val SP_NAME = "CONFIG"
    const val DEFAULT_TEXT = 2
    const val DEFAULT_BG = 1
    const val DEFAULT_TURN_MODE = 0

    /** 字体样式 */
    data class TextKind(val textSize: Int, val textExtra: Int)

    /**
     * 背景主题
     *
     * @param labelRes 主题展示名的字符串资源——配色值本身无法向读者表达"这是哪一档"，
     * 字体面板的色卡需要这个名字把「所见」与「所选」对上（见 reader/ReaderPanels.kt）
     */
    data class TextDrawable(val textColor: Int, val textBackground: Int, val labelRes: Int)

    /** 字体样式列表（初始化一次） */
    private val textKindList: List<TextKind> = listOf(
        TextKind(14, DisplayUtil.dp2px(context, 6.5f)),
        TextKind(16, DisplayUtil.dp2px(context, 8f)),
        TextKind(17, DisplayUtil.dp2px(context, 9f)),
        TextKind(20, DisplayUtil.dp2px(context, 11f)),
        TextKind(22, DisplayUtil.dp2px(context, 13f)),
        TextKind(24, DisplayUtil.dp2px(context, 15f)),
        TextKind(26, DisplayUtil.dp2px(context, 17f)),
        TextKind(30, DisplayUtil.dp2px(context, 21f))
    )

    /** 背景主题列表（初始化一次，顺序即字体面板色卡的展示顺序） */
    private val textDrawableList: List<TextDrawable> = listOf(
        TextDrawable("#3E3D3B".toColorInt(), "#F3F3F3".toColorInt(), R.string.theme_paper),//白
        TextDrawable("#5E432E".toColorInt(), "#DCD1BC".toColorInt(), R.string.theme_sepia),//黄
        TextDrawable("#22482C".toColorInt(), "#E1F0D9".toColorInt(), R.string.theme_green),//绿
        TextDrawable("#808080".toColorInt(), "#2D2D33".toColorInt(), R.string.theme_dark)//黑
    )

    /**
     * 翻页方式。
     *
     * 落 SP 的是 [turnModeList] 的**索引**而不是枚举名（与 textKindIndex/textDrawableIndex
     * 同口径）：索引越界能回落默认，枚举名一旦改名就会让老数据解析失败。
     * 枚举只活在内存里，不进任何持久化字段。
     */
    enum class TurnMode {
        /** 左右翻页：三页窗口 + 横向拖拽 */
        PAGE,

        /** 上下滚屏：整章屏块的竖向连续滚动 */
        SCROLL,
    }

    /** 翻页方式列表（顺序即设置面板的展示顺序） */
    private val turnModeList: List<TurnMode> = listOf(TurnMode.PAGE, TurnMode.SCROLL)

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
    var turnModeIndex: Int
        private set

    /** 当前翻页方式（= [turnModeList] 的 [turnModeIndex] 项，索引已在 init 里钳过界） */
    val turnMode: TurnMode
        get() = turnModeList[turnModeIndex]

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

        turnModeIndex = SPUtil.get("turnModeIndex", DEFAULT_TURN_MODE, SP_NAME)
        // 越界回落默认：默认是左右翻页，老用户升级后行为不变，不会一觉醒来变成滚屏
        if (turnModeIndex !in turnModeList.indices) turnModeIndex = DEFAULT_TURN_MODE
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

    fun updateTurnModeIndex(index: Int) {
        if (index !in turnModeList.indices) return
        turnModeIndex = index
        SPUtil.put("turnModeIndex", index, SP_NAME)
    }

    // ----------------------------
    // 工具方法
    // ----------------------------
    fun getTextKindList(): List<TextKind> = textKindList
    fun getTextDrawableList(): List<TextDrawable> = textDrawableList
    fun getTurnModeList(): List<TurnMode> = turnModeList
}
