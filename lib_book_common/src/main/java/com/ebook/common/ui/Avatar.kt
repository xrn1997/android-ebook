package com.ebook.common.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import coil.compose.AsyncImage
import com.ebook.common.R

/**
 * 用户头像：Coil 网络图 + 圆形裁剪 + 三态兜底。
 *
 * 与 [BookCover] 同构的收敛点：各页手写的「AsyncImage + 默认图回退」在这里收一次，
 * 调用方只给 URL 与尺寸。修掉的缺陷是原先的回退只判 URL 是否为空——**URL 非空但取不到**
 * （上传文件被删、CDN 失效、设备离线）时会渲染成一个空白圆，默认头像永不登场。
 *
 * 三态取法：
 * - 空 URL：没有请求可发，直接展示默认头像（走 [Image]，不经过 Coil）；
 * - 加载中：[MaterialTheme.colorScheme.surfaceVariant] 中性色块。刻意不给默认头像——
 *   头像的内容必然是「人」，加载途中先闪一张陌生剪影再换成真人照片，比色块突兀；
 * - 失败：默认头像。
 *
 * 登录态不属于本组件的语义：未登录时由调用方传空串（「我的」页就是这么做的）。
 * 光环、描边一类的装饰同样留在调用方——它们各只有一处使用者，收进参数等于
 * 把一个调用点的复杂度摊给所有调用点，把组件做浅。
 *
 * @param url 头像地址；空串直接展示默认头像
 * @param modifier 尺寸由调用方决定（如 `Modifier.size(72.dp)`）
 * @param contentDescription 无障碍描述，`null` 表示纯装饰
 */
@Composable
fun Avatar(
    url: String,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    val defaultAvatar = painterResource(id = R.drawable.img_avatar_default)
    val clip = modifier.clip(CircleShape)
    if (url.isBlank()) {
        Image(
            painter = defaultAvatar,
            contentDescription = contentDescription,
            modifier = clip,
            contentScale = ContentScale.Crop,
        )
    } else {
        AsyncImage(
            model = url,
            contentDescription = contentDescription,
            modifier = clip,
            // 加载中用中性色块而不是默认头像：头像内容必然是「人」，先闪一张陌生剪影更突兀
            placeholder = ColorPainter(MaterialTheme.colorScheme.surfaceVariant),
            error = defaultAvatar,
            contentScale = ContentScale.Crop,
        )
    }
}
