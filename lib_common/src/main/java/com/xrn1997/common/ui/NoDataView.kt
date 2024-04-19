package com.xrn1997.common.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.xrn1997.common.R

/**
 * “没有数据"  视图
 * @param res: 图片资源ID
 * @param des: 图片内容描述
 */
@Composable
fun NoDataView(
    visible: Boolean,
    modifier: Modifier = Modifier,
    res: Int = R.drawable.no_data,
    des: Int = R.string.no_data
) {
    if (visible) {
        Column(
            modifier = modifier,
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Image(
                painter = painterResource(id = res),
                contentDescription = stringResource(des)
            )
        }
    }
}