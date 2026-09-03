package com.ebook.common.provider

import com.ebook.common.domain.UserSession

interface ILoginProvider {
    suspend fun login(username: String, password: String): Result<UserSession>
}