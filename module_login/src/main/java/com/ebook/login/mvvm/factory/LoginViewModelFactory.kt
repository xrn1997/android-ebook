package com.ebook.login.mvvm.factory

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import com.ebook.login.mvvm.model.LoginModel
import com.ebook.login.mvvm.model.ModifyPwdModel
import com.ebook.login.mvvm.model.RegisterModel
import com.ebook.login.mvvm.viewmodel.LoginViewModel
import com.ebook.login.mvvm.viewmodel.ModifyPwdViewModel
import com.ebook.login.mvvm.viewmodel.RegisterViewModel

object LoginViewModelFactory : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
        // 通过extras获取application
        val mApplication = extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]!!
        return when (modelClass) {
            LoginViewModel::class.java -> {
                LoginViewModel(mApplication, LoginModel(mApplication))
            }

            RegisterViewModel::class.java -> {
                RegisterViewModel(mApplication, RegisterModel(mApplication))
            }

            ModifyPwdViewModel::class.java -> {
                ModifyPwdViewModel(mApplication, ModifyPwdModel(mApplication))
            }

            else -> throw IllegalArgumentException("Unknown ViewModel class: " + modelClass.getName())
        } as T
    }
}
