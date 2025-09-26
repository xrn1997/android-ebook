package debug

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import com.ebook.common.event.KeyCode
import com.ebook.login.databinding.ActivityTestBinding
import com.therouter.router.Autowired
import com.therouter.router.Route
import com.xrn1997.common.mvvm.view.BaseActivity

@Route(path = KeyCode.Login.TEST_INTERRUPT_PATH, params = ["needLogin", "true"])
class TestInterruptActivity : BaseActivity<ActivityTestBinding>() {
    @Autowired
    var msg: String? = null
    private lateinit var tvMsg: TextView

    override fun initView() {
        tvMsg = binding.tvMsg
    }

    override fun initData() {
        tvMsg.text = msg
    }

    override fun onBindViewBinding(
        inflater: LayoutInflater,
        parent: ViewGroup?,
        attachToParent: Boolean
    ): ActivityTestBinding {
        return ActivityTestBinding.inflate(inflater, parent, attachToParent)
    }
}
