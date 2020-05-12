package debug;


import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentTransaction;

import com.alibaba.android.arouter.facade.annotation.Autowired;
import com.ebook.common.mvvm.BaseActivity;
import com.ebook.common.provider.IMeProvider;
import com.ebook.me.R;

public class MainActivity extends BaseActivity {
    @Autowired(name = "/me/main")
    IMeProvider mMeProvider;

    private Fragment mMeFragment;

    @Override
    public int onBindLayout() {
        return R.layout.activity_main;
    }

    @Override
    public void initView() {
        if (mMeProvider != null) {
            mMeFragment = mMeProvider.getMainMeFragment();
        }
        FragmentTransaction transaction = getSupportFragmentManager().beginTransaction();
        if (!mMeFragment.isAdded()) {
            transaction.add(R.id.frame_content, mMeFragment, "ME").commit();
        } else {
            transaction.show(mMeFragment).commit();
        }
    }

    @Override
    public void initData() {

    }
}
