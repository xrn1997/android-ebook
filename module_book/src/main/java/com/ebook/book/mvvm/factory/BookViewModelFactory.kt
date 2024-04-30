package com.ebook.book.mvvm.factory

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import com.ebook.book.mvvm.model.BookCommentsModel
import com.ebook.book.mvvm.model.BookListModel
import com.ebook.book.mvvm.viewmodel.BookCommentsViewModel
import com.ebook.book.mvvm.viewmodel.BookListViewModel

object BookViewModelFactory : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
        // 通过extras获取application
        val mApplication = extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]!!
        return when (modelClass) {
            BookListViewModel::class.java -> {
                BookListViewModel(mApplication, BookListModel(mApplication))
            }

            BookCommentsViewModel::class.java -> {
                BookCommentsViewModel(mApplication, BookCommentsModel(mApplication))
            }

            else -> throw IllegalArgumentException("Unknown ViewModel class: " + modelClass.getName())
        } as T
    }
}
