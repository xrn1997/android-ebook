package com.xrn1997.common.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.util.UUID

/**
 * 页面跳转数据类
 * @author xrn1997
 */
data class ButtonFun(
    /**
     * 页面说明
     */
    val text: String,
    /**
     * 目标页面
     */
    val activity: Class<*>? = null,
    /**
     * 目标UI状态,-1代表未设置
     */
    val state: Int = -1,
    /**
     * 页面ID,不可重复
     */
    val id: UUID = UUID.randomUUID()
)

/**
 * 一个button列表,一般测试用的模块使用居多
 */
@Suppress("unused")
@Composable
@Stable
fun ScrollableList(
    list: List<ButtonFun>,
    onItemClick: (bf: ButtonFun) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(20.dp),
        contentPadding = PaddingValues(top = 20.dp, bottom = 20.dp)
    ) {
        itemsIndexed(list) { _, item ->
            key(item.id) {
                TextInButton(
                    onClick = { onItemClick(item) },
                    text = item.text
                )
            }

        }
    }
}