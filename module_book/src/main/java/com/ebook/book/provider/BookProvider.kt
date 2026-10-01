package com.ebook.book.provider

import androidx.compose.runtime.Composable
import com.ebook.book.page.BookShelfPage
import com.ebook.common.provider.IBookProvider
import com.therouter.inject.ServiceProvider

@ServiceProvider
class BookProvider : IBookProvider {
    // Compose 页面（@Composable (T) -> Unit）由宿主 NavHost 直接组合，
    // 每次组合创建新的页面实例（ViewModel 作用域由 hiltViewModel 决定）；
    // onGoBookstore 是宿主递进来的「切到书城 Tab」能力，独立运行的调试宿主传 null
    override val mainBookPage: @Composable (onGoBookstore: (() -> Unit)?) -> Unit = { onGoBookstore ->
        BookShelfPage(onGoBookstore = onGoBookstore)
    }
}
