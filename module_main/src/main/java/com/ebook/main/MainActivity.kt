package com.ebook.main

import android.view.KeyEvent
import android.view.MenuItem
import androidx.fragment.app.Fragment
import com.ebook.common.mvvm.BaseActivity
import com.ebook.common.provider.IBookProvider
import com.ebook.common.provider.IFindProvider
import com.ebook.common.provider.IMeProvider
import com.ebook.common.util.ToastUtil
import com.ebook.main.entity.MainChannel
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.therouter.TheRouter


class MainActivity : BaseActivity() {
    private var mBookFragment: Fragment? = null
    private var mFindFragment: Fragment? = null
    private var mMeFragment: Fragment? = null
    private var mCurrFragment: Fragment? = null
    private var exitTime: Long = 0
    override fun onBindLayout(): Int {
        return R.layout.activity_main
    }

    override fun enableToolbar(): Boolean {
        return false
    }

    override fun initView() {
        val navigation = findViewById<BottomNavigationView>(R.id.navigation_main)
        navigation.setOnNavigationItemSelectedListener { menuItem: MenuItem ->
            val i = menuItem.itemId
            if (i == R.id.navigation_trip) {
                switchContent(mCurrFragment, mBookFragment, MainChannel.BOOKSHELF.name)
                mCurrFragment = mBookFragment
                return@setOnNavigationItemSelectedListener true
            } else if (i == R.id.navigation_discover) {
                switchContent(mCurrFragment, mFindFragment, MainChannel.BOOKSTORE.name)
                mCurrFragment = mFindFragment
                return@setOnNavigationItemSelectedListener true
            } else if (i == R.id.navigation_me) {
                switchContent(mCurrFragment, mMeFragment, MainChannel.ME.name)
                mCurrFragment = mMeFragment
                return@setOnNavigationItemSelectedListener true
            }
            false
        }
        mBookFragment = TheRouter.get(IBookProvider::class.java)?.getMainBookFragment()
        mFindFragment = TheRouter.get(IFindProvider::class.java)?.getMainFindFragment()
        mMeFragment = TheRouter.get(IMeProvider::class.java)?.getMainMeFragment()
        mCurrFragment = mBookFragment
        if (mBookFragment != null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.frame_content, mBookFragment!!, MainChannel.BOOKSHELF.name).commit()
        }
    }

    override fun initData() {}
    fun switchContent(from: Fragment?, to: Fragment?, tag: String?) {
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

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            exit()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    fun exit() {
        if (System.currentTimeMillis() - exitTime > 2000) {
            ToastUtil.showToast("再按一次退出程序")
            exitTime = System.currentTimeMillis()
        } else {
            finish()
            System.exit(0)
        }
    }
}
