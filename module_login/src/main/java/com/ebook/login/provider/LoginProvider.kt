package com.ebook.login.provider


import com.ebook.api.dto.RespDTO
import com.ebook.api.entity.LoginDTO
import com.ebook.common.provider.ILoginProvider
import com.ebook.login.mvvm.model.LoginModel
import com.therouter.inject.ServiceProvider
import io.reactivex.rxjava3.core.Observable


@ServiceProvider
class LoginProvider : ILoginProvider {
    override fun login(username: String, password: String): Observable<RespDTO<LoginDTO>> {
        return LoginModel.login(username, password)
    }
}

