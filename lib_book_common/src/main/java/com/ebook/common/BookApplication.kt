package com.ebook.common

import com.xrn1997.common.BaseApplication
import com.xrn1997.common.ui.theme.AppTheme
import com.xrn1997.common.ui.theme.MyApplicationTheme

open class BookApplication : BaseApplication() {
    override fun onCreate() {
        super.onCreate()
        // 主题装配点接入（lib_common 侧 AppTheme 装配约定）：装配默认 MyApplicationTheme（深浅色 + Android 12+ 动态取色）。
        // 品牌色策略待产品决策——将来固定品牌色只需替换此 lambda（dynamicColor=false 或自定义色板），页面零改动
        AppTheme.install { content ->
            MyApplicationTheme(content = content)
        }
    }
}
