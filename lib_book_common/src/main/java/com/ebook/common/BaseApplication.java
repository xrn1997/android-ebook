package com.ebook.common;

import com.ebook.common.util.log.KLog;

public class BaseApplication extends com.xrn1997.common.BaseApplication {
    @Override
    public void onCreate() {
        super.onCreate();
        KLog.init(BuildConfig.IS_DEBUG);
    }
}
