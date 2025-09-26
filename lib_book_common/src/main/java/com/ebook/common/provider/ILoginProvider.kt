package com.ebook.common.provider

import com.ebook.api.dto.RespDTO
import com.ebook.api.entity.LoginDTO
import io.reactivex.rxjava3.core.Observable

interface ILoginProvider {
    fun login(username: String, password: String): Observable<RespDTO<LoginDTO>>
}