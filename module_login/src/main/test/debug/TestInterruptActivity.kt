package debug

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import com.ebook.common.event.KeyCode
import com.ebook.login.databinding.ActivityTestBinding
import com.therouter.TheRouter
import com.therouter.router.Autowired
import com.therouter.router.Route
import com.xrn1997.common.mvvm.view.BaseActivity

@Route(path = KeyCode.Login.TEST_INTERRUPT_PATH, params = ["needLogin", "true"])
class TestInterruptActivity : BaseActivity<ActivityTestBinding>() {
    @Autowired //只支持String和八重基本数据类型，其他的需要自定义解析规则。
    var msg: String? = null

    @Autowired(name = "key")
    var key: Int = -1

    private lateinit var tvMsg: TextView

    public override fun onCreate(savedInstanceState: Bundle?) {
        TheRouter.inject(this)
        super.onCreate(savedInstanceState)
    }

    override fun initView() {
        tvMsg = binding.tvMsg
    }

    @SuppressLint("SetTextI18n")
    override fun initData() {
        val test = intent?.extras?.getBoolean("123", false) ?: false
        tvMsg.text = "$msg $key $test"
    }

    override fun onBindViewBinding(
        inflater: LayoutInflater,
        parent: ViewGroup?,
        attachToParent: Boolean
    ): ActivityTestBinding {
        return ActivityTestBinding.inflate(inflater, parent, attachToParent)
    }
}
