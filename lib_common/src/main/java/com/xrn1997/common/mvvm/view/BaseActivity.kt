package com.xrn1997.common.mvvm.view


import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewStub
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.annotation.LayoutRes
import androidx.appcompat.widget.Toolbar
import androidx.databinding.DataBindingUtil
import androidx.viewbinding.ViewBinding
import com.blankj.utilcode.util.NetworkUtils
import com.trello.rxlifecycle4.components.support.RxAppCompatActivity
import com.xrn1997.common.R
import com.xrn1997.common.databinding.ActivityRootBinding
import com.xrn1997.common.event.BaseActivityEvent
import com.xrn1997.common.manager.ActivityManager
import com.xrn1997.common.mvvm.IBaseView
import com.xrn1997.common.view.LoadingView
import com.xrn1997.common.view.NetErrorView
import com.xrn1997.common.view.NoDataView
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode

/**
 * 基于ViewBinding的Activity基类
 * @author xrn1997
 */
@Suppress("unused")
abstract class BaseActivity<V : ViewBinding> : RxAppCompatActivity(), IBaseView {
    private lateinit var mContentView: ViewGroup
    private var mViewStubContent: RelativeLayout? = null
    private var mToolBarTitle: TextView? = null

    protected var mNetErrorView: NetErrorView? = null
    protected var mNoDataView: NoDataView? = null
    protected var mLoadingView: LoadingView? = null
    protected var mToolbar: Toolbar? = null

    private lateinit var mViewStubToolbar: ViewStub
    private lateinit var mViewStubLoading: ViewStub
    private lateinit var mViewStubNoData: ViewStub
    private lateinit var mViewStubError: ViewStub

    private var _binding: V? = null

    /**
     * 该binding仅用于取代findViewById
     */
    protected open val mBinding get() = _binding!!

    /**
     * 默认toolBarTitle，并且设置完成后，通过setTitle是无法修改的。
     * @see setTitle
     */
    open var toolBarTitle: String = ""
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mBinding = ActivityRootBinding.inflate(layoutInflater)
        val rootView: View = mBinding.root
        super.setContentView(rootView)
        mContentView = findViewById(android.R.id.content)
        EventBus.getDefault().register(this)
        initCommonView(mBinding)
        initData()
        ActivityManager.addActivity(this)
    }

    /**
     * 注意，这里重写，是为了让[DataBindingUtil.setContentView]起作用
     * 大坑，不重写就寄。
     * @param layoutResID Int
     */
    final override fun setContentView(@LayoutRes layoutResID: Int) {
        if (mViewStubContent != null) {
            initContentView(layoutResID)
        }
    }

    private fun initCommonView(binding: ActivityRootBinding) {
        mViewStubToolbar = binding.vsToolbar
        mViewStubContent = binding.rlContent
        mViewStubLoading = binding.vsLoading
        mViewStubError = binding.vsError
        mViewStubNoData = binding.vsNoData
        if (enableToolbar()) {
            mViewStubToolbar.layoutResource = onBindToolbarLayout()
            val view = mViewStubToolbar.inflate()
            initToolbar(view)
        }
        initContentView()
    }

    /**
     * 绑定toolbar layout
     * @return Int
     */
    open fun onBindToolbarLayout(): Int {
        return R.layout.view_toolbar
    }

    open fun initContentView() {
        _binding = onBindViewBinding(LayoutInflater.from(this), mViewStubContent, false)
        initView()
        mViewStubContent?.id = android.R.id.content
        mContentView.id = View.NO_ID
        mViewStubContent?.removeAllViews()
        mViewStubContent?.addView(mBinding.root)
    }

    private fun initContentView(@LayoutRes layoutResID: Int) {
        val view: View = LayoutInflater.from(this).inflate(layoutResID, mViewStubContent, false)
        mViewStubContent?.id = android.R.id.content
        mContentView.id = View.NO_ID
        mViewStubContent?.removeAllViews()
        mViewStubContent?.addView(view)
    }

    /**
     * 初始化toolbar，可重写，如果enableToolbar()返回false，则该
     * 项不起作用。
     * @see BaseActivity.enableToolbar
     */
    open fun initToolbar(view: View) {
        mToolBarTitle = view.findViewById(R.id.toolbar_title)
        mToolbar = view.findViewById(R.id.toolbar_root)
        if (mToolbar != null) {
            setSupportActionBar(mToolbar)
            supportActionBar?.setDisplayShowTitleEnabled(false)
            mToolbar?.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
        }
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
        if (!title.isNullOrEmpty()) {
            mToolBarTitle?.text = title
        }
        // 自定义title
        if (toolBarTitle.isNotEmpty()) {
            mToolBarTitle?.text = toolBarTitle
        }
    }

    abstract override fun initView()

    abstract override fun initData()

    /**
     * 这个方法返回需要绑定的ViewBinding
     * @param inflater LayoutInflater
     * @param container ViewGroup? 整合到哪
     * @param attachToParent Boolean  是否整合到Parent上
     * @return V
     */
    abstract fun onBindViewBinding(
        inflater: LayoutInflater,
        container: ViewGroup?,
        attachToParent: Boolean
    ): V

    override fun finishActivity() {
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        EventBus.getDefault().unregister(this)
        ActivityManager.finishActivity(this)
    }

    override fun getContext(): Context? {
        return this
    }

    override fun showLoadingView(show: Boolean) {
        if (mLoadingView == null) {
            val view: View = mViewStubLoading.inflate()
            mLoadingView = view.findViewById(R.id.view_loading)
        }
        mLoadingView?.visibility = if (show) View.VISIBLE else View.GONE
        mLoadingView?.show(show)
    }


    override fun showNoDataView(show: Boolean, resId: Int?) {
        if (mNoDataView == null) {
            val view = mViewStubNoData.inflate()
            mNoDataView = view.findViewById(R.id.ndv_no_data)
        }
        if (resId != null) {
            mNoDataView?.setNoDataView(resId)
        }
        mNoDataView?.show(show)
    }

    override fun showNetWorkErrView(show: Boolean, resId: Int?) {
        if (mNetErrorView == null) {
            val view = mViewStubError.inflate()
            mNetErrorView = view.findViewById(R.id.nev_net_error)
            mNetErrorView?.setRefreshBtnClickListener {
                NetworkUtils.isAvailableAsync {
                    if (it) {
                        mNetErrorView!!.visibility = View.GONE
                        initData()
                    }
                }
            }
        }
        if (resId != null) {
            mNetErrorView?.setNetErrorView(resId)
        }
        mNetErrorView?.show(show)
    }

    /**
     * 如有必要，可以用EventBus传值调用BaseActivity中的方法
     * @param event BaseActivityEvent<T>?
     */
    @Subscribe(threadMode = ThreadMode.MAIN)
    open fun <T> onEvent(event: BaseActivityEvent<T>?) {
        Log.d(TAG, "onEvent: $event")
    }

    companion object {
        private const val TAG = "BaseActivity"
    }
}