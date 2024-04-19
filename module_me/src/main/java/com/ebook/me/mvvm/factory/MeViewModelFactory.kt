package com.ebook.me.mvvm.factory

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import com.ebook.me.mvvm.model.CommentModel
import com.ebook.me.mvvm.model.ModifyModel
import com.ebook.me.mvvm.viewmodel.CommentViewModel
import com.ebook.me.mvvm.viewmodel.ModifyViewModel

object MeViewModelFactory : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
        // 通过extras获取application
        val mApplication = extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]!!
        return when (modelClass) {
            CommentViewModel::class.java -> {
                CommentViewModel(mApplication, CommentModel(mApplication))
            }

            ModifyViewModel::class.java -> {
                ModifyViewModel(mApplication, ModifyModel(mApplication))
            }

            else -> throw IllegalArgumentException("Unknown ViewModel class: " + modelClass.getName())
        } as T
    }
}
