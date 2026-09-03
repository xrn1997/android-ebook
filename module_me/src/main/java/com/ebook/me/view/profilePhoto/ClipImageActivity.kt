package com.ebook.me.view.profilePhoto

import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.BlendMode
import androidx.lifecycle.lifecycleScope
import com.ebook.me.R
import com.xrn1997.common.mvvm.compose.BaseActivity
import com.xrn1997.common.util.BitmapUtil
import com.xrn1997.common.util.FileUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max
import kotlin.math.min

/**
 * 头像裁剪页（Compose，替代原 ViewBinding 版）。
 *
 * 图片经手势单指拖动、双指缩放（范围 [minScale, 4*minScale]），圆形裁剪区固定在容器中央，
 * 拖动/缩放被约束为裁剪圆内不露白；确认后按当前显示矩阵裁出圆外接正方形并缩放输出。
 *
 * 与旧版（ClipViewLayout + ClipView 自绘 View）行为一致，但纯 Compose 实现，
 * 深色模式自动跟随主题（MyApplicationTheme），不再依赖 ViewBinding 主题。
 */
class ClipImageActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        toolbarTitle.value = getString(R.string.clip_crop_title)
    }

    @Composable
    override fun PageContent() {
        val uri = remember { intent?.data }
        var bitmap by remember { mutableStateOf<Bitmap?>(null) }
        var failed by remember { mutableStateOf(false) }

        // 采样解码（防 OOM）+ EXIF 旋转纠正：BitmapUtil 为同步方法，调用方切 IO 线程（X1 上提契约）
        LaunchedEffect(uri) {
            val bmp = uri?.let {
                withContext(Dispatchers.IO) {
                    BitmapUtil.loadSampledBitmap(this@ClipImageActivity, it, 720, 1280)
                }
            }
            bitmap = bmp
            failed = bmp == null
        }

        val bmp = bitmap
        when {
            bmp != null -> ClipImageScreen(
                bitmap = bmp,
                onCancel = { finish() },
                onConfirm = { returnCropResult(it) }
            )
            failed -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.clip_load_failed),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            else -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        }
    }

    /**
     * 把裁剪结果写为临时 JPEG 并通过 result 返回 Uri（与旧版一致，供上层上传）。
     *
     * JPEG 压缩与文件写入在 IO 线程执行（裁剪输出虽小，仍不阻塞主线程），
     * 完成后回主线程 setResult + finish。
     */
    private fun returnCropResult(cropped: Bitmap) {
        lifecycleScope.launch {
            val resultFile = withContext(Dispatchers.IO) {
                runCatching {
                    val file = File(cacheDir, "cropped_${System.currentTimeMillis()}.jpg")
                    file.outputStream().use {
                        cropped.compress(Bitmap.CompressFormat.JPEG, 90, it)
                    }
                    file
                }.getOrNull()
            }
            setResult(
                if (resultFile != null) RESULT_OK else RESULT_CANCELED,
                Intent().apply { if (resultFile != null) data = FileUtil.contentUri(this@ClipImageActivity, resultFile) }
            )
            cropped.recycle()
            finish()
        }
    }
}

/**
 * 裁剪页内容：手势裁剪区 + 底部操作栏。
 *
 * 裁剪区尺寸经 onSizeChanged 采集后计算裁剪圆（直径 = 短边 - 左右留白），
 * 确认按钮在裁剪区尺寸就绪前禁用。
 */
@Composable
private fun ClipImageScreen(
    bitmap: Bitmap,
    onCancel: () -> Unit,
    onConfirm: (Bitmap) -> Unit,
    modifier: Modifier = Modifier
) {
    var cropAreaSize by remember { mutableStateOf(IntSize.Zero) }
    val cropState = if (cropAreaSize.width > 0 && cropAreaSize.height > 0) {
        rememberCropState(bitmap, cropAreaSize)
    } else {
        null
    }

    Column(modifier = modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .onSizeChanged { cropAreaSize = it }
        ) {
            if (cropState != null) {
                ClipCropBox(
                    bitmap = bitmap,
                    state = cropState,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        // 底部操作栏：取消 / 确定
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onCancel) {
                Text(stringResource(R.string.common_cancel))
            }
            Button(
                enabled = cropState != null,
                onClick = { cropState?.let { onConfirm(cropBitmap(bitmap, it)) } }
            ) {
                Text(stringResource(R.string.common_confirm))
            }
        }
    }
}

/**
 * 手势裁剪容器：图片层（手势矩阵）+ 遮罩层（scrim 语义色挖圆孔）+ 边框（onSurface 语义色）。
 *
 * 裁剪配色统一走 MaterialTheme 语义色，深浅色主题自动适配对比度；
 * 不再像旧 View 版那样硬编码暗底 / 黑遮罩 / 白框。
 *
 * 遮罩层独立离屏绘制（CompositingStrategy.Offscreen），BlendMode.Clear 只清除本层内容，
 * 使圆孔区域透明、露出下层图片——等价于旧 ClipView 的 DST_OUT 效果。
 */
