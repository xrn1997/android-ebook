package debug;

import com.ebook.api.RetrofitManager;
import com.ebook.common.BaseApplication;
import com.ebook.login.interceptor.LoginInterceptor;
import com.therouter.router.NavigatorKt;

public class LoginApplication extends BaseApplication {
    @Override
    public void onCreate() {
        super.onCreate();
        RetrofitManager.init(this);
        NavigatorKt.addRouterReplaceInterceptor(new LoginInterceptor());
    }
}
