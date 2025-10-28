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
    private var mCurrFragment: Fragment? = null
    private var exitTime: Long = 0

    override fun enableToolbar(): Boolean {
        return false
    }

    override fun initView() {
        mCurrFragment = supportFragmentManager.findFragmentById(R.id.frame_content)
        if (mCurrFragment == null) {
            // Activity 首次创建，显示书架 Fragment
            mCurrFragment = TheRouter.get(IBookProvider::class.java)?.mainBookFragment
            mCurrFragment?.let {
                supportFragmentManager.beginTransaction()
                    .replace(R.id.frame_content, it, MainChannel.BOOKSHELF.name)
                    .commit()
            }
        }
        binding.navigationMain.setOnItemSelectedListener { menuItem: MenuItem ->
            val tag: String
            val to: Fragment?
            when (menuItem.itemId) {
                R.id.navigation_trip -> {
                    tag = MainChannel.BOOKSHELF.name
                    to = TheRouter.get(IBookProvider::class.java)?.mainBookFragment
                }

                R.id.navigation_discover -> {
                    tag = MainChannel.BOOKSTORE.name
                    to = TheRouter.get(IFindProvider::class.java)?.mainFindFragment
                }

                R.id.navigation_me -> {
                    tag = MainChannel.ME.name
                    to = TheRouter.get(IMeProvider::class.java)?.mainMeFragment
                }

                else -> return@setOnItemSelectedListener false
            }
            switchContent(mCurrFragment, to, tag)
            mCurrFragment = to
            return@setOnItemSelectedListener true
        }
        onBackPressedDispatcher.addCallback(this) { exit() }
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
