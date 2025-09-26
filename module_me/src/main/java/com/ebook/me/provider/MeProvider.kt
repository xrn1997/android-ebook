package com.ebook.me.provider

import androidx.fragment.app.Fragment
import com.ebook.common.provider.IMeProvider
import com.ebook.me.fragment.MainMeFragment
import com.therouter.inject.ServiceProvider


@ServiceProvider
class MeProvider : IMeProvider {
    override val mainMeFragment: Fragment = MainMeFragment.newInstance()
}


