package com.xrn1997.common.mvvm.view

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewStub
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.appcompat.widget.Toolbar
import androidx.viewbinding.ViewBinding
import com.blankj.utilcode.util.NetworkUtils
import com.trello.rxlifecycle4.components.support.RxAppCompatActivity
import com.trello.rxlifecycle4.components.support.RxFragment
import com.xrn1997.common.R
import com.xrn1997.common.databinding.FragmentRootBinding
import com.xrn1997.common.event.BaseFragmentEvent
import com.xrn1997.common.mvvm.IBaseView
import com.xrn1997.common.view.LoadingView
import com.xrn1997.common.view.NetErrorView
import com.xrn1997.common.view.NoDataView
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode


/**
 * 基于ViewBinding的Fragment基类
 * @author xrn1997
 */
@Suppress("unused")
abstract class BaseFragment<V : ViewBinding> : RxFragment(), IBaseView {
    protected lateinit var mActivity: RxAppCompatActivity
    private var mViewStubContent: RelativeLayout? = null
    protected var mNetErrorView: NetErrorView? = null
    protected var mNoDataView: NoDataView? = null
    protected var mLoadingView: LoadingView? = null
    protected var mToolbar: Toolbar? = null

    private lateinit var mViewStubToolbar: ViewStub
    private lateinit var mViewStubLoading: ViewStub
    private lateinit var mViewStubNoData: ViewStub
    private lateinit var mViewStubError: ViewStub

    private lateinit var _binding: V

    /**
     * 此属性仅在onCreateView及之后的生命周期有效。
     * 请注意不要随便覆写。
     */
    protected open val binding get() = _binding

    /**
     * 默认toolBarTitle
     */
    open var toolBarTitle: String = ""
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mActivity = activity as RxAppCompatActivity
        EventBus.getDefault().register(this)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val mBinding = FragmentRootBinding.inflate(inflater, container, false)
        initCommonView(mBinding)
        return mBinding.root
    }


    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initData()
    }

    open fun initCommonView(binding: FragmentRootBinding) {
        mViewStubToolbar = binding.vsToolbar
        mViewStubContent = binding.rlContent
        mViewStubLoading = binding.vsLoading
        mViewStubError = binding.vsError
        mViewStubNoData = binding.vsNoData
        binding.parentLayout.fitsSystemWindows = enableFitsSystemWindows()
        if (enableToolbar()) {
            mViewStubToolbar.layoutResource = onBindToolbarLayout()
            val view = mViewStubToolbar.inflate()
            initToolbar(view)
        }
        initContentView(mViewStubContent)
    }

    /**
     * 给根布局设置fitsSystemWindows，默认false
     */
    open fun enableFitsSystemWindows(): Boolean {
        return false
    }

    /**
     * 绑定toolbar layout
     * @return Int
     */
    open fun onBindToolbarLayout(): Int {
        return R.layout.view_toolbar
    }

    open fun initContentView(root: ViewGroup?) {
        _binding = onBindViewBinding(LayoutInflater.from(mActivity), root, true)
        initView()
    }

    /**
     * 初始化toolbar，可重写，如果enableToolbar()返回false，则该
     * 项不起作用。
     * @see BaseActivity.enableToolbar
     */
    open fun initToolbar(view: View) {
        val mToolBarTitle: TextView? = view.findViewById(R.id.toolbar_title)
        mToolbar = view.findViewById(R.id.toolbar_root)
        if (mToolbar != null) {
            mActivity.setSupportActionBar(mToolbar)
            mActivity.supportActionBar?.setDisplayShowTitleEnabled(false)
            mToolbar?.setNavigationOnClickListener { mActivity.onBackPressedDispatcher.onBackPressed() }
        }
        if (mToolBarTitle != null) {
            mToolBarTitle.text = toolBarTitle
        }
    }

    /**
     * 是否启用toolbar，默认false
     * @return Boolean
     */
    open fun enableToolbar(): Boolean {
        return false
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
        mActivity.finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        EventBus.getDefault().unregister(this)
    }

    override fun showLoadingView(show: Boolean) {
        if (mLoadingView == null) {
            val view: View = mViewStubLoading.inflate()
            mLoadingView = view.findViewById(R.id.view_loading)
        }
        mLoadingView?.show(show)
    }

    override fun showNoDataView(show: Boolean, resId: Int?) {
        if (mNetErrorView == null) {
            val view: View = mViewStubNoData.inflate()
            mNoDataView = view.findViewById(R.id.ndv_no_data)
        }
        if (resId != null) {
            mNoDataView?.setNoDataView(resId)
        }
        mNoDataView?.show(show)
    }

    override fun showNetWorkErrView(show: Boolean, resId: Int?) {
        if (mNetErrorView == null) {
            val view: View = mViewStubError.inflate()
            mNetErrorView = view.findViewById(R.id.nev_net_error)
            mNetErrorView?.setRefreshBtnClickListener {
                NetworkUtils.isAvailableAsync {
                    if (it) {
                        mNetErrorView?.visibility = View.GONE
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
     * @param event BaseFragmentEvent<T>?
     */
    @Subscribe(threadMode = ThreadMode.MAIN)
    open fun <T> onEvent(event: BaseFragmentEvent<T>?) {
        Log.d(TAG, "onEvent: $event")
    }

    companion object {
        private const val TAG = "BaseActivity"
    }
}