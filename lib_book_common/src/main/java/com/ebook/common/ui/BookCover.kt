package com.ebook.common.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage

/**
 * 书籍封面：Coil 网络图 + 统一占位图（[rememberCoverPlaceholderPainter]）+ 圆角裁剪。
 *
 * 书城横向书卡、搜索结果条目、书架等多处封面展示的收敛点，
 * 避免各页重复组合 AsyncImage + 占位 Painter + clip。
 *
 * contentScale 固定 [ContentScale.Crop]：旧实现未指定（默认 Fit），
 * 非 3:4 封面会被拉伸变形；Crop 改为裁切填充，观感更稳。
 * 需要阴影/描边时由调用方外包 Card/Surface（如书城横向书卡），本组件保持纯粹。
 *
 * @param url 封面图片地址（Coil 内部处理空串/失败 → error 占位）
 * @param modifier 尺寸由调用方决定（如 `Modifier.size(60.dp, 90.dp)`）
 * @param contentDescription 无障碍描述
 * @param shape 圆角，默认 [CommonUiTokens.coverCorner]；条目内小封面可传更小圆角
 */
@Composable
fun BookCover(
    url: String,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    shape: Shape = RoundedCornerShape(CommonUiTokens.coverCorner)
) {
    AsyncImage(
        model = url,
        contentDescription = contentDescription,
        modifier = modifier.clip(shape),
        placeholder = rememberCoverPlaceholderPainter(),
        error = rememberCoverPlaceholderPainter(),
        contentScale = ContentScale.Crop
    )
}
