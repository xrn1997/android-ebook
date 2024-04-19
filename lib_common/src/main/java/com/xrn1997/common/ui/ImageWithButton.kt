package com.xrn1997.common.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

/**
 * 上面一个显示用的Image，下面一个Button
 * @param res: 图片资源ID
 * @param des: 图片内容描述
 * @param onClick: 单击事件
 * @param buttonText: 按钮上的文字
 */
@Composable
fun ImageWithButton(
    res: Int,
    des: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    buttonText: String = "Click Me",
) {
    Column(
        modifier = modifier
            .wrapContentSize(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Image(
            modifier = Modifier.padding(bottom = 16.dp),
            painter = painterResource(id = res),
            contentDescription = stringResource(des)
        )
        TextInButton(onClick, modifier, buttonText)
    }
}