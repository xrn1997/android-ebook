package debug


import android.view.LayoutInflater
import android.view.ViewGroup
import com.ebook.common.provider.IMeProvider
import com.ebook.me.R
import com.ebook.me.databinding.ActivityMainBinding
import com.therouter.TheRouter
import com.xrn1997.common.mvvm.view.BaseActivity

class MainActivity : BaseActivity<ActivityMainBinding>() {
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
    override fun onBindViewBinding(
        inflater: LayoutInflater,
        container: ViewGroup?,
        attachToParent: Boolean
    ): ActivityMainBinding {
        return ActivityMainBinding.inflate(inflater, container, attachToParent)
    }
}
