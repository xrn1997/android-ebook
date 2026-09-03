package com.ebook.common.provider

import androidx.compose.runtime.Composable

/**
 * 个人中心模块对外暴露的页面级服务。
 *
 * 返回 [@Composable] 页面（而非 Fragment），供宿主（module_main）的 NavHost 直接组合，
 * 页面 ViewModel 作用域绑定调用处的 NavBackStackEntry（hiltViewModel 默认行为）。
 */
interface IMeProvider {
    val mainMePage: @Composable () -> Unit
}
