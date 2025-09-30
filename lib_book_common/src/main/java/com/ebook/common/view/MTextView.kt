package com.ebook.common.view

import android.content.Context
import android.graphics.Canvas
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatTextView

class MTextView : AppCompatTextView {

    constructor(context: Context) : super(context)

    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)

    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(context, attrs, defStyleAttr)

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
    }

    override fun onDraw(canvas: Canvas) {
        val paint: TextPaint = paint
        paint.color = textColors.defaultColor

        val layout = StaticLayout.Builder.obtain(
            text,          // 需要分行的字符串
            0,             // 文本起始位置
            text.length,   // 文本结束位置
            paint,         // 画笔对象
            width   // layout的宽度，超出时换行
        )
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)  // 对齐方式
            .setLineSpacing(lineSpacingExtra, lineSpacingMultiplier) // 行间距
            .setIncludePad(false) // 是否包含内边距
            .build()

        layout.draw(canvas)
    }
}