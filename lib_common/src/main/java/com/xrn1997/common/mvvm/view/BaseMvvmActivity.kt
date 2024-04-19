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
 * 基于MVVM DataBinding的Activity基类
 * @author xrn1997
 */
abstract class BaseMvvmActivity<V : ViewDataBinding, VM : BaseViewModel<*>> : BaseActivity<V>() {
    private lateinit var _binding: V
    /**
     * MVVM中的V，负责视图显示。
     * 此属性仅在onCreateView及之后的生命周期有效。
     */
    override val mBinding get() = _binding

    /**
     * MVVM中的VM，负责处理视图的操作功能，与M进行数据交互。
     */
    protected lateinit var mViewModel: VM

    override fun initContentView() {
        initViewModel()
        initView()
        initBaseViewObservable()
        initViewObservable()
    }

    private fun initViewModel() {
        _binding = DataBindingUtil.setContentView(this, onBindLayout())
        mViewModel = createViewModel()
        val viewModelId = onBindVariableId()
        _binding.setVariable(viewModelId, mViewModel)
        lifecycle.addObserver(mViewModel)
    }

    final override fun onBindViewBinding(
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
            ActivityManager.finishActivity(this@BaseMvvmActivity)
        }
        mViewModel.mUIChangeLiveData.mOnBackPressedEvent.observe(this) {
            onBackPressedDispatcher.onBackPressed() }
    }


    private fun startActivity(clz: Class<*>?, bundle: Bundle?) {
        val intent = Intent(this, clz)
        if (bundle != null) {
            intent.putExtras(bundle)
        }
        startActivity(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        mBinding.unbind()
    }
}