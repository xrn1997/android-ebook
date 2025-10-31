package com.ebook.find.provider

import com.ebook.common.provider.IFindProvider
import com.ebook.find.fragment.MainFindFragment
import com.therouter.inject.ServiceProvider

@ServiceProvider
class FindProvider : IFindProvider {
    override val mainFindFragment: MainFindFragment
        get() = MainFindFragment.newInstance()  // 每次访问都新建
}
