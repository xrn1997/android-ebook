package com.ebook.common;

import android.app.Application;

import com.alibaba.android.arouter.launcher.ARouter;
import com.ebook.common.BuildConfig;
import com.ebook.common.util.log.KLog;
import com.facebook.stetho.Stetho;

public class BaseApplication extends Application {
    private static BaseApplication mApplication;

    @Override
    public void onCreate() {
        super.onCreate();
        mApplication = this;
        KLog.init(BuildConfig.IS_DEBUG);
        Stetho.initializeWithDefaults(this);
        if (BuildConfig.IS_DEBUG) {           // 这两行必须写在init之前，否则这些配置在init过程中将无效
            ARouter.openLog();     // 打印日志
            ARouter.openDebug();   // 开启调试模式(如果在InstantRun模式下运行，必须开启调试模式！线上版本需要关闭,否则有安全风险)
        }
        ARouter.init(this); // 尽可能早，推荐在Application中初始化
    }

    public static BaseApplication getInstance() {
        return mApplication;
    }
}
