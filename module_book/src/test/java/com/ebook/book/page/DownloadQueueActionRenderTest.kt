package com.ebook.book.page

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * [DownloadQueueAction] 的渲染回归：书架顶栏下载角标在任意位数的剩余数下都要完整可见。
 *
 * 为什么要数像素，而不是断言语义/布局边界：角标的缺陷形态是「布局上存在、画出来被祖先 clip
 * 切掉」——material3 1.4.0 起 IconButton 的容器自带 `.clip(shape)`，而 BadgedBox 把角标放到
 * 锚点右上角之外，超出的部分只被裁掉，bounds 一切正常，故所有只看布局的断言都是绿的。
 * 这里用 Robolectric 原生图形真渲染一次，数角标底色与数字各画出了多少像素。
 *
 * 为什么自己画到软件画布：compose 的 captureToImage 走 PixelCopy + frame commit callback，
 * Robolectric 的 paused looper 不驱动该回调，2s 后必抛 ComposeTimeoutException。
 * `decorView.draw(Canvas)` 是同步的，且同样经过各级 clip，得到的就是用户会看到的那张图。
 *
 * 探针色：角标默认用 error/onError，而 onError 是白色、与整窗底色同色，分不清「数字画出来了」
 * 与「一片白底」，故把这两个色换成画面里不可能出现的颜色；裁切与配色无关，不影响结论。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class DownloadQueueActionRenderTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val remaining = mutableStateOf(0)
    private var badgeMinSizePx = 0

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun ShelfTopBar() {
        TopAppBar(
            title = { Text("我的书架") },
            actions = {
                DownloadQueueAction(remaining = remaining.value) {}
            }
        )
    }

    @Test
    fun `剩余数各位数时角标与数字都完整画出`() {
        showContent()
        for (count in listOf(3, 12, 123, 1234)) {
            remaining.value = count
            composeRule.waitForIdle()
            assertBadgeFullyPainted(captureDecor(), count.toString())
        }
    }

    @Test
    fun `没有任务时不画角标`() {
        showContent()
        remaining.value = 0
        composeRule.waitForIdle()
        val canvas = captureDecor()
        assertTrue(
            "剩余数为 0 仍画出了角标底色 ${canvas.pixelBox(ProbeBg).describe()}",
            canvas.pixelBox(ProbeBg).count == 0
        )
    }

    private fun showContent() {
        composeRule.setContent {
            MaterialTheme(colorScheme = lightColorScheme(error = ProbeBg, onError = ProbeFg)) {
                badgeMinSizePx = with(LocalDensity.current) { BadgeMinSize.roundToPx() }
                ShelfTopBar()
            }
        }
    }

    /**
     * 一个位数的数字约 11px 宽，角标胶囊约 16dp + 每增加一位加约 11px；被裁时外接矩形的右边界
     * 会钉死在 clip 线上，因此判据取「两端必须是圆角收口」：最左/最右两列的底色像素数远小于
     * 角标高度。数字像素量与文字是否落在底色内，用来兜住「整块没画」与「飘到外面」两种失败。
     */
    private fun assertBadgeFullyPainted(canvas: Bitmap, digits: String) {
        val bg = canvas.pixelBox(ProbeBg)
        val fg = canvas.pixelBox(ProbeFg)
        val report = "剩余数 $digits，画布 ${canvas.width}x${canvas.height}，" +
            "角标底色 ${bg.describe()}，数字 ${fg.describe()}" +
            "，两端列底色像素 ${bg.edgeColumnHeights(canvas).joinToString(" / ")}"

        assertTrue("角标底色一个像素都没画出来。$report", bg.count > 0)
        assertTrue("角标边长不足 ${badgeMinSizePx}px，被切成窄条。$report",
            bg.width >= badgeMinSizePx && bg.height >= badgeMinSizePx)
        assertTrue("角标贴到画布右边界，右侧被裁。$report", bg.right < canvas.width - 1)
        assertTrue(
            "角标右端是齐边的切口而非圆角收口（高度 ${bg.height}），说明被 clip 切断。$report",
            bg.edgeColumnHeights(canvas).all { it <= CapColumnMaxPx }
        )
        assertTrue(
            "数字像素量 ${fg.count} 不足（$digits 每位按 ≥${GlyphPixelsPerDigit} 计）。$report",
            fg.count >= GlyphPixelsPerDigit * digits.length
        )
        assertTrue("数字未落在角标底色内。$report", bg.contains(fg))
    }

    private fun PixelBox.edgeColumnHeights(canvas: Bitmap) =
        intArrayOf(canvas.columnHeight(ProbeBg, left), canvas.columnHeight(ProbeBg, right))

    private fun Bitmap.columnHeight(color: Color, x: Int): Int {
        var hits = 0
        for (y in 0 until height) if (matches(getPixel(x, y), color)) hits++
        return hits
    }

    private fun captureDecor(): Bitmap {
        val decor: View = composeRule.activity.window!!.decorView
        assertTrue(
            "decor 尚未布局（${decor.width}x${decor.height}），取到的像素无意义",
            decor.width > 0 && decor.height > 0
        )
        val bitmap = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
        decor.draw(Canvas(bitmap))
        return bitmap
    }

    /** 与 [color] 近似的像素的外接矩形；零命中返回空矩形，让断言先判数量再看坐标 */
    private fun Bitmap.pixelBox(color: Color): PixelBox {
        var left = Int.MAX_VALUE
        var top = Int.MAX_VALUE
        var right = 0
        var bottom = 0
        var hit = 0
        for (y in 0 until height) {
            for (x in 0 until width) {
                if (matches(getPixel(x, y), color)) {
                    hit++
                    if (x < left) left = x
                    if (x > right) right = x
                    if (y < top) top = y
                    if (y > bottom) bottom = y
                }
            }
        }
        return if (hit == 0) PixelBox(0, 0, -1, -1, 0) else PixelBox(left, top, right, bottom, hit)
    }

    private fun matches(argb: Int, color: Color): Boolean {
        val red = (color.red * 255).roundToInt()
        val green = (color.green * 255).roundToInt()
        val blue = (color.blue * 255).roundToInt()
        return abs((argb shr 16 and 0xFF) - red) <= ColorTolerance &&
            abs((argb shr 8 and 0xFF) - green) <= ColorTolerance &&
            abs((argb and 0xFF) - blue) <= ColorTolerance
    }

    private data class PixelBox(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
        val count: Int,
    ) {
        val width get() = right - left + 1
        val height get() = bottom - top + 1
        fun contains(other: PixelBox) = count > 0 && other.count > 0 &&
            other.left >= left && other.right <= right && other.top >= top && other.bottom <= bottom

        fun describe() = "[$left,$top→$right,$bottom] ${width}x$height ${count}px"
    }

    private companion object {
        val ProbeBg = Color(0xFFAC62D1)
        val ProbeFg = Color(0xFF17B8A6)

        /** 有内容的 Badge 最小边长，取 material3 BadgeTokens.LargeSize */
        val BadgeMinSize = 16.dp
        const val ColorTolerance = 6

        /** Robolectric 字体下每个数字约 40+ 像素，取一半做下限，够咬住「少画一位」 */
        const val GlyphPixelsPerDigit = 20

        /** 圆角收口处最外两列只有寥寥几个像素；齐边切口会是整列 */
        const val CapColumnMaxPx = 4
    }
}
