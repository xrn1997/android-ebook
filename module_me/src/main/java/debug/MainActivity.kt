package debug

import com.ebook.common.mvvm.BaseActivity
import com.ebook.common.provider.IMeProvider
import com.ebook.me.R
import com.therouter.TheRouter

class MainActivity : BaseActivity() {
    override fun onBindLayout(): Int {
        return R.layout.activity_main
    }

    override fun initView() {
        val mMeFragment = TheRouter.get(IMeProvider::class.java)?.getMainMeFragment()
        val transaction = supportFragmentManager.beginTransaction()
        if (mMeFragment != null) {
            if (!mMeFragment.isAdded) {
                transaction.add(R.id.frame_content, mMeFragment, "ME")
            } else {
                transaction.show(mMeFragment)
            }
        }
        transaction.commit()
    }

    override fun initData() {}
}
