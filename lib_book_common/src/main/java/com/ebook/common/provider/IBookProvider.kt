package com.ebook.common.provider

import androidx.compose.runtime.Composable

/**
 * 书架模块对外暴露的页面级服务。
 *
 * 返回 [@Composable] 页面（而非 Fragment），供宿主（module_main）的 NavHost 直接组合，
 * 页面 ViewModel 作用域绑定调用处的 NavBackStackEntry（hiltViewModel 默认行为）。
 */
interface IBookProvider {
    /**
     * 书架主页面。
     *
     * @param onGoBookstore 切到书城 Tab 的回调，由宿主注入。为什么会走到这里：书架空态要一句
     *   「去书城找书」，而书城是宿主 NavHost 的目的地（`Screen.Bookstore.route`），
     *   模块内既没有 NavController 也没有那条路由，只能由知道它的宿主把能力递进来。
     *   传 `null` 表示当前宿主没有书城 Tab（模块独立运行时的调试宿主，
     *   与「跨模块路由在独立模式下静默丢失」同一处置口径）：调用方据此**不渲染**该动作，
     *   而不是画一个点不动的按钮。
     */
    val mainBookPage: @Composable (onGoBookstore: (() -> Unit)?) -> Unit
}
