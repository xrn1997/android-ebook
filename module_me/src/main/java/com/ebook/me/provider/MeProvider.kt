package com.ebook.me.provider

import com.ebook.common.provider.IMeProvider
import com.ebook.me.fragment.MainMeFragment
import com.therouter.inject.ServiceProvider


@ServiceProvider
class MeProvider : IMeProvider {
    override val mainMeFragment: MainMeFragment
        get() = MainMeFragment.newInstance()  // 每次访问都新建
}


