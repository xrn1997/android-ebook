package debug

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.ebook.common.event.KeyCode
import com.therouter.TheRouter
import com.therouter.router.Autowired
import com.therouter.router.Route
import com.xrn1997.common.ui.theme.MyApplicationTheme
import com.xrn1997.common.util.setStatusBarColor

/**
 * 路由拦截测试页：携带 needLogin=true 参数，经 [com.ebook.common.interceptor.LoginInterceptor]
 * 未登录时会被重定向到登录页，用于单模块调试 TheRouter 拦截链路。
 *
 * 与 [MainActivity] 相同，独立宿主必须自行包裹 [MyApplicationTheme] 并开启 edge-to-edge，
 * 对齐集成宿主 BaseActivity 的主题与沉浸式行为。
 */
@Route(path = KeyCode.Login.TEST_INTERRUPT_PATH, params = ["needLogin", "true"])
class TestInterruptActivity : ComponentActivity() {
    @Autowired //只支持String和八重基本数据类型，其他的需要自定义解析规则。
    var msg: String? = null

    @Autowired(name = "key")
    var key: Int = -1

    public override fun onCreate(savedInstanceState: Bundle?) {
        TheRouter.inject(this)
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setStatusBarColor()
        // 拦截参数拼装成展示文本（@Autowired 注入的 msg/key + bundle 中的布尔标记）
        val test = intent?.extras?.getBoolean("123", false) ?: false
        val displayText = "$msg $key $test"
        setContent {
            MyApplicationTheme {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = displayText,
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            }
        }
    }
}
