package debug;

import android.view.KeyEvent;

import com.ebook.book.R;
import com.ebook.book.fragment.MainBookFragment;
import com.ebook.common.mvvm.BaseActivity;
import com.ebook.common.util.ToastUtil;

public class MainActivity extends BaseActivity {


    @Override
    public int onBindLayout() {
        return R.layout.activity_main;
    }

    @Override
    public void initView() {
        getSupportFragmentManager().beginTransaction().replace(R.id.frame_content, MainBookFragment.newInstance()).commit();
    }

    @Override
    public boolean enableToolbar() {
        return false;
    }

    @Override
    public void initData() {

    }
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            exit();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }
    private long exitTime = 0;

    public void exit() {
        if ((System.currentTimeMillis() - exitTime) > 2000) {
            ToastUtil.showToast("再按一次退出程序");
            exitTime = System.currentTimeMillis();
        } else {
            finish();
            System.exit(0);
        }
    }


}
