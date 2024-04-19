package com.xrn1997.common.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.xrn1997.common.R

/**
 * “网络错误” 视图
 * @param res: 图片资源ID
 * @param des: 图片内容描述
 */
@Composable
fun NetworkErrorView(
    visible: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    res: Int = R.drawable.no_network,
    des: Int = R.string.no_network
) {
    if (visible) {
        Column(
            modifier = modifier
                .wrapContentSize(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            ImageWithButton(
                res,
                des,
                onClick = onClick,
                buttonText = stringResource(id = R.string.refresh)
            )
        }
    }
}