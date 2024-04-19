package com.xrn1997.common.mvvm.view

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.databinding.DataBindingUtil
import androidx.databinding.ViewDataBinding
import androidx.lifecycle.ViewModelProvider
import com.xrn1997.common.manager.ActivityManager
import com.xrn1997.common.mvvm.viewmodel.BaseViewModel


/**
 * 基于MVVM DataBinding的Fragment基类
 * @author xrn1997
 */
abstract class BaseMvvmFragment<V : ViewDataBinding, VM : BaseViewModel<*>> : BaseFragment<V>() {
    private lateinit var _binding: V
    /**
     * MVVM中的V，负责视图显示。
     * 此属性仅在onCreateView及之后的生命周期有效。
     */
    override val mBinding get() = _binding
    /**
     * MVVM中的VM，负责处理视图的操作功能，与M进行数据交互。
     * 在onCreateView之前的生命周期中不得使用。
     */
    protected lateinit var mViewModel: VM

    override fun initContentView(root: ViewGroup?) {
        initViewModel(root)
        initView()
        initBaseViewObservable()
        initViewObservable()
    }

    private fun initViewModel(root: ViewGroup?) {
        _binding =
            DataBindingUtil.inflate(LayoutInflater.from(mActivity), onBindLayout(), root, true)
        mViewModel = createViewModel()
        val viewModelId = onBindVariableId()
        _binding.setVariable(viewModelId, mViewModel)
        lifecycle.addObserver(mViewModel)
    }

    override fun onBindViewBinding(
        inflater: LayoutInflater,
        container: ViewGroup?,
        attachToParent: Boolean
    ): V {
        return mBinding
    }

    open fun createViewModel(): VM {
        return ViewModelProvider(this, onBindViewModelFactory())[onBindViewModel()]
    }

    abstract fun initViewObservable()

    /**
     * 绑定layout
     * @return Int
     */
    abstract fun onBindLayout(): Int
    abstract fun onBindViewModel(): Class<VM>
    abstract fun onBindViewModelFactory(): ViewModelProvider.Factory
    abstract fun onBindVariableId(): Int

    protected open fun initBaseViewObservable() {
        mViewModel.mUIChangeLiveData.mShowLoadingViewEvent.observe(this) { show ->
            showLoadingView(show!!)
        }
        mViewModel.mUIChangeLiveData.mShowNoDataViewEvent.observe(this) { show ->
            showNoDataView(show!!)
        }
        mViewModel.mUIChangeLiveData.mShowNetWorkErrViewEvent.observe(this) { show ->
            showNetWorkErrView(show!!)
        }
        mViewModel.mUIChangeLiveData.mStartActivityEvent.observe(this) { params ->
            val clz = params!![BaseViewModel.Companion.ParameterField.CLASS] as Class<*>?
            val bundle: Bundle? = params[BaseViewModel.Companion.ParameterField.BUNDLE] as Bundle?
            startActivity(clz, bundle)
        }
        mViewModel.mUIChangeLiveData.mFinishActivityEvent.observe(this) {
            ActivityManager.finishActivity(mActivity)
        }
        mViewModel.mUIChangeLiveData.mOnBackPressedEvent.observe(this) {
            mActivity.onBackPressedDispatcher.onBackPressed()
        }
    }

    fun startActivity(clz: Class<*>?, bundle: Bundle?) {
        val intent = Intent(mActivity, clz)
        if (bundle != null) {
            intent.putExtras(bundle)
        }
        startActivity(intent)
    }

}