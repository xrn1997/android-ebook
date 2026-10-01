package com.ebook.common.ui.preview

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import com.xrn1997.common.ui.theme.MyApplicationTheme

/**
 * 预览（与非设备渲染环境）统一使用的主题包装。
 *
 * 存在的理由：预览里不套主题，组件取到的就是 Material3 的出厂默认调板，看到的配色与 App
 * 实际渲染完全不是一回事——这类偏差不会报错，只会让人按一张错的图改样式。
 *
 * **不套 [com.xrn1997.common.ui.theme.AppTheme.Content]**：全局主题的装配发生在
 * `Application.onCreate`（见 `lib_book_common` 的 `BookApplication`），而预览渲染环境不保证
 * 执行它。把配色的来源交给装配点，等于让同一份代码在"预览跑不跑 Application"两种模式下
 * 出两种结果。预览一律显式定主题，输出才可预期。
 *
 * **`dynamicColor` 关到 false**：[MyApplicationTheme] 默认开动态取色，在 API 31+ 走壁纸调色板。
 * 预览里取到的是渲染宿主机的那份调色板，于是同一屏在不同机器上长得不一样，改前改后的对比
 * 也失去基准。静态调板（lib_common 的 Light/DarkColors）才是可比的。
 * 真机动态取色那一档需要看时，用官方 `@PreviewDynamicColors`（四种壁纸主色各一张），
 * 而不是把这里改回 true。
 *
 * **`darkTheme` 默认跟随 `isSystemInDarkTheme()`**：运行时「跟随系统」这一档用的就是同一个
 * 判定（`BookApplication` 里 `ThemeMode.SYSTEM -> isSystemInDarkTheme()`）。深浅色交给官方
 * `@PreviewLightDark`（它把 `uiMode` 置为 night，从而翻掉这个判定），预览函数就不必自己写
 * `darkTheme = true`——手写的那份写错了不报错，只是这张图一直在看错的配色。
 *
 * 品牌色策略将来落地时（`BookApplication` 已预告"固定品牌色只需替换 dynamicColor"），
 * 这里是预览侧的唯一改动点，与装配点同源，不必逐页跟改。
 */
@Composable
fun AppPreview(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MyApplicationTheme(
        darkTheme = darkTheme,
        dynamicColor = false,
        content = content,
    )
}
