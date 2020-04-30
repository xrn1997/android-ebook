package debug;

import android.content.Intent;

import com.ebook.api.RetrofitManager;
import com.ebook.common.BaseApplication;
import com.ebook.db.GreenDaoManager;


public class FindApplication extends BaseApplication {
    @Override
    public void onCreate() {
        super.onCreate();
        RetrofitManager.init(this);
        GreenDaoManager.init(this);
    }
}
