package com.ebook.api.entity

import kotlinx.serialization.Serializable

@Serializable
data class LoginDTO(
    var user: User? = null,
    var token: String? = null
)
