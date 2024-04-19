package com.xrn1997.common.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xrn1997.common.R

/**
 * "正在加载"视图（默认不透明背景）
 * @param visible 是否在加载
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoadingView(
    visible: Boolean,
    modifier: Modifier = Modifier,
    txt: String = stringResource(R.string.loading)
) {
    if (visible) {
        BasicAlertDialog(
            onDismissRequest = { /* Do nothing */ },
            modifier = modifier
                .wrapContentWidth()
                .background(
                    color = MaterialTheme.colorScheme.background,
                    shape = RoundedCornerShape(15.dp)
                ),
            content = {
                Row(
                    modifier = Modifier.padding(top = 8.dp, bottom = 8.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .size(96.dp)
                            .padding(16.dp)
                    )
                    Text(
                        text = txt,
                        fontSize = 24.sp,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                }
            }
        )
    }
}








