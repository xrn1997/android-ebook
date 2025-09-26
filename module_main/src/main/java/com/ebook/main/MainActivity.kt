package com.ebook.main

import android.view.LayoutInflater
import android.view.MenuItem
import android.view.ViewGroup
import androidx.activity.addCallback
import androidx.fragment.app.Fragment
import com.ebook.common.provider.IBookProvider
import com.ebook.common.provider.IFindProvider
import com.ebook.common.provider.IMeProvider
import com.ebook.main.databinding.ActivityMainBinding
import com.ebook.main.entity.MainChannel
import com.therouter.TheRouter
import com.xrn1997.common.mvvm.view.BaseActivity
import com.xrn1997.common.util.ToastUtil
import dagger.hilt.android.AndroidEntryPoint
import kotlin.system.exitProcess

@AndroidEntryPoint
class MainActivity : BaseActivity<ActivityMainBinding>() {
    private var mBookFragment: Fragment? = null
    private var mFindFragment: Fragment? = null
    private var mMeFragment: Fragment? = null
    private var mCurrFragment: Fragment? = null
    private var exitTime: Long = 0

    override fun enableToolbar(): Boolean {
        return false
    }

    override fun initView() {
        binding.navigationMain.setOnItemSelectedListener { menuItem: MenuItem ->
            val i = menuItem.itemId
            when (i) {
                R.id.navigation_trip -> {
                    switchContent(mCurrFragment, mBookFragment, MainChannel.BOOKSHELF.name)
                    mCurrFragment = mBookFragment
                    return@setOnItemSelectedListener true
                }

                R.id.navigation_discover -> {
                    switchContent(mCurrFragment, mFindFragment, MainChannel.BOOKSTORE.name)
                    mCurrFragment = mFindFragment
                    return@setOnItemSelectedListener true
                }

                R.id.navigation_me -> {
                    switchContent(mCurrFragment, mMeFragment, MainChannel.ME.name)
                    mCurrFragment = mMeFragment
                    return@setOnItemSelectedListener true
                }

                else -> false
            }
        }
        mBookFragment = TheRouter.get(IBookProvider::class.java)?.mainBookFragment
        mFindFragment = TheRouter.get(IFindProvider::class.java)?.mainFindFragment
        mMeFragment = TheRouter.get(IMeProvider::class.java)?.mainMeFragment
        mCurrFragment = mBookFragment
        mCurrFragment?.let {
            supportFragmentManager.beginTransaction()
                .replace(R.id.frame_content, it, MainChannel.BOOKSHELF.name).commit()
        }
        onBackPressedDispatcher.addCallback(this) {
            exit()
        }
    }

    override fun initData() {}
    override fun onBindViewBinding(
        inflater: LayoutInflater,
        parent: ViewGroup?,
        attachToParent: Boolean
    ): ActivityMainBinding {
        return ActivityMainBinding.inflate(inflater, parent, attachToParent)
    }

    private fun switchContent(from: Fragment?, to: Fragment?, tag: String?) {
        if (from == null || to == null) {
            return
        }
        val transaction = supportFragmentManager.beginTransaction()
        if (!to.isAdded) {
            transaction.hide(from).add(R.id.frame_content, to, tag).commit()
        } else {
            transaction.hide(from).show(to).commit()
        }
    }

    private fun exit() {
        if (System.currentTimeMillis() - exitTime > 2000) {
            ToastUtil.showShort(this, "再按一次退出程序")
            exitTime = System.currentTimeMillis()
        } else {
            finish()
            exitProcess(0)
        }
    }
}
