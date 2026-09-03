package com.ebook.common.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalContext
import androidx.core.graphics.drawable.toBitmap

/**
 * 默认书籍封面占位图（供 Coil AsyncImage 的 placeholder/error 使用）。
 *
 * 资源 [com.ebook.common.R.drawable.img_cover_default] 是 NinePatch（.9.png），
 * `painterResource` 不支持（仅 VectorDrawable/PNG/JPG/WEBP，运行时抛异常），
 * 故转 Bitmap 后包装为 [BitmapPainter]。
 * 注：NinePatch 的拉伸区域语义在转 Bitmap 后丢失（按 density 缩放），占位图场景可接受。
 *
 * 抑制 `LocalContextGetResourceValueCall`：该 lint 建议改用 `painterResource`/`LocalResources`，
 * 但 `painterResource` 不支持 NinePatch（运行时抛 ResourceResolutionException），此处**必须**走
 * `Context.getDrawable`；占位图为固定内置资源、不随 Configuration 变化，抑制无副作用。
 */
@Suppress("LocalContextGetResourceValueCall")
@Composable
fun rememberCoverPlaceholderPainter(): Painter {
    val context = LocalContext.current
    // 解码失败时的回退色走语义色（禁止硬编码色值）；取一次即可，占位色不随内容变化
    val fallbackColor = MaterialTheme.colorScheme.surfaceVariant
    return remember {
        context.getDrawable(com.ebook.common.R.drawable.img_cover_default)
            ?.toBitmap()
            ?.asImageBitmap()
            ?.let { BitmapPainter(it) }
            ?: ColorPainter(fallbackColor)
    }
}
