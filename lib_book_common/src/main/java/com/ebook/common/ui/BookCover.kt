package com.ebook.common.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.ebook.common.ui.preview.AppPreview
import com.ebook.common.ui.preview.SAMPLE_COVER_URL

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
 * @param modifier 尺寸与比例都由调用方决定（本组件没有固有比例，[ContentScale.Crop] 按这个框裁切填满）
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

/**
 * 预览：条目封面尺寸。宽度按 [BookItemLayout.coverWidth] 从列高推出，正是「三行书条目」里
 * 封面与正文列等高且比例 3:4 的那对关系——预览里成对给出，改列高时能当场看出比例有没有跟上。
 *
 * **这里看到的必然是兜底图**：预览与非设备环境没有可联网的图像加载，Coil 直接落 error/placeholder
 * 那一份。这恰好是有用的一档（真实站点里就有一批书没封面），但「有封面时裁切对不对」只能在设备上看。
 */
@Preview(showBackground = true, widthDp = 200)
@Composable
private fun BookCoverPreview() {
    AppPreview {
        val columnHeight = BookItemLayout.columnHeight
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            BookCover(
                url = SAMPLE_COVER_URL,
                modifier = Modifier
                    .width(BookItemLayout.coverWidth(columnHeight))
                    .height(columnHeight),
                contentDescription = "条目封面",
            )
            // 小圆角档：搜索结果里的小封面走这一档
            BookCover(
                url = SAMPLE_COVER_URL,
                modifier = Modifier
                    .width(BookItemLayout.coverWidth(56.dp))
                    .height(56.dp),
                shape = RoundedCornerShape(6.dp),
                contentDescription = "小封面",
            )
        }
    }
}
