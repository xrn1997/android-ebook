package debug

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.activity.addCallback
import com.ebook.book.R
import com.ebook.book.databinding.ActivityMainBinding
import com.ebook.book.fragment.MainBookFragment.Companion.newInstance
import com.xrn1997.common.mvvm.view.BaseActivity
import com.xrn1997.common.util.ToastUtil.showShort
import dagger.hilt.android.AndroidEntryPoint
import kotlin.system.exitProcess

@AndroidEntryPoint
class MainActivity : BaseActivity<ActivityMainBinding>() {
    private var exitTime: Long = 0


    override fun initView() {
        supportFragmentManager.beginTransaction()
            .replace(R.id.frame_content, newInstance()).commit()
        onBackPressedDispatcher.addCallback(this) {
            exit()
        }
    }

    override fun enableToolbar(): Boolean {
        return false
    }

    override fun initData() {
    }

    private fun exit() {
        if ((System.currentTimeMillis() - exitTime) > 2000) {
            showShort(this, "再按一次退出程序")
            exitTime = System.currentTimeMillis()
        } else {
            finish()
            exitProcess(0)
        }
    }


    override fun onBindViewBinding(
        inflater: LayoutInflater,
        parent: ViewGroup?,
        attachToParent: Boolean
    ): ActivityMainBinding {
        return ActivityMainBinding.inflate(inflater, parent, attachToParent)
    }
}
