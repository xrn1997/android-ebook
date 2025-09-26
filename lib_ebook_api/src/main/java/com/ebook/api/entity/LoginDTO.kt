package com.ebook.api.entity

data class LoginDTO(
    var user: User? = null,
    var token: String? = null
)
