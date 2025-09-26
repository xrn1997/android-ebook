package com.ebook.find.provider

import androidx.fragment.app.Fragment
import com.ebook.common.provider.IFindProvider
import com.ebook.find.fragment.MainFindFragment
import com.therouter.inject.ServiceProvider

@ServiceProvider
class FindProvider : IFindProvider {
    override val mainFindFragment: Fragment = MainFindFragment.newInstance()
}
