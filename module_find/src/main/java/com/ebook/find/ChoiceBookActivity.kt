package com.ebook.find

import android.os.Bundle
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.ebook.common.event.FROM_SEARCH
import com.ebook.common.event.KeyCode
import com.ebook.common.ui.CommonUiTokens
import com.ebook.find.mvvm.viewmodel.ChoiceBookViewModel
import com.ebook.find.view.SearchBookItem
import com.therouter.TheRouter
import com.therouter.router.Route
import com.xrn1997.common.mvvm.compose.BaseMvvmRefreshActivity
import com.xrn1997.common.ui.LoadMoreFooter
import dagger.hilt.android.AndroidEntryPoint

/**
 * 分类书籍列表页（Compose 基类，经 [KeyCode.Find.CHOICE_PATH] 路由进入）。
 *
 * - 外壳：lib_common Compose 基类统一提供 Toolbar/刷新容器/加载与空态覆盖层
 * - 参数：路由 withString 的 url/title 最终落到 intent.extras。url 由
 *   [ChoiceBookViewModel] 经 SavedStateHandle 读取（见 VM KDoc），title 在
 *   [onCreate] 直接读 extras 设工具栏标题（纯 View 状态，留在 View 层）
 *   （不用 TheRouter @Autowired 注入：其生成代码对 String 产生"No cast needed"新警告）
 */
@AndroidEntryPoint
@Route(path = KeyCode.Find.CHOICE_PATH)
class ChoiceBookActivity : BaseMvvmRefreshActivity<ChoiceBookViewModel>() {
    protected override val viewModel: ChoiceBookViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        toolbarTitle.value = intent.extras?.getString("title").orEmpty()
        super.onCreate(savedInstanceState)
    }

    override fun enableLoadMore(): Boolean = true

    @Composable
    override fun PageContent(state: LazyListState) {
        val books by viewModel.list.collectAsState()
        // 加载更多底部状态（ADR-0041）：由基类渲染镜像推导，失败可点重试
        val loadingMore by remember { isLoadingMoreState }
        val loadMoreFailed by remember { loadMoreFailedState }
        val hasMore by remember { hasMoreDataState }
        LazyColumn(
            state = state,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(
                start = CommonUiTokens.pagePadding,
                top = CommonUiTokens.sectionSpacing,
                end = CommonUiTokens.pagePadding,
                bottom = CommonUiTokens.pagePadding
            ),
            // 条目为独立圆角卡片（见 SearchBookItem），用间距分隔替代条目内分割线
            verticalArrangement = Arrangement.spacedBy(CommonUiTokens.listSpacing)
        ) {
            items(books, key = { it.noteUrl }) { searchBook ->
                SearchBookItem(
                    searchBook = searchBook,
                    onItemClick = {
                        TheRouter.build(KeyCode.Book.DETAIL_PATH)
                            .withInt("from", FROM_SEARCH)
                            .withObject("data", searchBook)
                            .navigation(this@ChoiceBookActivity)
                    },
                    onAddShelf = { viewModel.addBookToShelf(searchBook) }
                )
            }
            // 触底加载反馈：加载中 / 失败重试 / 没有更多（ADR-0041）
            item {
                LoadMoreFooter(
                    isLoadingMore = loadingMore,
                    loadMoreFailed = loadMoreFailed,
                    hasMoreData = hasMore,
                    onRetry = { retryLoadMore() },
                )
            }
        }
    }
}
