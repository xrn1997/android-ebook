package com.ebook.book.provider

import androidx.compose.runtime.Composable
import com.ebook.book.page.BookShelfPage
import com.ebook.common.provider.IBookProvider
import com.therouter.inject.ServiceProvider

@ServiceProvider
class BookProvider : IBookProvider {
    // Compose 页面（@Composable () -> Unit）由宿主 NavHost 直接组合，
    // 每次组合创建新的页面实例（ViewModel 作用域由 hiltViewModel 决定）
    override val mainBookPage: @Composable () -> Unit = {
        BookShelfPage()
    }
}
