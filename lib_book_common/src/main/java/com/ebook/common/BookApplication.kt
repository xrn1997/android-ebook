package com.ebook.common

import com.ebook.common.util.log.klog.KLog
import com.xrn1997.common.BaseApplication

open class BookApplication : BaseApplication() {
    override fun onCreate() {
        super.onCreate()
        KLog.init(BuildConfig.IS_DEBUG)
    }
}
