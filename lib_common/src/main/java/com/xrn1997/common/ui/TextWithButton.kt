package com.xrn1997.common.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp

/**
 * 上面一个显示用的Text，下面一个Button
 * @param text 显示的内容
 * @param buttonText 按钮上的文字
 */
@Composable
fun TextWithButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    buttonText: String = "Click Me",
) {
    Column(
        modifier = modifier
            .wrapContentSize(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = text,
            fontSize = 50.sp,
            textAlign = TextAlign.Center
        )
        TextInButton(onClick, modifier, buttonText)
    }
}
