package com.ebook.common;

import android.app.Application;

import com.ebook.common.util.log.KLog;
import com.facebook.stetho.Stetho;

public class BaseApplication extends Application {
    private static BaseApplication mApplication;

    public static BaseApplication getInstance() {
        return mApplication;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        mApplication = this;
        KLog.init(BuildConfig.IS_DEBUG);
        Stetho.initializeWithDefaults(this);
    }
}
