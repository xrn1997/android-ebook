package com.ebook.book.provider

import com.ebook.book.fragment.MainBookFragment
import com.ebook.common.provider.IBookProvider
import com.therouter.inject.ServiceProvider

@ServiceProvider
class BookProvider : IBookProvider {
    override val mainBookFragment = MainBookFragment.newInstance()
}
