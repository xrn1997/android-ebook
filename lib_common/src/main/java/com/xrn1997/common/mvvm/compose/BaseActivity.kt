package com.xrn1997.common.mvvm.compose

import android.content.Context
import android.os.Bundle
import android.text.TextUtils
import android.util.Log
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import com.blankj.utilcode.util.NetworkUtils
import com.trello.rxlifecycle4.components.support.RxAppCompatActivity
import com.xrn1997.common.R
import com.xrn1997.common.event.BaseComposeActivityEvent
import com.xrn1997.common.manager.ActivityManager
import com.xrn1997.common.mvvm.IBaseView
import com.xrn1997.common.ui.LoadingView
import com.xrn1997.common.ui.NetworkErrorView
import com.xrn1997.common.ui.NoDataView
import com.xrn1997.common.ui.theme.MyApplicationTheme
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode

/**
 * 基于Compose的Activity基类。'
 * @author xrn1997
 */
abstract class BaseActivity : RxAppCompatActivity(), IBaseView {

    /**加载页面状态*/
    private var loadingState = mutableStateOf(false)

    /**网络错误页面状态*/
    private var networkErrorState = mutableStateOf(false)

    /**没有数据页面状态*/
    private var noDataState = mutableStateOf(false)

    /**没有数据页展示的图片*/
    private var noDataImage = mutableIntStateOf(R.drawable.no_data)

    /**网络错误页展示的图片*/
    private var noNetworkErrorImage = mutableIntStateOf(R.drawable.no_network)

    /**
     * 默认toolBarTitle，并且设置完成后，通过setTitle是无法修改的。
     * @see setTitle
     */
    open var toolBarTitle: String = ""
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        EventBus.getDefault().register(this)
        setContent {
            InitCommonView()
        }
        initContentView()
        initData()
        ActivityManager.addActivity(this)
    }

    /**
     * 在onCreate中调用，不强制覆盖。
     */
    override fun initView() {}

    /**
     * 初始化需要再onCreate调用的页面
     */
    open fun initContentView() {
        initView()
    }

    /**
     * 是否启用toolbar，默认true
     * @return Boolean
     */
    open fun enableToolbar(): Boolean {
        return true
    }


    override fun onTitleChanged(title: CharSequence?, color: Int) {
        super.onTitleChanged(title, color)
        //android:label 默认title
        if (!TextUtils.isEmpty(title) && toolBarTitle.isEmpty()) {
            toolBarTitle = title.toString()
        }
    }

    override fun finishActivity() {
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        EventBus.getDefault().unregister(this)
        ActivityManager.removeActivity(this)
    }

    override fun getContext(): Context? {
        return this
    }

    override fun showLoadingView(show: Boolean) {
        loadingState.value = show
    }

    override fun showNoDataView(show: Boolean, resId: Int?) {
        if (resId != null) {
            noDataImage.intValue = resId
        }
        noDataState.value = show
    }

    override fun showNetWorkErrView(show: Boolean, resId: Int?) {
        if (resId != null) {
            noNetworkErrorImage.intValue = resId
        }
        networkErrorState.value = show
    }

    companion object {
        const val TAG = "Compose BaseActivity"
    }

    /**
     * 如有必要，可以用EventBus传值调用BaseActivity中的方法
     * @param event BaseComposeActivityEvent<T>?
     */
    @Subscribe(threadMode = ThreadMode.MAIN)
    open fun <T> onEvent(event: BaseComposeActivityEvent<T>?) {
        Log.d(TAG, "onEvent: $event")
    }

    /**
     * Compose Body，不含头，不含尾
     */
    @Composable
    abstract fun InitView()

    /**
     * 初始化基础页面
     */
    @Composable
    open fun InitCommonView() {
        MyApplicationTheme {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background
            ) {
                Scaffold(
                    topBar = {
                        if (enableToolbar()) {
                            OnBindToolbarLayout()
                        }
                    },
                    bottomBar = {
                        OnBindBottomBarLayout()
                    }
                ) { innerPadding ->
                    HomePage(
                        Modifier
                            .padding(innerPadding)
                            .fillMaxSize()
                    )
                }

            }
        }
    }

    /**
     * 默认为空
     */
    @Composable
    open fun OnBindBottomBarLayout() {
    }

    @Composable
    open fun HomePage(modifier: Modifier) {
        Box(
            modifier = modifier
        ) {
            InitView()
            LoadingView()
            NoDataView(Modifier.matchParentSize())
            NetworkErrorView(Modifier.matchParentSize())
        }
    }

    @Composable
    fun LoadingView() {
        val loading by remember { loadingState }
        LoadingView(
            loading,
            modifier = Modifier
                .wrapContentSize()
        )
    }

    @Composable
    fun NoDataView(modifier: Modifier = Modifier) {
        val noData by remember { noDataState }
        val noDataImage by remember { noDataImage }

        NoDataView(
            noData,
            modifier = modifier
                .background(MaterialTheme.colorScheme.background),
            res = noDataImage
        )
    }

    @Composable
    fun NetworkErrorView(modifier: Modifier = Modifier) {
        val networkError by remember { networkErrorState }
        val noNetworkErrorImage by remember { noNetworkErrorImage }

        NetworkErrorView(
            networkError,
            modifier = modifier
                .background(MaterialTheme.colorScheme.background),
            onClick = {
                NetworkUtils.isAvailableAsync {
                    if (it) {
                        networkErrorState.value = false
                        initData()
                    }
                }
            },
            res = noNetworkErrorImage
        )
    }


    /**
     * 设置toolbar
     * @return Int
     */
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    open fun OnBindToolbarLayout() {
        CenterAlignedTopAppBar(
            title = {
                Text(
                    toolBarTitle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.primary,
                titleContentColor = MaterialTheme.colorScheme.onPrimary,
            ),
        )
    }
}