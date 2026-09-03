package com.ebook.me.provider

import androidx.compose.runtime.Composable
import com.ebook.common.provider.IMeProvider
import com.ebook.me.page.MePage
import com.therouter.inject.ServiceProvider


/**
 * 我的页服务提供者（TheRouter SPI 注册）。
 *
 * 由宿主 module_main 经 [IMeProvider] 接口获取，[mainMePage] 返回 Compose 页面
 * 供宿主 NavHost 直接组合。每次组合创建新页面实例，ViewModel 作用域由 hiltViewModel 决定。
 */
@ServiceProvider
class MeProvider : IMeProvider {
    override val mainMePage: @Composable () -> Unit = {
        MePage()
    }
}
