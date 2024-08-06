package com.xrn1997.common.mvvm.compose

import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.scwang.smart.refresh.footer.ClassicsFooter
import com.scwang.smart.refresh.header.ClassicsHeader
import com.scwang.smart.refresh.layout.SmartRefreshLayout
import com.scwang.smart.refresh.layout.api.RefreshFooter
import com.scwang.smart.refresh.layout.api.RefreshHeader
import com.scwang.smart.refresh.layout.api.RefreshLayout
import com.scwang.smart.refresh.layout.listener.ScrollBoundaryDecider
import com.xrn1997.common.mvvm.viewmodel.BaseRefreshViewModel


/**
 * 基于Compose、 MVVM的可刷新的Activity基类
 * @author xrn1997
 */
abstract class BaseMvvmRefreshActivity<VM : BaseRefreshViewModel<*, *>> : BaseMvvmActivity<VM>() {
    protected lateinit var mRefreshLayout: RefreshLayout
    override fun initBaseViewObservable() {
        super.initBaseViewObservable()
        initBaseViewRefreshObservable()
    }

    private fun initBaseViewRefreshObservable() {
        mViewModel.mUIChangeRefreshLiveData.mAutoRefreshLiveEvent.observe(this) {
            autoLoadData()
        }
        mViewModel.mUIChangeRefreshLiveData.mStopRefreshLiveEvent.observe(this) { success ->
            stopRefresh(success!!)
        }
        mViewModel.mUIChangeRefreshLiveData.mStopLoadMoreLiveEvent.observe(this) { success ->
            stopLoadMore(success!!)
        }
    }

    /**
     * 完成加载
     * @param success Boolean 数据是否成功刷新 （会影响到上次更新时间的改变）
     * @see RefreshLayout.finishRefresh
     */
    open fun stopRefresh(success: Boolean) {
        mRefreshLayout.finishRefresh(success)
    }

    /**
     * 完成加载
     * @param success Boolean 数据是否成功
     * @see RefreshLayout.finishLoadMore
     */
    open fun stopLoadMore(success: Boolean) {
        mRefreshLayout.finishLoadMore(success)
    }

    /**
     * 显示刷新动画并且触发刷新事件
     * @see RefreshLayout.autoRefresh
     */
    open fun autoLoadData() {
        mRefreshLayout.autoRefresh()
    }

    @Composable
    override fun HomePage(modifier: Modifier) {
        val state = rememberLazyListState()
        RefreshLayout(
            viewModel = mViewModel,
            header = { setHeaderView() },
            body = {
                InitView(state)
            },
            footer = { setRooterView() },
            modifier = modifier,
            state = state
        )
    }

    /**
     * 此抽象方法在[HomePage]中执行.
     */
    @Composable
    abstract fun InitView(state: LazyListState)

    @Composable
    final override fun InitView() {
    }

    open fun setHeaderView(): ClassicsHeader {
        return ClassicsHeader(getContext())
    }

    open fun setRooterView(): ClassicsFooter {
        return ClassicsFooter(getContext())
    }

    /**
     * 基于SmartRefreshLayout的Compose调用
     * @param viewModel 刷新视图模型
     * @param header Compose函数,用于头部视图
     * @param body Compose函数,用于主体视图
     * @param footer Compose函数,用于底部视图
     * @param modifier Modifier
     * @param state  使用LazyListState的控件必须要传,不然会出现嵌套冲突
     */
    @Composable
    fun RefreshLayout(
        viewModel: BaseRefreshViewModel<*, *>,
        header: () -> RefreshHeader,
        body: @Composable () -> Unit,
        footer: () -> RefreshFooter,
        modifier: Modifier = Modifier,
        state: LazyListState? = null
    ) {
        val enableLoadMore by viewModel.enableLoadMore.observeAsState(false)
        val enableRefresh by viewModel.enableRefresh.observeAsState(false)
        val curContext = LocalContext.current

        // 使用ComposeView将Compose布局渲染为Android View
        val bodyView = remember { ComposeView(curContext) }

        bodyView.setContent {
            Box {
                body()
                LoadingView()
                NoDataView(Modifier.matchParentSize())
                NetworkErrorView(Modifier.matchParentSize())
            }
        }
        val canRefresh by remember {
            derivedStateOf {
                state?.firstVisibleItemScrollOffset == 0
            }
        }
        val canLoadMore by remember {
            derivedStateOf {
                return@derivedStateOf if (state != null) {
                    val isLastIndex =
                        (state.layoutInfo.visibleItemsInfo.last().index == state.layoutInfo.totalItemsCount - 1)
                    val isLastItemCompleteVisible =
                        (state.layoutInfo.visibleItemsInfo.last().offset * state.layoutInfo.visibleItemsInfo.size + state.firstVisibleItemScrollOffset
                                == state.layoutInfo.viewportEndOffset * (state.layoutInfo.visibleItemsInfo.size - 1))
                    isLastIndex && isLastItemCompleteVisible
                } else {
                    false
                }
            }
        }
        // 使用AndroidView将SmartRefreshLayout添加到Compose布局中
        AndroidView(modifier = modifier.fillMaxSize(), factory = { context ->
            SmartRefreshLayout(context).apply {
                setOnLoadMoreListener { viewModel.onLoadMoreCommand.execute() }
                setOnRefreshListener { viewModel.onRefreshCommand.execute() }
                setRefreshHeader(header())
                setRefreshFooter(footer())
                setRefreshContent(bodyView)

                setScrollBoundaryDecider(object : ScrollBoundaryDecider {
                    override fun canRefresh(content: View?): Boolean = canRefresh
                    override fun canLoadMore(content: View?): Boolean = canLoadMore
                })
            }
        }, update = { view ->
            view.setEnableLoadMore(enableLoadMore ?: false)
            view.setEnableRefresh(enableRefresh ?: false)
            mRefreshLayout = view
        }, onReset = {

        })
    }
}


