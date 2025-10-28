package com.ebook.common.provider

import com.ebook.api.entity.LoginDTO
import com.xrn1997.common.dto.RespDTO

interface ILoginProvider {
    suspend fun login(username: String, password: String): Result<RespDTO<LoginDTO>>
}