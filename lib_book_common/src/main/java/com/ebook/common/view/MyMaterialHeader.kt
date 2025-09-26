package com.ebook.common.view

import android.content.Context
import android.util.AttributeSet
import com.scwang.smart.refresh.header.MaterialHeader
import com.xrn1997.common.util.getThemeColor

/**
 * 颜色模式跟随系统
 */
class MyMaterialHeader(context: Context, attrs: AttributeSet?) : MaterialHeader(context, attrs) {
    init {
        val color = context.getThemeColor(com.google.android.material.R.attr.colorSurfaceVariant)
        setProgressBackgroundColorSchemeColor(color)
    }
}