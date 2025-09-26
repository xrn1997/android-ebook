package com.ebook.common.util

import android.view.animation.Animation
import android.view.animation.TranslateAnimation


object AnimationUtils {
    /**
     * 从控件所在位置移动到控件的底部
     *
     * @return
     */
    @JvmStatic
    fun moveToBottom(): TranslateAnimation {
        val hiddenAction = TranslateAnimation(
            Animation.RELATIVE_TO_SELF, 0.0f, Animation.RELATIVE_TO_SELF, 0.0f,
            Animation.RELATIVE_TO_SELF, 0.0f, Animation.RELATIVE_TO_SELF, 1.0f
        )
        hiddenAction.duration = 300
        return hiddenAction
    }

    @JvmStatic
    fun moveToLocation(): TranslateAnimation {
        val visibleAction = TranslateAnimation(
            Animation.RELATIVE_TO_SELF, 0.0f, Animation.RELATIVE_TO_SELF, 0.0f,
            Animation.RELATIVE_TO_SELF, 1.0f, Animation.RELATIVE_TO_SELF, 0.0f
        )
        visibleAction.duration = 300
        return visibleAction
    }
}
