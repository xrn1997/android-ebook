package com.ebook.find.provider

import androidx.compose.runtime.Composable
import com.ebook.common.provider.IFindProvider
import com.ebook.find.page.BookstorePage
import com.therouter.inject.ServiceProvider

/**
 * 书城页服务提供者（TheRouter SPI 注册）。
 *
 * 由宿主 module_main 经 [IFindProvider] 接口获取，[mainFindPage] 返回 Compose 页面
 * 供宿主 NavHost 直接组合。每次组合创建新页面实例，ViewModel 作用域由 hiltViewModel 决定。
 */
@ServiceProvider
class FindProvider : IFindProvider {
    override val mainFindPage: @Composable () -> Unit = {
        BookstorePage()
    }
}
