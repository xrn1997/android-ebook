package com.ebook.book.reader

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertTrue
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 渲染回归的取色探针：把 decorView 画进 Bitmap 后数某个颜色的命中像素与外接矩形。
 *
 * 取图走 `decorView.draw(Canvas)` 而不是 compose 的 `captureToImage`：后者依赖
 * PixelCopy + frame commit 回调，Robolectric 的 paused looper 不驱动该回调，2s 后必抛超时。
 *
 * 从 ReaderChromeThemeRenderTest 抽出来共用，是因为「某个颜色角色被写回常量」这类失败
 * 在顶栏/底栏与设置面板行上是同一种形态，判据也同一套；两处各抄一遍像素扫描，
 * 早晚有一处的容差或坐标算法悄悄改了而另一处没跟上。
 *
 * 写成顶层函数而不是持 rule 的类：本仓 Compose 测试用的是
 * `androidx.compose.ui.test.junit4.v2.createAndroidComposeRule`，其返回类型与经典版不同，
 * 为套一个泛型包装去改两个测试的 rule 声明不值当——调用方各自取 decorView 传进来即可。
 */
internal fun captureDecor(decor: View): Bitmap {
    assertTrue(
        "decor 尚未布局（" + decor.width + "x" + decor.height + "），取到的像素无意义",
        decor.width > 0 && decor.height > 0
    )
    val bitmap = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
    decor.draw(Canvas(bitmap))
    return bitmap
}

/** 与 [color] 近似（容差 [ColorTolerance]）的像素的外接矩形；零命中返回空矩形 */
internal fun Bitmap.pixelBox(color: Color): PixelBox {
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

internal data class PixelBox(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val count: Int,
) {
    val width get() = right - left + 1
    val height get() = bottom - top + 1
    fun describe() = "[$left,$top→$right,$bottom] ${width}x$height ${count}px"
}

/** 探针色比对的容差（抗锯齿与色彩管理会带来 ±几的偏差） */
internal const val ColorTolerance = 6
