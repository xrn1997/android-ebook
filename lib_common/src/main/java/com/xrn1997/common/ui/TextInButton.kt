package com.xrn1997.common.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 带文字的Button
 * @author xrn1997
 */
@Composable
fun TextInButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    text: String = "Click Me"
) {
    Button(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp),
        onClick = { onClick() }
    ) {
        Text(
            text = text
        )
    }
}