@Composable
private fun ClipCropBox(
    bitmap: Bitmap,
    state: CropState,
    modifier: Modifier = Modifier
) {
    val imageBitmap = remember(bitmap) { bitmap.asImageBitmap() }
    // 裁剪配色统一走语义色，深浅色主题自动适配
    val cropBackground = MaterialTheme.colorScheme.surfaceVariant
    val cropMask = MaterialTheme.colorScheme.scrim
    val cropBorder = MaterialTheme.colorScheme.onSurface
    Box(
        modifier
            .clipToBounds()
            // state 作为 key：旋转屏幕等场景下 CropState 重建后手势协程随之重启，避免操作旧对象
            .pointerInput(bitmap, state) {
                detectTransformGestures { centroid, pan, zoom, _ ->
                    state.onTransform(centroid, pan, zoom)
                }
            }
    ) {
        // 图片层：固定背景（surfaceVariant 语义色）保证图片对比度，图片按手势矩阵绘制
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawRect(cropBackground)
            withTransform({
                translate(state.offset.x, state.offset.y)
                scale(state.scale, state.scale, pivot = Offset.Zero)
            }) {
                drawImage(imageBitmap)
            }
        }

        // 遮罩层：scrim 语义色遮罩 + Clear 挖圆孔
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    compositingStrategy = CompositingStrategy.Offscreen
                }
        ) {
            drawRect(cropMask)
            drawCircle(
                color = Color.Transparent,
                radius = state.radius,
                center = state.center,
                blendMode = BlendMode.Clear
            )
        }

        // 边框：onSurface 语义色圆框
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawCircle(
                color = cropBorder,
                radius = state.radius,
                center = state.center,
                style = Stroke(width = 2.dp.toPx())
            )
        }
    }
}

/**
 * 裁剪手势状态：缩放比例 + 平移偏移，随容器尺寸/图片初始化。
 *
 * 初始缩放保证图片两方向都覆盖圆直径并居中；手势围绕质心缩放、再平移，
 * 最后约束边界使裁剪圆内始终被图片覆盖（不露白）。
 */
@Stable
class CropState(
    private val bitmap: Bitmap,
    private val size: IntSize,
    val radius: Float,
) {
    /** 裁剪圆心（容器中心） */
    val center: Offset
        get() = Offset(size.width / 2f, size.height / 2f)

    /** 图片显示缩放比例（相对源图像素） */
    var scale by mutableFloatStateOf(0f)
        private set

    /** 图片左上角在容器中的偏移（px） */
    var offset by mutableStateOf(Offset.Zero)
        private set

    private val minScale: Float

    private val maxScale: Float
        get() = minScale * 4f

    init {
        // 初始缩放：两方向均覆盖圆直径（直径 2r），保证圆内不露白
        val initial = max(2f * radius / bitmap.width, 2f * radius / bitmap.height)
        minScale = initial
        scale = initial
        offset = Offset(
            size.width / 2f - bitmap.width * initial / 2f,
            size.height / 2f - bitmap.height * initial / 2f
        )
    }

    /**
     * 手势更新：围绕 centroid 缩放（保持质心内容不动）→ 平移 → 边界约束。
     */
    fun onTransform(centroid: Offset, pan: Offset, zoom: Float) {
        val oldScale = scale
        val newScale = (oldScale * zoom).coerceIn(minScale, maxScale)
        val k = newScale / oldScale
        offset = Offset(
            centroid.x - (centroid.x - offset.x) * k + pan.x,
            centroid.y - (centroid.y - offset.y) * k + pan.y
        )
        scale = newScale
        clampToCropBounds()
    }

    /** 约束偏移：裁剪圆外接正方形必须落在图片范围内（不允许露白）。 */
    private fun clampToCropBounds() {
        val imgWidth = bitmap.width * scale
        val imgHeight = bitmap.height * scale
        val c = center
        var dx = 0f
        var dy = 0f
        if (offset.x > c.x - radius) dx = c.x - radius - offset.x
        if (offset.x + imgWidth < c.x + radius) dx = c.x + radius - offset.x - imgWidth
        if (offset.y > c.y - radius) dy = c.y - radius - offset.y
        if (offset.y + imgHeight < c.y + radius) dy = c.y + radius - offset.y - imgHeight
        if (dx != 0f || dy != 0f) {
            offset = Offset(offset.x + dx, offset.y + dy)
        }
    }
}

/**
 * 按当前显示矩阵从源图裁出圆外接正方形区域并缩放。
 *
 * 屏幕坐标 → 输出坐标：圆外接正方形 [center±radius] 映射到 [0, outputSize]，
 * 源图经 (显示 = *scale + offset) 变换后取该区域。
 *
 * @param outputSize 输出边长（默认 200px，头像上传的标准尺寸）
 */
private fun cropBitmap(source: Bitmap, state: CropState, outputSize: Int = 200): Bitmap {
    val r = state.radius
    val c = state.center
    val s = state.scale * outputSize / (2f * r)
    val tx = (state.offset.x - (c.x - r)) * outputSize / (2f * r)
    val ty = (state.offset.y - (c.y - r)) * outputSize / (2f * r)
    val out = Bitmap.createBitmap(outputSize, outputSize, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(out)
    canvas.translate(tx, ty)
    canvas.scale(s, s)
    canvas.drawBitmap(source, 0f, 0f, null)
    return out
}

/**
 * 根据容器尺寸计算裁剪圆半径并创建手势状态。
 */
@Composable
private fun rememberCropState(bitmap: Bitmap, size: IntSize): CropState {
    val density = LocalDensity.current
    return remember(bitmap, size) {
        // 圆直径 = 短边 - 2 * 左右留白（对齐旧版 30dp 水平间距）
        val padding = with(density) { 30.dp.toPx() }
        val radius = (min(size.width, size.height) / 2f - padding).coerceAtLeast(1f)
        CropState(bitmap = bitmap, size = size, radius = radius)
    }
}
