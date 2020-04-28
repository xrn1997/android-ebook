package debug;

import androidx.appcompat.app.AppCompatActivity;

import android.os.Bundle;
import android.view.View;

import com.alibaba.android.arouter.launcher.ARouter;
import com.blankj.utilcode.util.SPUtils;
import com.blankj.utilcode.util.ToastUtils;

import com.ebook.common.event.KeyCode;
import com.ebook.common.interceptor.LoginNavigationCallbackImpl;
import com.ebook.login.R;

public class MainActivity extends AppCompatActivity implements View.OnClickListener {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        initListener();
    }

    private void initListener() {
        findViewById(R.id.btn_login).setOnClickListener(this);
        findViewById(R.id.btn_register).setOnClickListener(this);
        findViewById(R.id.btn_interrupt).setOnClickListener(this);
        findViewById(R.id.btn_exit).setOnClickListener(this);
    }

    @Override
    public void onClick(View v) {
        int id = v.getId();
        if (id == R.id.btn_login) {
            ARouter.getInstance().build(KeyCode.Login.Login_PATH)
                    .navigation();
        } else if (id == R.id.btn_register) { // 注册
            ARouter.getInstance().build(KeyCode.Login.Register_PATH)
                    .withString("msg", "ARouter传递过来的不需要登录的参数msg")
                    .navigation();
        } else if (id == R.id.btn_interrupt) { // 需要登录的
            ARouter.getInstance().build(KeyCode.Login.Test_PATH)
                    .withString("msg", "ARouter传递过来的需要登录的参数msg")
                    .navigation(this, new LoginNavigationCallbackImpl());
        } else if (id == R.id.btn_exit) { // 退出登录
            ToastUtils.showShort("退出登录成功");
            SPUtils.getInstance().remove(KeyCode.Login.SP_IS_LOGIN);
        }
    }
}
